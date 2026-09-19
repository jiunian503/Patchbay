#!/usr/bin/env python3
"""一个最小的 SearXNG 替身，用来在真机上验收 search_web 工具。

## 为什么需要一个假实例

三种后端里只有 SearXNG 能在「不注册、不花钱」的前提下自建 —— 而它恰好
也是唯一能被本机模拟的（一个 GET + 一段 JSON）。Brave / Tavily 要真的 Key，
没法在验收里反复跑。

而且真机验收要的是**确定性**：结果必须是固定的，这样才能判断
「模型回答里的那个事实是从这儿来的，不是它编的」。

## 用法

    python tools/mock-searxng.py 8767
    adb -s 127.0.0.1:7555 reverse tcp:8767 tcp:8767

然后在 App 里把后端选成 SearXNG、地址填 http://127.0.0.1:8767。

## 它会记录每一次请求

打出来的每一行都是一个证据：设备确实发了请求、关键词是什么、
带没带 format=json。验收要看的就是这些。
"""

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

# 结果里刻意放一个**编不出来的事实**：模型不可能从训练数据里知道
# 「Patchbay 的跳线板编号是 PB-7742」。如果它在回答里说出了这个编号，
# 那就只能是它真的调了搜索工具、真的读到了这段文字。
RESULTS = [
    {
        "title": "Patchbay 跳线板说明书",
        "url": "https://example.invalid/patchbay/manual",
        "content": "Patchbay 的跳线板编号是 PB-7742，出厂日期 2026 年 9 月，"
                   "支持的插件数量上限是 64 个。",
    },
    {
        "title": "Patchbay 常见问题",
        "url": "https://example.invalid/patchbay/faq",
        "content": "问：跳线板编号在哪里看？答：在设置页最下方，格式是 PB-XXXX。",
    },
    {
        "title": "<b>Patchbay</b> 发布说明",
        "url": "https://example.invalid/patchbay/release",
        "content": "本次发布新增了 <b>联网搜索</b> 工具，以及一个搜索结果缓存。",
    },
]


class Handler(BaseHTTPRequestHandler):

    def do_GET(self):  # noqa: N802 —— BaseHTTPRequestHandler 的约定
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        q = (query.get("q") or [""])[0]
        fmt = (query.get("format") or [""])[0]

        # 把请求打出来，这就是「设备真的来过」的证据
        sys.stdout.write(f"[req] path={parsed.path} q={q!r} format={fmt!r}\n")
        sys.stdout.flush()

        if parsed.path != "/search":
            return self._send(404, "text/plain", b"not found")

        # 模拟「实例没打开 json 格式」：SearXNG 在这种情况下返回的是
        # 一个 HTML 搜索页，而不是报错。这个分支是给「错误提示对不对」用的
        if fmt != "json":
            return self._send(
                200,
                "text/html; charset=utf-8",
                b"<!doctype html><html><body><form>search</form></body></html>",
            )

        payload = {
            "query": q,
            "number_of_results": len(RESULTS),
            "results": RESULTS,
        }
        return self._send(
            200,
            "application/json; charset=utf-8",
            json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        )

    def _send(self, code, content_type, body):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        """屏蔽默认的 stderr 日志 —— 上面那行 [req] 才是我们要看的。"""
        return


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8767
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    sys.stdout.write(f"mock searxng listening on http://127.0.0.1:{port}\n")
    sys.stdout.flush()
    server.serve_forever()


if __name__ == "__main__":
    main()
