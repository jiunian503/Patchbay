package com.aichat.plugin.runtime.mcp

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP Streamable HTTP 的线上常量。
 *
 * ## 为什么这些字面量要集中在一处
 *
 * 它们每一个都是**对端和宿主之间的契约**，写错一个字母的症状是
 * 「服务端返回一个看不懂的 400」，而那是排查成本最高的一类错误 ——
 * 从症状看不出是哪一行代码写错了。集中在一处之后，改动是原子的，
 * 而且能被测试逐条钉住。
 *
 * ## 两条容易记反的事实（都和早期资料相反）
 *
 * 1. **现代版（[PROTOCOL_VERSION]）没有 `initialize` 握手。** 协议版本、
 *    客户端信息、能力声明全部**跟着每个请求走**（[META] 里的那几个键）。
 *    握手是**旧版**（[LEGACY_VERSION] 及更早）的东西。所以客户端的形状是
 *    「先按现代版发，从错误里认出对端是哪一代」，而不是「先握手」。
 * 2. **现代版没有会话。** `Mcp-Session-Id` 属于旧版；现代版收到它要
 *    **忽略**（不铸造、不回显）。照着旧文档写出「先 initialize 拿 session」
 *    的代码，打现代服务端会静默退化。
 */
internal object McpWire {

    /** 本客户端实现的现代版协议版本。日期字符串，可以直接按字典序比大小。 */
    const val PROTOCOL_VERSION = "2026-07-28"

    /** 旧版（握手式）协议的版本。回退时用它发起 `initialize`。 */
    const val LEGACY_VERSION = "2025-11-25"

    /**
     * 现代版与旧版的分界。
     *
     * 版本号是 `YYYY-MM-DD` 形式，所以「是不是现代版」可以**直接比字符串** ——
     * 不需要解析日期、也不需要维护一张版本表。用这个性质之前先确认版本号
     * 真的还是这个格式，否则比较会安静地给出错误答案。
     */
    const val MODERN_EPOCH = PROTOCOL_VERSION

    // ---------------------------------------------------------------- 请求头

    /** 镜像 body 里 `params._meta` 的协议版本。**每个 POST 都要带。** */
    const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"

    /** 镜像 body 里的 `method`。 */
    const val HEADER_METHOD = "Mcp-Method"

    /** 镜像 body 里的 `params.name`（或 `params.uri`）。`tools/call` 必须带。 */
    const val HEADER_NAME = "Mcp-Name"

    /** 旧版的会话 id。现代版没有会话 —— 收到要忽略。 */
    const val HEADER_SESSION = "Mcp-Session-Id"

    /** 工具参数镜像成头时的前缀，后接 `inputSchema` 里 `x-mcp-header` 的值。 */
    const val HEADER_PARAM_PREFIX = "Mcp-Param-"

    /**
     * `Accept` 必须**同时**列出两种类型。
     *
     * 只写 `application/json` 的话，一个想用事件流回答的服务端就没法合规地
     * 这么做 —— 而事件流是它推「先几条通知、再最终响应」的唯一手段。
     */
    const val ACCEPT = "application/json, text/event-stream"

    const val CONTENT_TYPE_JSON = "application/json"
    const val CONTENT_TYPE_SSE = "text/event-stream"

    // ---------------------------------------------------------------- body 元数据

    /** 元数据挂在 `params._meta` 下（**不是**与 `method` 平级的顶层字段）。 */
    const val META = "_meta"

    /** 保留前缀。第二段是 `modelcontextprotocol` 的前缀归 MCP 所有。 */
    private const val META_PREFIX = "io.modelcontextprotocol/"

    const val META_PROTOCOL_VERSION = META_PREFIX + "protocolVersion"
    const val META_CLIENT_INFO = META_PREFIX + "clientInfo"
    const val META_CLIENT_CAPABILITIES = META_PREFIX + "clientCapabilities"

