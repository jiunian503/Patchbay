package com.aichat.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aichat.theme.Space

/**
 * 设置类页面共用的三块外观。
 *
 * ## 为什么要抽出来
 *
 * 这些东西原来各自写在自己的页面里（`ProviderListScreen` 有一份私有的
 * `PbCard`），第二页要用的时候只能复制一份 —— 而复制出来的那份迟早会和
 * 原来那份不一样：圆角差 2dp、内边距差 4dp 这种事在单个页面里看不出来，
 * 两个页面来回切就能看出来「哪里不对但说不上来」。
 *
 * 现在「卡片长什么样」「小节标题长什么样」只有一处定义。
 *
 * ## 为什么不是 Material 的 `Card`
 *
 * 默认的 `Card` 用 `surfaceContainerLow` 做底色，在这个 App 的浅色主题里
 * 和背景几乎同色（见 `Theme.kt` 的五级阶梯）—— 于是卡片会「看不见」，
 * 只剩一圈很淡的边。这里改成 `surface` 底色 + `outlineVariant` 描边，
 * 边界清楚，而且深色下不会糊成一片。
 */
@Composable
fun PbCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            modifier.fillMaxWidth().then(
                if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier,
            ),
    ) {
        Column(Modifier.padding(Space.lg), content = content)
    }
}

/** 小节标题。全大写不适用于中文，所以靠字号和颜色拉开层级。 */
@Composable
fun PbSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Space.xs, top = Space.md, bottom = Space.xs),
    )
}

/**
 * 空状态 / 说明卡：一个图标 + 标题 + 一句话。
 *
 * 图标用 `outline` 而不是 `primary`：它不是「这里有个操作」，
 * 而是「这里本来该有内容，但还没有」。
 */
@Composable
fun PbHintCard(
    icon: ImageVector,
    title: String,
    body: String,
) {
    PbCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Space.sm))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 空状态：**居中的一张说明卡**，而不是一行悬在空白里的灰字。
 *
 * 一行灰字在视觉上等于「这里什么都没有，别看了」；一张卡会说明本来该有什么、
 * 以及怎么让它出现。两种做法在同一个 App 里并存的话，用户会把「灰字」读成
 * 「这一块坏了」。
 *
 * 抽到这里是因为它已经在两处出现：搜索页原来有个私有的 `HintState`，抽屉里的
 * 空状态又要写一遍同样的 Box + 居中 + 左右留白。**卡片的样式**由 [PbHintCard]
 * 保证一致，**摆放方式**由这里保证一致 —— 两件事都该有唯一出处。
 *
 * [modifier] 默认铺满并居中。放在 `Column` 里（比如抽屉）时传
 * `Modifier.weight(1f)`，别用 `fillMaxSize()` —— 后者会跟兄弟节点抢高度。
 */
@Composable
fun PbEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    Box(
        modifier = modifier.padding(horizontal = Space.lg),
        contentAlignment = Alignment.Center,
    ) {
        PbHintCard(icon = icon, title = title, body = body)
    }
}

/**
 * 设置页里那种「一行入口」：图标 + 标题 + 说明 + 右侧箭头。
 *
 * 箭头不是装饰：**它是「这一行能点进去」唯一的视觉信号**。
 * 这个 App 里有过一次教训 —— 顶栏文字默认看起来像「显示」而不是「能点」，
 * 所以给能点的东西加了一个 `▾`（见 `ChatScreen`）。
 * 这里同理，箭头就是这个 `▾`。
 */
@Composable
fun PbNavRow(
    icon: ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    PbCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Space.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(Space.sm))
            Icon(
                PbIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
