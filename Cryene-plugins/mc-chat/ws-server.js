"use strict";

/**
 * mc-chat 桥接核心：在本地启动 WebSocket 服务器，对接 Minecraft 客户端模组
 * （ai_chat_websocket，参考 C:\mmod\mod）。
 *
 * 模组侧协议（来自 AiChatWebsocketClient / AiBridgeClient）：
 *  - 模组主动连到 ws://127.0.0.1:8765（可配置），断线后 2s~30s 指数退避自动重连；
 *  - 连接建立后模组先发一条 hello 握手：
 *      {"type":"hello","modId":"ai_chat_websocket","modVersion":"1.0.0","minecraft":"1.21.1","player":"Steve","playerUuid":"..."}
 *  - 之后每条聊天转发为：
 *      {"type":"chat","sender","senderUuid","message","mentioned","self","target","dimension","time"}
 *  - 服务端（本插件）可回：
 *      {"type":"reply","message":"..."}            → 以玩家身份发进游戏聊天栏
 *      {"type":"message"|"chat"|"say",...}         → 同上（message/content/text 任取一个）
 *      {"type":"notice"|"status","message":"..."}  → 只在本地屏幕提示，不进聊天栏
 *      {"type":"ping"}                             → 模组会回 {"type":"pong"}
 *  - 模组回显的 AI 回复（回声）会被它自己的 aiReplyGuard 拦住，不会再次转发给本插件。
 */
const path = require("node:path");

// ws 库从插件自带 node_modules 解析，不依赖宿主环境
const { WebSocketServer } = require(path.join(__dirname, "node_modules", "ws"));

/** 单条游戏聊天长度上限（模组侧也会截断到 256）。 */
const CHAT_LINE_LIMIT = 256;

class McBridge {
  /**
   * @param {object} options
   * @param {(msg: string) => void} log 宿主日志函数
   */
  constructor(log) {
    this.log = log || ((msg) => console.log("[mc-chat]", msg));
    this.wss = null;
    this.host = "127.0.0.1";
    this.port = 8765;
    /** 连接中的模组客户端列表。 */
    this.clients = new Set();
    /** 最近一次收到 chat 的客户端（回复默认发给它）。 */
    this.lastActiveClient = null;
    /** 是否把回复广播给所有已连接的模组客户端。 */
    this.broadcastReplies = false;
    this.chatsReceived = 0;
    this.repliesSent = 0;
    this.noticesSent = 0;
    /** 被识别为自身回声而丢弃的聊天数（防循环）。 */
    this.suppressedEchoes = 0;
    /** 最近发出的回复文本（回声抑制用）。 */
    this.recentReplies = [];
    /** 模组侧配置的 @ 名字（从 chat.target 学到）。 */
    this.aiName = null;
    /** 是否只回应被 @ 的消息（仅@模式，由频道适配器读取）。 */
    this.respondOnlyMentioned = false;
    /** 相邻两条回复的最小间隔（毫秒），防止刷屏。 */
    this.minReplyIntervalMs = 1000;
    this.lastReplyAt = 0;
    /** 心跳间隔句柄。 */
    this.heartbeatTimer = null;
    /** 事件订阅函数集合（onChat / onClient / onState）。 */
    this._handlers = { chat: [], client: [], state: [] };
  }

  on(event, fn) {
    const list = this._handlers[event];
    if (!list) return () => {};
    list.push(fn);
    // 返回退订函数，便于频道 stop() 时清理
    return () => {
      const i = list.indexOf(fn);
      if (i >= 0) list.splice(i, 1);
    };
  }

  _emit(event, ...args) {
    for (const fn of this._handlers[event] || []) {
      try {
        fn(...args);
      } catch (err) {
        this.log(`事件处理出错(${event}): ${err instanceof Error ? err.message : String(err)}`);
      }
    }
  }

  /** 启动 WebSocket 服务器。 */
  async start({ host, port, broadcastReplies } = {}) {
    if (this.wss) return this.status();
    this.host = host || "127.0.0.1";
    this.port = Number(port) || 8765;
    this.broadcastReplies = !!broadcastReplies;

    const wss = new WebSocketServer({ host: this.host, port: this.port });
    this.wss = wss;

    wss.on("connection", (ws) => this._handleConnection(ws));
    wss.on("error", (err) => {
      this.log(`WebSocket 服务器错误: ${err instanceof Error ? err.message : String(err)}`);
      this._emit("state", { state: "error", error: err });
    });

    // 协议级心跳：30s 一次，探测死连接
    this.heartbeatTimer = setInterval(() => this._heartbeat(), 30_000);
    if (this.heartbeatTimer.unref) this.heartbeatTimer.unref();

    this.log(`mc-chat 服务已启动 ws://${this.host}:${this.port}`);
    this._emit("state", { state: "running", host: this.host, port: this.port });
    return this.status();
  }

