package com.aichat.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角。
 *
 * ## 比 Material 默认小一档
 *
 * Material 3 默认是 4 / 8 / 12 / 16 / 28。那个 28 用在对话框和底部弹层上，
 * 配上一圈柔和的阴影，观感是「气泡、糖果」。
 *
 * 这个 App 借的是**设备面板**的语言：一块面板的角是收着的，不是鼓起来的。
 * 所以整体压一档到 6 / 10 / 14 / 18 / 22 —— 仍然是圆角，但克制。
 *
 * 另外原来各处的圆角是**硬编码**的（气泡 14dp、思考块 10dp、工具 chip 8dp），
 * 各写各的、以后必然漂移。现在统一走这里，改一处全变。
 *
 * ## 一个已知的落差：`small` 现在其实没人用
 *
 * 这里原本写着 `small = 按钮`，但那是**写了没生效** —— Material 3 的
 * `Button` / `FilledTonalButton` / `OutlinedButton` 用的是自己的
 * `ButtonDefaults.shape`（**胶囊形**，两端全圆），根本不读 `Shapes`。
 * 实测全 App 14 个有底色的按钮里只有 1 个显式传了 `shape`。
 *
 * 所以现状是：**卡片走 14dp、输入框走 6dp，而按钮是胶囊**。
 * 三者不是一套语言 —— 但按钮那一套是 Material 的默认，全 App 一致，
 * 所以不算「不一致」，只是和这套「设备面板」的收角语言不同调。
 *
 * 要不要把 14 处按钮统一改成 `shapes.small`（10dp 圆角矩形），
 * 是一次**观感层面的整体决定**，不是 bug 修复 —— 需要先看一眼效果再定。
 * 在决定之前，`small` 就当「显式指定时给 chip 用」的备用档，别当成按钮档。
 */
val Shapes =
  Shapes(
    extraSmall = RoundedCornerShape(6.dp), // 输入框、小标签
    small = RoundedCornerShape(10.dp), // chip。**不是按钮**（见上面的说明）
    medium = RoundedCornerShape(14.dp), // 卡片
    large = RoundedCornerShape(18.dp), // 气泡、FAB
    extraLarge = RoundedCornerShape(22.dp), // 对话框
  )
