package com.aichat.chat

import com.aichat.domain.llm.AssistantToolCall
import com.aichat.domain.llm.LlmJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [toDto] —— 领域模型 → 线上 DTO 的**唯一**转换点。
 *
 * 这个文件是补上来的：在这之前，`ChatMessageDto.assistant(...)` 只在生产代码里
 * 被调用过，**没有任何测试构造过它**，`toDto()` 整条链路的覆盖是零。
 *
 * 为什么值得守 —— 这里的每一条都不是「顺手加的断言」，而是
 * **错了不会报错、只会在真机上打不通或答错**的东西：
 *
 * - 四个 role 字符串是 OpenAI 方言，写错服务端直接认不出这条消息；
 * - 助手只调工具时正文必须发 `null`，发空串会被部分服务端判成
 *   「content 不能为空」；
 * - 工具调用回传时字段名必须是 `tool_calls`（下划线），且空列表要整个省略；
 * - 思维链**绝不能**进上下文 —— 模型看到自己上一轮的草稿会接着往下写，
 *   而不是重新回答。
 *
 * 断言用 [LlmJson]（生产用的那个实例）序列化之后再比对，不另配一份 Json：
 * 另配一份就是复制品，会和真实请求体一起漂移。
 *
 * ⚠️ 第一条顺带守住了 `:network` 里 `ChatMessageDto` 四个工厂函数写死的
 * 字面量 —— `:network` 看不见 `:chat`（依赖方向是 `:chat` → `:network`），
 * 所以那份词表只能从这里反向盯住。
 */
class ChatMessageDtoMappingTest {

    /**
     * 四个 role 字符串是**冻结**的。
     *
     * 写成四句 `assertEquals` 而不是遍历枚举：遍历只能证明映射自洽，
     * 证明不了映射没变过；冻结要的就是后者。
     */
    @Test
    fun `四个角色映射到线上词表`() {
        assertEquals("system", ChatMessage.system("你是助手").toDto().role)
        assertEquals("user", ChatMessage.user("你好").toDto().role)
        assertEquals("assistant", ChatMessage.assistant("你好呀").toDto().role)
        assertEquals("tool", ChatMessage.tool("call_a", "25°C").toDto().role)
    }

    /**
     * 助手只调工具、不说话时，`content` 必须是 `null` 而不是空串。
     *
     * 空串和「没有正文」在协议上是两回事：部分服务端对 `"content":""`
     * 直接回 400。
     */
    @Test
    fun `助手空正文发 null 而不是空串`() {
        assertNull(ChatMessage.assistant("").toDto().content)
        assertNull(
            "只有空白字符也算空 —— 模型有时会吐一个换行",
            ChatMessage.assistant("  \n ").toDto().content,
        )
    }

    /** 有正文时原样带出去，别把正常回答也一起吞了。 */
    @Test
    fun `助手有正文时原样带出去`() {
        assertEquals("北京今天晴", ChatMessage.assistant("北京今天晴").toDto().content)
    }

    /**
     * 工具调用回传时字段名必须是 `tool_calls`。
     *
     * 这一条尤其要紧：少了它，模型看到后面的 `tool` 结果却找不到对应的调用，
     * 会认为上下文损坏并拒绝继续 —— 而不是报一个能看出原因的错。
     */
    @Test
    fun `助手的工具调用按线上字段名回传`() {
        val call = AssistantToolCall(
            id = "call_a",
            name = "get_weather",
            argumentsJson = """{"city":"北京"}""",
        )
        val dto = ChatMessage.assistant("", listOf(call)).toDto()

        val sent = dto.toolCalls
        assertEquals("工具调用必须原样带上", 1, sent?.size)
        assertEquals("call_a", sent!![0].id)
        assertEquals("get_weather", sent[0].function.name)
        assertEquals("""{"city":"北京"}""", sent[0].function.arguments)

        val json = LlmJson.encodeToString(dto)
        assertTrue("请求体里必须出现下划线的 tool_calls：$json", json.contains(""""tool_calls":"""))
        assertFalse("不能发成驼峰 toolCalls：$json", json.contains(""""toolCalls":"""))
    }

    /**
     * 没有工具调用时，`tool_calls` 字段要**整个省略**，不能发一个空数组。
     *
     * 靠的是 `takeIf { it.isNotEmpty() }` —— 这行看起来像多余的保险，
     * 其实是协议要求，所以单独钉一条。
     */
    @Test
    fun `助手没有工具调用时不发 tool_calls 字段`() {
        val json = LlmJson.encodeToString(ChatMessage.assistant("你好").toDto())
        assertFalse("没有调用时不该出现 tool_calls：$json", json.contains("tool_calls"))
    }

    /** `tool` 消息必须带上它对应哪次调用，否则服务端找不到。 */
    @Test
    fun `工具结果带上 tool_call_id`() {
        val dto = ChatMessage.tool("call_a", "25°C").toDto()

        assertEquals("tool", dto.role)
        assertEquals("call_a", dto.toolCallId)

        val json = LlmJson.encodeToString(dto)
        assertTrue(
            "tool 消息缺 tool_call_id 会被服务端拒绝：$json",
            json.contains(""""tool_call_id":"call_a""""),
        )
    }

    /**
     * 思维链存下来给用户回看，但**绝不能**进上下文。
     *
     * 这条规则写在 [ChatMessage] 的 KDoc 里（「模型看到自己上一轮的草稿会
     * 继续往下写」），而它唯一的执行者是「DTO 里压根没有这个字段」这个
     * 结构性事实 —— 没有别的东西在守，所以在这里钉住。
     */
    @Test
    fun `思维链不进请求体`() {
        val dto = ChatMessage.assistant("答案是 42").copy(reasoning = "让我想想……").toDto()
        val json = LlmJson.encodeToString(dto)

        assertTrue("正文该发还是要发：$json", json.contains("答案是 42"))
        assertFalse("思维链只给用户回看，不能进上下文：$json", json.contains("让我想想"))
        assertFalse("请求体里不该出现 reasoning 相关字段：$json", json.contains("reasoning"))
    }
}
