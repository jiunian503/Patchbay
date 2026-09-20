#!/usr/bin/env python3
"""读 APK 里每个 `.so` 的 ELF program header，报出每个 PT_LOAD 段的 `p_align`。

## 为什么要它

16 KB 内存页那条判据（SKILL.md §88 六）依赖的是**每个库每个 LOAD 段的 `p_align`**，
而不是「这个库是 4 KB 还是 16 KB」。手工一个个看**已经错过一次**：

release 包里其实是 **3** 个库 × 2 个 ABI（`libandroidx.graphics.path.so` 来自
Compose 的传递依赖，和 QuickJS 无关），当时只查了 QuickJS 那两个，于是
「8 个 `.so` 全是 4 KB 对齐」这句话既数错了总数（12 个）、又漏了一个库 ——
而漏掉的那个恰好是**唯一 16 KB 对齐的**（androidx 早就改过了）。

## 为什么要自己解 ELF

`pyelftools` 是个依赖，而读 ELF 在这个项目里是偶发动作（改依赖、升 AAR 时才跑）。
Google 官方那个 `check_elf_alignment.sh` 要 `unzip` + shell，跨平台不顺手。
这个文件零依赖、直接吃 APK。

## 用法

    python tools/elf_align.py app/build/outputs/apk/release/app-release.apk
    python tools/elf_align.py <某个 .so 的路径>

## 怎么读结果

- **最小 `p_align` ≥ `0x4000`** 才算 16 KB 兼容。`0x1000` 就是只有 4 KB
- `p_align` 是**编译期定死**的，改不了（要改只能重编那个库）
- 顺带检查 `p_offset ≡ p_vaddr (mod 0x4000)`：不成立意味着重定位后指向错的页，
  链接器会在运行期 segfault —— 这是 Google 文档里 RELRO 段那条
"""

import struct
import sys
import zipfile

PT_LOAD = 1


def phdrs(blob):
    """返回 [(p_type, p_offset, p_vaddr, p_filesz, p_memsz, p_align), …]。

    自己解 ELF，不依赖 pyelftools —— 见文件头「为什么要自己解 ELF」。
    """
    if blob[:4] != b"\x7fELF":
        raise ValueError("不是 ELF")
    is64 = blob[4] == 2
    little = blob[5] == 1
    end = "<" if little else ">"
    if is64:
        (e_phoff,) = struct.unpack_from(end + "Q", blob, 0x20)
        e_phentsize, e_phnum = struct.unpack_from(end + "HH", blob, 0x36)
        fmt = end + "IIQQQQQQ"
    else:
        (e_phoff,) = struct.unpack_from(end + "I", blob, 0x1C)
        e_phentsize, e_phnum = struct.unpack_from(end + "HH", blob, 0x2A)
        fmt = end + "IIIIIIII"
    out = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        v = struct.unpack_from(fmt, blob, off)
        if is64:
            p_type, _flags, p_off, p_vaddr, _pa, p_filesz, p_memsz, p_align = v
        else:
            p_type, p_off, p_vaddr, _pa, p_filesz, p_memsz, _flags, p_align = v
        if p_type == PT_LOAD:
            out.append((p_type, p_off, p_vaddr, p_filesz, p_memsz, p_align))
    return out


def report(name, blob):
    try:
        loads = phdrs(blob)
    except ValueError as exc:
        print("%-46s  %s" % (name, exc))
        return
    worst = min((l[5] for l in loads), default=0)
    verdict = "16KB OK" if worst >= 0x4000 else "**只有 4KB**"
    print("%-46s  LOAD×%d  最小 p_align=0x%x  %s" % (name, len(loads), worst, verdict))
    for _t, p_off, p_vaddr, _fs, _ms, p_align in loads:
        # 16 KB 页的硬要求：偏移与虚拟地址必须同余，否则重定位后指向错的页
        congruent = None if p_align < 0x4000 else (p_off % 0x4000) == (p_vaddr % 0x4000)
        note = "" if congruent is None else ("  同余 OK" if congruent else "  **同余不成立**")
        print("      off=0x%-8x vaddr=0x%-8x align=0x%-6x%s" % (p_off, p_vaddr, p_align, note))


def main(argv=None):
    """`argv` 可注入（不传就用 `sys.argv[1:]`）—— 直接读 `sys.argv` 的话，
    测试就得去改全局状态，那比让这个参数存在贵得多。

    单个字符串也接受：`list("a.apk")` 会把它拆成 `['a', '.', 'p', …]`，
    然后报一个 `FileNotFoundError: 'a'` —— 那个报错完全看不出是「传错了类型」。
    """
    if argv is None:
        args = sys.argv[1:]
    elif isinstance(argv, str):
        args = [argv]
    else:
        args = list(argv)
    if not args:
        print(__doc__)
        return 1
    target = args[0]
    if target.lower().endswith(".apk"):
        with zipfile.ZipFile(target) as z:
            names = sorted(n for n in z.namelist() if n.startswith("lib/") and n.endswith(".so"))
            if not names:
                print("这个 APK 里没有 lib/**.so")
                return 1
            print("== %s ==" % target)
            for n in names:
                report(n, z.read(n))
    else:
        with open(target, "rb") as f:
            report(target, f.read())
    return 0


if __name__ == "__main__":
    sys.exit(main())
