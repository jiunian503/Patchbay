package com.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.core.data.displayName
import com.aichat.di.AppContainer
import com.aichat.settings.AppSettings
import com.aichat.tools.WebSearchBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
