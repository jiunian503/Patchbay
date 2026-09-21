package com.aichat.ui.plugins

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.core.data.PluginSettingsDraft
import com.aichat.di.AppContainer
import com.aichat.plugin.host.PluginRegistry
import com.aichat.plugin.manifest.FilesystemScope
import com.aichat.plugin.manifest.PluginManifest
import com.aichat.plugin.manifest.SettingType
import com.aichat.plugin.manifest.describe
import com.aichat.plugin.manifest.displayName
import com.aichat.plugin.manifest.isHighRisk
import com.aichat.plugin.runtime.mcp.McpToolSnapshot
import com.aichat.plugin.runtime.mcp.displayName
import com.aichat.plugin.workspace.PluginWorkspace
import com.aichat.plugin.workspace.WorkspaceException
import com.aichat.plugin.workspace.WorkspaceFile
import com.aichat.plugin.workspace.WorkspaceUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/** 一个可编辑的配置项。 */
data class SettingField(
    val key: String,
    val title: String,
    val description: String?,
    val type: SettingType,
    val options: List<String>,

    /** 敏感项。界面把它渲染成密码框，且**永远不回显值**。 */
    val secret: Boolean,

    /** 非敏感项的当前值。敏感项恒为空字符串。 */
    val text: String,

    /** 敏感项是否已经填过。界面据此显示「已配置」而不是空白。 */
    val configured: Boolean,
)

/** 详情页里的一个工具。 */
data class PluginToolRow(
    val name: String,
    val description: String,

    /**
     * **实际生效**的确认策略，不是清单里写的那个值。
     *
     * 清单里不写 `requiresConfirmation` 时，宿主会按 HTTP 方法兜底
     * （非 GET 一律确认）。界面上要显示的是这个结果 —— 用户关心的是
     * 「它会不会问我」，而不是「作者有没有声明」。
     *
     * MCP 工具的这条规则不一样（`readOnlyHint` 只能免掉确认，
     * 声明了任意主机时一律确认），但**这里不需要知道**：
     * 装配好的 [com.aichat.domain.tool.Tool] 已经算好了，直接读它的。
     */
    val requiresConfirmation: Boolean,
)

/**
 * MCP 工具清单的状态。
 *
 * 单独一块状态、而不是塞进 [PluginDetailUiState] 的几个字段，是因为
 * 「MCP 插件」和「声明式插件」在详情页上要展示的东西本来就不一样：
 * 前者有一份**会过期的**快照（拉取时间、对端协议版本、丢掉了几个），
 * 后者没有。合成一个类型的话，声明式插件的那几个字段永远是空的，
 * 而「永远是空的字段」迟早会被人拿去当条件用。
 */
data class McpStatus(
    /** 有没有可用的工具清单。false = 还没连过，或者配置改过了。 */
    val connected: Boolean = false,

    /** 上次拉取时刻。null = 没有缓存。 */
    val fetchedAt: Long? = null,

    /** 对端属于哪一代协议，人话说法。 */
    val era: String? = null,

    val protocolVersion: String? = null,

    /** 对端一共报了几个工具。 */
    val offered: Int = 0,

    /** 因为没名字被丢掉了几个。 */
    val dropped: Int = 0,

    /** 正在连接。界面据此禁用按钮并转圈。 */
    val connecting: Boolean = false,

    /** 上次连接的结果（成功或失败都写在这里）。 */
    val message: String? = null,
    val failed: Boolean = false,
)

/**
 * 插件工作区的状态。
 *
 * ## 「工作区是空的」和「不显示这块」是两回事
 *
 * [PluginDetailUiState.workspace] 为 `null` 表示**这块界面不该出现**，
 * 而不是「里面没有文件」。两种情况下是 null：
 *
 * - 插件没声明 `filesystem` 权限 —— 它拿不到工作区，给用户一个导入入口
 *   只会把文件导进一个谁也读不到的地方
 * - 插件是 MCP 形态 —— 它的工具在对端手里，本地这份工作区没有消费者。
 *   这和 [McpStatus] 那块显示不显示是同一个判断：**没有读者的入口比没有入口更糟**
 *
 * 「空的工作区」是另一回事：那时候这个字段不是 null，只是 [files] 是空的，
 * 界面该显示「还没有文件」加导入按钮。
 */