    // ---------------------------------------------------------------- 错误码

    /** 头与 body 对不上，或者必需的头缺失／格式不对。HTTP 400。 */
    const val CODE_HEADER_MISMATCH = -32020

    /** 服务端需要一项客户端没声明的能力，`data.requiredCapabilities` 里列出缺的那些。 */
    const val CODE_MISSING_CAPABILITY = -32021

    /**
     * 服务端不实现请求的这个协议版本，`data.supported` 里列出它支持的版本。
     *
     * 这个码是**识别「对端是现代版」的钥匙**：它属于现代版才有的错误。
     * 所以拿到它时**不能**回退到旧版握手 —— 对方比我们新，只是版本没谈拢。
     */
    const val CODE_UNSUPPORTED_VERSION = -32022

    /** 方法未实现。HTTP 404。 */
    const val CODE_METHOD_NOT_FOUND = -32601

    /** 参数不合法（现代版里，缺 `_meta` 的必需字段也算这个）。 */
    const val CODE_INVALID_PARAMS = -32602

    /** 回退判定要看的那几个状态码：都是「这个请求在旧服务端上不成立」的信号。 */
    val FALLBACK_STATUSES = setOf(400, 404, 405)

    // ---------------------------------------------------------------- 哨兵编码

    private const val SENTINEL_PREFIX = "=?base64?"
    private const val SENTINEL_SUFFIX = "?="

    /**
     * 头值编码。
     *
     * ## 为什么这不是可选优化，而是**必须**做的
     *
     * OkHttp 会拒绝任何含非 ASCII 的头值（`Unexpected char`），直接抛异常。
     * 而 MCP 的头值是从 body 里镜像出来的 —— body 里的工具名可以是中文。
     * 所以「工具名带中文」这件事，不编码就是**发不出去**，而不是「发出去了对端看不懂」。
     *
     * ## 规则
     *
     * 明文只在「全是可见 ASCII（0x21–0x7E）**且**长得不像哨兵」时才用。
     * 两点都必要：
     * - 含空格／换行要编码。规范明确列了 `" padded "` 和 `"line1\nline2"` 两个例子 ——
     *   首尾空格在头里会被剥掉，换行则根本非法。
     * - **长得像哨兵的值也要编码**，否则一个内容恰好是 `=?base64?literal?=` 的工具名
     *   会被对端先解码一次，两边比出来的值就不一样了。规范要求客户端对这类值
     *   也做编码，正是为了消掉这个歧义。
     *
     * 前缀后缀是**大小写敏感**的，必须严格小写 —— 写成 `=?Base64?` 对端不会认。
     */
    fun encodeHeaderValue(raw: String): String {
        val plain = raw.isNotEmpty() &&
            raw.all { it.code in 0x21..0x7E } &&
            !looksLikeSentinel(raw)
        if (plain) return raw
        val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        return SENTINEL_PREFIX + encoded + SENTINEL_SUFFIX
    }

    private fun looksLikeSentinel(value: String): Boolean =
        value.startsWith(SENTINEL_PREFIX) && value.endsWith(SENTINEL_SUFFIX)

    // ---------------------------------------------------------------- 解析

