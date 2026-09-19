package com.aichat.domain.llm

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实抓包的报文形状验证反序列化。
 *
 * 这些 JSON 里刻意保留了各家服务端的差异（多余字段、缺失字段、
 * 私有字段），因为线上就是这么来的 —— 测试用例太干净反而测不出问题。
 */
class OpenAiChatDtosTest {

    private fun decode(json: String) = LlmJson.decodeFromString<ChatCompletionChunk>(json)

    private fun events(json: String) = decode(json).toStreamEvents()

    // ---------- 文本增量 ----------

    @Test
    fun `首个分片带 role 与正文`() {
        val json = """{"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"你好"}}]}"""
        assertEquals(listOf(ChatStreamEvent.TextDelta("你好")), events(json))
    }

    @Test
    fun `正文为空的分片不产生事件`() {
        // 收尾分片常见 `"delta":{"content":""}`，不该产生空 delta
        val json = """{"choices":[{"index":0,"delta":{"content":""}}]}"""
        assertEquals(emptyList<ChatStreamEvent>(), events(json))
    }

    @Test
    fun `只有 role 的起始分片不产生事件`() {
        val json = """{"choices":[{"index":0,"delta":{"role":"assistant"}}]}"""
        assertEquals(emptyList<ChatStreamEvent>(), events(json))
    }

    // ---------- 思维链 ----------

    @Test
    fun `reasoning_content 被识别为思维链`() {
        val json = """{"choices":[{"index":0,"delta":{"reasoning_content":"让我想想"}}]}"""
        assertEquals(listOf(ChatStreamEvent.ReasoningDelta("让我想想")), events(json))
    }

    @Test
    fun `reasoning 别名同样被识别`() {
        val json = """{"choices":[{"index":0,"delta":{"reasoning":"再想想"}}]}"""
        assertEquals(listOf(ChatStreamEvent.ReasoningDelta("再想想")), events(json))
    }

    @Test
    fun `思维链与正文同时出现时各归各的`() {
        val json = """{"choices":[{"index":0,"delta":{"reasoning_content":"嗯","content":"答案"}}]}"""
        assertEquals(
            listOf(ChatStreamEvent.ReasoningDelta("嗯"), ChatStreamEvent.TextDelta("答案")),
            events(json),
        )
    }

    // ---------- 工具调用 ----------

