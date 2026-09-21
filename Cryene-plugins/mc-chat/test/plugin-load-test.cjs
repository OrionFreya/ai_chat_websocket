"use strict";

/**
 * mc-chat 插件加载自测：用 mock ctx 调用 register()，验证：
 *  - 服务按 storage 配置启动；
 *  - 3 个工具、频道适配器、提示词 Provider、IPC 都正确注册；
 *  - 模拟模组客户端连接 + 发聊天 → 频道 onMessage 收到归一化消息；
 *  - 模拟 Agent 回复 → 模组客户端收到 reply。
 */
const path = require("node:path");
const assert = require("node:assert");
const WebSocket = require(path.join(__dirname, "..", "node_modules", "ws"));
const plugin = require("../index.cjs");

const PORT = 18767;

/** 简易 mock ctx，结构与宿主注入的 ctx 对齐。 */
function makeCtx() {
  const store = new Map();
  store.set("port", PORT);
  // 模拟用户开启了「仅@回应」+ 自定义回复间隔（回归：仅@ 配置下插件必须正常启动）
  store.set("respondOnlyMentioned", true);
  store.set("minReplyIntervalMs", 1500);
  const registered = { tools: [], channels: [], ipcs: new Map(), providers: [], disposed: [] };
  return {
    registered,
    log() {},
    storage: {
      get: (k) => (store.has(k) ? store.get(k) : undefined),
      set: (k, v) => store.set(k, v),
    },
    registerTool: (t) => registered.tools.push(t),
    registerChannelAdapter: async (a) => { registered.channels.push(a); },
    registerIpc: (name, fn) => registered.ipcs.set(name, fn),
    registerPromptProvider: (p) => registered.providers.push(p),
    onDispose: (fn) => registered.disposed.push(fn),
  };
}

