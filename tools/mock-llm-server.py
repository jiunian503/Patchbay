#!/usr/bin/env python3
"""本地 OpenAI 兼容的假服务端 —— 用来在没有真实 API Key 的情况下验证流式链路。

## 为什么需要它

真实的流式路径（SSE 分片拼行、逐 token 渲染、工具卡片、工具循环、
一轮一条 assistant 行的落库顺序）用假 Key 是测不到的：请求会在 401 就断掉。
单测里的 MockWebServer 能覆盖解析逻辑，但覆盖不到「设备上的 Compose 渲染 +
Room 写入 + 网络」这一整条链。

有了这个服务端，就能在模拟器上把整条链跑通，不花一分钱 token，也不依赖外网。

## 用法

    python tools/mock-llm-server.py            # 监听 127.0.0.1:8765
    adb reverse tcp:8765 tcp:8765              # 把模拟器的 8765 转发到宿主机

然后在 App 里加一个服务商：
    接口地址 = http://127.0.0.1:8765
    模型名   = mock-model
    API Key  = 随便填（服务端不校验）

## 它会演什么

**按用户最后一句话选工具**，把每条路径都能演到：

| 用户消息里含 | 请求调用 | 演的是 |
|---|---|---|
| `calc` | `calculate("1234*5678")` | 真实工具执行 —— 结果由设备上的解析器算出 |
| `time` | `get_current_time()` | 无参工具 + 真实时区 |
| `device` | `device_info()` | 需要 Android 能力的工具 |
| `fetch` | `fetch_url(本服务端的 /hello)` | **需要用户确认**的工具 → 弹框 |
| `geo` | `geocode_city("Shanghai")` | **插件工具**（装了内置天气示例之后才有）→ 真发一次外网请求 |
| `history` | `search_history("北京")` | **历史检索工具** → 真查一次设备本地数据库（Hermes 第三层记忆） |
| `web` / `search` | `search_web("kotlin coroutines")` | **联网搜索** → 真出网抓一次 `cn.bing.com` 的结果页 |
| `csv` | `csv_stats(内联 CSV)` | **脚本插件工具**（装了 `csvstat` 示例之后才有）→ 真起一次 `:sandbox` |
| `md` | 不调工具，直接回一大段 Markdown | 渲染效果验收（标题/代码/表格/列表/引用/行内格式） |
| 其它 | `no_such_tool_zzz(...)` | **工具不存在**的错误路径 |

`history` 那条验的是「模型能搜用户的历史对话」。它的第二轮回显里会出现
**设备上真实存在的历史消息原文**，所以想看到内容，得先发过含「北京」的消息。
它的价值在于：检索走的是和搜索界面**同一个** `ConversationSearch` 实例，
这条路径通过就意味着「用户搜得到、模型也搜得到」。

`geo` 那条是插件链路的验收路径，会真的打到 `api.open-meteo.com`
（模拟器需要能上外网）。整条链一次跑完：装配 → 模型看到工具 → 调用 →
占位符替换 → 白名单放行 → HTTP → `responsePath` 取值 → 回灌。

`csv` 那条是**脚本插件**（QuickJS）唯一的验收路径，别的触发器都验不到它 ——
声明式插件、MCP、内置工具全在**主进程**里跑，而这一条会真的拉起 `:sandbox`
进程、dlopen `libquickjs.so`、在里面执行第三方 JS，再把结果搬回来。
所以它同时是「脚本运行时能不能用」和「release 包（R8 混淆过）里那个独立进程
起不起得来」的判据 —— 后者在 debug 上是验不到的。

CSV 走**内联参数**而不是工作区文件：工作区要先在插件详情页导入，而中文文件名
`input text` 打不进去，会把「怎么复现」变成一道额外的题。两列刻意一数值一文本，
脚本里「数值占比超过八成才当数值列」那条分支的两边都要走到。

**`no_such_tool_zzz` 是个刻意的怪名字。** 原来这里用的是 `get_weather`，
装上天气插件之后它就成了真工具，默认分支会从「工具不存在」悄悄变成
「真的去查天气」—— 一个测试装置的行为被另一个功能改变了。
用一个插件和内置工具都不可能起的名字，这条路径才稳定。

`fetch` 那条会真的打到本服务端的 `GET /hello`（同一进程里也提供了这个页面），
所以点完「允许」之后能真的抓到内容 —— 弹框 → 批准 → 执行 → 回灌，
整条链一次跑完。

第二轮会把**设备真正返回的工具结果**原样回显到回答里，
所以界面上看到的数字是设备算出来的，不是这里编的。

另外它刻意演了几件容易写错的事：

1. 先吐 `reasoning_content`（思考过程），再请求工具调用
2. 工具参数**分两片**发送 —— 真实服务端就是这么逐字符吐的
3. 响应切得很碎（每次几个字），用来验证跨 TCP 分片的拼行逻辑

一次发送应当产生：`user` → `assistant`(带 tool_calls) → `tool` → `assistant`(正文)
四行，重启后顺序仍然正确。这正好验证「一轮一条 assistant 行」的设计。
"""

