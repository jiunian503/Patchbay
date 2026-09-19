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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { ToolCountNotice(state.totalTools) }

                if (state.conflicts.isNotEmpty()) {
                    item { ConflictBlock(state.conflicts) }
                }

                if (state.items.isEmpty()) {
                    item { EmptyHint() }
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

            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    FilledTonalButton(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                        Text("安装插件")
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCountNotice(total: Int) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = "模型现在能用 $total 个工具",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = "插件提供的能力会直接出现在对话里，模型自己决定什么时候调用。" +
                    "涉及写操作、或者插件声明了「任意主机」访问权时，调用前会先问你。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun ConflictBlock(lines: List<String>) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = "有 ${lines.size} 个插件工具没有被启用",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
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
private fun EmptyHint() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "还没有装插件",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PluginCard(row: PluginRow, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleSmall,
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
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (row.isBroken) {
                IssueLine("这个插件的清单现在解析不了，它提供的工具已经不可用。点进去看原因。", error = true)
            }
            if (row.toolCount > 0) {
                Text(
                    text = "提供 ${row.toolCount} 个工具",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.notices.forEach { IssueLine(it, error = true) }
            row.warnings.forEach { IssueLine(it, error = false) }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpen) { Text("详情与设置") }
            }
        }
    }
}

@Composable
private fun IssueLine(text: String, error: Boolean) {
    Text(
        text = if (error) "⚠️ $text" else "· $text",
        style = MaterialTheme.typography.labelSmall,
        color = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
