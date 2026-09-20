"""`tools/archive_release.py` 的判据测试 —— 零依赖，只用标准库。

跑法：

    python tools/tests/test_archive_release.py

## 为什么这个脚本值得有测试

`verify()` 是发布流程里**唯一**的机械判据，而它判错的代价很高 —— 手册 §79 二 记着：
APK 和 mapping 不配套时 `retrace` **不报错**，会给一份行号错误但看着完全合理的结果。
也就是说这条判据要是失灵，没有任何人会当场发现，等发现时那一版的线上崩溃已经读不懂了。

这些分支上一轮是在命令行手敲验的（造一个假 zip、改一位 hash、跑一次），敲完就没了。
这里把它们固化成能重复跑的。

## 夹具

不碰真实的 APK 和 mapping（4 MB + 43 MB，而且每次构建都变）：

    APK     → 一个真的 zip，里面是若干 `.dex`，内容里嵌 `r8-map-id-<64hex>`
    mapping → 纯文本，按 R8 9.0 的真实头格式造（`_mapping` 里那份）

假 DEX 里刻意加了前后噪声字节 —— 真实 DEX 里那个字符串也是夹在常量池里的，
不是文件开头，所以正则不能假设位置。

写夹具用 `write_bytes` 而不是 `write_text`：后者在 Windows 上会把 `\\n` 翻成 CRLF，
而真实的 mapping 是 LF（§60④ 判据 6 就是踩这个踩出来的）。
"""
import contextlib
import io
import sys
import unittest
import zipfile
from pathlib import Path
from tempfile import TemporaryDirectory

TOOLS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS_DIR))

import archive_release as ar  # noqa: E402  —— 必须先改 sys.path 才能 import

# 都是合法的 64 位十六进制（正则只认小写 [0-9a-f]）
HASH_A = "a1" * 32
HASH_B = "b2" * 32
HASH_OTHER = "c3" * 32


def _apk(path: Path, *hashes: str | None) -> Path:
    """造一个「像 APK 一样」的 zip。一个元素一个 dex 文件。

    传 `None` 表示那个 dex 里**不放** map-id（模拟没经过 R8 的包）。
    """
    with zipfile.ZipFile(path, "w") as z:
        for i, h in enumerate(hashes):
            body = b"\x00\x01noise"
            if h is not None:
                body += f"r8-map-id-{h}".encode()
            z.writestr(f"classes{i}.dex", body + b"\xfftail")
    return path


# R8 9.0 真实输出的前 5 行，共 132 字节。`_mapping` 的 pad 计算依赖这个数 ——
# 改夹具时记得一起改（`test_pg_map_id_刚好落在_4KB_之内能读到` 会先红给你看）。
MAPPING_HEAD = [
    "# compiler: R8",
    "# compiler_version: 9.0.32",
    "# min_api: 28",
    "# common_typos_disable",
    '# {"id":"com.android.tools.r8.mapping","version":"2.2"}',
]


def _mapping(path: Path, map_id: str | None = None, pad_bytes: int = 0) -> Path:
    """按真实头格式造一份 mapping。

    `pad_bytes` 把 `# pg_map_id:` 往后推 —— 脚本只读前 `MAPPING_HEAD_BYTES` 个字符，
    这是那条边界唯一能被测到的办法。
    """
    lines = list(MAPPING_HEAD)
    if pad_bytes:
        # 塞一条长注释。真实 mapping 里紧跟这几行的就是第一个类映射。
        lines.append("# " + "x" * pad_bytes)
    lines.append(f"# pg_map_id: {map_id}" if map_id else "# pg_map_id: 不是哈希")
    lines.append("# pg_map_hash: SHA-256 " + "0" * 64)
    lines.append("com.example.Foo -> a.b.c:")
    path.write_bytes(("\n".join(lines) + "\n").encode("utf-8"))
    return path


