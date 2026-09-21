package com.aichat.ui.characters

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.di.AppContainer
import com.aichat.theme.Space
import com.aichat.ui.common.PbButton
import com.aichat.ui.common.PbCard
import com.aichat.ui.common.PbIcons
import com.aichat.ui.common.PbOutlinedButton
import com.aichat.ui.common.PbScaffold
import com.aichat.ui.common.PbSectionLabel

/**
 * 角色编辑：人设 + 它的世界书。
 *
 * ## 为什么不用 `PbTextField`
 *
 * 因为它不存在 —— 这个 App 的表单一直直接用 M3 的 `OutlinedTextField`
 * （见 `ProviderEditScreen`）。统一在这个约定上，别为了一处「好看一点」
 * 造一个组件，那会变成第二套表单语言。
 *
 * ## 世界书为什么直接长在这一页里
 *
 * 世界书是**挂在角色上**的（一个角色一本），所以它不是独立的东西，
 * 就是这张角色卡的一部分。做成「角色 → 世界书 → 条目」三级导航的话，
 * 用户为了改一个触发词要点三次，而那条路径上没有任何一次点击
 * 是在做选择 —— 他早就知道要改哪个角色的词条了。
 *
 * ## 人设和简介的说明文字是刻意的
 *
 * 两个框长得像，作用完全不同：简介只在列表页显示，人设会**原样发给模型**。
 * 不写清楚的话，用户会把「这是我照着小说捏的」写进人设里，
 * 而模型会把它当成设定的一部分照着演。
 */
@Composable
fun CharacterEditScreen(
    container: AppContainer,
    characterId: String?,
    onBack: () -> Unit,
) {
    val viewModel: CharacterEditViewModel =
        viewModel { CharacterEditViewModel(container, characterId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 保存成功就退回去。放在 LaunchedEffect 里而不是直接在按钮的 onClick
    // 里调 onBack：保存是挂起操作，失败时不该离开这一页
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    PbScaffold(
        title = when {
            state.loading -> "角色"
            state.isNew -> "新建角色"
            else -> "编辑角色"
        },
        onBack = onBack,
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
                    top = Space.md + topInset,
                    bottom = Space.xxl,
                ),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            item { PbSectionLabel("基本信息") }

            item {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = { Text("名字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                OutlinedTextField(
                    value = state.description,
                    onValueChange = viewModel::setDescription,
                    label = { Text("简介") },
                    supportingText = { Text("只在角色列表里显示，不会发给模型。") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                OutlinedTextField(
                    value = state.persona,
                    onValueChange = viewModel::setPersona,
                    label = { Text("人设") },
                    supportingText = { Text("每次发送都会放在提示词最前面。留空则不注入。") },
                    minLines = 5,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { PbSectionLabel("世界书") }

            item { WorldBookHint() }

            itemsIndexed(
                items = state.entries,
                // 新建的词条还没有 id，用下标兜底 —— 两者都不会重复
                key = { index, entry -> entry.id ?: "new-$index" },
            ) { index, entry ->
                EntryCard(
                    index = index,
                    entry = entry,
                    total = state.entries.size,
                    onUpdate = { transform -> viewModel.updateEntry(index, transform) },
                    onRemove = { viewModel.removeEntry(index) },
                    onMove = { delta -> viewModel.moveEntry(index, delta) },
                )
            }

            item {
                PbOutlinedButton(onClick = viewModel::addEntry, modifier = Modifier.fillMaxWidth()) {
                    Icon(PbIcons.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Space.xs))
                    Text("加一条词条")
                }
            }

            item {
                Spacer(Modifier.height(Space.xs))
                PbButton(
                    onClick = viewModel::save,
                    modifier = Modifier.fillMaxWidth(),
                    // 名字为空时保存必然失败（Repository 会抛）。
                    // 与其让用户点一下再看到报错，不如直接禁掉
                    enabled = state.name.isNotBlank(),
                ) {
                    Text("保存")
                }
            }
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("保存失败") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("知道了") } },
        )
    }
}

@Composable
private fun WorldBookHint() {
    PbCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                PbIcons.Book,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Space.sm))
            Column {
                Text(
                    text = "聊到才生效",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    text = "词条只有在这段对话里出现过触发词时才会发给模型。" +
                        "触发词可以写好几个，用逗号或顿号隔开 —— 任意一个命中都算。" +
                        "中文按子串匹配，英文缩写按整词匹配（所以 AI 不会在 said 里被触发）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EntryCard(
    index: Int,
    entry: WorldBookEntryForm,
    total: Int,
    onUpdate: ((WorldBookEntryForm) -> WorldBookEntryForm) -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
) {
    PbCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "第 ${index + 1} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // 顺序就是注入顺序，所以必须能调。用按钮不用拖拽：这里最多
            // 十几条，两个按钮够用，而且不会误触
            TextButton(onClick = { onMove(-1) }, enabled = index > 0) { Text("上移") }
            TextButton(onClick = { onMove(1) }, enabled = index < total - 1) { Text("下移") }
            TextButton(onClick = onRemove) { Text("删除") }
        }

        Spacer(Modifier.height(Space.xs))
        OutlinedTextField(
            value = entry.keysText,
            onValueChange = { value -> onUpdate { it.copy(keysText = value) } },
            label = { Text("触发词") },
            placeholder = { Text("老王、王叔、王建国") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Space.xs))
        OutlinedTextField(
            value = entry.content,
            onValueChange = { value -> onUpdate { it.copy(content = value) } },
            label = { Text("内容") },
            supportingText = { Text("命中后追加进提示词。") },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "启用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = entry.enabled,
                onCheckedChange = { value -> onUpdate { it.copy(enabled = value) } },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "触发词区分大小写",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = entry.caseSensitive,
                onCheckedChange = { value -> onUpdate { it.copy(caseSensitive = value) } },
            )
        }
    }
}
