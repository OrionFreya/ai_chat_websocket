package cn.blockforge.generated.aichatwebsocket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模组配置。保存在 游戏目录/config/ai_chat_websocket.json，
 * 也可以直接编辑这个文件后用 /aiws reload 重载。
 */
public final class AiConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("ai_chat_websocket/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** WebSocket 服务地址，例：ws://127.0.0.1:8765 或 wss://your-server:8443/ws */
    public String url = "ws://127.0.0.1:8765";
    /** AI 的名字（默认玩家名）。聊天里出现 @这个名字 时触发回复 */
    public String aiName = "AI";
    /** true = 只有被@（或私聊）时才转发给 AI；false = 转发所有聊天 */
    public boolean mentionOnly = true;
    /** true = AI 的回复以玩家身份发到聊天栏（其他人可见）；false = 只在自己屏幕显示 */
    public boolean replyAsChat = true;
    /** 进入世界时自动连接 */
    public boolean autoConnect = true;
    /** 自己打字的普通聊天是否也转发给 AI（方便直接和 AI 对话） */
    public boolean forwardOwnChat = true;
    /** 忽略发送者名字等于 aiName 的聊天（防止别的 AI 玩家回声造成循环） */
    public boolean ignoreAiNameMessages = true;
    /** 是否允许 AI 回复以 / 开头时被当作命令执行（危险，默认关） */
    public boolean allowAiCommands = false;
    /** 两条 AI 回复之间的最小间隔（tick，20 tick = 1 秒），防刷屏被服务器踢 */
    public int replyMinIntervalTicks = 20;
    /** AI 回复最长多少个字符（游戏聊天单条上限 256） */
    public int maxReplyChars = 240;

    // ---------------------------------------------------------- TrChat 兼容

    /**
     * TrChat 兼容模式：
     * - "auto"（默认）：自动检测。服务器装了 TrChat（取消原版广播、把聊天以系统消息重发）时，
     *   模组认出“[频道] 玩家名: 内容”且发送者确实在当前在线列表里，就自动按玩家聊天收进来；
     *   普通服务器上的进服提示、公告因为对不上格式/不是在线玩家，不会误转发。
     * - "on"：强制收。凡是能按格式拆出来的系统消息都当聊天（含拆不出格式但 @ 到 AI 的公告）。
     * - "off"：完全不监听系统消息。
     */
    public String trchatMode = "auto";
    /** 拆解频道消息的正则，捕获组 1=频道标签(可无) 2=玩家名 3=内容。默认匹配 "[世界] Name: 内容" */
    public String trchatParsePattern =
            "^(?:\\[([^\\]]{1,32})\\]\\s*)*([A-Za-z0-9_]{2,16})\\s*(?:[:：»⇒→]|->)\\s*(.+)$";
    /** AI 回复通过该 TrChat 频道命令发出（如 g / all / shout）；留空 = 直接发普通聊天 */
    public String trchatReplyCommand = "";

    /** trchatParsePattern 编译结果（不落盘，加载时重建） */
    public transient java.util.regex.Pattern trchatPattern;

    public static final String DEFAULT_TRCHAT_PATTERN =
            "^(?:\\[([^\\]]{1,32})\\]\\s*)*([A-Za-z0-9_]{2,16})\\s*(?:[:：»⇒→]|->)\\s*(.+)$";
    public static final String DEFAULT_TRCHAT_MODE = "auto";

    /** 兼容模式是否生效（auto 或 on） */
    public boolean trchatActive() {
        return !"off".equals(trchatMode);
    }

    /** 是否处于自动检测档（只信任「格式匹配 + 发送者在线」的系统消息） */
    public boolean trchatAuto() {
        return "auto".equals(trchatMode);
    }

    public static AiConfig current = new AiConfig();

    private AiConfig() { }

    public static AiConfig create() {
        return new AiConfig();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("ai_chat_websocket.json");
    }

    public static void load() {
        Path path = file();
        try {
            if (Files.exists(path)) {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                AiConfig parsed = GSON.fromJson(json, AiConfig.class);
                if (parsed != null) {
                    current = parsed;
                    current.migrateLegacyTrchat(json);
                }
            } else {
                current = create();
                save();
            }
        } catch (Exception e) {
            LOGGER.warn("读取配置失败，使用默认值：{}", e.toString());
            current = create();
        }
        current.normalize();
    }

    /** 旧版配置只有 boolean trchatCompat：true 视为强制开，false 视为升级到自动检测 */
    private void migrateLegacyTrchat(String json) {
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(json);
            if (!el.isJsonObject()) {
                return;
            }
            com.google.gson.JsonObject o = el.getAsJsonObject();
            if (!o.has("trchatMode") && o.has("trchatCompat")
                    && o.get("trchatCompat").isJsonPrimitive()) {
                trchatMode = o.get("trchatCompat").getAsBoolean() ? "on" : DEFAULT_TRCHAT_MODE;
                save();
            }
        } catch (Exception ignored) {
            // 解析不出来就算了，normalize() 会兜底
        }
    }

    public static void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(current), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("保存配置失败：{}", e.toString());
        }
    }

    /** 修掉不合法的值，避免越界引起异常 */
    private void normalize() {
        if (url == null || url.isBlank()) {
            url = "ws://127.0.0.1:8765";
        }
        if (aiName == null || aiName.isBlank()) {
            aiName = "AI";
        }
        if (maxReplyChars < 8) {
            maxReplyChars = 8;
        }
        if (maxReplyChars > 256) {
            maxReplyChars = 256;
        }
        if (replyMinIntervalTicks < 0) {
            replyMinIntervalTicks = 0;
        }
        if (replyMinIntervalTicks > 200) {
            replyMinIntervalTicks = 200;
        }
        if (trchatParsePattern == null || trchatParsePattern.isBlank()) {
            trchatParsePattern = DEFAULT_TRCHAT_PATTERN;
        }
        if (trchatMode == null) {
            trchatMode = DEFAULT_TRCHAT_MODE;
        }
        trchatMode = trchatMode.strip().toLowerCase(java.util.Locale.ROOT);
        if (trchatMode.equals("true")) {
            trchatMode = "on";
        } else if (trchatMode.equals("false")) {
            trchatMode = "off";
        }
        if (!(trchatMode.equals("auto") || trchatMode.equals("on") || trchatMode.equals("off"))) {
            trchatMode = DEFAULT_TRCHAT_MODE;
        }
        try {
            trchatPattern = java.util.regex.Pattern.compile(trchatParsePattern);
        } catch (Exception e) {
            LOGGER.warn("trchatParsePattern 不是合法正则，已回退默认值：{}", e.toString());
            trchatParsePattern = DEFAULT_TRCHAT_PATTERN;
            trchatPattern = java.util.regex.Pattern.compile(trchatParsePattern);
        }
        if (trchatReplyCommand == null) {
            trchatReplyCommand = "";
        }
        trchatReplyCommand = trchatReplyCommand.strip();
        if (trchatReplyCommand.startsWith("/")) {
            trchatReplyCommand = trchatReplyCommand.substring(1).strip();
        }
    }
}
