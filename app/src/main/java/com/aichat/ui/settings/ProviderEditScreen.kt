package com.aichat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.di.AppContainer
import com.aichat.ui.common.PbTonalButton
import com.aichat.ui.common.PbScaffold

/** 添加 / 编辑服务商。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(
    container: AppContainer,
    providerId: String?,
    onBack: () -> Unit,
) {
    val viewModel: ProviderEditViewModel = viewModel(key = "provider-${providerId ?: "new"}") {
        ProviderEditViewModel(container, providerId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 保存成功就退回列表。放在 LaunchedEffect 里而不是在点击回调里直接跳，
    // 是因为跳转要等真正的写库完成 —— 失败时不能跳走
    LaunchedEffect(state.done) {
        if (state.done) onBack()
    }

    PbScaffold(
        title = if (state.isNew) "添加服务商" else "编辑服务商",
        onBack = onBack,
    ) { topInset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 这两个 padding 都在 `verticalScroll` **之后** —— 它们是滚动区域
                // **内部**的内边距，所以滚动时表单照样会从顶栏背后经过（毛玻璃靠它）。
                // 放在 `verticalScroll` 之前就成了「把滚动区整个推下去」，顶栏背后
                // 永远是空的。
                .padding(top = topInset)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("名称（可留空）") },
                placeholder = { Text("例如：深度求索") },
                supportingText = { Text("留空时列表里显示接口域名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = { Text("接口地址") },
                placeholder = { Text("api.deepseek.com") },
                supportingText = {
                    if (state.previewUrl.isNotBlank()) {
                        Text("实际请求：${state.previewUrl}")
                    } else {
                        Text("填域名即可，会自动补 https 和 /v1；自建网关带路径前缀时原样使用")
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.model,
                onValueChange = viewModel::onModelChange,
                label = { Text("模型名") },
                placeholder = { Text("deepseek-chat") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))

            Text("生成参数", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "留空就用服务端默认值 —— 我们不替你填，因为各家默认并不相同。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.systemPrompt,
                onValueChange = viewModel::onSystemPromptChange,
                label = { Text("系统提示词（可留空）") },
                placeholder = { Text("例如：你是简洁的助手，回答不超过三句话。") },
                // 「所有会话都生效」这句必须写：不写的话用户会以为只影响当前会话
                supportingText = { Text("用这个服务商的所有会话都生效。留空则不发送系统消息") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.temperatureInput,
                onValueChange = viewModel::onTemperatureChange,
                label = { Text("温度（可留空）") },
                placeholder = { Text("0.7") },
                supportingText = { Text("越大越随机。留空 = 请求里不发送这个字段") },
                isError = state.temperatureInput.isNotBlank() && state.temperature == null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.maxTokensInput,
                onValueChange = viewModel::onMaxTokensChange,
                label = { Text("最大回复长度（可留空）") },
                placeholder = { Text("4096") },
                supportingText = { Text("单次回复的 token 上限。留空 = 请求里不发送这个字段") },
                isError = state.maxTokensInput.isNotBlank() && state.maxTokens == null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // 填了但解析不出来时要挡在保存之前，否则参数悄悄不生效而界面报成功
            state.numberError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = state.apiKeyInput,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("API Key") },
                singleLine = true,
                // 密钥框用密码样式：不是为了防谁，而是避免在公共场合被旁人看到
                visualTransformation = PasswordVisualTransformation(),
                enabled = !state.clearKey,
                supportingText = { Text(state.keyActionLabel) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.storedKeyHint != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.clearKey, onCheckedChange = { viewModel.onToggleClearKey() })
                    Text("删除已保存的密钥", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.makeDefault,
                    onCheckedChange = { viewModel.onToggleMakeDefault() },
                )
                Text("设为默认服务商", style = MaterialTheme.typography.bodyMedium)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.includeUsage,
                    onCheckedChange = { viewModel.onToggleIncludeUsage() },
                )
                Column {
                    Text("请求 token 用量统计", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "默认关闭。部分第三方网关不认这个字段，开了会直接 400",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.error?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            PbTonalButton(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.saving) "保存中…" else "保存")
            }

            if (state.isNew) {
                Text(
                    text = "第一个保存的服务商会自动成为默认。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
