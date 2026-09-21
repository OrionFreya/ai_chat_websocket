"use strict";

/**
 * mc-chat —— Cyrene 插件：把 Minecraft 客户端模组（ai_chat_websocket）接进 Agent。
 *
 * 数据流：
 *   游戏聊天 → 模组(客户端) → WebSocket → 本插件(McBridge)
 *        → 频道适配器(McChatChannelAdapter) → Cyrene 对话调度器跑一轮 Agent
 *        → 出站回复 → 频道 send() → reply 发回模组 → 以玩家身份发进游戏聊天栏
 *
 * 插件 API 契约对齐现有插件（minecraft-bot / system-status）：
 *   module.exports = { register(ctx), open(), unregister() }
 */
const path = require("node:path");
const { McBridge } = require("./ws-server");
const { McChatChannelAdapter } = require("./channel");

/** 配置窗口实例；open() 创建，unregister() 关闭。 */
let configWin = null;

const SETTINGS_KEYS = {
  host: "host",
  port: "port",
  broadcastReplies: "broadcastReplies",
  respondOnlyMentioned: "respondOnlyMentioned",
  minReplyIntervalMs: "minReplyIntervalMs",
};

function defaultSettings() {
  return {
    host: "127.0.0.1",
    port: 8765,
    broadcastReplies: false,
    // 默认仅在被 @ 时回应：避免把游戏里所有公共聊天都当成对话对象，刷屏扰民
    respondOnlyMentioned: true,
    // 相邻两条回复的最小间隔（毫秒），配合模组侧 1s 限速防刷屏
    minReplyIntervalMs: 1000,
  };
}

