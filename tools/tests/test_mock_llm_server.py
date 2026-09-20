"""`tools/mock-llm-server.py` 的判据测试 —— 零依赖，只用标准库。

跑法：

    python tools/tests/test_mock_llm_server.py

## 为什么这个「假服务端」值得有测试

它不是玩具，是**验收装置**：`csv` / `geo` / `history` / `web` 这几条路径是
「脚本插件 / 声明式插件 / 历史检索 / 联网搜索」**唯一**能在设备上端到端跑通的入口
（SKILL.md §89、§53、§56）。而它坏掉的方式是**静默**的：

- 触发器表被重排、或新加的关键字把旧的**吃掉**（`search history` 曾经就撞过
  `history` 那条）→ 某条路径再也测不到，但**没有任何报错**，只是「这次没验到」
- 文档里那张表和 `_pick_tool` 的代码**各自漂移** → 照着文档复现不出来，
  而人会先怀疑设备、怀疑 App，最后才怀疑文档
- 工具名写错（改成插件里不存在的名字）→ 走的是「工具不存在」那条错误路径，
  看起来像「功能坏了」

这三条都不是「跑一次就知道」的，所以钉在这里。

## 夹具

不启服务端，直接调 `_pick_tool`（纯函数）。模块文件名带 `-`，所以用
`importlib` 按路径加载 —— 这是唯一能 import 它的办法。
"""

import importlib.util
import json
import re
import sys
import unittest
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent.parent
SERVER = TOOLS_DIR / "mock-llm-server.py"


def load_server():
    spec = importlib.util.spec_from_file_location("mock_llm_server", SERVER)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)  # 只在 __main__ 里起服务，import 是安全的
    return mod


srv = load_server()


def pick(message):
    """模拟「用户说了 `message`」时服务端会请求调用哪个工具。"""
    return srv.Handler._pick_tool([{"role": "user", "content": message}])


def doc_table():
    """从模块 docstring 里解出那张「用户消息里含 → 请求调用」表。

    返回 `[(触发词原文, 工具名), …]`。

    **只认第二列写成 `工具名(...)` 的行。** 表里有两行的第二列不是工具名 ——
    表头（`请求调用`）和 `md` 那行（`不调工具，直接回一大段 Markdown`）——
    靠「后面跟一个 `(`」把它们和真正的工具行区分开。解不出来就抛：
    表格格式是这份文档的契约，改了格式就要改这里（**宁可红，也别静默少测几条**）。
    """
    rows = []
    for line in srv.__doc__.splitlines():
        m = re.match(r"\|\s*(.+?)\s*\|\s*`?(\w+)\(", line)
        if m and m.group(1) != "用户消息里含":
            rows.append((m.group(1), m.group(2)))
    assert len(rows) >= 6, f"只解出 {len(rows)} 行触发器 —— 表格格式变了？"
    return rows


class TableMatchesCodeTest(unittest.TestCase):
    """文档那张表必须和 `_pick_tool` 对得上。"""

    def test_表里每一行都真的会触发那个工具(self):
        for keywords, tool in doc_table():
            if keywords.startswith("其它"):
                continue  # 兜底分支单独测
            # 表格里写成 `web` / `search` 这种多触发词的形式
            first = re.split(r"\s*/\s*", keywords)[0].strip("`")
            actual, _params = pick(f"帮我 {first} 一下")
            self.assertEqual(
                tool,
                actual,
                f"文档说含「{first}」会请求 `{tool}`，实际请求的是 `{actual}`",
            )

    def test_兜底分支就是文档写的那个工具(self):
        actual, _ = pick("一句什么触发器都不含的话 zzz")
        self.assertEqual("no_such_tool_zzz", actual)

    def test_代码能返回的每个工具名都在文档里(self):
        """反向查：`_pick_tool` 里出现了一个文档没写的工具，就是漏了一条路径。"""
        documented = {tool for _k, tool in doc_table()}
        source = SERVER.read_text(encoding="utf-8")
        body = source.split("def _pick_tool")[1]
        returned = set(re.findall(r'return\s+"(\w+)",', body))
        self.assertTrue(returned, "没解出任何 return —— `_pick_tool` 的写法变了？")
        self.assertEqual(
            set(),
            returned - documented,
            "这些工具被 `_pick_tool` 返回，但文档的表里没有 —— 补一行，否则没人知道怎么触发它",
        )


class CsvTriggerTest(unittest.TestCase):
    """`csv` 那条是**脚本插件**唯一的验收路径（§89），单独钉死。"""

    def test_csv_触发的是脚本插件那个工具(self):
        tool, params = pick("csv 统计一下")
        self.assertEqual("csv_stats", tool, "改名的话 §89 的验收步骤就作废了")
        json.loads(params)  # 参数必须是合法 JSON，否则请求会被 App 拒掉

    def test_csv_参数里带的是可解析的_CSV(self):
        _tool, params = pick("csv")
        csv = json.loads(params)["csv"]
        rows = [r for r in csv.split("\n") if r.strip()]
        self.assertGreaterEqual(len(rows), 2, "至少要表头行 + 一行数据，否则脚本会抛")
        self.assertEqual(
            len(rows[0].split(",")),
            len(rows[1].split(",")),
            "表头和数据行的列数必须一致 —— 不一致时脚本算出来的统计量看着像对的",
        )

    def test_csv_同时覆盖数值列和文本列(self):
        """`csv_stats` 里那条「数值占比超过八成才当数值列」的分支两边都要走到。

        只有数值列的话，文本分支（`distinct` / `top`）永远不会被执行到，
        而「脚本真的跑了」这个结论就少了一半证据。
        """
        csv = json.loads(pick("csv")[1])["csv"]
        header, *body = [r for r in csv.split("\n") if r.strip()]
        numeric, text = 0, 0
        for idx in range(len(header.split(","))):
            values = [r.split(",")[idx] for r in body]
            if all(v.replace(".", "").isdigit() for v in values):
                numeric += 1
            else:
                text += 1
        self.assertGreaterEqual(numeric, 1, "没有数值列 → 均值/分位数那半边没被验到")
        self.assertGreaterEqual(text, 1, "没有文本列 → 取值分布那半边没被验到")

    def test_csv_不是别的触发词的子串冲突(self):
        """`csv` 不能被更靠前的分支吃掉。"""
        tool, _ = pick("csv")
        self.assertEqual("csv_stats", tool)


class ParamsAreValidJsonTest(unittest.TestCase):
    """所有触发器的参数都得是合法 JSON —— 拼字符串拼错了会在这里红。"""

    def test_每条路径的参数都是合法_JSON(self):
        for keywords, _tool in doc_table():
            first = re.split(r"\s*/\s*", keywords)[0].strip("`")
            if first.startswith("其它"):
                continue
            _tool, params = pick(f"帮我 {first} 一下")
            with self.subTest(trigger=first):
                json.loads(params)


if __name__ == "__main__":
    unittest.main(verbosity=2)