class VerifyTest(unittest.TestCase):
    """`verify()` —— 「这两个产物配套吗」的唯一判据。"""

    def setUp(self) -> None:
        self._tmp = TemporaryDirectory()
        self.dir = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def expect_fail(self, apk: Path, mapping: Path) -> str:
        """跑 verify，断言它以退出码 1 结束；返回它写到 stderr 的话。"""
        err = io.StringIO()
        with contextlib.redirect_stderr(err), self.assertRaises(SystemExit) as cm:
            ar.verify(apk, mapping)
        self.assertEqual(1, cm.exception.code, "判据不通过时必须退出码 1，不能返回")
        return err.getvalue()

    def test_配套时返回那个_hash(self):
        apk = _apk(self.dir / "app.apk", HASH_A)
        mapping = _mapping(self.dir / "mapping.txt", HASH_A)
        self.assertEqual(HASH_A, ar.verify(apk, mapping))

    def test_mapping_文件不存在时停下(self):
        """没开混淆的构建不会产出 mapping —— 这时要说清原因，而不是报个找不到。"""
        apk = _apk(self.dir / "app.apk", HASH_A)
        msg = self.expect_fail(apk, self.dir / "没有这个文件.txt")
        self.assertIn("找不到", msg)

    def test_APK_没经过_R8_时停下(self):
        apk = _apk(self.dir / "app.apk", None)
        mapping = _mapping(self.dir / "mapping.txt", HASH_A)
        msg = self.expect_fail(apk, mapping)
        self.assertIn("r8-map-id", msg)

    def test_APK_里有多个不同_map_id_时停下(self):
        """混进了别的构建的 dex —— 这时**不能**随便挑一个用。"""
        apk = _apk(self.dir / "app.apk", HASH_A, HASH_B)
        mapping = _mapping(self.dir / "mapping.txt", HASH_A)
        msg = self.expect_fail(apk, mapping)
        self.assertIn("2 个不同的 map-id", msg)

    def test_同一个_hash_出现在两个_dex_里算一个(self):
        """去重是在 verify 里做的 —— 别把「多 dex」误判成「多版本」。"""
        apk = _apk(self.dir / "app.apk", HASH_A, HASH_A)
        mapping = _mapping(self.dir / "mapping.txt", HASH_A)
        self.assertEqual(HASH_A, ar.verify(apk, mapping))

    def test_mapping_里没有_pg_map_id_时停下(self):
        apk = _apk(self.dir / "app.apk", HASH_A)
        mapping = _mapping(self.dir / "mapping.txt", None)
        msg = self.expect_fail(apk, mapping)
        self.assertIn("pg_map_id", msg)

    def test_不配套时停下并把两个_hash_都打出来(self):
        """整个脚本存在的理由。不配套时 retrace 不报错，所以只能在这里拦住。"""
        apk = _apk(self.dir / "app.apk", HASH_A)
        mapping = _mapping(self.dir / "mapping.txt", HASH_B)
        msg = self.expect_fail(apk, mapping)
        self.assertIn("不配套", msg)
        self.assertIn(HASH_A, msg, "要打出来 APK 里那个 hash，否则没法判断是谁旧了")
        self.assertIn(HASH_B, msg, "也要打出来 mapping 里那个")

    def test_pg_map_id_被推到_4KB_之外就读不到(self):
        """脚本只读前 4 KB（整个文件 42 MB），代价是这条边界必须有人守。"""
        apk = _apk(self.dir / "app.apk", HASH_A)
        mapping = _mapping(self.dir / "mapping.txt", HASH_A, pad_bytes=ar.MAPPING_HEAD_BYTES)
        msg = self.expect_fail(apk, mapping)
        self.assertIn("pg_map_id", msg)

    def test_pg_map_id_刚好落在_4KB_之内能读到(self):
        """上一条的反面 —— 没有它，「读不到」也可能只是夹具自己造错了。"""
        apk = _apk(self.dir / "app.apk", HASH_A)
        mapping = _mapping(self.dir / "mapping.txt", HASH_A, pad_bytes=ar.MAPPING_HEAD_BYTES - 400)
        self.assertEqual(HASH_A, ar.verify(apk, mapping))

    def test_hash_必须是_64_位小写十六进制(self):
        """正则只认 `[0-9a-f]{64}` —— 大写、少一位、多一位、混进 g 都不算数。"""
        for bad in (HASH_A.upper(), HASH_A[:-1], HASH_A + "0", HASH_A.replace("a", "g", 1)):
            with self.subTest(bad=bad):
                stem = f"bad-{abs(hash(bad)) % 10000}"
                apk = _apk(self.dir / f"{stem}.apk", HASH_A)
                mapping = _mapping(self.dir / f"{stem}.txt", bad)
                self.expect_fail(apk, mapping)


