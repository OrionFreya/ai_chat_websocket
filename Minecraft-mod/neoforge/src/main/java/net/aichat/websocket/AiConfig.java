package net.aichat.websocket;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gson 落盘配置：{@code <游戏目录>/config/ai_chat_websocket.json}。
 * load 时 normalize()：越界修正、trchatMode 非法值回退 auto、字符串 "true"/"false" 折算 on/off、
 * 正则编译失败回退默认。旧文件只有布尔 trchatCompat 时迁移：true→on、false→auto，并立即回写。
 */
public final class AiConfig {
    public static final String FILE_NAME = "ai_chat_websocket.json";

    public static final String DEFAULT_URL = "ws://127.0.0.1:8765";
    public static final String DEFAULT_AI_NAME = "AI";
    public static final String DEFAULT_TRCHAT_PATTERN =
            "^(?:\\[([^\\]]{1,32})\\]\\s*)*([A-Za-z0-9_]{2,16})\\s*(?:[:：»⇒→]|->)\\s*(.+)$";

    private static final Logger LOGGER = LoggerFactory.getLogger("ai_chat_websocket");
    private static final Gson GSON = new Gson();

    public String url = DEFAULT_URL;
    public String aiName = DEFAULT_AI_NAME;
    /** true＝仅被@/私聊时转发；false＝全量转发 */
    public boolean mentionOnly = true;
    /** true＝AI 回复以玩家身份发聊天栏；false＝仅本地屏幕显示 */
    public boolean replyAsChat = true;
    /** 进游戏自动连接 */
    public boolean autoConnect = true;
    /** 自己打的普通聊天也转发 */
    public boolean forwardOwnChat = true;
    /** 忽略发送者名＝aiName 的聊天（防别的 AI 客户端回声） */
    public boolean ignoreAiNameMessages = true;
    /** 允许 AI 回复以 / 开头时当命令执行（危险） */
    public boolean allowAiCommands = false;
    /** 两条回复最小间隔（tick），防刷屏被踢 */
    public int replyMinIntervalTicks = 20;
    /** 回复截断长度（游戏上限 256） */
    public int maxReplyChars = 240;
    /** TrChat 兼容三档：auto 自动检测 / on 强制开 / off 关 */
    public String trchatMode = "auto";
    /** 拆 "[频道] 玩家名: 内容"，组1=频道 组2=名字 组3=正文 */
    public String trchatParsePattern = DEFAULT_TRCHAT_PATTERN;
    /** AI 回复改走该频道命令（如 g）；空＝直发普通聊天 */
    public String trchatReplyCommand = "";

    /** 编译后的解析正则；transient，加载/normalize 时重建 */
    public transient Pattern trchatPattern;

    public static Path configFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }

    /** 从游戏目录读取；文件缺失/损坏时回退默认值并写出。含旧布尔字段迁移。 */
    public static AiConfig load() {
        Path path = configFile();
        try {
            if (Files.isRegularFile(path)) {
                JsonElement root;
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    root = JsonParser.parseReader(reader);
                }
                if (root.isJsonObject()) {
                    JsonObject obj = root.getAsJsonObject();
                    AiConfig cfg = GSON.fromJson(obj, AiConfig.class);
                    if (cfg == null) {
                        cfg = new AiConfig();
                    }
                    boolean migrated = false;
                    boolean hasMode = obj.has("trchatMode") && obj.get("trchatMode").isJsonPrimitive();
                    if (!hasMode && obj.has("trchatCompat") && obj.get("trchatCompat").isJsonPrimitive()) {
                        // 旧配置迁移：布尔 trchatCompat，true→on、false→auto
                        JsonElement old = obj.get("trchatCompat");
                        boolean value = old.isJsonPrimitive() && old.getAsJsonPrimitive().isBoolean()
                                ? old.getAsBoolean()
                                : Boolean.parseBoolean(old.getAsString());
                        cfg.trchatMode = value ? "on" : "auto";
                        migrated = true;
                    }
                    cfg.normalize();
                    if (migrated) {
                        LOGGER.info("Migrated legacy boolean trchatCompat to trchatMode={}", cfg.trchatMode);
                        cfg.save();
                    }
                    return cfg;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read config {}, using defaults", path, e);
        }
        AiConfig cfg = new AiConfig();
        cfg.normalize();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Path path = configFile();
            Files.createDirectories(path.getParent());
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(this);
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Failed to write config", e);
        }
    }

    /** 越界修正 + 非法值回退 + 正则重建。 */
    public void normalize() {
        url = normalizeUrl(url == null || url.isBlank() ? DEFAULT_URL : url.trim());
        aiName = aiName == null || aiName.isBlank() ? DEFAULT_AI_NAME : aiName.trim();
        replyMinIntervalTicks = clamp(replyMinIntervalTicks, 0, 200);
        maxReplyChars = clamp(maxReplyChars, 1, 256);
        trchatMode = normalizeMode(trchatMode);
        trchatReplyCommand = trchatReplyCommand == null ? "" : trchatReplyCommand.trim();
        if (trchatParsePattern == null || trchatParsePattern.isBlank()) {
            trchatParsePattern = DEFAULT_TRCHAT_PATTERN;
        }
        try {
            trchatPattern = Pattern.compile(trchatParsePattern);
        } catch (Exception e) {
            LOGGER.warn("Invalid trchatParsePattern, falling back to default");
            trchatParsePattern = DEFAULT_TRCHAT_PATTERN;
            trchatPattern = Pattern.compile(DEFAULT_TRCHAT_PATTERN);
        }
    }

    /** auto/on/off 之外的非法值：字符串 "true"/"false" 折算 on/off，其余回退 auto。 */
    public static String normalizeMode(String mode) {
        if (mode == null) {
            return "auto";
        }
        String m = mode.trim().toLowerCase(Locale.ROOT);
        switch (m) {
            case "auto", "on", "off" -> {
                return m;
            }
            case "true" -> {
                return "on";
            }
            case "false" -> {
                return "off";
            }
            default -> {
                return "auto";
            }
        }
    }

    /** http→ws、https→wss 自动改写。 */
    public static String normalizeUrl(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.regionMatches(true, 0, "http://", 0, 7)) {
            u = "ws://" + u.substring(7);
        } else if (u.regionMatches(true, 0, "https://", 0, 8)) {
            u = "wss://" + u.substring(8);
        }
        return u;
    }

    public static boolean isWebSocketUrl(String u) {
        return u != null && (u.regionMatches(true, 0, "ws://", 0, 5) || u.regionMatches(true, 0, "wss://", 0, 6))
                && u.length() > (u.regionMatches(true, 0, "ws://", 0, 5) ? 5 : 6);
    }

    public static boolean isBoolean(String s) {
        String v = s == null ? "" : s.toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("false");
    }

    public static boolean parseBoolean(String s) {
        return "true".equalsIgnoreCase(s);
    }

    /** 频道命令名合法性：[A-Za-z0-9_:.-]{1,32} */
    public static boolean isValidCommandName(String s) {
        return s != null && !s.isEmpty()
                && Pattern.compile("^[A-Za-z0-9_:.-]{1,32}$").matcher(s).matches();
    }

    public boolean trchatOff() {
        return trchatMode.equals("off");
    }

    public boolean trchatForced() {
        return trchatMode.equals("on");
    }

    public boolean trchatAuto() {
        return trchatMode.equals("auto");
    }

    /** trchatCompat 布尔快照字段：＝trchatMode != off（发给不认识新字段的旧 AI 服务）。 */
    public boolean trchatCompatFlag() {
        return !trchatOff();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
