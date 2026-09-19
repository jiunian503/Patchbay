package com.aichat.core.data

import com.aichat.chat.ChatMessage
import com.aichat.chat.MessageStatus
import com.aichat.chat.StoredMessage
import com.aichat.domain.llm.AssistantToolCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * `tool_calls` 列的 JSON 形态。
 *
 * 刻意**不复用** `:network` 的 `ToolCallDto`：那个是网络协议类型，
 * 跟着 OpenAI 方言走（有 `type` 字段、`function` 嵌套）。拿它当存储格式，
 * 意味着以后协议一改就得写数据迁移。存储格式应该只跟领域模型走。
 */
@Serializable
internal data class ToolCallJson(
    val id: String,
    val name: String,
    val arguments: String,
)

internal val messageJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val toolCallListSerializer = ListSerializer(ToolCallJson.serializer())

internal fun encodeToolCalls(calls: List<AssistantToolCall>): String =
    messageJson.encodeToString(
        toolCallListSerializer,
        calls.map { ToolCallJson(it.id, it.name, it.argumentsJson) },
    )

/**
 * 解析 `tool_calls` 列。
 *
 * 坏数据一律当空列表：这条消息本来就只是「模型用过哪些工具」的元信息，
 * 解析失败不该让整条消息读不出来 —— 正文还在，用户照样能看到回复。
 * 真正的执行只发生在当轮，落库的这份是给界面渲染工具卡片用的。
 */
internal fun decodeToolCalls(raw: String?): List<AssistantToolCall> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        messageJson.decodeFromString(toolCallListSerializer, raw)
            .map { AssistantToolCall(it.id, it.name, it.arguments) }
    }.getOrDefault(emptyList())
}

/**
 * 角色的存储形态。
 *
 * 用字符串而不是 `ordinal`：以后加角色（比如 OpenAI 的 `developer`）时，
 * 序号会全部错位，而字符串不会。也不直接用 `enum.name` —— 改个枚举名
 * 就废掉全部历史数据，这种坑不值得踩。
 */
internal fun ChatMessage.Role.toWire(): String = when (this) {
    ChatMessage.Role.System -> "system"
    ChatMessage.Role.User -> "user"
    ChatMessage.Role.Assistant -> "assistant"
    ChatMessage.Role.Tool -> "tool"
}

/**
 * 反序列化角色。认不出的值退回 [ChatMessage.Role.User]。
 *
 * 退成 User 而不是别的，是因为未知角色最可能是「用户侧的新指令类型」
 * （`developer` 之类）；当成 assistant 会让模型以为那是自己说过的话，
 * 当成 tool 则会导致找不到对应的 `tool_call_id` 而请求被拒。
 */
internal fun roleFromWire(raw: String): ChatMessage.Role = when (raw) {
    "system" -> ChatMessage.Role.System
    "user" -> ChatMessage.Role.User
    "assistant" -> ChatMessage.Role.Assistant
    "tool" -> ChatMessage.Role.Tool
    else -> ChatMessage.Role.User
}

internal fun StoredMessage.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = message.role.toWire(),
    content = message.content,
    reasoning = message.reasoning,
    toolCallsJson = message.toolCalls.takeIf { it.isNotEmpty() }?.let(::encodeToolCalls),
    toolCallId = message.toolCallId,
    status = status.wire,
    model = model,
    error = error,
    // upsert 走的是 REPLACE（先删后插），所以这里必须显式写回 0，
    // 否则「保存一条已软删的消息」会把它复活
    deleted = false,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun MessageEntity.toChatMessage(): ChatMessage = ChatMessage(
    role = roleFromWire(role),
    content = content,
    reasoning = reasoning,
    toolCalls = decodeToolCalls(toolCallsJson),
    toolCallId = toolCallId,
)

internal fun MessageEntity.toStoredMessage(): StoredMessage = StoredMessage(
    id = id,
    conversationId = conversationId,
    message = toChatMessage(),
    status = MessageStatus.fromWire(status),
    model = model,
    error = error,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
