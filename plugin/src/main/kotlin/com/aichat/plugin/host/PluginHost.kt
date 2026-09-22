package com.aichat.plugin.host

import com.aichat.domain.text.errorDetail
import com.aichat.domain.tool.Tool
import com.aichat.plugin.manifest.AuthSpec
import com.aichat.plugin.manifest.AuthType
import com.aichat.plugin.manifest.ManifestProblem
import com.aichat.plugin.manifest.McpEntry
import com.aichat.plugin.manifest.McpTransport
import com.aichat.plugin.manifest.PluginManifest
import com.aichat.plugin.manifest.PluginRuntimeKind
import com.aichat.plugin.permission.NetworkDeniedException
import com.aichat.plugin.permission.NetworkGuard
import com.aichat.plugin.runtime.AuthPlan
import com.aichat.plugin.runtime.DeclarativeTool
import com.aichat.plugin.runtime.PluginSettings
import com.aichat.plugin.runtime.mcp.McpClient
import com.aichat.plugin.runtime.mcp.McpEra
import com.aichat.plugin.runtime.mcp.McpFailure
import com.aichat.plugin.runtime.mcp.McpTool
import com.aichat.plugin.runtime.mcp.McpToolCache
import com.aichat.plugin.runtime.mcp.McpToolSnapshot
import com.aichat.plugin.runtime.mcp.mcpCacheFingerprint
import com.aichat.plugin.runtime.mcp.toDescriptor
import com.aichat.plugin.runtime.mcp.toSnapshot
import com.aichat.plugin.runtime.script.ScriptRequest
import com.aichat.plugin.runtime.script.ScriptRuntime
import com.aichat.plugin.runtime.script.ScriptTool
import com.aichat.plugin.template.Placeholder
import com.aichat.plugin.template.Placeholders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * 一个装在本地的插件。
 *
 * [settings] 是**用户填好的值**（密钥、偏好），不是清单里的默认值 ——
 * 默认值的合并由配置层负责，这里拿到的就是最终要用的那一份。
 * 分开的理由：默认值可能来自 `SettingSpec.default`，而它是个 `JsonElement`，
 * 合并逻辑里有「用户填了空串算不算填了」这种判断，那些属于配置层的知识。
 *
 * [enabled] 是用户的总开关。关掉的插件**不进注册表** —— 不只是不执行，
 * 连工具定义都不会出现在发给模型的请求里。后者更重要：一个被关掉的插件
 * 如果还留在工具列表里，模型会去调它，然后拿到「插件已停用」，
 * 白白多跑一轮。
 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    val settings: PluginSettings = PluginSettings.Empty,
    val enabled: Boolean = true,

    /**
     * MCP 工具清单的**缓存**。null 表示「还没成功连过」，或者缓存读不出来。
     *
     * ## 为什么它是 [InstalledPlugin] 的一部分
     *
     * 因为 [PluginHost.tools] 必须是同步的（它被 `ToolRegistryHolder.refresh()`
     * 直接调用，而那一层在 ViewModel 的协程里），而 MCP 的工具清单只能联网拿到。
     * 所以装配读缓存、联网是另一条路（[PluginHost.connect]）。
     *
     * 把它放进这个数据类、而不是给 [PluginHost] 注入一个 `McpCatalog` 接口，
     * 是为了保住一条已有的性质：**装配需要的东西全在 `InstalledPlugin` 里**。
     * 注入接口的话，`PluginRegistry.build` 的签名也要跟着多一个参数，
     * 而那个签名现在只有「内置 + 插件 + client」三样，干净得值得保住。
     *
     * ## 指纹不匹配时装配会**忽略它**
     *
     * 见 [McpToolCache.fingerprint]。判断由 [PluginHost] 做 —— 那正是
     * 使用它的地方，规则不该和消费它的代码分开。
     */
    val mcpCache: McpToolCache? = null,
)

/**
 * 装配一个插件得到的工具。
 *
 * [problems] 是**装配期**才发现的问题，和 `ManifestParser` 的校验结果是两回事：
 * 那边查的是「这份清单本身合不合法」，这里查的是「这份合法的清单，
 * 在这台设备、这个宿主版本上能不能真的跑起来」——
 * 比如运行形态还没实现、baseUrl 解析不出来。
 *
 * 两类问题都带 [ManifestProblem] 是因为它们要显示在同一个地方（插件详情页），
 * 而且都要指出「哪一行、怎么改」。
 */
