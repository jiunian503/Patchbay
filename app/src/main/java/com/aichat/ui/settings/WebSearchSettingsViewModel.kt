package com.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.di.AppContainer
import com.aichat.tools.WebSearchBackend
import com.aichat.tools.WebSearchConfig
import com.aichat.tools.defaultEndpoint
import com.aichat.tools.resolveEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 联网搜索设置页的状态。
 *
 * ## 密钥的三态
 *
 * [apiKeyInput] 永远是**空**开始（已存的密钥不回显），所以「用户没碰」和
 * 「用户想删掉」在界面上长得一样 —— 靠 [clearedKey] 区分。这和插件配置页
 * 是同一套处理（见 `PluginDetailViewModel`），不重复一遍理由。
 */
data class WebSearchUiState(
    /** null = 关着。 */
    val backend: WebSearchBackend? = null,

    val endpoint: String = "",

    /** 用户新输入的密钥。**不回显已存的那个。** */
    val apiKeyInput: String = "",

    /** KeyStore 里是否已经有一把密钥。 */
    val keyConfigured: Boolean = false,

    /** 用户点了「清除密钥」。 */
    val clearedKey: Boolean = false,

    val loading: Boolean = true,

    val saving: Boolean = false,

    /** 表单校验没过时的提示。非空时不该保存。 */
    val formError: String? = null,

    /** 递增一次 = 弹一次「已保存」。计数器而不是 Boolean，连续保存两次都能提示。 */
    val savedTick: Int = 0,

    val testing: Boolean = false,

    /** 「测试」的结果。null = 还没测过。 */
    val testMessage: String? = null,

    val testFailed: Boolean = false,
) {

    /** 这个后端要不要密钥。SearXNG 的实例大多不要。 */
    val keyRequired: Boolean
        get() = backend == WebSearchBackend.BRAVE || backend == WebSearchBackend.TAVILY
}

/**
 * 联网搜索设置。
 *
 * ## 保存是显式的，不是随打随存
 *
 * 三个字段里有一个是密钥。随打随存的话，用户打到一半（`tvly-` 还没打完）
 * 就已经被写进 KeyStore 并且工具已经重新装配 —— 期间如果模型刚好要搜，
 * 会拿着半截密钥去请求，报一个 401，而用户完全不知道发生了什么。
 * 显式保存让「写入」和「生效」都发生在用户点下去的那一刻。
 */
class WebSearchSettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(WebSearchUiState())
    val state: StateFlow<WebSearchUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val backend = WebSearchBackend.fromId(container.settings.webSearchBackend())
            val configured = container.webSearch.storedKey() != null
            _state.update {
                it.copy(
                    backend = backend,
                    // 没存过地址时给后端默认值，省得用户对着空框发呆。
                    // SearXNG 的默认是空串（没有公共实例），所以它会保持为空 ——
                    // 这正是我们要的：那个地址必须用户自己去挑
                    endpoint = container.settings.webSearchEndpoint()
                        .ifBlank { backend?.let(::defaultEndpoint).orEmpty() },
                    apiKeyInput = "",
                    keyConfigured = configured,
                    clearedKey = false,
                    loading = false,
                )
            }
        }
    }

    /**
     * 换后端。
     *
     * 地址会**跟着换**，但只在两种情况：当前是空的，或者当前正好等于
     * 上一个后端的默认地址。用户自己填过的地址不能被覆盖 ——
     * 那往往是他折腾很久才找到的一个能用的实例。
     */
    fun selectBackend(backend: WebSearchBackend?) {
        _state.update { state ->
            val previous = state.backend
            val wasDefaultOrBlank =
                state.endpoint.isBlank() ||
                    (previous != null && state.endpoint.trim() == defaultEndpoint(previous))
            state.copy(
                backend = backend,
                endpoint = if (wasDefaultOrBlank) backend?.let(::defaultEndpoint).orEmpty() else state.endpoint,
                // 换了后端，上一次的测试结果就没有意义了
                testMessage = null,
                formError = null,
            )
        }
    }

    fun onEndpointChange(value: String) {
        _state.update { it.copy(endpoint = value, formError = null, testMessage = null) }
    }

    fun onKeyChange(value: String) {
        _state.update {
            it.copy(
                apiKeyInput = value,
                // 用户重新输入了值，之前那次「清除」就不算数了
                clearedKey = false,
                formError = null,
                testMessage = null,
            )
        }
    }

    fun clearKey() {
        _state.update {
            it.copy(apiKeyInput = "", clearedKey = true, formError = null, testMessage = null)
        }
    }

    fun save() {
        val state = _state.value
        val backend = state.backend

        validate(state)?.let { message ->
            _state.update { it.copy(formError = message) }
            return
        }

        _state.update { it.copy(saving = true, formError = null) }
        viewModelScope.launch {
            container.saveWebSearch(
                backend = backend,
                endpoint = state.endpoint,
                // 三态：null = 不动已存的；空串 = 清除；有值 = 写入
                apiKey = when {
                    state.clearedKey -> ""
                    state.apiKeyInput.isBlank() -> null
                    else -> state.apiKeyInput
                },
            )
            _state.update { it.copy(saving = false, savedTick = it.savedTick + 1) }
            // 重新读一遍真实状态，而不是手写 fields —— 手写等于把读取逻辑抄一遍，
            // 两份实现迟早会不一致（而且第一份必然是错的）
            load()
        }
    }

    /**
     * 「测试」按钮。
     *
     * 用**表单里的值**而不是已保存的值：用户刚填完地址和密钥、还没保存，
     * 这时候最需要知道「这样填对不对」。要测已保存的值，先保存就是了。
     *
     * 密钥的取值顺序：用户新输入的 → KeyStore 里已存的。用户没重新输入
     * 密钥是常态（他可能只是改了地址），那时候必须用已存的那把。
     */
    fun test() {
        val state = _state.value
        validate(state)?.let { message ->
            _state.update { it.copy(formError = message) }
            return
        }

        _state.update { it.copy(testing = true, formError = null, testMessage = null) }
        viewModelScope.launch {
            val backend = requireNotNull(state.backend)
            val key = when {
                state.clearedKey -> null
                state.apiKeyInput.isNotBlank() -> state.apiKeyInput
                else -> container.webSearch.storedKey()
            }

            val result = container.probeWebSearch(WebSearchConfig(backend, state.endpoint, key))

            _state.update {
                it.copy(
                    testing = false,
                    testMessage = result.content,
                    testFailed = result.isError,
                )
            }
        }
    }

    /**
     * 表单校验。返回非 null 表示不能保存。
     *
     * ## 为什么密钥在这里是硬性的
     *
     * 工具**在不在**模型的工具列表里，取决于 `WebSearchSource.configured()`
     * （同步、查不了密钥）。如果允许「选了 Brave 但没填密钥」保存成功，
     * 工具会被注册，然后每次调用都报错 —— 用户看到的是「搜索老是失败」，
     * 而不是「我没填密钥」。
     *
     * 在这里拦住之后，`configured() == true` 就意味着「保存时是完整的」，
     * 唯一还能出现「注册了但没密钥」的情况是设备恢复出厂把 KeyStore 清空，
     * 而那种情况执行时的报错正好指向这里。
     */
    private fun validate(state: WebSearchUiState): String? {
        val backend = state.backend ?: return null // 关闭：随时可以保存

        val config = WebSearchConfig(backend, state.endpoint)
        if (config.resolveEndpoint() == null) {
            return if (backend == WebSearchBackend.SEARXNG) {
                "要填一个 SearXNG 实例的地址，例如 https://searx.be。"
            } else {
                "地址不是合法的 http/https 链接，检查一下。"
            }
        }

        val hasKey = state.apiKeyInput.isNotBlank() ||
            (state.keyConfigured && !state.clearedKey)
        if (state.keyRequired && !hasKey) {
            return "${backend.label} 需要 API Key。"
        }

        return null
    }
}
