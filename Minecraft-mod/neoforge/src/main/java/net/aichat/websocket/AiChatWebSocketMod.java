package net.aichat.websocket;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ai_chat_websocket 主入口（纯客户端）：
 * 事件接线（收聊天→转发、TrChat 系统消息解析、本地发送回声防护、tick 交接、/aiws 客户端命令）。
 * 行为规格对齐 Fabric 端 1.0.0-r3。
 */
@Mod(AiChatWebSocketMod.MOD_ID)
public final class AiChatWebSocketMod {
    public static final String MOD_ID = "ai_chat_websocket";
    /** hello 报文里的 modVersion（协议字段，与 gradle 版本号区分） */
    static final String PROTOCOL_MOD_VERSION = "1.0.0";
    static final String GAME_VERSION = "1.21.1";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int REPLY_QUEUE_MAX = 32;
    private static final int ECHO_GUARD_MAX = 16;
    private static final int DEDUP_MAX = 16;
    private static final long DEDUP_WINDOW_MS = 5_000;
    private static final int DEDUP_MIN_LEN = 4;
    private static final int STATUS_QUEUE_MAX = 64;
    private static final int INBOUND_QUEUE_MAX = 256;

    private final AiConfig config;
    private final AiBridgeClient bridge;

    /** 后台线程 → 客户端 tick 的交接队列 */
    private final ConcurrentLinkedQueue<String> inbound = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> statusNotes = new ConcurrentLinkedQueue<>();

    /** AI 回复队列（tick 限速消费），≤32 满丢最旧 */
    private final ArrayDeque<String> replyQueue = new ArrayDeque<>();
    private final Object replyLock = new Object();

    /** 回声防护：即将以玩家身份发出的原文/命令，发送事件命中即消费（仅客户端线程访问） */
    private final ArrayDeque<String> echoGuard = new ArrayDeque<>();

    /** TrChat 去重：最近处理过的原文（可能来自后台与客户端线程，加锁） */
    private record DedupEntry(String text, long time) {}
    private final ArrayDeque<DedupEntry> processed = new ArrayDeque<>();
    private final Object dedupLock = new Object();

    private long lastReplyAtMs;
    private boolean helloSent;
    private boolean wasConnected;
    /** 本次进服是否已由 auto 档认出插件重发（hello/回复频道命令用） */
    private boolean trchatAutoDetected;
    private boolean trchatNoticeShown;

    public AiChatWebSocketMod(IEventBus modEventBus) {
        this.config = AiConfig.load();
        this.bridge = new AiBridgeClient(config.url, this::onInbound, this::onBridgeStatus);
        this.bridge.start();
        if (config.autoConnect) {
            this.bridge.connectNow();
        }
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("{} client bridge initialised, url={}", MOD_ID, config.url);
    }

    // ------------------------------------------------------------------
    // 事件接线
    // ------------------------------------------------------------------

