package com.aichat.plugin.runtime.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SSE 切分。
 *
 * ## 为什么这些用例值得逐条写
 *
 * 切分错了的症状是「JSON 少了一半」—— 报出来是一个解析错误，
 * 从症状看不出是切分的问题。而且切错的代价**不对称**：
 * 少切一个事件 → 「对端没返回结果」；多切一个事件 → 「JSON 不完整」。
 * 两种都像是服务端的问题。
 *
 * 所以这里逐条钉住规则，而不是靠一个「正常的流能解析」的用例。
 */
class McpSseTest {

    private val parser = SseParser()

    /** 把一段文本按行喂进去，收集所有派发出来的事件（含流结束时的收尾）。 */
    private fun feed(text: String): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        for (line in text.split("\n")) {
            parser.line(line)?.let { out += it }
        }
        parser.finish()?.let { out += it }
        return out
    }

    @Test
    fun `一条 data 加一个空行就是一个事件`() {
        val events = feed("data: {\"a\":1}\n\n")
        assertEquals(1, events.size)
        assertEquals("{\"a\":1}", events[0].data)
    }

    @Test
    fun `CRLF 换行同样能切`() {
        // 服务端用 \r\n 是常态（很多框架的默认），只认 \n 的话
        // 每行的值末尾会多一个 \r，JSON 解析直接失败
        val events = feed("data: {\"a\":1}\r\n\r\n")
        assertEquals(1, events.size)
        assertEquals("{\"a\":1}", events[0].data)
    }

    @Test
    fun `注释行是 keep-alive 不是事件边界`() {
        // MCP 规范鼓励服务端周期性发 `:\r\n` 做心跳。把它当成事件的话，
        // 一个空闲的流会不断产出「空事件」
        val events = feed(":\n\ndata: x\n\n:\r\n\r\n")
        assertEquals("注释行不该产生事件，只应有一条真正的 data", 1, events.size)
        assertEquals("x", events[0].data)
    }

    @Test
    fun `多行 data 用换行拼起来`() {
        val events = feed("data: line1\ndata: line2\n\n")
        assertEquals(1, events.size)
        assertEquals("line1\nline2", events[0].data)
    }

    @Test
    fun `字段名和值之间只剥一个空格`() {
        // `data:  x` 的值是 " x"。多剥的话，靠前导空格排版的 JSON
        // （缩进过的响应体）会被改掉
        assertEquals(" x", feed("data:  x\n\n")[0].data)
    }

    @Test
    fun `没有冒号的行等于字段名加空值`() {
        // 规范允许裸的字段名。当成错误的话会漏掉一个事件边界
        val events = feed("data\n\n")
        assertEquals(1, events.size)
        assertEquals("", events[0].data)
    }

    @Test
    fun `流结束时没有空行收尾的最后一条事件也要派发`() {
        // 只在最后一个事件里给出响应的服务端，漏了 finish 就会被判成
        // 「没返回结果」—— 而响应明明在流里
        val events = feed("data: {\"a\":1}")
        assertEquals(1, events.size)
        assertEquals("{\"a\":1}", events[0].data)
    }

    @Test
    fun `连续空行不产生空事件`() {
        val events = feed("\n\n\ndata: x\n\n\n\n")
        assertEquals(1, events.size)
    }

    @Test
    fun `event 字段被单独记下来`() {
        val events = feed("event: message\ndata: x\n\n")
        assertEquals(1, events.size)
        assertEquals("message", events[0].event)
        assertEquals("x", events[0].data)
    }

    @Test
    fun `id 和 retry 字段认出来但不产生事件`() {
        // MCP 不支持 Last-Event-ID 恢复，所以这两个字段对我们是噪音。
        // 但它们**不能**被当成 data —— 那会拼出一段不是 JSON 的文本
        val events = feed("id: 7\nretry: 1000\ndata: x\n\n")
        assertEquals(1, events.size)
        assertEquals("x", events[0].data)
    }

    @Test
    fun `空输入什么都不产生`() {
        assertEquals(emptyList<SseEvent>(), feed(""))
        assertNull(SseParser().finish())
    }
}
