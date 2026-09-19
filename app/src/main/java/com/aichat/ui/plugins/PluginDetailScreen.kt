package com.aichat.ui.plugins

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.aichat.ui.common.PbTopBar
import com.aichat.plugin.manifest.SettingType
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

    Scaffold(
        topBar = {
            PbTopBar(title = state.name.ifBlank { "插件" }, onBack = onBack)
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
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
            ToolBlock(state.tools, isMcp = state.isMcp, hasCache = state.mcp.connected)

            if (state.fields.isNotEmpty()) {
                SettingsBlock(
                    fields = state.fields,
                    cleared = state.clearedSecrets,
                    onTextChange = viewModel::onTextChange,
                    onClearSecret = viewModel::clearSecret,
                )
                Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                    Text("保存设置")
                }
            }

            Spacer(Modifier.height(8.dp))
            DangerZone(pluginName = state.name, onUninstall = viewModel::uninstall)
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
            Button(
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

@Composable
private fun ToolBlock(tools: List<PluginToolRow>, isMcp: Boolean, hasCache: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("提供的工具", style = MaterialTheme.typography.titleSmall)
            if (tools.isEmpty()) {
                Text(
                    // 空的原因决定了用户下一步该做什么，所以必须分开说。
                    // 一句笼统的「没有提供工具」会让 MCP 用户去卸载重装 ——
                    // 而真正该做的是点上面那个「连接并刷新」
                    text = when {
                        isMcp && !hasCache ->
                            "还没拉取过工具清单。点上面那个按钮连一次就能看到。"

                        isMcp ->
                            "对端这次一个工具都没提供。可能是它在服务端把工具关掉了，" +
                                "也可能是这个地址指向的不是工具端点。"

                        else ->
                            "这个插件没有提供工具（清单里 tools 是空的，或者运行形态还没实现）"
                    },
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
 * 确认框里把插件名写出来。泛泛的「确定要卸载吗」在列表页可能还有歧义，
 * 这里虽然是从详情页进来的、名字就在上面，但把名字写进问句成本几乎为零，
 * 而误删一个已经配好的插件成本很高。
 */
@Composable
private fun DangerZone(pluginName: String, onUninstall: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("卸载", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "卸载会删掉这个插件的配置，包括你填过的密钥。这一步不能撤销。",
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
                        "你填过的配置和密钥会一起删掉，而且不能撤销 —— " +
                        "要恢复只能重新装一遍、重新填。",
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
