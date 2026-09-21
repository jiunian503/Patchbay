package com.aichat.ui.plugins

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.di.AppContainer
import com.aichat.ui.common.PbButton
import com.aichat.ui.common.PbScaffold
import com.aichat.plugin.manifest.SettingType
import com.aichat.plugin.workspace.PluginWorkspace
import com.aichat.ui.common.PbOutlinedButton
import com.aichat.ui.conversations.formatTime

/**
 * 插件详情与配置。
 *
 * ## 密钥框为什么永远是空的
 *
 * 和 `ProviderEditScreen` 同一个约定：读出来的密钥不往界面状态里放。
 * 一旦放进 Compose 的 State，它就会进快照系统、可能被写进 savedState、
 * 在内存里多出好几份副本 —— 而这个字段存在的全部意义就是「少一份泄漏面」。
 *
 * 代价是用户看不到自己填的是什么。所以旁边有一个「清除」按钮：
 * 没有它的话，用户填错一个 Key 之后唯一的办法是覆盖一个新的，
 * 而「我不想用了、把它删掉」这件事做不到。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailScreen(
    container: AppContainer,
    pluginId: String,
    onBack: () -> Unit,
) {
    val viewModel: PluginDetailViewModel = viewModel(key = pluginId) {
        PluginDetailViewModel(container, pluginId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 插件没了（被卸载、或从安装页跳过来时 id 不对）就返回，不要停在一个空页面上
    LaunchedEffect(state.missing) { if (state.missing) onBack() }

    // 导入文件走 SAF（和安装插件页同一个做法）：Android 10 起拿不到别人的
    // 绝对路径，而且 SAF 给的是 content URI，用户从哪里选（下载、网盘、
    // 聊天记录）都行。MIME 不设过滤 —— 工作区可以放任何文件，插件读不读得懂
    // 是插件的事，宿主没道理在这里替它挑
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importFile) }

    PbScaffold(title = state.name.ifBlank { "插件" }, onBack = onBack) { topInset ->
        if (state.loading) {
            Box(
                Modifier.fillMaxSize().padding(top = topInset),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@PbScaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 滚动区域**内部**的内边距（在 `verticalScroll` 之后），
                // 这样滚动时内容会从顶栏背后经过（毛玻璃靠它）
                .padding(top = topInset)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Header(state, onToggle = viewModel::setEnabled)

            if (state.notices.isNotEmpty() || state.warnings.isNotEmpty()) {
                NoticeBlock(state.notices, state.warnings)
            }

            if (state.isMcp) {
                McpBlock(state.mcp, onRefresh = viewModel::refreshTools)
            }

            PermissionBlock(state.permissions, state.highRisk)

            // 紧跟在权限后面：上面那句「文件：可以读写自己的工作区」说的是
            // 「能做什么」，这块说的是「它存在这里的东西」—— 因果连着的
            state.workspace?.let { workspace ->
                WorkspaceBlock(
                    state = workspace,
                    onPick = { pickFile.launch(arrayOf("*/*")) },
                    onRemove = viewModel::removeFile,
                )
            }

            ToolBlock(
                state.tools,
                isMcp = state.isMcp,
                hasCache = state.mcp.connected,
                broken = state.isBroken,
            )

            if (state.fields.isNotEmpty()) {
                SettingsBlock(
                    fields = state.fields,
                    cleared = state.clearedSecrets,
                    onTextChange = viewModel::onTextChange,
                    onClearSecret = viewModel::clearSecret,
                )
                PbButton(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                    Text("保存设置")
                }
            }

            Spacer(Modifier.height(8.dp))
            DangerZone(
                pluginName = state.name,
                hasWorkspace = state.workspace != null,
                onUninstall = viewModel::uninstall,
            )
        }
    }
}

