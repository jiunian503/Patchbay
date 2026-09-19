package com.aichat.ui.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aichat.chat.ConversationSummary
import com.aichat.theme.MonoLabelStyle
import com.aichat.theme.Space
import com.aichat.ui.common.PbIcons

/**
 * 会话侧边栏。
 *
 * ## 为什么不再是一个独立的「首页」
 *
 * 原来是「列表页 → 对话页」两级：每次换个会话都要退回列表、找到、再进去。
 * 而「换会话」和「接着聊」是同一个动作的两面 —— 用户想换会话的时候，
 * 人已经在对话里了。所以列表改成从左边拉出来的抽屉，**对话页本身就是首页**。
 *
 * 连带的好处：打开 App 直接落在会话里（见 `MainNavigation` 的起始目的地），
 * 不用先看一眼列表。
 *
 * ## 为什么抽屉里不显示「当前服务商」
 *
 * 对话页标题栏已经写着「服务商 · 模型」。在抽屉里再放一遍是同一句话出现两处，
 * 而且抽屉里的那个还会在换会话后立刻变旧。
 */
@Composable
fun ConversationDrawer(
    items: List<ConversationSummary>,
    loading: Boolean,
    currentConversationId: String,
    onOpenConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onTogglePinned: (String, Boolean) -> Unit,
    onExportConversation: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // 待确认删除的那一条。删除会话是**不可撤销**的（里面的消息一起没）。
    // 确认框里写出会话标题：泛泛的「确定吗」在满屏都是会话的抽屉里没有意义。
    var pendingDelete by remember { mutableStateOf<ConversationSummary?>(null) }

    // 待改名的那一条。改名可撤销，所以**不弹确认框** —— 直接给一个能编辑的框。
    // 二次确认留给「删了就回不来」的动作，用在改名上只会让人多点一次。
    var pendingRename by remember { mutableStateOf<ConversationSummary?>(null) }

    /**
     * 列表重排之后**滚回顶部**。
     *
     * ## 为什么必须显式做
     *
     * `LazyColumn` 会记住「第一个可见条目的 key」，数据变化之后把它保持在原位
     * （这是为了「插入新消息时不跳」）。于是置顶之后那条会话在库里确实跳到了
     * 第一位，**而视图没有跟着滚上去** —— 用户看到的是「我点了置顶，可它没动」。
     * 数据是对的，是视图没跟。
     *
     * 反过来，用户往上翻过之后如果列表重排，留在原地才是对的 —— 但这里不会
     * 出现那种情况：抽屉里的列表只在拉开时和动作之后刷新，刷新就意味着
     * 「刚刚发生了一件事」，那时候用户想看的正是最上面那条。
     *
     * `items` 用内容比较（`List.equals`），所以内容没变的重组不会触发滚动。
     */
    val listState = rememberLazyListState()
    LaunchedEffect(items) {
        if (items.isNotEmpty()) listState.animateScrollToItem(0)
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「${target.title.ifBlank { "新对话" }}」？") },
            text = {
                Text(
                    if (target.messageCount > 0) {
                        "这个会话和里面的 ${target.messageCount} 条消息会一起删掉，而且不能撤销。"
                    } else {
                        "这个会话会被删掉，而且不能撤销。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteConversation(target.id)
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    pendingRename?.let { target ->
        RenameDialog(
            initial = target.title,
            onConfirm = { title ->
                pendingRename = null
                onRenameConversation(target.id, title)
            },
            onDismiss = { pendingRename = null },
        )
    }

    ModalDrawerSheet(
        // 收到 300dp：默认值在 360dp 宽的手机上几乎占满，那就不是「抽屉」
        // 而是「换了一页」，失去「旁边还留着主界面」的感觉
        modifier = Modifier.width(300.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            DrawerHeader()

            Button(
                onClick = onNewConversation,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg),
            ) {
                Icon(PbIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.sm))
                Text("新建对话")
            }

            Spacer(Modifier.height(Space.md))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            when {
                loading ->
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                items.isEmpty() ->
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "还没有对话",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                else ->
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = Space.sm, horizontal = Space.sm),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(items, key = { it.id }) { item ->
                            ConversationDrawerRow(
                                title = item.title.ifBlank { "新对话" },
                                messageCount = item.messageCount,
                                updatedAt = item.updatedAt,
                                pinned = item.pinned,
                                current = item.id == currentConversationId,
                                onClick = { onOpenConversation(item.id) },
                                onRename = { pendingRename = item },
                                onTogglePinned = { onTogglePinned(item.id, !item.pinned) },
                                onExport = { onExportConversation(item.id) },
                                onDelete = { pendingDelete = item },
                            )
                        }
                    }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DrawerAction(PbIcons.Search, "搜索历史", onOpenSearch)
            DrawerAction(PbIcons.Settings, "设置", onOpenSettings)
            Spacer(Modifier.height(Space.sm))
        }
    }
}

@Composable
private fun DrawerHeader() {
    Column(Modifier.padding(start = Space.lg, end = Space.lg, top = Space.xl, bottom = Space.md)) {
        Text(
            text = "Patchbay",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "会话",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 抽屉底部的一个动作（搜索历史 / 设置）。
 *
 * 做成**整行可点**而不是把图标塞进标题栏：抽屉里的目标行高是 48dp 上下，
 * 手指好按；而标题栏上的图标按钮虽然也是 48dp，但它在屏幕最边上，
 * 单手操作够不着。
 */
@Composable
private fun DrawerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onClick)
                .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Space.md))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 抽屉里的一条会话。
 *
 * **当前所在的那一条要看得出来。** 抽屉打开时用户第一件事是确认「我在哪」——
 * 不给标记的话，他得靠标题去猜，而两个会话标题一样是常事
 * （比如两条都叫「hi」）。标记用的是底色 + 左侧一条竖线，
 * 不用颜色单独承担信息（色盲用户看不出）。
 *
 * ## 置顶的标记为什么是文字而不是图标
 *
 * 置顶之后那条会话会一直待在最上面，而**用户过几天就忘了自己置过顶**，
 * 于是会以为是排序坏了。一行小字「置顶」直接说明原因，不需要用户去猜
 * 一个图钉的含义 —— 而且置顶/取消置顶是同一个入口的两种状态，
 * 菜单项的文案（「置顶」/「取消置顶」）也跟着当前状态走。
 */
@Composable
private fun ConversationDrawerRow(
    title: String,
    messageCount: Int,
    updatedAt: Long,
    pinned: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePinned: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        shape = shape,
        color =
            if (current) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Space.sm, end = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧竖线：当前会话的第二个信号，不依赖颜色也能看出来
            Box(
                Modifier
                    .width(3.dp)
                    .height(28.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .then(
                        if (current) {
                            Modifier.background(MaterialTheme.colorScheme.primary)
                        } else {
                            Modifier
                        },
                    ),
            )
            Spacer(Modifier.width(Space.sm))

            Column(Modifier.weight(1f).padding(vertical = Space.sm)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (current) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pinned) {
                        Text(
                            text = "置顶",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = " · ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "$messageCount",
                        style = MonoLabelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = " 条 · ${formatTime(updatedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        PbIcons.MoreVertical,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        // 文案跟着当前状态走。写死「置顶」的话，置顶过的会话
                        // 菜单里还是「置顶」，用户点一下会以为没生效
                        text = { Text(if (pinned) "取消置顶" else "置顶") },
                        onClick = {
                            menuOpen = false
                            onTogglePinned()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出 Markdown") },
                        onClick = {
                            menuOpen = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 改名对话框。
 *
 * ## 为什么点「确定」时不拦空标题
 *
 * 空标题是**有意义**的：清掉它，会话就回到「新对话」的显示。拦下来
 * 用户会以为改名功能坏了。真想去掉这个选项，也该在界面上说明，
 * 而不是静默地什么都不做。
 *
 * ## 为什么初值给的是完整标题
 *
 * 用户点「重命名」通常是想**改几个字**，不是重新打一遍。
 * 给个空框等于逼他重打。
 */
@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("会话标题") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 时间显示。
 *
 * 刻意手写而不是引 java.time 的格式化器：这里只需要「今天 / 昨天 / 几月几号」
 * 三档，用 SimpleDateFormat 反而要处理线程安全和 locale 两件事。
 *
 * `internal` 而不是 `private`：搜索结果页（同模块）也要显示时间。
 * 复制一份的话，两处的「刚刚 / 昨天」分档迟早会不一致。
 */
internal fun formatTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "—"
    val now = System.currentTimeMillis()
    val diff = now - epochMillis
    val day = 24L * 60 * 60 * 1000
    return when {
        diff < 60_000L -> "刚刚"
        diff < 60L * 60_000L -> "${diff / 60_000L} 分钟前"
        diff < day -> "${diff / (60L * 60_000L)} 小时前"
        diff < 2 * day -> "昨天"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
            "${cal.get(java.util.Calendar.MONTH) + 1} 月 ${cal.get(java.util.Calendar.DAY_OF_MONTH)} 日"
        }
    }
}
