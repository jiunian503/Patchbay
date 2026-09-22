package com.aichat.plugin.runtime.mcp

import com.aichat.domain.text.errorDetail
import com.aichat.network.CappedSource
import com.aichat.network.ResponseTooLargeException
import com.aichat.network.peekTextCapped
import com.aichat.plugin.permission.NetworkDeniedException
import com.aichat.plugin.permission.NetworkGuard
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.buffer

/**
 * Streamable HTTP 传输：把一次 JSON-RPC 请求发出去，把响应读回来。
 *
 * ## 单一端点，纯 POST
 *
 * 现代版只有一种形状：**每个消息都是往同一个 MCP endpoint 发一个 POST**，
 * 回复要么是一个 JSON 对象，要么是一个**请求作用域**的 SSE 流。
 * 没有 GET 流端点、没有 `DELETE` 收尾、没有 `Last-Event-ID` 续传 ——
 * 这几样在早期文档里都有，照着旧文档写出来的客户端打现代服务端会被
 * `405`（现代服务端对 GET/DELETE 就回这个码）。
 *
 * ## 头是从 body **推导**出来的，不是调用方传进来的
 *
 * `Mcp-Method` 镜像 body 的 `method`，`Mcp-Name` 镜像 body 的 `params.name`。
 * 这两件事都由这里从已经拼好的 body 上取，于是**头不可能和 body 不一致**。
 * 让调用方额外传一份的话，迟早会出现「改了 body 忘了改头」——
 * 而服务端对不一致的响应是 `400 HeaderMismatch`（`-32020`），
 * 报错信息里只有「头不匹配」，看不出是哪个头。
 */
