package com.aichat.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.di.AppContainer
import com.aichat.theme.Space
import com.aichat.ui.common.PbCard
import com.aichat.ui.common.PbIcons
import com.aichat.ui.common.PbScaffold

/**
 * 内置终端。
 *
 * ## 它是什么、不是什么
 *
 * 是**用户自己敲命令**的地方 —— 跑的是系统自带的 `sh`，命令也是系统自带的
 * （约 190 个，见 `TerminalViewModel` 的 KDoc 和 SKILL.md §100.8）。
 *
 * **不是**「内置了一个 Linux」。`apt install` 装不了、`curl`/`python` 系统里没有、
 * 也没有交互式终端（PTY 要 JNI）。
 *
 * ⚠️ **但这三条都不是「平台做不到」** —— 文案里别写成那样：
 *
 * - `apt` / 别的发行版：proot + rootfs 能跑（SKILL.md §105.4 记了参考实现），
 *   代价是 **67 MB 起** + 一个 JNI 模块 + 一个自己写的 VT 模拟器
 * - `curl` / `python`：改名成 `lib*.so` + `useLegacyPackaging` 就能 exec 自己的
 *   二进制（§105.3），代价是各带几 MB
 * - 交互式终端：要自己写 JNI（`forkpty`），是**工程成本**，不是禁令
 *
 * 所以界面上说的是「**这个 App 选了零体积那条路**」，而不是「装不进来」——
 * 把取舍说成禁令，等于替 Android 背一口它没欠的锅。
 * 但「`apt install` 现在跑不了」这件事仍然要说清楚：不说的话，用户敲一句
 * 只会以为 App 坏了。
 *
 * 也**不是**「让 AI 跑命令」那个能力：那是给模型的工具（`Tool` 那条线），
 * 这一页是给人用的。两者共用同一个机制，但入口和风险不同。
 *
 * ## 为什么是「一条一条跑」而不是一个黑框
 *
 * 见 `TerminalViewModel` 的 KDoc：没有 PTY 就没有真正的行编辑、光标定位和
 * `Ctrl+C`。硬做一个假的黑框，用户会以为 `vi` 能开 —— 然后在里面卡住。
 * 分成「命令 / 输出 / 退出码」三段反而**把真相摆出来了**：退出码不是 0
 * 的时候，用户一眼能看到是命令失败了还是权限不够。
 *
 * ## 快捷命令为什么要放在空状态里
 *
 * 用户第一次进来面对一个空输入框，最大的问题是**不知道能敲什么** ——
 * 因为「系统里有什么命令」这件事没有别的入口可看（`ls /system/bin` 是列不出来的，
 * 那是 DAC 751，见 §100.8 第三节）。所以空状态直接给几条能跑出结果的命令，
 * 点一下就填进输入框。
 */
@Composable
fun TerminalScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: TerminalViewModel = viewModel { TerminalViewModel(container.shell) }
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 新命令插进来、以及最后一条跑完，都要跟到底部 ——
    // 否则长输出之后用户得自己往下翻才能看到刚敲的那条的结果
    LaunchedEffect(entries.size, entries.lastOrNull()?.exitCode) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    PbScaffold(
        title = "终端",
        onBack = onBack,
        actions = {
            IconButton(
                onClick = viewModel::clear,
                enabled = entries.isNotEmpty() && !running,
            ) {
                Icon(PbIcons.DeleteSweep, contentDescription = "清空")
            }
        },
    ) { topInset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 键盘弹出来时输入行要跟着上移，否则被盖住
                .imePadding(),
        ) {
            if (entries.isEmpty()) {
                TerminalIntro(
                    modifier = Modifier.weight(1f),
                    topInset = topInset,
                    onPick = { input = it },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding =
                        PaddingValues(
                            start = Space.lg,
                            end = Space.lg,
                            top = Space.md + topInset,
                            bottom = Space.md,
                        ),
                    verticalArrangement = Arrangement.spacedBy(Space.lg),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        TerminalEntry(entry = entry, onReuse = { input = it })
                    }
                }
            }

            TerminalInputBar(
                value = input,
                onValueChange = { input = it },
                running = running,
                onRun = {
                    viewModel.run(input)
                    input = ""
                },
                onCancel = viewModel::cancel,
            )
        }
    }
}

/** 空状态：说清边界，再给几条真能跑出结果的命令。 */
@Composable
private fun TerminalIntro(
    modifier: Modifier,
    topInset: Dp,
    onPick: (String) -> Unit,
) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(
                    start = Space.lg,
                    end = Space.lg,
                    top = Space.md + topInset,
                    bottom = Space.md,
                ),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Text(
            text = "系统自带的 shell",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "命令以这个 App 的身份运行，能碰到的东西和 App 一样多。" +
                "系统里自带的约 190 个命令都能用，不需要下载任何东西。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "没有的东西：apt（装软件）、curl、python。不是装不进来，是这个 App 没装 —— " +
                "apt 要一整个 Linux 用户空间（67 MB 起），curl / python 也要各带几 MB 的二进制。" +
                "这一页选了零体积那条路。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "试试这些",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.sm),
        )
        SAMPLES.forEach { (command, description) ->
            PbCard(onClick = { onPick(command) }) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(
                        text = command,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 一条记录：命令 + 输出 + 状态。点命令能把它填回输入框。 */
@Composable
private fun TerminalEntry(
    entry: TerminalViewModel.Entry,
    onReuse: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onReuse(entry.command) }
                    .padding(vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text(
                text = "$",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = entry.command,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (entry.exitCode == null && !entry.timedOut) {
            Text(
                text = "运行中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        if (entry.output.isEmpty()) {
            Text(
                text = "（没有输出）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = entry.output,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (entry.truncated) {
            Text(
                text = "输出太长，后面截掉了。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            entry.timedOut ->
                Text(
                    text = "超过 15 秒，已停止。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            // 退出码 0 不用报 —— 那是常态，每条都写一遍只会变成噪音
            entry.exitCode != null && entry.exitCode != 0 ->
                Text(
                    text = "退出码 ${entry.exitCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
        }
    }
}

/** 底部输入行。跑着的时候「运行」换成「停止」。 */
@Composable
private fun TerminalInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = Space.lg,
                    end = Space.lg,
                    top = Space.sm,
                    bottom = Space.md,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            enabled = !running,
            singleLine = true,
            placeholder = { Text("输入命令") },
            // 命令一律等宽 —— 空格和对齐在输出里是有意义的
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions =
                KeyboardActions(
                    onSend = { if (!running && value.isNotBlank()) onRun() },
                ),
        )
        if (running) {
            IconButton(onClick = onCancel) {
                Icon(PbIcons.Stop, contentDescription = "停止")
            }
        } else {
            IconButton(onClick = onRun, enabled = value.isNotBlank()) {
                Icon(PbIcons.Send, contentDescription = "运行")
            }
        }
    }
}

/**
 * 空状态里的示例命令。
 *
 * 挑的标准是「**跑得出结果、而且结果是人想看的**」—— 一条会报错的命令
 * 摆在那里，用户点完只会以为功能坏了。所以 `apt`、`curl` 这类**故意不列**。
 */
private val SAMPLES =
    listOf(
        "ls /sdcard" to "共享存储里有什么",
        "ps -A | wc -l" to "现在有多少个进程",
        "df -h /data" to "存储还剩多少",
        "ip addr show" to "网络接口和地址",
        "getprop ro.build.version.release" to "这台设备的 Android 版本",
        "uname -a" to "内核版本",
    )