const plugin = {
  async register(ctx) {
    const bridge = new McBridge((msg) => ctx.log(msg));

    // 从 storage 恢复配置并启动服务
    const settings = { ...defaultSettings() };
    for (const [key, storageKey] of Object.entries(SETTINGS_KEYS)) {
      const v = ctx.storage.get(storageKey);
      if (v !== undefined && v !== null) settings[key] = v;
    }
    settings.port = Number(settings.port) || 8765;
    settings.minReplyIntervalMs = Math.min(Math.max(Number(settings.minReplyIntervalMs) || 0, 0), 10_000);

    // 频道适配器的过滤逻辑依赖桥上的这份配置
    bridge.respondOnlyMentioned = settings.respondOnlyMentioned === true;
    bridge.minReplyIntervalMs = settings.minReplyIntervalMs;

    try {
      await bridge.start({
        host: settings.host,
        port: settings.port,
        broadcastReplies: settings.broadcastReplies === true,
      });
    } catch (err) {
      ctx.log(`mc-chat 服务启动失败: ${err instanceof Error ? err.message : String(err)}`);
      ctx.log("请检查端口是否被占用，可在插件设置中修改端口后重试。");
    }

    // ---- 工具 ----
    ctx.registerTool({
      id: "mc-chat_status",
      enabled: true,
      name: "查看 Minecraft 聊天桥状态",
      description:
        "查看 Minecraft 客户端模组（ai_chat_websocket）桥接状态：服务是否在监听、已连接的模组客户端（玩家）、收到/回复的消息数、当前端口。用户问「MC 桥接好了吗」「游戏连上了吗」「模组在线吗」时用。",
      risk: "safe",
      effectKind: "read",
      inputSchema: { type: "object", properties: {} },
      async execute() {
        return formatStatus(bridge.status());
      },
    });

    ctx.registerTool({
      id: "mc-chat_say",
      enabled: true,
      name: "在 Minecraft 游戏里说话",
      description:
        "主动以玩家身份在 Minecraft 游戏聊天栏说一句话（通过已连接的模组客户端发送）。适合在没被 @ 时主动开口、或频道回复失效时兜底。",
      risk: "safe",
      effectKind: "mutation",
      inputSchema: {
        type: "object",
        properties: {
          message: { type: "string", description: "要在游戏里说的话" },
        },
        required: ["message"],
      },
      async execute(args) {
        const message = String(args.message ?? "").trim();
        if (!message) throw new Error("消息内容不能为空");
        if (message.length > 256) throw new Error("游戏聊天单条最长 256 字符");
        const res = bridge.sendReply(message);
        if (!res.ok) throw new Error(res.error);
        return `你已在游戏里说: ${message}`;
      },
    });

    ctx.registerTool({
      id: "mc-chat_notice",
      enabled: true,
      name: "给玩家发一条屏幕提示",
      description:
        "给玩家发一条仅本地屏幕显示的提示（带 [AI-WS] 前缀，不会发进游戏聊天栏），适合通知「我在线/服务重启了」这类不打扰聊天的信息。",
      risk: "safe",
      effectKind: "mutation",
      inputSchema: {
        type: "object",
        properties: {
          message: { type: "string", description: "提示内容" },
        },
        required: ["message"],
      },
      async execute(args) {
        const message = String(args.message ?? "").trim();
        if (!message) throw new Error("提示内容不能为空");
        const res = bridge.sendNotice(message);
        if (!res.ok) throw new Error(res.error);
        return `已向玩家发送屏幕提示: ${message}`;
      },
    });

    // ---- 频道：游戏聊天接入对话系统 ----
    await ctx.registerChannelAdapter(new McChatChannelAdapter(bridge));

    // ---- 配置窗口 IPC（ui.html 通过 plugin:mc-chat:<name> invoke）----
    ctx.registerIpc("status", () => bridge.status());
    ctx.registerIpc("getSettings", () => {
      const s = bridge.status();
      return {
        host: s.host,
        port: s.port,
        broadcastReplies: s.broadcastReplies,
        respondOnlyMentioned: bridge.respondOnlyMentioned === true,
        minReplyIntervalMs: bridge.minReplyIntervalMs,
        aiName: s.aiName,
      };
    });
    ctx.registerIpc("setSettings", async (arg) => {
      const input = arg || {};
      const host = String(input.host ?? "127.0.0.1").trim() || "127.0.0.1";
      const port = Number(input.port) || 8765;
      if (port < 1 || port > 65535) throw new Error("端口需在 1-65535 之间");
      const broadcastReplies = input.broadcastReplies === true;
      const respondOnlyMentioned = input.respondOnlyMentioned === true;
      const minReplyIntervalMs = Math.min(Math.max(Number(input.minReplyIntervalMs) || 0, 0), 10_000);

      ctx.storage.set(SETTINGS_KEYS.host, host);
      ctx.storage.set(SETTINGS_KEYS.port, port);
      ctx.storage.set(SETTINGS_KEYS.broadcastReplies, broadcastReplies);
      ctx.storage.set(SETTINGS_KEYS.respondOnlyMentioned, respondOnlyMentioned);
      ctx.storage.set(SETTINGS_KEYS.minReplyIntervalMs, minReplyIntervalMs);

      const old = bridge.status();
      const addrChanged = old.host !== host || Number(old.port) !== port;

      // 行为类设置即时生效；只有地址/端口变了才重启服务（避免打断游戏连接）
      bridge.respondOnlyMentioned = respondOnlyMentioned;
      bridge.minReplyIntervalMs = minReplyIntervalMs;
      bridge.broadcastReplies = broadcastReplies;
      if (addrChanged) {
        await bridge.stop();
        await bridge.start({ host, port, broadcastReplies });
        ctx.log(`mc-chat 已切换监听地址: ws://${host}:${port}`);
      }
      ctx.log(
        `mc-chat 配置已更新: 广播=${broadcastReplies}，仅@回复=${respondOnlyMentioned}，回复间隔=${minReplyIntervalMs}ms`
      );
      return bridge.status();
    });
    ctx.registerIpc("test", async () => {
      const res = await bridge.sendReply("【测试】mc-chat 桥接正常，这条消息来自 Agent 服务端。");
      return res.ok ? { ok: true, message: "测试消息已发送" } : { ok: false, error: res.error };
    });

    // ---- 游戏情境注入：只在本频道注入，桌面对话不受影响 ----
    const provider = {
      id: "mc-chat-context",
      // sources 必须只含宿主 registry 认可的场景字面量（conversation / scheduler / moments-post）
      sources: ["conversation"],
      provide(input) {
        if (input.channel !== "mc-chat") return "";
        const s = bridge.status();
        if (s.state !== "running") return "";
        const players =
          s.clients.length > 0
            ? s.clients.map((c) => c.player || "未握手").join("、")
            : "（暂无模组客户端连接）";
        // 模组侧配置的 @ 名字（从聊天消息的 target 学到；模组默认 "AI"）
        const mentionName = s.aiName || "AI";
        const lines = [
          "【你正在 Minecraft 游戏里，通过玩家的客户端与玩家交流】",
          `当前桥接服务 ws://${s.host}:${s.port}，已连接的模组客户端：${players}。`,
          "你在游戏聊天栏里的回复，会以玩家本人的身份发进聊天栏——其他玩家看到的是你借玩家的口在说话。",
          "",
          "【说话规则】",
          "· 只发纯文本：不要用 Markdown 标记、图片、表格、代码块、卡片；@ 符号会原样显示。",
          "· 单条消息最多 256 字符，多行内容会按行逐条发送，尽量简短。",
          `· 玩家用 @${mentionName}（不区分大小写）是对你直接说话；没被 @ 时是游戏里的公共聊天，你可以选择回应，也可以安静听着。`,
          "· 你在这个频道里只能说话，没有移动、挖矿、攻击等游戏操作能力（那是别的插件的事）。",
          "· 保持游戏内聊天的自然语气，像游戏里的小伙伴。",
        ];
        if (bridge.respondOnlyMentioned) {
          lines.push("· 当前设置为「仅被@时回应」：只在玩家明确 @ 你时才需要回复。");
        }
        return lines.join("\n");
      },
    };
    ctx.registerPromptProvider(provider);

    // ---- 清理 ----
    ctx.onDispose(async () => {
      await bridge.stop();
    });

    const s = bridge.status();
    ctx.log(
      `mc-chat 已加载: 服务 ${s.state === "running" ? "运行中" : "启动失败"}（ws://${s.host}:${s.port}），频道 mc-chat 已接入`
    );
  },

  /** 设置页/插件面板的"打开"按钮：弹出配置窗口。 */
  async open() {
    if (configWin && !configWin.isDestroyed()) {
      configWin.focus();
      return;
    }
    const { BrowserWindow } = require("electron");
    configWin = new BrowserWindow({
      width: 480,
      height: 620,
      minWidth: 420,
      minHeight: 520,
      autoHideMenuBar: true,
      backgroundColor: "#fff9fc",
      webPreferences: {
        nodeIntegration: true,
        contextIsolation: false,
      },
    });
    configWin.on("closed", () => {
      configWin = null;
    });
    await configWin.loadFile(path.join(__dirname, "ui.html"));
  },

  unregister() {
    if (configWin && !configWin.isDestroyed()) configWin.close();
  },
};

/** 状态快照整理成多行文本（供工具返回给模型看）。 */
function formatStatus(s) {
  const lines = [];
  lines.push(s.state === "running" ? `桥接服务: 运行中 ws://${s.host}:${s.port}` : "桥接服务: 未运行");
  lines.push(`已连接模组客户端: ${s.clients.length} 个`);
  for (const c of s.clients) {
    lines.push(`  - ${c.player || "未握手"}${c.modId ? `（${c.modId}@${c.modVersion || "?"}）` : ""}`);
  }
  lines.push(`收到聊天: ${s.chatsReceived} 条 | 发出回复: ${s.repliesSent} 条 | 发出提示: ${s.noticesSent} 条`);
  lines.push(`抑制回声: ${s.suppressedEchoes} 条（自动丢弃自己回复的回声，防循环）`);
  lines.push(`回复间隔: ${s.minReplyIntervalMs}ms | 仅@回应: ${s.respondOnlyMentioned ? "开" : "关"} | 广播: ${s.broadcastReplies ? "开" : "关"}`);
  lines.push(`模组 @ 名字: @${s.aiName || "AI"}（在游戏里 /aiws ai <名字> 修改）`);
  return lines.join("\n");
}

module.exports = plugin;
module.exports.default = plugin;