    /**
     * 解析用 JSON。
     *
     * `ignoreUnknownKeys` 是必须的：MCP 的响应里会带上这个宿主不认识的字段
     * （扩展、服务端自留字段），严格解析会让一个完全正常的服务端不可用。
     * 注意这和**清单解析**的取向相反 —— 那边严格是因为清单是用户要授权的契约，
     * 一个拼错的 `requiresConfirmation` 会变成「本该弹窗的写操作直接执行」。
     * 这里只是读对端的回答，宽松不会带来权限后果。
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}

/**
 * 客户端自报家门。会出现在对端服务端的日志里。
 *
 * 默认值用能认出来的名字，而不是 `unknown` —— 用户去问服务端作者
 * 「为什么我的请求失败了」时，这个名字是唯一的线索。
 */
data class McpClientInfo(val name: String, val version: String) {
    companion object {
        // 值要和 `app_name`（app/src/main/res/values/strings.xml）保持一致 ——
        // 用户拿着这个名字去问服务端作者，两边说的得是同一个东西才对得上
        val Default = McpClientInfo(name = "patchbay", version = "0.1.0")
    }
}

/**
 * 对端服务端属于哪一代协议。判定一次之后缓存。
 *
 * 这是**对端的属性**，不是单个请求的属性（规范明确这么说），所以它值得
 * 被显示出来：用户遇到「这个 MCP 插件怪怪的」时，「对端是旧版协议」
 * 是一句能让他去问服务端作者的话，而「调用失败」不是。
 *
 * ## 为什么要 `@Serializable` 且每个值都显式写 `@SerialName`
 *
 * 因为它会**跟着工具清单一起落进数据库**（见 [McpToolCache]）。落盘的标识
 * 不能和代码里的枚举常量名绑在一起 —— 有人把 `Legacy` 改名成 `Handshake`
 * 就会让所有已缓存的清单解码失败，而症状是「插件突然说没连接过」。
 * 这和 `PluginRuntimeKind` / `MessageStatus.wire` 是同一条规矩。
 */
@Serializable
enum class McpEra {
    /** 还没发过请求。 */
    @SerialName("unknown")
    Unknown,

    /** 每个请求自带元数据，没有握手、没有会话。 */
    @SerialName("modern")
    Modern,

    /** 握手式：`initialize` → `notifications/initialized` → 业务请求，且带会话 id。 */
    @SerialName("legacy")
    Legacy,
}

/** 给用户看的说法。不复用 `enum.name`：那是英文，而这句话要能直接显示在插件详情页上。 */
val McpEra.displayName: String
    get() = when (this) {
        McpEra.Unknown -> "尚未连接"
        McpEra.Modern -> "现代版（每个请求自带协议版本，无会话）"
        McpEra.Legacy -> "旧版（initialize 握手 + 会话）"
    }

// -------------------------------------------------------------------- JSON-RPC

/**
 * 一个 JSON-RPC 请求。
 *
 * [id] 可空：为 null 时编码结果里**没有** `id` 字段，那就是一个 notification
 * （旧协议的 `notifications/initialized` 就是这么发的）。
 * 靠 `explicitNulls = false` 实现，而不是手写两个模型 —— 两个模型的话
 * 「notification 少写了 method」这类错要靠人记得。
 */
@Serializable
internal data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val method: String,
    val params: JsonObject? = null,
)

@Serializable
internal data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

/**
 * 一个 JSON-RPC 响应。
 *
 * [id] 用 `JsonElement` 而不是 `Long`：规范说整数 id **按数值比较**
 * （`42.0` 和 `42` 相等），而字符串 id 也是允许的。收成 `Long` 会在
 * 对端回一个字符串 id 时解析失败 —— 而那是一个完全合规的服务端。
 * 比较在 [idMatches] 里做。
 */
@Serializable
internal data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

/** id 是否等于 [expected]。按**文本**比，于是数字 `1` 和字符串 `"1"` 都认。 */
internal fun JsonRpcResponse.idMatches(expected: Long): Boolean =
    id?.jsonPrimitive?.contentOrNull == expected.toString()

// -------------------------------------------------------------------- 对外的模型

/**
 * 对端服务端提供的一个工具。
 *
 * 这是**对端给的**，不是作者在清单里写的 —— 所以它比清单里的 [com.aichat.plugin.manifest.ToolSpec]
 * 少一层信任：[description] 和 [inputSchema] 都可能被对端在任何时候改掉，
 * 而用户装插件时看到的那份说明里并没有它们。
 *
 * 界面要展示「这个插件会提供什么」时，拿到的就是这份东西的**快照**。
 */
