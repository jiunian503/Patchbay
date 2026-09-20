"""把一次 release 的产物收成一份可归档的包 —— 核心是**证明 APK 和 mapping 配套**。

用法：
    python tools/archive_release.py                  # 归档到 dist/patchbay-<version>/
    python tools/archive_release.py --check-only     # 只验配套，不落盘
    python tools/archive_release.py --force          # 目录已存在时覆盖
    python tools/archive_release.py --find-map-id <64 位 hash>
                                                     # 反查：这条堆栈属于哪一版

## 为什么要有这个脚本

混淆后的崩溃堆栈只有配对的 `mapping.txt` 能还原（§73），而 mapping
**只对产出它的那一版 APK 有效**。更麻烦的是：两者不配套时 `retrace`
**不报错**，会给一份行号错误但看着完全合理的结果。所以「配套」这件事
必须机械地验，不能靠记性。

配对判据（§73 三）：

    APK 的 DEX 里嵌着   r8-map-id-<hash>
    mapping.txt 里有      # pg_map_id: <hash>

两个 hash 必须逐字节相同。而用户发来的堆栈里带的就是前者那个 hash ——
所以 `--find-map-id` 能反查出「该用哪一份 mapping」。

## 归档里有什么

    dist/patchbay-<version>/
        patchbay-<version>.apk   会发出去的那一份（已签名的优先）
        mapping.txt              只对这一份 APK 有效
        map-id.txt               一行 hash，给 --find-map-id 用
        README.txt               版本 / 时间 / commit / sha256 / 还原命令

`dist/` 在 .gitignore 里 —— 每份约 45 MB，主体是那个 mapping，该传到
Release 附件，不该进 git。本机留一份只是为了随时能 retrace。
"""
import argparse
import hashlib
import re
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime, timezone, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RELEASE_APK_DIR = ROOT / "app" / "build" / "outputs" / "apk" / "release"
MAPPING = ROOT / "app" / "build" / "outputs" / "mapping" / "release" / "mapping.txt"
GRADLE_FILE = ROOT / "app" / "build.gradle.kts"

# 只扫前 4 KB —— `# pg_map_id:` 在 R8 输出的第 6 行，后面是几十万行类映射。
# 整个文件 42 MB，读进来只为了找一行是浪费。
MAPPING_HEAD_BYTES = 4096

MAP_ID_IN_DEX = re.compile(rb"r8-map-id-([0-9a-f]{64})")
PG_MAP_ID = re.compile(r"^#\s*pg_map_id:\s*([0-9a-f]{64})\s*$", re.MULTILINE)


def fail(msg: str) -> None:
    print(f"✗ {msg}", file=sys.stderr)
    sys.exit(1)


def pick_apk() -> Path:
    """挑要归档的 APK。

    已签名的优先 —— 归档要归的是「会发出去的那一份」，不是中间产物。
    配好 `keystore.properties` 之后 AGP 直接产出 `app-release.apk`；
    没配就是 `app-release-unsigned.apk`（那也照归，但会警告）。
    """
    for name in ("app-release.apk", "app-release-signed.apk", "app-release-unsigned.apk"):
        p = RELEASE_APK_DIR / name
        if p.exists():
            return p
    fail(f"在 {RELEASE_APK_DIR} 找不到 release APK —— 先跑 :app:assembleRelease")


def dex_map_ids(apk: Path) -> set[str]:
    """APK 里所有 DEX 中出现的 `r8-map-id-<hash>`。"""
    with zipfile.ZipFile(apk) as z:
        blob = b"".join(z.read(n) for n in z.namelist() if n.endswith(".dex"))
    return {m.decode() for m in MAP_ID_IN_DEX.findall(blob)}


def mapping_map_id(mapping: Path) -> str | None:
    with mapping.open("r", encoding="utf-8", errors="replace") as f:
        head = f.read(MAPPING_HEAD_BYTES)
    m = PG_MAP_ID.search(head)
    return m.group(1) if m else None


def verify(apk: Path, mapping: Path) -> str:
    """验 APK 与 mapping 配套，返回那个 hash。不配套就退出。"""
    if not mapping.exists():
        fail(f"找不到 {mapping} —— 没开混淆的构建不会产出它")

    ids = dex_map_ids(apk)
    if not ids:
        fail(
            f"{apk.name} 的 DEX 里没有 r8-map-id —— 这份 APK 没经过 R8，"
            "mapping.txt 对它没有意义"
        )
    if len(ids) > 1:
        # 正常只有一份 mapping，所以只有一个 id。多于一个说明打包路径出了
        # 意料之外的事（比如混进了别的构建的 dex），这时**不能**随便挑一个用。
        fail(f"{apk.name} 里有 {len(ids)} 个不同的 map-id，无法判断该用哪个：{sorted(ids)}")

    apk_id = ids.pop()
    map_id = mapping_map_id(mapping)
    if map_id is None:
        fail(f"{mapping.name} 里没有 `# pg_map_id:` 行 —— 这不是 R8 产出的 mapping？")
    if apk_id != map_id:
        fail(
            "APK 和 mapping **不配套** —— 归档它们等于存一份读不懂的堆栈。\n"
            f"    APK 里的 map-id      {apk_id}\n"
            f"    mapping 里的 pg_map_id {map_id}\n"
            "  最可能的原因：APK 是上一次构建的，mapping 是这一次的（或反过来）。\n"
            "  修法：把 :app:assembleRelease 重跑一遍，让两个产物来自同一次构建。"
        )
    return apk_id


