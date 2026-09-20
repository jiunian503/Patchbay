package com.aichat.plugin

import com.aichat.plugin.host.InstalledPlugin
import com.aichat.plugin.manifest.AuthSpec
import com.aichat.plugin.manifest.DeclarativeEntry
import com.aichat.plugin.manifest.FilesystemScope
import com.aichat.plugin.manifest.ManifestParser
import com.aichat.plugin.manifest.McpEntry
import com.aichat.plugin.manifest.McpTransport
import com.aichat.plugin.manifest.PluginEntry
import com.aichat.plugin.manifest.PluginManifest
import com.aichat.plugin.manifest.PluginPermissions
import com.aichat.plugin.manifest.PluginRuntimeKind
import com.aichat.plugin.manifest.RequestSpec
import com.aichat.plugin.manifest.ScriptEntry
import com.aichat.plugin.manifest.ToolSpec
import com.aichat.plugin.runtime.mcp.McpEra
import com.aichat.plugin.runtime.mcp.McpToolCache
import com.aichat.plugin.runtime.mcp.McpToolSnapshot
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 测试用的清单构造器。
 *
 * ## 为什么用「拼 JSON 文本」而不是「直接 new 对象」
 *
 * 因为清单是这个系统里唯一的**外部输入**，而它最容易出错的地方恰恰是
 * 「文本长什么样」（字段名拼错、占位符写错、类型写成了字符串）。
 * 直接 new 一个 [PluginManifest] 对象就把这一层跳过去了 ——
 * 那样测出来的是「我的代码处理得了我自己造的对象」，不是
 * 「我的代码处理得了作者写的那份 JSON」。
 *
 * 只有 [ManifestParserTest] 里少数几条要测「已经拿到对象之后」的语义校验时，
 * 才直接 new 对象。
 */
object Manifests {

    /** 一份最小的合法声明式清单。 */
    fun declarative(
        id: String = "pub.test.demo",
        name: String = "测试插件",
        version: String = "1.0.0",
        baseUrl: String = "https://api.example.com",
        network: List<String> = listOf("api.example.com"),
        tools: List<String> = listOf("do_thing"),
        extraPermissions: String = "",
        extraEntry: String = "",
        settings: String = "",
        toolBody: (String) -> String = { toolSpec(it) },
    ): String = buildString {
        append("{")
        append("\"id\":\"$id\",")
        append("\"name\":\"$name\",")
        append("\"version\":\"$version\",")
        append("\"runtime\":\"declarative\",")
        append("\"permissions\":{\"network\":${network.jsonArray()}$extraPermissions},")
        if (settings.isNotEmpty()) append("\"settings\":{$settings},")
        append("\"entry\":{\"declarative\":{\"baseUrl\":\"$baseUrl\"$extraEntry}},")
        append("\"tools\":[${tools.joinToString(",") { toolBody(it) }}]")
        append("}")
    }

    /**
     * 一个声明式工具：GET /p/{{arg}}，带一个字符串参数 `arg`。
     *
     * [host] 是**原始 JSON 片段**（要带引号），和 [query] 一样。
     * 这样测试可以塞进去非法值（`"https://x"`、`"x:8080"`）来看校验层的反应 ——
     * 传一个 String 的话，非法值根本写不进去，而那正是最需要测的部分。
     */
    fun toolSpec(
        name: String,
        method: String = "GET",
        path: String = "/p",
        query: String = "",
        confirmation: String = "",
        dangerous: String = "",
        host: String = "",
        parameters: String = """{"type":"object","properties":{"arg":{"type":"string"}}}""",
    ): String = buildString {
        append("{")
        append("\"name\":\"$name\",")
        append("\"description\":\"测试用工具 $name\",")
        append("\"parameters\":$parameters,")
        if (confirmation.isNotEmpty()) append("\"requiresConfirmation\":$confirmation,")
        if (dangerous.isNotEmpty()) append("\"dangerous\":$dangerous,")
        append("\"request\":{\"method\":\"$method\",\"path\":\"$path\"")
        if (host.isNotEmpty()) append(",\"host\":$host")
        if (query.isNotEmpty()) append(",\"query\":$query")
        append("}}")
    }

