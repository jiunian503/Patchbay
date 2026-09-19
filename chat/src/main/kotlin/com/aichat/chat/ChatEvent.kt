package com.aichat.chat

import com.aichat.domain.llm.AssistantToolCall
import com.aichat.domain.tool.ToolResult

/**
 * 编排层向 UI 推送的事件。
 *
 * 比 [com.aichat.domain.llm.ChatStreamEvent] 高一层：那个是「网络收到了什么」，
 * 这个是「对话进行到哪一步了」—— 已经消化了工具循环，UI 不需要知道
 * 后面还有几轮、上下文怎么拼。
 *
 * 事件按发生顺序推送，UI 直接顺序渲染即可：先来的文字在工具卡片上方，
 * 工具执行完的文字在下方，天然正确。
 */
sealed interface ChatEvent {

    /**
     * 开始第 [round] 轮请求（从 0 起）。
     *
     * 出现 `round > 0` 就说明发生了工具调用 —— UI 可以据此显示「正在处理…」。
     */
    data class RoundStarted(val round: Int) : ChatEvent

    data class TextDelta(val text: String) : ChatEvent

    data class ReasoningDelta(val text: String) : ChatEvent

    /** 模型决定调用某个工具，即将执行。 */
    data class ToolCallStarted(val call: AssistantToolCall) : ChatEvent

    /**
     * 工具执行完毕。
     *
     * [ToolResult.isError] 为 true 不代表整场对话失败 —— 错误信息已经回灌给模型，
     * 它会自行决定重试、换工具还是直接告诉用户做不到。
     */
    data class ToolCallFinished(
        val callId: String,
        val toolName: String,
        val result: ToolResult,
    ) : ChatEvent

    /** token 用量。每轮各报一次，需要总量的话由调用方累加。 */
    data class Usage(val promptTokens: Int, val completionTokens: Int) : ChatEvent

    /** 模型给出最终答案，整场对话结束。 */
    data class Completed(val message: ChatMessage) : ChatEvent

    /** 对话中断。已经推送过的 [TextDelta] 仍然有效，UI 应保留它们。 */
    data class Failed(val error: Throwable) : ChatEvent
}

/**
 * 工具循环超过上限。
 *
 * 触发场景：模型反复调用同一个工具且每次都得到同样的错误（典型是参数怎么填都不对），
 * 或者工具之间互相触发。不设上限会一直烧 token。
 */
class ToolLoopExceededException(rounds: Int) : Exception(
    "连续 $rounds 轮工具调用仍未给出最终答案。" +
        "可能是模型陷入了重复调用，或者某个工具一直返回同样的错误。"
)
