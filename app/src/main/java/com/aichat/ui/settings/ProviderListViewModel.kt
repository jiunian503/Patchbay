package com.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.core.data.displayName
import com.aichat.di.AppContainer
import com.aichat.settings.AppSettings
import com.aichat.tools.WebSearchBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 列表里的一行。刻意只带界面需要的字段 —— 不要把 [ProviderEntity] 直接漏给 UI。 */
data class ProviderRow(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val keyHint: String?,
    val isDefault: Boolean,
) {
    /** 没有 keyHint 就等于没填过 Key。**不要**去读真实密钥来判断。 */
    val hasApiKey: Boolean get() = keyHint != null
}

/**
 * 设置页那一行「诊断」要用的东西。
 *
 * 刻意只带**条数和最新一条的时刻**，不带堆栈正文 —— 正文只有记录页要用，
 * 放在这里等于每次进设置页都把几十 KB 的文本读进内存。
 *
 * 整体可空：**一条记录都没有时那一行根本不显示**。放一个灰着的入口在那儿，
 * 用户点进去看到一页空白，只会以为这功能坏了。
 */
data class CrashSummary(val count: Int, val latestAt: Long)

data class ProviderListUiState(
    val items: List<ProviderRow> = emptyList(),
    val loading: Boolean = true,

    /** 模型能不能主动检索历史对话。见 [AppSettings.longTermMemory]。 */
    val longTermMemory: Boolean = AppSettings.DEFAULT_LONG_TERM_MEMORY,

    /**
     * 联网搜索后端的显示名。**空串表示关着。**
     *
     * 存的是给人看的名字而不是枚举：这张卡片只需要回答「开着还是关着、
     * 开的是哪个」—— 把枚举漏给 UI 会让「怎么显示」这件事散到界面层去。
     */
    val webSearchLabel: String = "",

    /** 崩溃记录摘要。**null = 一条都没有**，这时「诊断」那一节整个不出现。 */
    val crashes: CrashSummary? = null,
)

class ProviderListViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ProviderListUiState())
    val state: StateFlow<ProviderListUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val items = container.providers.list().map {
                ProviderRow(
                    id = it.id,
                    name = it.displayName,
                    baseUrl = it.baseUrl,
                    model = it.model,
                    keyHint = it.keyHint,
                    isDefault = it.isDefault,
                )
            }
            // 崩溃记录读的是**文件**（不是数据库），是阻塞调用 —— 必须挪到 IO 上。
            // viewModelScope 默认跑在主线程，直接调 list() 就是在主线程读磁盘
            val crashes = withContext(Dispatchers.IO) { container.crashes.list() }
            _state.update {
                it.copy(
                    items = items,
                    loading = false,
                    longTermMemory = container.settings.longTermMemory(),
                    // 读的是**同步**的 SharedPreferences，不是挂起接口 ——
                    // 这张卡片要在冷启动第一次组合时就显示对
                    webSearchLabel = WebSearchBackend
                        .fromId(container.settings.webSearchBackend())
                        ?.label
                        .orEmpty(),
                    // list() 已经是时间倒序，第一条就是最新那次崩溃
                    crashes = crashes.firstOrNull()
                        ?.let { newest ->
                            CrashSummary(count = crashes.size, latestAt = newest.epochMillis)
                        },
                )
            }
        }
    }

    fun setDefault(id: String) {
        viewModelScope.launch {
            container.providers.setDefault(id)
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            container.providers.delete(id)
            refresh()
        }
    }

    /**
     * 切换长期记忆。
     *
     * 界面**先乐观地动**，不等落盘完成 —— 一个开关按下去要等一次数据库往返
     * 才动，手感很差。写失败的可能性极低（写的是 SharedPreferences 的一个布尔），
     * 而真失败了 [refresh] 会把界面拉回真实值。
     */
    fun setLongTermMemory(enabled: Boolean) {
        _state.update { it.copy(longTermMemory = enabled) }
        viewModelScope.launch {
            container.setLongTermMemory(enabled)
            refresh()
        }
    }
}