    @SubscribeEvent
    public void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        helloSent = false;
        trchatAutoDetected = false;
        trchatNoticeShown = false;
    }

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        helloSent = false;
        trchatAutoDetected = false;
    }

    @SubscribeEvent
    public void onTickEnd(ClientTickEvent.Post event) {
        tick();
    }

    /** 签名聊天（2.1）：别人说的话。 */
    @SubscribeEvent
    public void onSignedChat(ClientChatReceivedEvent.Player event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !bridgeActive()) {
            return;
        }
        UUID sender = event.getSender();
        if (sender == null || sender.equals(player.getUUID())) {
            // 自己说的话走本地发送事件
            return;
        }
        String name = senderName(event.getSender());
        if (config.ignoreAiNameMessages && name.equalsIgnoreCase(config.aiName)) {
            return;
        }
        String text = signedText(event);
        if (text == null || text.isBlank()) {
            return;
        }
        if (dedupHit(text)) {
            // 插件重发的系统消息副本已处理过
            return;
        }
        boolean mentioned = containsMention(text);
        if (config.mentionOnly && !mentioned) {
            return;
        }
        recordProcessed(text);
        bridge.send(buildChat(name, sender.toString(), text, mentioned, false, null, false, null));
    }

    /** 系统消息（2.2）：TrChat 兼容核心。 */
    @SubscribeEvent
    public void onSystemChat(ClientChatReceivedEvent.System event) {
        if (event.isOverlay()) {
            // 动作栏不是聊天
            return;
        }
        if (config.trchatOff()) {
            // off 档完全不监听系统消息
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !bridgeActive()) {
            return;
        }
        String raw = event.getMessage().getString();
        if (raw.isBlank()) {
            return;
        }
        String stripped = stripCodes(raw);
        if (config.trchatPattern == null) {
            config.normalize();
        }
        java.util.regex.Matcher matcher = config.trchatPattern.matcher(stripped);
        if (!matcher.matches()) {
            // 拆不出格式（2.2 第 4 步）：auto 档直接不处理；on 档只有含 @aiName 时才当聊天转
            if (config.trchatForced() && containsMention(stripped) && !dedupHit(stripped)) {
                recordProcessed(stripped);
                bridge.send(buildChat("", "", stripped, true, false, config.aiName, true, null));
            }
            return;
        }
        String channel = matcher.group(1);
        String name = matcher.group(2);
        String body = matcher.group(3).trim();
        if (body.isBlank()) {
            return;
        }
        // 第 2 步：与 5 秒内已处理原文重合（互为包含）→ 重复副本
        if (dedupHit(body)) {
            return;
        }
        // 第 3 步：auto 档保险——玩家名必须在当前在线列表里，否则视为公告
        if (config.trchatAuto() && !isOnlinePlayer(name)) {
            return;
        }
        String selfName = player.getGameProfile().getName();
        // 第 5 步：发送者＝自己或＝aiName（且开忽略）不转
        if (name.equalsIgnoreCase(selfName)) {
            return;
        }
        if (config.ignoreAiNameMessages && name.equalsIgnoreCase(config.aiName)) {
            return;
        }
        boolean mentioned = containsMention(body);
        if (config.mentionOnly && !mentioned) {
            return;
        }
        // 第 7 步：auto 档第一次成功收取时本地提示一次（每次进服只提示一次）
        if (config.trchatAuto() && !trchatAutoDetected) {
            trchatAutoDetected = true;
            if (!trchatNoticeShown) {
                trchatNoticeShown = true;
                note("检测到本服务器用插件重发聊天，已自动兼容转发");
            }
        }
        recordProcessed(body);
        // 第 6 步：viaTrChat=true、channel（可无）、senderUuid 空串
        bridge.send(buildChat(name, "", body, mentioned, false, null, true, channel));
    }

    /** 本地发送聊天（含我们程序化 sendChat）：回声防护消费 + forwardOwnChat。 */
    @SubscribeEvent
    public void onSendChat(ClientChatEvent event) {
        String message = event.getMessage();
        if (message == null || message.isBlank()) {
            return;
        }
        if (consumeGuard(message)) {
            // AI 回复自己发的，不转回给 AI
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !bridgeActive() || !config.forwardOwnChat) {
            return;
        }
        boolean mentioned = containsMention(message);
        if (config.mentionOnly && !mentioned) {
            return;
        }
        recordProcessed(message);
        bridge.send(buildChat(player.getGameProfile().getName(), player.getUUID().toString(),
                message, mentioned, true, mentioned ? config.aiName : null, false, null));
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(buildAiwsCommand());
        // NeoForge 1.21.1 没有"本地发命令"事件；注册透传型客户端命令，
        // 观测 /msg <aiName> ... 后抛出 unknown command 原样放行走服务器（零影响）。
        for (String alias : List.of("msg", "w", "tell", "whisper", "pm")) {
            dispatcher.register(buildWhisperPassthrough(alias));
        }
    }

    // ------------------------------------------------------------------
    // tick：队列交接与限速
    // ------------------------------------------------------------------

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientPacketListener connection = mc.getConnection();

        // (重新)连上 或 (重新)进世界 → 握手补一次
        boolean connected = bridge.isConnected();
        if (connected != wasConnected) {
            wasConnected = connected;
            if (connected) {
                helloSent = false;
            }
        }
        if (!helloSent && connected && player != null && mc.level != null) {
            helloSent = true;
            bridge.send(buildHello(player));
        }

        String status;
        int budget = 8;
        while (budget-- > 0 && (status = statusNotes.poll()) != null) {
            note(status);
        }

        String message;
        budget = 16;
        while (budget-- > 0 && (message = inbound.poll()) != null) {
            try {
                handleWsMessage(message);
            } catch (Exception e) {
                LOGGER.warn("Failed to handle inbound ws message", e);
            }
        }

        String reply;
        synchronized (replyLock) {
            reply = replyQueue.isEmpty() ? null : replyQueue.peekFirst();
        }
        if (reply != null && player != null && connection != null) {
            long minInterval = config.replyMinIntervalTicks * 50L;
            long now = System.currentTimeMillis();
            if (now - lastReplyAtMs >= minInterval) {
                synchronized (replyLock) {
                    reply = replyQueue.pollFirst();
                }
                if (reply != null) {
                    lastReplyAtMs = now;
                    deliverReply(reply, player, connection);
                }
            }
        }
    }

    private void onInbound(String raw) {
        while (inbound.size() >= INBOUND_QUEUE_MAX) {
            inbound.poll();
        }
        inbound.offer(raw);
    }

    private void onBridgeStatus(String status) {
        while (statusNotes.size() >= STATUS_QUEUE_MAX) {
            statusNotes.poll();
        }
        statusNotes.offer(status);
    }

    // ------------------------------------------------------------------
    // 接收报文解析（2.5 协议）
    // ------------------------------------------------------------------

    private void handleWsMessage(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(trimmed);
        } catch (Exception notJson) {
            // 非 JSON 的纯文本直接当回复
            enqueueReply(trimmed);
            return;
        }
        if (!parsed.isJsonObject()) {
            enqueueReply(trimmed);
            return;
        }
        JsonObject json = parsed.getAsJsonObject();
        String type = optString(json, "type").toLowerCase(Locale.ROOT);
        switch (type) {
            case "ping" -> bridge.send("{\"type\":\"pong\"}");
            case "notice", "status" -> {
                String text = firstString(json, "message", "content", "text");
                if (!text.isEmpty()) {
                    note(text);
                }
            }
            case "reply", "message", "chat", "say" -> {
                String text = firstString(json, "message", "content", "text");
                if (!text.isEmpty()) {
                    enqueueReply(text);
                }
            }
            default -> {
                String text = firstString(json, "message", "content", "text");
                if (!text.isEmpty()) {
                    enqueueReply(text);
                }
            }
        }
    }

    private void enqueueReply(String text) {
        synchronized (replyLock) {
            while (replyQueue.size() >= REPLY_QUEUE_MAX) {
                replyQueue.pollFirst();
            }
            replyQueue.addLast(text);
        }
    }

    // ------------------------------------------------------------------
    // AI 回复 → 发回游戏（2.3）
    // ------------------------------------------------------------------

    private void deliverReply(String rawReply, LocalPlayer player, ClientPacketListener connection) {
        String text = cleanReply(rawReply);
        if (text.isEmpty()) {
            return;
        }
        if (text.startsWith("/")) {
            if (config.allowAiCommands) {
                String command = text.substring(1).trim();
                if (!command.isEmpty()) {
                    guardAdd(command);
                    connection.sendCommand(command);
                }
                return;
            }
            note("AI 回复是命令但已禁用执行（/aiws commands true 开启），降级为普通文本发送");
            text = text.replaceFirst("^/+", "").trim();
            if (text.isEmpty()) {
                return;
            }
        }
        if (!config.replyAsChat) {
            showLocalReply(text);
            return;
        }
        String channelCommand = config.trchatReplyCommand;
        if (!channelCommand.isEmpty() && trchatActive()) {
            if (AiConfig.isValidCommandName(channelCommand)) {
                String command = channelCommand + " " + text;
                guardAdd(command);
                connection.sendCommand(command);
                return;
            }
            note("回复频道命令名非法：" + channelCommand + "，改为直发聊天");
        }
        guardAdd(text);
        connection.sendChat(text);
    }

    private void showLocalReply(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) {
            return;
        }
        Component line = Component.literal("[" + config.aiName + "]").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(" " + text).withStyle(ChatFormatting.WHITE));
        mc.gui.getChat().addMessage(line);
    }

    /** 清洗：去 § 与控制字符、合并空白、截断（截断加 …）。 */
    private String cleanReply(String raw) {
        String s = stripCodes(raw);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        s = out.toString().replaceAll("\\s+", " ").trim();
        int max = config.maxReplyChars;
        if (max > 0 && s.length() > max) {
            s = s.substring(0, max) + "…";
        }
        return s;
    }

    // ------------------------------------------------------------------
    // /aiws 客户端命令（2.4）
    // ------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> buildAiwsCommand() {
        LiteralArgumentBuilder<CommandSourceStack> aiws = Commands.literal("aiws")
                .executes(ctx -> {
                    sendHelp(ctx.getSource());
                    return 1;
                })
                .then(Commands.literal("help").executes(ctx -> {
                    sendHelp(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("connect").executes(ctx -> {
                    bridge.connectNow();
                    reply(ctx.getSource(), "正在连接 " + config.url + " …");
                    return 1;
                }))
                .then(Commands.literal("disconnect").executes(ctx -> {
                    bridge.disconnectNow();
                    reply(ctx.getSource(), "已断开并停止重连（/aiws connect 恢复）");
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    showStatus(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("save").executes(ctx -> {
                    applyConfig();
                    reply(ctx.getSource(), "配置已保存到 config/" + AiConfig.FILE_NAME);
                    return 1;
                }))
                .then(Commands.literal("reload").executes(ctx -> {
                    AiConfig loaded = AiConfig.load();
                    copyInto(loaded);
                    bridge.updateUrl(config.url);
                    pushConfigSnapshot();
                    reply(ctx.getSource(), "配置已重新加载");
                    return 1;
                }))
                .then(Commands.literal("url").then(Commands.argument("value", StringArgumentType.greedyString())
                        .executes(ctx -> setUrl(ctx, "value"))))
                .then(Commands.literal("ai").then(Commands.argument("value", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "value").trim();
                            if (name.isEmpty() || name.length() > 32) {
                                fail(ctx.getSource(), "AI 名字长度需为 1~32 字符");
                                return 0;
                            }
                            config.aiName = name;
                            applyConfig();
                            reply(ctx.getSource(), "AI 名字已设为 " + name);
                            return 1;
                        })))
                .then(Commands.literal("mode").then(Commands.literal("mention").executes(ctx -> {
                            config.mentionOnly = true;
                            applyConfig();
                            reply(ctx.getSource(), "转发模式：仅被@/私聊");
                            return 1;
                        }))
                        .then(Commands.literal("all").executes(ctx -> {
                            config.mentionOnly = false;
                            applyConfig();
                            reply(ctx.getSource(), "转发模式：全量转发");
                            return 1;
                        })))
                .then(Commands.literal("reply").then(Commands.literal("chat").executes(ctx -> {
                            config.replyAsChat = true;
                            applyConfig();
                            reply(ctx.getSource(), "AI 回复将以玩家身份发聊天栏");
                            return 1;
                        }))
                        .then(Commands.literal("local").executes(ctx -> {
                            config.replyAsChat = false;
                            applyConfig();
                            reply(ctx.getSource(), "AI 回复仅本地屏幕显示");
                            return 1;
                        })))
                .then(Commands.literal("trchat").then(Commands.argument("value", StringArgumentType.word())
                        .executes(ctx -> {
                            String mode = AiConfig.normalizeMode(StringArgumentType.getString(ctx, "value"));
                            // 非法值 normalizeMode 已回退 auto，但对显式错误给提示
                            String input = StringArgumentType.getString(ctx, "value").toLowerCase(Locale.ROOT);
                            if (!input.equals("auto") && !input.equals("on") && !input.equals("off")
                                    && !input.equals("true") && !input.equals("false")) {
                                fail(ctx.getSource(), "取值 auto|on|off（兼容 true/false→on/off）");
                                return 0;
                            }
                            config.trchatMode = mode;
                            applyConfig();
                            reply(ctx.getSource(), "TrChat 兼容模式：" + modeLabel(mode));
                            return 1;
                        })))
                .then(Commands.literal("trchatcmd").then(Commands.argument("value", StringArgumentType.word())
                        .executes(ctx -> {
                            String value = StringArgumentType.getString(ctx, "value");
                            if (value.equalsIgnoreCase("off")) {
                                config.trchatReplyCommand = "";
                            } else if (AiConfig.isValidCommandName(value)) {
                                config.trchatReplyCommand = value;
                            } else {
                                fail(ctx.getSource(), "命令名需匹配 [A-Za-z0-9_:.-]{1,32}，或用 off 关闭");
                                return 0;
                            }
                            applyConfig();
                            reply(ctx.getSource(), config.trchatReplyCommand.isEmpty()
                                    ? "AI 回复将直发普通聊天"
                                    : "AI 回复将走 /" + config.trchatReplyCommand + " 频道命令");
                            return 1;
                        })))
                .then(booleanSetter("autoconnect", "进游戏自动连接", v -> config.autoConnect = v))
                .then(booleanSetter("forwardme", "转发自己的聊天", v -> config.forwardOwnChat = v))
                .then(booleanSetter("commands", "允许 AI 命令（危险）", v -> config.allowAiCommands = v));
        LiteralArgumentBuilder<CommandSourceStack> withTest = aiws
                .then(Commands.literal("test").then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            enqueueReply(StringArgumentType.getString(ctx, "text"));
                            reply(ctx.getSource(), "已排入回复队列");
                            return 1;
                        })));
        // ignoreAiNameMessages 开关（配置字段表中列出，status 展示）
        return withTest;
    }

    /** 兼容旧参数：booleanSetter 接受 true/false（trchat 亦接受 true→on/false→off 见其处理）。 */
    private LiteralArgumentBuilder<CommandSourceStack> booleanSetter(String name, String label,
            java.util.function.Consumer<Boolean> setter) {
        return Commands.literal(name).then(Commands.argument("value", StringArgumentType.word())
                .executes(ctx -> {
                    String v = StringArgumentType.getString(ctx, "value").toLowerCase(Locale.ROOT);
                    if (!AiConfig.isBoolean(v)) {
                        fail(ctx.getSource(), "取值 true|false");
                        return 0;
                    }
                    setter.accept(AiConfig.parseBoolean(v));
                    applyConfig();
                    reply(ctx.getSource(), label + "：" + (AiConfig.parseBoolean(v) ? "开" : "关"));
                    return 1;
                }));
    }

    private int setUrl(CommandContext<CommandSourceStack> ctx, String arg) {
        String url = AiConfig.normalizeUrl(StringArgumentType.getString(ctx, arg));
        if (!AiConfig.isWebSocketUrl(url)) {
            fail(ctx.getSource(), "地址需以 ws:// 或 wss:// 开头（http/https 会自动改写）");
            return 0;
        }
        config.url = url;
        applyConfig();
        bridge.updateUrl(url);
        reply(ctx.getSource(), "AI 服务地址已设为 " + url + (bridge.isEnabled() ? "，正在重连" : ""));
        return 1;
    }

    private void sendHelp(CommandSourceStack source) {
        reply(source, "aiws — AI 聊天桥（纯客户端）");
        reply(source, "/aiws help · connect · disconnect · status · save · reload");
        reply(source, "/aiws url <ws://…> · ai <名字> · mode <mention|all> · reply <chat|local>");
        reply(source, "/aiws trchat <auto|on|off> · trchatcmd <命令名|off>");
        reply(source, "/aiws autoconnect|forwardme|commands <true|false> · test <文本>");
    }

    private void showStatus(CommandSourceStack source) {
        String state = bridge.isConnected() ? "已连接" : (bridge.isConnecting() ? "重连中" : "未连接");
        reply(source, "地址：" + config.url + " [" + state + "]");
        reply(source, "AI 名字：" + config.aiName);
        reply(source, "转发模式：" + (config.mentionOnly ? "仅被@/私聊" : "全量转发"));
        reply(source, "回复方式：" + (config.replyAsChat ? "聊天栏（玩家身份）" : "仅本地显示"));
        reply(source, "自动连接：" + onOff(config.autoConnect)
                + " · 转发自己聊天：" + onOff(config.forwardOwnChat)
                + " · 忽略AI名消息：" + onOff(config.ignoreAiNameMessages));
        reply(source, "允许AI命令：" + onOff(config.allowAiCommands)
                + " · 回复限速：" + config.replyMinIntervalTicks + " tick"
                + " · 截断：" + config.maxReplyChars + " 字");
        String mode = modeLabel(config.trchatMode);
        if (config.trchatAuto()) {
            mode += trchatActive() ? "（本服已自动认出）" : "（暂未认出）";
        }
        reply(source, "TrChat 兼容：" + mode + " · 回复频道命令："
                + (config.trchatReplyCommand.isEmpty() ? "直发普通聊天" : "/" + config.trchatReplyCommand));
    }

    private static String onOff(boolean b) {
        return b ? "开" : "关";
    }

    private static String modeLabel(String mode) {
        return switch (mode) {
            case "on" -> "强制开";
            case "off" -> "关";
            default -> "自动检测";
        };
    }

    /** 配置修改统一入口：normalize + 即时保存 + 推 config 快照（2.4）。 */
    private void applyConfig() {
        config.normalize();
        config.save();
        pushConfigSnapshot();
    }

    private void copyInto(AiConfig loaded) {
        config.url = loaded.url;
        config.aiName = loaded.aiName;
        config.mentionOnly = loaded.mentionOnly;
        config.replyAsChat = loaded.replyAsChat;
        config.autoConnect = loaded.autoConnect;
        config.forwardOwnChat = loaded.forwardOwnChat;
        config.ignoreAiNameMessages = loaded.ignoreAiNameMessages;
        config.allowAiCommands = loaded.allowAiCommands;
        config.replyMinIntervalTicks = loaded.replyMinIntervalTicks;
        config.maxReplyChars = loaded.maxReplyChars;
        config.trchatMode = loaded.trchatMode;
        config.trchatParsePattern = loaded.trchatParsePattern;
        config.trchatReplyCommand = loaded.trchatReplyCommand;
        config.trchatPattern = loaded.trchatPattern;
    }

    // ------------------------------------------------------------------
    // 私聊转发（2.1 末条）：/msg|w|tell|whisper|pm <aiName> 内容
    // ------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> buildWhisperPassthrough(String alias) {
        return Commands.literal(alias)
                .then(Commands.argument("target", StringArgumentType.word())
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String target = StringArgumentType.getString(ctx, "target");
                                    String message = StringArgumentType.getString(ctx, "message");
                                    Minecraft mc = Minecraft.getInstance();
                                    LocalPlayer player = mc.player;
                                    if (player != null && bridgeActive()
                                            && target.equalsIgnoreCase(config.aiName)
                                            && !message.isBlank()) {
                                        recordProcessed(message);
                                        bridge.send(buildChat(player.getGameProfile().getName(),
                                                player.getUUID().toString(), message.trim(),
                                                true, true, config.aiName, false, null));
                                    }
                                    // 抛 unknown command → NeoForge 原样发给服务器，命令功能零影响
                                    throw CommandSyntaxException.BUILT_IN_EXCEPTIONS
                                            .dispatcherUnknownCommand().create();
                                })));
    }

    // ------------------------------------------------------------------
    // 出站报文（2.5 协议）
    // ------------------------------------------------------------------

    private String buildHello(LocalPlayer player) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "hello");
        json.addProperty("modId", MOD_ID);
        json.addProperty("modVersion", PROTOCOL_MOD_VERSION);
        json.addProperty("minecraft", GAME_VERSION);
        json.addProperty("trchatMode", config.trchatMode);
        // trchatCompat：布尔快照，保留给不认识新字段的旧 AI 服务
        json.addProperty("trchatCompat", config.trchatCompatFlag());
        if (!config.trchatOff() && !config.trchatReplyCommand.isEmpty()) {
            json.addProperty("trchatReplyCommand", config.trchatReplyCommand);
        }
        json.addProperty("player", player.getGameProfile().getName());
        json.addProperty("playerUuid", player.getUUID().toString());
        return json.toString();
    }

    private String buildChat(String sender, String senderUuid, String message, boolean mentioned,
            boolean self, String target, boolean viaTrChat, String channel) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "chat");
        json.addProperty("sender", sender);
        json.addProperty("senderUuid", senderUuid);
        json.addProperty("message", message);
        json.addProperty("mentioned", mentioned);
        json.addProperty("self", self);
        if (target != null && !target.isEmpty()) {
            json.addProperty("target", target);
        }
        if (viaTrChat) {
            json.addProperty("viaTrChat", true);
            if (channel != null && !channel.isEmpty()) {
                json.addProperty("channel", channel);
            }
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            json.addProperty("dimension", mc.level.dimension().location().toString());
        }
        json.addProperty("time", System.currentTimeMillis());
        return json.toString();
    }

    /** 开关变更推 config 快照。 */
    private void pushConfigSnapshot() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "config");
        json.addProperty("aiName", config.aiName);
        json.addProperty("mentionOnly", config.mentionOnly);
        json.addProperty("trchatMode", config.trchatMode);
        json.addProperty("trchatCompat", config.trchatCompatFlag());
        if (!config.trchatOff() && !config.trchatReplyCommand.isEmpty()) {
            json.addProperty("trchatReplyCommand", config.trchatReplyCommand);
        }
        bridge.send(json.toString());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 已连接或正在重连（2.1 前置条件之一；"玩家在世界中"由调用处判 player）。 */
    private boolean bridgeActive() {
        return bridge.isConnected() || bridge.isConnecting();
    }

    /** TrChat 当前是否生效：on 档恒生效；auto 档本次进服认出后生效。 */
    private boolean trchatActive() {
        return config.trchatForced() || (config.trchatAuto() && trchatAutoDetected);
    }

    private boolean containsMention(String text) {
        if (text == null) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains("@" + config.aiName.toLowerCase(Locale.ROOT));
    }

    private String senderName(UUID sender) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null && sender != null) {
            PlayerInfo info = connection.getPlayerInfo(sender);
            if (info != null) {
                return info.getProfile().getName();
            }
        }
        return sender == null ? "" : sender.toString();
    }

    private boolean isOnlinePlayer(String name) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return false;
        }
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            if (info.getProfile().getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static String signedText(ClientChatReceivedEvent.Player event) {
        PlayerChatMessage message = event.getPlayerChatMessage();
        if (message != null) {
            String signed = message.signedContent();
            if (signed != null && !signed.isBlank()) {
                return signed;
            }
        }
        return event.getMessage().getString();
    }

    /** 剥掉 § 颜色码与零宽字符。 */
    static String stripCodes(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') {
                if (i + 1 < s.length()) {
                    i++;
                }
                continue;
            }
            if (isZeroWidth(c)) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isZeroWidth(char c) {
        return (c >= '\u200B' && c <= '\u200F') || c == '\uFEFF';
    }

    private void recordProcessed(String text) {
        String normalized = normalizeForDedup(text);
        if (normalized.length() < DEDUP_MIN_LEN) {
            // 忽略短文本（2.2 去重表规则）
            return;
        }
        synchronized (dedupLock) {
            while (processed.size() >= DEDUP_MAX) {
                processed.pollFirst();
            }
            processed.addLast(new DedupEntry(normalized, System.currentTimeMillis()));
        }
    }

    private boolean dedupHit(String text) {
        String normalized = normalizeForDedup(text);
        if (normalized.length() < DEDUP_MIN_LEN) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (dedupLock) {
            Iterator<DedupEntry> it = processed.iterator();
            while (it.hasNext()) {
                DedupEntry entry = it.next();
                if (now - entry.time() > DEDUP_WINDOW_MS) {
                    it.remove();
                    continue;
                }
                if (entry.text().contains(normalized) || normalized.contains(entry.text())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalizeForDedup(String text) {
        return stripCodes(text).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String optString(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e == null || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    /** 取 message|content|text 任一键。 */
    private static String firstString(JsonObject json, String... keys) {
        for (String key : keys) {
            String v = optString(json, key);
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private void guardAdd(String message) {
        while (echoGuard.size() >= ECHO_GUARD_MAX) {
            echoGuard.pollFirst();
        }
        echoGuard.addLast(message);
    }

    private boolean consumeGuard(String message) {
        return echoGuard.remove(message);
    }

    /** 本地状态提示：[AI-WS] 灰标签 + 黄字。 */
    private void note(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) {
            return;
        }
        Component line = Component.literal("[AI-WS] ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(text).withStyle(ChatFormatting.YELLOW));
        mc.gui.getChat().addMessage(line);
    }

    private void reply(CommandSourceStack source, String text) {
        source.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    private void fail(CommandSourceStack source, String text) {
        source.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.RED));
    }
}