data class PluginTools(
    val plugin: InstalledPlugin,
    val tools: List<Tool>,
    val problems: List<ManifestProblem>,
) {
    val pluginId: String get() = plugin.manifest.id
    val pluginName: String get() = plugin.manifest.name
}

/**
 * 一次 MCP 连接的结果。
 *
 * ## 失败时 [cache] 是 null，而且调用方**不要**清掉已有的缓存
 *
 * 这两件事是分开的，而且第二件容易做错：网络不通时那次刷新失败，
 * 但上一次成功拉到的工具清单仍然是**手上最好的信息**。把它清掉的话，
 * 用户只是断了个网，插件就从「能用」变成「什么都没有」。
 *
 * 顺带一提，配置变了（指纹不匹配）时也不需要在这里清 —— 装配侧本来就会
 * 因为指纹对不上而忽略旧缓存。**「什么时候不再相信这份缓存」是一条规则，
 * 只该有一个地方回答它。**
 */
data class McpRefresh(
    val cache: McpToolCache?,

    /** 这次连接发现的问题。成功时也可能非空（比如清单里声明的工具名对端没有）。 */
    val problems: List<ManifestProblem> = emptyList(),
) {
    val ok: Boolean get() = cache != null

    /** 给用户看的一句话。 */
    val message: String
        get() = if (ok) {
            "连接成功，对端提供了 ${cache?.tools?.size ?: 0} 个工具。"
        } else {
            problems.firstOrNull()?.message ?: "连接失败。"
        }
}

/**
 * 插件宿主：把「装好的插件」变成「模型能调用的工具」。
 *
 * ## 它在整个架构里的位置
 *
 * ```
 * 清单文件 ──ManifestParser──> PluginManifest   （这份东西合不合法）
 *                                    │
 *                                    ▼
 *                            PluginHost        （在这台设备上跑不跑得起来）
 *                                    │
 *                                    ▼
 *                      List<Tool> ──PluginRegistry──> ToolRegistry  （和内置工具合成）
 * ```
 *
 * 每一步只做一件事，而且**每一步的产物都是可以单独看的东西** ——
 * 清单有问题时你能指着校验报告说话，装配失败时你能指着装配问题说话，
 * 不需要「上模拟器调一次看看」。
 *
 * ## 为什么装配失败不抛异常
 *
 * 因为失败的是**一个插件**，不是整个 App。一个插件的 baseUrl 写错了，
 * 不该让另外五个插件也用不了。所以这里一律返回 [PluginTools]，
 * 把问题装在 [PluginTools.problems] 里往外带。
 *
 * ## 两个入口：同步的 [tools] 和挂起的 [connect]
 *
 * 这个类原来只有一个方法，MCP 让它必须有两个。原因是**线程契约**：
 * [tools] 在装配路径上（可能在主线程），[connect] 要联网。
 * 把它们合成一个「会自己决定要不要联网」的方法，等于让调用方
 * 无法判断自己会不会被阻塞 —— 而那正是最难查的一类问题。
 */
object PluginHost {

    /**
     * 装配一个插件的全部工具。**同步，不联网** —— MCP 的工具清单从缓存里读。
     *
     * [scripts] 是脚本运行时的宿主实现。默认值 [ScriptRuntime.Unavailable] 让现有
     * 调用点（以及 25 处测试）一行都不用改，而且「没接引擎」时用户看到的仍是
     * 明确的「宿主还不支持」，不是静默的空列表 —— 理由见 [ScriptRuntime.Unavailable]。
     */
    fun tools(
        plugin: InstalledPlugin,
        client: OkHttpClient,
        scripts: ScriptRuntime = ScriptRuntime.Unavailable,
    ): PluginTools {
        if (!plugin.enabled) return PluginTools(plugin, emptyList(), emptyList())

        return when (plugin.manifest.runtime) {
            PluginRuntimeKind.Declarative -> declarative(plugin, client)
            PluginRuntimeKind.Mcp -> mcp(plugin, client)
            PluginRuntimeKind.Script -> script(plugin, scripts)

            // native 形态宿主还没实现。这里**明确报出来**而不是返回空列表：
            // 「装了一个插件，工具列表里什么都没有，也没有任何提示」
            // 是最难查的一种状态 —— 用户会怀疑是安装没成功
            PluginRuntimeKind.Native,
            -> PluginTools(
                plugin = plugin,
                tools = emptyList(),
                problems = listOf(
                    ManifestProblem(
                        "$.runtime",
                        "当前版本的宿主还不支持 ${plugin.manifest.runtime.name.lowercase()} 运行形态，" +
                            "这个插件暂时不会提供任何工具。它的清单是合法的，等宿主支持后可以直接用。",
                        severity = ManifestProblem.Severity.Warning,
                    ),
                ),
            )
        }
    }