data class WorkspaceState(
    val files: List<WorkspaceFile> = emptyList(),
    val usage: WorkspaceUsage = WorkspaceUsage(0, 0L),

    /** 正在导入或删除。界面据此禁用按钮 —— 一次导入要读几 MB 进内存。 */
    val busy: Boolean = false,

    /** 上一次操作的结果。成功和失败都写这里。 */
    val message: String? = null,
    val failed: Boolean = false,
)

data class PluginDetailUiState(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val description: String? = null,
    val author: String? = null,
    val license: String? = null,
    val homepage: String? = null,
    val runtimeLabel: String = "",
    val enabled: Boolean = true,

    /**
     * 这个插件是 MCP 形态。
     *
     * 界面据此决定要不要显示「连接并刷新工具列表」那块 —— 声明式插件的
     * 工具是清单里写死的，没有「连接」这个动作，显示一个永远点不出东西的
     * 按钮比不显示更糟。
     */
    val isMcp: Boolean = false,

    val mcp: McpStatus = McpStatus(),

    /** 见 [WorkspaceState]。`null` = 这块界面不显示。 */
    val workspace: WorkspaceState? = null,

    val tools: List<PluginToolRow> = emptyList(),
    val permissions: List<String> = emptyList(),
    val highRisk: Boolean = false,

    /**
     * 清单现在解析不了。
     *
     * 和列表页的 [PluginRow.isBroken] 是同一个信号（都来自
     * `PluginStatus.isBroken`），但详情页非有它不可：**有两块内容会因此变成
     * 空的** —— 它申请了什么权限、它提供了哪些工具，两样都读不出来。
     * 界面拿不到这个信号，就会把「读不出来」说成「没有」，而这两句话都
     * 出现在用户判断「要不要继续用它」的那一刻。
     *
     * ⚠️ 和 [missing] 不是一回事：`missing` 是插件没了（要返回列表），
     * 这个是插件还在、只是清单读不出来（列表页会把它列出来并说明原因）。
     */
    val isBroken: Boolean = false,

    val warnings: List<String> = emptyList(),
    val notices: List<String> = emptyList(),

    val fields: List<SettingField> = emptyList(),

    /**
     * 用户点了「清除」的敏感项。
     *
     * ## 为什么需要一个单独的集合
     *
     * 敏感项的输入框永远是空的（不回显密钥），所以「用户没碰」和
     * 「用户想清掉」在框里长得一模一样。没有这个集合的话，
     * 用户永远删不掉一个填错的 Key —— 他只能重新填一个新的。
     */
    val clearedSecrets: Set<String> = emptySet(),

    val loading: Boolean = true,

    /** 递增一次 = 弹一次「已保存」。用计数器而不是 Boolean，是为了连续保存两次都能提示。 */
    val savedTick: Int = 0,

    /** 插件不存在了（被卸载、或 id 拼错）。界面据此返回列表。 */
    val missing: Boolean = false,
)

