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
 */
val Shapes =
  Shapes(
    extraSmall = RoundedCornerShape(6.dp), // 输入框、小标签
    small = RoundedCornerShape(10.dp), // 按钮、chip
    medium = RoundedCornerShape(14.dp), // 卡片
    large = RoundedCornerShape(18.dp), // 气泡、FAB
    extraLarge = RoundedCornerShape(22.dp), // 对话框
  )
