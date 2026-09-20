"""把示例插件目录里的源文件嵌进它的 manifest.json —— 也就是「打包」。

用法：

    python tools/embed_example_files.py                 # 就地更新所有示例
    python tools/embed_example_files.py --dry-run       # 只打印会改什么，不落盘
    python tools/embed_example_files.py csvstat         # 只处理指定的几个

    python tools/tests/test_embed_example_files.py      # 本脚本自己的判据测试（零依赖）

## 为什么需要这一步

一个脚本插件要能被安装，就得是**一份文档** —— 源码嵌在清单顶层的 `files` 里
（理由见 `PluginManifest.files`）：这个 App 没有插件商店，装插件只有
「粘贴一段 JSON」这一条路，源码放在另一个文件里那条路当场就断了。

但 `plugin/examples/` 是插件作者照着抄的参考版，参考版里的 JS 必须是**能读的**。
把 156 行 JS 塞进 JSON 字符串之后，它就只剩一行转义文本了。所以示例目录里
两份都留着：

    csvstat/index.js        源文件 —— 人读的、人改的，**源头**
    csvstat/manifest.json   清单 —— 里面嵌一份副本，**可安装的产物**

两份就必须有人守着。这个脚本负责生成，而 `ExampleManifestsTest` 里那条
「示例自带的源码和旁边的源文件逐字节一致」负责在跑测试时发现漂移 ——
它会告诉你回来跑这个脚本。

这和管理 `assets/plugins/` 与 `plugin/examples/` 的关系是同一个问题、同一个答案：
**生成的东西可以有两份，但必须有一条机械的判据把两边钉在一起。**

## 嵌哪些文件

示例目录下**除 `manifest.json` 之外的全部普通文件**，递归，路径用 `/` 分隔，
以 `.` 开头的文件和目录不算包内容（和 `.gitignore` 同一个直觉）。

刻意不做成「只嵌 `entry` 指向的那一个」：多文件插件是正常写法（清单的路径规则
明确允许子目录），而「哪些是需要的」要靠解析 JS 里的 `require` 才知道 ——
那是给打包器加一个 JS 解析器，不值。目录里放了什么就打包什么，
规则简单到不会猜错。

**推论：别在示例目录里放 `README.md`。** 它会被当成插件自带的文件打进包里
（无害，但会让示例的 `files` 里多一份没人读的东西）。要写说明就写在
`index.js` 顶部的注释里 —— 那份是给人读的，也是被打包的那一份。
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXAMPLES = ROOT / "plugin" / "examples"

MANIFEST = "manifest.json"


def source_files(directory: Path) -> dict[str, str]:
    """目录下除 `manifest.json` 外的全部普通文件，按路径排序。

    ## 为什么读字节再解码，而不是 `read_text()`

    `read_text()` 在 Windows 上会把 `\\n` 翻成 `\\r\\n`，于是嵌进 JSON 的内容
    和磁盘上的字节**不一样**，而那条逐字节的守卫测试会红 —— 红得还很有误导性，
    看起来像「清单过期了」，其实只是读法不对。

    真实踩过一次的地方：`tools/tests/test_archive_release.py` 的夹具注释里
    记着同一件事（§60④ 判据 6）。
    """
    out: dict[str, str] = {}
    for path in sorted(directory.rglob("*")):
        rel = path.relative_to(directory).as_posix()
        if any(part.startswith(".") for part in path.relative_to(directory).parts):
            continue
        if not path.is_file() or rel == MANIFEST:
            continue
        out[rel] = path.read_bytes().decode("utf-8")
    return out


def repack(directory: Path) -> tuple[str, str] | None:
    """返回 `(旧文本, 新文本)`；不需要改动时返回 `None`。

    不需要改动有两种：这个示例不是 `script` 形态（它不该有 `files`），
    或者嵌进去的内容已经和磁盘上的一致。
    """
    manifest_path = directory / MANIFEST
    if not manifest_path.is_file():
        return None

    old = manifest_path.read_bytes().decode("utf-8")
    manifest = json.loads(old)
    if manifest.get("runtime") != "script":
        return None

    files = source_files(directory)
    if not files:
        raise SystemExit(
            f"{directory.name}: runtime 是 script，但目录下一个源文件都没有。"
            f"清单里的 entry.script.main 指向谁？"
        )
    if manifest.get("files") == files:
        return None

    manifest["files"] = files
    return old, json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="把示例插件的源文件嵌进 manifest.json")
    parser.add_argument("names", nargs="*", help="只处理这些示例目录（默认全部）")
    parser.add_argument("--dry-run", action="store_true", help="只打印会改什么，不落盘")
    args = parser.parse_args(argv)

    if not EXAMPLES.is_dir():
        raise SystemExit(f"找不到示例目录：{EXAMPLES}")

    targets = [d for d in sorted(EXAMPLES.iterdir()) if d.is_dir()]
    if args.names:
        wanted = set(args.names)
        missing = wanted - {d.name for d in targets}
        if missing:
            raise SystemExit(f"没有这些示例目录：{'、'.join(sorted(missing))}")
        targets = [d for d in targets if d.name in wanted]

    changed = 0
    for directory in targets:
        result = repack(directory)
        if result is None:
            print(f"  = {directory.name}（没变化）")
            continue

        old, new = result
        changed += 1
        if args.dry_run:
            print(f"  ~ {directory.name}（会更新，{len(old)} → {len(new)} 字节）")
        else:
            # 写字节而不是写文本：文本模式在 Windows 上会把 \n 写成 \r\n，
            # 而这个仓库要求 LF（§60）
            (directory / MANIFEST).write_bytes(new.encode("utf-8"))
            print(f"  + {directory.name}（{len(old)} → {len(new)} 字节）")

    suffix = "（--dry-run，未落盘）" if args.dry_run else ""
    print(f"{changed} 份需要更新{suffix}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
