# AI Chat WebSocket（ai_chat_websocket）

纯客户端 Minecraft **NeoForge 1.21.1** 模组：把游戏聊天经 WebSocket 转发给你自己的 AI 服务，
AI 的回复再以玩家身份发回聊天栏。对 TrChat 类聊天插件（取消原版广播、以系统消息重发聊天）
**默认自动兼容**，玩家不需要输任何命令。

行为规范对齐 Fabric 端 `1.0.0-r3`（见 `docs/NEOFORGE-PORT-BRIEF.md`）。
本 jar 的版本号尾缀 `-rN` 由构建平台自动盖印，与行为规格无关；
`hello` 报文里的 `modVersion` 字段固定为 `1.0.0`，与协议要求逐字一致。

## 安装

1. 客户端装好 Minecraft **1.21.1 + NeoForge**（21.1.x，对应 21.1.244 编译）。
2. 把 `ai_chat_websocket-1.0.0-r1.jar` 放进客户端的 `mods` 文件夹即可。
   - 无需 Fabric API、无任何前置模组；
   - **服务器什么都不用装**（模组标记为仅客户端加载，服务器装了反而会拒绝）。

## 快速上手

```
/aiws url ws://127.0.0.1:8765     设置 AI 服务地址（http/https 会自动改写成 ws/wss）
/aiws connect                     立即连接（默认 autoConnect=true，进游戏自动连）
/aiws status                      查看地址、连接态、各开关与 TrChat 档位
/aiws test 你好                   手动排一条回复，验证显示链路
```

默认行为：只转发**提到 @AI 的聊天和你自己的私聊**（`/msg AI 内容`），
AI 回复以玩家身份发到聊天栏。全量转发用 `/aiws mode all`。

## /aiws 命令树（全部即时保存配置并推送 config 快照）

| 命令 | 作用 |
|---|---|
| `help` / `connect` / `disconnect` / `status` / `save` / `reload` | 基础操作 |
| `url <ws://…>` | 设置地址（http→ws、https→wss 自动改写） |
| `ai <名字>` | 被 @ 的名字（默认 `AI`） |
| `mode <mention\|all>` | 仅被@转发 / 全量转发 |
| `reply <chat\|local>` | 回复发聊天栏 / 仅本地显示 |
| `trchat <auto\|on\|off>` | 三档兼容；兼容旧参数 `true→on`、`false→off` |
| `trchatcmd <命令名\|off>` | 回复改走频道命令（如 `g`），校验 `[A-Za-z0-9_:.-]{1,32}` |
| `autoconnect` / `forwardme` / `commands` `<bool>` | 自动连接 / 转发自己聊天 / 允许 AI 命令（危险） |
| `test <文本>` | 把文本排入回复队列，立即走一遍显示链路 |

## 配置文件

`<游戏目录>/config/ai_chat_websocket.json`（Gson，字段与默认值见简报 1.2 表格）。
旧文件里只有布尔 `trchatCompat` 而没有 `trchatMode` 时自动迁移：`true→"on"`、`false→"auto"`，并立即回写。

## WebSocket 协议

连接：模组 → 你的 AI 服务，`ws://`（JDK 自带 `java.net.http.WebSocket`，JSON 用游戏自带 Gson）。

- **握手**（每次进世界、每次重新连上后发一次）：
  ```json
  {"type":"hello","modId":"ai_chat_websocket","modVersion":"1.0.0","minecraft":"1.21.1","trchatMode":"auto","trchatCompat":true,"trchatReplyCommand":"g","player":"Steve","playerUuid":"…"}
  ```
  （`trchatCompat` 为布尔快照＝`trchatMode!="off"`，保留给旧 AI 服务；`trchatReplyCommand` 仅生效且非空时带。）
- **聊天**（转发的每条）：
  ```json
  {"type":"chat","sender":"Notch","senderUuid":"…","message":"@AI 你好","mentioned":true,"self":false,"target":"AI","dimension":"minecraft:overworld","time":1712345678901}
  ```
  TrChat 来源额外带 `"viaTrChat":true` 与可选 `"channel":"世界"`，且 `senderUuid` 为空串；
  私聊 `/msg AI 内容` 以 `mentioned:true, self:true` 转发内容部分。
- **配置快照**（开关变更时）：
  ```json
  {"type":"config","aiName":"AI","mentionOnly":true,"trchatMode":"auto","trchatCompat":true,"trchatReplyCommand":"g"}
  ```
- **接收**：`reply|message|chat|say` 取 `message|content|text` 任一键入回复队列；`ping` 回 `{"type":"pong"}`；
  `notice|status` 仅本地提示；**非 JSON 纯文本直接当回复**。
- 断线重连 2 秒起步指数退避、上限 30 秒（单次连接存活超 10 秒则重置）；未连接时消息进 64 条有界队列，连上补发。

## TrChat 自动兼容（默认 auto 档）

系统消息按 `^(?:\[([^\]]{1,32})\]\s*)*([A-Za-z0-9_]{2,16})\s*(?:[:：»⇒→]|->)\s*(.+)$` 拆解，
通过全部关卡（去重、auto 档要求玩家名在当前在线列表、非自己/非 AI 名、@ 规则）才转发。
`auto` 档第一次成功收取时本地提示一次（每次进服只提示一次）；插件公告零误转发。

## 移植实现说明（Fabric → NeoForge 1.21.1 事件对应）

| 简报（Fabric 用法） | 本项目实际（NeoForge 21.1.244） |
|---|---|
| `ClientReceiveMessageEvents.CHAT` | `ClientChatReceivedEvent.Player`（`getPlayerChatMessage().signedContent()` + `getSender()`；发送者名查 Tab 列表 `getPlayerInfo(uuid)`） |
| `ClientReceiveMessageEvents.GAME` | `ClientChatReceivedEvent.System`（跳过 `isOverlay()` 动作栏） |
| `ClientSendMessageEvents.CHAT` | `ClientChatEvent`（程序化 `sendChat` 同样同步触发，回声防护成立） |
| `ClientSendMessageEvents.COMMAND` | **21.1 无对应事件**。改为注册透传型客户端命令 `msg/w/tell/whisper/pm`：观测到 `/msg <aiName> …` 后抛 Brigadier `unknown command`，NeoForge 随即把原命令原样（含签名）发服务器，功能零影响 |
| `ClientTickEvents.END_CLIENT_TICK` | `ClientTickEvent.Post` |
| `ClientPlayConnectionEvents.JOIN` | `ClientPlayerNetworkEvent.LoggingIn` / `LoggingOut` |
| `ClientCommandRegistrationCallback` | `RegisterClientCommandsEvent` |
| `networkHandler.sendChatMessage/sendChatCommand` | `ClientPacketListener.sendChat(String)` / `sendCommand(String)` |

## 源码结构

- `net/aichat/websocket/AiBridgeClient.java` — WebSocket 封装（后台守护线程、退避重连、有界队列、串行发送、1 MiB 防炸）
- `net/aichat/websocket/AiConfig.java` — Gson 配置、normalize、旧配置迁移
- `net/aichat/websocket/AiChatWebSocketMod.java` — 主入口：事件接线、/aiws 命令、TrChat 兼容、回复限速与回声防护
