package cn.blockforge.generated.aichatwebsocket;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 主入口（客户端）：
 * - 收到别的玩家聊天 → 按规则（被@/全量）打包成 JSON，通过 WebSocket 发给 AI 服务；
 * - 自己打的字、发给 AI 的私聊命令也转发；
 * - AI 回消息 → 排队，在客户端 tick 里按限速发进聊天栏（或以本地消息显示）；
 * - /aiws 命令负责连接、改配置。
 * WebSocket 的回调都在后台线程，一切和 Minecraft 打交道的动作都挪回客户端 tick 里做。
 */
public final class AiChatWebsocketClient implements ClientModInitializer {
    public static final String MOD_ID = "ai_chat_websocket";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private AiBridgeClient bridge;
    /** 待发到聊天栏的 AI 回复（后台线程写入，tick 线程消费） */
    private final Queue<String> pendingReplies = new ConcurrentLinkedQueue<>();
    /** 待显示的连接状态提示 */
    private final Queue<String> pendingStatus = new ConcurrentLinkedQueue<>();
    /** 刚以玩家身份发出去的 AI 回复原文，用于识别并忽略自己的回声，防止 AI 回复自己 */
    private final Deque<String> aiReplyGuard = new ArrayDeque<>();
    /** 最近已转发给 AI 的原文（签名聊天/本地发送），用于抑制 TrChat 兼容模式下系统消息里的重复副本 */
    private final Deque<Forwarded> recentForwarded = new ArrayDeque<>();
    private static final long ECHO_WINDOW_MS = 5_000L;
    private static final int ECHO_KEEP = 16;
    private volatile boolean needHello;
    private volatile long lastReplyMs;
    /** auto 档首次认出 TrChat 重发消息时提示一次（进服/切档后重置） */
    private volatile boolean trchatAutoNoticeShown;

    private record Forwarded(String text, long time) { }

