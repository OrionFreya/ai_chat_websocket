"use strict";

/**
 * mc-chat 频道适配器：把「Minecraft 客户端模组转发的游戏聊天」接进 Cyrene 的对话系统。
 *
 * 契约与宿主约定（对齐 minecraft-bot 的 MinecraftChannelAdapter）：
 *  - start() 订阅桥的 chat 事件，归一化成入站消息后调用 this.onMessage(inMsg)，
 *    由宿主调度器跑一轮 Agent；
 *  - send(message) 把 Agent 出站回复按行拆分，逐条以 reply 类型发回模组客户端，
 *    模组会以玩家身份把回复发进游戏聊天栏；
 *  - getStatus() 供宿主显示频道状态。
 */
const { CHAT_LINE_LIMIT } = require("./ws-server");

/** 多行回复逐条发送的间隔，避免触发服务器刷屏保护。 */
const LINE_SEND_INTERVAL_MS = 1400;

const CAPABILITY = {
  text: true,
  image: false,
  audio: false,
  file: false,
  video: false,
  markdown: false,
  card: false,
  sticker: false,
  maxTextLength: CHAT_LINE_LIMIT,
};

class McChatChannelAdapter {
  constructor(bridge) {
    this.bridge = bridge;
    this.id = "mc-chat";
    this.displayName = "Minecraft 聊天";
    this.capability = CAPABILITY;
    /** 宿主调度器注入的入站回调。 */
    this.onMessage = null;
    this._unsubs = [];
  }

  async start() {
    this._unsubs.push(
      this.bridge.on("chat", (chat) => {
        void this._handleGameChat(chat);
      })
    );
  }

  async stop() {
    for (const unsub of this._unsubs) unsub();
    this._unsubs = [];
  }

  /** 模组转发的聊天 → 宿主入站消息。 */
  async _handleGameChat(chat) {
    if (!this.onMessage) return;
    // 「仅@时回应」过滤：没被 @ 的公共聊天不进对话
    if (this.bridge.respondOnlyMentioned === true && !chat.mentioned) return;
    const inMsg = {
      channel: this.id,
      // 游戏聊天本质是所有人共享的公共频道
      chatType: "group",
      messageId: `mcchat-${chat.time}-${Math.random().toString(36).slice(2, 8)}`,
      senderId: chat.senderUuid || chat.sender,
      senderName: chat.sender,
      chatId: "minecraft-client-chat",
      text: chat.message,
      at: new Date(chat.time),
      // 附加原始字段，供提示词 Provider 判断是否被直接 @
      meta: {
        mentioned: chat.mentioned,
        self: chat.self,
        target: chat.target,
        dimension: chat.dimension,
      },
    };
    try {
      await this.onMessage(inMsg);
    } catch (err) {
      // 调度器内部已记日志；这里兜底防异常冒泡到桥的事件循环
      console.error("[mc-chat] 调度器处理消息失败:", err);
    }
  }

  /** 出站：Agent 回复文本按行拆分，逐条发回模组客户端（以玩家身份进游戏聊天栏）。 */
  async send(message) {
    const text = (message.parts || [])
      .filter((p) => p.kind === "text" && typeof p.text === "string")
      .map((p) => String(p.text))
      .join("\n");
    const lines = text
      .split("\n")
      .map((l) => l.trim().slice(0, CHAT_LINE_LIMIT))
      .filter((l) => l.length > 0);
    if (lines.length === 0) return { ok: true };

    try {
      for (let i = 0; i < lines.length; i++) {
        if (i > 0) await new Promise((r) => setTimeout(r, LINE_SEND_INTERVAL_MS));
        const res = await this.bridge.sendReply(lines[i]);
        if (!res.ok) return res;
      }
      return { ok: true };
    } catch (err) {
      return { ok: false, error: err instanceof Error ? err.message : String(err) };
    }
  }

  getStatus() {
    const s = this.bridge.status();
    if (s.state === "running" && s.clients.length > 0) {
      const players = s.clients.map((c) => c.player || "未握手").join("、");
      return {
        enabled: true,
        phase: "running",
        message: `正在监听 ws://${s.host}:${s.port}，已连接 ${s.clients.length} 个客户端（${players}）`,
      };
    }
    if (s.state === "running") {
      return {
        enabled: true,
        phase: "idle",
        message: `正在监听 ws://${s.host}:${s.port}，等待模组客户端连接（进游戏后自动连）`,
      };
    }
    return { enabled: true, phase: "offline", message: "mc-chat 服务未启动" };
  }
}

module.exports = { McChatChannelAdapter };
