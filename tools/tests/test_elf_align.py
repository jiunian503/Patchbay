"""`tools/elf_align.py` 的判据测试 —— 零依赖，只用标准库。

跑法：

    python tools/tests/test_elf_align.py

## 为什么这个脚本值得有测试

它算出来的东西**直接支撑一条会写进手册的判据**（16 KB 内存页，SKILL.md §88 六），
而它读的是二进制结构。这类解析器出错的方式都很安静 —— 不抛异常，只是**报一个错的数**：

- **ELF32 和 ELF64 的 program header 字段顺序不一样**。ELF64 是
  `p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align`；
  ELF32 是 `p_type, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_flags, p_align`
  —— **`p_flags` 从第二位挪到了第七位**。照着 ELF64 的格式去解 32 位的库，
  读到的 `p_align` 是垃圾值，而输出看着像模像样
- **端序**同理（`e_ident[5]`）
- 大小端/位数都对，但 `e_phoff` 的偏移取错（0x20 vs 0x1C）→ 读到别的字段

这三种都不会崩，只会让「这个库是不是 16 KB 对齐」这句话说反 —— 而下游的后果是
「在 16 KB 页设备上试着加载一个会崩的库」，或者反过来「白白关掉一个能用的功能」。

## 夹具

用 `struct` 现场拼一个**只有头部和 program header、没有代码**的合法 ELF。
不需要真编译 —— 被测的是「解析」这件事，不是链接器。
"""

import contextlib
import io
import struct
import sys
import unittest
import zipfile
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS_DIR))

import elf_align as ea  # noqa: E402  —— 必须先改 sys.path 才能 import

PT_LOAD = 1
PT_NOTE = 4


def make_elf(is64=True, little=True, loads=((0x0, 0x0, 0x1000),)):
    """拼一个最小 ELF：只有 header + program header 表。

    `loads` 是 `(p_offset, p_vaddr, p_align)` 的三元组序列，全部生成 PT_LOAD。
    前面额外插一个 PT_NOTE，用来验证「只挑 PT_LOAD」这条过滤真的生效。
    """
    end = "<" if little else ">"
    ident = b"\x7fELF" + bytes([2 if is64 else 1, 1 if little else 2, 1]) + b"\x00" * 9
    entries = [(PT_NOTE, 0, 0, 0)] + [(PT_LOAD, off, va, al) for off, va, al in loads]
    if is64:
        ehsize, phentsize, phoff = 64, 56, 64
        header = ident + struct.pack(
            end + "HHIQQQIHHHHHH", 3, 0xB7, 1, 0, phoff, 0, 0, ehsize, phentsize, len(entries), 0, 0, 0
        )
        body = b"".join(
            struct.pack(end + "IIQQQQQQ", t, 0, off, va, va, 16, 16, al) for t, off, va, al in entries
        )
    else:
        ehsize, phentsize, phoff = 52, 32, 52
        header = ident + struct.pack(
            end + "HHIIIIIHHHHHH", 3, 0x28, 1, 0, phoff, 0, 0, ehsize, phentsize, len(entries), 0, 0, 0
        )
        body = b"".join(
            struct.pack(end + "IIIIIIII", t, off, va, va, 16, 16, 0, al) for t, off, va, al in entries
        )
    return header + body


def capture(fn, *args):
    """跑 `fn` 并把 stdout 收回来（`report()` 是打印型的）。"""
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        fn(*args)
    return buf.getvalue()


