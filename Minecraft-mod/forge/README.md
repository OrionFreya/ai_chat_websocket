# AI Chat WebSocket（Forge 1.20.1 版）

纯客户端模组：把游戏聊天经 WebSocket 转发给你自己的 AI 服务，AI 的回复再以玩家身份发回聊天栏；
对 TrChat 类聊天插件**默认自动兼容**，玩家不需要输入任何命令。
行为与 Fabric 端 1.0.0-r3 完全一致（本版本即按移植简报对齐 r3）。

## 安装

1. 游戏版本 Minecraft **1.20.1**，加载器 **Forge 47.x**（任意 47.1+ 即可），**Java 17**。
2. 把 `ai_chat_websocket-1.0.0-r1.jar` 放进游戏目录的 `mods` 文件夹即可。
3. 无需任何前置库；服务器不需要安装任何东西（纯客户端模组，联机好友各自装各自的）。

首次启动后会在 `config/ai_chat_websocket.json` 生成默认配置，
与 Fabric 端同名同字段，**两端共用同一份配置文件、同一个 AI 服务**，互改另一边能读。

## 命令（全部为客户端命令，不需要服务器权限）

```
/aiws help | connect | disconnect | status | save | reload
/aiws url <ws://...>            地址（http/https 自动改写为 ws/wss）
/aiws ai <名字>                 被 @ 的名字
/aiws mode <mention|all>        仅被@/私聊转发 ＝ mention ／ 全量 ＝ all
/aiws reply <chat|local>        回复发聊天栏 ＝ chat ／ 仅本地显示 ＝ local
/aiws trchat <auto|on|off>      TrChat 兼容三档（兼容旧参数 true/false）
/aiws trchatcmd <命令名|off>    AI 回复改走该频道命令（如 g → /g 文本）
/aiws autoconnect <bool>        进世界自动连接
/aiws forwardme <bool>          自己打的普通聊天也转发
/aiws commands <bool>           允许 AI 回复以 / 开头时当命令执行（危险）
/aiws test <文本>               把文本排入回复队列，走一遍显示链路
```

每条开关命令都会即时保存配置，并把 config 快照推给 AI 服务。

## WebSocket 协议（与 Fabric 端逐字段一致，AI 服务端不用改）

- 握手（连接成功且在世界中时、每次进世界重发一次）：

```json
{"type":"hello","modId":"ai_chat_websocket","modVersion":"1.0.0-r3","minecraft":"1.20.1","trchatMode":"auto","trchatCompat":true,"trchatReplyCommand":"g","player":"Steve","playerUuid":"..."}
```

（`trchatCompat` 是布尔＝`trchatMode!="off"`，保留给旧 AI 服务端；`trchatReplyCommand` 仅生效且非空时带。）

- 每条转发的聊天：

```json
{"type":"chat","sender":"Notch","senderUuid":"...","message":"@AI 你好","mentioned":true,"self":false,"target":"AI","dimension":"minecraft:overworld","time":1712345678901}
```

（`target`：被 @ 时＝AI 名，否则空串。）

TrChat 来源额外带 `"viaTrChat":true` 与可选 `"channel":"世界"`，`senderUuid` 为空串。

- 开关变更快照：

```json
{"type":"config","aiName":"AI","mentionOnly":true,"trchatMode":"auto","trchatCompat":true,"trchatReplyCommand":"g"}
```

- 接收：`reply|message|chat|say` 取 `message|content|text` 任一键入回复队列；`ping`→回 `{"type":"pong"}`；
  `notice|status`→仅本地提示；非 JSON 的纯文本直接当回复。

## Forge 1.20.1 适配说明（相对简报的两处换算，行为不变）

- 简报按 NeoForge 写的 `ClientChatReceivedEvent.MessageSigned` 子类型在 Forge 1.20.1 不存在：
  本版用单一 `ClientChatReceivedEvent`，按 `isSystem()`（发送者是否 NIL_UUID）区分
  签名聊天与插件重发的系统消息，发送者用 `getSender()` UUID（Tab 列表反查名字）。
- `ClientChatEvent` 在 1.20.1 只覆盖普通聊天（字节码确认 `sendCommand` 不触发），
  所以本地 `/msg|w|tell|whisper|pm AI 内容` 检测改挂在 `ScreenEvent.KeyPressed.Pre`
  （ChatScreen 回车前读输入框，只观察不取消）；回声防护在 `sendChat` 路径上不受影响。

## 断线与性能

- 断线重连：2 秒起步指数退避、上限 30 秒；连接存活超 10 秒则退避重置。
- 未连接时消息进有界队列（64 条，满丢最旧），连上自动补发；不刷屏报错。
- AI 回复限速（`replyMinIntervalTicks`，默认 20 tick）＋截断（`maxReplyChars`，默认 240）。
- 不引第三方网络库：WebSocket 用 JDK 自带 `java.net.http.WebSocket`，JSON 用游戏自带 Gson。
