package com.aichat.plugin.runtime.mcp

import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolResult
import com.aichat.plugin.permission.NetworkDeniedException
import com.aichat.plugin.permission.NetworkGuard
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * 一个 MCP 服务端。
 *
 * ## 它最反直觉的地方：现代版**没有握手**
 *
 * 早期资料（和绝大多数现存的 MCP 客户端实现）都是「先 `initialize` 拿会话，
 * 再发业务请求」。2026-07-28 修订版把这条路径整个换掉了：版本、客户端信息、
 * 能力声明**跟着每个请求走**，没有会话、没有握手、没有连接状态。
 *
 * 所以这里的形状是：
 *
 * ```
 * 第一次请求：按现代版直接发 tools/list
 *    ├─ 成功                    → 对端是现代版，一路现代版
 *    ├─ 4xx + 认得出来的现代错误 → 对端是现代版，按它说的版本重谈（**不回退**）
 *    └─ 4xx + 认不出来的 body    → 对端是旧版 → initialize 握手，之后走旧版
 * ```
 *
 * 「认得出来」这一条是规范给 HTTP 绑定的检测机制，也是唯一能区分
 * 「旧版服务端」和「现代服务端拒绝了这个请求」的办法 —— 两者都回 4xx。
 * 认错的代价是不对称的：把现代服务端当成旧版，会对着它发一个它不认识的
 * `initialize`；把旧版当成现代版，则是每次请求都少一个必需的头。
 *
 * ## 判定结果缓存在实例上
 *
 * 规范说「对端的年代是**服务端**的属性，不是单个请求的属性」，并建议
 * 按 origin 缓存。这个类就是那个缓存 —— 每次装配建一个新实例，
 * 生命周期内只判定一次。
 *
 * ## [knownEra]：把上一次连接谈成的结果带回来
 *
 * 装配路径是**读缓存**的（`PluginHost.tools()` 不能联网），所以它拿不到
 * 一次 `tools/list` 来触发判定 —— 而 [callTool] 走的 [rpc] **不会**判定。
 * 于是一个旧版对端会在 `tools/call` 上被当成现代版发出去，然后失败。
 *
 * 所以上一次连接谈成的年代必须能从缓存里带回来。这里有一条**不对称**：
 *
 * - 缓存说 [McpEra.Modern] → 直接用，省一次往返。现代版不会退回旧版。
 * - 缓存说 [McpEra.Legacy] → 也直接用，但**握手被拒时会重新判定一次**
 *   （见 [rpc]）。因为服务端升级是常事，而降级不是。
 *
 * 把这条不对称写出来，是因为「缓存说 X 就直接信 X」听起来是对称的、
 * 显然的，而它会让一个升级过的对端永久失效直到用户手动刷新。
 */