    @Test
    fun `工具调用首片带 id 与函数名`() {
        val json = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}"""
        assertEquals(
            listOf(ChatStreamEvent.ToolCallDelta(0, "call_a", "get_weather", "")),
            events(json),
        )
    }

    @Test
    fun `工具调用后续分片只带参数`() {
        val json = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"lat\":39}"}}]}}]}"""
        assertEquals(
            listOf(ChatStreamEvent.ToolCallDelta(0, null, null, """{"lat":39}""")),
            events(json),
        )
    }

    @Test
    fun `单个分片里可以并发多个工具调用`() {
        val json = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"f_a","arguments":"{}"}},{"index":1,"id":"b","function":{"name":"f_b","arguments":"{}"}}]}}]}"""
        val out = events(json)
        assertEquals(2, out.size)
        assertEquals(ChatStreamEvent.ToolCallDelta(0, "a", "f_a", "{}"), out[0])
        assertEquals(ChatStreamEvent.ToolCallDelta(1, "b", "f_b", "{}"), out[1])
    }

    // ---------- 收尾与用量 ----------

    @Test
    fun `finish_reason 产生收尾事件`() {
        val json = """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        assertEquals(listOf(ChatStreamEvent.Finished(FinishReason.Stop)), events(json))
    }

    @Test
    fun `usage 帧的 choices 为空`() {
        val json = """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":34,"total_tokens":46}}"""
        val out = events(json)
        assertEquals(listOf(ChatStreamEvent.Usage(12, 34)), out)
        assertEquals(46, (out[0] as ChatStreamEvent.Usage).totalTokens)
    }

    @Test
    fun `完全没有 usage 字段时正常解析`() {
        val json = """{"choices":[{"index":0,"delta":{"content":"x"}}]}"""
        assertEquals(listOf(ChatStreamEvent.TextDelta("x")), events(json))
    }

    // ---------- 容错 ----------

    @Test
    fun `未知字段不影响解析`() {
        // 各家都会塞私有字段，不能因此报错
        val json = """{"id":"c1","object":"chat.completion.chunk","created":1,"system_fingerprint":"fp_x","vendor_ext":{"a":1},"choices":[{"index":0,"delta":{"content":"ok"},"logprobs":null}]}"""
        assertEquals(listOf(ChatStreamEvent.TextDelta("ok")), events(json))
    }

    @Test
    fun `最小帧也能解析`() {
        val json = """{"choices":[{"delta":{}}]}"""
        assertEquals(emptyList<ChatStreamEvent>(), events(json))
    }

    @Test
    fun `usage 为 null 时不产生事件`() {
        // coerceInputValues 把 null 转成默认值，不该抛异常
        val json = """{"choices":[{"index":0,"delta":{"content":"a"}}],"usage":null}"""
        assertEquals(listOf(ChatStreamEvent.TextDelta("a")), events(json))
    }

    @Test
    fun `usage 缺字段时补零`() {
        val json = """{"choices":[],"usage":{"prompt_tokens":5}}"""
        assertEquals(listOf(ChatStreamEvent.Usage(5, 0)), events(json))
    }

    @Test
    fun `content 为 null 时不产生事件`() {
        val json = """{"choices":[{"index":0,"delta":{"content":null}}]}"""
        assertEquals(emptyList<ChatStreamEvent>(), events(json))
    }

    // ---------- 停止原因映射 ----------

    @Test
    fun `停止原因按 OpenAI 取值映射`() {
        assertEquals(FinishReason.Stop, FinishReason.from("stop"))
        assertEquals(FinishReason.Length, FinishReason.from("length"))
        assertEquals(FinishReason.ToolCalls, FinishReason.from("tool_calls"))
        assertEquals(FinishReason.ContentFilter, FinishReason.from("content_filter"))
    }

    @Test
    fun `旧版 function_call 视为工具调用`() {
        assertEquals(FinishReason.ToolCalls, FinishReason.from("function_call"))
    }

    @Test
    fun `未知与缺失的停止原因都不崩`() {
        assertEquals(FinishReason.Unknown, FinishReason.from(null))
        assertEquals(FinishReason.Unknown, FinishReason.from("some_vendor_reason"))
    }

    // ---------- 端到端：SSE 文本 -> 事件 -> 完整工具调用 ----------

    /**
     * 把「SSE 解析 -> DTO 反序列化 -> 事件 -> 累加器」串起来跑一遍。
     * 这条链路是 function calling 的完整数据通路，任何一环错都会在这里暴露。
     */
    @Test
    fun `从 SSE 文本一路还原出可执行的工具调用`() {
        val stream = buildString {
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"我来查一下\"}}]}\n\n")
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]}}]}\n\n")
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"lat\\\":\"}}]}}]}\n\n")
            append("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"39.9}\"}}]}}]}\n\n")
            append("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
            append("data: [DONE]\n\n")
        }

        val parser = SseParser()
        val accumulator = ToolCallAccumulator()
        val text = StringBuilder()
        var finished: FinishReason? = null

        for (payload in parser.feed(stream)) {
            if (payload == "[DONE]") break
            for (event in decode(payload).toStreamEvents()) {
                when (event) {
                    is ChatStreamEvent.TextDelta -> text.append(event.text)
                    is ChatStreamEvent.ToolCallDelta -> accumulator.accept(event)
                    is ChatStreamEvent.Finished -> finished = event.reason
                    else -> Unit
                }
            }
        }

        assertEquals("我来查一下", text.toString())
        assertEquals(FinishReason.ToolCalls, finished)

        val calls = accumulator.build()
        assertEquals(1, calls.size)
        assertEquals("call_a", calls[0].id)
        assertEquals("get_weather", calls[0].name)
        assertTrue(calls[0].isExecutable)
        assertEquals("""{"lat":39.9}""", calls[0].argumentsJson)
        assertEquals(39.9, ToolArguments.parseOrNull(calls[0].argumentsJson)?.get("lat")?.toString()?.toDouble())
    }
}