    /**
     * 脚本形态的装配。
     *
     * 装配期只**读源码**、不跑脚本 —— 「跑一次才知道行不行」的话，插件详情页上
     * 那句「这个插件能用」就退化成「你调一次试试」，而详情页要给的正是前者。
     */
    private fun script(plugin: InstalledPlugin, scripts: ScriptRuntime): PluginTools {
        // 顺序要紧：**先看引擎，再看文件**。反过来的话，宿主没装引擎时用户看到的
        // 会是「入口文件读不到」—— 而真实原因是这个宿主压根没有脚本运行时，
        // 两者的出路完全不同（一个让他去查文件，一个让他等宿主升级）。
        //
        // 那句话**由宿主给**（[ScriptRuntime.unavailableReason]），这里不写死：
        // 「宿主没有引擎」和「这台设备跑不了引擎」的出路不一样，只有宿主知道是哪种。
        val noEngine = scripts.unavailableReason
        if (noEngine != null) {
            return PluginTools(
                plugin = plugin,
                tools = emptyList(),
                problems = listOf(
                    ManifestProblem(
                        "$.runtime",
                        noEngine,
                        severity = ManifestProblem.Severity.Warning,
                    ),
                ),
            )
        }

        val entry = plugin.manifest.entry.script
            ?: return fail(plugin, "$.entry.script", "runtime 是 script 但没有 script 段，无法装配。")

        // 下面这处「校验已经排除了」的情况仍然要挡：PluginHost 是 public API，
        // 不能假设调用方一定先跑过 ManifestParser（和 declarative 分支同一个理由）
        if (plugin.manifest.tools.isEmpty()) return noTools(plugin)

        // 源码从**清单自己**里取（`files`），不问引擎要。理由见 PluginManifest.files：
        // 一个插件就是一份文档，装/升级/卸载都是对同一份文本的一次操作。
        // 于是「入口文件读不到」不再是一条装配期问题 —— 它已经在校验层被拦下了
        // （`checkEntryFileExists`）。这里留着是给绕过校验的调用方兜底。
        if (entry.main !in plugin.manifest.files) {
            return fail(
                plugin,
                "$.entry.script.main",
                "读不到插件的入口脚本「${entry.main}」。它不在清单的 files 里，" +
                    "这个插件装的时候就不完整，重装一次试试。",
            )
        }

        val tools = plugin.manifest.tools.map { spec ->
            ScriptTool(
                pluginName = plugin.manifest.name,
                spec = spec,
                template = ScriptRequest(
                    pluginId = plugin.manifest.id,
                    pluginName = plugin.manifest.name,
                    entryFile = entry.main,
                    // 整份带过去，不只入口那一份：多文件插件要靠它做模块解析
                    // （`require('./lib/util.js')`），而且沙箱因此完全不用碰文件系统
                    files = plugin.manifest.files,
                    toolName = spec.name,
                    // 模型给的参数要到调用时才有，这里留空、由 ScriptTool 填
                    inputJson = "",
                    settings = plugin.settings.asMap(),
                    network = plugin.manifest.permissions.network,
                    filesystem = plugin.manifest.permissions.filesystem,
                    timeoutMs = entry.timeoutMs,
                    memoryLimitMb = entry.memoryLimitMb,
                ),
                runtime = scripts,
            )
        }

        return PluginTools(plugin = plugin, tools = tools, problems = emptyList())
    }

