package com.aichat.plugin.manifest

import com.aichat.plugin.template.Placeholder
import com.aichat.plugin.template.Placeholders
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 清单解析 + 校验。
 *
 * ## 严格模式是一条安全性质，不是洁癖
 *
 * [json] 里 `ignoreUnknownKeys = false` 是刻意的，而且不能为了「兼容性」关掉。
 * 想一下拼错 `requiresConfirmation` 会发生什么：
 *
 * | 解析模式 | 结果 |
 * |---|---|
 * | 宽松（忽略未知键） | 这个字段被当成**没写** → 默认 `false` → 本该弹窗确认的写操作**直接执行** |
 * | 严格（报错） | 安装失败，作者看到「不认识的字段 `requireConfirmation`」 |
 *
 * 同一件事对 `permissions.network` 拼错也成立：宽松模式下插件会静默地
 * 拿不到任何网络权限，作者调试半天「为什么请求全被拦」。**报错比猜测便宜得多。**
 *
 * 所以清单格式是**封闭**的（`manifest.schema.json` 里也是
 * `additionalProperties: false`）。要加字段就得同时改 schema 和这里的模型，
 * 那是应该付的成本 —— 它是给第三方写的东西，契约越明确越好。
 *
 * ## 校验分两层
 *
 * 1. **结构**：字段类型、必填、未知键 —— 交给 kotlinx.serialization
 * 2. **语义**：`runtime` 和 `entry` 对不对得上、`auth.settingKey` 指向的配置项
 *    是否存在、模板里的占位符有没有拼错 —— 这些是本节手写的
 *
 * 第 2 层里最值钱的是**占位符校验**：`{{latitide}}` 拼错的话，
 * 宽松处理会把它原样拼进 URL，报回来一个 400，而作者会去怀疑服务端。
 */
object ManifestParser {

    private val json = Json {
        // 见 KDoc：关掉未知键容忍是安全性质
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
        encodeDefaults = true
    }

    private val ID_PATTERN = Regex("""^[a-z0-9]+([.-][a-z0-9]+)+$""")
    private val VERSION_PATTERN = Regex("""^\d+\.\d+\.\d+$""")
    private val TOOL_NAME_PATTERN = Regex("""^[a-z][a-z0-9_]*$""")
    private val SETTING_KEY_PATTERN = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")

    /**
     * 主机名白名单的合法形态。
     *
     * 允许 `*`、`[IPv6]`、普通域名/IPv4。刻意**不允许**写成
     * `https://api.example.com/v1` 这种带协议或路径的形式 ——
     * 那会让人以为「路径也被限制了」，而实际匹配只看主机名。
     * 与其让它静默地只取主机名，不如直接报错。
     */
    private val HOST_PATTERN = Regex(
        """^(\*|\[[0-9A-Fa-f:]+\]|[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*)$""",
    )

    private const val TOOL_DESCRIPTION_MAX = 300

