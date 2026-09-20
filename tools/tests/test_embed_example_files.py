"""`tools/embed_example_files.py` 的判据测试 —— 零依赖，只用标准库。

跑法：

    python tools/tests/test_embed_example_files.py

## 为什么这个脚本值得有测试

它改的是**源码文件**（`plugin/examples/*/manifest.json`），而且改法是
「整个文件重写成 `json.dumps` 的输出」。这类脚本出错的方式都很安静：

- 读法不对（`read_text` 在 Windows 上把 `\\n` 翻成 `\\r\\n`）→ 嵌进去的内容和磁盘
  上的字节不一致，于是**那条逐字节的守卫测试永远红**，而红的原因看着像
  「清单过期了」，于是一遍遍重跑这个脚本，越跑越不对
- 该跳过的没跳过（把 `manifest.json` 自己也嵌进去）→ 自我包含，第二次跑内容又变
- 幂等性坏了 → 每次跑都产生 diff，提交里全是噪音

这三个都不是「跑一次就知道」的，而是「换一台机器、换一次目录结构才发作」的。

## 夹具

一律在 `TemporaryDirectory` 里造一个假示例目录，不碰真实的
`plugin/examples/`（除了最后一条 —— 它**故意**要读真的，见那里的注释）。

写夹具用 `write_bytes` 而不是 `write_text`：这一条本身就是被测行为的一部分
（CRLF 必须原样保持），用 `write_text` 造夹具的话，那条用例就成了自证。
"""
import io
import contextlib
import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

TOOLS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS_DIR))

import embed_example_files as ef  # noqa: E402  —— 必须先改 sys.path 才能 import


SCRIPT_MANIFEST = {
    "id": "pub.test.demo",
    "name": "测试示例",
    "version": "1.0.0",
    "runtime": "script",
    "entry": {"script": {"main": "index.js"}},
    "tools": [
        {
            "name": "demo",
            "description": "测试用",
            "parameters": {"type": "object", "properties": {}},
        }
    ],
}


def make_example(root: Path, name: str = "demo", manifest: dict | None = None) -> Path:
    directory = root / name
    directory.mkdir(parents=True)
    payload = json.dumps(manifest or SCRIPT_MANIFEST, ensure_ascii=False, indent=2) + "\n"
    (directory / ef.MANIFEST).write_bytes(payload.encode("utf-8"))
    return directory


def read_manifest(directory: Path) -> dict:
    return json.loads((directory / ef.MANIFEST).read_bytes().decode("utf-8"))


class SourceFilesTest(unittest.TestCase):
    """挑哪些文件进包。"""

    def test_排除_manifest_自己(self):
        with TemporaryDirectory() as tmp:
            directory = make_example(Path(tmp))
            (directory / "index.js").write_bytes(b"x")

            self.assertEqual({"index.js"}, set(ef.source_files(directory)))

    def test_递归并用正斜杠分隔(self):
        with TemporaryDirectory() as tmp:
            directory = make_example(Path(tmp))
            (directory / "lib").mkdir()
            (directory / "lib" / "util.js").write_bytes(b"x")
            (directory / "index.js").write_bytes(b"x")

            # 用 `/` 而不是 os.sep：这些键会被写进 JSON，而 JSON 里没有
            # 「Windows 路径」这回事 —— 在 Windows 上打包出来的清单
            # 必须和 Linux 上一样
            self.assertEqual({"index.js", "lib/util.js"}, set(ef.source_files(directory)))

    def test_跳过点开头的文件和目录(self):
        with TemporaryDirectory() as tmp:
            directory = make_example(Path(tmp))
            (directory / "index.js").write_bytes(b"x")
            (directory / ".DS_Store").write_bytes(b"x")
            (directory / ".cache").mkdir()
            (directory / ".cache" / "junk").write_bytes(b"x")

            self.assertEqual({"index.js"}, set(ef.source_files(directory)))

    def test_空目录得到空表(self):
        with TemporaryDirectory() as tmp:
            directory = make_example(Path(tmp))

            self.assertEqual({}, ef.source_files(directory))