  /** 停止服务器并断开所有客户端。 */
  async stop() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    const wss = this.wss;
    this.wss = null;
    if (wss) {
      for (const c of this.clients) {
        try {
          c.ws.close(1001, "server shutdown");
        } catch { /* ignore */ }
      }
      await new Promise((resolve) => {
        wss.close(() => resolve());
        // 兜底：30s 内没关完直接强制结束
        setTimeout(resolve, 30_000);
      });
    }
    this.clients.clear();
    this.lastActiveClient = null;
    this._emit("state", { state: "stopped" });
  }

  _handleConnection(ws) {
    const client = {
      ws,
      player: null,
      playerUuid: null,
      modId: null,
      modVersion: null,
      minecraft: null,
      connectedAt: Date.now(),
      lastSeen: Date.now(),
    };
    this.clients.add(client);
    this.log(`模组客户端已连接（当前 ${this.clients.size} 个）`);

    ws.on("message", (data, isBinary) => {
      client.lastSeen = Date.now();
      if (isBinary) return; // 模组只用文本帧
      this._handleMessage(client, data.toString());
    });
    ws.on("close", () => {
      this.clients.delete(client);
      if (this.lastActiveClient === client) this.lastActiveClient = null;
      this.log(`模组客户端断开（${client.player || "未握手"}，当前 ${this.clients.size} 个）`);
      this._emit("client", { type: "leave", client });
    });
    ws.on("error", (err) => {
      this.log(`客户端连接错误: ${err instanceof Error ? err.message : String(err)}`);
    });
  }

  _handleMessage(client, raw) {
    let obj = null;
    try {
      obj = JSON.parse(raw);
    } catch {
      // 不是 JSON：模组不会发这种消息，忽略
      return;
    }
    if (!obj || typeof obj !== "object") return;
    const type = typeof obj.type === "string" ? obj.type.toLowerCase() : null;

    switch (type) {
      case "hello": {
        client.player = typeof obj.player === "string" ? obj.player : client.player;
        client.playerUuid = typeof obj.playerUuid === "string" ? obj.playerUuid : client.playerUuid;
        client.modId = typeof obj.modId === "string" ? obj.modId : client.modId;
        client.modVersion = typeof obj.modVersion === "string" ? obj.modVersion : client.modVersion;
        client.minecraft = typeof obj.minecraft === "string" ? obj.minecraft : client.minecraft;
        this.log(`模组握手: mod=${client.modId}@${client.modVersion} 玩家=${client.player}`);
        this._emit("client", { type: "hello", client });
        break;
      }
      case "chat": {
        const message = typeof obj.message === "string" ? obj.message : "";
        if (!message.trim()) break;
        // 学习模组侧配置的 @ 名字（target 由 /aiws ai <名字> 决定）
        if (typeof obj.target === "string" && obj.target.trim()) {
          this.aiName = obj.target.trim();
        }
        // 回声抑制：我们的回复以玩家身份发出后，服务器回显 / 其他装了模组的客户端
        // 会把它当作普通聊天转发回来。内容匹配最近发过的回复 → 丢弃，切断循环。
        if (this._isEcho(message)) {
          this.suppressedEchoes += 1;
          this.log(`已抑制回声聊天（防循环）: ${message.slice(0, 40)}`);
          break;
        }
        this.lastActiveClient = client;
        this.chatsReceived += 1;
        this._emit("chat", {
          client,
          sender: typeof obj.sender === "string" ? obj.sender : "?",
          senderUuid: typeof obj.senderUuid === "string" ? obj.senderUuid : null,
          message,
          mentioned: obj.mentioned === true,
          self: obj.self === true,
          target: typeof obj.target === "string" ? obj.target : null,
          dimension: typeof obj.dimension === "string" ? obj.dimension : null,
          time: typeof obj.time === "number" ? obj.time : Date.now(),
        });
        break;
      }
      case "ping": {
        // 模组主动 ping 时回 pong（模组本身不主动发，但保持对称）
        this._sendTo(client, { type: "pong" });
        break;
      }
      case "pong":
        break;
      default:
        break; // 忽略未知类型
    }
  }

  /**
   * 把一条回复发回游戏（模组会以玩家身份发进聊天栏）。
   * 受全局最小回复间隔限制（防刷屏），并按需记录到回声缓冲。
   * @param {string} text 纯文本回复
   */
  async sendReply(text) {
    if (!this.wss || this.clients.size === 0) {
      return { ok: false, error: "没有已连接的 Minecraft 客户端（模组未连接）" };
    }
    const waitMs = this.lastReplyAt ? Math.max(0, this.minReplyIntervalMs - (Date.now() - this.lastReplyAt)) : 0;
    if (waitMs > 0) await new Promise((r) => setTimeout(r, waitMs));
    this.lastReplyAt = Date.now();

    const payload = { type: "reply", message: text };
    if (this.broadcastReplies) {
      for (const c of this.clients) this._sendTo(c, payload);
    } else {
      const target = this.lastActiveClient || [...this.clients][0];
      if (!target || target.ws.readyState !== target.ws.OPEN) {
        return { ok: false, error: "目标客户端已断开" };
      }
      this._sendTo(target, payload);
    }
    this.repliesSent += 1;
    this._rememberReply(text);
    return { ok: true };
  }

  /** 发一条仅本地屏幕显示的提示（不进聊天栏）。 */
  sendNotice(text) {
    if (!this.wss || this.clients.size === 0) {
      return { ok: false, error: "没有已连接的 Minecraft 客户端（模组未连接）" };
    }
    const payload = { type: "notice", message: text };
    const target = this.lastActiveClient || [...this.clients][0];
    if (target && target.ws.readyState === target.ws.OPEN) {
      this._sendTo(target, payload);
      this.noticesSent += 1;
    }
    return { ok: true };
  }

  _sendTo(client, obj) {
    try {
      client.ws.send(JSON.stringify(obj));
    } catch (err) {
      this.log(`发送失败: ${err instanceof Error ? err.message : String(err)}`);
    }
  }

  // ------------------------------------------------------------ 回声抑制

  /** 回声缓冲保留窗口：30 秒内发出的回复都参与匹配。 */
  static get ECHO_WINDOW_MS() {
    return 30_000;
  }

  /** 归一化：去 § 控制符、折叠空白、小写、截断（与模组 sanitizeChatText 大致对齐）。 */
  static normalizeText(t) {
    const out = String(t || "")
      .replace(/[\u00a7§]/g, "")
      .replace(/[\r\n\t ]+/g, " ")
      .trim()
      .toLowerCase();
    return out.slice(0, CHAT_LINE_LIMIT);
  }

  _rememberReply(text) {
    const now = Date.now();
    this.recentReplies = this.recentReplies.filter((r) => now - r.at < McBridge.ECHO_WINDOW_MS);
    this.recentReplies.push({ text: String(text || ""), at: now });
    if (this.recentReplies.length > 50) this.recentReplies.shift();
  }

  /** 入站聊天是否匹配最近发过的回复（我们的回声）。 */
  _isEcho(message) {
    const now = Date.now();
    const norm = McBridge.normalizeText(message);
    if (!norm) return false;
    return this.recentReplies.some((r) => {
      if (now - r.at >= McBridge.ECHO_WINDOW_MS) return false;
      const rn = McBridge.normalizeText(r.text);
      if (!rn) return false;
      // 完全一致，或任一侧被截断/附加内容后包含对方
      return rn === norm || rn.includes(norm) || norm.includes(rn);
    });
  }

  _heartbeat() {
    const now = Date.now();
    for (const c of [...this.clients]) {
      if (now - c.lastSeen > 60_000) {
        this.log(`心跳超时，断开 ${c.player || "未握手客户端"}`);
        try {
          c.ws.terminate();
        } catch { /* ignore */ }
        this.clients.delete(c);
        if (this.lastActiveClient === c) this.lastActiveClient = null;
      } else if (c.ws.readyState === c.ws.OPEN) {
        try {
          c.ws.ping();
        } catch { /* ignore */ }
      }
    }
  }

  /** 当前状态快照（供工具 / IPC / 提示词注入使用）。 */
  status() {
    return {
      state: this.wss ? "running" : "stopped",
      host: this.host,
      port: this.port,
      broadcastReplies: this.broadcastReplies,
      respondOnlyMentioned: this.respondOnlyMentioned === true,
      minReplyIntervalMs: this.minReplyIntervalMs,
      aiName: this.aiName,
      clients: [...this.clients].map((c) => ({
        player: c.player,
        playerUuid: c.playerUuid,
        modId: c.modId,
        modVersion: c.modVersion,
        minecraft: c.minecraft,
        connectedAt: c.connectedAt,
        lastSeen: c.lastSeen,
      })),
      chatsReceived: this.chatsReceived,
      repliesSent: this.repliesSent,
      noticesSent: this.noticesSent,
      suppressedEchoes: this.suppressedEchoes,
      chatLineLimit: CHAT_LINE_LIMIT,
    };
  }
}

module.exports = { McBridge, CHAT_LINE_LIMIT };