class PhdrsTest(unittest.TestCase):
    """解析层：位数、端序、只挑 PT_LOAD。"""

    def test_只挑_PT_LOAD_不挑别的段(self):
        blob = make_elf(loads=((0x0, 0x0, 0x1000), (0x2000, 0x3000, 0x4000)))
        loads = ea.phdrs(blob)
        self.assertEqual(2, len(loads), "PT_NOTE 不该被算进来")
        self.assertEqual([0x0, 0x2000], [l[1] for l in loads])

    def test_ELF64_小端_读对_offset_vaddr_align(self):
        blob = make_elf(is64=True, little=True, loads=((0x1c40, 0x5c40, 0x4000),))
        (load,) = ea.phdrs(blob)
        self.assertEqual((0x1c40, 0x5c40, 0x4000), (load[1], load[2], load[5]))

    def test_ELF32_的_p_flags_在第七位_不是第二位(self):
        """这条就是「照着 64 位的格式解 32 位的库」那个坑。

        ELF32 里 `p_flags` 排在 `p_memsz` **之后**。如果解析器照 ELF64 的顺序解，
        会把 `p_flags`（0）当成 `p_offset`、把 `p_offset` 当成 `p_vaddr`……
        最后读到的 `p_align` 是个垃圾值。这条用非零的 offset/vaddr 把它钉住。
        """
        blob = make_elf(is64=False, little=True, loads=((0x14c0, 0x54c0, 0x4000),))
        (load,) = ea.phdrs(blob)
        self.assertEqual(0x14c0, load[1], "p_offset 读错 —— 十有八九是按 ELF64 的格式解的")
        self.assertEqual(0x54c0, load[2])
        self.assertEqual(0x4000, load[5], "p_align 读错")

    def test_大端也能读(self):
        blob = make_elf(is64=False, little=False, loads=((0x1000, 0x2000, 0x1000),))
        (load,) = ea.phdrs(blob)
        self.assertEqual((0x1000, 0x2000, 0x1000), (load[1], load[2], load[5]))

    def test_不是_ELF_要抛(self):
        with self.assertRaises(ValueError):
            ea.phdrs(b"not an elf at all")


class VerdictTest(unittest.TestCase):
    """结论层：`report()` 打印的那两句话。"""

    def test_全部_4KB_判成只有_4KB(self):
        out = capture(ea.report, "x.so", make_elf(loads=((0x0, 0x0, 0x1000), (0x1000, 0x2000, 0x1000))))
        self.assertIn("**只有 4KB**", out)
        self.assertNotIn("16KB OK", out)

    def test_有一段_4KB_就整体判成_只有_4KB(self):
        """取的是**最小** `p_align` —— 只要有一个段按 4 KB 排，整库就不兼容。"""
        out = capture(ea.report, "x.so", make_elf(loads=((0x0, 0x0, 0x4000), (0x1fd0, 0x9fd0, 0x1000))))
        self.assertIn("**只有 4KB**", out)

    def test_全_16KB_且同余_判成_OK(self):
        out = capture(ea.report, "x.so", make_elf(loads=((0x0, 0x0, 0x4000), (0x1c40, 0x5c40, 0x4000))))
        self.assertIn("16KB OK", out)
        self.assertEqual(2, out.count("同余 OK"))
        self.assertNotIn("同余不成立", out)

    def test_16KB_但不同余_要报出来(self):
        """`p_offset ≡ p_vaddr (mod 0x4000)` 不成立 = 重定位后指向错的页。

        这正是 Google 文档里 RELRO 段那条：**不报错，运行期 segfault**。
        """
        out = capture(ea.report, "x.so", make_elf(loads=((0x0, 0x0, 0x4000), (0x1c40, 0x2c40, 0x4000))))
        self.assertIn("同余不成立", out)

    def test_4KB_的段不做同余判定(self):
        """`p_align` 只有 4 KB 时同余无从谈起 —— 不该刷一堆假警告。"""
        out = capture(ea.report, "x.so", make_elf(loads=((0x0, 0x0, 0x1000),)))
        self.assertNotIn("同余", out)


class ApkTest(unittest.TestCase):
    """`main()` 的 APK 分支 —— 真机上就是这么用的。"""

    def test_能吃_apk_并遍历_lib_下所有_so(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("lib/arm64-v8a/libquickjs.so", make_elf(loads=((0x0, 0x0, 0x1000),)))
            z.writestr("lib/arm64-v8a/libpath.so", make_elf(loads=((0x0, 0x0, 0x4000),)))
            z.writestr("classes.dex", b"\x00")  # 不该被碰
        apk = Path(self.enterContext(__import__("tempfile").TemporaryDirectory())) / "a.apk"
        apk.write_bytes(buf.getvalue())

        out = capture(ea.main, str(apk))
        self.assertIn("lib/arm64-v8a/libquickjs.so", out)
        self.assertIn("lib/arm64-v8a/libpath.so", out)
        self.assertIn("**只有 4KB**", out)
        self.assertIn("16KB OK", out)
        self.assertNotIn("classes.dex", out)

    def test_没有_native_库的_apk_要说清楚(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("classes.dex", b"\x00")
        apk = Path(self.enterContext(__import__("tempfile").TemporaryDirectory())) / "b.apk"
        apk.write_bytes(buf.getvalue())
        self.assertIn("没有", capture(ea.main, str(apk)))


if __name__ == "__main__":
    unittest.main(verbosity=2)
