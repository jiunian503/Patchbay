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
 * 所以整体压一档到 10 / 10 / 14 / 18 / 22（`extraSmall` 与 `small` 同值，见下）——
 * 仍然是圆角，但克制。
 *
 * 另外原来各处的圆角是**硬编码**的（气泡 14dp、思考块 10dp、工具 chip 8dp），
 * 各写各的、以后必然漂移。现在统一走这里，改一处全变。
 *
 * ## 按钮：已收口到 `PbButton`（第三十四轮）
 *
 * 这里原本写着 `small = 按钮`，但那是**写了没生效**：
 * `Button` / `FilledTonalButton` / `OutlinedButton` 走的是自己的
 * `ButtonDefaults.shape`（**胶囊形**，两端全圆），**不读 `Shapes`**。
 *
 * 于是形状只能在每个调用点显式传 —— 而实测全 App 14 个有底色的按钮
 * （`Button` 6 + `FilledTonalButton` 4 + `OutlinedButton` 4）里**只有 1 个**
 * 传了 `shape`（抽屉里的「新建对话」传了 `small`）。结果是同一个抽屉里
 * 「新建对话」是 10dp 圆角、它上面的图标按钮是圆形、别的页面是药丸 ——
 * **一屏三套形状**。这比「全是胶囊」更像 bug。
 *
 * 决定是**统一到 `small`**（10dp）。做法**不是**逐处传 `shape`（那是 14 处），
 * 而是照 `PbCard` 的样子包了一层 `PbButton` / `PbTonalButton` /
 * `PbOutlinedButton`（都在 `ui/common/PbButton.kt`）：默认参数里定死一次，
 * 14 个调用点只换名字。**以后加按钮用那三个，别直接用 Material 的。**
 *
 * ## 输入框：**读** `extraSmall`，16 处一行都不用改（第三十五轮更正）
 *
 * 第三十四轮我把上面那句「M3 的控件不读 `Shapes`」**顺手也套在了输入框上 —— 那是错的**。
 *
 * `OutlinedTextField` 的 `shape` 默认值链是：
 * `OutlinedTextFieldDefaults.shape` → `TextFieldDefaults.shape` →
 * `FilledTextFieldTokens.ContainerShape` → **`MaterialTheme.shapes.extraSmall`**。
 * 也就是说这一档从写下那天起就是生效的，全 App 16 处 `OutlinedTextField`
 * 都在读它 —— 只是当时值是 6dp，和 M3 默认的 4dp 很接近，肉眼看不出来。
 *
 * **怎么验的（这招比读库源码快，值得记住）**：把 `extraSmall` 临时改成 `40.dp`，
 * 编译装机，截一张「输入框和按钮并排」的页面 —— 输入框立刻变成**药丸**，
 * 而紧挨着的按钮纹丝不动（它走 `small`）。**一个实验同时验两件事。**
 *
 * 于是把 `extraSmall` 从 6dp 提到 **10dp**（和按钮同值），16 处输入框
 * **零代码改动**跟着变。
 *
 * ## 教训
 *
 * 「某个控件读不读主题 token」**不能靠记忆**。M3 里按钮不读、输入框读 ——
 * 两者在源码里长得几乎一样（都是 `XxxDefaults.shape`），但一个指向常量、
 * 一个指向 token。**要判断就把 token 改成夸张的值看一眼**，别猜。
 *
 * 这两档本身没白留：思考块、工具 chip、失败块在用 `small`；输入框、服务商列表
 * 的状态标签、抽屉的选中竖条在用 `extraSmall`。
 *
 * ## 另有 3 处硬编码 —— **全部是有意的**（第三十六轮收完了）
 *
 * - 引用块左侧竖条 2dp —— 装饰线条，不是容器
 * - 消息气泡的 18dp / 一侧 6dp —— 那个 6dp 是**尾巴**，靠非对称指出是谁说的话
 *
 * 原来还有三处 8dp（代码块卡片、表格、工具审批弹窗里的参数 JSON），第三十六轮
 * 收成了 `small`。**收的理由不是「8 不好看」，是它根本不在刻度里**（10/14/18/22），
 * 而且**同屏的同类块用的就是 `small`** —— `ToolChip`（工具调用条）和 `FailureBlock`
 * （生成失败块）都在消息里、都是 10dp。一条消息里三个块 10dp、两个块 8dp，
 * 差 2dp 只会像手滑。
 *
 * 还有一条更硬的理由：这些块**嵌在消息气泡里**，而气泡是 `large = 18dp`。
 * **嵌套容器的圆角必须小于父容器**，否则两层弧线打架。10dp < 18dp ✓。
 */
val Shapes =
  Shapes(
    // extraSmall 与 small **刻意同值**：输入框和按钮在同一行里并排出现
    // （搜索栏、聊天输入栏、插件安装页），半径不同会看起来像没对齐。
    extraSmall = RoundedCornerShape(10.dp), // 输入框、状态标签、抽屉选中条
    small = RoundedCornerShape(10.dp), // 按钮（经 PbButton）、chip、思考块
    medium = RoundedCornerShape(14.dp), // 卡片
    large = RoundedCornerShape(18.dp), // 气泡、FAB
    extraLarge = RoundedCornerShape(22.dp), // 对话框
  )
