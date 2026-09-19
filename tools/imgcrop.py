"""把截图裁一块并放大 —— 用来核对圆角、间距这类「整体看不出、放大才明显」的细节。

用法：
    python tools/imgcrop.py in.png out.png --box 0,120,560,340 [--scale 2]
    python tools/imgcrop.py in.png            # 只打印尺寸，不裁

`--box` 是 x,y,w,h（像素，左上角原点）。`--scale` 默认 2（最近邻放大，
不做平滑 —— 平滑会把圆角的锯齿抹掉，反而看不清是真圆角还是假圆角）。

为什么要有这个：`uiautomator dump` 拿得到 `bounds`，但拿不到**圆角**；
截图能看出圆角，可 1080 宽的整图缩到对话框里就只剩几个像素。
两个凑一起才够用：dump 拿坐标，crop 拿形状。
"""
import argparse
import sys

from PIL import Image


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst", nargs="?")
    ap.add_argument("--box", help="x,y,w,h")
    ap.add_argument("--scale", type=int, default=2)
    a = ap.parse_args()

    im = Image.open(a.src)
    print(f"{a.src}: {im.size[0]}x{im.size[1]}")
    if not a.dst:
        return 0

    if a.box:
        x, y, w, h = (int(v) for v in a.box.split(","))
    else:
        x, y, w, h = 0, 0, im.size[0], im.size[1]
    x = max(0, min(x, im.size[0]))
    y = max(0, min(y, im.size[1]))
    w = min(w, im.size[0] - x)
    h = min(h, im.size[1] - y)

    # 裁框超出图片时**明确报错**，别把空图交给 resize。
    # 这个分支是实战踩出来的：模拟器会跟着重力感应自己转屏，上一张还是
    # 1080x1920、这一张就变成 1920x1080，于是按竖屏算的 y=1780 直接超界，
    # 报出来的是 `ValueError: height and width must be > 0` —— 看着像 PIL 的
    # 毛病，其实是「方向变了」。所以这里把两边尺寸都打出来。
    if w <= 0 or h <= 0:
        print(
            f"!! 裁框超界：图是 {im.size[0]}x{im.size[1]}，框是 {a.box}\n"
            f"   （屏幕方向变了？竖屏 1080x1920 / 横屏 1920x1080）",
            file=sys.stderr,
        )
        return 2

    im = im.crop((x, y, x + w, y + h))
    if a.scale != 1:
        im = im.resize((im.size[0] * a.scale, im.size[1] * a.scale), Image.NEAREST)
    im.save(a.dst)
    print(f"-> {a.dst} ({im.size[0]}x{im.size[1]})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