@Composable
private fun Header(state: PluginDetailUiState, onToggle: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${state.version} · ${state.runtimeLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.enabled, onCheckedChange = onToggle)
            }
            state.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            val meta = listOfNotNull(
                state.author?.let { "作者 $it" },
                state.license?.let { "许可 $it" },
                state.homepage,
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "插件标识 ${state.id}（安装后不可变更，权限授权认的是它）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoticeBlock(notices: List<String>, warnings: List<String>) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (notices.isNotEmpty()) {
                Text(
                    text = "这个插件当前不能工作",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            (notices + warnings).forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * MCP 工具清单的拉取。
 *
 * ## 为什么这块必须存在，而且必须显眼
 *
 * 声明式插件的工具是清单里写死的，装完就能用。MCP 插件的工具在**对端手里** ——
 * 本地只有一份缓存，而那份缓存只能在联网之后才有。没有这块界面的话，
 * 用户装完一个 MCP 插件会看到「提供的工具」是空的，然后合理地认为
 * 这个插件坏了或者自己装错了，而他唯一能做的就是卸载重装一遍。
 *
 * ## 为什么把「拉取时间」和「对端协议版本」也显示出来
 *
 * 因为这份清单**会过期**：对端改了工具名，本地这份不会自己更新。
 * 显示时间等于告诉用户「这份东西有多旧」；显示协议版本是给
 * 「这个 MCP 服务怪怪的」这类问题一个能拿去问对端作者的说法。
 */
@Composable
private fun McpBlock(status: McpStatus, onRefresh: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("MCP 工具清单", style = MaterialTheme.typography.titleSmall)

            Text(
                text = if (status.connected) {
                    "这份清单是从对端拉下来的，不是清单里写死的 —— 对端改了工具，" +
                        "要重新点一次下面这个按钮才会更新。"
                } else {
                    "还没有从对端拉取过工具清单（或者配置改过了），所以现在模型看不到它的工具。" +
                        "点下面这个按钮连一次。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (status.connected) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "上次拉取：${formatTime(status.fetchedAt ?: 0L)}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = "对端协议：${status.era ?: "未知"}" +
                        (status.protocolVersion?.let { "（$it）" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (status.dropped > 0) {
                        "对端报了 ${status.offered} 个工具，其中 ${status.dropped} 个没有名字、已经忽略"
                    } else {
                        "对端报了 ${status.offered} 个工具"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.dropped > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            PbButton(
                onClick = onRefresh,
                // 连接是一次发往第三方的网络请求，最慢能到 45 秒。
                // 不禁用的话用户会连点，而对端会看到同一个插件连着打了好几遍
                enabled = !status.connecting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (status.connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).width(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在连接…")
                } else {
                    Text(if (status.connected) "重新连接并刷新" else "连接并刷新工具列表")
                }
            }

            status.message?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                // 刷新失败时旧清单还在用。这句话不能省：用户看到红色报错
                // 之后会以为插件已经不能用了
                if (status.failed && status.connected) {
                    Text(
                        text = "上一次成功拉取的清单仍然有效，模型还能调用它里面的工具。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionBlock(permissions: List<String>, highRisk: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (highRisk) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("它能做什么", style = MaterialTheme.typography.titleSmall)
            if (permissions.isEmpty()) {
                Text("没有申请任何权限", style = MaterialTheme.typography.bodySmall)
            }
            permissions.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 工作区：插件存东西的地方，加上一个「把文件放进去」的入口。
 *
 * ## 为什么这块非有不可
 *
 * `host.fs` 只给了**插件**读写能力，用户没有任何入口把文件放进去 ——
 * 于是插件能读到的永远只有它自己写下的东西。示例 `csvstat` 要一份 CSV
 * 才能干活，没有这块的话它的 `path` 参数就是个死参数（清单里那句
 * 「工作区通常为空」是当时的诚实说法，有这块之后才不成立）。
 *
 * ## 为什么把「用量」摆在文件列表上面
 *
 * 工作区有配额（[PluginWorkspace.MAX_TOTAL_BYTES]），而配额本来只在**写失败**
 * 的时候才会被用户感知到。把「12 KB / 8 MB」放在上面，用户导之前就能
 * 判断放不放得下，而不是导到一半被告知不行。
 *
 * ## 为什么删除要确认
 *
 * 删除不可撤销，而误触的代价很具体：刚导入的一份 CSV 没了，得回去找到
 * 原件再导一次。确认框的文案也要说清「插件下次就看不到它了」——
 * 用户此刻需要知道的是这一条，而不是一句「已删除」。
 */
@Composable
private fun WorkspaceBlock(
    state: WorkspaceState,
    onPick: () -> Unit,
    onRemove: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("工作区", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "这个插件只能在这个目录里存东西，看不到别处。" +
                    "下面既是它自己留下的，也是你放进来给它的。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(6.dp))
            Text(
                text = "${state.usage.entries} 个文件 · ${formatBytes(state.usage.bytes)} / " +
                    formatBytes(PluginWorkspace.MAX_TOTAL_BYTES.toLong()),
                style = MaterialTheme.typography.labelSmall,
            )

            val shown = state.files.take(MAX_VISIBLE_FILES)
            if (shown.isEmpty()) {
                Text(
                    text = "还没有文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            shown.forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        // 路径原样显示（含子目录）—— 用户要照着它填工具参数，
                        // 比如 csvstat 的 path
                        Text(file.path, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = formatBytes(file.bytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { pendingDelete = file.path }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (state.files.size > shown.size) {
                // 不静默截断：没列出来的文件也要让用户知道它存在，
                // 否则他会以为工作区里就这 30 个
                Text(
                    text = "还有 ${state.files.size - shown.size} 个文件没有列出来" +
                        "（工作区最多 ${PluginWorkspace.MAX_ENTRIES} 个）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            PbOutlinedButton(
                onClick = onPick,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.busy) "处理中…" else "导入文件…")
            }

            state.message?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }

    pendingDelete?.let { path ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删掉「$path」？") },
            text = {
                Text(
                    "插件下次调用时就看不到它了。如果它是你导入的文件，" +
                        "本机还留着原件的话可以再导一次。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onRemove(path)
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
}

/** 文件列表最多列几行。超出的折成一句「还有 N 个」。 */
private const val MAX_VISIBLE_FILES = 30

@Composable
private fun ToolBlock(
    tools: List<PluginToolRow>,
    isMcp: Boolean,
    hasCache: Boolean,
    broken: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("提供的工具", style = MaterialTheme.typography.titleSmall)
            if (tools.isEmpty()) {
                Text(
                    // 空的原因决定了用户下一步该做什么，所以必须分开说 ——
                    // 三句话都在 [emptyToolsMessage] 里，那份能在 JVM 上测
                    text = emptyToolsMessage(broken, isMcp, hasCache),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tools.forEach { tool ->
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // 显示**实际生效**的策略，不是清单里写的那个值 ——
                    // 用户关心的是「它会不会问我」
                    text = if (tool.requiresConfirmation) "调用前会问你" else "调用前不会打扰你",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tool.requiresConfirmation) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun SettingsBlock(
    fields: List<SettingField>,
    cleared: Set<String>,
    onTextChange: (String, String) -> Unit,
    onClearSecret: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("设置", style = MaterialTheme.typography.titleSmall)
            fields.forEach { field ->
                Spacer(Modifier.height(8.dp))
                when (field.type) {
                    SettingType.Boolean -> BooleanField(field, onTextChange)

                    SettingType.Enum -> EnumField(field, onTextChange)

                    SettingType.String, SettingType.Number -> TextField(field, cleared, onTextChange, onClearSecret)
                }
                field.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BooleanField(field: SettingField, onTextChange: (String, String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(field.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = field.text.equals("true", ignoreCase = true),
            onCheckedChange = { onTextChange(field.key, it.toString()) },
        )
    }
}

@Composable
private fun EnumField(field: SettingField, onTextChange: (String, String) -> Unit) {
    Text(field.title, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        field.options.forEach { option ->
            FilterChip(
                selected = field.text == option,
                onClick = { onTextChange(field.key, option) },
                label = { Text(option, style = MaterialTheme.typography.labelSmall) },
                // 选中态的底色必须**显式指定**，不能吃 M3 的默认值。
                //
                // 默认值（`FilterChipTokens.FlatSelectedContainerColor`）是
                // `secondaryContainer` —— 而本 App 的浅色主题里它和
                // `surfaceVariant` 是**同一个值** `Ink100`（见 `theme/Theme.kt`），
                // 也就是这张 `SettingsBlock` 卡片自己的底色。
                //
                // 同时选中态的描边宽度是 **0dp**（`FlatSelectedOutlineWidth`），
                // 未选中态才是 1dp 描边。两者相加：**选中的那颗药丸整个消失**，
                // 只剩文字颜色从 `onSurfaceVariant` 变成 `onSecondaryContainer`。
                // 用户点了「fahrenheit」，看到的是选项毫无反应。
                //
                // 改用 `primaryContainer`：它是这个 App 里既有的「选中」色
                // （会话抽屉里的当前会话、用户消息气泡都用它），和 `Ink100`
                // 有色相差别，不靠明度也能分辨。
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun TextField(
    field: SettingField,
    cleared: Set<String>,
    onTextChange: (String, String) -> Unit,
    onClearSecret: (String) -> Unit,
) {
    OutlinedTextField(
        value = field.text,
        onValueChange = { onTextChange(field.key, it) },
        label = { Text(field.title) },
        singleLine = true,
        visualTransformation = if (field.secret) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        supportingText = if (field.secret) {
            {
                Text(
                    when {
                        field.key in cleared -> "保存后会清除这个值"
                        field.configured -> "已配置（出于安全不回显）。直接输入即可覆盖"
                        else -> "还没填。这个插件需要它才能工作"
                    },
                )
            }
        } else {
            null
        },
        trailingIcon = if (field.secret && field.configured && field.key !in cleared) {
            { TextButton(onClick = { onClearSecret(field.key) }) { Text("清除") } }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 卸载。
 *
 * 这个卡片自己写着「这一步不能撤销」，所以**那句话必须真的成立** ——
 * 点一次就执行的话，一次误触就能把它变成事实，那句话就成了一句装饰。
 * 而且这里删掉的不只是插件：用户手填的密钥会一起没，只能重装重填。
 *
 * ## 为什么要说清「工作区里的文件」
 *
 * `PluginRepository.uninstall` 会连工作区目录一起删（那是刻意的：用户决定
 * 卸载了，插件留下的东西就该清干净）。但工作区里可能有**用户自己导入的文件** ——
 * 那些不是插件产生的，用户未必想到它们会跟着走。文案不写这一句的话，
 * 「这一步不能撤销」就少说了一半。
 *
 * [hasWorkspace] 为 false 时不提工作区：没有那个目录的插件，
 * 说一句关于工作区的话只会让人去找它。
 *
 * 确认框里把插件名写出来。泛泛的「确定要卸载吗」在列表页可能还有歧义，
 * 这里虽然是从详情页进来的、名字就在上面，但把名字写进问句成本几乎为零，
 * 而误删一个已经配好的插件成本很高。
 */
@Composable
private fun DangerZone(pluginName: String, hasWorkspace: Boolean, onUninstall: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("卸载", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "卸载会删掉这个插件的配置，包括你填过的密钥" +
                    (if (hasWorkspace) "，以及工作区里的文件" else "") +
                    "。这一步不能撤销。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { confirming = true }) {
                Text("卸载插件", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("卸载「$pluginName」？") },
            text = {
                Text(
                    "它会从工具列表里消失，模型不能再调用它。" +
                        "你填过的配置和密钥会一起删掉" +
                        (if (hasWorkspace) "，工作区里的文件也会（包括你自己导入的那些）" else "") +
                        "，而且不能撤销 —— 要恢复只能重新装一遍、重新填。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onUninstall()
                    },
                ) {
                    Text("卸载", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("取消") }
            },
        )
    }
}
