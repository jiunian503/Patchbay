package com.aichat.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 二级页面的骨架：顶栏 + 毛玻璃 + 内容避让。
 *
 * ## 为什么连 `Scaffold` 一起收口，而不是只抽一个顶栏
 *
 * 顶栏毛玻璃不是「给顶栏加个效果」—— 它要**三件事同时成立**：
 *
 * 1. 内容能**滚到顶栏背后**（`Scaffold` 的 content 不能消费 `padding.top`）
 * 2. 顶栏自己**透明**并挂上 `hazeEffect`
 * 3. 内容自己用**内边距**避开顶栏（`LazyColumn` 的 `contentPadding`，
 *    或滚动容器里的 `Modifier.padding`）
 *
 * 这三件事分在三个地方，**谁漏一个都不会报错** —— 只会看到「顶栏是一条实心色条」，
 * 而且看不出是哪一步没做到。所以把它们收进同一个组件，调用方只负责两件事：
 * 往 `content` 里放内容、用传进来的 `topInset` 做避让。
 *
 * 这和 `PbTopBar` 的关系：`PbScaffold` 内部就是调它，只是额外把
 * `containerColor` 换成透明、把 `hazeEffect` 挂上去。
 *
 * ## 用法
 *
 * ```kotlin
 * PbScaffold(title = "插件", onBack = onBack) { topInset ->
 *     LazyColumn(
 *         contentPadding = PaddingValues(
 *             start = Space.lg, end = Space.lg,
 *             top = Space.md + topInset,   // ← 用传进来的
 *             bottom = Space.md,
 *         ),
 *     ) { ... }
 * }
 * ```
 *
 * ⚠️ **不要再给列表加 `Modifier.padding(padding)`** —— 那是收口之前的写法，
 * 它会把列表整个推下去，顶栏背后永远是空的，毛玻璃白做。
 *
 * ## 毛玻璃的参数
 *
 * 与 `ChatScreen` 用的是同一套（`tintAlpha` 0.35、`backgroundColor` 取
 * `background` 而不是 `surface`）。这两个值都是实测出来的，理由见 §62 ——
 * **改动前先读那一节**，别凭直觉调。
 *
 * @param topInset 顶栏盖住的高度（已含状态栏）。调用方拿它做内容的顶部内边距。
 */
@Composable
fun PbScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (topInset: Dp) -> Unit,
) {
    val hazeState = rememberHazeState()

    // 顶栏背后的底色用 `background` 而不是 `surface` —— 顶栏背后没有内容的地方，
    // 露出来的该是页面底色。这两个 token 在浅色主题下差 5 个色阶，窄屏看不出来，
    // 宽屏会沿顶栏下沿拉出一条横线（§62 有实测）。
    val backdrop = MaterialTheme.colorScheme.background
    val fallback = MaterialTheme.colorScheme.surface
    val hazeStyle =
        remember(backdrop, fallback) {
            HazeStyle(
                backgroundColor = backdrop,
                tint = HazeTint(backdrop.copy(alpha = 0.35f)),
                blurRadius = 20.dp,
                // 不能模糊的机器（API < 31）上退化成普通不透明顶栏 ——
                // 用 surface 是因为 M3 的顶栏本来就该比页面底色亮一档
                fallbackTint = HazeTint(fallback.copy(alpha = 0.94f)),
            )
        }

    Scaffold(
        containerColor = backdrop,
        topBar = {
            PbTopBar(
                title = title,
                onBack = onBack,
                actions = actions,
                // 背景必须透明，否则把 haze 的模糊整个盖住
                containerColor = Color.Transparent,
                modifier = Modifier.hazeEffect(state = hazeState, style = hazeStyle),
            )
        },
        floatingActionButton = floatingActionButton,
        snackbarHost = snackbarHost,
    ) { padding ->
        val topInset = padding.calculateTopPadding()
        val layoutDirection = LocalLayoutDirection.current

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    // ⚠️ 顶部**故意不消费** Scaffold 给的 padding：内容要能滚到顶栏背后，
                    // 毛玻璃才有东西可模糊。左右和底部照常消费。
                    .padding(
                        start = padding.calculateStartPadding(layoutDirection),
                        end = padding.calculateEndPadding(layoutDirection),
                        bottom = padding.calculateBottomPadding(),
                    )
                    // 毛玻璃的源。挂在内容这一层，顶栏只取自己背后那一条
                    .hazeSource(hazeState),
        ) {
            content(topInset)
        }
    }
}