    /** 从 JSON 文本解析。文本本身不是合法 JSON 时也会得到一条可读的问题。 */
    fun parse(text: String): ManifestCheck {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return ManifestCheck(
                manifest = null,
                problems = listOf(
                    ManifestProblem("$", "不是合法的 JSON：${e.message?.lineSequence()?.firstOrNull() ?: "解析失败"}"),
                ),
            )
        }
        return parse(element)
    }

    fun parse(element: JsonElement): ManifestCheck {
        val manifest = try {
            json.decodeFromJsonElement(PluginManifest.serializer(), element)
        } catch (e: SerializationException) {
            return ManifestCheck(manifest = null, problems = listOf(structuralProblem(e)))
        }
        return check(manifest)
    }

    /** 只跑语义校验。已经拿到 [PluginManifest] 对象时用它。 */
    fun check(manifest: PluginManifest): ManifestCheck {
        val problems = mutableListOf<ManifestProblem>()

        checkIdentity(manifest, problems)
        checkRuntimeEntry(manifest, problems)
        checkPermissions(manifest, problems)
        checkSettings(manifest, problems)
        checkAuth(manifest, problems)
        checkTools(manifest, problems)

        val hasError = problems.any { it.severity == ManifestProblem.Severity.Error }
        return ManifestCheck(
            manifest = if (hasError) null else manifest,
            problems = problems,
        )
    }

    // ---------------------------------------------------------------- 结构层

    /**
     * 把 kotlinx.serialization 的异常翻译成给作者看的话。
     *
     * 它的原始信息是英文的、而且默认不带字段路径（只有部分异常带 `at path:`），
     * 直接透出去作者基本没法用。所以这里做两件事：
     * 抓出它给的路径、把「未知键」这一类单独改写成带解释的话。
     */
    private fun structuralProblem(e: SerializationException): ManifestProblem {
        val raw = e.message.orEmpty()
        val path = PATH_PATTERN.find(raw)?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { "$" }

        if (raw.contains("unknown key", ignoreCase = true)) {
            val key = QUOTED.find(raw)?.groupValues?.get(1)
            val shown = if (key.isNullOrBlank()) "某个字段" else "「$key」"
            return ManifestProblem(
                path = path,
                message = "不认识字段 $shown。" +
                    "清单格式是封闭的：拼错一个字段名（比如把 requiresConfirmation 写成 " +
                    "requireConfirmation）会被当成「没写」而取默认值 —— " +
                    "对确认开关来说，那意味着本该弹窗的写操作直接执行。" +
                    "所以这里一律报错。请对照 manifest.schema.json 核对字段名。",
            )
        }

        if (raw.contains("should be quoted", ignoreCase = true)) {
            // kotlinx 的原话是 "String literal for value of key 'x' should be quoted"。
            // 作者看到的场景几乎一定是「query 里写了个数字/布尔」：
            //   "query": { "forecast_days": 3 }      ← 想这么写
            //   "query": { "forecast_days": "3" }    ← 得这么写
            // 这句英文既没说清是哪个字段，也没说该怎么改
            val key = QUOTED.find(raw)?.groupValues?.get(1)
            return ManifestProblem(
                path = path,
                message = "字段${if (key.isNullOrBlank()) "" else "「$key」"}的值类型不对，" +
                    "需要写成带引号的字符串。" +
                    "最常见的原因是 query 或 headers 里写了数字或布尔值 —— " +
                    "写成 \"3\" 而不是 3，写成 \"true\" 而不是 true。" +
                    "（查询参数和请求头在网络上本来就是字符串，所以清单里也统一用字符串，" +
                    "这样它们才可能放进 {{占位符}}。）",
            )
        }

        return ManifestProblem(path = path, message = raw.lineSequence().firstOrNull() ?: "清单解析失败")
    }

    private val PATH_PATTERN = Regex("""path:\s*(\$[^\s,)]*)""")
    private val QUOTED = Regex("""'([^']*)'""")

    // ---------------------------------------------------------------- 身份

    private fun checkIdentity(m: PluginManifest, out: MutableList<ManifestProblem>) {
        if (!ID_PATTERN.matches(m.id)) {
            out += ManifestProblem(
                "$.id",
                "「${m.id}」不是合法的插件标识。要求反域名风格、全小写，" +
                    "至少两段用 `.` 或 `-` 隔开，例如 pub.example.weather。" +
                    "标识安装后不可变更 —— 它是权限授权的键，改了等于换一个插件。",
            )
        }
        if (m.name.isBlank() || m.name.length > 40) {
            out += ManifestProblem("$.name", "插件名必须是 1~40 个字符，现在是 ${m.name.length} 个。")
        }
        if (!VERSION_PATTERN.matches(m.version)) {
            out += ManifestProblem(
                "$.version",
                "版本号要写成三段式 `1.0.0`，现在是「${m.version}」。" +
                    "宿主靠它判断升级，写成 `1.0` 或 `v1` 会让升级判断失效。",
            )
        }
        m.homepage?.let { url ->
            if (url.toHttpUrlOrNull() == null) {
                out += ManifestProblem("$.homepage", "「$url」不是合法的 http/https 地址。")
            }
        }
    }

    // ---------------------------------------------------------------- runtime 与 entry

    private fun checkRuntimeEntry(m: PluginManifest, out: MutableList<ManifestProblem>) {
        val e = m.entry
        when (m.runtime) {
            PluginRuntimeKind.Declarative -> {
                val d = e.declarative
                if (d == null) {
                    out += ManifestProblem(
                        "$.entry.declarative",
                        "runtime 是 declarative，但 entry 里没有 declarative 段。" +
                            "声明式插件必须给 baseUrl，否则宿主不知道该往哪发请求。",
                    )
                    return
                }
                checkBaseUrl(d.baseUrl, out)
                checkBaseUrlAllowed(d.baseUrl, m, out)
                checkEntryHeaders(d.headers, m, "$.entry.declarative.headers", out)
            }

            PluginRuntimeKind.Script -> if (e.script == null) {
                out += ManifestProblem("$.entry.script", "runtime 是 script，但 entry 里没有 script 段（至少要给 main）。")
            }

            PluginRuntimeKind.Mcp -> if (e.mcp == null) {
                out += ManifestProblem("$.entry.mcp", "runtime 是 mcp，但 entry 里没有 mcp 段。")
            } else {
                checkMcpEntry(m, e.mcp, out)
            }

            PluginRuntimeKind.Native -> if (e.native == null) {
                out += ManifestProblem("$.entry.native", "runtime 是 native，但 entry 里没有 native 段（至少要给 library）。")
            } else if (!e.native.library.matches(Regex("""^lib[a-z0-9_]+\.so$"""))) {
                out += ManifestProblem(
                    "$.entry.native.library",
                    "「${e.native.library}」不符合 lib*.so 命名。Android 只会把 lib 前缀的 .so " +
                        "打进 release 包，别的名字在调试包里能跑、打包后就找不到。",
                )
            }
        }
    }

    private fun checkBaseUrl(raw: String, out: MutableList<ManifestProblem>) {
        val url = raw.toHttpUrlOrNull()
        if (url == null) {
            out += ManifestProblem(
                "$.entry.declarative.baseUrl",
                "「$raw」不是合法的 http/https 地址。" +
                    "必须是带协议的完整地址，例如 https://api.example.com。" +
                    "（常见错误：写成 api.example.com，少了 `https://`。）",
            )
            return
        }
        if (url.query != null || url.fragment != null) {
            out += ManifestProblem(
                "$.entry.declarative.baseUrl",
                "baseUrl 不能带查询串或锚点。工具自己的参数写在 tools[].request 里，" +
                    "写在 baseUrl 上会让每个请求都带上它，而且会被工具参数覆盖成不确定的结果。",
            )
        }
        if (url.scheme == "http") {
            out += ManifestProblem(
                "$.entry.declarative.baseUrl",
                "baseUrl 用的是 http（明文）。插件参数和 API Key 会以明文经过网络，" +
                    "只有本机调试时才应该这样。",
                severity = ManifestProblem.Severity.Warning,
            )
        }
    }

    /**
     * baseUrl 的主机必须在网络白名单里。
     *
     * 不检查的话，这个插件是**装了但一次都跑不通**的：每个工具的请求都会在
     * 第一跳被守卫拦下。作者写白名单时很容易只想着「我要调哪些第三方接口」，
     * 忘了 baseUrl 自己也是一台机器。
     *
     * 这不算重复劳动 —— 白名单和 baseUrl 是两个字段，它们的一致性没人管的话
     * 只能靠调用时才发现，而那时作者已经在怀疑自己的代码了。
     */
    private fun checkBaseUrlAllowed(raw: String, m: PluginManifest, out: MutableList<ManifestProblem>) {
        val host = raw.toHttpUrlOrNull()?.host ?: return
        if (networkAllows(m, host)) return

        out += ManifestProblem(
            "$.permissions.network",
            "baseUrl 的主机 $host 不在网络白名单里" +
                "（现有：${m.permissions.network.sorted().ifEmpty { listOf("无") }.joinToString("、")}）。" +
                "这样每个工具调用都会在第一跳被守卫拦下 —— 插件装上去一次也跑不通。",
        )
    }

    /**
     * 白名单是否放行某个主机。
     *
     * **必须和 `NetworkGuard` 的判据一致**：大小写不敏感的全等，外加显式的 `*`。
     * 这里宽松一点（放过了运行时会被拦的东西）会让作者在安装时看不到任何提示，
     * 直到第一次调用才收到一个看不懂的 403；严格一点（拦下运行时其实能过的）
     * 会让合法的清单装不上。两边的规则必须是同一条。
     */
    private fun networkAllows(m: PluginManifest, host: String): Boolean {
        val declared = m.permissions.network
        return declared.any { it.trim() == "*" } ||
            declared.any { it.equals(host, ignoreCase = true) }
    }

    /**
     * 工具级的主机名覆盖。
     *
     * ## 为什么要在安装时报，而不是等调用时
     *
     * 因为它一定会失败 —— 白名单在运行时拦下它，模型收到的是
     * 「插件没有被授权访问 geocoding-api.open-meteo.com」。
     * 那句提示是**写给模型的**（让它换个地址重试），作者在安装界面上
     * 根本看不到。等到用户问一句天气、模型报一句看不懂的错，
     * 作者才会开始查，而那时他已经不记得自己写过这个字段了。
     */
    private fun checkToolHost(
        m: PluginManifest,
        tool: ToolSpec,
        at: String,
        out: MutableList<ManifestProblem>,
    ) {
        val raw = tool.request?.host ?: return
        val host = raw.trim()

        if (host.isEmpty()) {
            out += ManifestProblem(
                "$at.request.host",
                "host 不能是空字符串。不需要换主机就别写这个字段 —— 不写就用 baseUrl 的主机名。",
            )
            return
        }
        // `*` 在权限声明里是有意义的（任意主机），但作为一个「这次请求打去哪」的
        // 值没有意义：请求总得有一个具体的目标
        if (host == "*" || !HOST_PATTERN.matches(host)) {
            out += ManifestProblem(
                "$at.request.host",
                "「$host」不是一个具体的主机名。这里只填主机名（例如 geocoding-api.example.com），" +
                    "不要带协议、路径或端口 —— 协议和端口一律沿用 baseUrl。" +
                    "只改主机名是一条安全性质：允许整段地址的话，作者可以写 http:// " +
                    "把密钥明文发出去，而安装界面上那行权限声明完全不会变。",
            )
            return
        }
        if (!networkAllows(m, host)) {
            out += ManifestProblem(
                "$at.request.host",
                "这个工具要访问 $host，但 permissions.network 里没有它" +
                    "（现有：${m.permissions.network.sorted().ifEmpty { listOf("无") }.joinToString("、")}）。" +
                    "调用时会被白名单拦下，模型只会收到一句看不懂的拒绝 —— 请把主机名补进白名单。",
            )
        }
    }

    /**
     * 插件级静态请求头。
     *
     * 只允许 `{{settings.配置项}}`，**不允许 `{{参数名}}`**：这段头是插件级的、
     * 被所有工具共用，而「参数」是每个工具各自的东西 —— 在共享的头里引用
     * 某个工具的参数，换个工具就成了未定义行为。
     *
     * 顺带守住一个静默失效：作者在这里写了头却没生效（因为宿主没实现），
     * 他会对着一个「看起来声明了」的清单查很久。所以要么支持、要么报错，
     * 不能默默丢掉。
     */
    private fun checkEntryHeaders(
        headers: Map<String, String>,
        m: PluginManifest,
        at: String,
        out: MutableList<ManifestProblem>,
    ) {
        headers.forEach { (name, value) ->
            if (name.isBlank()) {
                out += ManifestProblem("$at", "请求头名字不能为空。")
            }
            // 换行会让一个头变成两个 —— 请求头注入的经典形态。
            // 就算 OkHttp 自己会挡，也不该让这种清单通过校验
            if (name.any { it == '\n' || it == '\r' } || value.any { it == '\n' || it == '\r' }) {
                out += ManifestProblem(
                    "$at.$name",
                    "请求头的名字和值都不能包含换行。换行会让一个头被解析成两个，" +
                        "这是请求头注入的经典形态。",
                )
            }
            if (name.equals("Host", ignoreCase = true)) {
                out += ManifestProblem(
                    "$at.$name",
                    "不能自己设置 Host 头。它会和实际连接的主机名不一致，" +
                        "而且会绕过网络白名单的语义 —— 白名单约束的正是「连到哪台机器」。",
                )
            }

            for (placeholder in Placeholders.scan(value)) {
                when (placeholder) {
                    is Placeholder.Setting -> if (placeholder.name !in m.settings) {
                        out += ManifestProblem(
                            "$at.$name",
                            "占位符 `{{${placeholder.raw}}}` 对应的配置项不存在" +
                                "（现有：${m.settings.keys.sorted().ifEmpty { listOf("无") }.joinToString("、")}）。",
                        )
                    }

                    is Placeholder.Argument -> out += ManifestProblem(
                        "$at.$name",
                        "这里不能用 `{{${placeholder.raw}}}` 这种参数占位符。" +
                            "插件级的请求头被所有工具共用，而参数是每个工具各自的 —— " +
                            "换个工具它就无值可取了。要用密钥请写 `{{settings.配置项名}}`。",
                    )

                    is Placeholder.Malformed -> out += ManifestProblem(
                        "$at.$name",
                        "占位符 `{{${placeholder.raw}}}` 写法不合法。" +
                            "这里只支持 `{{settings.配置项名}}`。",
                    )
                }
            }
        }
    }

    /**
     * MCP 入口。
     *
     * ## 为什么这里也要查主机名白名单
     *
     * 和 [checkToolHost] 是同一个理由：`url` 的主机名不在
     * `permissions.network` 里的话，第一次连接就会被 `NetworkGuard` 拦下，
     * 而拦下的那句话是**写给模型的**（「插件没有被授权访问 x」）。
     * 作者在安装界面上看不到它，只会在用户报「这个插件装了没用」时才开始查。
     *
     * 顺带一条：[McpTransport.Stdio] 在 Android 上永远跑不起来，但**这里不报错** ——
     * 清单是跨宿主的，一个桌面宿主能跑 stdio。所以那是「这台设备上跑不起来」，
     * 属于装配期的问题（见 `PluginHost`），不是清单不合法。
     */
    private fun checkMcpEntry(m: PluginManifest, entry: McpEntry, out: MutableList<ManifestProblem>) {
        when (entry.transport) {
            McpTransport.Stdio -> if (entry.command.isNullOrBlank()) {
                out += ManifestProblem(
                    "$.entry.mcp.command",
                    "transport 是 stdio，必须给 command —— 宿主靠它启动对端进程。",
                )
            }

            McpTransport.Http, McpTransport.Sse -> {
                if (entry.url.isNullOrBlank()) {
                    // 用 lowercase 而不是 `$entry.transport`：后者会打印 Kotlin 枚举名
                    // （`Http`），而作者在清单里写的是 `http` —— 报错信息里的写法
                    // 必须和作者写的一致，否则他会去清单里找一个不存在的值
                    out += ManifestProblem(
                        "$.entry.mcp.url",
                        "transport 是 ${entry.transport.name.lowercase()}，必须给 url。",
                    )
                } else if (entry.url.toHttpUrlOrNull() == null) {
                    out += ManifestProblem("$.entry.mcp.url", "「${entry.url}」不是合法的 http/https 地址。")
                } else {
                    entry.url.toHttpUrlOrNull()?.host?.let { host ->
                        if (!networkAllows(m, host)) {
                            out += ManifestProblem(
                                "$.entry.mcp.url",
                                "这个 MCP 服务的地址在 $host 上，但 permissions.network 里没有它" +
                                    "（现有：${m.permissions.network.sorted().ifEmpty { listOf("无") }.joinToString("、")}）。" +
                                    "连接时会被白名单拦下，模型只会收到一句看不懂的拒绝 —— 请把主机名补进白名单。",
                            )
                        }
                    }
                }
            }
        }

        checkEntryHeaders(entry.headers, m, "$.entry.mcp.headers", out)
    }

    // ---------------------------------------------------------------- 权限

    private fun checkPermissions(m: PluginManifest, out: MutableList<ManifestProblem>) {
        val p = m.permissions

        p.network.forEachIndexed { i, host ->
            if (!HOST_PATTERN.matches(host)) {
                out += ManifestProblem(
                    "$.permissions.network[$i]",
                    "「$host」不是合法的主机名。这里只写主机名（api.example.com），" +
                        "不要带协议、路径或端口 —— 带上会让人以为那些也被限制了，而匹配只看主机名。",
                )
            }
        }

        if (p.network.contains("*")) {
            out += ManifestProblem(
                "$.permissions.network",
                "声明了 `*`（任意主机）。这等于没有网络限制：插件可以把对话内容发到任何地方。",
                severity = ManifestProblem.Severity.Warning,
            )
        }

        if (p.shell) {
            out += ManifestProblem(
                "$.permissions.shell",
                "申请了 shell 权限。这等于把设备交给插件，安装时必须让用户二次确认。",
                severity = ManifestProblem.Severity.Warning,
            )
        }

        if (p.linuxEnv) {
            out += ManifestProblem(
                "$.permissions.linuxEnv",
                "需要 Ubuntu 工作区。首次调用要等环境启动，宿主会据此放宽超时。",
                severity = ManifestProblem.Severity.Warning,
            )
        }

        if (p.device.isNotEmpty()) {
            out += ManifestProblem(
                "$.permissions.device",
                "申请了设备能力：${p.device.joinToString("、") { it.displayName }}。" +
                    "这些是用户隐私数据，安装时要逐项展示。",
                severity = ManifestProblem.Severity.Warning,
            )
        }

        // 声明式插件没有网络白名单 = 每次调用都会被拦。
        // 这是个「装了但完全不工作」的清单错误，作者不看日志很难发现，所以是错误不是警告
        if (m.runtime == PluginRuntimeKind.Declarative && p.network.isEmpty()) {
            out += ManifestProblem(
                "$.permissions.network",
                "声明式插件没有声明任何可访问的主机，所有工具调用都会被网络守卫拦下。" +
                    "至少写上 baseUrl 的主机名（例如 api.example.com）。",
            )
        }
    }

    // ---------------------------------------------------------------- 配置项
    private fun checkSettings(m: PluginManifest, out: MutableList<ManifestProblem>) {
        m.settings.forEach { (key, spec) ->
            if (!SETTING_KEY_PATTERN.matches(key)) {
                out += ManifestProblem(
                    "$.settings.$key",
                    "配置项的名字只能是字母、数字、下划线，且不能以数字开头 —— " +
                        "它会被写进模板占位符 `{{settings.$key}}`，允许别的字符会让占位符解析产生歧义。",
                )
            }
            if (spec.title.isBlank()) {
                out += ManifestProblem("$.settings.$key.title", "配置项必须有 title，它是显示给用户看的标签。")
            }
            if (spec.type == SettingType.Enum && spec.enum.isEmpty()) {
                out += ManifestProblem(
                    "$.settings.$key.enum",
                    "type 是 enum 但没有给 enum 取值，用户会看到一个没有选项的下拉框。",
                )
            }
            val default = spec.default
            if (default != null && spec.type == SettingType.Enum && spec.enum.isNotEmpty() && default !in spec.enum) {
                out += ManifestProblem(
                    "$.settings.$key.default",
                    "默认值 $default 不在 enum 取值 ${spec.enum} 里，用户永远选不到它。",
                )
            }

            // 敏感项的默认值会被忽略（见 `SettingSpec.defaultText`）。
            // 报出来是因为作者写了它、多半以为它生效了 —— 静默忽略的话，
            // 他会看到「明明配了默认值，插件还是说缺配置」而无从下手。
            //
            // 是 warning 而不是 error：这不是「一定不工作」，插件本身能跑，
            // 只是作者以为的那条路被堵了。和「baseUrl 用了 http」同一个级别。
            if (spec.secret && default != null) {
                out += ManifestProblem(
                    "$.settings.$key.default",
                    "敏感项的默认值会被忽略 —— 清单是明文，而且会从网址下载、会被粘贴和分享，" +
                        "凭据只能由用户自己填。想表达「不填也能跑」的话，把那个值做成一个非敏感项" +
                        "（例如 mode: \"demo\"），而不是把凭据写进默认值。",
                    severity = ManifestProblem.Severity.Warning,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 认证

    /**
     * 认证声明的校验。
     *
     * ## 为什么按运行形态分别走一遍
     *
     * 因为 `auth` 字段在**两段里各有一份**（declarative 和 mcp），
     * 而报错路径必须指回作者写的那一段 —— 报 `$.entry.declarative.auth`
     * 而作者写的是 `mcp.auth` 时，他会去一个不存在的地方找。
     *
     * ## 为什么要**两段都查**，而不是只查 `runtime` 对应的那段
     *
     * 一份清单可以同时写 `declarative` 和 `mcp` 两段（宿主只读匹配的那段），
     * 而作者改运行形态时很容易只改 `runtime`、留下一段过期的配置。
     * 把两段都查，那些残留会当场暴露出来。代价是可能报出「暂时用不上」
     * 的错，但那个错本来就是作者要处理的东西。
     */
    private fun checkAuth(m: PluginManifest, out: MutableList<ManifestProblem>) {
        m.entry.declarative?.auth?.let { checkAuthSpec(m, it, "$.entry.declarative.auth", out) }

        m.entry.mcp?.auth?.let { auth ->
            checkAuthSpec(m, auth, "$.entry.mcp.auth", out)

            // MCP 只有一个固定端点，认证只能走请求头。query 不是「不推荐」，
            // 而是**没法表达**：没有「往哪个查询参数上挂」的语义
            if (auth.type == AuthType.Query) {
                out += ManifestProblem(
                    "$.entry.mcp.auth.type",
                    "MCP 的地址是一个固定端点，认证不能走查询参数 —— 请用 bearer " +
                        "（Authorization: Bearer）或 header（自定义请求头）。" +
                        "把密钥放在 URL 里还会让它出现在服务端的访问日志和任何中间代理上。",
                )
            }
        }
    }

    private fun checkAuthSpec(
        m: PluginManifest,
        auth: AuthSpec,
        path: String,
        out: MutableList<ManifestProblem>,
    ) {
        if (auth.type == AuthType.None) {
            // 声明了 none 却又给 settingKey，多半是改配置时漏删
            if (auth.settingKey != null) {
                out += ManifestProblem(
                    "$path.settingKey",
                    "auth.type 是 none，settingKey 不会被使用。要么把 type 改成 bearer/header/query，" +
                        "要么删掉这个字段 —— 留着会让作者以为密钥已经配上了。",
                    severity = ManifestProblem.Severity.Warning,
                )
            }
            return
        }

        val key = auth.settingKey
        if (key.isNullOrBlank()) {
            out += ManifestProblem(
                "$path.settingKey",
                "auth.type 是 ${auth.type.name.lowercase()}，必须给 settingKey 指明密钥取自哪个配置项。",
            )
        } else if (key !in m.settings) {
            out += ManifestProblem(
                "$path.settingKey",
                "settingKey 指向「$key」，但 settings 里没有这一项" +
                    "（现有：${m.settings.keys.sorted().ifEmpty { listOf("无") }.joinToString("、")}）。" +
                    "指向不存在的配置项会让密钥**静默地不发送**，用户只会看到一个 401，根本查不到原因。",
            )
        }

        when (auth.type) {
            AuthType.Header -> if (auth.headerName.isNullOrBlank()) {
                out += ManifestProblem("$path.headerName", "auth.type 是 header，必须给 headerName。")
            }

            AuthType.Query -> if (auth.queryName.isNullOrBlank()) {
                out += ManifestProblem("$path.queryName", "auth.type 是 query，必须给 queryName。")
            }

            else -> Unit
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun checkTools(m: PluginManifest, out: MutableList<ManifestProblem>) {
        if (m.tools.isEmpty()) {
            // mcp 是例外：工具清单由对端在装配时**动态**给出，作者在清单里
            // 写不出来。要求它非空的话，作者只能瞎编几个名字凑数 ——
            // 而那些名字永远不会被用到，却会出现在「这个插件提供什么」的界面上，
            // 让用户照着它去问模型，而模型根本没有那个工具。
            if (m.runtime == PluginRuntimeKind.Mcp) return

            out += ManifestProblem("$.tools", "一个插件至少要暴露一个工具，否则装进来没有任何作用。")
            return
        }

        val seen = mutableSetOf<String>()
        m.tools.forEachIndexed { i, tool ->
            val at = "$.tools[$i]"

            if (!TOOL_NAME_PATTERN.matches(tool.name)) {
                out += ManifestProblem(
                    "$at.name",
                    "「${tool.name}」不是合法的工具名。要求小写字母开头、只含小写字母数字和下划线 —— " +
                        "这个名字会直接作为 function calling 的 name 发给模型，OpenAI 兼容接口对它有格式要求。",
                )
            }
            if (!seen.add(tool.name)) {
                out += ManifestProblem("$at.name", "工具名「${tool.name}」在本插件里重复了，模型无法区分它们。")
            }
            if (tool.description.isBlank()) {
                out += ManifestProblem(
                    "$at.description",
                    "工具说明不能为空。它是**提示词**，直接决定模型会不会选对工具 —— " +
                        "至少要写清什么时候该用、什么时候不该用。",
                )
            } else if (tool.description.length > TOOL_DESCRIPTION_MAX) {
                out += ManifestProblem(
                    "$at.description",
                    "工具说明 ${tool.description.length} 字，超过 $TOOL_DESCRIPTION_MAX 字上限。" +
                        "过长的说明会挤占上下文，而且模型对后半段的注意力会明显下降。",
                )
            }

            // dangerous 是「高危」的声明，而 requiresConfirmation 是唯一实际生效的保护。
            // 两者矛盾时按更安全的一边解释：报错，逼作者二选一。
            //
            // 判据是 `!= true` 而不是 `== false`：没写（null）时宿主会按 HTTP 方法兜底
            // （非 GET 一律确认），所以「没写」是安全的，不该报错
            if (tool.dangerous && tool.requiresConfirmation != true) {
                out += ManifestProblem(
                    "$at",
                    "标了 dangerous 却没有把 requiresConfirmation 设为 true。dangerous 只是给自动模式看的提示，" +
                        "当前唯一实际生效的保护就是弹窗确认 —— 请显式写上 true。",
                )
            }

            // 作者明确关掉确认，而这个工具会发非 GET 请求。
            // 不报错（有些接口确实用 POST 做只读查询），但要说出来 ——
            // 用户在一次数据被改掉之后，唯一能回看的就是这条记录
            if (tool.requiresConfirmation == false &&
                tool.request?.method?.let { it != HttpMethod.Get } == true
            ) {
                out += ManifestProblem(
                    "$at.requiresConfirmation",
                    "作者明确关掉了确认弹窗，但 request.method 是 ${tool.request.method.wire}。" +
                        "非 GET 请求可能改动服务端数据（RFC 9110 只把 GET/HEAD 定义为安全方法）。" +
                        "如果这里确实是只读查询，建议改用 GET；确实需要 POST 的话，请确认你知道这个接口的副作用。",
                    severity = ManifestProblem.Severity.Warning,
                )
            }

            checkToolRequest(m, tool, at, out)
        }
    }

    private fun checkToolRequest(
        m: PluginManifest,
        tool: ToolSpec,
        at: String,
        out: MutableList<ManifestProblem>,
    ) {
        if (m.runtime != PluginRuntimeKind.Declarative) {
            if (tool.request != null) {
                out += ManifestProblem(
                    "$at.request",
                    "runtime 是 ${m.runtime.name.lowercase()}，request 不会生效（它只用于 declarative）。" +
                        "留着会让作者以为请求是这样发出去的。",
                    severity = ManifestProblem.Severity.Warning,
                )
            }
            return
        }

        val request = tool.request
        if (request == null) {
            out += ManifestProblem(
                "$at.request",
                "声明式插件的每个工具都必须给 request（method / path / query），否则宿主不知道该发什么请求。",
            )
            return
        }

        if (request.path.isBlank()) {
            out += ManifestProblem("$at.request.path", "path 不能为空。")
        }
        if (request.path.toHttpUrlOrNull() != null) {
            out += ManifestProblem(
                "$at.request.path",
                "path 必须是相对 baseUrl 的路径，不能是完整地址。" +
                    "写成完整地址会让这个工具绕过 baseUrl 直接指向别的主机 —— " +
                    "虽然网络白名单仍然会拦，但那份声明是给用户看的，不该被这样绕过。" +
                    "要换主机请用 request.host（只填主机名）。",
            )
        }

        checkToolHost(m, tool, at, out)

        // 占位符校验：拼错一个字母就会静默变成字面文本，报回来一个 400，
        // 而作者会去怀疑服务端
        val available = parameterNames(tool)
        val settingKeys = m.settings.keys

        fun checkTemplate(value: String, where: String) {
            for (placeholder in Placeholders.scan(value)) {
                when (placeholder) {
                    is Placeholder.Malformed -> out += ManifestProblem(
                        where,
                        "占位符 `{{${placeholder.raw}}}` 写法不合法。" +
                            "只支持 `{{参数名}}` 和 `{{settings.配置项名}}`，名字只能是字母数字下划线。",
                    )

                    is Placeholder.Argument -> if (placeholder.name !in available) {
                        out += ManifestProblem(
                            where,
                            "占位符 `{{${placeholder.raw}}}` 对应的参数不存在。" +
                                "这个工具的 parameters.properties 里只有 " +
                                "${available.sorted().ifEmpty { listOf("（没有属性）") }.joinToString("、")}。" +
                                "拼错的占位符会被原样拼进请求，报回来一个看不懂的 400。",
                        )
                    }

                    is Placeholder.Setting -> if (placeholder.name !in settingKeys) {
                        out += ManifestProblem(
                            where,
                            "占位符 `{{${placeholder.raw}}}` 对应的配置项不存在" +
                                "（现有：${settingKeys.sorted().ifEmpty { listOf("无") }.joinToString("、")}）。",
                        )
                    }
                }
            }
        }

        checkTemplate(request.path, "$at.request.path")
        request.query.forEach { (name, value) -> checkTemplate(value, "$at.request.query.$name") }
        collectStrings(request.body).forEach { checkTemplate(it, "$at.request.body") }

        // 请求体只有 POST/PUT/PATCH 才有意义
        if (request.body != null && request.method !in setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)) {
            out += ManifestProblem(
                "$at.request.body",
                "method 是 ${request.method.wire}，带请求体不会生效。GET/DELETE 用 query 传参。",
                severity = ManifestProblem.Severity.Warning,
            )
        }
    }

    /** JSON Schema 里顶层 `properties` 的名字，也就是能出现在占位符里的参数名。 */
    private fun parameterNames(tool: ToolSpec): Set<String> {
        val properties = tool.parameters["properties"] as? JsonObject ?: return emptySet()
        return properties.keys
    }

    /** 递归收集 JSON 里所有字符串值（含数组元素和对象的值）。 */
    private fun collectStrings(element: JsonElement?): List<String> = when (element) {
        null -> emptyList()
        is JsonPrimitive -> if (element.isString) listOf(element.content) else emptyList()
        is JsonArray -> element.flatMap { collectStrings(it) }
        is JsonObject -> element.values.flatMap { collectStrings(it) }
    }
}