class PluginDetailViewModel(
    private val container: AppContainer,
    private val pluginId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(PluginDetailUiState(id = pluginId))
    val state: StateFlow<PluginDetailUiState> = _state.asStateFlow()

    /**
     * 自动连接试过了。
     *
     * 是一个**只进不出**的标记，不是「当前是否在连接」—— 后者由
     * [McpStatus.connecting] 表达。分开的理由：这个标记要防的是
     * 「反复自动重试」，而 `connecting` 在每次连接结束后都会回到 false，
     * 拿它当闸门等于每次 `load()` 都会重新自动连一次。
     */
    private var autoConnectTried = false

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val status = container.plugins.status(pluginId)
            if (status == null) {
                _state.update { it.copy(loading = false, missing = true) }
                return@launch
            }

            val manifest = status.manifest
            val view = container.plugins.settingsView(pluginId)
            val registry = container.tools.registry
            val cache = status.mcpCache
            val anyHost = manifest?.permissions?.network?.contains("*") == true

            // 工作区只在两种情况下有意义：插件声明了 filesystem，且它的工具在
            // 本地跑（MCP 的工具在对端手里，本地这份工作区没有读者）。
            // 详见 WorkspaceState 的说明
            val fsScope = manifest?.permissions?.filesystem ?: FilesystemScope.None
            val workspace = if (fsScope != FilesystemScope.None && !status.isMcp) {
                readWorkspace()
            } else {
                null
            }

            _state.update { previous ->
                previous.copy(
                    workspace = workspace?.let { fresh ->
                        // busy / message 必须从 previous 带过来：load() 在每次
                        // 操作结束、每次保存设置之后都会被调一次，从默认值重建的话，
                        // 刚做完那件事的提示会在显示出来之前就被抹掉
                        fresh.copy(
                            busy = previous.workspace?.busy ?: false,
                            message = previous.workspace?.message,
                            failed = previous.workspace?.failed ?: false,
                        )
                    },
                    name = manifest?.name ?: pluginId,
                    version = manifest?.version ?: "?",
                    description = manifest?.description,
                    author = manifest?.author,
                    license = manifest?.license,
                    homepage = manifest?.homepage,
                    runtimeLabel = manifest?.runtime?.displayName ?: "清单无法解析",
                    enabled = status.entity.enabled,
                    isMcp = status.isMcp,
                    mcp = McpStatus(
                        connected = cache != null,
                        fetchedAt = cache?.fetchedAt,
                        era = cache?.era?.displayName,
                        protocolVersion = cache?.protocolVersion,
                        offered = cache?.offered ?: 0,
                        dropped = cache?.dropped ?: 0,
                        // 连接中的状态和上一次的结果**必须从 previous 带过来**：
                        // load() 在连接完成后会被调一次，从默认值重建的话
                        // 刚拿到的结果会在显示出来之前就被抹掉
                        connecting = previous.mcp.connecting,
                        message = previous.mcp.message,
                        failed = previous.mcp.failed,
                    ),
                    tools = toolRows(status.isMcp, cache?.tools, manifest, registry, anyHost),
                    // 清单解析不了时权限区**不能**给空列表：界面会把空列表读成
                    // 「没有申请任何权限」，而真相是「看不出」。详见 [permissionLines]
                    permissions = permissionLines(
                        broken = status.isBroken,
                        permissions = manifest?.permissions?.describe().orEmpty(),
                    ),
                    isBroken = status.isBroken,
                    highRisk = manifest?.permissions?.isHighRisk == true,
                    warnings = status.check?.warnings?.map { it.toString() }.orEmpty(),
                    notices = registry.problemsOf(pluginId).map { it.toString() },
                    fields = manifest?.settings.orEmpty().map { (key, spec) ->
                        SettingField(
                            key = key,
                            title = spec.title,
                            description = spec.description,
                            type = spec.type,
                            options = spec.enum.mapNotNull { (it as? JsonPrimitive)?.content },
                            secret = spec.secret,
                            // 敏感项**不回显**：值在 KeyStore 里，读出来放进
                            // Compose 状态等于把它多复制一份到内存和快照系统里
                            text = if (spec.secret) "" else view.values[key].orEmpty(),
                            configured = key in view.configuredSecrets,
                        )
                    },
                    loading = false,
                    // 保留用户已经输入但还没保存的内容
                    clearedSecrets = previous.clearedSecrets,
                )
            }

            autoConnectIfNeeded(
                isMcp = status.isMcp,
                connected = cache != null,
                enabled = status.entity.enabled,
            )
        }
    }

    /**
     * 只在**不连就什么都看不到**的时候自动连一次。
     *
     * ## 这条规则为什么值得单独写出来
     *
     * 用户刚装完一个 MCP 插件、被跳到这一页，此时缓存是空的 ——
     * 页面上「提供的工具」一栏什么都没有，而他能做的只有卸载重装。
     * 自动连一次把这条路补上，是「装完就能用」和「装完像坏了」的区别。
     *
     * 反过来，**缓存有效时绝不自动连**：用户只是从列表点进来看看，
     * 不该因此向第三方服务打一个请求。缓存的时间戳和刷新按钮
     * 已经把「这份清单可能会旧」这件事交给用户判断了。
     *
     * 只试一次（[autoConnectTried]）。失败时不重试 —— 失败的原因
     * （地址错、密钥没填、服务端挂了）不会因为再试一次而改变，
     * 而每次进页面都自动打一遍网络请求是很糟的行为。
     */
    private fun autoConnectIfNeeded(isMcp: Boolean, connected: Boolean, enabled: Boolean) {
        if (autoConnectTried) return
        if (!isMcp || connected || !enabled) return
        autoConnectTried = true
        refreshTools()
    }

    /**
     * 工具列表的两条来源。
     *
     * ## 为什么 MCP 和声明式不能共用一条
     *
     * 声明式插件的工具是**清单里写死的**，任何时候都能显示，那是用户
     * 装它的时候看到的契约。MCP 插件的工具**在对端手里**，本地只有一份
     * 会过期的快照 —— 没连过的时候一个都显示不出来，而那时候界面必须
     * 说清「还没拉取」而不是「这个插件没有工具」。
     *
     * 这里刻意**不**从注册表反查工具（`registry.find(name)` 只能一个一个查）：
     * 注册表里没有的插件（被停用、清单坏了）也要能显示它「本来会提供什么」。
     */
    private fun toolRows(
        isMcp: Boolean,
        cache: List<McpToolSnapshot>?,
        manifest: PluginManifest?,
        registry: PluginRegistry,
        anyHost: Boolean,
    ): List<PluginToolRow> {
        if (isMcp) {
            return cache.orEmpty().map { snapshot ->
                PluginToolRow(
                    name = snapshot.name,
                    description = snapshot.description,
                    requiresConfirmation = registry.find(snapshot.name)?.requiresConfirmation
                        // 注册表里没有（插件被停用 / 还没装配完）时按同一套规则自己算：
                        // 声明了任意主机一律确认，否则看对端的 readOnlyHint。
                        // 和 `McpTool.requiresConfirmation` 是同一条规则
                        ?: (anyHost || !snapshot.readOnly),
                )
            }
        }

        return manifest?.tools.orEmpty().map { spec ->
            PluginToolRow(
                name = spec.name,
                description = spec.description,
                requiresConfirmation = registry.find(spec.name)?.requiresConfirmation
                    ?: (spec.requiresConfirmation == true),
            )
        }
    }

    /**
     * 连一次 MCP 服务，把工具清单拉回来。
     *
     * ## 为什么要一个 `connecting` 闸门
     *
     * 这个按钮会发一个**到第三方服务器**的网络请求，最慢能到 45 秒
     * （客户端超时）。用户等不及会连点，而每一次点击都是一次真实的请求 ——
     * 对端会看到同一个插件连着打了五遍。所以第二次点击直接丢掉。
     */
    fun refreshTools() {
        if (_state.value.mcp.connecting) return

        _state.update { it.copy(mcp = it.mcp.copy(connecting = true, message = null, failed = false)) }

        viewModelScope.launch {
            val plugin = container.plugins.installed(pluginId)
            if (plugin == null) {
                _state.update { it.copy(loading = false, missing = true) }
                return@launch
            }

            val result = container.refreshMcpTools(plugin)

            _state.update {
                it.copy(
                    mcp = it.mcp.copy(
                        connecting = false,
                        message = result.message,
                        failed = !result.ok,
                    ),
                )
            }
            // 重新读一遍：连接成功时缓存、工具列表、装配提示全都变了。
            // 不要在这里手写它们 —— 那等于把 load() 的逻辑抄一遍
            load()
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.plugins.setEnabled(pluginId, enabled)
            // 启停会改变工具集合，必须重新合成 —— 否则这个页面上显示的工具
            // 状态和模型实际拿到的不一致
            container.tools.refresh()
            load()
        }
    }

    fun onTextChange(key: String, value: String) {
        _state.update { state ->
            state.copy(
                fields = state.fields.map { if (it.key == key) it.copy(text = value) else it },
                // 用户重新输入了值，之前那次「清除」就不算数了
                clearedSecrets = state.clearedSecrets - key,
            )
        }
    }

    fun clearSecret(key: String) {
        _state.update { state ->
            state.copy(
                fields = state.fields.map { if (it.key == key) it.copy(text = "") else it },
                clearedSecrets = state.clearedSecrets + key,
            )
        }
    }

    fun save() {
        viewModelScope.launch {
            val fields = _state.value.fields
            val cleared = _state.value.clearedSecrets

            val draft = PluginSettingsDraft(
                values = fields.filterNot { it.secret }.associate { it.key to it.text },
                secrets = fields.filter { it.secret }.mapNotNull { field ->
                    when {
                        // 点了「清除」→ 显式传 null（清除）
                        field.key in cleared -> field.key to null
                        // 输入框是空的 → 整条不传（不动）。这是关键：
                        // 传空串的话，用户改个温度单位就会把 API Key 抹掉
                        field.text.isBlank() -> null
                        else -> field.key to field.text
                    }
                }.toMap(),
            )

            container.plugins.saveSettings(pluginId, draft)

            // 配置变了 → 装配结果可能跟着变（请求头里的 `{{settings.x}}` 现在有值了、
            // 认证从「没填」变成「填了」）。不刷新的话，模型那边还是旧的一套，
            // 而用户刚点完「保存设置」，他合理地认为已经生效了
            container.tools.refresh()

            _state.update { it.copy(savedTick = it.savedTick + 1, clearedSecrets = emptySet()) }
            // 重新读一遍，把「已配置」标记和输入框刷新成真实状态。
            // 不要在这里手写 fields —— 那等于把 settingsView 的读取逻辑抄一遍，
            // 两份实现迟早会不一致（而且第一份必然是错的）
            load()
        }
    }

    // ---------------------------------------------------------------- 工作区

    /**
     * 读一遍工作区。
     *
     * 磁盘 IO，所以必须切线程 —— 最多遍历 [PluginWorkspace.MAX_ENTRIES] 个文件。
     * 用 `adminOf` 而不是 `open`：前者不会因为「看一眼」就把目录建出来，
     * 也不受插件声明的读写方向限制（用户是主人，见它的 KDoc）。
     */
    private suspend fun readWorkspace(): WorkspaceState = withContext(Dispatchers.IO) {
        val workspace = container.workspaces.adminOf(pluginId)
        WorkspaceState(files = workspace.allFiles(), usage = workspace.usage())
    }

    /**
     * 把用户选中的一个文件放进工作区。
     *
     * [uri] 来自 `ActivityResultContracts.OpenDocument`，是 SAF 给的
     * content URI —— 不要当路径用，也不要假设能直接 `File()` 打开。
     *
     * ## 为什么要先自己判一次大小
     *
     * `PluginWorkspace.writeBytes` 里那道上限也会拦，但它抛出来的话是
     * **写给插件作者看的**。用户导入一个 20 MB 的 CSV 时，需要听到的是
     * 「这个文件多大、上限多少、所以没导进去」，而不是一句关于「拆开写」
     * 的劝告。所以这里先判、给出用户话术；剩下的失败（磁盘满、路径不对）
     * 才落到兜底那句上 —— 那些确实不是用户能处理的。
     */
    fun importFile(uri: Uri) {
        if (_state.value.workspace?.busy == true) return
        markBusy()

        viewModelScope.launch {
            when (val picked = withContext(Dispatchers.IO) { readPicked(uri) }) {
                is Picked.Failed -> reportWorkspace(picked.message, failed = true)
                is Picked.Ok -> writePicked(picked)
            }
            load()
        }
    }

    /**
     * 删掉工作区里的一个文件。
     *
     * [path] 来自界面上的列表（[WorkspaceState.files]），也就是
     * [PluginWorkspace.allFiles] 的输出 —— 那里只有文件，没有目录。
     */
    fun removeFile(path: String) {
        if (_state.value.workspace?.busy == true) return
        markBusy()

        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching { container.workspaces.adminOf(pluginId).remove(path) }.getOrDefault(false)
            }
            // 「已经不在了」不标红：用户的意图（让它消失）已经达成了，
            // 大概率是他连点了两下
            reportWorkspace(if (removed) "已删除 $path" else "「$path」已经不在了。")
            load()
        }
    }

    private suspend fun writePicked(picked: Picked.Ok) {
        // 名字要洗：SAF 给的名字由文件提供方说了算，可能是 "Download/foo.csv"、
        // 可能带控制字符、可能长到让 createNewFile 抛 ENAMETOOLONG
        val name = PluginWorkspace.cleanName(picked.name)

        val failure = withContext(Dispatchers.IO) {
            runCatching {
                container.workspaces.adminOf(pluginId).writeBytes(name, picked.bytes)
            }.exceptionOrNull()
        }

        when (failure) {
            null -> reportWorkspace("已导入 $name（${formatBytes(picked.bytes.size.toLong())}）")
            is WorkspaceException -> reportWorkspace("导入失败：${failure.message}", failed = true)
            else -> reportWorkspace(
                "导入失败：${failure.message ?: failure::class.simpleName}。可能是存储空间不够。",
                failed = true,
            )
        }
    }

    private fun markBusy() {
        _state.update {
            it.copy(workspace = it.workspace?.copy(busy = true, message = null, failed = false))
        }
    }

    /** 写下一次操作的结束状态。真实数据由调用点接着 `load()` 拿。 */
    private fun reportWorkspace(message: String, failed: Boolean = false) {
        _state.update {
            it.copy(workspace = it.workspace?.copy(busy = false, message = message, failed = failed))
        }
    }

    /** 从 SAF 给的 URI 读一个文件。切线程由调用点负责。 */
    private fun readPicked(uri: Uri): Picked {
        val resolver = container.appContext.contentResolver
        val declared = querySize(uri)

        // 先看提供方报的大小：本地文件几乎都有，能省掉一次「读进内存再拒绝」
        if (declared != null && declared > PluginWorkspace.MAX_FILE_BYTES) {
            return Picked.Failed(tooLarge(declared))
        }

        val read = runCatching {
            resolver.openInputStream(uri)?.use { readCappedBytes(it, PluginWorkspace.MAX_FILE_BYTES) }
        }.getOrNull()

        return when (read) {
            null -> Picked.Failed("读不到这个文件，可能它已经被移走或删掉了。")
            // 提供方报的大小可能撒谎（或者根本没报），所以这里还有一道兜底
            CappedBytes.TooLarge -> Picked.Failed(tooLarge(declared))
            is CappedBytes.Ok -> Picked.Ok(name = queryDisplayName(uri), bytes = read.bytes)
        }
    }

    private fun tooLarge(bytes: Long?): String {
        val max = formatBytes(PluginWorkspace.MAX_FILE_BYTES.toLong())
        val actual = bytes?.let { "${formatBytes(it)}，" }.orEmpty()
        return "这个文件${actual}超过插件工作区单文件上限 $max。" +
            "工作区不是网盘 —— 它是给插件放数据的地方。"
    }

    /** 从 SAF 拿文件名。拿不到就返回空串，[PluginWorkspace.cleanName] 会兜底。 */
    private fun queryDisplayName(uri: Uri): String = runCatching {
        container.appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull().orEmpty()

    /** 从 SAF 拿文件大小。拿不到（或提供方说 -1）返回 null。 */
    private fun querySize(uri: Uri): Long? = runCatching {
        container.appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            // 有些提供方对「不知道」返回 -1 而不是 null，不能拿它当「很小」
            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index).takeIf { it >= 0 } else null
        }
    }.getOrNull()

    /** 从 SAF 读一个文件的结果。 */
    private sealed interface Picked {
        /** 用普通类不用 `data class` —— `ByteArray` 的 `equals` 是引用比较。 */
        class Ok(val name: String, val bytes: ByteArray) : Picked
        data class Failed(val message: String) : Picked
    }

    fun uninstall() {
        viewModelScope.launch {
            container.plugins.uninstall(pluginId)
            container.tools.refresh()
            _state.update { it.copy(missing = true) }
        }
    }
}