    /**
     * 连一次 MCP 服务，把它的工具清单拉回来。
     *
     * **挂起，自己切到 IO 调度器。** 这一点不能省：调用方是
     * `viewModelScope.launch`（主线程），而这里要发 HTTP 请求 ——
     * 在 Android 上会直接 `NetworkOnMainThreadException` 闪退，
     * 而且纯 JVM 单测**测不出来**（那是运行时的检查）。
     *
     * 顺带把异常转成 [McpRefresh] 而不是抛出去：调用方是界面，
     * 它需要的是「一句能显示的话」，不是一个要它自己分类的异常树。
     * `CancellationException` 照例原样重抛 —— 取消不是失败。
     */
    suspend fun connect(
        plugin: InstalledPlugin,
        client: OkHttpClient,
        now: Long = System.currentTimeMillis(),
    ): McpRefresh = withContext(Dispatchers.IO) {
        if (!plugin.enabled) {
            return@withContext McpRefresh(
                cache = null,
                problems = listOf(problem("$.runtime", "这个插件现在是停用状态，不会去连接 MCP 服务。先把它打开。")),
            )
        }

        val entry = plugin.manifest.entry.mcp
            ?: return@withContext McpRefresh(
                cache = null,
                problems = listOf(problem("$.entry.mcp", "runtime 是 mcp 但没有 mcp 段，无法连接。")),
            )

        // stdio 在 Android 上永远跑不起来。这里给的是**用户能照着做**的那句话：
        // 去找作者要一个远程地址，而不是「不支持」三个字
        if (entry.transport == McpTransport.Stdio) {
            return@withContext McpRefresh(cache = null, problems = listOf(stdioProblem()))
        }

        val endpoint = when (val setup = setup(plugin, entry)) {
            is McpSetup.Bad -> return@withContext McpRefresh(
                cache = null,
                problems = listOf(problem(setup.path, setup.message)),
            )

            is McpSetup.Ok -> setup.endpoint
        }

        // 密钥没填时**先说清楚**，别发出去换一个 401 回来。
        // 401 的排查方向是「密钥错了」，而真实原因是「密钥没填」—— 完全是两回事
        endpoint.missingSecret?.let { key ->
            return@withContext McpRefresh(
                cache = null,
                problems = listOf(
                    problem(
                        "$.entry.mcp.auth",
                        "认证配置指向的「$key」还没有填值。先去这个插件的设置里把它填上，" +
                            "再回来点一次「连接并刷新工具列表」。",
                    ),
                ),
            )
        }

        try {
            val mcp = McpClient(
                endpoint = endpoint.url,
                guard = endpoint.guard,
                client = client,
                extraHeaders = endpoint.headers,
            )
            val discovery = mcp.discover()
            val snapshots = discovery.tools.map { it.toSnapshot() }
            val (_, whitelistProblems) = whitelist(snapshots, entry.tools)

            McpRefresh(
                cache = McpToolCache(
                    fingerprint = endpoint.fingerprint,
                    fetchedAt = now,
                    // discover() 一定会把年代定下来，所以这里不会是 Unknown
                    era = mcp.detectedEra ?: McpEra.Unknown,
                    protocolVersion = mcp.negotiatedVersion,
                    offered = discovery.offered,
                    tools = snapshots,
                ),
                problems = whitelistProblems,
            )
        } catch (e: CancellationException) {
            // 取消不是失败。吞掉它会让「用户离开页面」看起来像「连接出错」
            throw e
        } catch (e: NetworkDeniedException) {
            McpRefresh(cache = null, problems = listOf(problem("$.permissions.network", e.message.orEmpty())))
        } catch (e: McpFailure) {
            McpRefresh(cache = null, problems = listOf(problem("$.entry.mcp.url", e.message.orEmpty())))
        } catch (t: Throwable) {
            // 兜底：漏出来的异常如果不在这里变成一句话，就会在界面上变成一个
            // 没有上下文的崩溃或空白
            McpRefresh(
                cache = null,
                problems = listOf(
                    problem(
                        "$.entry.mcp.url",
                        "连接这个 MCP 服务时出错：${errorDetail(t)}。" +
                            "如果反复出现，请把这个插件的清单发给作者。",
                    ),
                ),
            )
        }
    }

    // ------------------------------------------------------------------ 声明式