    @Override
    public void onInitializeClient() {
        AiConfig.load();
        bridge = new AiBridgeClient(AiConfig.current.url, this::handleBridgeMessage,
                new AiBridgeClient.StatusListener() {
                    @Override
                    public void onStatus(String text) {
                        addStatus(text);
                    }

                    @Override
                    public void onOpen() {
                        addStatus("已连接到 AI 服务");
                        needHello = true;
                    }

                    @Override
                    public void onClose(String reason) {
                        if (bridge != null && bridge.wantsConnection()) {
                            addStatus("与 AI 服务断开（" + reason + "），稍后自动重连…");
                        }
                    }
                });

        ClientReceiveMessageEvents.CHAT.register(this::onChatReceived);
        ClientReceiveMessageEvents.GAME.register(this::onGameReceived);
        ClientSendMessageEvents.CHAT.register(this::onLocalChatSent);
        ClientSendMessageEvents.COMMAND.register(this::onLocalCommandSent);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin(client));
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> registerCommands(dispatcher));

        LOGGER.info("[{}] 初始化完成：进世界自动连接，或输入 /aiws connect", MOD_ID);
    }

    // ---------------------------------------------------------------- 连接

    private void onJoin(MinecraftClient client) {
        trchatAutoNoticeShown = false;
        if (AiConfig.current.autoConnect) {
            bridge.start();
        } else if (bridge.isConnected()) {
            needHello = true;
        } else {
            addStatus("AI 未连接，输入 /aiws connect 连接（本次会话不再提示）");
        }
    }

    private String buildHello(MinecraftClient client) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "hello");
        o.addProperty("modId", MOD_ID);
        o.addProperty("modVersion", "1.0.0");
        o.addProperty("minecraft", "1.21.1");
        o.addProperty("trchatMode", AiConfig.current.trchatMode);
        o.addProperty("trchatCompat", AiConfig.current.trchatActive());
        if (AiConfig.current.trchatActive() && !AiConfig.current.trchatReplyCommand.isEmpty()) {
            o.addProperty("trchatReplyCommand", AiConfig.current.trchatReplyCommand);
        }
        if (client.player != null) {
            o.addProperty("player", client.player.getName().getString());
            o.addProperty("playerUuid", client.player.getUuid().toString());
        }
        return o.toString();
    }

    // ---------------------------------------------------------- 收到聊天 → 转发

    private void onChatReceived(Text message, SignedMessage signedMessage, GameProfile sender,
                                MessageType.Parameters params, Instant receivedTime) {
        try {
            AiConfig cfg = AiConfig.current;
            if (!bridge.wantsConnection() || sender == null) {
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return;
            }
            UUID self = client.player.getUuid();
            if (self.equals(sender.getId())) {
                // 自己说的话由 ClientSendMessageEvents.CHAT 处理，这里跳过服务器回显
                return;
            }
            if (cfg.ignoreAiNameMessages && sender.getName().equalsIgnoreCase(cfg.aiName)) {
                return;
            }
            String raw = rawText(message, signedMessage);
            if (raw.isBlank()) {
                return;
            }
            boolean mentioned = mentionsAi(raw, cfg.aiName);
            if (cfg.mentionOnly && !mentioned) {
                return;
            }
            rememberForwarded(raw);
            sendChatPayload(client, sender.getName(), sender.getId().toString(), raw, mentioned, false,
                    null, false);
        } catch (Exception e) {
            LOGGER.warn("处理收到的聊天出错：{}", e.toString());
        }
    }

    private static String rawText(Text message, SignedMessage signedMessage) {
        if (signedMessage != null) {
            try {
                return signedMessage.getSignedContent().strip();
            } catch (Exception ignored) {
                // 落到 unsignedContent
            }
        }
        return message.getString().strip();
    }

    private void onLocalChatSent(String message) {
        try {
            if (aiReplyGuard.remove(message)) {
                // 这是刚才 AI 回复的回声，别再转给 AI
                return;
            }
            AiConfig cfg = AiConfig.current;
            if (!bridge.wantsConnection()) {
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return;
            }
            // 无论这条转不转，都先记下原文：TrChat 兼容模式下服务器回显的系统消息里含同一句话，
            // 不记的话会被当成别人说的话再转一遍
            rememberForwarded(message);
            boolean mentioned = mentionsAi(message, cfg.aiName);
            boolean forward = !cfg.mentionOnly || mentioned || cfg.forwardOwnChat;
            if (!forward) {
                return;
            }
            sendChatPayload(client, client.player.getName().getString(),
                    client.player.getUuid().toString(), message.strip(), mentioned, true,
                    null, false);
        } catch (Exception e) {
            LOGGER.warn("处理自己发送的聊天出错：{}", e.toString());
        }
    }

    private void onLocalCommandSent(String command) {
        try {
            if (aiReplyGuard.remove(command) || aiReplyGuard.remove("/" + command)) {
                // 这是 AI 回复刚发出的命令，别回头再转发给 AI
                return;
            }
            AiConfig cfg = AiConfig.current;
            if (!bridge.wantsConnection()) {
                return;
            }
            String cmd = command.startsWith("/") ? command.substring(1) : command;
            String[] parts = cmd.trim().split("\\s+", 3);
            if (parts.length < 3) {
                return;
            }
            String head = parts[0].toLowerCase(Locale.ROOT);
            boolean isMsg = head.equals("msg") || head.equals("w") || head.equals("tell")
                    || head.equals("whisper") || head.equals("pm");
            if (isMsg && parts[1].equalsIgnoreCase(cfg.aiName)) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player == null) {
                    return;
                }
                rememberForwarded(parts[2]);
                sendChatPayload(client, client.player.getName().getString(),
                        client.player.getUuid().toString(), parts[2].strip(), true, true,
                        null, false);
            }
        } catch (Exception e) {
            LOGGER.warn("处理私聊命令出错：{}", e.toString());
        }
    }

    private void sendChatPayload(MinecraftClient client, String sender, String senderUuid,
                                 String message, boolean mentioned, boolean self,
                                 String channel, boolean viaTrChat) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "chat");
        o.addProperty("sender", sender);
        o.addProperty("senderUuid", senderUuid);
        o.addProperty("message", message);
        o.addProperty("mentioned", mentioned);
        o.addProperty("self", self);
        o.addProperty("target", AiConfig.current.aiName);
        if (viaTrChat) {
            o.addProperty("viaTrChat", true);
            if (channel != null && !channel.isEmpty()) {
                o.addProperty("channel", channel);
            }
        }
        if (client.world != null) {
            o.addProperty("dimension", client.world.getRegistryKey().getValue().toString());
        }
        o.addProperty("time", System.currentTimeMillis());
        bridge.send(o.toString());
    }

    private static boolean mentionsAi(String text, String aiName) {
        if (aiName == null || aiName.isBlank()) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains("@" + aiName.toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------- TrChat 兼容：系统消息通道

    /**
     * TrChat（以及同类插件）会取消原版聊天广播，把格式化后的消息以“系统消息”重发，
     * 形如 "[世界] PlayerName: 内容"。这类消息不会触发 CHAT 事件，只会走 GAME 事件，
     * 所以在这里收消息：
     * - auto 档（默认）＝自动检测：只有「能用正则拆成 频道/玩家名/正文」并且「玩家名确实在
     *   当前服务器在线列表里」的系统消息才被当成聊天转发，进服提示、别的插件公告、
     *   假名字都会被这两道关卡挡掉——普通服务器行为完全不变；
     * - on 档＝强制收：凡格式能拆开的都当聊天，拆不出格式但 @ 到 AI 的公告也转；
     * - off 档＝不监听；
     * - 若正文与最近已由 CHAT/本地发送处理过的原文重合（同一句话的两份拷贝），跳过。
     */
    private void onGameReceived(Text message, boolean signed) {
        try {
            AiConfig cfg = AiConfig.current;
            if (!cfg.trchatActive() || !bridge.wantsConnection() || cfg.trchatPattern == null) {
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return;
            }
            String line = stripInvisible(message.getString()).strip();
            if (line.isEmpty()) {
                return;
            }
            java.util.regex.Matcher m = cfg.trchatPattern.matcher(line);
            String channel = "";
            String sender = "";
            String body = line;
            if (m.matches()) {
                channel = m.group(1) == null ? "" : m.group(1).strip();
                sender = m.group(2).strip();
                body = stripInvisible(m.group(3)).strip();
                if (isRecentEcho(body)) {
                    // 同一句话已经走签名聊天/本地发送转过了，这份是插件重发的格式化副本
                    return;
                }
                if (cfg.trchatAuto() && !isOnlinePlayer(client, sender)) {
                    // 自动检测档：名字不在在线列表里，多半是公告/机器人假名，不当成玩家聊天
                    return;
                }
            } else if (cfg.trchatAuto()) {
                // 自动检测档只信任“看起来就是玩家聊天”的行，其它系统消息一律不碰
                return;
            }
            boolean mentioned = mentionsAi(line, cfg.aiName);
            if (!m.matches() && !mentioned) {
                // 拆不出格式又没 @AI：大概率是进服提示/别的插件公告，忽略
                return;
            }
            if (cfg.mentionOnly && !mentioned) {
                return;
            }
            if (cfg.ignoreAiNameMessages && sender.equalsIgnoreCase(cfg.aiName)) {
                return;
            }
            if (!sender.isEmpty() && sender.equalsIgnoreCase(client.player.getName().getString())) {
                return; // 自己说的话由发送侧处理
            }
            if (cfg.trchatAuto() && !trchatAutoNoticeShown) {
                trchatAutoNoticeShown = true;
                addStatus("自动检测到本服务器用插件（TrChat 类）重发聊天，已自动按玩家聊天收取。"
                        + "更严格可 /aiws trchat on，关闭可 /aiws trchat off");
            }
            sendChatPayload(client, sender, "", body, mentioned, false, channel, true);
        } catch (Exception e) {
            LOGGER.warn("处理 TrChat 系统消息出错：{}", e.toString());
        }
    }

    /** 名字是否是当前服务器在线列表里的玩家（自动检测档的第二道保险） */
    private static boolean isOnlinePlayer(MinecraftClient client, String name) {
        if (name.isEmpty()) {
            return false;
        }
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (handler == null) {
            return false;
        }
        if (handler.getPlayerListEntry(name) != null) {
            return true;
        }
        for (net.minecraft.client.network.PlayerListEntry entry : handler.getPlayerList()) {
            if (entry.getProfile().getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 记下已由 CHAT 事件或本地发送处理过的原文，供系统消息侧去重（tick 线程与网络线程都会碰，加锁） */
    private void rememberForwarded(String text) {
        String s = stripInvisible(text).strip();
        if (s.length() < 4) {
            return; // 太短的文本容易和别的消息误判重合，不记
        }
        synchronized (recentForwarded) {
            recentForwarded.addLast(new Forwarded(s, System.currentTimeMillis()));
            while (recentForwarded.size() > ECHO_KEEP) {
                recentForwarded.removeFirst();
            }
        }
    }

    /** 该文本是否和最近转发过的某条原文重合（互为包含即算，插件会往原文两边加格式） */
    private boolean isRecentEcho(String text) {
        String s = stripInvisible(text).strip();
        if (s.length() < 4) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (recentForwarded) {
            recentForwarded.removeIf(f -> now - f.time() > ECHO_WINDOW_MS);
            for (Forwarded f : recentForwarded) {
                if (s.contains(f.text()) || f.text().contains(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 去掉 § 颜色码、零宽字符等不可见内容，便于比对与交给 AI */
    private static String stripInvisible(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' || c == '\u00a7') {
                i++; // 跳过 § 后面的格式字符
                continue;
            }
            if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\u2060' || c == '\uFEFF') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // -------------------------------------------------- 收到 AI 消息 → 排队回复

    /** WebSocket 后台线程调用；只做解析和入队 */
    private void handleBridgeMessage(String raw) {
        String s = raw.strip();
        if (s.isEmpty()) {
            return;
        }
        try {
            JsonElement el = JsonParser.parseString(s);
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                String type = "reply";
                if (o.has("type") && o.get("type").isJsonPrimitive()) {
                    type = o.get("type").getAsString().toLowerCase(Locale.ROOT);
                }
                switch (type) {
                    case "reply", "message", "chat", "say" -> {
                        String msg = firstString(o, "message", "content", "text");
                        if (msg != null && !msg.isBlank()) {
                            enqueueReply(msg);
                        }
                    }
                    case "ping" -> bridge.send("{\"type\":\"pong\"}");
                    case "notice", "status" -> {
                        String msg = firstString(o, "message", "content", "text");
                        if (msg != null) {
                            addStatus(msg);
                        }
                    }
                    default -> LOGGER.debug("忽略未知类型的 AI 消息：{}", type);
                }
                return;
            }
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                enqueueReply(el.getAsString());
                return;
            }
        } catch (JsonSyntaxException ignored) {
            // 不是 JSON：当成纯文本回复
        } catch (Exception e) {
            LOGGER.warn("解析 AI 消息出错：{}", e.toString());
        }
        enqueueReply(s);
    }

    private void enqueueReply(String msg) {
        if (pendingReplies.size() > 32) {
            pendingReplies.poll();
        }
        pendingReplies.add(msg);
    }

    private static String firstString(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) {
                return o.get(k).getAsString();
            }
        }
        return null;
    }

    private void addStatus(String text) {
        while (pendingStatus.size() > 20) {
            pendingStatus.poll();
        }
        pendingStatus.add(text);
    }

    // -------------------------------------------------------------- 客户端 tick

    private void onTick(MinecraftClient client) {
        try {
            if (client.player != null) {
                String st;
                while ((st = pendingStatus.poll()) != null) {
                    showSystem(client, st);
                }
            } else {
                while (pendingStatus.size() > 8) {
                    pendingStatus.poll();
                }
            }
            if (needHello && client.player != null && bridge.isConnected()) {
                needHello = false;
                bridge.send(buildHello(client));
            }
            String reply = pendingReplies.peek();
            if (reply != null) {
                long gap = AiConfig.current.replyMinIntervalTicks * 50L;
                if (System.currentTimeMillis() - lastReplyMs >= gap && client.player != null) {
                    pendingReplies.poll();
                    dispatchReply(client, reply);
                    lastReplyMs = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            LOGGER.warn("tick 出错：{}", e.toString());
        }
    }

    /** 把 AI 回复发进聊天栏（以玩家身份）或仅本地显示 */
    private void dispatchReply(MinecraftClient client, String text) {
        AiConfig cfg = AiConfig.current;
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        boolean asCommand = trimmed.startsWith("/");
        String body = sanitizeChatText(asCommand ? trimmed.substring(1) : trimmed, cfg.maxReplyChars);
        if (body.isEmpty()) {
            return;
        }
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (cfg.replyAsChat && handler != null) {
            // 先登记回声防护再发送：发送事件是同步触发的，晚一步就会被当成玩家发言转回去
            String chCmd = cfg.trchatActive() ? cfg.trchatReplyCommand : "";
            if (asCommand && cfg.allowAiCommands) {
                aiReplyGuard.addLast("/" + body);
                rememberForwarded(body);
                handler.sendChatCommand(body);
            } else if (!chCmd.isEmpty() && !asCommand) {
                // TrChat 服务器：走频道命令发送（如 /g 文本），让插件把话路由到指定频道
                if (!isValidCommandName(chCmd)) {
                    showSystem(client, "trchatcmd \"" + chCmd + "\" 不是合法命令名，已按普通聊天发送");
                    aiReplyGuard.addLast(body);
                    rememberForwarded(body);
                    handler.sendChatMessage(body);
                } else {
                    String command = chCmd + " " + body;
                    aiReplyGuard.addLast("/" + command);
                    rememberForwarded(body);
                    handler.sendChatCommand(command);
                }
            } else {
                if (asCommand) {
                    showSystem(client, "AI 发来一条命令，未启用命令权限（/aiws commands true 可开），按普通文本发送");
                }
                aiReplyGuard.addLast(body);
                rememberForwarded(body);
                handler.sendChatMessage(body);
            }
            while (aiReplyGuard.size() > 16) {
                aiReplyGuard.removeFirst();
            }
        } else {
            client.inGameHud.getChatHud().addMessage(Text.literal("[" + cfg.aiName + "] ")
                    .formatted(Formatting.LIGHT_PURPLE)
                    .append(Text.literal(body).formatted(Formatting.WHITE)));
        }
    }

    private void showSystem(MinecraftClient client, String text) {
        if (client.inGameHud == null) {
            return;
        }
        client.inGameHud.getChatHud().addMessage(Text.literal("[AI-WS] ")
                .formatted(Formatting.GRAY)
                .append(Text.literal(text).formatted(Formatting.YELLOW)));
    }

    /** 命令名是否合法（TrChat 频道绑定的命令，如 g / all / shout） */
    private static boolean isValidCommandName(String s) {
        return !s.isEmpty() && s.matches("[A-Za-z0-9_:.-]{1,32}");
    }

    /** 去掉控制字符和 §，合并空白，截断到 max 字符 */
    private static String sanitizeChatText(String text, int max) {
        StringBuilder sb = new StringBuilder(Math.min(text.length(), max + 2));
        boolean lastWasSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' || c == '\u00a7') {
                continue;
            }
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ') {
                if (!lastWasSpace && sb.length() > 0) {
                    sb.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            if (c < 0x20 || c == 0x7F) {
                continue;
            }
            sb.append(c);
            lastWasSpace = false;
        }
        String out = sb.toString().strip();
        if (out.length() > max) {
            out = out.substring(0, Math.max(0, max - 1)) + "…";
        }
        return out;
    }

    // ------------------------------------------------------------ /aiws 命令

    private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("aiws")
                .then(ClientCommandManager.literal("help").executes(this::sendHelp))
                .then(ClientCommandManager.literal("connect").executes(ctx -> {
                    bridge.start();
                    ctx.getSource().sendFeedback(t("开始连接 " + AiConfig.current.url, Formatting.GREEN));
                    return 1;
                }))
                .then(ClientCommandManager.literal("disconnect").executes(ctx -> {
                    bridge.stop();
                    ctx.getSource().sendFeedback(t("已断开并停止重连", Formatting.RED));
                    return 1;
                }))
                .then(ClientCommandManager.literal("status").executes(this::sendStatus))
                .then(ClientCommandManager.literal("save").executes(ctx -> {
                    AiConfig.save();
                    ctx.getSource().sendFeedback(t("配置已保存", Formatting.GREEN));
                    return 1;
                }))
                .then(ClientCommandManager.literal("reload").executes(ctx -> {
                    AiConfig.load();
                    bridge.updateUrl(AiConfig.current.url);
                    ctx.getSource().sendFeedback(t("配置已重新载入（" + AiConfig.current.url + "）", Formatting.GREEN));
                    return 1;
                }))
                .then(ClientCommandManager.literal("url").then(
                        ClientCommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(this::setUrl)))
                .then(ClientCommandManager.literal("ai").then(
                        ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name").strip();
                                    if (name.isEmpty()) {
                                        ctx.getSource().sendError(t("名字不能为空", Formatting.RED));
                                        return 0;
                                    }
                                    AiConfig.current.aiName = name;
                                    AiConfig.save();
                                    ctx.getSource().sendFeedback(t("AI 名字已设为 @" + name, Formatting.GREEN));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("mode").then(
                        ClientCommandManager.argument("value", StringArgumentType.word())
                                .suggests((src, b) -> b.suggest("mention").suggest("all").buildFuture())
                                .executes(ctx -> {
                                    String v = StringArgumentType.getString(ctx, "value").toLowerCase(Locale.ROOT);
                                    if (v.equals("mention")) {
                                        AiConfig.current.mentionOnly = true;
                                    } else if (v.equals("all")) {
                                        AiConfig.current.mentionOnly = false;
                                    } else {
                                        ctx.getSource().sendError(t("只能是 mention 或 all", Formatting.RED));
                                        return 0;
                                    }
                                    AiConfig.save();
                                    ctx.getSource().sendFeedback(t(AiConfig.current.mentionOnly
                                            ? "已改为只有被@时才转发" : "已改为转发所有聊天", Formatting.GREEN));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("reply").then(
                        ClientCommandManager.argument("value", StringArgumentType.word())
                                .suggests((src, b) -> b.suggest("chat").suggest("local").buildFuture())
                                .executes(ctx -> {
                                    String v = StringArgumentType.getString(ctx, "value").toLowerCase(Locale.ROOT);
                                    if (v.equals("chat")) {
                                        AiConfig.current.replyAsChat = true;
                                    } else if (v.equals("local")) {
                                        AiConfig.current.replyAsChat = false;
                                    } else {
                                        ctx.getSource().sendError(t("只能是 chat 或 local", Formatting.RED));
                                        return 0;
                                    }
                                    AiConfig.save();
                                    ctx.getSource().sendFeedback(t(AiConfig.current.replyAsChat
                                            ? "AI 回复将以玩家身份发到聊天栏" : "AI 回复只在你的屏幕上显示", Formatting.GREEN));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("trchat").then(
                        ClientCommandManager.argument("mode", StringArgumentType.word())
                                .suggests((src, b) -> b.suggest("auto").suggest("on").suggest("off").buildFuture())
                                .executes(ctx -> {
                                    String v = StringArgumentType.getString(ctx, "mode")
                                            .toLowerCase(Locale.ROOT).strip();
                                    if (v.equals("true")) {
                                        v = "on";
                                    } else if (v.equals("false")) {
                                        v = "off";
                                    }
                                    if (!(v.equals("auto") || v.equals("on") || v.equals("off"))) {
                                        ctx.getSource().sendError(t("只能是 auto / on / off", Formatting.RED));
                                        return 0;
                                    }
                                    AiConfig.current.trchatMode = v;
                                    trchatAutoNoticeShown = false;
                                    AiConfig.save();
                                    sendConfigNotice();
                                    ctx.getSource().sendFeedback(t(switch (v) {
                                        case "on" -> "TrChat 兼容：强制开（凡能按格式拆开的系统消息都当聊天）";
                                        case "off" -> "TrChat 兼容：已关闭（不再监听系统消息）";
                                        default -> "TrChat 兼容：自动检测（发现服务器用插件重发聊天时自动生效）";
                                    }, Formatting.GREEN));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("trchatcmd").then(
                        ClientCommandManager.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    String v = StringArgumentType.getString(ctx, "name")
                                            .toLowerCase(Locale.ROOT).strip();
                                    if (v.equals("off") || v.equals("none")) {
                                        AiConfig.current.trchatReplyCommand = "";
                                    } else if (!isValidCommandName(v)) {
                                        ctx.getSource().sendError(t("命令名只能是字母数字下划线等，或直接填 off",
                                                Formatting.RED));
                                        return 0;
                                    } else {
                                        AiConfig.current.trchatReplyCommand = v;
                                    }
                                    AiConfig.save();
                                    sendConfigNotice();
                                    ctx.getSource().sendFeedback(t(AiConfig.current.trchatReplyCommand.isEmpty()
                                            ? "AI 回复将直接发普通聊天（由 TrChat 路由到默认频道）"
                                            : "AI 回复将以 /" + AiConfig.current.trchatReplyCommand + " 文本 发出",
                                            Formatting.GREEN));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("autoconnect").then(boolOption((v) -> {
                    AiConfig.current.autoConnect = v;
                }, "进世界自动连接")))
                .then(ClientCommandManager.literal("forwardme").then(boolOption((v) -> {
                    AiConfig.current.forwardOwnChat = v;
                }, "转发自己发的普通聊天")))
                .then(ClientCommandManager.literal("commands").then(boolOption((v) -> {
                    AiConfig.current.allowAiCommands = v;
                }, "允许 AI 的回复执行命令")))
                .then(ClientCommandManager.literal("test").then(
                        ClientCommandManager.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    enqueueReply(StringArgumentType.getString(ctx, "text"));
                                    lastReplyMs = 0;
                                    ctx.getSource().sendFeedback(t("已把这条文本排入 AI 回复队列", Formatting.GREEN));
                                    return 1;
                                }))));
    }

    /** 通用 true|false 开关 */
    private RequiredArgumentBuilder<FabricClientCommandSource, Boolean> boolOption(
            java.util.function.Consumer<Boolean> setter, String label) {
        return ClientCommandManager.argument("value", BoolArgumentType.bool())
                .executes(ctx -> {
                    boolean v = BoolArgumentType.getBool(ctx, "value");
                    setter.accept(v);
                    AiConfig.save();
                    sendConfigNotice();
                    ctx.getSource().sendFeedback(t(label + (v ? "：开" : "：关"), Formatting.GREEN));
                    return 1;
                });
    }

    /** 把关键运行参数同步给 AI 服务，让它知道客户端现在处于什么模式 */
    private void sendConfigNotice() {
        AiConfig cfg = AiConfig.current;
        JsonObject o = new JsonObject();
        o.addProperty("type", "config");
        o.addProperty("aiName", cfg.aiName);
        o.addProperty("mentionOnly", cfg.mentionOnly);
        o.addProperty("trchatMode", cfg.trchatMode);
        o.addProperty("trchatCompat", cfg.trchatActive());
        o.addProperty("trchatReplyCommand", cfg.trchatReplyCommand);
        bridge.send(o.toString());
    }

    private int setUrl(CommandContext<FabricClientCommandSource> ctx) {
        String raw = StringArgumentType.getString(ctx, "url").strip();
        String normalized = normalizeUrl(raw);
        if (normalized == null) {
            ctx.getSource().sendError(t("地址不合法，要用 ws:// 或 wss:// 开头", Formatting.RED));
            return 0;
        }
        AiConfig.current.url = normalized;
        AiConfig.save();
        bridge.updateUrl(normalized);
        ctx.getSource().sendFeedback(t("服务地址已设为 " + normalized
                + (bridge.wantsConnection() ? "（正在用新地址重连）" : ""), Formatting.GREEN));
        return 1;
    }

    /** http→ws，https→wss；校验 URI */
    private static String normalizeUrl(String raw) {
        String s = raw.trim();
        if (s.startsWith("https://")) {
            s = "wss://" + s.substring("https://".length());
        } else if (s.startsWith("http://")) {
            s = "ws://" + s.substring("http://".length());
        }
        if (!(s.startsWith("ws://") || s.startsWith("wss://"))) {
            return null;
        }
        try {
            URI uri = new URI(s);
            if (uri.getHost() == null) {
                return null;
            }
            return s;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private int sendStatus(CommandContext<FabricClientCommandSource> ctx) {
        AiConfig cfg = AiConfig.current;
        ctx.getSource().sendFeedback(t("— AI Chat WebSocket 状态 —", Formatting.GOLD));
        ctx.getSource().sendFeedback(t("地址：" + cfg.url
                + (bridge.isConnected() ? "  [已连接]" : bridge.wantsConnection() ? "  [连接中/重连中]" : "  [未连接]"),
                bridge.isConnected() ? Formatting.GREEN : Formatting.YELLOW));
        ctx.getSource().sendFeedback(t("AI 名字：@" + cfg.aiName
                + "  |  转发模式：" + (cfg.mentionOnly ? "仅@/私聊" : "全部聊天")
                + "  |  回复方式：" + (cfg.replyAsChat ? "发进聊天栏" : "仅本地显示"), Formatting.GRAY));
        ctx.getSource().sendFeedback(t("自动连接：" + cfg.autoConnect + "  |  转发自己的话：" + cfg.forwardOwnChat
                + "  |  AI 命令：" + cfg.allowAiCommands, Formatting.GRAY));
        ctx.getSource().sendFeedback(t("TrChat 兼容：" + switch (cfg.trchatMode) {
                        case "on" -> "强制开";
                        case "off" -> "关";
                        default -> "自动检测";
                    }
                    + (cfg.trchatActive() ? "  |  回复频道命令："
                            + (cfg.trchatReplyCommand.isEmpty() ? "（直接发普通聊天）" : "/" + cfg.trchatReplyCommand)
                            : ""), Formatting.GRAY));
        return 1;
    }

    private int sendHelp(CommandContext<FabricClientCommandSource> ctx) {
        ctx.getSource().sendFeedback(t("— /aiws 用法 —", Formatting.GOLD));
        ctx.getSource().sendFeedback(t("/aiws connect | disconnect | status | save | reload", Formatting.GRAY));
        ctx.getSource().sendFeedback(t("/aiws url <ws://或wss://地址>", Formatting.GRAY));
        ctx.getSource().sendFeedback(t("/aiws ai <AI名字>  被@时的名字", Formatting.GRAY));
        ctx.getSource().sendFeedback(t("/aiws mode <mention|all>  仅@转发 / 全部转发", Formatting.GRAY));
        ctx.getSource().sendFeedback(t("/aiws reply <chat|local>  回复发聊天栏 / 仅本地", Formatting.GRAY));
        ctx.getSource().sendFeedback(t("/aiws trchat <auto|on|off>  TrChat 兼容：自动检测(默认)/强制开/关",
                Formatting.GRAY));
        ctx.getSource().sendFeedback(t("/aiws trchatcmd <命令名|off>  AI 回复走哪个频道命令（如 g / all）",
                Formatting.GRAY));
        ctx.getSource().sendFeedback(t("/aiws autoconnect|forwardme|commands <true|false>", Formatting.GRAY));
        ctx.getSource().sendFeedback(t("/aiws test <文本>  模拟一条 AI 回复", Formatting.GRAY));
        return 1;
    }

    private static Text t(String s, Formatting f) {
        return Text.literal(s).formatted(f);
    }
}