import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = 8765

# 每次吐这么多个字符就 flush 一次。调小 = 分片更碎 = 对解析更狠。
CHUNK_SIZE = 3

# 「从网址装插件」用的清单。
#
# 刻意用一个**独立**的 id（不是内置天气那个）：id 撞上会走「升级 + 扩权确认」
# 那条路，而这里要验的是「全新安装 → `source` 记成 url」。
MANIFEST = {
    "id": "pub.fromurl",
    "name": "网址装来的示例",
    "version": "1.0.0",
    "description": "这个插件是「从网址安装」那条入口拉下来的，用来验证整条链路。",
    "author": "mock",
    "license": "MIT",
    "runtime": "declarative",
    "permissions": {
        "network": ["api.open-meteo.com"],
        "filesystem": "none",
        "shell": False,
        "device": [],
        "linuxEnv": False,
    },
    "entry": {
        "declarative": {
            "baseUrl": "https://api.open-meteo.com",
            "auth": {"type": "none"},
        }
    },
    "tools": [
        {
            "name": "fromurl_ping",
            "description": (
                "查询上海当前的 2 米气温。这是一个占位工具，用来证明"
                "「从网址装来的插件」真的进了模型能看到的工具列表。"
            ),
            "parameters": {"type": "object", "properties": {}},
            "request": {
                "method": "GET",
                "path": "/v1/forecast",
                "query": {
                    "latitude": "31.22222",
                    "longitude": "121.45806",
                    "current": "temperature_2m",
                },
            },
        }
    ],
}


def chunk(delta: dict, finish_reason=None) -> str:
    """按 OpenAI 的流式格式包一片。"""
    payload = {
        "id": "chatcmpl-mock",
        "object": "chat.completion.chunk",
        "created": int(time.time()),
        "model": "mock-model",
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish_reason}],
    }
    # 注意 ensure_ascii=False：中文必须原样出去，否则客户端拿到的是 \uXXXX
    return "data: " + json.dumps(payload, ensure_ascii=False) + "\n\n"


def text_chunks(text: str):
    """把一段文本切成若干片 content 增量。"""
    for i in range(0, len(text), CHUNK_SIZE):
        yield chunk({"content": text[i : i + CHUNK_SIZE]})


