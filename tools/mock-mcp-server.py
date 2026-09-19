#!/usr/bin/env python3
"""Mock MCP 服务端 —— 用来在不花 token、不依赖外部服务的情况下验收 MCP 运行时。

## 它存在的理由

MCP 的线上协议有几条**只在真实 HTTP 交互里才会显形**的规则：响应可以走
`application/json` 也可以走请求作用域的 `text/event-stream`；`400` 的 body
决定了客户端该不该回退到旧协议握手；`Mcp-Method` / `Mcp-Name` 必须和 body
一致。这些在单测里用 MockWebServer 已经覆盖了，但**客户端是不是真的按
这些规则在真机上发请求**，只有让一个真实服务端把收到的头打印出来才知道。

## 用法

    # 1. 起服务（用 run_in_background 常驻，不要写成 `&` —— 结束 shell 会被一起杀）
    python tools/mock-mcp-server.py

    # 2. 把设备的 8766 端口反向转发到本机（daemon 会被回收，要和后续操作串在一条命令里）
    adb -s 127.0.0.1:7555 reverse tcp:8766 tcp:8766

    # 3. 装一个 MCP 插件，url 填 http://127.0.0.1:8766/mcp
    #    白名单要写 127.0.0.1

## 模式（`--mode`）

| 模式 | 行为 | 验收什么 |
|---|---|---|
| `modern` | 每个请求回 JSON | 正常路径：一次请求拿到工具 |
| `modern-sse` | 先推一条通知，再在事件流里给响应 | 客户端会不会把第一条 data 当成响应 |
| `modern-sse-keepalive` | 事件流里插 keep-alive 注释行 | 注释行有没有被当成事件 |
| `legacy` | 对现代版请求回 400 空 body，然后握手 | 回退判定（认不出来 → 回退） |
| `modern-error` | 对现代版请求回 400 + HeaderMismatch | **不回退**（认得出来） |
| `modern-version` | 回 400 + UnsupportedProtocolVersion（只列旧版） | 版本谈不拢时回退 |
| `reject-origin` | 一切请求回 403 | 403 的报错文案 |
| `huge` | 回一个超大的工具说明 | 说明截断 |
| `slow` | 每个请求延迟 5 秒 | 装配时的等待表现 |

服务端把**每个请求的方法、头、body** 都打印出来，形如
`[mcp] <- tools/call  Mcp-Name=search  meta=yes`，验收时直接看这个日志。
"""

import argparse
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PROTOCOL_VERSION = "2026-07-28"
LEGACY_VERSION = "2025-11-25"
SESSION_ID = "mock-session-1"

META_VERSION_KEY = "io.modelcontextprotocol/protocolVersion"
META_CAPABILITIES_KEY = "io.modelcontextprotocol/clientCapabilities"
META_CLIENT_INFO_KEY = "io.modelcontextprotocol/clientInfo"

TOOLS = [
    {
        "name": "echo",
        "description": (
            "回显一段文字。用户说「试试这个 MCP 工具」时调用它。"
            "参数 text 是要回显的内容。"
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string", "description": "要回显的文字"}},
            "required": ["text"],
        },
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "region_lookup",
        "description": "按区域查数据。region 参数会同时出现在请求头 Mcp-Param-Region 里。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "region": {"type": "string", "x-mcp-header": "Region"},
                "q": {"type": "string"},
            },
        },
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "write_note",
        "description": "写一条备注到服务端。会改动服务端数据。",
        "inputSchema": {
            "type": "object",
            "properties": {"note": {"type": "string"}},
        },
        "annotations": {"readOnlyHint": False},
    },
]

# 收到过哪些请求，验收时打印出来
RECEIVED = []


def log(line: str) -> None:
    print(line, flush=True)