internal class McpHttpTransport(
    private val endpoint: HttpUrl,
    private val guard: NetworkGuard,
    private val client: OkHttpClient,
    private val clientInfo: McpClientInfo,
    /**
     * 每次都附带的静态头（例如 `Authorization`）。
     *
     * 由调用方给**渲染好的**值，不是模板 —— 模板渲染留在装配期，
     * 那样「配置项拼错」在安装时就报得出来，而不是变成某次调用里的 401。
     */
    private val extraHeaders: Map<String, String> = emptyMap(),
) {

    /**
     * 旧协议对端在 `initialize` 的响应里给的会话 id。
     *
     * **只在旧协议下记录。** 现代版规范要求收到 `Mcp-Session-Id` 就忽略
     * （不铸造、不回显）—— 记下来再回显的话，等于对着一个不承认会话的
     * 服务端假装有会话，而它可能把重复的会话头当成协议错误。
     */
    var sessionId: String? = null
        private set

    /**
     * 发一次请求。
     *
     * @param id 为 null 时发的是 **notification**（body 里没有 `id`），
     *   对端正常会回 `202`，那时返回 null。
     * @param modern 是否按现代版发（带 `_meta` 和三个镜像头）。
     * @param version 本次声明的协议版本。回退/重谈之后会变，所以是参数不是常量。
     * @param paramHeaders 要镜像成请求头的工具参数（`x-mcp-header`），值为**原始**参数值。
     * @return 对端的响应；notification 被接受时是 null。
     * @throws NetworkDeniedException 白名单拦下了（**不要重试**，换个地址或让用户改权限）
     * @throws McpFailure 其余全部失败
     */
    fun post(
        method: String,
        params: JsonObject?,
        id: Long?,
        modern: Boolean,
        version: String,
        paramHeaders: Map<String, String> = emptyMap(),
    ): JsonRpcResponse? {
        val body = buildJsonObject {
            put("jsonrpc", JsonPrimitive(JSONRPC_VERSION))
            if (id != null) put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", paramsFor(params, modern, version))
        }

        val builder = Request.Builder()
            .url(endpoint)
            .post(McpWire.json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", McpWire.ACCEPT)

        for ((name, value) in extraHeaders) builder.header(name, value)

        if (modern) {
            // 三个镜像头都从 body 上取 —— 见类注释
            builder.header(McpWire.HEADER_PROTOCOL_VERSION, version)
            builder.header(McpWire.HEADER_METHOD, McpWire.encodeHeaderValue(method))
            headerNameOf(params)?.let { builder.header(McpWire.HEADER_NAME, McpWire.encodeHeaderValue(it)) }
            for ((name, value) in paramHeaders) {
                builder.header(McpWire.HEADER_PARAM_PREFIX + name, McpWire.encodeHeaderValue(value))
            }
        } else {
            sessionId?.let { builder.header(McpWire.HEADER_SESSION, it) }
        }

        val response = try {
            guard.call(client, builder.build())
        } catch (e: NetworkDeniedException) {
            throw e
        } catch (e: IOException) {
            // 网络故障和「被白名单拒绝」分开报：前者可以重试，后者重试没有意义
            throw McpFailure(
                "连接 MCP 服务 ${endpoint.host} 失败：${errorDetail(e)}。" +
                    "这是网络问题，可以稍后重试。",
                retryable = true,
                cause = e,
            )
        }

        return response.use { read(it, id, modern) }
    }

    // ------------------------------------------------------------------ 请求

    /**
     * 拼 `params`。
     *
     * 现代版要求每个请求都带上元数据，缺了属于「格式错误」——
     * 服务端必须回 `400` + `-32602`。所以这不是「加个字段更规范」，
     * 而是**不带就发不出去**。
     */
    private fun paramsFor(params: JsonObject?, modern: Boolean, version: String): JsonObject {
        val base = params ?: JsonObject(emptyMap())
        if (!modern) return base

        return buildJsonObject {
            for ((key, value) in base) put(key, value)
            put(
                McpWire.META,
                buildJsonObject {
                    put(McpWire.META_PROTOCOL_VERSION, JsonPrimitive(version))
                    // 必须项：不声明能力的话，一个需要某项能力的服务端
                    // 只能回 -32021，而那是我们这边的错
                    put(McpWire.META_CLIENT_CAPABILITIES, buildJsonObject {})
                    put(
                        McpWire.META_CLIENT_INFO,
                        buildJsonObject {
                            put("name", JsonPrimitive(clientInfo.name))
                            put("version", JsonPrimitive(clientInfo.version))
                        },
                    )
                },
            )
        }
    }

    /** `Mcp-Name` 的来源：`params.name`，或者资源类的 `params.uri`。 */
    private fun headerNameOf(params: JsonObject?): String? =
        params?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: params?.get("uri")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ 响应

    private fun read(response: Response, id: Long?, modern: Boolean): JsonRpcResponse? {
        if (!modern) {
            response.header(McpWire.HEADER_SESSION)?.takeIf { it.isNotBlank() }?.let { sessionId = it }
        }

        val status = response.code

        if (status == 202) {
            // 规范里 202 只用于「notification 被接受」。请求（带 id）收到 202
            // 说明对端没有给出结果 —— 那是它的问题，而且重试也不会变
            if (id == null) return null
            throw McpFailure(
                "MCP 服务接受了请求但没有返回结果（HTTP 202）。这不符合协议，重试也不会成功，" +
                    "请告诉用户这个 MCP 服务可能有问题。",
                retryable = false,
            )
        }

        if (status !in 200..299) {
            val text = response.peekBody(MAX_ERROR_BYTES).string()
            val rpcError = parseRpcError(text)
            throw McpHttpFailure(
                status = status,
                body = text,
                rpcError = rpcError,
                message = describeHttpStatus(
                    status = status,
                    host = endpoint.host,
                    detail = rpcError?.let { "${it.message}（错误码 ${it.code}）" }
                        ?: text.trim().take(300).ifEmpty { "（响应体为空）" },
                ),
            )
        }

        if (id == null) return null

        return when {
            contentTypeIs(response, McpWire.CONTENT_TYPE_SSE) -> readSse(response, id)
            contentTypeIs(response, McpWire.CONTENT_TYPE_JSON) -> readJson(response)
            // Content-Type 缺失，或者被中间的代理改成了别的（`text/plain` 很常见）。
            // 这里按**内容**嗅探，而不是直接当 JSON —— 猜错的话症状是
            // 「SSE 流被当成 JSON 解析」，报的是一个和真实原因无关的解析错误
            else -> if (looksLikeSse(response)) readSse(response, id) else readJson(response)
        }
    }

    private fun contentTypeIs(response: Response, type: String): Boolean =
        response.header("Content-Type")?.contains(type, ignoreCase = true) == true

    private fun looksLikeSse(response: Response): Boolean {
        // peekBody 不消费，所以看完之后还能照常读整个 body
        val head = response.peekBody(SNIFF_BYTES).string().trimStart()
        return head.startsWith("data:") || head.startsWith("event:") || head.startsWith(":")
    }

    private fun readJson(response: Response): JsonRpcResponse {
        // 超上限要报「太大了」，不能让它截断之后再报成「对端返回的不是 JSON」——
        // 那句话把宿主自己的限制说成了对端的问题，用户会去投诉一个没毛病的服务端。
        // 同一份文件里的 `readSse` 早就报对了，这里漏了（§112）
        val text = response.peekTextCapped(MAX_BODY_BYTES)
            ?: throw McpFailure(
                "MCP 服务的响应超过 ${MAX_BODY_BYTES / 1024} KB 上限，宿主没有把它整个读进来。" +
                    "请让用户换一个返回内容更少的工具。",
                retryable = false,
            )
        val obj = runCatching { McpWire.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw McpFailure(
                "MCP 服务返回的不是一个 JSON 对象：${text.trim().take(300)}",
                retryable = false,
            )
        return decode(obj)
    }

    /**
     * 读一个请求作用域的 SSE 流。
     *
     * ## 为什么不能「读到第一条 data 就返回」
     *
     * 规范说这个流里**先传与该请求相关的通知，再传最终响应**。通知没有
     * `id`（或者 id 是别的），第一条 data 很可能是通知。取第一条的话，
     * 症状是「偶尔解析出一个没有 result 的对象」—— 而这是**看运气**的，
     * 取决于对端有没有先推通知，本地很难复现。
     *
     * ## 为什么找到就立刻返回
     *
     * 规范说最终响应**应该**终止流，但那是 SHOULD。不返回的话，一个
     * 忘了关流的服务端会把我们挂在这里直到超时。
     */
    private fun readSse(response: Response, id: Long): JsonRpcResponse {
        // OkHttp 5 里 body 非空（没有 body 的响应拿到的是空 body），
        // 所以「对端什么也没回」会走到底下那个「流结束了但没有结果」的分支 ——
        // 那个说法更准确：它确实是一个事件流，只是里面没有我们要的那条
        val source = CappedSource(response.body.source(), MAX_BODY_BYTES).buffer()
        val parser = SseParser()

        try {
            while (true) {
                val line = source.readUtf8Line() ?: break
                parser.line(line)?.let { event ->
                    responseOf(event, id)?.let { return it }
                }
            }
            // 流结束时最后一条事件可能没有以空行收尾
            parser.finish()?.let { event ->
                responseOf(event, id)?.let { return it }
            }
        } catch (e: ResponseTooLargeException) {
            throw McpFailure(
                "MCP 服务的事件流超过 ${MAX_BODY_BYTES / 1024} KB 上限，已停止读取。" +
                    "请让用户换一个返回内容更少的工具。",
                retryable = false,
            )
        } catch (e: IOException) {
            throw McpFailure(
                "读取 MCP 服务的事件流失败：${errorDetail(e)}。可以稍后重试。",
                retryable = true,
                cause = e,
            )
        }

        throw McpFailure(
            "MCP 服务的事件流结束了，但没有返回这次请求的结果（id=$id）。" +
                "重试也不会成功，请告诉用户这个 MCP 服务可能有问题。",
            retryable = false,
        )
    }

    /**
     * 一条事件是不是「我们要的那条响应」。
     *
     * 认不出来的一律返回 null（不是错误）：事件流里可能有通知、有对端
     * 自己的扩展事件，把它们当成错误会让一个合规的服务端不可用。
     */
    private fun responseOf(event: SseEvent, id: Long): JsonRpcResponse? {
        val obj = runCatching { McpWire.json.parseToJsonElement(event.data) as? JsonObject }
            .getOrNull() ?: return null
        val response = runCatching { decode(obj) }.getOrNull() ?: return null
        return response.takeIf { it.idMatches(id) }
    }

    private fun decode(obj: JsonObject): JsonRpcResponse =
        McpWire.json.decodeFromJsonElement(JsonRpcResponse.serializer(), obj)

    /**
     * 从一段文本里认出 JSON-RPC 错误。
     *
     * **这个函数是回退判定的全部依据。** 规范说：`400` 时先看 body ——
     * 里面有能认出来的现代 JSON-RPC 错误就说明对端是**现代**服务端
     * （只是版本没谈拢），**不要**回退；body 是空的或认不出来才是旧版。
     * 所以它必须**宽松**：只要求是一个带 `error` 对象的 JSON 对象，
     * 不要求 `jsonrpc` 字段、不要求 `id`（规范说 403 的错误响应可以没有 `id`）。
     * 严格一点的话，一个完全正常的现代服务端会被误判成旧版，
     * 然后我们对着它发一个它不认识的 `initialize`。
     */
    private fun parseRpcError(text: String): JsonRpcError? {
        val obj = runCatching { McpWire.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return null
        return runCatching { McpWire.json.decodeFromJsonElement(JsonRpcError.serializer(), obj["error"]!!) }
            .getOrNull()
    }

    private companion object {
        const val JSONRPC_VERSION = "2.0"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 上限和 `DeclarativeTool` / `FetchUrlTool` 保持一致：不加限制的调用是自伤。 */
        const val MAX_BODY_BYTES = 256L * 1024

        /** 错误响应体只需要够看清原因，不需要整个读进来。 */
        const val MAX_ERROR_BYTES = 4L * 1024

        const val SNIFF_BYTES = 64L
    }
}

// 「读多少字节就停」那两层（`CappedSource` / `ResponseTooLargeException`）
// 已提到 :network —— 那里有第二个消费者（`OpenAiChatClient` 的流式回答），
// 上限值不同，但「怎么卡」只该有一份实现。
