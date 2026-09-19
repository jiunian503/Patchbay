#!/usr/bin/env python3
"""真机 UI 探针 —— 把 uiautomator 的 XML 变成一张「能点的东西」清单。

## 为什么需要它

`adb shell input tap` 需要的是**真实像素**坐标，而截图在工具里显示时是缩小过的
（1080 宽的屏显示成 607 宽，比例 1.7792）。拿肉眼量截图坐标去点，会算偏，
而且**点偏了不报错** —— 只是输入框没拿到焦点，看起来像「应用没反应」。
用 uiautomator 拿到的是设备自己报的 bounds，没有换算误差。

## 用法

    python tools/ui.py dump              # 导出 + 打印所有可点/有文字的节点
    python tools/ui.py dump -b           # 同上，但多打印 bounds
    python tools/ui.py find 新建对话      # 只找匹配的节点，打印坐标
    python tools/ui.py tap 新建对话       # 找到并点击（等价于 find + input tap）
    python tools/ui.py tapx 79 1593      # 已知坐标直接点，不跑 uiautomator

`dump` 会覆盖 /sdcard/dump.xml。注意：**uiautomator dump 可能触发 Activity 重建**，
如果应用的导航状态没做 rememberSaveable，栈会回到首页。所以别做
「tap → dump → tap」连招；每一步之间都重新 dump 一次再定位。

## 关键字匹配按「有多像」排序，不是按文档顺序

`find` / `tap` 会先把命中的节点排序：**文字完全相等的最前，其次可点的，
再次文字短的**。这不是锦上添花，是必须的 —— 实测 `tap 安装` 命中了校验提示
「清单检查通过，可以安装」，点在了一段说明文字上，安装按钮根本没被按到，
而且**不报错**，看起来就像「安装按钮没反应」。

所以想点哪个按钮，尽量把它的文字写全（`tap 安装插件` 而不是 `tap 安装`）。
点不到时先看 `find` 打印出来的候选列表，排序后的第一个才是会被点的那个。

## 三个实测出来的坑

1. **`dump` 要 2.4 秒**（1080x1920 / Compose 界面实测）。所以它**看不见短命状态** ——
   比如「复制」按钮点完变「已复制」、2 秒后自己变回去，dump 永远只能抓到「复制」。
   要观察这类状态得用 `exec-out screencap -p`（约 0.3 秒）。曾经因此误判
   「按钮点了没反应」，白查一轮。

2. **`dump` 只能给到整个段落**。Compose 里一个 `Text` 里的行内 span（链接、粗体）
   没有独立的 accessibility 节点。想点中段落里某个链接，要么让测试内容
   「一行一个链接、贴左边缘」，要么按 bounds + 字号自己算偏移。

3. **uiautomator 的属性值可能用单引号包裹**（值里含 `"` 时，例如输入框里是一份
   JSON）。早先只认双引号，于是那种节点的文字被读成空串，看起来像
   「界面上明明有字、dump 说没有」。细节见 `attr()`。

4. **禁用的按钮照样在树里，`clickable` 还是 true**，只是 `enabled=false`。
   所以 `dump` 会把它标成「可点但禁用」—— 看到这个就别再点，先想为什么它是禁用的。
   不区分的话，「点了没反应」很容易被误判成「坐标算错了」。
"""

import re
import subprocess
import sys

ADB = r"C:/Users/nian/Android/Sdk/platform-tools/adb.exe"
DEVICE = "127.0.0.1:7555"
REMOTE = "/sdcard/dump.xml"


def adb(*args, binary=False):
    cmd = [ADB, "-s", DEVICE, *args]
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0 and not binary:
        sys.stderr.write(r.stderr.decode("utf-8", "replace"))
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def ensure_connected():
    adb("connect", DEVICE)


def dump_xml():
    ensure_connected()
    adb("shell", "uiautomator", "dump", REMOTE)
    # 用 exec-out 而不是 shell cat：shell 会做换行转换，把 XML 弄坏
    return adb("exec-out", "cat", REMOTE, binary=True).decode("utf-8", "replace")


NODE = re.compile(r"<node\b[^>]*>")


def attr(tag, name):
    """读一个 XML 属性。

    **属性值不一定是双引号包裹的。** uiautomator 写 XML 时会按内容挑引号：
    值里含 `"` 就改用单引号 —— 实测一个 Compose 多行输入框（内容是 JSON）被写成

        text='{&#10;  "id": "pub.fromurl", ...}'

    早先这里只认双引号，于是那个节点被当成「没有文字」，`dump` 显示成空串。
    单行的 URL 读得出来，纯粹因为它里面没有 `"`，掩盖了这个 bug ——
    看到「界面明明有字、dump 说没有」时先怀疑这里。
    """
    m = re.search(rf"""\b{name}=("[^"]*"|'[^']*')""", tag)
    if not m:
        return None
    return unescape(m.group(1)[1:-1])


