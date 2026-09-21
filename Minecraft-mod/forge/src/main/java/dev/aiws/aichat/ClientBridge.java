package dev.aiws.aichat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 客户端初始化 + 事件接线 + /aiws 命令（仅客户端；主类已用 Dist.CLIENT 把关）。
 * 行为逐条对齐 Fabric 端 1.0.0-r3（见移植简报 §3）。
 */
public final class ClientBridge {

    private static final int REPLY_QUEUE_LIMIT = 32;
    private static final int NOTICE_QUEUE_LIMIT = 64;
    private static final int GUARD_LIMIT = 16;
    private static final int SEEN_LIMIT = 16;
    private static final long SEEN_WINDOW_MS = 5_000L;
    private static final int SEEN_MIN_LEN = 4;

    /** 本地私聊命令：/msg|w|tell|whisper|pm <目标> <内容> */
    private static final Pattern PRIVATE_MSG =
            Pattern.compile("^/(?:msg|w|tell|whisper|pm)\\s+(\\S+)\\s+(\\S[\\s\\S]*)$", Pattern.CASE_INSENSITIVE);

    private final Minecraft mc = Minecraft.getInstance();
    private final Gson gson = new Gson();
    private final Path configFile;
    private volatile AiConfig cfg;
    private final AiBridgeClient bridge;

    /** 后台线程 → 客户端 tick 的交接队列（所有 Minecraft 动作都回 tick 做） */
    private final ConcurrentLinkedQueue<Runnable> tickTasks = new ConcurrentLinkedQueue<>();

    private final Object replyLock = new Object();
    private final ArrayDeque<String> replyQueue = new ArrayDeque<>();

    private final Object noticeLock = new Object();
    private final ArrayDeque<String> noticeQueue = new ArrayDeque<>();

    private final Object seenLock = new Object();
    private final ArrayDeque<Seen> seen = new ArrayDeque<>();

    private final Object guardLock = new Object();
    private final ArrayDeque<String> guard = new ArrayDeque<>();

    private long lastReplyAtMs;
    private volatile boolean wsWasConnected;
    /** TrChat 首次提示：每次进服（LoggingIn）重置 */
    private volatile boolean trchatFirstHintShown;
    /** auto 档：本服是否真的检测到过 TrChat 行 */
    private volatile boolean trchatDetected;

    private record Seen(String text, long time) {
    }

    private ClientBridge(AiConfig config, Path file) {
        this.cfg = config;
        this.configFile = file;
        this.bridge = new AiBridgeClient(AiConfig.wsUrl(config.url), new AiBridgeClient.Listener() {
            @Override
            public void onOpen() {
                // 连上：回 tick 里发握手（需要读玩家状态）
                tickTasks.add(ClientBridge.this::sendHello);
            }

            @Override
            public void onMessage(String text) {
                handleIncoming(text); // 后台线程只做解析入队
            }

            @Override
            public void onConnectedChanged(boolean connectedNow) {
                // 状态跃变才提示：断网/服务未起时不刷屏（验收 §5.5）
                if (connectedNow) {
                    if (!wsWasConnected) {
                        wsWasConnected = true;
                        notice("已连接 " + bridge.url());
                    }
                } else if (wsWasConnected) {
                    wsWasConnected = false;
                    notice("连接断开，自动重连中…");
                }
            }
        });
        this.bridge.setEnabled(config.autoConnect);
        this.bridge.start();
    }

    public static ClientBridge create(AiConfig config, Path file) {
        return new ClientBridge(config, file);
    }

