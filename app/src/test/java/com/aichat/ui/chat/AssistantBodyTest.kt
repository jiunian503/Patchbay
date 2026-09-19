package com.aichat.ui.chat

import com.aichat.chat.ChatMessage
import com.aichat.chat.MessageStatus
import com.aichat.domain.llm.AssistantToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 助手消息正文区的取舍。
 *
 * ## 为什么值得单独测
 *
 * 这个判断出过一个「看着不像 bug」的 bug：真机上发一条消息、Key 是错的，
 * 界面上出现的是
 *
 * ```
 * （没有内容）
 * HTTP 401: Authentication Fails, Your api key: ****abcd is invalid
 * ```
 *
 * 逻辑本身没错 —— 正文确实为空、也确实没有工具调用 —— 但它把
 * **「模型没说话」和「请求没成功」混成了同一件事**，而且是往更没用的那一边混：
 * 用户看到「（没有内容）」会以为模型坏了，而该做的动作（换 Key）
 * 缩在下面一行 `labelSmall` 里。真机上第一眼扫过去，注意力全被
 * 那句「（没有内容）」吃掉了。
 *
 * 这类判断是纯字符串与状态的分支，抽出来就能钉住，不需要跑一个真的 LazyColumn。
 */
class AssistantBodyTest {

    private fun message(
        content: String = "",
        status: MessageStatus = MessageStatus.Complete,
        error: String? = null,
        toolCalls: List<AssistantToolCall> = emptyList(),
    ) = MessageUi(
        id = 1,
        role = ChatMessage.Role.Assistant,
        content = content,
        toolCalls = toolCalls,
        status = status,
        error = error,
    )

    @Test
    fun `有正文就正常渲染 Markdown`() {
        val body = assistantBody(message(content = "你好"))
        assertEquals(AssistantBody.Text("你好"), body)
    }

    @Test
    fun `正文只有空白也算没有正文`() {
        // 模型有时会吐一串换行。那不算「说了话」，
        // 落进 Text 分支会渲染成一片空白，比提示还难懂
        assertEquals(AssistantBody.Empty, assistantBody(message(content = "  \n\n ")))
    }

    @Test
    fun `失败且没有正文时 原因是正文`() {
        val body = assistantBody(
            message(
                status = MessageStatus.Failed,
                error = "HTTP 401: Authentication Fails, Your api key: ****abcd is invalid",
            ),
        )
        assertEquals(
            AssistantBody.Failure("HTTP 401: Authentication Fails, Your api key: ****abcd is invalid"),
            body,
        )
    }

    @Test
    fun `失败但没有原因时兜一句 而不是显示成没有内容`() {
        // `error` 为空只可能来自状态机没写全。此时**仍然不能**退回 Empty ——
        // Empty 显示的是「（没有内容）」，那正好是这次要修掉的那句话
        val body = assistantBody(message(status = MessageStatus.Failed))
        assertTrue("失败必须落在 Failure 分支，实际是 $body", body is AssistantBody.Failure)
        assertEquals(AssistantBody.Failure("请求失败"), body)
    }

    @Test
    fun `失败优先于工具调用`() {
        // 一次成功的工具调用之后才轮到模型说话；能走到失败说明这一轮
        // 没有产出可用结果。工具卡片仍会渲染在下面（它记录了发生过什么），
        // 但正文位置留给原因 —— 用户此刻要知道的是「为什么没有」
        val body = assistantBody(
            message(
                status = MessageStatus.Failed,
                error = "连接被重置",
                toolCalls = listOf(AssistantToolCall(id = "c1", name = "get_weather", argumentsJson = "{}")),
            ),
        )
        assertEquals(AssistantBody.Failure("连接被重置"), body)
    }

    @Test
    fun `失败但已经有部分正文时 正文照常显示`() {
        // 流到一半断了：用户已经看到的那半句是真的，不能因为失败就丢掉，
        // 原因交给页脚去说（`assistantBody` 走 Text 分支，页脚就不被抑制）
        val body = assistantBody(
            message(content = "上海今天的天气", status = MessageStatus.Failed, error = "连接被重置"),
        )
        assertEquals(AssistantBody.Text("上海今天的天气"), body)
    }

    @Test
    fun `有工具调用但没有正文时 正文位置留空`() {
        // 工具卡片已经说明了发生过什么，再挂一句「（没有内容）」是噪音。
        // 注意这条**不带失败** —— 带上失败就该走 Failure 了
        val body = assistantBody(
            message(toolCalls = listOf(AssistantToolCall(id = "c1", name = "get_weather", argumentsJson = "{}"))),
        )
        assertEquals(AssistantBody.Suppressed, body)
    }

    @Test
    fun `既没正文也没工具也没失败时 才提示没有内容`() {
        assertEquals(AssistantBody.Empty, assistantBody(message()))
    }

    @Test
    fun `被停止时不算失败`() {
        // 用户自己按的停止，不该弹一块红底错误
        assertEquals(AssistantBody.Empty, assistantBody(message(status = MessageStatus.Stopped)))
    }
}