# XML 实体。`&#10;` 是换行 —— 不清掉的话多行输入框的内容会显示成一整坨。
ENTITY = re.compile(r"&(#(\d+)|#x([0-9a-fA-F]+)|lt|gt|amp|quot|apos);")

NAMED = {"lt": "<", "gt": ">", "amp": "&", "quot": '"', "apos": "'"}


def unescape(raw):
    def sub(m):
        if m.group(2):
            return chr(int(m.group(2)))
        if m.group(3):
            return chr(int(m.group(3), 16))
        return NAMED[m.group(1)]

    return ENTITY.sub(sub, raw)


def nodes(xml):
    """产出 (文字, 类名, bounds, 中心点, 可点, 可编辑)。"""
    out = []
    for tag in NODE.findall(xml):
        bounds = attr(tag, "bounds")
        if not bounds:
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append(
            {
                "text": attr(tag, "text") or "",
                "desc": attr(tag, "content-desc") or "",
                "cls": (attr(tag, "class") or "").split(".")[-1],
                "bounds": (x1, y1, x2, y2),
                "center": ((x1 + x2) // 2, (y1 + y2) // 2),
                "clickable": attr(tag, "clickable") == "true",
                # 禁用的按钮**照样在树里**、`clickable` 也可能是 true，
                # 只是 `enabled=false`。不区分的话，「点了没反应」会被误判成
                # 「坐标算错了」，然后去查一个根本不存在的问题。
                "enabled": attr(tag, "enabled") != "false",
                "editable": attr(tag, "class") == "android.widget.EditText",
            }
        )
    return out


def flatten(label, limit=72):
    """把多行文本压成一行显示 —— 只影响打印，匹配用的还是原文。

    多行输入框（比如粘了一整份 JSON 清单）不压平的话，一次 `dump`
    会刷出几十行，把同一屏上别的元素全顶出视野。
    """
    one = "⏎".join(label.splitlines())
    return one if len(one) <= limit else one[:limit] + "…"


def show(items, with_bounds=False):
    for n in items:
        label = n["text"] or (f'({n["desc"]})' if n["desc"] else "")
        flags = []
        if n["clickable"]:
            flags.append("可点" if n["enabled"] else "可点但禁用")
        if n["editable"]:
            flags.append("可输入")
        box = ""
        if with_bounds:
            x1, y1, x2, y2 = n["bounds"]
            box = f"[{x1},{y1}][{x2},{y2}]".ljust(22)
        print(
            f'{flatten(label)!r:34} {n["cls"]:14} {box}{str(n["center"]):12} '
            f'{" ".join(flags) if flags else "-"}'
        )


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    flags = {a for a in sys.argv[1:] if a.startswith("-")}
    with_bounds = "-b" in flags or "--bounds" in flags

    mode = args[0] if args else "dump"
    needle = args[1] if len(args) > 1 else None

    # tapx 不走 uiautomator —— 它是「我已经知道坐标，只想点一下」
    if mode == "tapx":
        x, y = int(args[1]), int(args[2])
        ensure_connected()
        adb("shell", "input", "tap", str(x), str(y))
        print(f"已点击 ({x}, {y})")
        return

    xml = dump_xml()
    items = nodes(xml)

    if mode == "dump":
        # 只显示「有文字」或「可点」的，滤掉一堆无意义的容器
        show(
            [n for n in items if n["text"] or n["desc"] or n["clickable"] or n["editable"]],
            with_bounds,
        )
        return

    if needle is None:
        print("需要一个关键字，例如： python tools/ui.py find 新建对话", file=sys.stderr)
        sys.exit(2)

    hits = [
        n for n in items
        if needle in n["text"] or needle in n["desc"]
    ]
    if not hits:
        print(f"没找到 {needle!r}。当前界面上的文字：")
        show([n for n in items if n["text"] or n["desc"]], with_bounds)
        sys.exit(1)

    # 按「有多像目标」排序，`tap` 才会点到按钮而不是碰巧含这个词的说明文案。
    # 实测踩过：`tap 安装` 命中了校验提示「清单检查通过，可以安装」，
    # 点在了文字上，安装按钮根本没被按到 —— 而且不报错，看起来像「安装没反应」。
    def rank(n):
        label = n["text"] or n["desc"]
        return (
            0 if label == needle else 1,          # 完全相等优先
            0 if n["clickable"] else 1,           # 可点的优先
            len(label),                           # 短的优先（越短越像标签）
        )

    hits.sort(key=rank)
    show(hits, with_bounds)

    if mode == "tap":
        x, y = hits[0]["center"]
        ensure_connected()
        adb("shell", "input", "tap", str(x), str(y))
        print(f"已点击 {hits[0]['text'] or hits[0]['desc']!r} @ ({x}, {y})")


if __name__ == "__main__":
    main()
