package com.aichat.chat

import com.aichat.domain.llm.AssistantToolCall
import com.aichat.network.ChatMessageDto
import com.aichat.network.FunctionCallDto
import com.aichat.network.ToolCallDto

/**
 * 对话中的一条消息。
 *
 * 这是**领域模型**，和数据库实体、网络 DTO 都不同 —— 三者刻意分开：
 * 数据库要的是紧凑可查询，网络要的是贴合 OpenAI 方言，领域要的是好推理。
 * 互相转换的代码集中在 [toDto] 一处，出问题只可能出在那里。
 *
 * [reasoning]（思维链）**存下来但不发送** —— 它能让用户回看当时的推理过程，
 * 但绝不能进上下文：模型看到自己上一轮的草稿会继续往下写，而不是重新回答。
 */
data class ChatMessage(
    val role: Role,
    val content: String = "",
    val reasoning: String? = null,
    val toolCalls: List<AssistantToolCall> = emptyList(),
    val toolCallId: String? = null,
) {
    enum class Role { System, User, Assistant, Tool }

    companion object {
        fun system(content: String) = ChatMessage(Role.System, content)

        fun user(content: String) = ChatMessage(Role.User, content)

        fun assistant(content: String, toolCalls: List<AssistantToolCall> = emptyList()) =
            ChatMessage(Role.Assistant, content, toolCalls = toolCalls)

        /** 工具结果。必须带 [toolCallId]，否则服务端找不到它对应哪次调用。 */
        fun tool(toolCallId: String, content: String) =
            ChatMessage(Role.Tool, content, toolCallId = toolCallId)
    }
}

internal fun ChatMessage.toDto(): ChatMessageDto = when (role) {
    ChatMessage.Role.System -> ChatMessageDto.system(content)

    ChatMessage.Role.User -> ChatMessageDto.user(content)

    ChatMessage.Role.Assistant -> ChatMessageDto.assistant(
        // 只调工具、不说话时 content 必须给 null 而不是空串 ——
        // 部分服务端对空串会报「content 不能为空」
        content = content.ifBlank { null },
        toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.map { it.toDto() },
    )

    ChatMessage.Role.Tool -> ChatMessageDto.tool(toolCallId.orEmpty(), content)
}

internal fun AssistantToolCall.toDto() = ToolCallDto(
    id = id,
    function = FunctionCallDto(name = name, arguments = argumentsJson),
)
