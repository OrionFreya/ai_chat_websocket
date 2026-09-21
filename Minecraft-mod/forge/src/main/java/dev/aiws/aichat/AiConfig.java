package dev.aiws.aichat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 配置（Gson 落盘，游戏目录 config/ai_chat_websocket.json）。
 * 字段、默认值、文件名与 Fabric 端 1.0.0-r3 逐字段一致，两端可共用同一份配置。
 */
public final class AiConfig {

    public static final String DEFAULT_URL = "ws://127.0.0.1:8765";
    public static final String DEFAULT_AI_NAME = "AI";
    /** 拆 `[频道] 玩家名: 内容`：组1=频道 组2=名字 组3=正文 */
    public static final String DEFAULT_TRCHAT_PATTERN =
            "^(?:\\[([^\\]]{1,32})\\]\\s*)*([A-Za-z0-9_]{2,16})\\s*(?:[:：»⇒→]|->)\\s*(.+)$";
    public static final String FILE_NAME = "ai_chat_websocket.json";

    /** 回复频道命令名合法性：[A-Za-z0-9_:.-]{1,32} */
    private static final Pattern TRCHAT_CMD_NAME = Pattern.compile("[A-Za-z0-9_:.-]{1,32}");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String url = DEFAULT_URL;
    public String aiName = DEFAULT_AI_NAME;
    /** true=仅被@/私聊时转发；false=全量 */
    public boolean mentionOnly = true;
    /** true=AI 回复以玩家身份发聊天栏；false=仅本地屏幕显示 */
    public boolean replyAsChat = true;
    public boolean autoConnect = true;
    public boolean forwardOwnChat = true;
    public boolean ignoreAiNameMessages = true;
    public boolean allowAiCommands = false;
    public int replyMinIntervalTicks = 20;
    /** 回复截断长度（游戏消息上限 256） */
    public int maxReplyChars = 240;
    /** TrChat 兼容三档：auto 自动检测 / on 强制开 / off 关 */
    public String trchatMode = "auto";
    public String trchatParsePattern = DEFAULT_TRCHAT_PATTERN;
    /** AI 回复改走该频道命令（如 g）；空=直发普通聊天 */
    public String trchatReplyCommand = "";

    /** 由 trchatParsePattern 加载时重建（transient，Gson 不持久化） */
    public transient Pattern trchatPattern;

    private AiConfig() {
        // Gson 反射构造时会先跑字段初始化器，缺省字段即默认值
    }

    public static boolean isValidReplyCommand(String cmd) {
        return cmd != null && !cmd.isEmpty() && TRCHAT_CMD_NAME.matcher(cmd).matches();
    }

    /** http→ws、https→wss 自动改写 */
    public static String wsUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("http://")) {
            s = "ws://" + s.substring("http://".length());
        } else if (s.startsWith("https://")) {
            s = "wss://" + s.substring("https://".length());
        }
        return s;
    }

    public static boolean isWsScheme(String s) {
        return s != null && (s.startsWith("ws://") || s.startsWith("wss://"));
    }

    /** 读盘 + 旧配置迁移 + normalize。任何异常回退默认值，不阻塞游戏启动。 */
    public static AiConfig load(Path file) {
        AiConfig cfg = new AiConfig();
        boolean migrated = false;
        try {
            if (Files.exists(file)) {
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
                // 旧配置迁移：只有布尔 trchatCompat、没有 trchatMode 时：true→on，false→auto
                if (obj.has("trchatCompat") && !obj.has("trchatMode")) {
                    migrated = true;
                }
                cfg = GSON.fromJson(obj, AiConfig.class);
                if (cfg == null) {
                    cfg = new AiConfig();
                }
                if (migrated) {
                    boolean compat = obj.get("trchatCompat").isJsonPrimitive()
                            && obj.get("trchatCompat").getAsBoolean();
                    cfg.trchatMode = compat ? "on" : "auto";
                }
            } else {
                migrated = true; // 首次落一份默认配置，两端互通用
            }
        } catch (Exception e) {
            AiChatWebsocket.LOGGER.warn("[ai_chat_websocket] 读取配置失败，使用默认值: {}", e.toString());
            cfg = new AiConfig();
        }
        cfg.normalize();
        if (migrated) {
            cfg.save(file); // 迁移/首建后立即回写
        }
        return cfg;
    }

    /** 越界修正、非法值回退、正则重建 */
    public void normalize() {
        url = wsUrl(url);
        if (!isWsScheme(url) || url.length() < 6) {
            url = DEFAULT_URL;
        }
        aiName = aiName == null ? "" : aiName.trim();
        if (aiName.isEmpty()) {
            aiName = DEFAULT_AI_NAME;
        }
        if (aiName.length() > 32) {
            aiName = aiName.substring(0, 32);
        }
        replyMinIntervalTicks = clamp(replyMinIntervalTicks, 0, 200);
        maxReplyChars = clamp(maxReplyChars, 16, 256);

        trchatMode = trchatMode == null ? "auto" : trchatMode.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(trchatMode)) {
            trchatMode = "on";
        } else if ("false".equals(trchatMode)) {
            trchatMode = "off";
        } else if (!"auto".equals(trchatMode) && !"on".equals(trchatMode) && !"off".equals(trchatMode)) {
            trchatMode = "auto";
        }

        trchatReplyCommand = trchatReplyCommand == null ? "" : trchatReplyCommand.trim();
        if (!trchatReplyCommand.isEmpty() && !isValidReplyCommand(trchatReplyCommand)) {
            trchatReplyCommand = "";
        }

        trchatParsePattern = trchatParsePattern == null || trchatParsePattern.trim().isEmpty()
                ? DEFAULT_TRCHAT_PATTERN : trchatParsePattern.trim();
        try {
            trchatPattern = Pattern.compile(trchatParsePattern);
        } catch (PatternSyntaxException e) {
            trchatParsePattern = DEFAULT_TRCHAT_PATTERN;
            trchatPattern = Pattern.compile(DEFAULT_TRCHAT_PATTERN);
        }
    }

    public boolean save(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            AiChatWebsocket.LOGGER.warn("[ai_chat_websocket] 保存配置失败: {}", e.toString());
            return false;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
