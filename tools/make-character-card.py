#!/usr/bin/env python3
"""造一张带角色卡数据的 PNG —— 用来手测「从文件导入角色卡」。

## 为什么需要它

导入这条路**只有真机能验**：文件选择器（SAF）给的 `Uri`、`ContentResolver`
读出来的字节、最后落进 Room 的东西 —— 这三样在 JVM 单测里都不存在。
单测验的是解析器（喂字节进去、看对象出来），这个脚本造的是**喂进去的那份字节**。

## 为什么不直接存一张真卡当夹具

真卡是别人的作品，体积几 MB，而且塞进仓库之后没人说得清它到底测了什么。
这个脚本三十来行，一眼能看出生成的文件里有什么 —— 想让「卡里带开场白」
就去改 `CARD`，比去找一张恰好带开场白的真卡容易得多。

## 用法

    python tools/make-character-card.py artifacts/test-card.png
    python tools/make-character-card.py out.png --card my-card.json   # 用自己写的卡

⚠️ 输出文件名只用 ASCII：这个文件要 `adb push` 到设备上，中文文件名在
`adb shell` 那一侧会因为编码不一致而找不到。
"""

import argparse
import base64
import json
import struct
import sys
import zlib
from pathlib import Path

# 一张「什么都带一点」的示例卡，刻意把每一类字段都放进去：
#
# - `creator_notes`                                  → 界面简介
# - `description` / `personality` / `scenario` / `mes_example` → 人设的四个小节
# - `first_mes` / `alternate_greetings`              → 开场白与备用开场白（都装得下）
# - `post_history_instructions`                      → 应当出现在「没导进来」的提示里
# - 世界书里一条 `selective`、一条 `constant`、一条空条目 → 也该出现在提示里
#
# 也就是说：**用它手测，正常路径和所有警告一次都能看到。**
#
# 备用开场白刻意给**两条**：只给一条的话，「随机挑一条」和「永远说主开场白」
# 在设备上看起来完全一样 —— 验不出来。
CARD = {
    "spec": "chara_card_v2",
    "spec_version": "2.0",
    "data": {
        "name": "苏晚",
        "description": "住在茶山的少女，认得每一棵茶树。",
        "personality": "安静，话不多；但一说到茶就停不下来。",
        "scenario": "{{user}} 上山问路，误入她家的茶棚。",
        "mes_example": "<START>\n{{user}}: 这茶怎么卖？\n{{char}}: 不卖。坐下喝一杯再说。",
        "creator_notes": "照着小说《茶山》捏的，用于手测导入。",
        "first_mes": "你来啦。今年的雨前可采了。",
        "post_history_instructions": "回答保持简短。",
        "alternate_greetings": [
            "又见面了。",
            "（她把炉子拨旺）今天想喝什么？",
        ],
        "character_book": {
            "name": "茶山设定",
            "recursive_scanning": False,
            "entries": [
                {
                    "keys": ["茶", "tea"],
                    "content": "茶山的雨前茶只在清明前后采，过了时节就发苦。",
                    "enabled": True,
                    "insertion_order": 10,
                    "case_sensitive": False,
                },
                {
                    "keys": ["王叔"],
                    "content": "王叔是山下收茶的，每年都压价。",
                    "enabled": True,
                    "insertion_order": 20,
                    "selective": True,
                    "secondary_keys": ["压价"],
                },
                {
                    "keys": ["常驻"],
                    "content": "这条在原卡里是常驻的，不靠触发词。",
                    "enabled": True,
                    "insertion_order": 30,
                    "constant": True,
                },
                {"keys": [], "content": "没有触发词，应当被跳过。"},
            ],
        },
    },
}


def chunk(typ: bytes, data: bytes) -> bytes:
    """一个 PNG 块：长度 + 类型 + 数据 + CRC。

    这里的 CRC 是**真的算出来的**（不像单测夹具那样填 0）：造出来的文件要能被
    图片查看器和文件选择器认成一张正常 PNG —— 缩略图那一步走的就是它。
    """
    return (
        struct.pack(">I", len(data))
        + typ
        + data
        + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)
    )


def png_with_card(card: dict, size: int = 64, rgb=(90, 140, 90)) -> bytes:
    """一张 size×size 的纯色图，正文里嵌了 `chara` 块。"""
    # 纯色：够大，缩略图看得出来；又不至于让文件变大
    raw = b"".join(b"\x00" + bytes(rgb) * size for _ in range(size))

    out = b"\x89PNG\r\n\x1a\n"
    out += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
    out += chunk(b"IDAT", zlib.compress(raw, 9))

    # 卡数据放在 IDAT **之后**、IEND 之前 —— 和 SillyTavern 导出的位置一致。
    # （规范允许 tEXt 出现在 IHDR 之后的任何位置，放这儿只是为了「和真卡一样」）
    payload = json.dumps(card, ensure_ascii=False).encode("utf-8")
    out += chunk(b"tEXt", b"chara\x00" + base64.b64encode(payload))

    out += chunk(b"IEND", b"")
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="造一张带角色卡数据的 PNG")
    ap.add_argument("out", help="输出路径（文件名只用 ASCII）")
    ap.add_argument("--card", help="用这个 JSON 文件当卡数据（默认用内置的示例卡）")
    ap.add_argument("--size", type=int, default=64, help="图片边长，默认 64")
    args = ap.parse_args()

    card = json.loads(Path(args.card).read_text(encoding="utf-8")) if args.card else CARD
    data = png_with_card(card, size=args.size)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(data)

    # 报「卡名」而不是只报路径：一眼能确认造出来的是哪张卡
    name = (card.get("data") or card).get("name", "?")
    print(f"已写出 {out}（{len(data)} 字节）：卡名「{name}」")
    return 0


if __name__ == "__main__":
    sys.exit(main())
