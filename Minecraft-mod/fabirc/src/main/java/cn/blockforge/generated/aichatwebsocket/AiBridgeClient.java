package cn.blockforge.generated.aichatwebsocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 基于 JDK 自带 java.net.http.WebSocket 的客户端封装：
 * 后台线程负责拨号与断线重连（2 秒起步指数退避，最长 30 秒），
 * 未连上时发消息会先进一个有界队列，连上后自动补发。
 * 所有回调都在后台线程触发，使用者需要自己把内容转回游戏主线程。
 */
public final class AiBridgeClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("ai_chat_websocket/ws");
    private static final int MAX_PENDING = 64;
    private static final long MAX_TEXT_CHARS = 1_048_576L;

    /** 连接状态回调，全部在后台线程调用 */
    public interface StatusListener {
        void onStatus(String text);

        void onOpen();

        void onClose(String reason);
    }

    private final Consumer<String> onMessage;
    private final StatusListener status;

    private final Object lock = new Object();
    private final Deque<String> pending = new ArrayDeque<>();
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    private Thread worker;
    private WebSocket socket;
    private volatile boolean open;
    private volatile boolean wantConnect;
    private String url;

    public AiBridgeClient(String url, Consumer<String> onMessage, StatusListener status) {
        this.url = url;
        this.onMessage = onMessage;
        this.status = status;
    }

    public boolean isConnected() {
        return open;
    }

    public boolean wantsConnection() {
        return wantConnect;
    }

    /** 更换地址：若正在连接则掐断当前连接，让后台线程用新地址重连 */
    public void updateUrl(String newUrl) {
        WebSocket ws;
        boolean changed;
        synchronized (lock) {
            changed = !newUrl.equals(url);
            if (changed) {
                url = newUrl;
            }
            ws = socket;
        }
        if (changed && ws != null) {
            ws.abort();
        }
    }

    public void start() {
        Thread t;
        synchronized (lock) {
            wantConnect = true;
            t = worker;
            if (t == null) {
                t = new Thread(this::runLoop, "ai-ws-client");
                t.setDaemon(true);
                worker = t;
                t.start();
            }
            lock.notifyAll();
        }
    }

    public void stop() {
        WebSocket ws;
        synchronized (lock) {
            wantConnect = false;
            ws = socket;
            lock.notifyAll();
        }
        if (ws != null) {
            ws.abort();
        }
    }

    /** 发送一条文本；未连接时先进队列（满了丢最旧的） */
    public void send(String text) {
        synchronized (lock) {
            if (!open || socket == null) {
                if (pending.size() >= MAX_PENDING) {
                    pending.pollFirst();
                }
                pending.addLast(text);
                return;
            }
            enqueueSend(socket, text);
        }
    }

    /** 必须在持有 lock 时调用：用 future 链把所有 sendText 串行化 */
    private void enqueueSend(WebSocket ws, String text) {
        sendChain = sendChain
                .thenCompose(v -> ws.sendText(text, true))
                .whenComplete((w, err) -> {
                    if (err != null) {
                        LOGGER.warn("发送失败：{}", err.toString());
                    }
                });
    }

    private void runLoop() {
        long backoffMs = 2_000L;
        while (wantConnect) {
            String target;
            synchronized (lock) {
                target = url;
            }
            ConnListener listener = new ConnListener();
            WebSocket ws = null;
            CompletableFuture<WebSocket> future = null;
            try {
                HttpClient http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(8))
                        .build();
                future = http.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(8))
                        .buildAsync(URI.create(target), listener);
                ws = future.get(15, TimeUnit.SECONDS);
                long openedAt;
                synchronized (lock) {
                    socket = ws;
                    open = true;
                    openedAt = System.currentTimeMillis();
                    while (!pending.isEmpty()) {
                        enqueueSend(ws, pending.pollFirst());
                    }
                }
                status.onOpen();
                LOGGER.info("已连接到 {}", target);
                listener.closed.join();
                long upMs = System.currentTimeMillis() - openedAt;
                backoffMs = upMs > 10_000L ? 2_000L : Math.min(backoffMs * 2, 30_000L);
                status.onClose(listener.closeReason.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.TimeoutException e) {
                if (future != null) {
                    future.cancel(true);
                }
                status.onStatus("连接超时：" + target);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                status.onStatus("连接失败：" + cause.getClass().getSimpleName()
                        + (cause.getMessage() == null ? "" : "（" + cause.getMessage() + "）"));
            } finally {
                open = false;
                synchronized (lock) {
                    if (ws != null && socket == ws) {
                        socket = null;
                    }
                }
                if (ws != null) {
                    try {
                        ws.abort();
                    } catch (Exception ignored) {
                        // 已经关了
                    }
                }
            }
            if (!wantConnect) {
                break;
            }
            try {
                synchronized (lock) {
                    if (wantConnect) {
                        lock.wait(backoffMs);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        synchronized (lock) {
            worker = null;
            socket = null;
            open = false;
            pending.clear();
        }
    }

    /** 每条连接一个监听器实例；文本分片在 buf 里拼接 */
    private final class ConnListener implements WebSocket.Listener {
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        final AtomicReference<String> closeReason = new AtomicReference<>("对方关闭了连接");
        private final StringBuilder buf = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (buf.length() > MAX_TEXT_CHARS) {
                LOGGER.warn("收到超长消息（>{} 字符），丢弃缓冲", MAX_TEXT_CHARS);
                buf.setLength(0);
                webSocket.request(1);
                return null;
            }
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                if (!msg.isBlank()) {
                    try {
                        onMessage.accept(msg);
                    } catch (Exception e) {
                        LOGGER.warn("处理消息出错：{}", e.toString());
                    }
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeReason.set("code " + statusCode
                    + (reason == null || reason.isBlank() ? "" : "（" + reason + "）"));
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeReason.set(error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : "：" + error.getMessage()));
            closed.complete(null);
        }
    }
}