    private fun declarative(plugin: InstalledPlugin, client: OkHttpClient): PluginTools {
        val manifest = plugin.manifest
        val entry = manifest.entry.declarative

        // 下面这几处「校验已经排除了」的情况仍然要挡：
        // PluginHost 是 public API，不能假设调用方一定先跑过 ManifestParser。
        // 一个绕过校验直接装配的调用点，会让「校验通过」这件事变成口头约定
        if (entry == null) {
            return fail(plugin, "$.entry.declarative", "runtime 是 declarative 但没有 declarative 段，无法装配。")
        }

        // 上面那句注释说的「校验已经排除了的情况」里的最后一条：tools 为空。
        // 少了它，绕过校验的调用点会拿到「0 个工具 + 0 条问题」——
        // 正是本文件反复要避免的那种没法排查的状态
        if (manifest.tools.isEmpty()) return noTools(plugin)

        val baseUrl = entry.baseUrl.toHttpUrlOrNull()
            ?: return fail(plugin, "$.entry.declarative.baseUrl", "「${entry.baseUrl}」不是合法地址，无法装配。")

        val auth = authPlan(entry.auth)
            ?: return fail(
                plugin,
                "$.entry.declarative.auth",
                authIncompleteMessage(entry.auth),
            )

        val headers = renderHeaders(entry.headers, plugin)
            ?: return fail(
                plugin,
                "$.entry.declarative.headers",
                "请求头模板里有配置项没填，无法装配。",
            )

        val guard = NetworkGuard(manifest.permissions.network)

        val tools = manifest.tools.mapNotNull { spec ->
            // 声明式工具的 request 为空是校验错误，这里再挡一次：
            // 没有 request 就没有「发什么请求」的信息，静默跳过会让工具凭空消失
            val request = spec.request ?: return@mapNotNull null
            DeclarativeTool(
                pluginName = manifest.name,
                baseUrl = baseUrl,
                spec = spec,
                request = request,
                settings = plugin.settings,
                guard = guard,
                client = client,
                auth = auth,
                headers = headers,
            )
        }

        return PluginTools(plugin, tools, emptyList())
    }

    // ------------------------------------------------------------------ MCP

    /**
     * 装配 MCP 插件。**只用缓存，不联网。**
     *
     * 没有缓存时返回**空工具列表 + 一条能照着做的提示**，而不是静默的空。
     * 「装了一个插件，工具列表里什么都没有」是最难查的一种状态 ——
     * 用户会怀疑是安装没成功，而真正的原因是「还没连接过」。
     */
    private fun mcp(plugin: InstalledPlugin, client: OkHttpClient): PluginTools {
        val entry = plugin.manifest.entry.mcp
            ?: return fail(plugin, "$.entry.mcp", "runtime 是 mcp 但没有 mcp 段，无法装配。")

        if (entry.transport == McpTransport.Stdio) {
            return PluginTools(plugin, emptyList(), listOf(stdioProblem()))
        }

        val endpoint = when (val setup = setup(plugin, entry)) {
            is McpSetup.Bad -> return fail(plugin, setup.path, setup.message)
            is McpSetup.Ok -> setup.endpoint
        }

        // 指纹对不上 = 这份缓存说的是**另一个配置**（用户改了地址或认证方式）。
        // 当成没有缓存，而不是「凑合用」—— 否则用户把地址从 A 换成 B 之后，
        // 界面上还挂着 A 的工具，而他会照着那些名字去问模型
        val cache = plugin.mcpCache?.takeIf { it.fingerprint == endpoint.fingerprint }

        val staleHint = ManifestProblem(
            "$.entry.mcp",
            "这个 MCP 插件的工具清单还没有拉取过（或者配置改过了）。" +
                "去这个插件的详情页点一次「连接并刷新工具列表」。",
            severity = ManifestProblem.Severity.Warning,
        )

        if (cache == null) {
            return PluginTools(plugin, emptyList(), listOf(staleHint))
        }

        val (selected, whitelistProblems) = whitelist(cache.tools, entry.tools)

        val problems = whitelistProblems.toMutableList()

        // 因为没名字被丢掉的工具：必须解释一句，否则「服务端说有 5 个、
        // App 里只有 4 个」这种不一致只能靠用户自己猜
        if (cache.dropped > 0) {
            problems += ManifestProblem(
                "$.entry.mcp",
                "对端报了 ${cache.offered} 个工具，其中 ${cache.dropped} 个没有名字，" +
                    "没法调用，已经忽略。对端可能在返回一个不合规的 tools/list。",
                severity = ManifestProblem.Severity.Warning,
            )
        }

        // 密钥没填时**仍然装配**（和声明式一致）：把工具留在列表里，
        // 调用时由对端回 401，模型能拿到一句「去设置里填 X」。
        // 在这里把工具拿掉的话，用户填好密钥之前连「这个插件提供什么」都看不到
        endpoint.missingSecret?.let { key ->
            problems += ManifestProblem(
                "$.entry.mcp.auth",
                "认证配置指向的「$key」还没有填值，调用这些工具会被服务端拒绝。",
                severity = ManifestProblem.Severity.Warning,
            )
        }

        val tools = selected.map { snapshot ->
            McpTool(
                pluginName = plugin.manifest.name,
                client = McpClient(
                    endpoint = endpoint.url,
                    guard = endpoint.guard,
                    client = client,
                    extraHeaders = endpoint.headers,
                    // 缓存里的年代必须带回来：`callTool` 走的 `rpc` 不触发判定，
                    // 不带上它的话旧版对端会被当成现代版发出去，然后失败
                    knownEra = cache.era.takeIf { it != McpEra.Unknown },
                ),
                descriptor = snapshot.toDescriptor(),
                guard = endpoint.guard,
            )
        }

        return PluginTools(plugin, tools, problems)
    }

