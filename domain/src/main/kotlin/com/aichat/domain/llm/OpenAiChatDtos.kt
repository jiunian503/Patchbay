package com.aichat.domain.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * OpenAI `/v1/chat/completions` 的流式响应 DTO。
 *
 * 之所以按 OpenAI 的形状建模，是因为它事实上是行业通用方言：DeepSeek、
 * 月之暗面、智谱、通义、硅基流动、Ollama、vLLM、LM Studio 全都兼容这一套。
 * 一个 provider 适配层能覆盖绝大多数自填 API Key 的场景。
 *
 * ## 字段为什么全给默认值
 *
 * 各家实现只在「最小可用」范围内对齐，多余字段、缺失字段都属常态：
 * 有的不回 `id`，有的不回 `usage`，有的在 `delta` 里塞自定义字段。
 * 全部给默认值 + `ignoreUnknownKeys`，才能做到「多一个不崩、少一个不崩」。
 */
@Serializable
data class ChatCompletionChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
    val usage: UsageDto? = null,
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: DeltaDto = DeltaDto(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class DeltaDto(
    val role: String? = null,
    val content: String? = null,

    // 推理模型的思维链字段没有统一命名：
    // DeepSeek 用 reasoning_content，部分网关用 reasoning，Qwen 用 reasoning_content。
    // 两个都收，取到哪个算哪个。
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("reasoning") val reasoning: String? = null,

    @SerialName("tool_calls") val toolCalls: List<ToolCallDeltaDto>? = null,
)

@Serializable
data class ToolCallDeltaDto(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionDeltaDto? = null,
)

@Serializable
data class FunctionDeltaDto(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

/**
 * 解析 LLM 流式响应用的 Json 实例。
 *
 * - `ignoreUnknownKeys`：各家都会加私有字段，不能因此报错。
 * - `isLenient`：容忍部分服务端输出的非严格 JSON（如裸控制字符）。
 * - `explicitNulls = false`：编码时省略 null 字段，构造请求体更干净。
 * - `coerceInputValues`：把 `null` 强转成字段默认值，避免 `"usage": null`
 *   之类的写法把整帧解析搞崩。
 * - `encodeDefaults = true`：**这一条不能省**。kotlinx.serialization 默认
 *   `encodeDefaults = false`，值等于默认值的字段会被直接丢掉 —— 而
 *   `ChatCompletionRequest.stream` 的默认值正好是 `true`，于是请求体里
 *   不会出现 `"stream":true`，服务端返回**非流式**响应，整个 SSE 解析链路
 *   全部失效。`ToolCallDto.type = "function"` 同理会被吞掉。
 */
val LlmJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

/** 一帧 SSE 负载 -> 领域事件。解析失败返回 null，由调用方决定是跳过还是报错。 */
fun ChatCompletionChunk.toStreamEvents(): List<ChatStreamEvent> {
    val out = mutableListOf<ChatStreamEvent>()

    for (choice in choices) {
        val d = choice.delta

        // 思维链排在正文之前：推理模型是「先想后答」，同一帧里两者都出现时
        // 按这个顺序喂给下游，界面上的呈现顺序才自然。
        val reasoning = d.reasoningContent?.takeIf { it.isNotEmpty() }
            ?: d.reasoning?.takeIf { it.isNotEmpty() }
        reasoning?.let { out += ChatStreamEvent.ReasoningDelta(it) }

        d.content?.takeIf { it.isNotEmpty() }?.let { out += ChatStreamEvent.TextDelta(it) }

        d.toolCalls?.forEach { tc ->
            out += ChatStreamEvent.ToolCallDelta(
                index = tc.index,
                id = tc.id,
                name = tc.function?.name,
                argumentsDelta = tc.function?.arguments,
            )
        }

        if (choice.finishReason != null) {
            out += ChatStreamEvent.Finished(FinishReason.from(choice.finishReason))
        }
    }

    // usage 在 choices 之外，独立成帧（且通常 choices 为空）
    usage?.let { out += ChatStreamEvent.Usage(it.promptTokens, it.completionTokens) }

    return out
}
