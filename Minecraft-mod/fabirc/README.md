# AI Chat WebSocket（ Fabric 客户端模组）

让 AI 通过 WebSocket 接管你的聊天：模组把游戏里的聊天转发给你的 AI 服务，
AI 生成回复后，模组再以玩家身份把回复发进聊天栏（被@时才回复，可配置）。

- 仅客户端模组，服务器**不需要**装任何东西，装在你自己机器上。
- 依赖：Fabric Loader 0.19.3+、**Fabric API**

## 安装

1. 下载 Fabric API（https://modrinth.com/mod/fabric-api）；
2. 把 `fabric-api-xxx.jar` 和本模组的
   一起放进游戏目录的 `mods` 文件夹；
3. 启动游戏进服即可。

## 使用

进世界后会自动连配置里的地址（默认 `ws://127.0.0.1:8765`）。游戏内命令：

```
/aiws connect                     连接
/aiws disconnect                  断开并停止重连
/aiws status                      查看状态
/aiws url ws://127.0.0.1:8765     改服务地址（也接受 ws:// wss://）
/aiws ai <名字>                   设置 AI 的名字（聊天里 @这个名字 触发回复）
/aiws mode mention|all            只在被@时转发 / 把聊天全部转发
/aiws reply chat|local            回复以玩家身份发到聊天栏 / 只显示在自己屏幕
/aiws autoconnect <true|false>    进世界自动连接
/aiws forwardme <true|false>      自己打的字是否也转发（方便和 AI 对话）
/aiws commands <true|false>       是否允许 AI 回复以 / 开头当命令执行（默认关，危险）
/aiws trchat auto|on|off          TrChat 兼容：自动检测(默认) / 强制开 / 关闭
/aiws trchatcmd <命令名|off>      AI 回复走哪个 TrChat 频道命令（如 g / all / shout）
/aiws test <文本>                 本地模拟一条 AI 回复，测试显示效果
/aiws reload | save               读写 config/ai_chat_websocket.json
```

配置文件在游戏目录 `config/ai_chat_websocket.json`，改完 `/aiws reload` 即可生效。

## TrChat 兼容（默认自动，不用手动开）

[TrChat](https://github.com/TrPlugins/TrChat) 是服务器上很常见的聊天管理插件（跨服、频道、格式化）。
它会**取消原版聊天广播**，把每条聊天按自己配置的格式（如 `[世界] Steve: 你好`）
以**系统消息**重新发给客户端。原版模组只监听"签名玩家聊天"，所以在这种服务器上会一条都收不到。

**从 r3 起，`trchat` 默认是 `auto`：模组自己认出这种情况，不需要你开任何开关。**
`auto` 档的判定规则（两道保险，防止误伤普通服务器）：

1. 这条系统消息能用正则拆成 `[频道] 玩家名: 内容` 的样子；
2. 拆出来的"玩家名"**确实在当前服务器的在线玩家列表里**。

两条都满足才当玩家聊天收进来。所以在没装 TrChat 的普通服务器上，进服提示、
别的插件公告都对不上这两条规则，行为和以前完全一样；第一次自动收取时会提示一次
"检测到本服务器用插件重发聊天"，你可以用 `/aiws trchat off` 关掉。

- **收**：格式默认匹配 `[世界] Steve: 你好` / `[Global] Steve => 你好` 等 TrChat 常见样式
  （配置项 `trchatParsePattern`，服主改成别的符号时改这条正则即可）。
  想要更激进（拆不出格式、但 @ 到 AI 的公告也转给 AI），用 `/aiws trchat on` 强制模式。
- **发**：这一步没法自动——AI 的回复默认发普通聊天，TrChat 会按你当前所在频道处理；
  想让回复固定进某个频道，再执行 `/aiws trchatcmd g`（g / all / shout 等，就是玩家平时
  `/global 文本` 用的那些，见服主的 `channels/*.yml` 里 `Bindings.Command`；`off` 恢复直发）。
- **防回环**：TrChat 重发的格式化消息和你自己发出去的话，模组会自动去重，
  不会把同一句话转两遍给 AI，也不会把 AI 自己的回复当成别人说话转回去。

## WebSocket 协议

连接后模组先发一条握手：

```json
{"type":"hello","modId":"ai_chat_websocket","modVersion":"1.0.0","minecraft":"1.21.1","trchatMode":"auto","trchatCompat":true,"player":"Steve","playerUuid":"..."}
```

之后每条要转发的聊天：

```json
{"type":"chat","sender":"Notch","senderUuid":"...","message":"@AI 你好","mentioned":true,"self":false,"target":"AI","dimension":"minecraft:overworld","time":1712345678901}
```

- `mentioned`：这条消息里是否 @ 了 AI；`mode=all` 时每条都是全量转发，AI 自己决定要不要回。
- `self:true`：是你（本机玩家）发的，包括 `/msg AI 你好` 这类私聊（会以 `mentioned:true` 转发内容部分）。

**TrChat 兼容模式下的差异**：从系统消息解析出来的聊天会多带两个字段——
`"viaTrChat":true` 和可选的 `"channel":"世界"`（正则从 `[频道] 玩家名: 内容` 里拆出的标签；
服主改了格式就可能拆不到）。这种消息 `senderUuid` 是空字符串，因为格式化之后原版签名信息已丢失。

另外切换开关（`trchat` / `trchatcmd` 等）时模组会推一条配置快照，方便 AI 服务感知模式：

```json
{"type":"config","aiName":"AI","mentionOnly":true,"trchatMode":"auto","trchatCompat":true,"trchatReplyCommand":"g"}
```

（`trchatCompat` 表示兼容当前是否生效：`auto` 和 `on` 都是 `true`。）

AI 服务不认识 `config` 类型直接忽略即可，不影响原有流程。

**模组期待的回复**（AI 服务发回来），二选一即可：

```json
{"type":"reply","message":"你好，我是AI"}
```

或直接发一行纯文本（不是 JSON 就按纯文本处理）。
`ping` 会收到 `{"type":"pong"}`；`notice` 类型只在本地提示、不进聊天。

## 最小可用的 AI 桥（Python）

用一个 20 行的小服务把游戏和任意大模型 API 接起来：

```python
# pip install websockets
import asyncio, json, websockets

async def handle(ws):
    async for raw in ws:
        data = json.loads(raw)
        if data.get("type") == "hello":
            continue
        if data.get("type") == "ping":
            await ws.send('{"type":"pong"}')
            continue
        if data.get("type") == "chat" and (data.get("mentioned") or True):
            reply = f'收到 {data["sender"]} 说的：{data["message"]}'  # 这里换成调用 AI 的代码
            await ws.send(json.dumps({"type": "reply", "message": reply}, ensure_ascii=False))

async def main():
    async with websockets.serve(handle, "127.0.0.1", 8765):
        await asyncio.Future()

asyncio.run(main())
```

跑起来后游戏里 `/aiws connect`，再进世界说句话或在聊天里 `@AI 你好` 试试。

## 注意

- 回复限速：两条 AI 回复默认至少间隔 1 秒（`replyMinIntervalTicks`），防止刷屏被服务器踢。
- AI 的回复是以**你的身份**发出去的，服务器把它当普通玩家聊天，请先确认服务器允许这种用法。
- 单条回复超过 `maxReplyChars`（默认 240，上限 256）会截断。