    /**
     * 清单里的工具白名单。
     *
     * ## 为什么写了不存在的名字要**报出来**
     *
     * 因为它的效果是「什么都没发生」：作者以为限定了 3 个工具，
     * 实际上一个都没匹配上（或者匹配上了别的），而界面上看不出区别。
     * 对端提供哪些工具是**运行时才知道**的，所以这个拼写错误
     * 没法在清单校验阶段发现 —— 这里是最早能发现它的地方。
     */
    private fun whitelist(
        offered: List<McpToolSnapshot>,
        declared: List<String>,
    ): Pair<List<McpToolSnapshot>, List<ManifestProblem>> {
        if (declared.isEmpty()) return offered to emptyList()

        val available = offered.map { it.name }
        val allowed = declared.toSet()
        val missing = declared.filterNot { it in available }

        val selected = offered.filter { it.name in allowed }

        val problems = if (missing.isEmpty()) {
            emptyList()
        } else {
            listOf(
                ManifestProblem(
                    "$.entry.mcp.tools",
                    "清单里声明只暴露 ${missing.joinToString("、") { "`$it`" }}，但对端没有提供这些工具" +
                        "（它提供的是 ${available.sorted().joinToString("、").ifEmpty { "（一个都没有）" }}）。" +
                        "这几个名字不会生效 —— 请核对拼写，或者删掉这一项表示全部暴露。",
                    severity = ManifestProblem.Severity.Warning,
                ),
            )
        }

        return selected to problems
    }

    // ------------------------------------------------------------------ MCP 的入口解析

    /**
     * MCP 入口的解析结果。
     *
     * 做成密封类而不是「返回 null + 一个 out 参数」，是因为 [tools] 和
     * [connect] **两条路都要做同一件事**（解析地址、渲染认证、算指纹），
     * 而它们必须用**同一份**结果 —— 各写一遍的话，指纹算得不一样就会
     * 变成「每次连接都成功、每次装配都看不到工具」这种诡异症状。
     */
    private sealed interface McpSetup {
        data class Ok(val endpoint: McpEndpoint) : McpSetup

        /** [path] 是清单里该改的那一行，[message] 是给作者/用户看的话。 */
        data class Bad(val path: String, val message: String) : McpSetup
    }

    private class McpEndpoint(
        val url: HttpUrl,
        val guard: NetworkGuard,

        /** 插件级请求头 + 认证头，**已经渲染好**的。 */
        val headers: Map<String, String>,

        /** 见 [mcpCacheFingerprint]。 */
        val fingerprint: String,

        /** 认证指向的配置项还没填值时，这里是那个键名。 */
        val missingSecret: String?,
    )

