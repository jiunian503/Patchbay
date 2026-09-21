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
     * 模型能不能在这台设备上跑系统命令。见 [AppSettings.shellEnabled]。
     *
     * 初值取的是常量而不是写死的 `false` —— 默认值改了这里跟着改，
     * 界面不会先渲染一帧错的。
     */
    val shellEnabled: Boolean = AppSettings.DEFAULT_SHELL_ENABLED,

    /**
     * 联网搜索后端的显示名。**空串表示关着。**
     *
     * 存的是给人看的名字而不是枚举：这张卡片只需要回答「开着还是关着、
     * 开的是哪个」—— 把枚举漏给 UI 会让「怎么显示」这件事散到界面层去。
     */
    val webSearchLabel: String = "",

    /** 崩溃记录摘要。**null = 一条都没有**，这时「诊断」那一节整个不出现。 */
    val crashes: CrashSummary? = null,

    /**
     * 「检查更新」那一行的状态。
     *
     * 初值是 [UpdateUiState.Idle]，`current` 由 [ProviderListViewModel.refresh]
     * 在 IO 线程上填一次 —— 读版本号要走一次 `PackageManager`，
     * 和 `crashes.list()` 一样是阻塞调用，不该在主线程上做。
     */
    val update: UpdateUiState = UpdateUiState.Idle(current = null),

    /**
     * 查出有新版本时是否弹对话框。
     *
     * 和 [update] **分开**：用户把框关掉之后，那一行仍然要显示「有新版本 v1.2」——
     * 结论不会因为他关了个框就失效。合成一个字段的话，关框就等于把结论也丢了，
     * 他再想看就得重新问一次远端。
     */
    val updateDialog: Boolean = false,

    /**
     * 角色卡数量。
     *
     * 只在「角色」那一行的说明里用。有角色和没有角色，那段话该说的东西
     * 完全不同：一个是「进去选一个」，另一个是「这东西是干什么的」。
     */
    val characterCount: Int = 0,
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
            // 版本号同理：读它要走一次 PackageManager（一个 binder 调用），
            // 和崩溃记录一起在 IO 上拿回来
            val version = withContext(Dispatchers.IO) { container.appVersionName }
            _state.update { previous ->
                previous.copy(
                    items = items,
                    loading = false,
                    longTermMemory = container.settings.longTermMemory(),
                    // 同上：同步读 SharedPreferences，这个开关也要在第一次组合时就是对的
                    shellEnabled = container.settings.shellEnabled(),
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
                    // 只数个数。这一行不需要角色名，读全表回来只为 size
                    // 是可接受的 —— 一张角色卡几十字节，用户也不会建几百个
                    characterCount = container.characters.list().size,
                    // 只在**还没查过**时填版本号。refresh() 也会被 setDefault /
                    // delete 调到，无条件覆盖的话，用户查出来的结论会被一次
                    // 无关的刷新冲掉 —— 表现为「刚查到有新版本，改了个默认服务商
                    // 就没了」
                    update = if (previous.update is UpdateUiState.Idle) {
                        UpdateUiState.Idle(current = version)
                    } else {
                        previous.update
                    },
                )
            }
        }
    }

    /**
     * 用户点了「检查更新」。
     *
     * ## 已经查过也能再查
     *
     * 不限「只查一次」—— 用户可能刚看完一个 Release 页面又回来点一次，
     * 或者上次是限流（那时候「过一会儿再试」就是**唯一**正确的动作）。
     *
     * ## 但正在查的时候要挡住
     *
     * 连点两下会发两个请求：GitHub 未认证的接口一小时只给 60 次，
     * 一次手抖花掉两次不划算，而且两个响应回来得看谁后到。
     * 界面上按钮也是灰的，这里再挡一道是防住「状态还没传下去的那一帧」。
     *
     * ## 不把版本号塞进状态里读
     *
     * 用的是 [AppContainer.appVersionName] 而不是 `state.update.current`：
     * 后者只在 `refresh()` 跑完之后才有值，而 `refresh()` 是异步的 ——
     * 冷启动时手快，用户可能在它落地之前就点到了按钮。
     */
    fun checkForUpdate() {
        if (_state.value.update is UpdateUiState.Checking) return
        _state.update { it.copy(update = UpdateUiState.Checking) }
        viewModelScope.launch {
            // 读版本号要走一次 PackageManager。AppContainer 那边是 by lazy，
            // 正常路径上 refresh() 已经读过了 —— 但挡不住「refresh 还没跑完」
            // 这个竞态，所以这里照旧挪到 IO 上，不赌它已经初始化过
            val version = withContext(Dispatchers.IO) { container.appVersionName }
            val lookup = container.releases.latest()
            val next = resolveUpdate(currentVersion = version, lookup = lookup)
            _state.update { previous ->
                previous.copy(
                    update = next,
                    // 只有「真的有新版」才自动弹框。查失败 / 无法判断 / 已是最新
                    // 都只更新那一行 —— 弹一个「检查失败」的框打断用户，
                    // 而结果其实已经写在那一行上了，等于让他多点一次「知道了」
                    updateDialog = next is UpdateUiState.Available,
                )
            }
        }
    }

    /**
     * 用户点了「检查更新」那一行。
     *
     * ## 已经查到有新版本时，点它应该**把框再弹出来**，而不是再查一次
     *
     * 那一行这时写着「有新版本 v1.2 —— 你现在用的是 1.1」，用户点它想要的是
     * 「去下载」，不是「再问一次」。再查一次有两个坏处：白花一次配额
     * （未认证的 GitHub 接口一小时 60 次），而且如果他上次把框关了、
     * 这次又只是想看看，那他要多等一次网络往返才看到同一句话。
     *
     * 分流放在 ViewModel 而不是界面里：界面只负责「这一行被点了」这件事，
     * 「点了该干什么」取决于状态 —— 那是逻辑，不是布局。
     */
    fun onUpdateRowClick() {
        if (_state.value.update is UpdateUiState.Available) {
            _state.update { it.copy(updateDialog = true) }
            return
        }
        checkForUpdate()
    }

    /** 用户把「有新版本」那个框关掉了。**只关框**，那一行的结论留着。 */
    fun dismissUpdateDialog() {
        _state.update { it.copy(updateDialog = false) }
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

    /**
     * 切换「让 AI 跑命令」。
     *
     * 和 [setLongTermMemory] 一样先乐观地动，但**这里的落盘更关键**：
     * 它决定 `run_command` 会不会被注册给模型。`container.setShellEnabled`
     * 里是「先写设置、再 refresh」，所以关掉之后**下一轮对话**模型就看不到
     * 那个工具了 —— 不是「等重启」。
     *
     * 反过来的方向也成立：打开之后模型立刻多一个工具，用户不用杀进程重开。
     */
    fun setShellEnabled(enabled: Boolean) {
        _state.update { it.copy(shellEnabled = enabled) }
        viewModelScope.launch {
            container.setShellEnabled(enabled)
            refresh()
        }
    }
}
