package com.aichat.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 发给 `/chat/completions` 的消息。
 *
 * 四种角色各有各的必填项，用错会被服务端拒掉，所以下面给了工厂方法 ——
 * 直接 new 容易漏字段：
 *
 * - `system`  —— 只有 content
 * - `user`    —— 只有 content
 * - `assistant` —— content 或 toolCalls 至少有一个（**可以为 null**：
 *   模型决定调工具时通常不带正文）
 * - `tool`    —— content 与 toolCallId 都必须有
 */
@Serializable
data class ChatMessageDto(
    val role: String,
    val content: String? = null,
    val name: String? = null,

    /**
     * assistant 消息里模型发起的工具调用。
     *
     * 回传历史时**必须原样带上**，否则模型看到 tool 结果却找不到对应的调用，
     * 会认为上下文损坏并拒绝继续。
     */
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,

    /** tool 消息专用：这条结果对应哪次调用。空串会被服务端拒绝。 */
    @SerialName("tool_call_id") val toolCallId: String? = null,
) {
    companion object {
        fun system(content: String) = ChatMessageDto(role = "system", content = content)

        fun user(content: String) = ChatMessageDto(role = "user", content = content)

        fun assistant(content: String?, toolCalls: List<ToolCallDto>? = null) =
            ChatMessageDto(role = "assistant", content = content, toolCalls = toolCalls)

        fun tool(toolCallId: String, content: String) =
            ChatMessageDto(role = "tool", content = content, toolCallId = toolCallId)
    }
}

/** assistant 消息里的工具调用。[arguments] 是 **JSON 字符串**，不是对象。 */
@Serializable
data class ToolCallDto(
    val id: String,
    val type: String = "function",
    val function: FunctionCallDto,
)

@Serializable
data class FunctionCallDto(
    val name: String,
    val arguments: String,
)

/** 暴露给模型的工具定义。 */
@Serializable
data class ToolDefinitionDto(
    val type: String = "function",
    val function: FunctionDefinitionDto,
) {
    companion object {
        /**
         * [parameters] 是 JSON Schema 片段，会被原样透传给模型。
         * 描述写得越具体，模型选错工具、填错参数的概率越低。
         */
        fun of(name: String, description: String, parameters: JsonObject) =
            ToolDefinitionDto(
                function = FunctionDefinitionDto(
                    name = name,
                    description = description,
                    parameters = parameters,
                )
            )
    }
}

@Serializable
data class FunctionDefinitionDto(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class StreamOptionsDto(
    @SerialName("include_usage") val includeUsage: Boolean = true,
)

/**
 * 一次对话请求。
 *
 * 所有可选字段都是 nullable + 序列化时省略（`explicitNulls = false`）——
 * 不能给它们填默认值再发出去：部分服务端对「显式传了 temperature=0.7」
 * 和「压根没传」的处理不同，而用户没调过的旋钮就不该出现在请求里。
 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val tools: List<ToolDefinitionDto>? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: StreamOptionsDto? = null,
)
