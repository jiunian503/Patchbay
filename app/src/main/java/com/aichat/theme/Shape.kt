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
 * ## 按钮和输入框：写了没生效
 *
 * 这里原本写着 `small = 按钮`、`extraSmall = 输入框`，但那是**写了没生效**：
 *
 * - `Button` / `FilledTonalButton` / `OutlinedButton` 走 `ButtonDefaults.shape`
 *   （**胶囊形**，两端全圆），不读 `Shapes`
 * - `OutlinedTextField` 走 `OutlinedTextFieldDefaults.shape`，也不读 `Shapes`
 *
 * 实测全 App 14 个有底色的按钮（`Button` 6 + `FilledTonalButton` 4 +
 * `OutlinedButton` 4）、16 处 `OutlinedTextField`，**没有任何一处**显式传过
 * `shape`。所以这两类控件走的其实是 Material 的默认，跟这套「设备面板」的
 * 收角语言不是一套。
 *
 * 要不要统一，是一次**观感层面的整体决定**，不是 bug 修复。
 *
 * 真要统一，正确做法**不是**逐处传 `shape`（那是 30 处），而是照 `PbCard`
 * 的样子包一层 `PbButton` / `PbTextField` —— 默认参数里把 shape 设好，
 * 调用点只换名字，以后也不会再漂回去。
 *
 * 这两档本身没白留：思考块、工具 chip、失败块在用 `small`，服务商列表的状态
 * 标签在用 `extraSmall`。是「按钮和输入框没用」，不是「没人用」。
 *
 * ## 另有 4 处硬编码绕过了这里
 *
 * 代码块卡片 8dp、引用块左侧竖条 2dp、表格 8dp、工具审批弹窗 8dp。竖条那 2dp
 * 是有意的（装饰线条不是容器），剩下三处迟早该收进来。
 */
val Shapes =
  Shapes(
    extraSmall = RoundedCornerShape(6.dp), // 输入框、小标签
    small = RoundedCornerShape(10.dp), // chip。**不是按钮**（见上面的说明）
    medium = RoundedCornerShape(14.dp), // 卡片
    large = RoundedCornerShape(18.dp), // 气泡、FAB
    extraLarge = RoundedCornerShape(22.dp), // 对话框
  )
