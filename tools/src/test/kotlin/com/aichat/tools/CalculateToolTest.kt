package com.aichat.tools

import com.aichat.domain.tool.ToolResult
import java.util.Locale
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计算工具。重点在两处：
 *
 * 1. **运算优先级与结合性** —— 这是解析器最容易写错的地方，而且错了以后
 *    结果看起来「差不多对」，不容易被发现（比如 `2-3-4` 算成 5 而不是 -5）。
 * 2. **模型给的参数不可信** —— 类型不对、参数缺失、塞进来一个对象，
 *    都必须变成一句人话回灌给模型，而不是抛异常把整场对话打断。
 */
class CalculateToolTest {

    private val tool = CalculateTool()

    private fun args(expression: String): JsonObject =
        buildJsonObject { put(CalculateTool.EXPRESSION, expression) }

    private suspend fun eval(expression: String): ToolResult = tool.execute(args(expression))

    /** 取「= 后面那段」，也就是结果本身。 */
    private suspend fun value(expression: String): String {
        val result = eval(expression)
        assertFalse("「$expression」不该失败：${result.content}", result.isError)
        return result.content.substringAfter(" = ")
    }

    // ---------- 优先级与结合性 ----------

    @Test
    fun `加减乘除的优先级正确`() = runTest {
        assertEquals("14", value("2+3*4"))
        assertEquals("20", value("(2+3)*4"))
        assertEquals("7", value("10-6/2"))
        assertEquals("2", value("10/(6-1)"))
    }

    @Test
    fun `同级运算从左到右`() = runTest {
        // 写成右结合会得到 2-(3-4)=3，是经典错误
        assertEquals("-5", value("2-3-4"))
        assertEquals("4", value("100/5/5"))
    }

    @Test
    fun `幂是右结合且优先级高于一元负号`() = runTest {
        // 2^(3^2) = 2^9 = 512，而不是 (2^3)^2 = 64
        assertEquals("512", value("2^3^2"))
        // -2^2 = -(2^2) = -4，和数学惯例一致，不是 (-2)^2 = 4
        assertEquals("-4", value("-2^2"))
        assertEquals("4", value("(-2)^2"))
    }

    @Test
    fun `取余可用且对零取余有明确报错`() = runTest {
        assertEquals("1", value("10%3"))

        val result = eval("10%0")
        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("取余"))
    }

    @Test
    fun `除以零报的是有限数错误而不是崩溃`() = runTest {
        val result = eval("1/0")
        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("不是有限数"))
    }

    @Test
    fun `空白不影响解析`() = runTest {
        assertEquals("14", value("  2 + 3 * 4  "))
    }

    // ---------- 函数与常量 ----------

    @Test
    fun `内置函数可用`() = runTest {
        assertEquals("3", value("sqrt(9)"))
        assertEquals("5", value("abs(-5)"))
        assertEquals("3", value("round(2.6)"))
        assertEquals("2", value("floor(2.9)"))
        assertEquals("3", value("ceil(2.1)"))
        assertEquals("1024", value("pow(2,10)"))
        assertEquals("3", value("min(3,7)"))
        assertEquals("7", value("max(3,7)"))
    }

    @Test
    fun `函数名大小写不敏感`() = runTest {
        assertEquals("3", value("SQRT(9)"))
    }

    @Test
    fun `pi 和 e 可用`() = runTest {
        assertTrue(value("pi").startsWith("3.14159"))
        assertTrue(value("e").startsWith("2.71828"))
    }

    @Test
    fun `函数参数个数不对会明确报错`() = runTest {
        val result = eval("sqrt(1,2)")
        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("需要 1 个参数"))
    }

    @Test
    fun `未知函数名会被指出来`() = runTest {
        val result = eval("foo(1)")
        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("没有 foo"))
    }

    @Test
    fun `孤立的标识符会被当成未知常量`() = runTest {
        val result = eval("x+1")
        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("不认识"))
    }

    // ---------- 语法错误 ----------

    @Test
    fun `括号不闭合会报错`() = runTest {
        val result = eval("(1+2")
        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("括号"))
    }

    @Test
    fun `多余字符会指出位置`() = runTest {
        val result = eval("1+2abc")
        assertTrue(result.isError)
        // 报错里必须带位置，模型据此才能改对
        assertTrue(result.content, result.content.contains("第"))
    }

    @Test
    fun `空表达式有明确报错`() = runTest {
        val result = eval("   ")
        assertTrue(result.isError)
    }

    @Test
    fun `科学计数法能解析`() = runTest {
        assertEquals("1500", value("1.5e3"))
        assertEquals("0.00025", value("2.5E-4"))
    }

    // ---------- 参数不可信 ----------

    @Test
    fun `参数缺失时提示怎么填`() = runTest {
        val result = tool.execute(buildJsonObject { })
        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains(CalculateTool.EXPRESSION))
    }

    @Test
    fun `参数不是字符串时不会崩`() = runTest {
        // 模型偶尔会把整个对象塞进一个标量字段
        val result = tool.execute(
            buildJsonObject { put(CalculateTool.EXPRESSION, buildJsonObject { put("a", 1) }) },
        )
        assertTrue(result.isError)
    }

    // ---------- 数字格式化 ----------

    @Test
    fun `整数结果不带小数点`() = runTest {
        assertEquals("4", value("2+2"))
        assertEquals("6", value("1.5*4"))
    }

    @Test
    fun `浮点噪声被收掉`() = runTest {
        // 0.1+0.2 在 IEEE754 下是 0.30000000000000004
        assertEquals("0.3", value("0.1+0.2"))
    }

    @Test
    fun `除不尽时保留十位有效数字`() = runTest {
        assertEquals("0.3333333333", value("1/3"))
    }

    @Test
    fun `零就是零`() = runTest {
        assertEquals("0", value("1-1"))
        assertEquals("0", value("0*999"))
    }

    @Test
    fun `负零不会显示成 -0`() = runTest {
        assertEquals("0", value("-0"))
    }

    /**
     * 格式化必须与默认 locale 无关。
     *
     * 德语/法语环境下 `"%.10g".format(1.5)` 会输出 `1,5` ——
     * 模型会把逗号读成千位分隔符，用户也会以为算错了。
     * 这个 bug 只在特定区域的机器上出现，所以必须显式测。
     */
    @Test
    fun `结果不受默认 locale 影响`() = runTest {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("0.3", value("0.1+0.2"))
            assertEquals("0.3333333333", value("1/3"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(Locale.getDefault())
    }

    // ---------- 元数据 ----------

    @Test
    fun `工具定义符合模型能用的最小要求`() {
        val definition = tool.definition
        assertEquals(CalculateTool.NAME, definition.name)
        assertTrue(definition.description.length > 20)
        assertEquals("object", definition.parameters["type"]?.toString()?.trim('"'))
    }

    @Test
    fun `默认不需要用户确认`() {
        assertFalse(tool.requiresConfirmation)
    }
}
