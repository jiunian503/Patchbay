package com.aichat.domain.llm

/**
 * 一次流式回复过程中可能出现的所有事件。
 *
 * 刻意做成 sealed interface 而不是「一个带一堆可空字段的 data class」：
 * 调用方必须穷尽处理每种情况，漏掉 tool_call 会在编译期就被发现，
 * 而不是变成运行时「工具莫名不执行」。
 */
sealed interface ChatStreamEvent {

    /** 正文增量。直接追加到气泡里即可。 */
    data class TextDelta(val text: String) : ChatStreamEvent

    /**
     * 思维链增量。DeepSeek-R1、QwQ 等推理模型会单独输出这部分。
     *
     * 界面应折叠展示、默认收起 —— 它是模型的草稿纸，不是回答。
     * 注意**不要**把它拼进正文，否则下一轮对话会把思维链当历史发回去。
     */
    data class ReasoningDelta(val text: String) : ChatStreamEvent

    /**
     * 工具调用增量。
     *
     * 一次工具调用的信息是**分多个分片**陆续到达的：首个分片带 [id] 与
     * [name]，后续分片只带 [argumentsDelta]。必须用 [ToolCallAccumulator]
     * 归并后才能得到可执行的调用。
     */
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String?,
    ) : ChatStreamEvent

    /**
     * token 用量。多数服务端只在最后一个分片里给（且需要请求时显式开启
     * `stream_options.include_usage`），所以它可能整场都不出现。
     */
    data class Usage(val promptTokens: Int, val completionTokens: Int) : ChatStreamEvent {
        val totalTokens: Int get() = promptTokens + completionTokens
    }

    /** 正常收尾。之后不会再有事件。 */
    data class Finished(val reason: FinishReason) : ChatStreamEvent
}

/** 停止原因。[from] 对未知值宽容处理 —— 各家的自定义值不该让整轮对话失败。 */
enum class FinishReason {
    /** 模型自然说完。 */
    Stop,

    /** 撞上 max_tokens 被截断，界面应提示用户「回答未结束」。 */
    Length,

    /** 模型要求调用工具。此时必须走工具流程，不能直接展示为最终答案。 */
    ToolCalls,

    /** 被内容安全策略拦截。 */
    ContentFilter,

    /** 服务端返回了本客户端不认识的值，或压根没给。 */
    Unknown,
    ;

    companion object {
        fun from(raw: String?): FinishReason = when (raw) {
            null -> Unknown
            "stop" -> Stop
            "length" -> Length
            // function_call 是旧版 API 的写法，语义等同
            "tool_calls", "function_call" -> ToolCalls
            "content_filter" -> ContentFilter
            else -> Unknown
        }
    }
}
