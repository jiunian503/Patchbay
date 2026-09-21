package com.aichat.ui.characters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
 * 角色列表。
 *
 * ## 这一页要解释「角色卡到底改变了什么」
 *
 * 用户第一次进来看到的是一个空列表和一个「新建角色」按钮 —— 而他心里
 * 并不知道角色卡和「服务商里那个系统提示词」有什么区别。所以顶部那张说明卡
 * 不是装饰，它回答的是「我为什么要在这儿写东西」。
 *
 * 顺序也写在那张卡里（人设 → 世界书 → 附加提示词）。这个顺序在界面上
 * 是**看不到的** —— 三个输入框分散在两个页面（角色编辑页、服务商编辑页），
 * 用户没法从界面上推断出谁在前谁在后。看不见的规则必须说出来。
 */
@Composable
fun CharacterListScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
) {
    val viewModel: CharacterListViewModel = viewModel { CharacterListViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    PbScaffold(
        title = "角色",
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(null) },
                icon = { Icon(PbIcons.Add, contentDescription = null) },
                text = { Text("新建角色") },
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
                    // 顶栏浮在列表上方，第一条要自己避开它（见 PbScaffold 的 KDoc）
                    top = Space.md + topInset,
                    // 底部让开 FAB，否则最后一张卡会被它压住
                    bottom = 88.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            item { HowItWorksCard() }

            if (state.items.isEmpty()) {
                item {
                    PbHintCard(
                        icon = PbIcons.Persona,
                        title = "还没有角色",
                        body = "角色卡是一套可复用的设定 —— 写一次人设，之后在每个会话里直接选它。" +
                            "世界书挂在角色上：只有聊到相关的词，那一段设定才会发给模型，" +
                            "所以设定集写多长都不占日常对话的上下文。",
                    )
                }
            } else {
                items(state.items, key = { it.id }) { row ->
                    CharacterCard(
                        row = row,
                        onEdit = { onEdit(row.id) },
                        onDelete = { viewModel.askDelete(row) },
                    )
                }
            }
        }
    }

    // 删除要确认。正文必须说清**两件**后果 —— 世界书一起没了（不可恢复），
    // 以及用它的会话会变回「没有角色」（聊天记录还在，但不再注入任何东西）。
    // 只说「确定删除吗」的话，用户事后会觉得「怎么这个会话变笨了」
    state.pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("删除「${row.name}」？") },
            text = {
                Text(
                    if (row.entryCount > 0) {
                        "它的 ${row.entryCount} 条世界书词条会一起删掉，没法恢复。" +
                            "用它的会话会变回「没有角色」的状态 —— 聊天记录都还在，" +
                            "只是不再注入人设和设定。"
                    } else {
                        "用它的会话会变回「没有角色」的状态 —— 聊天记录都还在，" +
                            "只是不再注入人设。"
                    }
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text("删除") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text("取消") } },
        )
    }
}

@Composable
private fun HowItWorksCard() {
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
                    text = "角色卡怎么生效",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    text = "会话里选中一个角色之后，每次发送都会把它的「人设」放在最前面，" +
                        "命中的世界书词条接在后面。服务商上配的「附加提示词」排在最后 —— " +
                        "那一段对所有角色都生效，用来写输出格式、语言这类通用要求。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CharacterCard(row: CharacterRow, onEdit: () -> Unit, onDelete: () -> Unit) {
    PbCard {
        Text(
            text = row.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (row.entryCount > 0) "世界书 ${row.entryCount} 条" else "没有世界书词条",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 简介优先，没写简介就露一段人设 —— 总比一行空白强，
        // 用户在一堆角色里认人的依据就是这点文字
        val preview = row.description.ifBlank { row.persona }
        if (preview.isNotBlank()) {
            Spacer(Modifier.height(Space.xs))
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(Space.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            TextButton(onClick = onEdit) { Text("编辑") }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}