def gradle_version() -> tuple[str, str]:
    """从 app/build.gradle.kts 读 versionCode / versionName。"""
    text = GRADLE_FILE.read_text(encoding="utf-8")
    code = re.search(r"^\s*versionCode\s*=\s*(\d+)", text, re.MULTILINE)
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', text, re.MULTILINE)
    return (code.group(1) if code else "0", name.group(1) if name else "unknown")


def git_state() -> tuple[str, bool]:
    """(短 hash, 有没有未提交改动)。

    未提交改动要写进 README：一份 mapping 配一份「和源码对不上」的 APK
    是能查的，但得先知道对不上。
    """
    def run(*args: str) -> str:
        return subprocess.run(
            ["git", *args], cwd=ROOT, capture_output=True, text=True, check=False
        ).stdout.strip()

    rev = run("rev-parse", "--short", "HEAD") or "（不是 git 仓库）"
    dirty = bool(run("status", "--porcelain"))
    return rev, dirty


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def archive(apk: Path, mapping: Path, map_id: str, out_root: Path, force: bool) -> Path:
    code, name = gradle_version()
    dest = out_root / f"patchbay-{name}"
    if dest.exists():
        if not force:
            fail(f"{dest} 已存在 —— 确认要覆盖就加 --force（覆盖会丢掉上一份归档）")
        shutil.rmtree(dest)
    dest.mkdir(parents=True)

    apk_dest = dest / f"patchbay-{name}.apk"
    shutil.copy2(apk, apk_dest)
    shutil.copy2(mapping, dest / "mapping.txt")
    (dest / "map-id.txt").write_text(map_id + "\n", encoding="utf-8")

    rev, dirty = git_state()
    now = datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S %z")
    unsigned = "unsigned" in apk.name

    lines = [
        "Patchbay 发布归档",
        "=" * 40,
        "",
        f"版本        {name} ({code})",
        f"归档时间    {now}",
        f"源码        {rev}" + ("  ⚠️ 有未提交改动" if dirty else "（工作区干净）"),
        f"APK         {apk_dest.name}",
        f"            {apk_dest.stat().st_size:,} B",
        f"            sha256 {sha256(apk_dest)}",
        f"map-id      {map_id}",
        "",
    ]
    if unsigned:
        lines += [
            "⚠️ 这是 **unsigned** 包，不能直接发给用户。",
            "   配好 keystore.properties 后 AGP 会产出 app-release.apk（已签名），",
            "   再跑一次这个脚本归档那一份。",
            "",
        ]
    lines += [
        "这份 mapping.txt 只对上面对那个 APK 有效。",
        "用户发来的堆栈里带着 `r8-map-id-<hash>` —— 和上面的 map-id 相同，才说明配套。",
        "",
        "还原一条堆栈（§73 四）：",
        "",
        '    "$ANDROID_HOME/cmdline-tools/latest/bin/retrace.bat" mapping.txt crash.txt',
        "",
        "反查某条堆栈属于哪一版：",
        "",
        "    python tools/archive_release.py --find-map-id <hash>",
        "",
        "⚠️ 这个目录丢了，那一版的线上崩溃就永远读不懂了。上传到 GitHub Release",
        "   的附件里（或另存一处），别只留在本机。",
        "",
    ]
    (dest / "README.txt").write_text("\n".join(lines), encoding="utf-8")
    return dest


def find_by_map_id(out_root: Path, wanted: str) -> int:
    wanted = wanted.strip().lower()
    hits = []
    if out_root.exists():
        for d in sorted(out_root.iterdir()):
            f = d / "map-id.txt"
            if f.is_file() and f.read_text(encoding="utf-8").strip().lower() == wanted:
                hits.append(d)
    if not hits:
        print(f"dist/ 里没有 map-id 为 {wanted} 的归档。", file=sys.stderr)
        print("要么那一版没归档，要么 hash 抄错了（64 位十六进制）。", file=sys.stderr)
        return 1
    for d in hits:
        print(d)
        readme = d / "README.txt"
        if readme.is_file():
            for line in readme.read_text(encoding="utf-8").splitlines():
                if line.startswith(("版本", "源码", "APK ")):
                    print(f"    {line}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--out", default=str(ROOT / "dist"), help="输出根目录（默认 dist/）")
    ap.add_argument("--check-only", action="store_true", help="只验配套，不落盘")
    ap.add_argument("--force", action="store_true", help="目标目录已存在时覆盖")
    ap.add_argument("--find-map-id", metavar="HASH", help="反查这个 map-id 属于哪一版")
    # 两个覆盖参数是为了**复验已有的归档**：「这一对还配套吗？」
    # 没有它们就只能去动 build/ 下的产物，而那是下一次构建会覆盖的地方。
    ap.add_argument("--apk", help="指定 APK（默认从 app/build/outputs/apk/release 里挑）")
    ap.add_argument("--mapping", help="指定 mapping.txt（默认 build 下的那一份）")
    a = ap.parse_args()

    out_root = Path(a.out)

    if a.find_map_id:
        return find_by_map_id(out_root, a.find_map_id)

    apk = Path(a.apk) if a.apk else pick_apk()
    mapping = Path(a.mapping) if a.mapping else MAPPING
    if not apk.exists():
        fail(f"找不到 {apk}")
    map_id = verify(apk, mapping)
    print(f"✓ 配套：{apk.name} ↔ {mapping.name}")
    print(f"  map-id {map_id}")

    if a.check_only:
        return 0

    dest = archive(apk, mapping, map_id, out_root, a.force)
    print(f"✓ 归档到 {dest}")
    for f in sorted(dest.iterdir()):
        print(f"    {f.name:32} {f.stat().st_size:>12,} B")
    return 0


if __name__ == "__main__":
    sys.exit(main())
