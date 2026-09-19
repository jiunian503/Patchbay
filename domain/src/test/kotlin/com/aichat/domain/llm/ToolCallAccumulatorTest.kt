package com.aichat.domain.llm

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallAccumulatorTest {

    private fun delta(
        index: Int = 0,
        id: String? = null,
        name: String? = null,
        args: String? = null,
    ) = ChatStreamEvent.ToolCallDelta(index, id, name, args)

    // ---------- 参数分片拼接 ----------

    @Test
    fun `单工具调用的参数分片被拼接`() {
        val acc = ToolCallAccumulator()
        acc.accept(delta(0, id = "call_a", name = "get_weather", args = ""))
        acc.accept(delta(0, args = """{"la"""))
        acc.accept(delta(0, args = """titude":"""))
        acc.accept(delta(0, args = "39.9}"))

        val calls = acc.build()
        assertEquals(1, calls.size)
        assertEquals("call_a", calls[0].id)
        assertEquals("get_weather", calls[0].name)
        assertEquals("""{"latitude":39.9}""", calls[0].argumentsJson)
    }

    @Test
    fun `多个工具调用按 index 交错归并`() {
        val acc = ToolCallAccumulator()
        acc.accept(delta(0, id = "call_a", name = "f_a", args = """{"x":"""))
        acc.accept(delta(1, id = "call_b", name = "f_b", args = """{"y":"""))
        acc.accept(delta(0, args = "1}"))
        acc.accept(delta(1, args = "2}"))

        val calls = acc.build()
        assertEquals(2, calls.size)
        assertEquals("call_a", calls[0].id)
        assertEquals("""{"x":1}""", calls[0].argumentsJson)
        assertEquals("call_b", calls[1].id)
        assertEquals("""{"y":2}""", calls[1].argumentsJson)
    }

    @Test
    fun `产出顺序按 index 升序而非到达顺序`() {
        val acc = ToolCallAccumulator()
        // 先到的是 index 1
        acc.accept(delta(1, id = "b", name = "f_b", args = "{}"))
        acc.accept(delta(0, id = "a", name = "f_a", args = "{}"))

        assertEquals(listOf("a", "b"), acc.build().map { it.id })
    }

    // ---------- 服务端差异容错 ----------

    @Test
    fun `重复发送 id 与 name 时幂等`() {
        val acc = ToolCallAccumulator()
        // 做协议转换的网关会在每个分片都带 id 和 name。
        // 碎片用普通字符串而非三引号：`"""{"a"""` 求值结果是 `{"a`（不含尾引号），
        // 极易看错，这里显式写转义。
        acc.accept(delta(0, id = "call_a", name = "get_weather", args = "{\"a\""))
        acc.accept(delta(0, id = "call_a", name = "get_weather", args = ":1}"))

        val calls = acc.build()
        assertEquals(1, calls.size)
        assertEquals("call_a", calls[0].id)
        assertEquals("get_weather", calls[0].name)
        assertEquals("{\"a\":1}", calls[0].argumentsJson)
    }

    @Test
    fun `省略 index 时归并到同一槽`() {
        val acc = ToolCallAccumulator()
        acc.accept(delta(0, id = "call_a", name = "f", args = "a"))
        acc.accept(delta(0, args = "b"))

        val calls = acc.build()
        assertEquals(1, calls.size)
        assertEquals("ab", calls[0].argumentsJson)
    }

    @Test
    fun `空参数分片不产生噪声`() {
        val acc = ToolCallAccumulator()
        acc.accept(delta(0, id = "call_t", name = "get_time", args = ""))
        acc.accept(delta(0, args = ""))

        assertEquals("{}", acc.build()[0].argumentsJson)
    }

    @Test
    fun `无参数工具补空对象`() {
        val acc = ToolCallAccumulator()
        acc.accept(delta(0, id = "call_t", name = "get_time"))

        assertEquals("{}", acc.build()[0].argumentsJson)
    }

    @Test
    fun `缺少 id 时合成稳定的占位 id`() {
        val acc = ToolCallAccumulator()
        acc.accept(delta(0, name = "f", args = "{}"))

        // 回传结果必须带 tool_call_id，空串会被服务端拒绝
        assertEquals("call_synthetic_0", acc.build()[0].id)
    }

    @Test
    fun `缺少 name 的调用被标记为不可执行`() {
        val acc = ToolCallAccumulator()
        acc.accept(delta(0, id = "call_a", args = "{}"))

        assertFalse(acc.build()[0].isExecutable)
    }

    @Test
    fun `完整的调用可执行`() {
        val acc = ToolCallAccumulator()
        acc.accept(delta(0, id = "call_a", name = "f", args = "{}"))

        assertTrue(acc.build()[0].isExecutable)
    }

    // ---------- 空状态 ----------

    @Test
    fun `没有分片时为空`() {
        val acc = ToolCallAccumulator()
        assertTrue(acc.isEmpty())
        assertTrue(acc.build().isEmpty())
    }

    @Test
    fun `收到分片后不再为空`() {
        val acc = ToolCallAccumulator()
        acc.accept(delta(0, id = "a", name = "f", args = "{}"))
        assertFalse(acc.isEmpty())
    }

    // ---------- 参数解析 ----------

    @Test
    fun `空串解析为空对象`() {
        val obj = ToolArguments.parseOrNull("")
        assertEquals(0, obj?.size)
    }

    @Test
    fun `纯空白解析为空对象`() {
        val obj = ToolArguments.parseOrNull("   \n ")
        assertEquals(0, obj?.size)
    }

    @Test
    fun `合法对象原样解析`() {
        val obj = ToolArguments.parseOrNull("""{"latitude":39.9,"unit":"celsius"}""")
        assertEquals(2, obj?.size)
        assertEquals(JsonPrimitive(39.9), obj?.get("latitude"))
    }

    @Test
    fun `坏 JSON 返回 null 而不是静默吞掉`() {
        // 模型偶尔会吐出不闭合的 JSON，静默当空参数会让工具返回错误结果
        assertNull(ToolArguments.parseOrNull("""{"latitude":39.9"""))
    }

    @Test
    fun `顶层是数组时返回 null`() {
        assertNull(ToolArguments.parseOrNull("""[1,2,3]"""))
    }

    @Test
    fun `顶层是字符串时返回 null`() {
        assertNull(ToolArguments.parseOrNull(""""just a string""""))
    }
}