    /**
     * 一份 MCP 清单。
     *
     * [tools] 默认**为空** —— 这不是偷懒，而是这类插件的真实形态：
     * 工具清单由对端在装配时动态给出，作者在清单里**写不出来**。
     *
     * [auth] / [headers] / [settings] 是**原始 JSON 片段**（要带引号或花括号），
     * 和 [declarative] 的 `extraEntry` 一样。这样测试可以塞进去非法值
     * （`"bearer"` 但 settingKey 指向不存在的项）来看校验层的反应。
     */
    fun mcp(
        id: String = "pub.test.mcp",
        version: String = "1.0.0",
        transport: String = "http",
        url: String = "https://api.example.com/mcp",
        network: List<String> = listOf("api.example.com"),
        whitelist: List<String> = emptyList(),
        settings: String = "",
        auth: String = "",
        headers: String = "",
    ): String = buildString {
        append("{")
        append("\"id\":\"$id\",")
        append("\"name\":\"MCP 插件\",")
        append("\"version\":\"$version\",")
        append("\"runtime\":\"mcp\",")
        append("\"permissions\":{\"network\":${network.jsonArray()}},")
        if (settings.isNotEmpty()) append("\"settings\":{$settings},")
        append("\"entry\":{\"mcp\":{\"transport\":\"$transport\"")
        // stdio 必须有 command，否则这份清单本身就是校验错误 ——
        // 而那是校验层该报的错，不该让每条装配用例都撞上
        if (transport == "stdio") append(",\"command\":\"mcp-server\"")
        if (url.isNotEmpty()) append(",\"url\":\"$url\"")
        if (auth.isNotEmpty()) append(",\"auth\":$auth")
        if (headers.isNotEmpty()) append(",\"headers\":$headers")
        if (whitelist.isNotEmpty()) {
            append(",\"tools\":${whitelist.jsonArray()}")
        }
        append("}},")
        // 顶层 tools 对 MCP 是**空的**：工具清单在对端手里，作者写不出来。
        // 这里刻意不留参数 —— 留了的话迟早有人拿它当「白名单」用，
        // 而白名单是 entry.mcp.tools（一串名字），两者完全不是一回事
        append("\"tools\":[]")
        append("}")
    }

    /**
     * 一份脚本清单。
     *
     * [main] 是**真值**，不是原始 JSON 片段 —— 要测非法路径（`../x.js`、
     * 带反斜杠）的用例走 [ManifestParserTest] 里手写的 JSON：那些值在 Kotlin
     * 字符串里没法干净地写出来（反斜杠要过两层转义），硬塞进来只会让这个
     * 构造器多一个没人看得懂的开关。
     *
     * [files] 默认就是「[main] 指向的那份源码」，也就是**最普通的**一份脚本插件。
     * 要测「入口不在 files 里」得走 [raw] + [scriptObject] —— 那种清单
     * 过不了校验，`installed()` 会在解析阶段就抛。
     *
     * [extraEntry] 是原始 JSON 片段，用来补 `timeoutMs` / `memoryLimitMb`。
     */
    fun script(
        id: String = "pub.test.script",
        name: String = "脚本插件",
        version: String = "1.0.0",
        network: List<String> = listOf("api.example.com"),
        filesystem: String = "read",
        settings: String = "",
        main: String = "index.js",
        files: Map<String, String> = mapOf(main to FakeScriptRuntime.DEFAULT_SOURCE),
        tools: List<String> = listOf("csv_stats"),
        extraEntry: String = "",
        toolBody: (String) -> String = { scriptToolSpec(it) },
    ): String = buildString {
        append("{")
        append("\"id\":\"$id\",")
        append("\"name\":\"$name\",")
        append("\"version\":\"$version\",")
        append("\"runtime\":\"script\",")
        append("\"permissions\":{\"network\":${network.jsonArray()},\"filesystem\":\"$filesystem\"},")
        if (settings.isNotEmpty()) append("\"settings\":{$settings},")
        append("\"entry\":{\"script\":{\"main\":\"$main\"$extraEntry}},")
        append("\"tools\":[${tools.joinToString(",") { toolBody(it) }}],")
        append("\"files\":${files.jsonObject()}")
        append("}")
    }

    /**
     * 一个脚本工具。
     *
     * 和 [toolSpec] 不同，这里**没有 `request` 段** —— 脚本工具干什么由 JS
     * 自己决定，清单里只声明「有这么个工具、参数长这样」。所以确认开关也没法
     * 按 HTTP 方法判断（宿主看不到请求），`null` 会落到 `true`。
     */
    fun scriptToolSpec(
        name: String,
        description: String = "脚本工具 $name",
        parameters: String = """{"type":"object","properties":{"arg":{"type":"string"}}}""",
        confirmation: String = "",
    ): String = buildString {
        append("{")
        append("\"name\":\"$name\",")
        append("\"description\":\"$description\",")
        append("\"parameters\":$parameters")
        if (confirmation.isNotEmpty()) append(",\"requiresConfirmation\":$confirmation")
        append("}")
    }

