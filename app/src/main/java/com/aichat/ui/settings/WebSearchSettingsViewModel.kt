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

    /** 当前这个后端在 KeyStore 里是否已经有一把密钥。 */
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

    /** 这个后端要不要密钥。内置的和 SearXNG 都不要。 */
    val keyRequired: Boolean
        get() = backend == WebSearchBackend.BRAVE || backend == WebSearchBackend.TAVILY

    /**
     * 换后端之后的状态。
     *
     * 抽成纯函数是为了能直接断言 —— 原来这段逻辑写在
     * [WebSearchSettingsViewModel.selectBackend] 里，而那个类要一个真的
     * `AppContainer`（Room、KeyStore、一堆 Android 依赖）才构造得出来。
     *
     * ## 地址**无条件**跟着新后端走
     *
     * 原来的写法是「当前地址是空的、或者正好等于上一个后端的默认地址，才换」，
     * 本意是保护用户折腾很久才找到的那个 SearXNG 实例地址。那个考虑没错，
     * 但它漏了一种情况：**上一个后端是 null**（关闭状态）。
     *
     * 「关闭 → 打开」正是**新用户第一次开联网搜索的唯一路径**，而那一刻地址框里
     * 留着的是上个时代的值（真机上抓到的是 `http://127.0.0.1:8767`，
     * 上一次折腾 SearXNG 留下的）。用户按提示选了「不用配置、装上就能用」的
     * 内置后端，保存之后搜索必然失败 —— 而失败信息（连不上一个莫名其妙的地址）
     * 完全指不出真正的原因。
     *
     * 所以这里不再判断，地址一律换成新后端的默认值。被放弃的是「跨后端保留
     * 用户手填的地址」：地址**属于**某个后端（Brave 的地址填给 Tavily 毫无意义），
     * 换后端本来就该重来。
     *
     * [keyConfigured] 先按 `false` 算，真实值由
     * [WebSearchSettingsViewModel.selectBackend] 异步补上（要读 KeyStore）。
     * 先按 `false` 是有意的：短暂显示「需要填密钥」会让人再确认一眼，
     * 短暂显示「已保存一个密钥」会让人直接点保存。
     */
    fun afterBackendChange(next: WebSearchBackend?): WebSearchUiState = copy(
        backend = next,
        endpoint = next?.let(::defaultEndpoint).orEmpty(),
        apiKeyInput = "",
        clearedKey = false,
        keyConfigured = false,
        testMessage = null,
        formError = null,
    )
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
            // 密钥是按后端分开存的，所以这里必须带上后端问
            val configured = container.webSearch.storedKey(backend) != null
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
     * 地址、密钥输入框、上一次的测试结果都跟着清（逐条理由见
     * [WebSearchUiState.afterBackendChange]）。
     *
     * 还要**重新读一次 KeyStore**：`keyConfigured` 是**按后端分类**的值，
     * 而它原来只在 [load] 里算过一次 —— 于是切到「从没配过」的后端时，
     * 界面照样显示「已保存一个密钥」，用户直接点保存，然后搜索失败。
     * 这是真机上抓到的第二层 bug，编译器和单测都看不见它：
     * **改了读取逻辑，不等于改了触发时机。**
     */
    fun selectBackend(backend: WebSearchBackend?) {
        _state.update { it.afterBackendChange(backend) }
        viewModelScope.launch {
            val configured = container.webSearch.storedKey(backend) != null
            // 只认「还是不是当前这个后端」：连点 A → B → A 时，
            // B 那次的结果可能最后才到，不能让它覆盖 A 的
            _state.update { if (it.backend == backend) it.copy(keyConfigured = configured) else it }
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
     * 密钥是常态（他可能只是改了地址），那时候必须用已存的那把 ——
     * 而且必须是**当前这个后端**的那把。
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
                else -> container.webSearch.storedKey(backend)
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