def sse(payloads, keepalive=False) -> str:
    """拼一个请求作用域的 SSE 流。"""
    out = []
    if keepalive:
        out.append(":\r\n\r\n")  # keep-alive 注释行
    for p in payloads:
        out.append("data: " + json.dumps(p, ensure_ascii=False) + "\n\n")
    return "".join(out)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    mode = "modern"

    # ---------------------------------------------------------------- 输出

    def log_message(self, fmt, *args):  # noqa: A003 - 覆盖基类的噪音日志
        pass

    def _send(self, code: int, body: str, content_type: str = "application/json", extra=None):
        raw = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(raw)

    # ---------------------------------------------------------------- 请求

    def do_POST(self):  # noqa: N802 - BaseHTTPRequestHandler 的约定
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8", "replace")
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = {}

        method = body.get("method", "?")
        params = body.get("params") or {}
        meta = params.get("_meta") or {}
        RECEIVED.append(method)

        log(
            f"[mcp] <- {method:28s} "
            f"version={self.headers.get('MCP-Protocol-Version') or '-':12s} "
            f"method_hdr={self.headers.get('Mcp-Method') or '-':16s} "
            f"name_hdr={self.headers.get('Mcp-Name') or '-':12s} "
            f"session={self.headers.get('Mcp-Session-Id') or '-':12s} "
            f"meta={'yes' if META_VERSION_KEY in meta else 'NO'}"
        )
        for h in ("Mcp-Param-Region",):
            if self.headers.get(h):
                log(f"[mcp]     {h}: {self.headers.get(h)}")

        if self.mode == "reject-origin":
            self._send(403, "")
            return

        if self.mode == "slow":
            time.sleep(5)

        if self.mode == "legacy" and self.headers.get("MCP-Protocol-Version"):
            # 旧服务端不认识现代版的头。**空 body** 是关键：客户端靠
            # 「body 里有没有认得出的现代错误」来判断该不该回退
            log("[mcp]     旧服务端：对现代版请求回 400 空 body")
            self._send(400, "")
            return

        if self.mode == "modern-error" and method == "tools/list" and not RECEIVED.count("initialize"):
            log("[mcp]     回 400 + HeaderMismatch（客户端**不该**回退）")
            self._send(
                400,
                json.dumps({
                    "jsonrpc": "2.0",
                    "id": body.get("id"),
                    "error": {"code": -32020, "message": "header mismatch"},
                }),
            )
            return

        if self.mode == "modern-version" and not RECEIVED.count("initialize"):
            log("[mcp]     回 400 + UnsupportedProtocolVersion（只列旧版）")
            self._send(
                400,
                json.dumps({
                    "jsonrpc": "2.0",
                    "id": body.get("id"),
                    "error": {
                        "code": -32022,
                        "message": "Unsupported protocol version",
                        "data": {"supported": [LEGACY_VERSION], "requested": PROTOCOL_VERSION},
                    },
                }),
            )
            return

        if method == "initialize":
            self._send(
                200,
                json.dumps({
                    "jsonrpc": "2.0",
                    "id": body.get("id"),
                    "result": {
                        "protocolVersion": LEGACY_VERSION,
                        "capabilities": {"tools": {}},
                        "serverInfo": {"name": "mock-mcp", "version": "1.0.0"},
                    },
                }),
                extra={"Mcp-Session-Id": SESSION_ID},
            )
            return

        if method.startswith("notifications/"):
            # notification 被接受 → 202，**没有 body**
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        if method == "tools/list":
            tools = list(TOOLS)
            if self.mode == "huge":
                tools[0] = dict(tools[0], description="很长的说明。" * 2000)
            result = {"jsonrpc": "2.0", "id": body.get("id"), "result": {"tools": tools}}

            if self.mode in ("modern-sse", "modern-sse-keepalive"):
                # 规范允许「先推该请求相关的通知，再给最终响应」——
                # 取第一条 data 的实现会在这里解析出一个没有 result 的对象
                stream = sse(
                    [
                        {"jsonrpc": "2.0", "method": "notifications/message",
                         "params": {"level": "info", "data": "查工具列表"}},
                        result,
                    ],
                    keepalive=self.mode == "modern-sse-keepalive",
                )
                self._send(200, stream, content_type="text/event-stream")
            else:
                self._send(200, json.dumps(result, ensure_ascii=False))
            return

        if method == "tools/call":
            name = params.get("name")
            args = params.get("arguments") or {}
            if name == "echo":
                text = str(args.get("text", ""))
                content = [{"type": "text", "text": f"服务端收到：{text}"}]
                result = {"jsonrpc": "2.0", "id": body.get("id"),
                          "result": {"content": content, "isError": False}}
            elif name == "region_lookup":
                header = self.headers.get("Mcp-Param-Region") or "（没有这个头）"
                result = {"jsonrpc": "2.0", "id": body.get("id"), "result": {
                    "content": [{"type": "text",
                                 "text": f"region 参数={args.get('region')}；"
                                         f"Mcp-Param-Region 头={header}"}],
                    "isError": False,
                }}
            elif name == "write_note":
                result = {"jsonrpc": "2.0", "id": body.get("id"),
                          "result": {"content": [{"type": "text", "text": "已写入"}],
                                     "isError": False}}
            else:
                result = {"jsonrpc": "2.0", "id": body.get("id"),
                          "result": {"content": [{"type": "text", "text": f"未知工具 {name}"}],
                                     "isError": True}}
            self._send(200, json.dumps(result, ensure_ascii=False))
            return

        # 其余方法一律 404 + -32601。这个码能把它和「旧 HTTP+SSE 服务端的 404」区分开
        self._send(404, json.dumps({
            "jsonrpc": "2.0",
            "id": body.get("id"),
            "error": {"code": -32601, "message": f"Method not found: {method}"},
        }))

    def do_GET(self):  # noqa: N802
        # 现代版服务端收到 GET 要回 405 —— 这是区分「现代服务端」和
        # 「旧 HTTP+SSE 服务端」的信号之一
        log("[mcp] <- GET（现代版服务端回 405）")
        self._send(405, "")


def main() -> int:
    parser = argparse.ArgumentParser(description="Mock MCP 服务端")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--mode", default="modern", help="见文件头的模式表")
    args = parser.parse_args()

    Handler.mode = args.mode
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    log(f"[mcp] 监听 http://127.0.0.1:{args.port}/mcp  模式={args.mode}")
    log("[mcp] 记得：adb -s 127.0.0.1:7555 reverse tcp:8766 tcp:8766")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    threading.current_thread().name = "mock-mcp"
    sys.exit(main())
