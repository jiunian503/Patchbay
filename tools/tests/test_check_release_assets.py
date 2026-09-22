"""`tools/check_release_assets.py` 的判据测试 —— 零依赖，只用标准库。

跑法：

    python tools/tests/test_check_release_assets.py

## 为什么这个脚本值得有测试

它是「某一版的 mapping 悄悄坏了」**唯一**的机械判据。而这件事的后果不响亮：
`map-id` 对不上时 `--find-map-id` 不会报错，它会指错版本、拿错 mapping 去
retrace —— 给一份行号错误但看着完全合理的结果（§93）。等发现时那一版的线上
崩溃已经读不懂了。

更直接的理由：**它上一版就是坏的**。文档里那段 heredoc 里的正则
`pg_map_id:\\s*(...)` 在 Git Bash 下会被盘符路径转换改成 `pg_map_id:/s*(...)`
⇒ 每一版都报 MISMATCH，而那句话把人引向「归档坏了」。这些用例把它固化成
能重复跑的，而不是靠某一次手工敲对。

## 夹具

不碰真的 43 MB mapping（每版一个，读完要几秒，而且内容随发版变）：
在临时目录里造一个 `dist/`，按 R8 9.0 的真实头格式造 mapping、造一个真的
zip 当 APK。形状和 `test_archive_release.py` 里那对工厂函数一样 —— 它们是
**夹具不是判据**，重复一份比让两个测试文件互相 import 更结实。

写夹具用 `write_bytes` 而不是 `write_text`：后者在 Windows 上会把 `\\n` 翻成
CRLF，而真实的 mapping 是 LF（§60④ 判据 6 就是踩这个踩出来的）。
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

import check_release_assets as cr  # noqa: E402  —— 必须先改 sys.path 才能 import

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


def _mapping(path: Path, map_id: str | None = None) -> Path:
    """按 R8 9.0 的真实头格式造一份 mapping。`None` = 没有 pg_map_id 行。"""
    lines = [
        "# compiler: R8",
        "# compiler_version: 9.0.32",
        "# min_api: 28",
        "# common_typos_disable",
        '# {"id":"com.android.tools.r8.mapping","version":"2.2"}',
    ]
    if map_id is not None:
        lines.append(f"# pg_map_id: {map_id}")
    path.write_bytes(("\n".join(lines) + "\n").encode())
    return path


class CheckReleaseAssetsTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = TemporaryDirectory()
        self.dist = Path(self._tmp.name) / "dist"
        self.dist.mkdir()

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _ok_version(self, version: str = "1.0", hash_: str = HASH_A) -> Path:
        """造一个三方一致的版本目录。"""
        d = self.dist / f"patchbay-{version}"
        d.mkdir()
        _apk(d / f"patchbay-{version}.apk", hash_)
        _mapping(d / "mapping.txt", hash_)
        (d / "map-id.txt").write_bytes((hash_ + "\n").encode())
        return d

    def _run_main(self) -> tuple[int, str]:
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = cr.main(self.dist)
        return code, out.getvalue()

    # ---- 判据本身 ----

    def test_三方一致时通过(self):
        d = self._ok_version()
        self.assertEqual([], cr.check_version_dir(d))

    def test_map_id_txt_写错时报三方不一致(self):
        """`map-id.txt` 是给 `--find-map-id` 反查用的，写错就指错版本。"""
        d = self._ok_version()
        (d / "map-id.txt").write_bytes((HASH_OTHER + "\n").encode())
        problems = cr.check_version_dir(d)
        self.assertEqual(1, len(problems), problems)
        self.assertIn("三方不一致", problems[0])
        # 三个值都得摆出来，不然拿到这句话没法查
        self.assertIn(HASH_A, problems[0])
        self.assertIn(HASH_OTHER, problems[0])

    def test_mapping_里的_pg_map_id_和_APK_不同时报出来(self):
        d = self._ok_version()
        _mapping(d / "mapping.txt", HASH_B)
        problems = cr.check_version_dir(d)
        self.assertEqual(1, len(problems), problems)
        self.assertIn("三方不一致", problems[0])
        self.assertIn(HASH_B, problems[0])

    def test_mapping_里没有_pg_map_id_行时报出来(self):
        d = self._ok_version()
        _mapping(d / "mapping.txt", None)
        problems = cr.check_version_dir(d)
        self.assertTrue(any("pg_map_id" in p for p in problems), problems)

    def test_APK_没经过_R8_时报出来(self):
        """没经过 R8 的包，mapping 对它没有意义 —— 不能当「配套」放过。"""
        d = self.dist / "patchbay-1.0"
        d.mkdir()
        _apk(d / "patchbay-1.0.apk", None)
        _mapping(d / "mapping.txt", HASH_A)
        (d / "map-id.txt").write_bytes((HASH_A + "\n").encode())
        problems = cr.check_version_dir(d)
        self.assertTrue(any("没经过 R8" in p for p in problems), problems)

    def test_APK_里有多个_map_id_时报出来(self):
        """多于一个 id 时不能随便挑一个 —— 那是替一份来路不明的 mapping 背书。"""
        d = self.dist / "patchbay-1.0"
        d.mkdir()
        _apk(d / "patchbay-1.0.apk", HASH_A, HASH_OTHER)
        _mapping(d / "mapping.txt", HASH_A)
        (d / "map-id.txt").write_bytes((HASH_A + "\n").encode())
        problems = cr.check_version_dir(d)
        self.assertTrue(any("2 个不同的 map-id" in p for p in problems), problems)

    def test_目录名与_APK_文件名不符时报出来(self):
        d = self.dist / "patchbay-1.3"
        d.mkdir()
        _apk(d / "patchbay-1.2.apk", HASH_A)  # 名字不对
        _mapping(d / "mapping.txt", HASH_A)
        (d / "map-id.txt").write_bytes((HASH_A + "\n").encode())
        problems = cr.check_version_dir(d)
        self.assertTrue(any("不符" in p for p in problems), problems)

    def test_缺文件时报出来(self):
        d = self.dist / "patchbay-1.0"
        d.mkdir()
        _apk(d / "patchbay-1.0.apk", HASH_A)
        problems = cr.check_version_dir(d)
        self.assertEqual(1, len(problems), problems)
        self.assertIn("mapping.txt", problems[0])
        self.assertIn("map-id.txt", problems[0])

    def test_目录里没有_apk_时报出来(self):
        d = self.dist / "patchbay-1.0"
        d.mkdir()
        _mapping(d / "mapping.txt", HASH_A)
        (d / "map-id.txt").write_bytes((HASH_A + "\n").encode())
        problems = cr.check_version_dir(d)
        self.assertTrue(any("没有 .apk" in p for p in problems), problems)

    # ---- 扫描范围与退出码 ----

    def test_扫描是自动的_新增一版就会被核到(self):
        """守住「不再写死版本列表」—— 原来那份硬编码到 1.2，1.3/1.4 从没被核过。"""
        self._ok_version("9.8")
        self._ok_version("9.9")
        code, text = self._run_main()
        self.assertIn("patchbay-9.8", text)
        self.assertIn("patchbay-9.9", text)
        self.assertEqual(0, code, text)

    def test_有一版不配套时_main_返回_1(self):
        self._ok_version("9.9")
        d = self._ok_version("9.8")
        (d / "map-id.txt").write_bytes((HASH_OTHER + "\n").encode())
        code, text = self._run_main()
        self.assertEqual(1, code, text)
        self.assertIn("1 个有问题", text)

    def test_dist_为空时报错而不是静默通过(self):
        """没有归档 ≠ 全部通过 —— 静默的绿比红危险。"""
        code, _ = self._run_main()
        self.assertEqual(1, code)

    # ---- 对着真归档 ----

    def test_真实_dist_里每一版都三方一致(self):
        """对着真归档跑 —— 这是这个脚本存在的意义。

        同时守住两件事：① 当前 `dist/` 下每一版都配套；② 脚本自己没坏
        （正则被改坏、只读 4 KB 的那行没了，都会让这条先红）。
        """
        dirs = sorted(p for p in cr.DIST.glob("patchbay-*") if p.is_dir())
        self.assertTrue(dirs, f"{cr.DIST} 下没有归档 —— 这条测试就没意义了")
        for d in dirs:
            self.assertEqual([], cr.check_version_dir(d), f"{d.name} 三方对不上")


if __name__ == "__main__":
    unittest.main(verbosity=2)