    // ------------------------------------------------------------------
    // tick：队列交接
    // ------------------------------------------------------------------

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return; // 1.20.1 的 ClientTickEvent 是 START/END 两段式，只在 END 跑
        }
        Runnable task;
        int budget = 16;
        while (budget-- > 0 && (task = tickTasks.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                AiChatWebsocket.LOGGER.warn("[ai_chat_websocket] tick task failed", e);
            }
        }
        drainNotices();
        drainReplies();
    }

    private void drainNotices() {
        String msg;
        while ((msg = pollNotice()) != null) {
            showLocal(noticeComponent(msg));
        }
    }

    private void drainReplies() {
        String reply;
        synchronized (replyLock) {
            if (replyQueue.isEmpty()) {
                return;
            }
            long intervalMs = Math.max(0, cfg.replyMinIntervalTicks) * 50L;
            long now = System.currentTimeMillis();
            if (now - lastReplyAtMs < intervalMs) {
                return; // 限速：不足间隔不出队
            }
            reply = replyQueue.pollFirst();
            lastReplyAtMs = now;
        }
        deliverReply(reply);
    }

    // ------------------------------------------------------------------
    // 收聊天 → 转发 AI（简报 §3.1、§3.2）
    // ------------------------------------------------------------------

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!bridge.isBusy()) {
            return; // 仅当已连接或正在重连
        }
        try {
            if (event.isSystem()) {
                handleTrChatLine(event.getMessage());
            } else {
                handleSignedChat(event);
            }
        } catch (Exception e) {
            AiChatWebsocket.LOGGER.warn("[ai_chat_websocket] receive handler error", e);
        }
    }

    /** 签名聊天（原版玩家消息） */
    private void handleSignedChat(ClientChatReceivedEvent event) {
        UUID sender = event.getSender();
        LocalPlayer self = mc.player;
        if (sender != null && !Util.NIL_UUID.equals(sender) && sender.equals(self.getUUID())) {
            return; // 自己说的话走本地发送事件
        }
        Component comp = event.getMessage();
        String decorated = normalize(comp.getString());
        String body = normalize(extractBody(comp, decorated));
        if (body.isEmpty()) {
            return;
        }
        String name = sender != null && !Util.NIL_UUID.equals(sender) ? nameOfPlayer(sender) : null;
        if (name == null) {
            name = nameFromDecorated(decorated);
        }
        if (name == null) {
            name = "";
        }
        if (cfg.ignoreAiNameMessages && !name.isEmpty() && name.equalsIgnoreCase(cfg.aiName)) {
            return; // 防别的 AI 客户端回声
        }
        boolean mentioned = containsMention(body);
        if (cfg.mentionOnly && !mentioned) {
            return;
        }
        rememberSeen(decorated);
        rememberSeen(body);
        sendChatJson(name, sender == null || Util.NIL_UUID.equals(sender) ? "" : sender.toString(),
                body, mentioned, false, false, null);
    }

    /** 系统消息 → TrChat 兼容（插件取消原版广播后以系统消息重发 `[频道] 名字: 内容`） */
    private void handleTrChatLine(Component comp) {
        String mode = cfg.trchatMode;
        if ("off".equals(mode)) {
            return;
        }
        String raw = normalize(comp.getString());
        if (raw.isEmpty()) {
            return;
        }
        Matcher m = cfg.trchatPattern.matcher(raw);
        if (m.matches()) {
            String channel = m.group(1) == null || m.group(1).isBlank() ? null : m.group(1).trim();
            String name = m.group(2);
            String body = normalize(m.group(3));
            if (body.isEmpty()) {
                return;
            }
            if (isDuplicate(raw)) {
                return; // 与 5 秒内已处理原文互为包含 → 重复副本
            }
            if ("auto".equals(mode) && !nameIsOnline(name)) {
                return; // 保险：拆出的名字必须在在线玩家列表里，否则视为公告
            }
            LocalPlayer self = mc.player;
            if (name.equalsIgnoreCase(self.getGameProfile().getName())) {
                return;
            }
            if (cfg.ignoreAiNameMessages && name.equalsIgnoreCase(cfg.aiName)) {
                return;
            }
            boolean mentioned = containsMention(body);
            if (cfg.mentionOnly && !mentioned) {
                return;
            }
            rememberSeen(raw);
            rememberSeen(body);
            sendChatJson(name, "", body, mentioned, false, true, channel);
            if ("auto".equals(mode)) {
                trchatDetected = true;
                if (!trchatFirstHintShown) {
                    trchatFirstHintShown = true;
                    notice("检测到本服务器用插件重发聊天（TrChat 类），已自动兼容。可用 /aiws trchat on|off 调整。");
                }
            } else {
                trchatDetected = true;
            }
        } else if ("on".equals(mode) && containsMention(raw)) {
            // 强制档：拆不出格式但含 @AI → 整行当聊天转发
            if (isDuplicate(raw)) {
                return;
            }
            rememberSeen(raw);
            sendChatJson("", "", raw, true, false, true, null);
        }
    }

    // ------------------------------------------------------------------
    // 本地发送（简报 §3.1 私聊、§3.3 回声防护）
    // ------------------------------------------------------------------

    /** 普通聊天回车发出时触发；斜杠命令不经过这里（1.20.1 Forge 的 ClientChatEvent 只挂 sendChat）。 */
    @SubscribeEvent
    public void onChatSending(ClientChatEvent event) {
        String msg = event.getMessage();
        if (msg == null || msg.isEmpty()) {
            return;
        }
        if (consumeGuard(msg)) {
            return; // 是自己（AI 回复）刚登记要发的内容：消费掉，不转发
        }
        if (mc.player == null || mc.level == null || !bridge.isBusy() || !cfg.forwardOwnChat) {
            return;
        }
        String body = normalize(msg);
        if (body.isEmpty()) {
            return;
        }
        boolean mentioned = containsMention(body);
        if (cfg.mentionOnly && !mentioned) {
            return;
        }
        rememberSeen(body);
        LocalPlayer p = mc.player;
        sendChatJson(p.getGameProfile().getName(), p.getUUID().toString(), body, mentioned, true, false, null);
    }

    /**
     * ClientChatEvent 不覆盖斜杠命令 → `/msg AI 内容` 在这里本地检测：
     * ChatScreen 按下回车（257/335）前，从子控件里读输入框文本。只观察、不取消，
     * 命令本身仍按原版发给服务器（与 Fabric 端观察式监听行为一致）。
     */
    @SubscribeEvent
    public void onScreenKeyPress(ScreenEvent.KeyPressed.Pre event) {
        int key = event.getKeyCode();
        if (key != 257 && key != 335) {
            return;
        }
        if (Screen.hasControlDown() || Screen.hasShiftDown()) {
            return; // 原版把 Ctrl/Shift+Enter 当填充前缀用，不发送
        }
        if (!(event.getScreen() instanceof ChatScreen)) {
            return;
        }
        if (mc.player == null || mc.level == null || !bridge.isBusy()) {
            return;
        }
        String text = readChatInput(event.getScreen());
        if (text == null) {
            return;
        }
        Matcher m = PRIVATE_MSG.matcher(text.trim());
        if (!m.matches() || !m.group(1).equalsIgnoreCase(cfg.aiName)) {
            return;
        }
        String content = normalize(m.group(2));
        if (content.isEmpty()) {
            return;
        }
        LocalPlayer p = mc.player;
        sendChatJson(p.getGameProfile().getName(), p.getUUID().toString(), content, true, true, false, null);
    }

    private static String readChatInput(Screen screen) {
        for (Object child : screen.children()) {
            if (child instanceof EditBox box) {
                return box.getValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 进出服（简报 §2.3 事件映射）
    // ------------------------------------------------------------------

    @SubscribeEvent
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        trchatFirstHintShown = false;
        trchatDetected = false;
        if (cfg.autoConnect) {
            bridge.setEnabled(true);
        }
        if (bridge.isConnected()) {
            tickTasks.add(this::sendHello); // 进世界重发 hello
        }
    }

    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        synchronized (replyLock) {
            replyQueue.clear();
        }
        synchronized (guardLock) {
            guard.clear();
        }
        synchronized (seenLock) {
            seen.clear();
        }
    }

    // ------------------------------------------------------------------
    // AI 回复入队与投递（简报 §3.3）
    // ------------------------------------------------------------------

    /** 后台线程只做解析入队；显示/发送都回 tick。 */
    private void handleIncoming(String text) {
        try {
            JsonElement el;
            try {
                el = JsonParser.parseString(text);
            } catch (Exception notJson) {
                enqueueReply(text); // 非 JSON 纯文本直接当回复
                return;
            }
            if (el == null || !el.isJsonObject()) {
                String asText = el == null ? text : el.getAsString();
                enqueueReply(asText);
                return;
            }
            JsonObject obj = el.getAsJsonObject();
            String type = optString(obj, "type");
            if (type == null) {
                type = "";
            }
            switch (type.toLowerCase(Locale.ROOT)) {
                case "reply", "message", "chat", "say" -> {
                    String body = firstString(obj, "message", "content", "text");
                    if (body != null) {
                        enqueueReply(body);
                    }
                }
                case "ping" -> bridge.send("{\"type\":\"pong\"}");
                case "notice", "status" -> {
                    String body = firstString(obj, "message", "text", "content");
                    if (body != null) {
                        notice(body);
                    }
                }
                default -> {
                    String body = firstString(obj, "message", "content", "text");
                    if (body != null) {
                        enqueueReply(body); // 带 message 的未知类型宽容当回复
                    }
                }
            }
        } catch (Exception e) {
            AiChatWebsocket.LOGGER.debug("[ai_chat_websocket] incoming parse error", e);
        }
    }

    private void enqueueReply(String raw) {
        String clean = cleanReply(raw);
        if (clean.isEmpty()) {
            return;
        }
        synchronized (replyLock) {
            if (replyQueue.size() >= REPLY_QUEUE_LIMIT) {
                replyQueue.pollFirst(); // 满丢最旧
            }
            replyQueue.addLast(clean);
        }
    }

    private void deliverReply(String reply) {
        LocalPlayer player = mc.player;
        boolean canSend = player != null && mc.level != null;
        if (!cfg.replyAsChat || !canSend) {
            showLocalReply(reply); // 仅本地屏幕显示：[aiName] 文本（淡紫标签+白字）
            return;
        }
        if (reply.startsWith("/")) {
            if (cfg.allowAiCommands) {
                player.connection.sendCommand(reply.substring(1));
            } else {
                notice("AI 回复以 / 开头，命令执行未开启（/aiws commands true），已降级为普通文本。");
                sendChatProtected(reply, player);
            }
            return;
        }
        String trCmd = cfg.trchatReplyCommand;
        if (!trCmd.isEmpty() && trchatActive()) {
            if (AiConfig.isValidReplyCommand(trCmd)) {
                player.connection.sendCommand(trCmd + " " + reply); // 走频道命令，如 /g 文本
            } else {
                notice("回复频道命令名非法，已回落普通聊天。");
                sendChatProtected(reply, player);
            }
        } else {
            sendChatProtected(reply, player);
        }
    }

    private void sendChatProtected(String text, LocalPlayer player) {
        registerGuard(text); // 发之前先登记回声防护
        rememberSeen(text);
        player.connection.sendChat(text);
    }

    /** TrChat 生效判定：on 档恒生效；auto 档检测到过才算生效 */
    private boolean trchatActive() {
        String mode = cfg.trchatMode;
        return "on".equals(mode) || ("auto".equals(mode) && trchatDetected);
    }

    // ------------------------------------------------------------------
    // WebSocket 协议（简报 §3.5，逐字段一致）
    // ------------------------------------------------------------------

    private void sendHello() {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !bridge.isConnected()) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "hello");
        o.addProperty("modId", AiChatWebsocket.MOD_ID);
        o.addProperty("modVersion", AiChatWebsocket.MOD_VERSION);
        o.addProperty("minecraft", AiChatWebsocket.MC_VERSION);
        o.addProperty("trchatMode", cfg.trchatMode);
        o.addProperty("trchatCompat", !"off".equals(cfg.trchatMode)); // 旧 AI 服务端兼容字段
        if (trchatActive() && AiConfig.isValidReplyCommand(cfg.trchatReplyCommand)) {
            o.addProperty("trchatReplyCommand", cfg.trchatReplyCommand);
        }
        o.addProperty("player", player.getGameProfile().getName());
        o.addProperty("playerUuid", player.getUUID().toString());
        bridge.send(gson.toJson(o));
    }

    private void sendChatJson(String sender, String senderUuid, String message,
                              boolean mentioned, boolean self, boolean viaTrChat, String channel) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "chat");
        o.addProperty("sender", sender);
        o.addProperty("senderUuid", senderUuid);
        o.addProperty("message", message);
        o.addProperty("mentioned", mentioned);
        o.addProperty("self", self);
        o.addProperty("target", mentioned ? cfg.aiName : "");
        o.addProperty("dimension", dimensionString());
        o.addProperty("time", System.currentTimeMillis());
        if (viaTrChat) {
            o.addProperty("viaTrChat", true);
            if (channel != null) {
                o.addProperty("channel", channel);
            }
        }
        bridge.send(gson.toJson(o));
    }

    private void pushConfigSnapshot() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "config");
        o.addProperty("aiName", cfg.aiName);
        o.addProperty("mentionOnly", cfg.mentionOnly);
        o.addProperty("trchatMode", cfg.trchatMode);
        o.addProperty("trchatCompat", !"off".equals(cfg.trchatMode));
        if (trchatActive() && AiConfig.isValidReplyCommand(cfg.trchatReplyCommand)) {
            o.addProperty("trchatReplyCommand", cfg.trchatReplyCommand);
        }
        bridge.send(gson.toJson(o));
    }

    private String dimensionString() {
        try {
            return mc.player.level().dimension().location().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------------
    // /aiws 客户端命令（简报 §3.4：全部即时保存并推 config 快照）
    // ------------------------------------------------------------------

    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(buildAiwsCommand());
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildAiwsCommand() {
        return lit("aiws")
                .executes(c -> {
                    showHelp();
                    return 1;
                })
                .then(lit("help").executes(c -> {
                    showHelp();
                    return 1;
                }))
                .then(lit("connect").executes(c -> {
                    // 只拉起连接，不改 autoConnect 配置（那是 /aiws autoconnect 的职责）
                    bridge.updateUrl(AiConfig.wsUrl(cfg.url));
                    bridge.setEnabled(true);
                    notice("正在连接 " + bridge.url());
                    pushConfigSnapshot();
                    return 1;
                }))
                .then(lit("disconnect").executes(c -> {
                    bridge.setEnabled(false);
                    notice("已手动断开（/aiws connect 恢复）。");
                    return 1;
                }))
                .then(lit("status").executes(c -> {
                    showStatus();
                    return 1;
                }))
                .then(lit("save").executes(c -> {
                    notice(cfg.save(configFile) ? "配置已保存。" : "配置保存失败，见日志。");
                    return 1;
                }))
                .then(lit("reload").executes(c -> {
                    cfg = AiConfig.load(configFile);
                    bridge.setEnabled(cfg.autoConnect);
                    if (cfg.autoConnect) {
                        bridge.updateUrl(AiConfig.wsUrl(cfg.url));
                    }
                    pushConfigSnapshot();
                    notice("配置已重新加载。");
                    return 1;
                }))
                .then(lit("url").then(arg("value").executes(c -> {
                    String v = AiConfig.wsUrl(StringArgumentType.getString(c, "value"));
                    if (!AiConfig.isWsScheme(v)) {
                        notice("地址需以 ws:// 或 wss:// 开头（http/https 会自动改写）。");
                        return 0;
                    }
                    cfg.url = v;
                    bridge.updateUrl(v);
                    afterChange("地址已设为 " + v);
                    return 1;
                })))
                .then(lit("ai").then(arg("name").executes(c -> {
                    String v = StringArgumentType.getString(c, "name").trim();
                    cfg.aiName = v.isEmpty() ? AiConfig.DEFAULT_AI_NAME : (v.length() > 32 ? v.substring(0, 32) : v);
                    afterChange("AI 名已设为 " + cfg.aiName);
                    return 1;
                })))
                .then(lit("mode")
                        .then(lit("mention").executes(c -> {
                            cfg.mentionOnly = true;
                            afterChange("转发模式：仅被@/私聊");
                            return 1;
                        }))
                        .then(lit("all").executes(c -> {
                            cfg.mentionOnly = false;
                            afterChange("转发模式：全量");
                            return 1;
                        })))
                .then(lit("reply")
                        .then(lit("chat").executes(c -> {
                            cfg.replyAsChat = true;
                            afterChange("回复方式：以玩家身份发聊天栏");
                            return 1;
                        }))
                        .then(lit("local").executes(c -> {
                            cfg.replyAsChat = false;
                            afterChange("回复方式：仅本地屏幕显示");
                            return 1;
                        })))
                .then(lit("trchat")
                        .then(lit("auto").executes(c -> setTrchat("auto")))
                        .then(lit("on").executes(c -> setTrchat("on")))
                        .then(lit("off").executes(c -> setTrchat("off")))
                        // 兼容旧参数 true/false → on/off
                        .then(lit("true").executes(c -> setTrchat("on")))
                        .then(lit("false").executes(c -> setTrchat("off"))))
                .then(lit("trchatcmd").then(arg("value").executes(c -> {
                    String v = StringArgumentType.getString(c, "value").trim();
                    if (v.equalsIgnoreCase("off") || v.isEmpty()) {
                        cfg.trchatReplyCommand = "";
                        afterChange("AI 回复已改回直发普通聊天");
                        return 1;
                    }
                    if (!AiConfig.isValidReplyCommand(v)) {
                        notice("命令名只允许字母数字 _ : . -（1-32 位），未修改。");
                        return 0;
                    }
                    cfg.trchatReplyCommand = v;
                    afterChange("AI 回复将走 /" + v + " 频道命令");
                    return 1;
                })))
                .then(lit("autoconnect").then(arg("bool").executes(c -> {
                    Boolean b = parseBool(StringArgumentType.getString(c, "bool"));
                    if (b == null) {
                        notice("请填 true 或 false。");
                        return 0;
                    }
                    cfg.autoConnect = b;
                    bridge.setEnabled(b);
                    afterChange("进世界自动连接：" + onOff(b));
                    return 1;
                })))
                .then(lit("forwardme").then(arg("bool").executes(c -> {
                    Boolean b = parseBool(StringArgumentType.getString(c, "bool"));
                    if (b == null) {
                        notice("请填 true 或 false。");
                        return 0;
                    }
                    cfg.forwardOwnChat = b;
                    afterChange("转发自己的普通聊天：" + onOff(b));
                    return 1;
                })))
                .then(lit("commands").then(arg("bool").executes(c -> {
                    Boolean b = parseBool(StringArgumentType.getString(c, "bool"));
                    if (b == null) {
                        notice("请填 true 或 false。");
                        return 0;
                    }
                    cfg.allowAiCommands = b;
                    afterChange("允许 AI 命令：" + onOff(b) + (b ? "（危险开关）" : ""));
                    return 1;
                })))
                .then(lit("test").then(arg("text").executes(c -> {
                    enqueueReply(StringArgumentType.getString(c, "text"));
                    return 1;
                })));
    }

    private int setTrchat(String mode) {
        cfg.trchatMode = mode;
        afterChange("TrChat 兼容：" + trchatModeName(mode));
        return 1;
    }

    /** 每次开关变更：即时保存 + 推 config 快照 */
    private void afterChange(String feedback) {
        cfg.save(configFile);
        pushConfigSnapshot();
        if (feedback != null) {
            notice(feedback);
        }
    }

    private void showHelp() {
        showLocal(Component.literal("/aiws 命令一览").withStyle(ChatFormatting.YELLOW));
        showLocal(Component.literal("help | connect | disconnect | status | save | reload"));
        showLocal(Component.literal("url <ws://...>   ai <名字>   mode <mention|all>   reply <chat|local>"));
        showLocal(Component.literal("trchat <auto|on|off>   trchatcmd <命令名|off>"));
        showLocal(Component.literal("autoconnect <bool>   forwardme <bool>   commands <bool>   test <文本>"));
    }

    private void showStatus() {
        String state = bridge.isConnected() ? "已连接" : (bridge.isEnabled() ? "未连接（重连中）" : "已停用");
        showLocal(Component.literal("[AI-WS] 状态").withStyle(ChatFormatting.YELLOW));
        showLocal(Component.literal(" 地址: " + bridge.url() + "（" + state + "）"));
        showLocal(Component.literal(" AI 名: " + cfg.aiName
                + " | 转发: " + (cfg.mentionOnly ? "仅被@" : "全量")
                + " | 回复: " + (cfg.replyAsChat ? "聊天栏" : "仅本地")));
        showLocal(Component.literal(" 自动连接: " + onOff(cfg.autoConnect)
                + " | 转发自己: " + onOff(cfg.forwardOwnChat)
                + " | AI命令: " + onOff(cfg.allowAiCommands)));
        String trState = "off".equals(cfg.trchatMode) ? "关"
                : "on".equals(cfg.trchatMode) ? "强制开"
                : "自动检测（" + (trchatDetected ? "已生效" : "未触发") + "）";
        String cmd = cfg.trchatReplyCommand.isEmpty() ? "无（直发聊天）" : "/" + cfg.trchatReplyCommand;
        showLocal(Component.literal(" TrChat: " + trState + " | 回复频道命令: " + cmd));
    }

    // ------------------------------------------------------------------
    // 去重表 / 回声防护（简报 §3.2、§3.3）
    // ------------------------------------------------------------------

    private void rememberSeen(String text) {
        String norm = normalize(text);
        if (norm.length() < SEEN_MIN_LEN) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (seenLock) {
            seen.addLast(new Seen(norm, now));
            while (seen.size() > SEEN_LIMIT || (!seen.isEmpty() && now - seen.peekFirst().time() > SEEN_WINDOW_MS)) {
                seen.pollFirst();
            }
        }
    }

    private boolean isDuplicate(String text) {
        String norm = normalize(text);
        if (norm.length() < SEEN_MIN_LEN) {
            return false; // 短文本不参与去重
        }
        long now = System.currentTimeMillis();
        synchronized (seenLock) {
            for (Seen s : seen) {
                if (now - s.time() > SEEN_WINDOW_MS) {
                    continue;
                }
                if (s.text().contains(norm) || norm.contains(s.text())) {
                    return true; // 互为包含即算重复
                }
            }
        }
        return false;
    }

    private void registerGuard(String text) {
        String norm = normalize(text);
        if (norm.isEmpty()) {
            return;
        }
        synchronized (guardLock) {
            if (guard.size() >= GUARD_LIMIT) {
                guard.pollFirst();
            }
            guard.addLast(norm);
        }
    }

    private boolean consumeGuard(String text) {
        String norm = normalize(text);
        if (norm.isEmpty()) {
            return false;
        }
        synchronized (guardLock) {
            if (guard.remove(norm)) {
                return true;
            }
            for (String g : guard) {
                if (g.equalsIgnoreCase(norm)) {
                    guard.remove(g);
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 文本工具
    // ------------------------------------------------------------------

    /** 剥掉 §颜色码、控制字符与零宽字符，折叠连续空白 */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00A7' && i + 1 < s.length()) {
                i++; // § + 格式码
                continue;
            }
            if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF') {
                continue;
            }
            if (c < 0x20) {
                sb.append(' ');
                continue;
            }
            sb.append(c);
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private String cleanReply(String raw) {
        String s = normalize(raw);
        int max = Math.max(16, cfg.maxReplyChars);
        if (s.length() > max) {
            s = s.substring(0, max) + "…";
        }
        return s;
    }

    /** 取消息正文：chat.type.* 装饰取最后一个参数；否则整串并剥 `<名字> ` 前缀 */
    private static String extractBody(Component comp, String decorated) {
        try {
            if (comp.getContents() instanceof TranslatableContents tc) {
                String key = tc.getKey();
                if ("chat.type.text".equals(key) || "chat.type.announcement".equals(key) || "chat.type.emote".equals(key)) {
                    Object[] args = tc.getArgs();
                    if (args != null && args.length >= 2) {
                        Object last = args[args.length - 1];
                        if (last instanceof Component c) {
                            return c.getString();
                        }
                        if (last instanceof String str) {
                            return str;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 结构不符合预期时退回整串
        }
        int gt = decorated.indexOf("> ");
        if (decorated.startsWith("<") && gt > 0 && gt <= 40) {
            return decorated.substring(gt + 2);
        }
        return decorated;
    }

    private static String nameFromDecorated(String decorated) {
        int gt = decorated.indexOf("> ");
        if (decorated.startsWith("<") && gt > 1 && gt <= 40) {
            return decorated.substring(1, gt);
        }
        return null;
    }

    private boolean containsMention(String text) {
        if (text == null) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains("@" + cfg.aiName.toLowerCase(Locale.ROOT));
    }

    private String nameOfPlayer(UUID id) {
        try {
            PlayerInfo info = mc.player.connection.getPlayerInfo(id);
            return info == null ? null : info.getProfile().getName();
        } catch (Exception e) {
            return null;
        }
    }

    /** 在线玩家名比对（不区分大小写），TrChat auto 档保险 */
    private boolean nameIsOnline(String name) {
        try {
            for (PlayerInfo info : mc.player.connection.getOnlinePlayers()) {
                if (info.getProfile().getName().equalsIgnoreCase(name)) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 本地显示
    // ------------------------------------------------------------------

    private void notice(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        synchronized (noticeLock) {
            if (noticeQueue.size() >= NOTICE_QUEUE_LIMIT) {
                noticeQueue.pollFirst();
            }
            noticeQueue.addLast(text);
        }
    }

    private String pollNotice() {
        synchronized (noticeLock) {
            return noticeQueue.pollFirst();
        }
    }

    /** `[AI-WS]` 灰标签 + 黄字 */
    private static Component noticeComponent(String text) {
        return Component.literal("[AI-WS] ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(text).withStyle(ChatFormatting.YELLOW));
    }

    private void showLocalReply(String text) {
        showLocal(Component.literal("[" + cfg.aiName + "] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
    }

    private void showLocal(Component c) {
        try {
            mc.gui.getChat().addMessage(c);
        } catch (Exception e) {
            AiChatWebsocket.LOGGER.debug("[ai_chat_websocket] showLocal failed", e);
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> lit(String name) {
        return Commands.literal(name);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> arg(String name) {
        return Commands.argument(name, StringArgumentType.greedyString());
    }

    private static Boolean parseBool(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("on") || v.equals("1")) {
            return Boolean.TRUE;
        }
        if (v.equals("false") || v.equals("off") || v.equals("0")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String onOff(boolean b) {
        return b ? "开" : "关";
    }

    private static String trchatModeName(String mode) {
        switch (mode) {
            case "on":
                return "强制开";
            case "off":
                return "关";
            default:
                return "自动检测";
        }
    }

    private static String optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return e.getAsString();
        } catch (Exception e2) {
            return null;
        }
    }

    private static String firstString(JsonObject o, String... keys) {
        for (String k : keys) {
            String v = optString(o, k);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }
}
