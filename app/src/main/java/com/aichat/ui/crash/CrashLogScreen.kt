package com.aichat.ui.crash

import android.content.ClipData
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.crash.CrashRecord
import com.aichat.di.AppContainer
import com.aichat.theme.MonoLabelStyle
import com.aichat.theme.MonoTextStyle
import com.aichat.theme.Space
import com.aichat.ui.common.PbCard
import com.aichat.ui.common.PbEmptyState
import com.aichat.ui.common.PbHintCard
import com.aichat.ui.common.PbIcons
import com.aichat.ui.common.PbScaffold
import com.aichat.ui.conversations.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 崩溃记录页。
 *
 * ## 这一页存在的理由
 *
 * 在它之前，用户说「它闪退了」，我们手里什么都没有 —— 没有堆栈、没有时间、
 * 没有版本号。现在崩溃会被写在本机（见 `com.aichat.crash`），这一页负责
 * 让用户**拿到它、发给别人**。所以「复制」是这一页的主按钮，不是附注。
 *
 * ## 三处刻意的取舍
 *
 * 1. **最新一条默认展开。** 用户进来十有八九是为了刚发生的那次崩溃，
 *    而它就在最上面。要求先点一下才看得到内容，是把最常见的路径多绕一步。
 *    其余几条折叠 —— 折叠状态下每条的头部（异常类 + 时间）就够用来挑了。
 * 2. **不联网、不上传，并且把这句话写在页面上。** 「崩溃记录」四个字的第一
 *    反应是「我的东西被传走了吗」。答案要由界面自己给出，不能指望用户去翻文档。
 * 3. **「清除全部」收在顶栏，并且要二次确认。** 它是这一页唯一不可撤销的动作
 *    （堆栈删了就没有第二份），而它旁边没有别的东西 —— 收进顶栏既避免了误触，
 *    也不会让用户找不到。
 *
 * ## 复制为什么是文字按钮而不是图标
 *
 * 用的是 `CodeBlockCard` 那套：按下去文字变成「已复制」、两秒后变回来。
 * 比 Snackbar 好在**不依赖 Context、也不用按版本分叉** —— Android 13 起
 * 系统自己会弹一个「已复制」浮层，我们再弹一个就会看到两个叠在一起
 * （理由见 `ChatScreen.copyToClipboard` 的 KDoc）。而这个文字翻转在
 * 所有版本上都成立，且反馈就发生在手指按下的地方。
 */
@Composable
fun CrashLogScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: CrashLogViewModel = viewModel { CrashLogViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.clearedTick) {
        if (state.clearedTick > 0) snackbar.showSnackbar("已清除全部崩溃记录")
    }

    var confirmingClear by remember { mutableStateOf(false) }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("清除全部崩溃记录？") },
            text = {
                Text(
                    "这些堆栈删掉就找不回来了 —— 下次崩溃会从头开始记。" +
                        "如果还没把它们发给谁，先取消，逐条复制出来。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        viewModel.clear()
                    },
                ) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text("取消") }
            },
        )
    }

    PbScaffold(
        title = "崩溃记录",
        onBack = onBack,
        actions = {
            // 没有记录时不给这个按钮：一个按下去什么也不做的图标，
            // 比没有这个图标更让人困惑
            if (state.records.isNotEmpty()) {
                IconButton(onClick = { confirmingClear = true }) {
                    Icon(
                        PbIcons.DeleteSweep,
                        contentDescription = "清除全部",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { topInset ->
        when {
            state.loading -> {
                Box(
                    Modifier.fillMaxSize().padding(top = topInset),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.records.isEmpty() -> {
                PbEmptyState(
                    icon = PbIcons.Check,
                    title = "没有崩溃记录",
                    body = "App 崩溃时会把堆栈留在这里，方便你复制出来排查。" +
                        "现在一条都没有 —— 说明它还没崩过。",
                    modifier = Modifier.fillMaxSize().padding(top = topInset),
                )
            }

            else -> {
                val newest = state.records.first().epochMillis
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            start = Space.lg,
                            end = Space.lg,
                            // 顶栏浮在列表上方，第一条要自己避开它（见 PbScaffold 的 KDoc）
                            top = Space.md + topInset,
                            bottom = Space.xxl,
                        ),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    // 这一页满屏都是「出了事」的证据，所以第一张卡先回答
                    // 「这些东西会去哪」——它是这个 App 的立场，不是免责声明
                    item {
                        PbHintCard(
                            icon = PbIcons.Info,
                            title = "只存在这台设备上",
                            body = "崩溃记录不联网、不上传，也不含会话内容、密钥或账号。" +
                                "要给别人看，得你自己复制出去。",
                        )
                    }

                    items(state.records, key = { it.epochMillis }) { record ->
                        CrashCard(record = record, defaultExpanded = record.epochMillis == newest)
                    }
                }
            }
        }
    }
}

/**
 * 一条崩溃记录。
 *
 * 折叠时是「异常类 + 多久以前 + 复制 + 展开箭头」，展开后接上报告原文。
 *
 * ## 为什么整张卡都能点
 *
 * 展开箭头只有 18dp，靠它自己点太窄。整张卡可点之后，箭头从「按钮」变成
 * 「状态指示」—— 它说明当前是展开还是收起，而不是唯一能碰的地方。
 * 里面的「复制」按钮自己会吃掉点击，不会被这层连累。
 *
 * ## 展开状态为什么用 `rememberSaveable`
 *
 * 列表滚出屏幕之后 item 会被销毁重建，`remember` 存不住。存不住的表现是
 * 「展开一条、往下滚、再滚回来，它又折上了」—— 而这正是用户比对两份
 * 堆栈时会做的事。
 */
@Composable
private fun CrashCard(record: CrashRecord, defaultExpanded: Boolean) {
    var expanded by rememberSaveable(record.epochMillis) { mutableStateOf(defaultExpanded) }
    var copied by remember { mutableStateOf(false) }

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // 「已复制」两秒后自己变回去，否则用户以为复制坏了
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    PbCard(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = record.headLine,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    // 异常类名在开头，两行足够看出是什么异常；再长就该展开看了
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                // 头部给**相对**时间（「3 小时前」），报告正文里给绝对时间。
                // 前者用来回答「是不是刚才那次」，后者用来和日志比对
                Text(
                    text = formatTime(record.epochMillis),
                    style = MonoLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(Space.xs))
            TextButton(
                onClick = {
                    scope.launch {
                        // 复制的是**报告原文**，和文件里的字节一模一样 ——
                        // 它要能被粘进 issue，多一个字都算污染
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("崩溃记录", record.text)))
                        copied = true
                    }
                },
            ) {
                Text(
                    text = if (copied) "已复制" else "复制",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            // 没有 ExpandLess 图标：把 ExpandMore 转 180° 就是它。
            // 少抄一条路径数据，也少一个「两个图标哪天对不上」的机会
            Icon(
                PbIcons.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f),
            )
        }

        if (expanded) {
            Spacer(Modifier.height(Space.sm))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(Space.sm))
            // 等宽：堆栈是靠**列对齐**读的（异常类、方法、文件:行号），
            // 比例字体下每一行的 `(Foo.kt:42)` 会错开，扫起来很累
            Text(
                text = record.text,
                style = MonoTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
