package net.aichat.websocket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDK {@link java.net.http.WebSocket} 封装（与加载器无关）：
 * <ul>
 *   <li>后台守护线程拨号；断线重连：2 秒起步指数退避、上限 30 秒；某次连接存活超过 10 秒则退避重置回 2 秒；</li>
 *   <li>未连接时 {@link #send} 进有界队列（64 条，满丢最旧），连上后自动补发；</li>
 *   <li>所有 sendText 用 CompletableFuture 链串行化；</li>
 *   <li>接收端拼接文本分片，超 1 MiB 丢弃缓冲防炸；onClose/onError 记录原因；</li>
 *   <li>{@link #updateUrl} 地址变化时 abort 当前连接，用新地址重连。</li>
 * </ul>
 * <b>所有回调都在后台线程触发</b>：任何和 Minecraft 相关的动作都必须由调用方挪回客户端 tick 里做（队列交接）。
 */
public final class AiBridgeClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("ai_chat_websocket");

    private static final int MAX_QUEUED_SENDS = 64;
    private static final long INITIAL_BACKOFF_MS = 2_000;
    private static final long MAX_BACKOFF_MS = 30_000;
    private static final long STABLE_CONNECTION_MS = 10_000;
    private static final long CONNECT_TIMEOUT_MS = 10_000;
    private static final int MAX_INBOUND_BYTES = 1024 * 1024; // 1 MiB

    private final HttpClient http = HttpClient.newHttpClient();
    private final Object lock = new Object();
    private final Consumer<String> onMessage;
    private final Consumer<String> onStatus;

    private volatile String url;
    private volatile WebSocket socket;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean enabled;
    private boolean stopped;

    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);
    private long backoffMs = INITIAL_BACKOFF_MS;
    private Thread worker;

    /** 连续失败时只在第一次失败播报，避免服务未启动时刷屏。 */
    private int failureStreak;

    public AiBridgeClient(String url, Consumer<String> onMessage, Consumer<String> onStatus) {
        this.url = url;
        this.onMessage = onMessage;
        this.onStatus = onStatus;
    }

    /** 启动后台线程（幂等）。 */
    public void start() {
        synchronized (lock) {
            if (worker != null) {
                return;
            }
            worker = new Thread(this::runLoop, "ai-chat-ws");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /** 主动连接（/aiws connect 与 autoConnect）。 */
    public void connectNow() {
        synchronized (lock) {
            enabled = true;
            backoffMs = INITIAL_BACKOFF_MS;
            lock.notifyAll();
        }
    }

    /** 主动断开并停止重连（/aiws disconnect）。 */
    public void disconnectNow() {
        synchronized (lock) {
            enabled = false;
        }
        abortCurrent("disconnected by user");
    }

    public boolean isConnected() {
        return connected;
    }

    /** 已启用但尚未连上（拨号中或退避等待中）。 */
    public boolean isConnecting() {
        return !connected && enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getUrl() {
        return url;
    }

    /** 地址变化时 abort 当前连接，让线程用新地址重连。 */
    public void updateUrl(String newUrl) {
        if (newUrl == null || newUrl.equals(url)) {
            return;
        }
        url = newUrl;
        abortCurrent("url changed");
    }

    /** 连接时关闭当前 socket，唤醒循环。 */
    public void shutdown() {
        synchronized (lock) {
            stopped = true;
            enabled = false;
            lock.notifyAll();
        }
        abortCurrent("shutting down");
    }

    /** 已连接直接发；未连接进有界队列（满丢最旧），连上后补发。 */
    public void send(String text) {
        synchronized (lock) {
            if (connected && socket != null) {
                scheduleSend(text);
            } else {
                if (pending.size() >= MAX_QUEUED_SENDS) {
                    pending.pollFirst();
                }
                pending.addLast(text);
            }
        }
    }

    /** 所有发送走同一条 CompletableFuture 链，保证顺序且不并发写 socket。 */
    private void scheduleSend(String text) {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        sendChain = sendChain
                .thenCompose(v -> ws.sendText(text, true).toCompletableFuture().thenApply(w -> (Void) null))
                .exceptionally(t -> {
                    LOGGER.debug("ws sendText failed: {}", t.toString());
                    return null;
                });
    }

    private void abortCurrent(String reason) {
        WebSocket ws = socket;
        if (ws != null) {
            try {
                ws.abort();
            } catch (Exception ignored) {
                // abort 失败无所谓，循环会自行感知连接断开
            }
        }
        LOGGER.debug("ws aborted: {}", reason);
    }

    private void runLoop() {
        while (true) {
            try {
                synchronized (lock) {
                    while (!enabled && !stopped) {
                        lock.wait(1_000);
                    }
                    if (stopped) {
                        return;
                    }
                }
                connecting = true;
                String target = url;
                Listener listener = new Listener();
                WebSocket ws = http.newWebSocketBuilder()
                        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                        .buildAsync(URI.create(target), listener)
                        .join();
                socket = ws;
                connected = true;
                connecting = false;
                failureStreak = 0;
                long openedAt = System.currentTimeMillis();
                LOGGER.info("AI WebSocket connected: {}", target);
                onStatus.accept("已连接到 " + target);
                // 补发断线期间排队的消息
                synchronized (lock) {
                    String queued;
                    while ((queued = pending.pollFirst()) != null) {
                        scheduleSend(queued);
                    }
                }
                // 阻塞直到 onClose/onError
                listener.closure.join();
                connected = false;
                socket = null;
                long life = System.currentTimeMillis() - openedAt;
                synchronized (lock) {
                    if (life > STABLE_CONNECTION_MS) {
                        backoffMs = INITIAL_BACKOFF_MS;
                    } else {
                        backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
                    }
                }
                if (!enabled) {
                    continue;
                }
                String cause = listener.reason();
                LOGGER.info("AI WebSocket closed ({}), retry in {} ms", cause, backoffMs);
                onStatus.accept("连接断开（" + cause + "），" + backoffMs / 1000 + " 秒后重连");
                sleepBackoff();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                connected = false;
                connecting = false;
                socket = null;
                String cause = rootMessage(e);
                long now = System.currentTimeMillis();
                synchronized (lock) {
                    backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
                }
                failureStreak++;
                if (failureStreak <= 1) {
                    LOGGER.warn("AI WebSocket connect failed: {}", cause);
                    onStatus.accept("连接失败：" + cause + "，将自动重试");
                } else {
                    LOGGER.debug("AI WebSocket connect failed again: {}", cause);
                }
                sleepBackoff();
            }
        }
    }

    /** 退避等待，但可被 connectNow/disconnectNow/updateUrl/shutdown 立即唤醒。 */
    private void sleepBackoff() {
        synchronized (lock) {
            long until = System.currentTimeMillis() + backoffMs;
            while (!stopped && enabled) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) {
                    return;
                }
                try {
                    lock.wait(Math.min(left, 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null || m.isBlank() ? cur.getClass().getSimpleName() : m;
    }

    /** 接收端 listener：拼接分片、超 1 MiB 丢弃整条、记录关闭原因。 */
    private final class Listener implements WebSocket.Listener {
        private final CompletableFuture<Void> closure = new CompletableFuture<>();
        private final StringBuilder buffer = new StringBuilder();
        private boolean discarding;
        private volatile String closeReason;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (!discarding) {
                buffer.append(data);
                if (buffer.length() > MAX_INBOUND_BYTES) {
                    buffer.setLength(0);
                    discarding = true;
                    LOGGER.warn("AI WebSocket inbound message exceeds 1 MiB, dropping");
                }
            }
            if (last) {
                if (!discarding && buffer.length() > 0) {
                    String message = buffer.toString();
                    onMessage.accept(message);
                }
                buffer.setLength(0);
                discarding = false;
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeReason = "code " + statusCode + (reason == null || reason.isBlank() ? "" : " " + reason);
            closure.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeReason = rootMessage(error);
            closure.complete(null);
        }

        String reason() {
            return closeReason == null ? "connection lost" : closeReason;
        }
    }
}
