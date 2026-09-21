package dev.aiws.aichat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 封装（与加载器无关，纯 JDK java.net.http + 守护线程）。
 *
 * 行为规格（对齐 Fabric 端 1.0.0-r3）：
 * - 后台守护线程拨号；断线重连：2 秒起步指数退避、上限 30 秒；某次连接存活超过 10 秒则退避重置回 2 秒；
 * - 未连接时 send 进有界队列（64 条，满丢最旧），连上后自动补发；
 * - 所有 sendText 通过 CompletableFuture 链串行化；
 * - 接收端拼接文本分片，超 1 MiB 丢弃缓冲防炸；onClose/onError 记录原因；
 * - updateUrl：地址变化时 abort 当前连接，让线程用新地址重连；
 * - 回调全在后台线程：任何和 Minecraft 相关的动作都必须由消费方挪回客户端 tick 里做（队列交接）。
 */
public final class AiBridgeClient {

    public interface Listener {
        /** 连接建立（后台线程） */
        void onOpen();

        /** 一条完整文本消息到达（后台线程） */
        void onMessage(String text);

        /** 连接态变化（后台线程）。true=刚连上 */
        void onConnectedChanged(boolean connected);
    }

    private static final int OUTBOX_LIMIT = 64;
    private static final long INITIAL_BACKOFF_MS = 2_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;
    private static final long STABLE_CONNECTION_MS = 10_000L;
    /** 接收分片拼接缓冲上限：1 MiB，超出直接丢弃防炸 */
    private static final int MAX_TEXT_BUFFER = 1024 * 1024;

    private final Listener listener;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    private volatile String url;
    /** false＝手动断开/离服暂停，线程不再拨号 */
    private volatile boolean enabled = true;
    private volatile WebSocket socket;
    private volatile boolean connected;

    private final Object outboxLock = new Object();
    private final ArrayDeque<String> outbox = new ArrayDeque<>();

    private final Object sendLock = new Object();
    private CompletableFuture<Void> sendTail = CompletableFuture.completedFuture(null);

    private final Object wakeLock = new Object();
    /** url/启停变化时自增：唤醒 worker 线程立刻重拨 */
    private volatile int generation;

    private Thread worker;
    private long backoffMs = INITIAL_BACKOFF_MS;

    public AiBridgeClient(String url, Listener listener) {
        this.url = url == null ? "" : url;
        this.listener = listener;
    }