    private fun setup(plugin: InstalledPlugin, entry: McpEntry): McpSetup {
        val raw = entry.url?.trim()
        if (raw.isNullOrBlank()) {
            return McpSetup.Bad("$.entry.mcp.url", "这个 MCP 插件没有给 url，无法连接。")
        }
        val url = raw.toHttpUrlOrNull()
            ?: return McpSetup.Bad("$.entry.mcp.url", "「$raw」不是合法地址，无法连接。")

        val auth = authPlan(entry.auth)
            ?: return McpSetup.Bad("$.entry.mcp.auth", authIncompleteMessage(entry.auth))

        if (auth is AuthPlan.Query) {
            // 校验层也会拦这个，这里再挡一次：PluginHost 是 public API，
            // 不能假设调用方一定先跑过 ManifestParser
            return McpSetup.Bad(
                "$.entry.mcp.auth.type",
                "MCP 的地址是一个固定端点，认证不能走查询参数 —— 请用 bearer 或 header。",
            )
        }

        val pluginHeaders = renderHeaders(entry.headers, plugin)
            ?: return McpSetup.Bad(
                "$.entry.mcp.headers",
                "请求头模板里有配置项没填，无法连接。",
            )

        // 密钥缺失**不阻止装配**，只是要在界面上说一句。
        // 和声明式一致：密钥没填时插件照样在工具列表里，调用时由对端回 401
        val missingSecret = auth.settingKey?.takeIf { plugin.settings.value(it) == null }

        val headers = pluginHeaders + authHeaders(auth, plugin.settings)

        return McpSetup.Ok(
            McpEndpoint(
                url = url,
                guard = NetworkGuard(plugin.manifest.permissions.network),
                headers = headers,
                fingerprint = mcpCacheFingerprint(
                    transport = entry.transport,
                    endpoint = url,
                    // **声明**的头名，不是 `headers` 的键集合。
                    // 后者里 `Authorization` 只在密钥填了之后才出现，
                    // 于是「用户把密钥补上」会让一份本来有效的缓存失效 ——
                    // 而用户刚做的是一个补全配置的动作，工具列表却变空了
                    headerNames = entry.headers.keys + authHeaderNames(auth),
                    auth = auth,
                ),
                missingSecret = missingSecret,
            ),
        )
    }

    /**
     * 认证会用到哪些请求头**名字**。
     *
     * 取名字而不是渲染结果：渲染结果里有密钥，而指纹会落进数据库。
     * 也不能拿「渲染出来的头」的键集合 —— 见 [mcpCacheFingerprint] 的注释。
     */
    private fun authHeaderNames(auth: AuthPlan): Set<String> = when (auth) {
        // query 认证改的是 URL，不是头。MCP 那边已经挡掉了，这里一并按空处理
        AuthPlan.None, is AuthPlan.Query -> emptySet()

        is AuthPlan.Bearer -> setOf("Authorization")

        is AuthPlan.Header -> setOf(auth.headerName)
    }

    /**
     * 认证方案 → 请求头。
     *
     * 和 `DeclarativeTool` 里那段 `when (auth)` 是**同一套语义**，但用途不同：
     * 那边是在发请求时现取（值缺失会抛错，因为那时候已经该有了），
     * 这边是在装配期渲染好一个静态头表交给 [McpClient]。
     *
     * 值缺失时返回空表而不是抛错 —— 缺失由 [McpEndpoint.missingSecret]
     * 单独承载，于是「密钥没填」在装配期是一个**警告**（工具照常出现），
     * 在连接期是一个**明确的错误**。两处需要的行为不同，所以不能合并成一个
     * 「要么给值要么炸」的函数。
     */
    private fun authHeaders(auth: AuthPlan, settings: PluginSettings): Map<String, String> = when (auth) {
        AuthPlan.None -> emptyMap()

        is AuthPlan.Bearer ->
            settings.value(auth.settingKey)?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()

        is AuthPlan.Header ->
            settings.value(auth.settingKey)?.let { mapOf(auth.headerName to it) }.orEmpty()

        // setup() 已经挡掉了 Query
        is AuthPlan.Query -> emptyMap()
    }

    private fun stdioProblem(): ManifestProblem = ManifestProblem(
        "$.entry.mcp.transport",
        "这个 MCP 插件用的是 stdio 传输（宿主启动一个本地子进程）。" +
            "当前宿主运行在 Android 上，没有可以启动的本地进程，所以它不会提供任何工具。" +
            "如果这个服务同时提供远程地址，请作者改成 transport: http 并给 url。",
        severity = ManifestProblem.Severity.Warning,
    )

    // ------------------------------------------------------------------ 共用

