package com.aichat.ui.characters

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.di.AppContainer
import com.aichat.theme.Space
import com.aichat.ui.common.PbIcons

/**
 * 对话页顶栏上的「角色」按钮 + 选择器。
 *
 * ## 为什么做成一个自包含的组件
 *
 * 按钮在 `actions`（`RowScope`）里，弹窗却要在页面顶层渲染 —— 分开放的话，
 * 对话页要为它加一个 ViewModel、一个状态、一个弹窗，四处改动散在一个
 * 1600 行的文件里。收成一个组件之后，对话页只需要 `actions` 里写一行。
 *
 * `Dialog` 本身是一个独立的窗口，所以它写在 `RowScope` 里也能正常显示 ——
 * 这一点不是巧合，是 `AlertDialog` 的实现方式决定的（它不参与父布局测量）。
 *
 * ## 按钮的点亮状态
 *
 * 选了角色时图标用 `primary` 色。**这是这一页唯一能看出「当前带不带人设」
 * 的地方** —— 标题栏已经被服务商和模型名占满了，再加一行会把顶栏撑高。
 * 用户想知道具体是哪个角色，点开看勾选。
 */
@Composable
fun CharacterPickerAction(
    container: AppContainer,
    conversationId: String,
) {
    val viewModel: ConversationCharacterViewModel =
        viewModel(key = "character-$conversationId") {
            ConversationCharacterViewModel(container, conversationId)
        }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 从设置页回来时重读一次 —— 用户可能在那边把正在用的角色删了或改了名，
    // 而 ViewModel 不会因为「回到前台」就重建（对话页一直在返回栈底）
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reload() }

    IconButton(onClick = viewModel::openPicker) {
        Icon(
            imageVector = PbIcons.Persona,
            contentDescription = if (state.selectedId != null) "角色：${state.selectedName}" else "选择角色",
            tint =
                if (state.selectedId != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    // 没选角色时跟着顶栏的 contentColor 走，和旁边的设置按钮一致
                    LocalContentColor.current
                },
        )
    }

    if (state.picking) {
        CharacterPickerDialog(
            characters = state.characters,
            currentId = state.selectedId,
            onPick = viewModel::select,
            onDismiss = viewModel::closePicker,
        )
    }
}

@Composable
private fun CharacterPickerDialog(
    characters: List<CharacterRow>,
    currentId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这个会话用哪个角色") },
        text = {
            if (characters.isEmpty()) {
                // 空状态要给**出路**，不能只说「没有」。用户此刻的意图很明确
                // （想选一个角色），所以这句话必须告诉他去哪儿建
                Text("还没有角色卡。在「设置 → 角色」里新建一个 —— 写好人设之后，它和它的世界书就跟着这个会话走。")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // 「不用角色」也是一个选项：选了之后要能退回默认状态。
                    // 少了这一项，用户想恢复默认就只能去把角色卡删掉
                    PickerRow(
                        name = "不用角色",
                        body = "只发服务商上配的附加提示词。",
                        selected = currentId == null,
                        onClick = { onPick(null) },
                    )
                    characters.forEach { row ->
                        PickerRow(
                            name = row.name,
                            body = row.description.ifBlank { null },
                            selected = row.id == currentId,
                            onClick = { onPick(row.id) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PickerRow(name: String, body: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            body?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 用勾而不是加粗/换色来标「当前选中」：这个列表里的每一项都是可点的，
        // 只靠文字颜色区分「选中」和「可点」在这套配色里区分度不够
        if (selected) {
            Icon(
                imageVector = PbIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
