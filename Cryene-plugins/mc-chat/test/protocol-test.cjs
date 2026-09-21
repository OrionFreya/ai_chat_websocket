"use strict";

/**
 * mc-chat 端到端自测：模拟 Minecraft 客户端模组连接，验证：
 *  1. 握手 hello 被记录；
 *  2. chat 被桥捕获并通过频道适配器转成入站消息；
 *  3. 适配器 send() 把 Agent 回复以 reply 类型发回模组客户端；
 *  4. ping 收到 pong；
 *  5. notice 本地提示正常；
 *  6. 广播模式、仅@模式过滤生效。
 */
const path = require("node:path");
const assert = require("node:assert");
const WebSocket = require(path.join(__dirname, "..", "node_modules", "ws"));
const { McBridge } = require("../ws-server");
const { McChatChannelAdapter } = require("../channel");

const PORT = 18765; // 测试端口，避开默认 8765
const logs = [];
const log = (m) => logs.push(m);

async function waitFor(cond, timeoutMs = 5000, desc = "条件") {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (cond()) return;
    await new Promise((r) => setTimeout(r, 30));
  }
  throw new Error(`等待超时: ${desc}`);
}

function connectClient() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${PORT}`);
    ws.on("open", () => resolve(ws));
    ws.on("error", reject);
  });
}

/** 收集某客户端收到的 JSON 消息队列。 */
function collector(ws) {
  const queue = [];
  ws.on("message", (data) => {
    try {
      queue.push(JSON.parse(data.toString()));
    } catch {
      queue.push({ raw: data.toString() });
    }
  });
  return {
    queue,
    next(type, timeoutMs = 5000) {
      return waitFor(() => queue.some((m) => m.type === type), timeoutMs, `收到 ${type} 消息`).then(
        () => queue.find((m) => m.type === type)
      );
    },
  };
}

async function main() {
  const bridge = new McBridge(log);
  await bridge.start({ host: "127.0.0.1", port: PORT, broadcastReplies: false });

  // ---- 1. 模组客户端连接 + 握手 ----
  const c1 = await connectClient();
  const q1 = collector(c1);
  c1.send(JSON.stringify({
    type: "hello",
    modId: "ai_chat_websocket",
    modVersion: "1.0.0",
    minecraft: "1.21.1",
    player: "Steve",
    playerUuid: "11111111-2222-3333-4444-555555555555",
  }));
  await waitFor(() => (bridge.status().clients[0] || {}).player === "Steve", 3000, "握手记录玩家");
  assert.strictEqual(bridge.status().clients.length, 1);
  assert.strictEqual(bridge.status().clients[0].player, "Steve");
  console.log("[PASS] 1. 握手记录玩家 Steve");

  // ---- 2. 频道适配器把 chat 转成入站消息 ----
  const adapter = new McChatChannelAdapter(bridge);
  const inbound = [];
  adapter.onMessage = async (msg) => { inbound.push(msg); };
  await adapter.start();

  c1.send(JSON.stringify({
    type: "chat",
    sender: "Alex",
    senderUuid: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    message: "@Cyrene 你好呀",
    mentioned: true,
    self: false,
    target: "Cyrene",
    dimension: "minecraft:overworld",
    time: Date.now(),
  }));
  await waitFor(() => inbound.length === 1, 3000, "入站消息");
  assert.strictEqual(inbound[0].channel, "mc-chat");
  assert.strictEqual(inbound[0].text, "@Cyrene 你好呀");
  assert.strictEqual(inbound[0].senderName, "Alex");
  assert.strictEqual(inbound[0].meta.mentioned, true);
  assert.strictEqual(bridge.aiName, "Cyrene", "应从 chat.target 学到 @ 名字");
  console.log("[PASS] 2. chat → 频道入站消息（mentioned=true，学到 @名字=Cyrene）");

  // ---- 3. 适配器 send() → reply 发回客户端 ----
  const sendRes = await adapter.send({
    parts: [{ kind: "text", text: "你好！我收到啦。\n第二行测试。" }],
  });
  assert.strictEqual(sendRes.ok, true);
  const reply = await q1.next("reply");
  assert.strictEqual(reply.message, "你好！我收到啦。");
  // 第二行 350ms 后到达
  await waitFor(() => q1.queue.filter((m) => m.type === "reply").length >= 2, 3000, "第二行回复");
  const replies = q1.queue.filter((m) => m.type === "reply");
  assert.strictEqual(replies[1].message, "第二行测试。");
  console.log("[PASS] 3. Agent 回复按行发回客户端（reply ×2）");

  // ---- 4. ping → pong ----
  c1.send(JSON.stringify({ type: "ping" }));
  await q1.next("pong");
  console.log("[PASS] 4. ping → pong");

  // ---- 5. notice ----
  bridge.sendNotice("这是一条本地提示");
  const notice = await q1.next("notice");
  assert.strictEqual(notice.message, "这是一条本地提示");
  console.log("[PASS] 5. notice 本地提示");

  // ---- 6. 仅@模式过滤 ----
  const inbound2 = [];
  bridge.respondOnlyMentioned = true;
  const adapter2 = new McChatChannelAdapter(bridge);
  adapter2.onMessage = async (m) => { inbound2.push(m); };
  await adapter2.start();

  c1.send(JSON.stringify({
    type: "chat",
    sender: "Alex",
    senderUuid: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    message: "今晚打末影龙吗",
    mentioned: false,
    self: false,
    target: "Cyrene",
    dimension: "minecraft:overworld",
    time: Date.now(),
  }));
  await new Promise((r) => setTimeout(r, 300));
  assert.strictEqual(inbound2.length, 0, "未被 @ 的消息不应进入对话");
  c1.send(JSON.stringify({
    type: "chat",
    sender: "Alex",
    senderUuid: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    message: "@Cyrene 在吗",
    mentioned: true,
    self: false,
    target: "Cyrene",
    dimension: "minecraft:overworld",
    time: Date.now(),
  }));
  await waitFor(() => inbound2.length === 1, 3000, "被 @ 消息进入对话");
  assert.strictEqual(inbound2[0].text, "@Cyrene 在吗");
  console.log("[PASS] 6. 仅@模式：未@不转发，@了才转发");
  bridge.respondOnlyMentioned = false;

  // ---- 7. 多客户端 + 广播模式 ----
  const c2 = await connectClient();
  const q2 = collector(c2);
  c2.send(JSON.stringify({ type: "hello", player: "Alex" }));
  await waitFor(() => (bridge.status().clients[1] || {}).player === "Alex", 3000, "第二个客户端握手");
  bridge.broadcastReplies = true;
  await bridge.sendReply("广播消息");
  await q1.next("reply");
  await q2.next("reply");
  console.log("[PASS] 7. 广播模式两个客户端都收到回复");

  // ---- 7.5 回声抑制：另一个客户端把我们的回复转发回来，应被丢弃（切断循环） ----
  const inboundEcho = [];
  const echoAdapter = new McChatChannelAdapter(bridge);
  echoAdapter.onMessage = async (m) => { inboundEcho.push(m); };
  await echoAdapter.start();
  const echoesBefore = bridge.status().suppressedEchoes;
  // 模拟服务器/其他客户端把「广播消息」这条回复原样转发回来
  c2.send(JSON.stringify({
    type: "chat",
    sender: "Steve",
    senderUuid: "11111111-2222-3333-4444-555555555555",
    message: "广播消息",
    mentioned: false,
    self: false,
    target: "Cyrene",
    dimension: "minecraft:overworld",
    time: Date.now(),
  }));
  await new Promise((r) => setTimeout(r, 400));
  assert.strictEqual(inboundEcho.length, 0, "回声不应进入对话");
  assert.ok(bridge.status().suppressedEchoes > echoesBefore, "抑制计数应增加");
  assert.strictEqual(bridge.status().chatsReceived, 3, "回声不应计入收到聊天数");
  console.log("[PASS] 7.5 回声抑制：自己回复的回声被丢弃，循环被切断");

  // ---- 8. 停止服务，客户端断开 ----
  const closed = new Promise((resolve) => { c1.on("close", resolve); });
  await bridge.stop();
  await closed;
  assert.strictEqual(bridge.status().state, "stopped");
  console.log("[PASS] 8. 停止服务后客户端断开");

  console.log("\n全部 9 项自测通过 ✅");
  process.exit(0);
}

main().catch((err) => {
  console.error("\n自测失败 ❌");
  console.error(err && err.stack ? err.stack : err);
  console.error("\n服务日志:");
  for (const l of logs) console.error("  ", l);
  process.exit(1);
});