    /**
     * 清单里的认证声明 → 运行时的认证方案。
     *
     * 返回 null 表示**配置不完整**（`type` 说要认证但没给 `settingKey`）。
     * 这时候刻意不退回 [AuthPlan.None]：那会发出去一个没有任何身份的请求，
     * 服务端报 401，而真正的原因是清单里少写了一个字母 —— 用户会去怀疑密钥。
     *
     * 声明式和 MCP 共用同一个 [AuthSpec]，所以这个函数不关心它来自哪一段。
     */
    internal fun authPlan(spec: AuthSpec): AuthPlan? {
        val key = spec.settingKey?.takeIf { it.isNotBlank() }
        return when (spec.type) {
            AuthType.None -> AuthPlan.None

            AuthType.Bearer -> key?.let { AuthPlan.Bearer(it) }

            AuthType.Header -> {
                val name = spec.headerName?.takeIf { it.isNotBlank() }
                if (key != null && name != null) AuthPlan.Header(key, name) else null
            }

            AuthType.Query -> {
                val name = spec.queryName?.takeIf { it.isNotBlank() }
                if (key != null && name != null) AuthPlan.Query(key, name) else null
            }
        }
    }

    /** [authPlan] 返回 null 时给用户看的话。两段入口共用，免得两处说法不一致。 */
    private fun authIncompleteMessage(spec: AuthSpec): String =
        "认证配置不完整（type=${spec.type}，settingKey=${spec.settingKey ?: "未给"}），无法装配。" +
            "这里刻意不退回「不认证」：那会让请求带着错误的身份发出去，" +
            "报回来一个和真实原因无关的 401。"

    /**
     * 渲染插件级请求头。
     *
     * ## 为什么缺值就整体失败，而不是把这一条头去掉
     *
     * 少一个头的结果通常是服务端返回 401 或 400 —— 一个和真实原因无关的错误。
     * 而「作者声明的头没生效」是最难查的一类问题：清单里明明写着。
     * 所以这里直接让装配失败，问题里指名道姓地说出是哪个配置项没填。
     *
     * ## 为什么不用 `DeclarativeTool` 的渲染逻辑
     *
     * 因为那套是按「参数优先、缺失即错误」设计的，而这里只认配置项，
     * 而且语义相反：这里的值来自用户配置，缺了就是**要用户去填**，
     * 不是「模型参数给错了」。两套查找表混在一起之后，
     * 一个 `{{foo}}` 到底是参数还是配置项就说不清了。
     */
    private fun renderHeaders(headers: Map<String, String>, plugin: InstalledPlugin): Map<String, String>? {
        if (headers.isEmpty()) return emptyMap()

        val missing = mutableListOf<String>()
        val rendered = headers.mapValues { (_, template) ->
            Placeholders.render(template) { placeholder ->
                when (placeholder) {
                    is Placeholder.Setting -> plugin.settings.value(placeholder.name)
                    is Placeholder.Argument, is Placeholder.Malformed -> null
                }
            }.also { missing += it.missing }
                .text
        }

        // 校验层会拦住参数占位符和写法错误，所以这里缺的只可能是「用户没填配置」
        return if (missing.isEmpty()) rendered else null
    }

    private fun problem(path: String, message: String): ManifestProblem = ManifestProblem(path, message)

    /**
     * 「一个工具都没有」在装配期必须**说出来**，不能返回一个空工具集。
     *
     * 这是 [mcp] 的 KDoc 点名的「最难查的一种状态」：用户会怀疑是安装没成功。
     * 同一个道理仓里写过三遍（[mcp]、native 分支、[script]），而**声明式分支漏了** ——
     * 它和脚本分支一样，工具来源是清单里写死的 `tools`，空就是空的。
     *
     * MCP 是**唯一豁免**：它的工具清单由对端在装配时动态给出，作者在清单里写不出来
     * （`ManifestParser.checkTools` 里那条豁免的理由）。
     *
     * 校验层会拦（那句诊断是 Error 级，装不进来），但 PluginHost 是 public API，
     * 不能假设调用方一定先跑过 ManifestParser。两个分支**共用这一处实现**，
     * 是为了「同一件事只有一句话」—— 各写一遍的话，改一处漏一处不会有东西变红。
     */
    private fun noTools(plugin: InstalledPlugin): PluginTools =
        fail(plugin, "$.tools", "一个插件至少要暴露一个工具，否则装进来没有任何作用。")

    private fun fail(plugin: InstalledPlugin, path: String, message: String): PluginTools = PluginTools(
        plugin = plugin,
        tools = emptyList(),
        problems = listOf(ManifestProblem(path, message)),
    )
}
