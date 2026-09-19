package com.aichat.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.core.data.PluginStatus
import com.aichat.di.AppContainer
import com.aichat.plugin.manifest.displayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 列表里的一行。
 *
 * 刻意**只带界面需要的字段**，不把 `PluginStatus` / `PluginEntity` 漏给 UI ——
 * 和 `ProviderRow` 同一个理由：实体一进 Compose，界面就会开始依赖数据库的列，
 * 之后改表结构等于改界面。
 */
data class PluginRow(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val runtimeLabel: String,
    val toolCount: Int,
    val enabled: Boolean,

    /**
     * 清单现在解析不了。
     *
     * 这种插件**已经不在工具列表里了**（拿不到 `PluginManifest` 就没法装配），
     * 所以必须在界面上显式列出来 —— 否则用户看到的是「这个插件不见了」，
     * 没有任何可查的线索。
     */
    val isBroken: Boolean = false,

    /** 清单校验给出的警告（安装时也显示过，这里留个回看的地方）。 */
    val warnings: List<String> = emptyList(),

    /** 装配期的问题，比如「这个运行形态宿主还不支持」。 */
    val notices: List<String> = emptyList(),
) {
    val needsAttention: Boolean get() = isBroken || warnings.isNotEmpty() || notices.isNotEmpty()
}

data class PluginListUiState(
    val items: List<PluginRow> = emptyList(),

    /**
     * 被丢弃的插件工具（和内置工具重名、或和别的插件重名）。
     *
     * 这一条**必须显示**。工具被丢掉之后，模型和用户都看不到它，
     * 唯一能说明「为什么我装了插件却用不上」的地方就是这个列表。
     */
    val conflicts: List<String> = emptyList(),

    /** 当前模型能调用的工具总数（含内置）。 */
    val totalTools: Int = 0,

    val loading: Boolean = true,
)

class PluginListViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(PluginListUiState())
    val state: StateFlow<PluginListUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * 刷新。
     *
     * 这里顺带调 `container.tools.refresh()` 而不是只读数据库 ——
     * 用户在这个页面上做的事（启停、卸载）都会改变工具集合，
     * 而冲突列表只有在**重新合成之后**才是准的。
     *
     * 代价是进这个页面会重建一次注册表（解析清单 + 解密密钥）。
     * 几个插件、几毫秒，换来的是「界面上显示的就是模型实际拿到的」。
     */
    fun refresh() {
        viewModelScope.launch {
            val registry = container.tools.refresh()

            val items = container.plugins.statuses().map { status ->
                status.toRow(notices = registry.problemsOf(status.id).map { it.toString() })
            }

            _state.update {
                it.copy(
                    items = items,
                    conflicts = registry.conflicts.map { conflict -> conflict.describe() },
                    totalTools = registry.all.size,
                    loading = false,
                )
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            container.plugins.setEnabled(id, enabled)
            refresh()
        }
    }

    fun uninstall(id: String) {
        viewModelScope.launch {
            container.plugins.uninstall(id)
            refresh()
        }
    }
}

private fun PluginStatus.toRow(notices: List<String>): PluginRow {
    val manifest = manifest
    return PluginRow(
        id = id,
        // 清单坏掉时连名字都读不出来，退回显示 id —— 至少用户知道是哪个
        name = manifest?.name ?: id,
        version = manifest?.version ?: "?",
        description = manifest?.description,
        runtimeLabel = manifest?.runtime?.displayName ?: "清单无法解析",
        toolCount = manifest?.tools?.size ?: 0,
        enabled = entity.enabled,
        isBroken = isBroken,
        warnings = check?.warnings?.map { it.toString() }.orEmpty(),
        notices = notices,
    )
}