class RepackTest(unittest.TestCase):
    """生成逻辑本身。"""

    def test_非_script_形态不动(self):
        with TemporaryDirectory() as tmp:
            manifest = dict(SCRIPT_MANIFEST, runtime="declarative")
            directory = make_example(Path(tmp), manifest=manifest)
            (directory / "index.js").write_bytes(b"x")
            before = (directory / ef.MANIFEST).read_bytes()

            # 别的形态不消费 files（校验层会为此给一条警告），
            # 所以打包器也不该往里塞
            self.assertIsNone(ef.repack(directory))
            self.assertEqual(before, (directory / ef.MANIFEST).read_bytes())

    def test_一个源文件都没有时停下(self):
        with TemporaryDirectory() as tmp:
            directory = make_example(Path(tmp))

            # 不能悄悄写一份 files 为空的清单 —— 那会生成一份
            # 「entry 指向一个不存在的文件」的坏包，而它是能装上去的
            with self.assertRaises(SystemExit):
                ef.repack(directory)

    def test_嵌进去的内容等于磁盘字节(self):
        with TemporaryDirectory() as tmp:
            directory = make_example(Path(tmp))
            source = "exports.run = () => ({ ok: true })\n"
            (directory / "index.js").write_bytes(source.encode("utf-8"))

            old, new = ef.repack(directory)
            (directory / ef.MANIFEST).write_bytes(new.encode("utf-8"))

            self.assertIn("index.js", old)
            self.assertEqual(source, read_manifest(directory)["files"]["index.js"])

    def test_幂等(self):
        with TemporaryDirectory() as tmp:
            directory = make_example(Path(tmp))
            (directory / "index.js").write_bytes(b"x")

            _, new = ef.repack(directory)
            (directory / ef.MANIFEST).write_bytes(new.encode("utf-8"))

            # 第二次必须说「没变化」。不幂等的话每次跑都产生 diff，
            # 提交里全是噪音，而真正的内容改动会被埋掉
            self.assertIsNone(ef.repack(directory))

    def test_源文件变了会重新打包(self):
        with TemporaryDirectory() as tmp:
            directory = make_example(Path(tmp))
            (directory / "index.js").write_bytes(b"one")
            _, new = ef.repack(directory)
            (directory / ef.MANIFEST).write_bytes(new.encode("utf-8"))

            (directory / "index.js").write_bytes(b"two")

            result = ef.repack(directory)
            self.assertIsNotNone(result, "源文件改了却没重新打包")
            _, again = result
            self.assertIn("two", again)

    def test_CRLF_原样保留(self):
        with TemporaryDirectory() as tmp:
            directory = make_example(Path(tmp))
            # 故意造一份 CRLF 的源文件
            (directory / "index.js").write_bytes(b"a\r\nb\r\n")

            _, new = ef.repack(directory)
            (directory / ef.MANIFEST).write_bytes(new.encode("utf-8"))

            # 关键断言：不能变成 LF。用 `read_text()` 读源文件的话，
            # Windows 上这里会是 "a\nb\n" —— 而症状是「那条逐字节的守卫
            # 测试永远红」，红的原因看着像清单过期，于是反复重跑这个脚本
            self.assertEqual("a\r\nb\r\n", read_manifest(directory)["files"]["index.js"])

    def test_落盘用_LF_不用_CRLF(self):
        with TemporaryDirectory() as tmp:
            directory = make_example(Path(tmp))
            (directory / "index.js").write_bytes(b"x")

            _, new = ef.repack(directory)
            (directory / ef.MANIFEST).write_bytes(new.encode("utf-8"))

            # 清单本身必须是 LF（§60：这个仓库要求 LF，`git checkout` 才是逐字节还原）
            raw = (directory / ef.MANIFEST).read_bytes()
            self.assertNotIn(b"\r\n", raw)

    def test_清单里别的字段一个不动(self):
        with TemporaryDirectory() as tmp:
            directory = make_example(Path(tmp))
            (directory / "index.js").write_bytes(b"x")

            _, new = ef.repack(directory)
            (directory / ef.MANIFEST).write_bytes(new.encode("utf-8"))
            after = read_manifest(directory)

            # 打包只该加一个键。手写的清单里有注释式的空行、有刻意的字段顺序，
            # 但 `json.dumps` 会重排格式 —— 那些是可接受的代价，
            # 而**内容**一个都不能变
            for key, value in SCRIPT_MANIFEST.items():
                self.assertEqual(value, after[key], f"字段 {key} 被改动了")
            self.assertEqual({"index.js"}, set(after["files"]))


class CommandLineTest(unittest.TestCase):
    """命令行行为。"""

    def _run(self, argv, examples_dir):
        out = io.StringIO()
        original = ef.EXAMPLES
        ef.EXAMPLES = examples_dir
        try:
            with contextlib.redirect_stdout(out):
                code = ef.main(argv)
        finally:
            ef.EXAMPLES = original
        return code, out.getvalue()

    def test_dry_run_不落盘(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            directory = make_example(root)
            (directory / "index.js").write_bytes(b"x")
            before = (directory / ef.MANIFEST).read_bytes()

            code, output = self._run(["--dry-run"], root)

            self.assertEqual(0, code)
            self.assertIn("会更新", output)
            self.assertEqual(before, (directory / ef.MANIFEST).read_bytes())

    def test_指定不存在的示例时报错(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            make_example(root)

            # 名字打错时**不能**静默地「处理了 0 个」——
            # 那和「全都已经同步」长得一模一样
            with self.assertRaises(SystemExit):
                self._run(["nope"], root)

    def test_只处理指定的那几个(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            first = make_example(root, name="one")
            second = make_example(root, name="two")
            (first / "index.js").write_bytes(b"x")
            (second / "index.js").write_bytes(b"x")

            code, _ = self._run(["one"], root)

            self.assertEqual(0, code)
            self.assertIn("files", read_manifest(first))
            self.assertNotIn("files", read_manifest(second))


class RealExamplesTest(unittest.TestCase):
    """真实示例目录当前**已经**是同步的。

    ## 为什么这条要读真的

    上面全是拿临时目录造的假示例，它们证明的是「逻辑对不对」，
    证明不了「仓库里现在这份对不对」。而这一条正好反过来：
    它不检查逻辑，只检查**当前状态** —— 跑一遍打包器，断言它无事可做。

    于是「改了 `index.js` 忘了跑打包器」这件事在 Python 这一侧也会红，
    不必等到 Gradle 测试（那要慢得多）。两边是同一个判据的两个入口，
    这不算重复：`ExampleManifestsTest` 那条是给「跑测试」用的，
    这条是给「改完示例顺手验一下」用的。
    """

    def test_仓库里的示例都已经同步(self):
        directories = [d for d in sorted(ef.EXAMPLES.iterdir()) if d.is_dir()]
        self.assertTrue(directories, f"示例目录下一个子目录都没有：{ef.EXAMPLES}")

        stale = []
        for directory in directories:
            if ef.repack(directory) is not None:
                stale.append(directory.name)

        self.assertEqual(
            [],
            stale,
            "这些示例的 manifest.json 和它旁边的源文件不同步，"
            "跑 `python tools/embed_example_files.py` 修一下",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
