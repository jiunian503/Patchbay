package com.aichat.network

import com.aichat.domain.llm.ChatStreamEvent
import kotlinx.coroutines.flow.Flow

/**
 * 一次流式对话请求的抽象。
 *
 * 存在的唯一理由是**可测**：对话核心的编排逻辑（尤其是工具循环）必须能用
 * 假实现驱动 —— 按脚本吐出文本、工具调用、错误，逐条验证引擎的反应。
 * 如果直接依赖 [OpenAiChatClient]，那些测试就只能靠真网络或反射。
 */
interface ChatCompletionClient {

    /**
     * 发起请求，返回事件流。
     *
     * Flow 正常完成 = 模型说完了；抛 [ChatApiException] = 失败；被取消 = 用户停止。
     */
    fun stream(request: ChatCompletionRequest): Flow<ChatStreamEvent>
}
