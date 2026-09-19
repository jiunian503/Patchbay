package com.aichat.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 按钮。**用这三个，别直接用 Material 的 `Button` / `FilledTonalButton` /
 * `OutlinedButton`。**
 *
 * ## 为什么必须包一层
 *
 * Material 3 的按钮**不读 `MaterialTheme.shapes`** —— 它走的是自己的
 * `ButtonDefaults.shape`，也就是**胶囊**（两端全圆）。所以光在 `theme/Shape.kt`
 * 里把 `small` 改成 10dp 是一点用都没有的，全 App 的按钮不会有任何变化。
 *
 * ⚠️ **别把这条推广到所有控件上。** `OutlinedTextField` 就**读**
 * `MaterialTheme.shapes.extraSmall`（第三十五轮实测：把那一档改成 40dp，
 * 输入框立刻变成药丸）。两者在 M3 源码里长得几乎一样（都是 `XxxDefaults.shape`），
 * 但一个指向常量、一个指向 token —— **从代码上看不出来，只能实验**。
 * 详见 `theme/Shape.kt` 的注释。
 *
 * 形状只能**在每个调用点显式传**。实测（第三十四轮之前）：全 App 14 个有底色
 * 的按钮里**只有 1 个**传了 `shape`（抽屉里的「新建对话」），剩下 13 个是胶囊。
 * 于是同一个抽屉里，「新建对话」是 10dp 圆角、它上面那个图标按钮是圆形、别的
 * 页面的按钮是药丸 —— 三套形状混在一屏上。**这才是问题所在，不是「胶囊不好看」。**
 *
 * ## 所以收口在这里
 *
 * 与其在 14 个地方各写一遍 `shape = MaterialTheme.shapes.small`（下次加按钮
 * 就会忘），不如在默认参数里定死一次 —— 和 [PbCard] 是同一个做法。
 *
 * ## 三个变体对应 Material 的三个
 *
 * | 这里 | Material | 用途 |
 * |---|---|---|
 * | [PbButton] | `Button` | 主要动作，实心 |
 * | [PbTonalButton] | `FilledTonalButton` | 次要动作，浅色底 |
 * | [PbOutlinedButton] | `OutlinedButton` | 再次一级，描边 |
 *
 * 参数只放**实际用到的这四个**，没有把 Material 的一整套透传过来。真需要
 * `colors` / `border` / `contentPadding` 时再加 —— 不要凭想象预留。
 *
 * 注：`TextButton`（27 处）**没有**收进来 —— 它没有底色，形状不可见，
 * 统一它没有收益。`IconButton` 本来就是圆形，也是 Material 的标准。
 */
@Composable
fun PbButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        content = content,
    )
}

@Composable
fun PbTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        content = content,
    )
}

@Composable
fun PbOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        content = content,
    )
}