    /** 非声明式清单（用来测「运行形态还没实现」的分支）。 */
    fun otherRuntime(id: String, runtime: String): String = when (runtime) {
        "script" -> """
            {"id":"$id","name":"脚本插件","version":"1.0.0","runtime":"script",
             "permissions":{"network":[],"filesystem":"read"},
             "entry":{"script":{"main":"index.js"}},
             "tools":[{"name":"csv_stats","description":"统计","parameters":{"type":"object","properties":{}}}],
             "files":{"index.js":"exports.run = () => 1"}}
        """.trimIndent()

        "mcp" -> """
            {"id":"$id","name":"MCP 插件","version":"1.0.0","runtime":"mcp",
             "permissions":{"network":["api.example.com"]},
             "entry":{"mcp":{"transport":"http","url":"https://api.example.com/mcp"}},
             "tools":[{"name":"mcp_thing","description":"统计","parameters":{"type":"object","properties":{}}}]}
        """.trimIndent()

        else -> """
            {"id":"$id","name":"原生插件","version":"1.0.0","runtime":"native",
             "permissions":{"network":[],"device":["clipboard"]},
             "entry":{"native":{"library":"libdemo.so"}},
             "tools":[{"name":"native_thing","description":"统计","parameters":{"type":"object","properties":{}}}]}
        """.trimIndent()
    }

    /** 解析成 [InstalledPlugin]，解析不过就抛 —— 测试里用，失败要立刻看见。 */
    fun installed(
        json: String,
        settings: Map<String, String> = emptyMap(),
        enabled: Boolean = true,
        mcpCache: McpToolCache? = null,
    ): InstalledPlugin {
        val check = ManifestParser.parse(json)
        val manifest = requireNotNull(check.manifest) { "测试清单本身就不合法：\n${check.report()}" }
        return InstalledPlugin(
            manifest = manifest,
            settings = com.aichat.plugin.runtime.PluginSettings(settings),
            enabled = enabled,
            mcpCache = mcpCache,
        )
    }

    /**
     * 造一份 MCP 工具清单缓存。
     *
     * ## [fingerprint] 必须由调用方给，而且是**故意**的
     *
     * 因为指纹要和 `PluginHost` 算出来的那一个比对才有意义，而那个算法
     * （哪些头、哪种认证）是**被测对象的一部分**。在这里再实现一遍的话，
     * 两边一起改错也能通过 —— 那就是典型的「测试和自己的实现犯同一个错」。
     *
     * 所以两条路：
     * - 要测「缓存真的生效」→ 用 `PluginHost.connect()` 对着 MockWebServer
     *   跑一次，拿它返回的缓存。**那条路不经过这个函数。**
     * - 要测「指纹对不上就忽略」→ 用这个函数，指纹随便给一个假的。
     */
    fun mcpCache(
        fingerprint: String = "fake-fingerprint",
        fetchedAt: Long = 1_700_000_000_000L,
        era: McpEra = McpEra.Modern,
        protocolVersion: String = "2026-07-28",
        offered: Int = 0,
        tools: List<McpToolSnapshot> = emptyList(),
    ) = McpToolCache(
        fingerprint = fingerprint,
        fetchedAt = fetchedAt,
        era = era,
        protocolVersion = protocolVersion,
        offered = if (offered == 0) tools.size else offered,
        tools = tools,
    )

    /** 一个 MCP 工具快照。 */
    fun snapshot(
        name: String,
        description: String = "对端提供的工具 $name",
        readOnly: Boolean = false,
        schema: String = """{"type":"object","properties":{"q":{"type":"string"}}}""",
    ) = McpToolSnapshot(
        name = name,
        description = description,
        inputSchema = kotlinx.serialization.json.Json.parseToJsonElement(schema).jsonObject,
        readOnly = readOnly,
    )

    fun parse(json: String): PluginManifest =
        requireNotNull(ManifestParser.parse(json).manifest) { "测试清单本身就不合法" }