class FindByMapIdTest(unittest.TestCase):
    """`--find-map-id` —— 用户发来一条堆栈，反查该用哪一份 mapping。"""

    def setUp(self) -> None:
        self._tmp = TemporaryDirectory()
        self.dist = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _archive(self, version: str, map_id: str) -> Path:
        d = self.dist / f"patchbay-{version}"
        d.mkdir(parents=True)
        (d / "map-id.txt").write_bytes((map_id + "\n").encode())
        (d / "README.txt").write_bytes(
            f"版本        {version} (1)\n源码        deadbee\nAPK         patchbay-{version}.apk\n".encode()
        )
        return d

    def test_命中时返回_0_并打印目录(self):
        self._archive("1.0", HASH_A)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(0, ar.find_by_map_id(self.dist, HASH_A))
        self.assertIn("patchbay-1.0", out.getvalue())

    def test_命中时顺带打印版本与源码(self):
        self._archive("1.0", HASH_A)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            ar.find_by_map_id(self.dist, HASH_A)
        text = out.getvalue()
        self.assertIn("版本        1.0 (1)", text)
        self.assertIn("deadbee", text)

    def test_大小写不敏感(self):
        """堆栈里的 hash 可能是大写，用户手抄时也常抄成大写。"""
        self._archive("1.0", HASH_A)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(0, ar.find_by_map_id(self.dist, HASH_A.upper()))

    def test_没命中时返回_1_并说清两种可能(self):
        self._archive("1.0", HASH_A)
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.assertEqual(1, ar.find_by_map_id(self.dist, HASH_OTHER))
        msg = err.getvalue()
        self.assertIn("没有", msg)
        self.assertIn("64 位十六进制", msg, "要提示 hash 抄错了这种可能")

    def test_dist_不存在时返回_1_而不是炸(self):
        """第一次发布之前 dist/ 根本不存在 —— 这条路上不能抛异常。"""
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.assertEqual(1, ar.find_by_map_id(self.dist / "还不存在", HASH_A))


class GradleVersionTest(unittest.TestCase):
    """归档目录名来自 `app/build.gradle.kts` 的 versionName。"""

    def setUp(self) -> None:
        self._tmp = TemporaryDirectory()
        self.dir = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _gradle(self, body: str) -> Path:
        p = self.dir / "build.gradle.kts"
        p.write_bytes(body.encode("utf-8"))
        return p

    def _expect_fail(self, gradle_file: Path) -> str:
        err = io.StringIO()
        with contextlib.redirect_stderr(err), self.assertRaises(SystemExit) as cm:
            ar.gradle_version(gradle_file)
        self.assertEqual(1, cm.exception.code)
        return err.getvalue()

    def test_能读到真实工程的版本(self):
        """对着真文件跑 —— 正则和 build.gradle.kts 脱节时这条会先红。"""
        code, name = ar.gradle_version()
        self.assertTrue(code.isdigit(), f"versionCode 读成了 {code!r}")
        self.assertNotEqual("unknown", name)

    def test_读不到_versionCode_时停下(self):
        msg = self._expect_fail(self._gradle('versionName = "2.0"\n'))
        self.assertIn("versionCode", msg)

    def test_读不到_versionName_时停下(self):
        """静默退回 "unknown" 的后果是归档目录叫 `patchbay-unknown`，没人会发现。"""
        msg = self._expect_fail(self._gradle("versionCode = 7\n"))
        self.assertIn("versionName", msg)


if __name__ == "__main__":
    unittest.main(verbosity=2)
