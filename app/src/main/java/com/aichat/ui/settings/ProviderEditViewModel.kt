package com.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.core.data.ProviderDraft
import com.aichat.di.AppContainer
import com.aichat.network.ProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 服务商编辑表单。
 *
 * ## 密钥框为什么永远从空开始
 *
 * 密钥**不回显**，所以编辑已有服务商时输入框是空的。这带来一个必须显式建模的
 * 问题：「空」到底是「不改」还是「清空」？答案是前者 —— 用户只改了超时时间
 * 却把 Key 抹掉，是最容易发生也最难自查的事故。
 *
 * 所以表单里有三个独立的量：
 *
 * - [UiState.apiKeyInput] 用户新输入的密钥
 * - [UiState.clearKey] 用户**显式**点了「清除密钥」
 * - [UiState.storedKeyHint] 已存密钥的尾部提示（非空 = 库里有一个）
 *
 * 提交时按「清除 > 新输入 > 保持」的优先级合成 `ProviderDraft.apiKey` 的三态。
 */
class ProviderEditViewModel(
    private val container: AppContainer,
    private val providerId: String?,
) : ViewModel() {

    data class UiState(
        val isNew: Boolean = true,
        val name: String = "",
        val baseUrl: String = "",
        val model: String = "",
        val apiKeyInput: String = "",
        val storedKeyHint: String? = null,
        val clearKey: Boolean = false,
        val includeUsage: Boolean = false,
        val makeDefault: Boolean = false,
        /**
         * 三个生成参数**以原始文本形态持有**，而不是解析后的值。
         *
         * 直接存 `Double?` 的话，用户打到一半的「0.」会被解析成 0.0 再回填成
         * 「0.0」—— 光标跳、文字自己变，没法正常输入小数。
         * 解析推迟到 [temperature] / [maxTokens] 这两个派生属性里。
         */
        val systemPrompt: String = "",
        val temperatureInput: String = "",
        val maxTokensInput: String = "",
        val loading: Boolean = true,
        val saving: Boolean = false,
        val error: String? = null,
        val done: Boolean = false,
    ) {
        /** 留空 = `null` = **请求里不发这个字段**，用服务端自己的默认值。 */
        val temperature: Double?
            get() = temperatureInput.trim().takeIf { it.isNotEmpty() }
                ?.toDoubleOrNull()
                // `"Infinity".toDoubleOrNull()` 是能解析成功的，但它发出去
                // 服务端一定报错。当成「填错了」处理
                ?.takeIf { it.isFinite() }

        val maxTokens: Int?
            get() = maxTokensInput.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()

        /**
         * 填了东西但解析不出来。**这种时候不能让保存通过** ——
         * 否则用户填了个「abc」，界面照常保存成功，参数却悄悄没生效。
         */
        val numberError: String?
            get() = when {
                temperatureInput.isNotBlank() && temperature == null -> "温度要填数字，例如 0.7"
                maxTokensInput.isNotBlank() && maxTokens == null -> "最大回复长度要填整数，例如 4096"
                else -> null
            }

        val canSave: Boolean
            get() = baseUrl.isNotBlank() && model.isNotBlank() &&
                !saving && numberError == null

        /** 实时预览实际会请求的地址 —— 用户填错 baseUrl 时这里一眼能看出来。 */
        val previewUrl: String
            get() = if (baseUrl.isBlank()) {
                ""
            } else {
                runCatching { ProviderConfig.chatCompletionsUrl(baseUrl) }.getOrDefault("")
            }

        val keyActionLabel: String
            get() = when {
                clearKey -> "保存后清除密钥"
                apiKeyInput.isNotBlank() -> "保存后替换密钥"
                storedKeyHint != null -> "保持现有密钥（$storedKeyHint）"
                else -> "还没有填密钥"
            }
    }

    private val _state = MutableStateFlow(UiState(isNew = providerId == null))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (providerId == null) {
            _state.update { it.copy(loading = false) }
            return
        }
        val entity = container.providers.get(providerId)
        _state.update {
            it.copy(
                loading = false,
                isNew = false,
                name = entity?.name.orEmpty(),
                baseUrl = entity?.baseUrl.orEmpty(),
                model = entity?.model.orEmpty(),
                storedKeyHint = entity?.keyHint,
                systemPrompt = entity?.systemPrompt.orEmpty(),
                temperatureInput = entity?.temperature?.let(::formatNumber).orEmpty(),
                maxTokensInput = entity?.maxTokens?.toString().orEmpty(),
                includeUsage = entity?.includeUsage ?: false,
                makeDefault = entity?.isDefault ?: false,
            )
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value) }

    fun onBaseUrlChange(value: String) = _state.update { it.copy(baseUrl = value) }

    fun onModelChange(value: String) = _state.update { it.copy(model = value) }

    fun onSystemPromptChange(value: String) = _state.update { it.copy(systemPrompt = value) }

    fun onTemperatureChange(value: String) = _state.update { it.copy(temperatureInput = value) }

    fun onMaxTokensChange(value: String) = _state.update { it.copy(maxTokensInput = value) }

    /** 一开始输入就自动取消「清除密钥」—— 用户显然是想换成新的那把。 */
    fun onApiKeyChange(value: String) = _state.update {
        it.copy(apiKeyInput = value, clearKey = if (value.isNotEmpty()) false else it.clearKey)
    }

    fun onToggleClearKey() = _state.update {
        it.copy(clearKey = !it.clearKey, apiKeyInput = if (it.clearKey) it.apiKeyInput else "")
    }

    fun onToggleIncludeUsage() = _state.update { it.copy(includeUsage = !it.includeUsage) }

    fun onToggleMakeDefault() = _state.update { it.copy(makeDefault = !it.makeDefault) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun save() {
        val current = _state.value
        if (!current.canSave) return

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                container.providers.save(
                    ProviderDraft(
                        id = providerId,
                        name = current.name,
                        baseUrl = current.baseUrl,
                        model = current.model,
                        // 留空就是 null，也就是「不发这个字段」—— 没有第三种状态
                        temperature = current.temperature,
                        maxTokens = current.maxTokens,
                        systemPrompt = current.systemPrompt,
                        // 三态合成，见类注释
                        apiKey = when {
                            current.clearKey -> ""
                            current.apiKeyInput.isNotBlank() -> current.apiKeyInput
                            else -> null
                        },
                        includeUsage = current.includeUsage,
                        makeDefault = current.makeDefault,
                    )
                )
                _state.update { it.copy(saving = false, done = true) }
            } catch (t: Throwable) {
                _state.update { it.copy(saving = false, error = t.message ?: "保存失败") }
            }
        }
    }
}

/**
 * 把存下来的温度回显成用户当初填的样子。
 *
 * `1.0.toString()` 是 `"1.0"`，但用户填的多半是 `1` —— 回显成 `1.0` 会让人
 * 以为这个值被改过。取最短形式，两边都能解析成同一个数。
 */
private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
