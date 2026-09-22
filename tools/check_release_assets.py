"""核对 `dist/` 下**所有**已归档版本的发布资产（判据 2：三方一致）。

用法：

    python tools/check_release_assets.py
    python tools/tests/test_check_release_assets.py    # 本脚本自己的判据测试（零依赖）

## 为什么要有这个脚本，而不是文档里那段 heredoc

两件事都是实测踩出来的：

1. **Git Bash 会把「`:` 紧跟 `\\`」当成盘符路径**（`C:\\`），把那个反斜杠换成 `/`。
   所以 `python - <<'PY'` / `python -c` 里的正则 `pg_map_id:\\s*(...)` 传到 Python
   手上会变成 `pg_map_id:/s*(...)` —— **匹配不到**，于是每一版都报 MISMATCH。
   而「MISMATCH」这句话把人引向「你的归档坏了」，真因却在命令行转义上
   （实测：`python -c "print(repr(r'x:\\s'))"` 打出 `'x:/s'`）。
   ⇒ 落成文件跑，正则写成什么就是什么。

2. **原来那份写死了 `for v in ('1.0', '1.1', '1.2')`** —— 发到 1.4 之后它就再也
   核不到 1.3 / 1.4，而且**没有任何东西会提醒你**（和「README 里写死的体积数字」
   同一个毛病，§91⑧）。
   ⇒ 这里改成扫 `dist/patchbay-*`，发多少版就核多少版。

## 判据

APK 的 DEX 里嵌着 `r8-map-id-<hash>`、mapping 里有 `# pg_map_id: <hash>`、
`map-id.txt` 里写着同一个 —— 三者逐字节相同。`map-id` 是「用户发来的堆栈属于
哪一版」唯一的线索，任何一方对不上，`--find-map-id` 就会指错版本、拿错 mapping
去 retrace（§93）。

顺带核一件不需要读内容的：**目录名与 APK 文件名要相符**（`dist/patchbay-1.3/`
里该是 `patchbay-1.3.apk`）。目录是归档脚本建的、文件名也是它写的，两边对不上
说明中间有人动过手。

正则和「只读 mapping 前 4 KB」这件事**复用 `archive_release`** —— 同一份判据
只留一处实现，不然两边会各自漂移（§111.9）。这也是这次出问题的根源：文档里
那份是抄来的副本，副本漂了没人知道。

## sha256 只打印，不算通过

判据 1 是「线上附件与本地归档逐字节相同」，脚本只拿得到本地那一半 ——
另一半要用 `gh api ... --jq '.assets[].digest'` 从线上取（§93）。所以这里把本地
sha256 打出来供对照，**不把它算进退出码**：它自己红不了。
"""
import sys
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
# 从别处 import 本模块时 sys.path[0] 不是这里，显式加一下
sys.path.insert(0, str(TOOLS_DIR))

import archive_release as ar  # noqa: E402  —— 必须先改 sys.path 才能 import

ROOT = TOOLS_DIR.parent
DIST = ROOT / "dist"


def check_version_dir(d: Path) -> list[str]:
    """核一个 `dist/patchbay-<v>/`，返回问题列表（空列表 = 通过）。

    只读文件，不改任何东西 —— 所以测试里可以对着临时目录随便跑。
    """
    problems: list[str] = []
    version = d.name.removeprefix("patchbay-")

    apks = sorted(d.glob("*.apk"))
    mapping = d / "mapping.txt"
    map_id_file = d / "map-id.txt"

    if not apks:
        problems.append("目录里没有 .apk")
    elif len(apks) > 1:
        problems.append(f"目录里有 {len(apks)} 个 .apk，不知道该核哪个：{[p.name for p in apks]}")
    elif apks[0].name != f"patchbay-{version}.apk":
        # 名字不符时**不**顺手去核它 —— 那是在替一个来路不明的文件背书
        problems.append(
            f"APK 文件名与目录名不符：目录是 {d.name}/，里面却是 {apks[0].name}"
        )

    missing = [p.name for p in (mapping, map_id_file) if not p.is_file()]
    if missing:
        problems.append(f"缺文件：{'、'.join(missing)}")

    if problems:
        return problems

    apk = apks[0]
    ids = ar.dex_map_ids(apk)
    if not ids:
        problems.append("APK 的 DEX 里没有 r8-map-id —— 这份 APK 没经过 R8，mapping 对它没意义")
    elif len(ids) > 1:
        problems.append(f"APK 里有 {len(ids)} 个不同的 map-id，无法判断该用哪个：{sorted(ids)}")

    pg = ar.mapping_map_id(mapping)
    if pg is None:
        problems.append("mapping 里没有 `# pg_map_id:` 行 —— 这不是 R8 产出的 mapping？")

    txt = map_id_file.read_text(encoding="utf-8").strip()

    if not problems:
        apk_id = next(iter(ids))
        if not (pg == apk_id == txt):
            problems.append(
                "三方不一致（`--find-map-id` 会指错版本）：\n"
                f"      APK 里的 map-id       {apk_id}\n"
                f"      mapping 里的 pg_map_id {pg}\n"
                f"      map-id.txt            {txt}"
            )

    return problems


def main(dist: Path = DIST) -> int:
    """核 `dist` 下所有版本，返回退出码（0 = 全过）。

    `dist` 可以传 —— 测试要对着临时目录跑，不能碰真归档。
    """
    dirs = sorted(p for p in dist.glob("patchbay-*") if p.is_dir())
    if not dirs:
        print(
            f"✗ {dist} 下没有 patchbay-* 目录 —— 先归档一版（python tools/archive_release.py）",
            file=sys.stderr,
        )
        return 1

    failed = 0
    for d in dirs:
        apks = sorted(d.glob("*.apk"))
        mapping = d / "mapping.txt"

        print(f"=== {d.name} ===")
        if len(apks) == 1:
            print(f"  APK      {apks[0].name}  {apks[0].stat().st_size:,} B")
            print(f"           sha256 {ar.sha256(apks[0])}")
        if mapping.is_file():
            print(f"  mapping  mapping.txt  {mapping.stat().st_size:,} B")
            print(f"           sha256 {ar.sha256(mapping)}")

        problems = check_version_dir(d)
        if problems:
            failed += 1
            for p in problems:
                # 多行的问题缩进对齐（三方不一致那条自己带了缩进）
                print(f"  ✗ {p}")
        else:
            txt = (d / "map-id.txt").read_text(encoding="utf-8").strip()
            print(f"  map-id   {txt}")
            print("  ✓ 三方一致")
        print()

    passed = len(dirs) - failed
    print(f"=== 汇总 ===  {len(dirs)} 个版本，{passed} 个通过" + (f"，{failed} 个有问题" if failed else ""))
    if failed:
        print("\n有问题的版本说明 `map-id.txt` / mapping / APK 三者对不上 ——")
        print("`--find-map-id` 会指错版本。修法：拿对应那一版的源码重新 assembleRelease + 归档。")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