def last_user_text(messages) -> str:
    """取最后一条用户消息，小写。

    按「最后一条」而不是「上下文里所有 user」找 —— 多轮会话里前面还有历史提问。
    """
    for m in reversed(messages):
        if m.get("role") == "user":
            return (m.get("content") or "").lower()
    return ""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        # 默认实现会往 stderr 刷一堆东西，这里只留一行有用的
        print(f"[mock] {self.command} {self.path}", flush=True)

    def do_GET(self):
        """
        两个用途：

        - 默认：给 `fetch_url` 当抓取目标用 —— 返回一段带标签和实体的 HTML，
          顺便验证客户端的 HTML 清洗。「批准 → 执行 → 回灌」因此能一次跑完。
        - `/manifest.json`：给「从网址装插件」当清单来源。

        有了第二个，「从网址安装」整条链（拉取 → 严格解析 → 权限展示 →
        落库 → `source` 记录）都能在本地跑完，不用另挂一个静态服务器。
        """
        if self.path.startswith("/manifest.json"):
            self._respond(
                200,
                "application/json; charset=utf-8",
                # ensure_ascii=False：中文必须原样出去，否则客户端拿到的
                # 是 \uXXXX —— 那虽然也能解析，但界面上那份权限说明会变成乱码
                json.dumps(MANIFEST, ensure_ascii=False, indent=2).encode("utf-8"),
            )
            return

        # 专门用来验「服务器返回 HTTP 404」那条错误路径
        if self.path.startswith("/nope"):
            self._respond(404, "text/plain; charset=utf-8", b"not found")
            return

        self._respond(
            200,
            "text/html; charset=utf-8",
            (
                "<html><head><title>Mock</title>"
                "<style>body{color:red}</style></head>"
                "<body><h1>Mock 页面</h1>"
                "<p>这一段是本地 mock 服务端返回的 &amp; 内容。</p>"
                "<p>第二段用来验证块级标签之间的换行。</p>"
                "<script>var ignored = 1;</script>"
                "</body></html>"
            ).encode("utf-8"),
        )

    def _respond(self, code: int, content_type: str, body: bytes):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = {}

        messages = body.get("messages", [])
        tools = body.get("tools") or []
        # 是不是工具循环的第二轮，要看**最后一条**是不是 tool 结果。
        #
        # 不能写成「上下文里有没有 role=tool」—— 多轮会话的历史里
        # 本来就躺着上一轮的 tool 消息，那样第二句用户提问会被直接
        # 当成第二轮，工具调用那一轮就被跳过了。
        # 引擎的约定是：追加完 tool 消息后立刻重新提问，所以它必然在末尾。
        has_tool_result = bool(messages) and messages[-1].get("role") == "tool"
        tool_output = messages[-1].get("content", "") if has_tool_result else ""

        # 把「客户端声明了几个工具」打出来 —— 这条日志是排查
        # 「模型为什么不用工具」的第一现场。0 就说明注册表没接上。
        tool_names = [
            (t.get("function") or {}).get("name") for t in tools
        ]
        print(
            f"[mock] 第 {len(messages)} 条上下文，"
            f"{'第二轮（含工具结果）' if has_tool_result else '第一轮'}，"
            f"客户端声明了 {len(tools)} 个工具 {tool_names}",
            flush=True,
        )

        # 采样参数与系统提示词。
        #
        # **「没发送」和「发了默认值」必须能分辨** —— 这三个字段的语义是
        # 「留空就不发，让服务端用自己的默认」，而各家默认并不相同。
        # 所以缺省时打 `<未发送>` 而不是打一个看起来像真值的数字。
        sampling = " ".join(
            (f"{k}={body[k]}" if k in body else f"{k}=<未发送>")
            for k in ("temperature", "max_tokens")
        )
        system = next(
            (m.get("content", "") for m in messages if m.get("role") == "system"),
            None,
        )
        print(
            f"[mock] {sampling} system={'<未发送>' if system is None else repr(system)}",
            flush=True,
        )

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        # 不写 Content-Length，靠 chunked 传输 —— 这正是真实流式服务端的行为
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        if has_tool_result:
            self._round_two(tool_output)
        elif "md" in last_user_text(messages):
            self._round_markdown()
        elif "link" in last_user_text(messages):
            self._round_links()
        else:
            self._round_one(messages)

        self._write("data: [DONE]\n\n")
        self._write("")  # 结束 chunked 编码

    # ---- 第一轮：思考 + 请求调用工具 -------------------------------------

    def _round_one(self, messages):
        reasoning = "用户想要天气，我得先查一下。"
        for i in range(0, len(reasoning), CHUNK_SIZE):
            self._write(chunk({"reasoning_content": reasoning[i : i + CHUNK_SIZE]}))
            time.sleep(0.05)

        self._write(chunk({"content": "我来查一下。\n"}))
        time.sleep(0.1)

        name, arguments = self._pick_tool(messages)
        print(f"[mock] 决定调用 {name}({arguments})", flush=True)

        # 工具调用的第一片必须带 id 和 name，后面几片只有 arguments
        self._write(
            chunk(
                {
                    "tool_calls": [
                        {
                            "index": 0,
                            "id": "call_mock_1",
                            "type": "function",
                            "function": {"name": name, "arguments": ""},
                        }
                    ]
                }
            )
        )
        # 参数**故意分两片**：真实服务端是逐字符吐的，客户端必须能拼起来
        half = len(arguments) // 2
        for piece in (arguments[:half], arguments[half:]):
            self._write(
                chunk(
                    {
                        "tool_calls": [
                            {"index": 0, "function": {"arguments": piece}}
                        ]
                    }
                )
            )
            time.sleep(0.08)

        self._write(chunk({}, finish_reason="tool_calls"))

    @staticmethod
    def _pick_tool(messages):
        """按用户最后一句话选一个工具。让每条路径都能被测到。"""
        last_user = last_user_text(messages)

        if "calc" in last_user:
            return "calculate", '{"expression": "1234*5678"}'
        if "time" in last_user:
            return "get_current_time", "{}"
        if "device" in last_user:
            return "device_info", "{}"
        if "fetch" in last_user:
            # 指回本服务端 —— 批准之后能真的抓到 /hello
            return "fetch_url", '{"url": "http://127.0.0.1:8765/hello"}'
        if "geo" in last_user:
            # 走**插件**提供的工具。装上内置的天气示例（声明式运行时）之后，
            # `geocode_city` 才存在；这条路径验的是整条插件链路：
            # 装配 → 模型看到它 → 调用 → 占位符替换 → 白名单放行 →
            # 真发一次 HTTP 到 api.open-meteo.com → responsePath 取值 → 回灌。
            #
            # 参数用**英文城市名**：`input text` 打不了中文，而这条路径
            # 常常要手敲参数复现，用中文会让「怎么复现」变成一道额外的题。
            return "geocode_city", '{"name": "Shanghai", "count": "1"}'
        if "csv" in last_user:
            # 走**脚本插件**提供的工具（`csv_stats`，QuickJS 运行时）。
            # 装上 `plugin/examples/csvstat` 之后才有；这是唯一一条会真的
            # 拉起 `:sandbox` 进程、dlopen `libquickjs.so` 并在里面执行第三方 JS
            # 的路径 —— 声明式插件 / MCP / 内置工具全在主进程里跑，验不到那一整套。
            #
            # 参数内联 CSV 而不是走工作区：工作区要先在插件详情页导入，
            # 而中文文件名 `input text` 打不进去（同 `geo` 的理由）。
            #
            # 两列刻意一数值一文本 —— 脚本里「数值占比超过八成才当数值列」
            # 那条分支的两边都要走到。
            return "csv_stats", json.dumps(
                {
                    "csv": "城市,销量,备注\n"
                    "北京,1200,好\n"
                    "上海,980,好\n"
                    "广州,1500,一般\n"
                    "深圳,1100,好"
                },
                ensure_ascii=False,
            )
        if "history" in last_user:
            # 历史检索（Hermes 三层记忆的第三层）。这条路径和别的不一样：
            # 它读的是**设备本地数据库**，所以第二轮回显里出现的内容
            # 只可能来自设备上真实存在的历史消息 —— 是最好的「工具真跑了」的证据。
            #
            # 要验它，得先在这个会话（或任何会话）里发过含该关键词的消息。
            #
            # 参数刻意用**两段**（空格分隔）：多段查询走的是「空格并置」那条路，
            # 而它正是 Android 的 Standard Query Syntax 下最容易写错的地方
            # （写成 `AND` 会永远返回空，且不报错）。模型完全可能给多段 query，
            # 所以这条路径必须从工具入口一路验到 FTS。
            return "search_history", '{"query": "北京 适合"}'
        if "web" in last_user or "search" in last_user:
            # 联网搜索。这条路径和别的不一样：它**真的出网**（打到 cn.bing.com），
            # 所以第二轮回显里出现的是从必应结果页里抠出来的**真实网址**。
            # 一次就把两件事验掉：设备能出网、解析规则还和对方的页面结构对得上。
            #
            # 放在 `history` 之后：`search history` 会先命中上面那条。
            #
            # query 用英文，理由同 `geo` —— `input text` 打不了中文，
            # 手敲复现时不该多一道题。
            return "search_web", '{"query": "kotlin coroutines"}'
        # 默认给一个**不存在**的工具，用来演错误路径。
        #
        # 注意：装上天气插件之后 `get_weather` 就**存在**了 —— 这条
        # 默认分支会从「工具不存在」悄悄变成「真的去查天气」。
        # 想稳定复现错误路径，就用一个插件和内置工具都不会有的名字。
        return "no_such_tool_zzz", '{"city": "Beijing"}'

    # ---- 富 Markdown：渲染效果验收 --------------------------------------

    def _round_markdown(self):
        """不调工具，直接回一大段 Markdown。

        内容是**故意挑的**：每一段都对应渲染器里一个容易写错的地方。
        配合 CHUNK_SIZE=3 的分片，顺便把「流式过程中半截语法」也演一遍 ——
        围栏会先以未闭合的形态出现好几帧。
        """
        doc = "\n".join(
            [
                "# 一级标题",
                "",
                "这是一段普通正文，用来确认行距和换行。",
                "",
                "## 二级标题",
                "",
                "行内格式：**粗体**、*斜体*、~~删除线~~、`行内代码`，",
                "以及 [带标题的链接](https://example.com \"示例\") 和裸链接 https://example.com/docs。",
                "",
                "### 三级标题",
                "",
                "```kotlin",
                "fun stream(prompt: String) {",
                "    val call = provider.chat(prompt)",
                "}",
                "```",
                "",
                "| 参数 | 类型 | 说明 |",
                "| :--- | :---: | ---: |",
                "| temperature | Double | 采样温度，越大越随机 |",
                "| maxTokens | Int | 单次回复的上限 |",
                "",
                "无序列表：",
                "",
                "- 第一项",
                "- 第二项",
                "  - 嵌套的子项",
                "  - 另一个子项",
                "",
                "有序列表：",
                "",
                "1. 第一步",
                "2. 第二步",
                "3. 第三步",
                "",
                "> 引用块里的内容。",
                "> 第二行同样属于引用。",
                "",
                "---",
                "",
                "最后一段，确认分隔线之后的内容仍然正常。",
            ]
        )
        for piece in text_chunks(doc):
            self._write(piece)
            time.sleep(0.02)
        self._write(chunk({}, finish_reason="stop"))

    # ---- 链接点击验收 ----------------------------------------------------

    def _round_links(self):
        """回一段**每行一个链接**的短正文。

        为什么要专门做一个分支：行内 span 没有独立的 accessibility 节点，
        uiautomator 只能给出整个段落的 bounds。段落一长，想点中某个链接
        就只能靠肉眼量截图 —— 那正是之前算偏 65px 踩过的坑。
        让每个链接独占一行、贴着左边缘，坐标就没有歧义了。

        第二行是**负面对照**：纯文字，点它不该有任何反应。
        第三行是协议白名单的负面对照：`javascript:` 会在解析阶段退化成
        纯文字，所以点它同样不该有反应 —— 但**要能看见链接样式没了**。
        """
        doc = "\n".join(
            [
                "[打开链接](https://example.com/docs)",
                "",
                "这段是普通文字，点它不应该有任何反应。",
                "",
                "[危险的 javascript 链接](javascript:alert(1))",
                "",
                "裸链接 https://example.com/bare",
            ]
        )
        for piece in text_chunks(doc):
            self._write(piece)
            time.sleep(0.02)
        self._write(chunk({}, finish_reason="stop"))

    # ---- 第二轮：拿到工具结果后的最终回答 --------------------------------

    def _round_two(self, tool_output: str):
        # 把设备真正算出来的结果原样回显 —— 界面上看到的数字因此是设备产的，
        # 不是这里编的。这是「工具真的在设备上执行了」的证据。
        answer = (
            f"工具返回了：\n{tool_output}\n\n"
            "上面这段是设备上的工具真实执行的结果，"
            "这条回答是本地 mock 服务端生成的，用来验证流式渲染与落库顺序。"
        )
        for piece in text_chunks(answer):
            self._write(piece)
            time.sleep(0.03)
        self._write(chunk({}, finish_reason="stop"))

    # ---- 底层写入（chunked 编码） ----------------------------------------

    def _write(self, text: str):
        if not text:
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
            return
        data = text.encode("utf-8")
        self.wfile.write(f"{len(data):X}\r\n".encode("ascii"))
        self.wfile.write(data)
        self.wfile.write(b"\r\n")
        self.wfile.flush()


class Server(ThreadingHTTPServer):
    """加了「客户端断开不算错」的 HTTP 服务端。

    默认实现会把**任何**异常打成一大坨 traceback。而用户点「停止」、
    或者 curl 读一行就走，都会触发 `ConnectionResetError` ——
    于是一屏吓人的堆栈其实只是「对面挂断了」。

    这种噪音比不打印更糟：它会训练人忽略日志，真出问题时也一起被忽略。
    """

    daemon_threads = True

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionResetError, ConnectionAbortedError, BrokenPipeError)):
            print(f"[mock] 客户端断开（{type(exc).__name__}），不是错误，忽略", flush=True)
            return
        super().handle_error(request, client_address)


if __name__ == "__main__":
    print(f"[mock] OpenAI 兼容假服务端已启动： http://{HOST}:{PORT}/v1/chat/completions")
    print(f"[mock] 别忘了： adb reverse tcp:{PORT} tcp:{PORT}")
    Server((HOST, PORT), Handler).serve_forever()
