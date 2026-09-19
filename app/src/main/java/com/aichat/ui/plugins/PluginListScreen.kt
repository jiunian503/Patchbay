package com.aichat.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.di.AppContainer
import com.aichat.theme.Space
import com.aichat.ui.common.PbCard
import com.aichat.ui.common.PbHintCard
import com.aichat.ui.common.PbIcons
import com.aichat.ui.common.PbScaffold

/**
 * 插件列表。
 *
 * ## 这个页面必须显示三件「正常产品会藏起来」的事
 *
 * 1. **冲突**：插件工具因为重名被丢弃。丢弃之后模型和用户都看不到它，
 *    这里是唯一能解释「为什么我装了插件却用不上」的地方。
 * 2. **装配问题**：比如运行形态还没实现。装了一个插件、工具列表里什么都没有、
 *    也没有任何提示 —— 这是最难查的一种状态。
 * 3. **清单坏掉的插件**：它们已经不在工具列表里了，如果这里也不显示，
 *    用户看到的只是「它不见了」。
 *
 * 共同点是：**沉默的失败**。这三件事一旦不显示，用户拿到的信息就只剩
 * 「好像没生效」，而那是没法排查的。
 *
 * ## 这一页原来和设置页长得不一样
 *
 * 插件卡片用的是 `Card(surfaceVariant)` —— **灰底压在近白的页面底色上**。
 * 而设置页的卡片是 `PbCard`（纯白 + 一圈描边）。于是同一个 App 里
 * 两套卡片语言：设置页的卡片是「纸叠在桌面上」，插件页的卡片是「凹进去的补丁」。
 *
 * 这正是 `PbCard` 的注释里写过的那个教训 ——「复制出来的那份迟早会和原来那份不一样」。
 * 所以这里不再自己写卡片，改用 `PbCard`。
 *
 * 另外两处同类的：
 *
 * - 「安装插件」原来是**底部通栏按钮**。它占掉一整行高度、把列表压短，
 *   而且看起来像一条「横幅」而不是一个动作。改成 FAB（见 §54）。
 * - 冲突提示原来用 `"⚠️ $text"`。**emoji 的字形由系统字体决定**（各家 ROM
 *   长得都不一样）、基线压不住、颜色不受 `contentColor` 控制 —— 在一套自己定的
 *   配色里塞一个不受控的彩色字形，是全屏最扎眼的一处不一致。改成画出来的图标。
 */
@Composable
fun PluginListScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPlugin: (String) -> Unit,
    onInstall: () -> Unit,
) {
    val viewModel: PluginListViewModel = viewModel { PluginListViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 从安装页/详情页返回时重新读一次：那边可能刚装完或刚卸载
    LaunchedEffect(Unit) { viewModel.refresh() }

    PbScaffold(
        title = "插件",
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onInstall,
                icon = { Icon(PbIcons.Add, contentDescription = null) },
                text = { Text("安装插件") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { topInset ->
        if (state.loading) {
            Box(
                Modifier.fillMaxSize().padding(top = topInset),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@PbScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = Space.lg,
                    end = Space.lg,
                    // 顶栏是浮在列表上方的，第一条要自己避开它（见 PbScaffold 的 KDoc）
                    top = Space.md + topInset,
                    // 底部让开 FAB，否则最后一张卡会被它压住
                    bottom = 88.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            item { ToolCountNotice(state.totalTools) }

            if (state.conflicts.isNotEmpty()) {
                item { ConflictBlock(state.conflicts) }
            }

            if (state.items.isEmpty()) {
                item {
                    PbHintCard(
                        icon = PbIcons.Plugin,
                        title = "还没有装插件",
                        body = "插件是给模型接上外部能力的东西 —— 查天气、读表格、连你自己的服务。" +
                            "点右下角装一个，或者自己写一份清单。",
                    )
                }
            } else {
                items(state.items, key = { it.id }) { row ->
                    PluginCard(
                        row = row,
                        onOpen = { onOpenPlugin(row.id) },
                        onToggle = { viewModel.setEnabled(row.id, it) },
                    )
                }
            }
        }
    }
}

/**
 * 「模型现在能用 N 个工具」。
 *
 * 原来是一块铺满宽度的 `secondaryContainer` 底色横幅。横幅在视觉上等同于
 * 「警告」，而这段话不是警告 —— 它是**这一页的说明**（告诉用户插件到底改变了什么）。
 * 改成带边框的普通卡片 + 一个信息图标，和设置页的隐私说明同一套做法。
 */
@Composable
private fun ToolCountNotice(total: Int) {
    PbCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                PbIcons.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Space.sm))
            Column {
                Text(
                    text = "模型现在能用 $total 个工具",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    text = "插件提供的能力会直接出现在对话里，模型自己决定什么时候调用。" +
                        "涉及写操作、或者插件声明了「任意主机」访问权时，调用前会先问你。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 工具重名冲突。
 *
 * 这里**保留** `errorContainer` 底色 —— 和上面那条说明不同，这是一件真的出错的事，
 * 而它的后果是「某个插件装了却用不了」，必须一眼看到。
 */
@Composable
private fun ConflictBlock(lines: List<String>) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    PbIcons.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = "有 ${lines.size} 个插件工具没有被启用",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(Space.xs))
            lines.forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun PluginCard(row: PluginRow, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    PbCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${row.version} · ${row.runtimeLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = row.enabled, onCheckedChange = onToggle)
        }

        row.description?.let {
            Spacer(Modifier.height(Space.xs))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (row.isBroken) {
            Spacer(Modifier.height(Space.xs))
            IssueLine("这个插件的清单现在解析不了，它提供的工具已经不可用。点进去看原因。", error = true)
        }
        if (row.toolCount > 0) {
            Spacer(Modifier.height(Space.xs))
            Text(
                text = "提供 ${row.toolCount} 个工具",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        row.notices.forEach {
            Spacer(Modifier.height(Space.xs))
            IssueLine(it, error = true)
        }
        row.warnings.forEach {
            Spacer(Modifier.height(Space.xs))
            IssueLine(it, error = false)
        }

        Spacer(Modifier.height(Space.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            TextButton(onClick = onOpen) { Text("详情与设置") }
        }
    }
}

/**
 * 一行问题说明。
 *
 * `error = true` 时**画一个警告图标**而不是打一个 `⚠️`（见文件头的说明）。
 * 图标是 `Icon`，所以它跟着 `contentColor` 走 —— 换主题、换深浅色都自动对。
 */
@Composable
private fun IssueLine(text: String, error: Boolean) {
    val color =
        if (error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(verticalAlignment = Alignment.Top) {
        if (error) {
            Icon(
                PbIcons.Warning,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(Space.xs))
        } else {
            Text(text = "·", style = MaterialTheme.typography.labelSmall, color = color)
            Spacer(Modifier.width(Space.xs))
        }
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
