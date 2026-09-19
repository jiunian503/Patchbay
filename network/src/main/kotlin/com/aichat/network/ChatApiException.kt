package com.aichat.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 调用 LLM 接口时的失败。分成三类，因为界面上要给用户看不同的提示。 */
sealed class ChatApiException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /**
     * 服务端返回了非 2xx。
     *
     * 常见原因：key 错（401）、余额不足（402）、模型名写错（404）、
     * 参数不被支持（400，比如给不认 `stream_options` 的网关开了用量统计）。
     */
    class Http(
        val statusCode: Int,
        val errorBody: String,
        message: String,
        cause: Throwable? = null,
    ) : ChatApiException(message, cause)

    /** 连不上 / 超时 / TLS 失败。通常是网络问题或 baseUrl 写错。 */
    class Network(cause: Throwable) :
        ChatApiException(cause.message ?: "网络请求失败", cause)

    /** 响应不是预期的 SSE 格式，或 JSON 结构对不上。 */
    class Protocol(message: String, cause: Throwable? = null) :
        ChatApiException(message, cause)
}

/**
 * 从错误响应体里挖出人能看懂的一句话。
 *
 * 各家格式完全不统一，实测至少这几种：
 *
 * - `{"error":{"message":"...","type":"..."}}`   OpenAI / DeepSeek
 * - `{"error":"..."}`                            部分网关
 * - `{"message":"..."}` / `{"msg":"..."}`        国内厂商常见
 * - 一整个 HTML 错误页                            反代 / 网关
 *
 * 挖不出来就退回「HTTP 码 + 原文前 500 字」—— 直接丢掉 body 会让用户
 * 完全无法自查，而原样全丢又可能把一大坨 HTML 塞进界面。
 */
internal fun extractApiErrorMessage(statusCode: Int, body: String): String {
    val trimmed = body.trim()

    val detail = runCatching {
        val root = Json.parseToJsonElement(trimmed) as? JsonObject ?: return@runCatching null
        when (val err = root["error"]) {
            is JsonObject -> (err["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> err.contentOrNull
            else -> (root["message"] as? JsonPrimitive)?.contentOrNull
                ?: (root["msg"] as? JsonPrimitive)?.contentOrNull
        }
    }.getOrNull()

    return if (detail.isNullOrBlank()) {
        val snippet = trimmed.take(500)
        "HTTP $statusCode${if (snippet.isBlank()) "（响应体为空）" else "：$snippet"}"
    } else {
        "HTTP $statusCode：$detail"
    }
}