data class McpToolDescriptor(
    val name: String,
    val description: String,

    /** JSON Schema 片段，原样透传给模型的 function calling。 */
    val inputSchema: JsonObject,

    /**
     * 对端声明这个工具只读（MCP 的 `annotations.readOnlyHint`）。
     *
     * ## 为什么它比清单里的 `requiresConfirmation` 弱
     *
     * 清单是用户**装的时候看过并授权**的契约，而这个提示是**每次连接时
     * 从线上下来的** —— 对端随时可以改，用户看不到改动。所以它只能用来
     * **免掉**确认，不能用来**加上**确认之外的权限：
     * 声明了任意主机（`network: ["*"]`）时一律确认，这条不受它影响。
     */
    val readOnly: Boolean = false,
) {
    /**
     * 需要镜像到请求头的参数：参数名 → 头名后缀。
     *
     * 来自 `inputSchema.properties[*]["x-mcp-header"]`。规范要求客户端**必须**
     * 支持它，否则对端那些靠头做路由／鉴权的工具会拿到缺参数的错误，
     * 而报错指向的是「参数没传」—— 参数明明在 body 里，作者会查很久。
     *
     * 在装配期算一次，不在每次调用时扫 schema：调用路径上不该有解析工作。
     */
    val headerParams: Map<String, String> = readHeaderParams(inputSchema)
}

private fun readHeaderParams(schema: JsonObject): Map<String, String> {
    val properties = schema["properties"] as? JsonObject ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    for ((paramName, spec) in properties) {
        val headerName = (spec as? JsonObject)
            ?.get("x-mcp-header")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: continue
        out[paramName] = headerName
    }
    return out
}

/**
 * 一次 `tools/call` 的结果。
 *
 * [isError] 是**对端**说这次调用失败了（MCP 的 `isError`），和「宿主没能
 * 完成这次调用」（抛 [McpFailure]）是两件事：前者要把对端的原话带给模型，
 * 后者是宿主这一侧的问题。
 */
data class McpCallResult(val text: String, val isError: Boolean)

// -------------------------------------------------------------------- 失败

/**
 * 调用 MCP 服务失败。
 *
 * [retryable] 不是一个装饰性的标记 —— 它决定写给模型的那句话。
 * 网络故障要说「可以稍后重试」，协议／配置问题要说「重试也不会成功，
 * 请告诉用户检查配置」。**混成一句「调用失败」的话，模型会对着一个
 * 永远不会成功的请求反复重试**，白烧好几轮上下文。
 */
open class McpFailure(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 对端回了一个非 2xx。
 *
 * [rpcError] 为 null 表示**这个响应体里没有能认出来的 JSON-RPC 错误** ——
 * 那正是规范给的「对端是旧版」的信号，回退逻辑靠的就是这个字段。
 * 把「认不认得出来」当成一个显式的字段，而不是在回退代码里再解析一遍 body：
 * 两处解析的话，迟早会出现「回退判定说认得、报错文案说没认出来」。
 */
internal class McpHttpFailure(
    val status: Int,
    val body: String,
    val rpcError: JsonRpcError?,
    message: String,
    retryable: Boolean = false,
) : McpFailure(message, retryable)

/** 状态码 → 给模型看的一句话。 */
internal fun describeHttpStatus(status: Int, host: String, detail: String): String = when (status) {
    400 -> "MCP 服务 $host 拒绝了这次请求（HTTP 400）。服务端说：$detail"
    403 -> "MCP 服务 $host 拒绝了这次请求（HTTP 403）。这通常是服务端在防 DNS rebinding，" +
        "需要服务端把本 App 的来源加进允许列表。"
    404 -> "MCP 服务 $host 说没有这个方法（HTTP 404）。"
    405 -> "MCP 服务 $host 不接受这个 HTTP 方法（HTTP 405）。"
    else -> "MCP 服务 $host 返回 HTTP $status。服务端说：$detail"
}