    public synchronized void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(this::runLoop, "ai-chat-websocket-io");
        worker.setDaemon(true);
        worker.start();
    }

    public boolean isConnected() {
        return connected;
    }

    /** 已连接，或已启用等待/正在重连（转发前置检查用） */
    public boolean isBusy() {
        return connected || (enabled && !url.isBlank());
    }

    public String url() {
        return url;
    }

    /** 发送一条文本：未连接则进有界补发队列（满丢最旧） */
    public void send(String json) {
        if (connected && socket != null) {
            enqueueSend(json);
            return;
        }
        synchronized (outboxLock) {
            if (outbox.size() >= OUTBOX_LIMIT) {
                outbox.pollFirst();
            }
            outbox.addLast(json);
        }
    }

    /** 地址变化时 abort 当前连接，让线程用新地址重连 */
    public void updateUrl(String newUrl) {
        String n = newUrl == null ? "" : newUrl;
        if (n.equals(url)) {
            return;
        }
        url = n;
        backoffMs = INITIAL_BACKOFF_MS;
        bump();
        abortCurrent();
    }

    public void setEnabled(boolean value) {
        enabled = value;
        bump();
        if (!value) {
            abortCurrent();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void bump() {
        synchronized (wakeLock) {
            generation++;
            wakeLock.notifyAll();
        }
    }

    private void abortCurrent() {
        WebSocket ws = socket;
        socket = null;
        connected = false;
        if (ws != null) {
            try {
                ws.abort();
            } catch (Exception ignored) {
                // 尽力而为
            }
        }
    }

    /** 所有 sendText 经此链串行化，避免 JDK WebSocket 并发写异常 */
    private void enqueueSend(String text) {
        synchronized (sendLock) {
            sendTail = sendTail.handle((v, t) -> (Void) null).thenRunAsync(() -> doSend(text));
        }
    }

    private void doSend(String text) {
        WebSocket ws = socket;
        if (ws == null || !connected) {
            return;
        }
        try {
            ws.sendText(text, true).get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            AiChatWebsocket.LOGGER.debug("[ai_chat_websocket] send failed: {}", e.toString());
        }
    }

    private void runLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                int gen;
                String target;
                synchronized (wakeLock) {
                    while (!enabled || url.isBlank()) {
                        wakeLock.wait(1_000L);
                    }
                    gen = generation;
                    target = url;
                }

                Handler handler = new Handler();
                CompletableFuture<WebSocket> future;
                try {
                    future = http.newWebSocketBuilder()
                            .connectTimeout(Duration.ofSeconds(6))
                            .buildAsync(URI.create(target), handler);
                } catch (Exception badUri) {
                    AiChatWebsocket.LOGGER.warn("[ai_chat_websocket] bad url {}: {}", target, badUri.toString());
                    sleepBackoff(gen);
                    continue;
                }

                WebSocket ws;
                try {
                    ws = future.get(12, TimeUnit.SECONDS);
                } catch (Exception dialError) {
                    if (gen != generation) {
                        continue; // 地址/开关变了，立刻按新状态重来
                    }
                    AiChatWebsocket.LOGGER.debug("[ai_chat_websocket] dial failed: {}", dialError.toString());
                    listener.onConnectedChanged(false);
                    sleepBackoff(gen);
                    continue;
                }

                if (gen != generation || !enabled) {
                    try {
                        ws.abort();
                    } catch (Exception ignored) {
                    }
                    continue;
                }

                long openedAt = System.currentTimeMillis();
                socket = ws;
                connected = true;
                backoffMs = INITIAL_BACKOFF_MS;
                AiChatWebsocket.LOGGER.info("[ai_chat_websocket] connected to {}", target);
                listener.onConnectedChanged(true);
                listener.onOpen();
                flushOutbox();

                handler.closed.await(); // 阻塞直到 onClose/onError

                long alive = System.currentTimeMillis() - openedAt;
                if (alive >= STABLE_CONNECTION_MS) {
                    backoffMs = INITIAL_BACKOFF_MS; // 存活超 10 秒：退避重置
                }
                connected = false;
                socket = null;
                AiChatWebsocket.LOGGER.info("[ai_chat_websocket] disconnected: {}", handler.closeReason());
                listener.onConnectedChanged(false);
                if (gen == generation && enabled) {
                    sleepBackoff(gen);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                AiChatWebsocket.LOGGER.warn("[ai_chat_websocket] io loop error", e);
                connected = false;
                socket = null;
                try {
                    sleepBackoff(generation);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void sleepBackoff(int genAtStart) throws InterruptedException {
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        synchronized (wakeLock) {
            long until = System.currentTimeMillis() + delay;
            while (generation == genAtStart && enabled && !url.isBlank()) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                wakeLock.wait(left);
            }
        }
    }

    private void flushOutbox() {
        String[] batch;
        synchronized (outboxLock) {
            if (outbox.isEmpty()) {
                return;
            }
            batch = outbox.toArray(new String[0]);
            outbox.clear();
        }
        for (String s : batch) {
            enqueueSend(s);
        }
    }

    /** 接收端：拼接文本分片、超长丢弃、关闭/错误记录原因 */
    private final class Handler implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();
        final CountDownLatch closed = new CountDownLatch(1);
        private volatile String reason = "";

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (buf.length() > MAX_TEXT_BUFFER) {
                buf.setLength(0); // 防炸：丢弃当前缓冲
            } else {
                buf.append(data);
                if (last) {
                    String s = buf.toString();
                    buf.setLength(0);
                    if (!s.isBlank()) {
                        listener.onMessage(s);
                    }
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reasonText) {
            reason = "closed code=" + statusCode + (reasonText == null || reasonText.isEmpty() ? "" : " reason=" + reasonText);
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            reason = "error " + error.getClass().getSimpleName() + ": " + error.getMessage();
            closed.countDown();
        }

        String closeReason() {
            return reason.isEmpty() ? "connection ended" : reason;
        }
    }
}
