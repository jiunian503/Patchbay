package com.aichat.ui.crash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.crash.CrashRecord
import com.aichat.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CrashLogUiState(
    val records: List<CrashRecord> = emptyList(),
    val loading: Boolean = true,

    /**
     * 每次「清除全部」成功就 +1，界面靠它弹一次提示。
     *
     * 不用「列表是不是空的」当判据：那样一进页面就会弹「已清除」——
     * 而那时什么都还没清。
     */
    val clearedTick: Int = 0,
)

/**
 * 崩溃记录页。
 *
 * ## 为什么每次操作都要 `withContext(Dispatchers.IO)`
 *
 * [com.aichat.crash.CrashStore] 是**同步的文件 I/O**（它的 KDoc 里写了为什么
 * 不能异步 —— 它是在崩溃线程上被调用的）。而 `viewModelScope` 默认跑在
 * 主线程上，直接在这里调它就是在主线程读磁盘。这里没有 Room、没有挂起接口
 * 帮忙切线程，得自己切。
 *
 * ## 为什么读全文
 *
 * [CrashRecord] 自带报告正文。份数与单份大小都有硬上限（5 × 64KB），
 * 所以一次性读回来是有界的；展开时才去读的话，得为「哪几条展开了」
 * 维护一套状态，而这一页一次最多五条。
 */
class CrashLogViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CrashLogUiState())
    val state: StateFlow<CrashLogUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val records = withContext(Dispatchers.IO) { container.crashes.list() }
            _state.update { it.copy(records = records, loading = false) }
        }
    }

    /**
     * 清掉全部记录。
     *
     * 清完**直接把界面清空**而不是重新读一遍：读回来必然还是空的，
     * 多一次磁盘往返只为了让界面晚一帧动。真的没删掉的话（比如文件被占），
     * 下次进这一页 `refresh()` 会把它们照实显示出来。
     */
    fun clear() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.crashes.clear() }
            _state.update { it.copy(records = emptyList(), clearedTick = it.clearedTick + 1) }
        }
    }
}