class McpClient(
    endpoint: HttpUrl,
    private val guard: NetworkGuard,
    client: OkHttpClient,
    private val clientInfo: McpClientInfo = McpClientInfo.Default,
    extraHeaders: Map<String, String> = emptyMap(),
    knownEra: McpEra? = null,
) {

    /** 对端主机名。给用户和模型看的报错都用它，不用整段 URL。 */
    val host: String = endpoint.host

    private val transport = McpHttpTransport(endpoint, guard, client, clientInfo, extraHeaders)

    private var nextId = 0L
    private var era = knownEra ?: McpEra.Unknown
    private var version = McpWire.PROTOCOL_VERSION
    private var handshaken = false

    /**
     * 当前的年代是**从缓存带进来的**（不是自己探出来的）。
     *
     * 只有它为 true 时才允许「握手被拒 → 重新判定」：自己探出来的结论
     * 不需要再怀疑一次，否则每一次失败都会多打一轮。
     */
    private var eraFromCache = knownEra != null

    /** 实际谈成的协议版本。诊断用，会显示在插件详情页。 */
    val negotiatedVersion: String get() = version

    /** 对端属于哪一代。`null` 表示还没发过请求。 */
    val detectedEra: McpEra? get() = era.takeIf { it != McpEra.Unknown }

    // ------------------------------------------------------------------ 工具列表

    /**
     * 列出对端提供的工具，附带「对端一共报了几个」。
     *
     * 第一次调用会顺带完成「对端是哪一代」的判定。
     *
     * ## 为什么要多给一个 [McpDiscovery.offered]
     *
     * 因为 `tools/list` 里没有名字的条目会被丢掉（没有名字就没法调用），
     * 于是「服务端文档说有 5 个工具、App 里只有 3 个」这种不一致
     * 必须有地方解释。用户看到工具少了一个时，唯一能让他判断
     * 「是我配置错了还是对端的问题」的信息就是这个数字。
     */
    fun discover(): McpDiscovery = parseTools(list())

    /**
     * [discover] 的便捷形式，只要工具本身。
     *
     * 保留它是因为绝大多数调用方（包括测试）不关心 [McpDiscovery.offered]，
     * 而 `discover().tools` 出现在十几个地方会把「这里其实在做一次发现」
     * 这件事说得很啰嗦。两个入口，一个实现。
     */
    fun listTools(): List<McpToolDescriptor> = discover().tools

    private fun list(): JsonElement = when (era) {
        McpEra.Modern -> rpc("tools/list", null)
        McpEra.Legacy -> {
            handshake()
            rpc("tools/list", null)
        }
        McpEra.Unknown -> detect()
    }

    /**
     * 第一次请求：按现代版发，从失败里读出对端的年代。
     *
     * 刻意用 `tools/list` 而不是 `server/discover` 来探测：规范允许
     * 「直接调任意 RPC，遇到不支持的版本再处理」，而探测用的那次请求
     * 如果本身就有用，就省掉一次往返。`server/discover` 的好处是
     * 错误更确定，代价是每个插件每次装配都多一次网络往返 ——
     * 而装配发生在用户点「安装」的时候，那时候的延迟是能感觉到的。
     */
    private fun detect(): JsonElement {
        try {
            val response = transport.post(
                method = "tools/list",
                params = null,
                id = nextId(),
                modern = true,
                version = McpWire.PROTOCOL_VERSION,
            )
            era = McpEra.Modern
            return resultOf(response)
        } catch (e: McpHttpFailure) {
            // 只有这几个状态码才可能是「这个请求在旧服务端上不成立」。
            // 别的（403、5xx、429）就是失败本身，回退只会把错误信息搅浑
            if (e.status !in McpWire.FALLBACK_STATUSES) throw e

            val err = e.rpcError
                ?: return legacyFallback()

            // 认得出来 → 对端是现代版。**不能回退**：它认识 `_meta`，
            // 只是这一项没谈拢，回退到握手只会让它更困惑
            return handleModernError(err)
        }
    }

    private fun handleModernError(err: JsonRpcError): JsonElement {
        if (err.code == McpWire.CODE_UNSUPPORTED_VERSION) {
            val supported = supportedVersions(err)
            when {
                // 我们只实现这一个现代版本，所以只有它出现时才能重谈
                McpWire.PROTOCOL_VERSION in supported -> {
                    era = McpEra.Modern
                    return rpc("tools/list", null)
                }

                // 双代服务端只实现了旧版本 → 走它认的那条路
                supported.isNotEmpty() && supported.all { it < McpWire.MODERN_EPOCH } -> {
                    return legacyFallback()
                }

                else -> throw McpFailure(
                    "MCP 服务 $host 支持的协议版本是 ${supported.joinToString("、").ifEmpty { "（没列出）" }}，" +
                        "本版本都不支持。重试也不会成功，请告诉用户换一个 MCP 服务。",
                    retryable = false,
                )
            }
        }

        throw McpFailure(
            "MCP 服务 $host 拒绝了请求：${err.message}（错误码 ${err.code}）。" +
                if (err.code == McpWire.CODE_HEADER_MISMATCH) {
                    "这是协议头与请求体不一致，属于本 App 的兼容性问题，请反馈。"
                } else {
                    "重试不会改变结果，请告诉用户检查这个 MCP 服务的配置。"
                },
            retryable = false,
        )
    }

    private fun legacyFallback(): JsonElement {
        era = McpEra.Legacy
        handshake()
        return rpc("tools/list", null)
    }

    /**
     * 旧协议的握手。
     *
     * 三步，缺一步对端都不该开始服务：`initialize` → 记下会话 id →
     * `notifications/initialized`。会话 id 是**响应头**给的，不是 body ——
     * 它由 [McpHttpTransport] 在读到响应时记下来，之后每个请求自动带上。
     */
    private fun handshake() {
        if (handshaken) return

        val response = transport.post(
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", JsonPrimitive(McpWire.LEGACY_VERSION))
                put("capabilities", buildJsonObject {})
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", JsonPrimitive(clientInfo.name))
                        put("version", JsonPrimitive(clientInfo.version))
                    },
                )
            },
            id = nextId(),
            modern = false,
            version = McpWire.LEGACY_VERSION,
        )

        val result = resultOf(response)
        // 对端可能把版本降到一个它支持的旧版本 —— 以它回的为准
        (result as? JsonObject)?.get("protocolVersion")
            ?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { version = it }

        handshaken = true

        // 这一步不能省：旧协议里对端在收到 initialized 之前不该处理业务请求
        transport.post(
            method = "notifications/initialized",
            params = JsonObject(emptyMap()),
            id = null,
            modern = false,
            version = version,
        )
    }

    // ------------------------------------------------------------------ 调用

    /**
     * 调用一个工具。
     *
     * [arguments] 是**模型给的**，一律不可信 —— 但它在这里的去向只有两个：
     * 进 JSON 请求体（由序列化器转义），以及按 schema 声明镜像成请求头
     * （由 [McpWire.encodeHeaderValue] 编码）。两处都不做字符串拼接，
     * 所以模型没法用参数值改变请求的形状。
     */
    fun callTool(descriptor: McpToolDescriptor, arguments: JsonObject): McpCallResult {
        val params = buildJsonObject {
            put("name", JsonPrimitive(descriptor.name))
            put("arguments", arguments)
        }

        // x-mcp-header：对端在 schema 里声明「这个参数同时要出现在请求头里」。
        // 规范要求客户端必须支持 —— 不支持的话，那些靠头做路由/鉴权的工具
        // 会报「参数缺失」，而参数明明在 body 里，排查方向完全是错的
        val paramHeaders = descriptor.headerParams.mapNotNull { (param, headerName) ->
            val value = (arguments[param] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            headerName to value
        }.toMap()

        return render(rpc("tools/call", params, paramHeaders))
    }

    // ------------------------------------------------------------------ 内部

    private fun rpc(
        method: String,
        params: JsonObject?,
        paramHeaders: Map<String, String> = emptyMap(),
    ): JsonElement {
        if (era == McpEra.Legacy) {
            try {
                handshake()
            } catch (e: McpHttpFailure) {
                // 缓存说对端是旧版，但握手被拒了 —— 它大概已经升级成现代版。
                // 丢掉缓存里的判定，按现代版重发一次。
                //
                // 只在这一种情况下重试（[eraFromCache] 为 true 且状态码是
                // 「这个请求在旧服务端上不成立」的那几个），否则一次网络抖动
                // 会被当成「对端换了一代」，然后多打一轮没意义的请求。
                // 重试后 [eraFromCache] 已经是 false，所以不会无限递归。
                if (!eraFromCache || e.status !in McpWire.FALLBACK_STATUSES) throw e

                eraFromCache = false
                era = McpEra.Unknown
                handshaken = false
                return rpc(method, params, paramHeaders)
            }
        }
        return resultOf(
            transport.post(
                method = method,
                params = params,
                id = nextId(),
                modern = era != McpEra.Legacy,
                version = version,
                paramHeaders = paramHeaders,
            ),
        )
    }

    private fun resultOf(response: JsonRpcResponse?): JsonElement {
        if (response == null) {
            throw McpFailure(
                "MCP 服务 $host 没有返回结果。重试也不会成功，请告诉用户这个 MCP 服务可能有问题。",
                retryable = false,
            )
        }
        response.error?.let { err ->
            throw McpFailure(
                "MCP 服务 $host 返回错误：${err.message}（错误码 ${err.code}）。" +
                    "重试不会改变结果，请告诉用户检查这个 MCP 服务的配置。",
                retryable = false,
            )
        }
        return response.result ?: JsonNull
    }

    private fun nextId(): Long = ++nextId

    private fun supportedVersions(err: JsonRpcError): List<String> {
        val data = err.data as? JsonObject ?: return emptyList()
        val array = data["supported"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    }

    private fun parseTools(result: JsonElement): McpDiscovery {
        val obj = result as? JsonObject ?: throw McpFailure(
            "MCP 服务 $host 的 tools/list 返回的不是一个对象。重试也不会成功。",
            retryable = false,
        )
        val array = obj["tools"] as? JsonArray
            ?: return McpDiscovery(tools = emptyList(), offered = 0)

        val tools = array.mapNotNull { element ->
            val tool = element as? JsonObject ?: return@mapNotNull null
            // 没有名字的工具没法调用 —— 丢掉。但调用方要把「对端给了几个、
            // 能用几个」显示出来，否则用户看到工具比服务端文档里少会以为是 bug
            val name = (tool["name"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            McpToolDescriptor(
                name = name,
                description = describe(tool),
                inputSchema = tool["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
                readOnly = (tool["annotations"] as? JsonObject)
                    ?.get("readOnlyHint")?.jsonPrimitive?.booleanOrNull == true,
            )
        }

        // offered 数的是**条目数**，不是能用的数 —— 两者的差就是被丢掉的那些
        return McpDiscovery(tools = tools, offered = array.size)
    }

    /**
     * 工具的说明文字。
     *
     * ## 为什么说明超长要截断（而不是原样透传）
     *
     * 它会被原样放进发给模型的工具定义里。对端是个远程服务，
     * 一段 20 万字的说明会**直接挤掉整个上下文** —— 而症状是
     * 「对话突然变傻」，看不出是某个插件的说明太长。和
     * `DeclarativeTool` 限制响应大小是同一个理由。
     *
     * 截断要**说明白**：模型看到「说明已截断」才会在需要细节时
     * 先问用户，而不是照着半句话猜。
     *
     * ## 没有说明的工具照样注册
     *
     * 对端不给说明是它的问题。把工具丢掉的话，用户会看到
     * 「服务端文档里说有 5 个工具、插件里只有 3 个」，
     * 而这是最难查的一类不一致。
     */
    private fun describe(tool: JsonObject): String {
        val raw = (tool["description"] as? JsonPrimitive)?.contentOrNull
            ?: (tool["annotations"] as? JsonObject)
                ?.get("title")?.jsonPrimitive?.contentOrNull
            ?: ""
        val text = raw.trim()

        if (text.isEmpty()) {
            return "（对端没有提供说明。请先向用户确认这个工具的用途，不要凭工具名猜测。）"
        }
        if (text.length <= MAX_DESCRIPTION) return text

        return text.take(MAX_DESCRIPTION) +
            "\n\n（说明过长已截断：原文 ${text.length} 字，只保留前 $MAX_DESCRIPTION 字。）"
    }

    /**
     * 把 `tools/call` 的结果翻成给模型看的文字。
     *
     * 三条规矩（和内置工具一致）：
     * 1. **内容为空时返回正常结果，不是错误。** 调用是成功的，
     *    报成错误会让模型以为工具坏了、反复换参数重试。
     * 2. **非文本内容要说出来。** 对端返回一张图而我们看不见时，
     *    沉默会让模型以为「工具什么都没返回」，然后据此下结论。
     * 3. **截断要说出来。**
     */
    private fun render(result: JsonElement): McpCallResult {
        val obj = result as? JsonObject
            ?: return McpCallResult(result.toString(), isError = false)

        val wire = runCatching { McpWire.json.decodeFromJsonElement(McpCallWire.serializer(), obj) }
            .getOrNull()
            ?: return McpCallResult(obj.toString(), isError = false)

        val texts = wire.content.mapNotNull { it.text?.takeIf(String::isNotEmpty) }
        val nonText = wire.content.count { it.type != "text" }

        val body = when {
            texts.isNotEmpty() -> texts.joinToString("\n\n")

            wire.structuredContent != null && wire.structuredContent !is JsonNull ->
                McpWire.json.encodeToString(JsonElement.serializer(), wire.structuredContent)

            nonText > 0 -> "（工具返回了 $nonText 项非文本内容，当前版本看不到它们。" +
                "请告诉用户这次调用确实成功了，但结果需要别的方式查看。）"

            wire.isError -> "（对端说这次调用失败了，但没有给出原因。）"

            else -> "（工具执行成功，但没有返回任何内容。）"
        }

        return McpCallResult(truncate(body), isError = wire.isError)
    }

    private fun truncate(text: String): String =
        if (text.length <= MAX_CONTENT_CHARS) {
            text
        } else {
            text.take(MAX_CONTENT_CHARS) +
                "\n\n（内容过长已截断：原文 ${text.length} 字，只保留前 $MAX_CONTENT_CHARS 字。" +
                "需要完整内容请让用户换一个更精确的查询。）"
        }

    private companion object {
        /** 和 `ManifestParser` 对清单里工具说明的上限一致，理由相同。 */
        const val MAX_DESCRIPTION = 2_000

        /** 和 `DeclarativeTool` / `FetchUrlTool` 一致。 */
        const val MAX_CONTENT_CHARS = 20_000
    }
}

/** `tools/call` 的响应体。对端可以只给 `content`、只给 `structuredContent`，或都不给。 */
@Serializable
private data class McpCallWire(
    val content: List<McpContentBlock> = emptyList(),
    @SerialName("isError") val isError: Boolean = false,
    val structuredContent: JsonElement? = null,
)

/**
 * 一块内容。
 *
 * 只声明 [type] / [text] / [mimeType]：对端可以返回任意类型
 * （`image` / `audio` / `resource` / 将来的扩展类型），
 * 全部用 `ignoreUnknownKeys` 收下，再在渲染时按 `type` 分类。
 * 为每种类型建一个模型的话，对端加一个新类型就会让整条响应解析失败。
 */
@Serializable
private data class McpContentBlock(
    val type: String = "",
    val text: String? = null,
    val mimeType: String? = null,
)

/**
 * 一个 MCP 工具，包成模型能调用的 [Tool]。
 *
 * ## 确认策略：谁能免掉确认
 *
 * ```
 * 声明了任意主机（network: ["*"]）  → 一律确认
 * 否则对端声明 readOnlyHint        → 免确认
 * 否则                             → 确认
 * ```
 *
 * 第一行的优先级是**刻意**的：`readOnlyHint` 是每次连接时从线上下来的，
 * 对端随时可以改，用户看不到改动 —— 它比清单里那句
 * `requiresConfirmation`（用户装的时候看过并授权）弱得多。
 * 所以它只能用来**免掉**确认，不能在「请求发去哪由模型决定」的场景下
 * 免掉确认。那正是 `network: ["*"]` 的意思。
 */
class McpTool(
    private val pluginName: String,
    private val client: McpClient,
    private val descriptor: McpToolDescriptor,
    private val guard: NetworkGuard,
) : Tool {

    override val definition = ToolDefinition(
        name = descriptor.name,
        description = descriptor.description,
        parameters = descriptor.inputSchema,
    )

    /**
     * 给用户看的一句话。
     *
     * 用户真正要判断的是「这次调用会发去哪」，所以给的是主机名 ——
     * 也正是白名单实际约束的东西。和 `DeclarativeTool` 同一个取向。
     */
    override val userSummary: String
        get() = buildString {
            append("插件「$pluginName」将通过 MCP 服务 ${client.host} 调用「${descriptor.name}」")
            when {
                guard.allowsAnyHost ->
                    append("。这个插件声明可以访问任意主机，所以每次都要你确认")

                descriptor.readOnly ->
                    append("。对端声明这个工具只读")

                else ->
                    append("。对端没有声明它只读，可能改动服务端数据，所以每次都要你确认")
            }
            append("。")
        }

    override val requiresConfirmation: Boolean
        get() = guard.allowsAnyHost || !descriptor.readOnly

    override suspend fun execute(arguments: JsonObject): ToolResult = try {
        val result = client.callTool(descriptor, arguments)
        if (result.isError) ToolResult.error(result.text) else ToolResult.ok(result.text)
    } catch (e: NetworkDeniedException) {
        // 白名单拒绝：**重试没有意义**，要说清是权限问题
        ToolResult.error(e.message ?: "这个地址不在插件的网络白名单里。")
    } catch (e: McpFailure) {
        ToolResult.error(e.message ?: "调用 MCP 工具失败。")
    } catch (e: IOException) {
        // 兜底：`McpFailure` 之外还漏出来的 IO 异常。留着这一层是因为
        // 「工具抛异常」会被引擎捕获成一条没有上下文的错误，
        // 而模型拿到的应该是一句能照着行动的话
        ToolResult.error(
            "调用 MCP 服务 ${client.host} 失败：${e.message ?: e::class.simpleName}。" +
                "这是网络问题，可以稍后重试。",
        )
    }
}