    /**
     * 绕过校验直接造一个 [InstalledPlugin]。
     *
     * 用来测**装配层对「绕过校验的调用方」的防御**。`PluginHost` 是 public API，
     * 不能假设每个调用点都先跑过 [ManifestParser] —— 一个绕过校验的调用点
     * 会让「校验通过」变成口头约定。
     *
     * 所以那几条用例必须把不合法的清单喂进去，而 `installed()` 会先
     * 在解析阶段就抛异常，够不到装配层。
     */
    fun raw(
        manifest: PluginManifest,
        settings: Map<String, String> = emptyMap(),
        enabled: Boolean = true,
        mcpCache: McpToolCache? = null,
    ): InstalledPlugin = InstalledPlugin(
        manifest = manifest,
        settings = com.aichat.plugin.runtime.PluginSettings(settings),
        enabled = enabled,
        mcpCache = mcpCache,
    )

    /** 一个 MCP 清单对象，字段可随意给成不合法的值。 */
    fun mcpObject(
        id: String = "pub.a.mcp",
        transport: McpTransport = McpTransport.Http,
        url: String? = "https://api.example.com/mcp",
        network: List<String> = listOf("api.example.com"),
        auth: AuthSpec = AuthSpec(),
        headers: Map<String, String> = emptyMap(),
        tools: List<String> = emptyList(),
    ) = PluginManifest(
        id = id,
        name = "MCP 插件 $id",
        version = "1.0.0",
        runtime = PluginRuntimeKind.Mcp,
        permissions = PluginPermissions(network = network),
        entry = PluginEntry(
            mcp = McpEntry(
                transport = transport,
                url = url,
                auth = auth,
                headers = headers,
                tools = tools,
            ),
        ),
        tools = emptyList(),
    )

    /** 一个声明式清单对象，字段可随意给成不合法的值。 */
    fun declarativeObject(
        id: String = "pub.a.one",
        baseUrl: String = "https://api.example.com",
        network: List<String> = listOf("api.example.com"),
        auth: AuthSpec = AuthSpec(),
        tools: List<ToolSpec> = listOf(defaultTool()),
    ) = PluginManifest(
        id = id,
        name = "插件 $id",
        version = "1.0.0",
        runtime = PluginRuntimeKind.Declarative,
        permissions = PluginPermissions(network = network),
        entry = PluginEntry(declarative = DeclarativeEntry(baseUrl = baseUrl, auth = auth)),
        tools = tools,
    )

    fun defaultTool(
        name: String = "do_thing",
        request: RequestSpec? = RequestSpec(path = "/p"),
    ) = ToolSpec(
        name = name,
        description = "测试用工具 $name",
        parameters = kotlinx.serialization.json.buildJsonObject {},
        request = request,
    )

    /**
     * 一个脚本清单对象，字段可随意给成不合法的值。
     *
     * 和 [declarativeObject] 同一个用途：测**装配层对「绕过校验的调用方」的防御**。
     * 比如「引擎没装 **且** 入口文件也不在 `files` 里」—— 这种清单
     * `installed()` 会在解析阶段就抛（校验层新增了那条错误），够不到装配层。
     */
    fun scriptObject(
        id: String = "pub.a.script",
        name: String = "脚本插件 $id",
        network: List<String> = emptyList(),
        filesystem: FilesystemScope = FilesystemScope.Read,
        main: String = "index.js",
        files: Map<String, String> = mapOf(main to FakeScriptRuntime.DEFAULT_SOURCE),
        tools: List<ToolSpec> = listOf(scriptTool()),
    ) = PluginManifest(
        id = id,
        name = name,
        version = "1.0.0",
        runtime = PluginRuntimeKind.Script,
        permissions = PluginPermissions(network = network, filesystem = filesystem),
        entry = PluginEntry(script = ScriptEntry(main = main)),
        tools = tools,
        files = files,
    )

    /** 一个脚本工具：**没有 `request` 段** —— 干什么由 JS 自己决定。 */
    fun scriptTool(
        name: String = "csv_stats",
        requiresConfirmation: Boolean? = null,
    ) = ToolSpec(
        name = name,
        description = "脚本工具 $name",
        parameters = kotlinx.serialization.json.buildJsonObject {},
        requiresConfirmation = requiresConfirmation,
    )

    private fun List<String>.jsonArray(): String =
        joinToString(",", "[", "]") { "\"$it\"" }

    /**
     * 把一串字符串编成 JSON object 文本。
     *
     * 走 kotlinx 的序列化器，而不是手写 `"\"$k\":\"$v\""`：**源码里有引号和换行**，
     * 手拼会造出非法 JSON，而症状是「清单解析失败」—— 看起来像被测代码的毛病，
     * 其实是夹具自己坏了。
     */
    private fun Map<String, String>.jsonObject(): String =
        Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), this)
}