async function main() {
  const ctx = makeCtx();
  assert.strictEqual(typeof plugin.register, "function");
  assert.strictEqual(typeof plugin.open, "function");
  assert.strictEqual(typeof plugin.unregister, "function");

  await plugin.register(ctx);

  // ---- 注册项检查 ----
  assert.strictEqual(ctx.registered.tools.length, 3, "应有 3 个工具");
  const toolIds = ctx.registered.tools.map((t) => t.id);
  assert.deepStrictEqual(toolIds, ["mc-chat_status", "mc-chat_say", "mc-chat_notice"]);
  assert.strictEqual(ctx.registered.channels.length, 1, "应有 1 个频道适配器");
  assert.strictEqual(ctx.registered.channels[0].id, "mc-chat");
  assert.strictEqual(ctx.registered.providers.length, 1, "应有 1 个提示词 Provider");
  assert.strictEqual(ctx.registered.providers[0].id, "mc-chat-context");
  assert.ok(ctx.registered.ipcs.has("status"));
  assert.ok(ctx.registered.ipcs.has("getSettings"));
  assert.ok(ctx.registered.ipcs.has("setSettings"));
  assert.ok(ctx.registered.ipcs.has("test"));
  assert.strictEqual(ctx.registered.disposed.length, 1);
  console.log("[PASS] 1. register() 注册了 3 工具 / 频道 / Provider / 4 IPC / dispose");

  // ---- 服务已按配置启动 ----
  const status = await ctx.registered.ipcs.get("status")();
  assert.strictEqual(status.state, "running");
  assert.strictEqual(status.port, PORT);
  // 「仅@」配置必须让插件正常启动（回归）
  assert.strictEqual(status.respondOnlyMentioned, true);
  assert.strictEqual(status.minReplyIntervalMs, 1500);
  console.log(`[PASS] 2. 服务按 storage 配置启动（仅@=${status.respondOnlyMentioned}，间隔=${status.minReplyIntervalMs}ms）: ws://${status.host}:${status.port}`);

  // ---- 模拟模组客户端：连接 + 发聊天 ----
  const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
  await new Promise((res, rej) => { ws.on("open", res); ws.on("error", rej); });
  const replies = [];
  ws.on("message", (d) => { try { replies.push(JSON.parse(d.toString())); } catch {} });
  ws.send(JSON.stringify({ type: "hello", player: "Steve", playerUuid: "u-1", modId: "ai_chat_websocket", modVersion: "1.0.0", minecraft: "1.21.1" }));

  // 等桥捕获到 hello
  await new Promise((r) => setTimeout(r, 300));

  const adapter = ctx.registered.channels[0];
  const inbound = [];
  adapter.onMessage = async (m) => { inbound.push(m); };
  await adapter.start();

  ws.send(JSON.stringify({
    type: "chat", sender: "Steve", senderUuid: "u-1",
    message: "@Cyrene 你好", mentioned: true, self: true,
    target: "Cyrene", dimension: "minecraft:overworld", time: Date.now(),
  }));
  await (async () => {
    const t0 = Date.now();
    while (inbound.length === 0 && Date.now() - t0 < 3000) await new Promise((r) => setTimeout(r, 30));
  })();
  assert.strictEqual(inbound.length, 1, "频道应收到入站消息");
  assert.strictEqual(inbound[0].text, "@Cyrene 你好");
  assert.strictEqual(inbound[0].senderId, "u-1");
  assert.strictEqual(inbound[0].meta.mentioned, true);
  assert.strictEqual(inbound[0].meta.self, true);
  console.log("[PASS] 3. 模组聊天 → 频道 onMessage 归一化入站消息");

  // ---- 仅@过滤：未 @ 的公共聊天不进对话（响应 open 时 仅@ 已开启） ----
  ws.send(JSON.stringify({
    type: "chat", sender: "Alex", senderUuid: "u-2",
    message: "今晚打末影龙吗", mentioned: false, self: false,
    target: "Cyrene", dimension: "minecraft:overworld", time: Date.now(),
  }));
  await new Promise((r) => setTimeout(r, 300));
  assert.strictEqual(inbound.length, 1, "未 @ 的消息不应进入对话");
  console.log("[PASS] 3.5 仅@过滤：未 @ 的公共聊天不进入对话");

  // ---- 提示词 Provider 只在本频道注入，且带上 @ 名字 ----
  const promptMc = ctx.registered.providers[0].provide({ channel: "mc-chat", source: "conversation" });
  const promptChat = ctx.registered.providers[0].provide({ channel: "default", source: "conversation" });
  assert.ok(promptMc.includes("Minecraft"), "本频道应注入游戏情境");
  assert.ok(promptMc.includes("@Cyrene"), "提示词应带上模组 @ 名字");
  assert.strictEqual(promptChat, "", "其他频道不应注入");
  console.log("[PASS] 4. 提示词 Provider 只在 mc-chat 频道注入（含 @名字）");

  // ---- 模拟 Agent 回复 → 客户端收到 reply ----
  const sendRes = await adapter.send({ parts: [{ kind: "text", text: "你好，我在！" }] });
  assert.strictEqual(sendRes.ok, true);
  await (async () => {
    const t0 = Date.now();
    while (!replies.some((m) => m.type === "reply") && Date.now() - t0 < 3000) await new Promise((r) => setTimeout(r, 30));
  })();
  const reply = replies.find((m) => m.type === "reply");
  assert.ok(reply, "客户端应收到 reply");
  assert.strictEqual(reply.message, "你好，我在！");
  console.log("[PASS] 5. Agent 回复 → 模组客户端收到 reply");

  // ---- 工具执行 ----
  const statusTool = ctx.registered.tools.find((t) => t.id === "mc-chat_status");
  const statusText = await statusTool.execute({});
  assert.ok(statusText.includes("Steve"));
  assert.ok(statusText.includes("@Cyrene"), "状态应包含模组 @ 名字");
  console.log("[PASS] 6. mc-chat_status 工具返回包含玩家与 @名字");

  // ---- setSettings 即时生效：只改行为类设置不重启服务（不打断游戏连接） ----
  const before = await ctx.registered.ipcs.get("status")();
  const s = await ctx.registered.ipcs.get("setSettings")({
    host: "127.0.0.1",
    port: PORT,
    broadcastReplies: true,
    respondOnlyMentioned: false,
    minReplyIntervalMs: 2000,
  });
  assert.strictEqual(s.state, "running", "设置后服务应保持运行（地址未变不重启）");
  assert.strictEqual(s.respondOnlyMentioned, false);
  assert.strictEqual(s.minReplyIntervalMs, 2000);
  assert.strictEqual(s.broadcastReplies, true);
  assert.strictEqual(s.host, before.host, "端口地址未变时不应重启");
  assert.strictEqual(s.port, before.port);
  console.log("[PASS] 6.5 setSettings 即时生效：行为类设置无需重启服务");

  // ---- dispose 清理 ----
  await ctx.registered.disposed[0]();
  assert.strictEqual((await ctx.registered.ipcs.get("status")()).state, "stopped");
  console.log("[PASS] 7. onDispose 停止服务");

  ws.close();
  console.log("\n插件加载自测 8 项全部通过 ✅");
  process.exit(0);
}

main().catch((err) => {
  console.error("\n插件加载自测失败 ❌");
  console.error(err && err.stack ? err.stack : err);
  process.exit(1);
});
