"""量一个圆角矩形左上角的**半径**（像素），并折算成 dp。

用法：
    python tools/imgcorner.py in.png --box x,y,w,h

`--box` 的**左上角必须是纯背景**（通常取容器左上方一小片空白）。脚本以
那个像素为背景基准，逐行向右找容器的左轮廓，再数「从顶边到左轮廓开始
走直」的距离 —— 那就是圆角半径。

## 为什么需要它

8dp 与 10dp 在 MuMu 的 1.75x 下只差 3.5px，1080 宽的整图缩进对话框后连
1px 都不到。放大 5 倍能看出「两者不一样」，但说不出是 8 还是 10 —— 而
要核对的判据恰恰是「等于 shapes.small = 10dp」。目测只能给「像」。

## 为什么用「半强度」而不是「颜色差超过阈值」

第一版用的是 `|c - bg| > tol`，结果同一种圆角（都是 shapes.small）在
表格上量出 12px、在 chip 上量出 15px —— 因为表格表头色与背景只差 28，
chip 色与背景差得多，同一个 tol 卡在渐变带的不同位置上。

圆角边缘是**抗锯齿**的：容器色和背景色之间有一圈渐变。几何边界在
「50% 混合」处，所以要沿「背景色 → 容器色」这条颜色轴做投影，取
投影系数 > 0.5 的位置。这样**与两者颜色差多少无关**，不同元素之间才可比。

容器色不靠猜：取「已经过了圆角」的垂直直线段上、左边缘内 4px 的像素。

实测（第三十六轮，1920x1080 横屏 @1.75x；真值见 Shape.kt）：

    气泡（硬编码 18dp）              30.5px ≈ 17.4dp  ← 标定，测法本身准
    代码块（shapes.small = 10dp）    17.8px ≈ 10.2dp
    工具 chip（shapes.small = 10dp） 17.8px ≈ 10.2dp
    表格（shapes.small + 1dp 描边）  15.7px ≈  9.0dp

最后一行是关键：**有描边的容器，可见填充圆角 = token 值 − 描边宽**。
表格的 Surface 是「透明填充 + 1dp 描边」，填充的最外 1dp 被描边盖住，
所以量出来必然比代码块小一档。别据此以为改动没生效。

先量一个**已知真值**的元素做标定（上面那个气泡），再看要测的目标 ——
否则分不清「差 1dp」是改动没生效还是测法本身有偏。
"""
import argparse
import math
import sys

from PIL import Image

# MuMu 模拟器：density 280 → 1.75x
DENSITY = 1.75


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("--box", required=True, help="x,y,w,h（左上角须为纯背景）")
    a = ap.parse_args()

    im = Image.open(a.src).convert("RGB")
    x, y, w, h = (int(v) for v in a.box.split(","))
    x = max(0, min(x, im.size[0]))
    y = max(0, min(y, im.size[1]))
    w = min(w, im.size[0] - x)
    h = min(h, im.size[1] - y)
    if w <= 0 or h <= 0:
        print(
            f"!! 裁框超界：图是 {im.size[0]}x{im.size[1]}，框是 {a.box}\n"
            f"   （屏幕方向变了？竖屏 1080x1920 / 横屏 1920x1080）",
            file=sys.stderr,
        )
        return 2

    px = im.load()
    bg = px[x, y]

    # 先粗扫一遍拿到大概轮廓，用它取容器色
    def diff(c) -> int:
        return abs(c[0] - bg[0]) + abs(c[1] - bg[1]) + abs(c[2] - bg[2])

    coarse = {}
    for yy in range(y, y + h):
        for xx in range(x, x + w):
            if diff(px[xx, yy]) > 8:
                coarse[yy] = xx
                break
    if not coarse:
        print(f"!! 框里找不到容器（背景 RGB{bg}）—— 框选错了吧", file=sys.stderr)
        return 2

    top0 = min(coarse)
    xmin0 = min(coarse.values())
    probe_y = min(top0 + 25, y + h - 1)  # 25 > 最大圆角（10dp@1.75x = 17.5px）
    probe_x = min(xmin0 + 4, x + w - 1)
    cin = px[probe_x, probe_y]

    if diff(cin) <= 8:
        print(f"!! 取到的容器色和背景一样（都是 RGB{bg}）—— "
              f"要么容器和背景同色，要么框太小没过圆角", file=sys.stderr)
        return 2

    # 沿「背景 → 容器色」投影，0 = 纯背景，1 = 纯容器色
    den = sum((cin[i] - bg[i]) ** 2 for i in range(3))

    def t_at(c) -> float:
        num = sum((c[i] - bg[i]) * (cin[i] - bg[i]) for i in range(3))
        return num / den

    left = {}
    for yy in range(y, y + h):
        for xx in range(x, x + w):
            if t_at(px[xx, yy]) > 0.5:
                left[yy] = xx
                break
    if not left:
        print("!! 半强度阈值下找不到轮廓", file=sys.stderr)
        return 2

    rows = sorted(left)
    top0 = rows[0]
    xmin0 = min(left.values())

    # 轮廓点：dy 相对第一条有轮廓的行，off 相对最左轮廓。
    #
    # **丢掉第一行**：顶边那一行上下两侧都在抗锯齿带里，半强度判定必然偏内
    # ——实测代码块第一行 off=13，而 r=17.5 的理论值是 17.5。留着它会把
    # 拟合结果整体拉小 ~2px（第一版就是这么把 10dp 量成 8.7dp 的）。
    pts = [(yy - top0, left[yy] - xmin0) for yy in rows[1:]]

    # 对圆弧做最小二乘拟合，r 与 top 一起搜。
    #
    # 为什么不用「轮廓走直的那一行」当半径：圆弧末端的 offset 衰减很快
    # （r=17.5 时，距圆心 5px 处 offset 只剩 0.7px，3px 处只剩 0.26px），
    # 被像素量化一吸就提前「走直」，系统性低估约 3px。第一版就是这么把
    # 同一种圆角量成 12/13/14px 的。
    best = None
    for r10 in range(60, 401):  # 6.0 ~ 40.0 px
        r = r10 / 10
        for dt10 in range(-20, 21):  # 顶边微调 ±2px（抗锯齿会让它偏 1px 上下）
            dt = dt10 / 10
            err, n = 0.0, 0
            for dy, off in pts:
                u = dy - dt  # 相对几何顶边的 y
                if u < 0 or u > r:
                    continue
                pred = r - math.sqrt(r * r - (r - u) ** 2)
                err += (pred - off) ** 2
                n += 1
            if n >= 6:
                e = err / n
                if best is None or e < best[0]:
                    best = (e, r, dt, n)

    if best is None:
        print("!! 拟合失败：轮廓点太少 —— 框太小，或容器不是圆角矩形",
              file=sys.stderr)
        return 2

    rmse, r, dt, n = best
    print(f"背景 RGB{bg} · 容器色 RGB{cin} · 左边缘 x={xmin0} · 顶边 y={top0 + dt:.1f}")
    print(f"圆角半径 {r:.1f}px ≈ {r / DENSITY:.1f}dp  (@{DENSITY}x)"
          f"  · {n} 个轮廓点，rmse {math.sqrt(rmse):.2f}px")
    return 0


if __name__ == "__main__":
    sys.exit(main())
