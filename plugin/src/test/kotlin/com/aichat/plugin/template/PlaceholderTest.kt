package com.aichat.plugin.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 占位符解析与渲染。
 *
 * 这一层的测试重点不是「能不能替换」，而是**替换的边界**：
 * 只替换一遍、缺失不当成空串、畸形原样留着。这三条每一条都是安全性质。
 *
 * 另外钉住 `raw` 和 `name` 的区别 —— 它们是两个不同的东西，
 * 混用会让报错信息指向一个清单里不存在的写法。
 */
class PlaceholderTest {

    private fun render(template: String, values: Map<String, String> = emptyMap()): Rendered =
        Placeholders.render(template) { p -> values[p.name] }

    // ---------- 分类 ----------

    @Test
    fun `普通名字是参数来源`() {
        assertEquals(
            Placeholder.Argument(raw = "latitude", name = "latitude"),
            Placeholders.parse("latitude"),
        )
    }

    @Test
    fun `settings 前缀是配置来源`() {
        assertEquals(
            Placeholder.Setting(raw = "settings.unit", name = "unit"),
            Placeholders.parse("settings.unit"),
        )
    }

    @Test
    fun `raw 保留完整原文而 name 是查找用的键`() {
        // 报错信息必须用 raw：`{{settings.unit}}` 没值时不能报成 `{{unit}}`，
        // 否则作者会拿着一个清单里根本不存在的写法去排查
        val p = Placeholders.parse("settings.unit")
        assertEquals("settings.unit", p.raw)
        assertEquals("unit", p.name)
    }

    @Test
    fun `空名字是畸形`() {
        assertEquals(Placeholder.Malformed(""), Placeholders.parse(""))
        assertEquals(Placeholder.Malformed(" "), Placeholders.parse(" "))
    }

    @Test
    fun `settings 后面没名字是畸形`() {
        assertEquals(Placeholder.Malformed("settings."), Placeholders.parse("settings."))
    }

    @Test
    fun `名字里有连字符是畸形`() {
        // 收紧到标识符是有意的：放宽只会让「作者拼错占位符」更难发现
        assertEquals(Placeholder.Malformed("a-b"), Placeholders.parse("a-b"))
    }

    @Test
    fun `数字开头的名字是畸形`() {
        assertEquals(Placeholder.Malformed("1abc"), Placeholders.parse("1abc"))
    }

    // ---------- 扫描 ----------

    @Test
    fun `扫描去重并保持出现顺序`() {
        val found = Placeholders.scan("{{a}}/{{settings.b}}/{{a}}")
        assertEquals(
            listOf(
                Placeholder.Argument(raw = "a", name = "a"),
                Placeholder.Setting(raw = "settings.b", name = "b"),
            ),
            found,
        )
    }

    // ---------- 渲染 ----------

    @Test
    fun `正常替换`() {
        val out = render("/v1/{{city}}?unit={{settings.unit}}", mapOf("city" to "上海", "unit" to "celsius"))
        assertEquals("/v1/上海?unit=celsius", out.text)
        assertTrue(out.missing.isEmpty())
    }

    @Test
    fun `缺失时保留原文并记进 missing`() {
        // 关键：不能替换成空串。「用户没填密钥」和「用户填了空」要能分开
        val out = render("Bearer {{settings.key}}")
        assertEquals("Bearer {{settings.key}}", out.text)
        // missing 里带的是完整原文 —— 调用方按 `{{$it}}` 拼报错信息，
        // 拼出来必须是作者写的那个样子
        assertEquals(listOf("settings.key"), out.missing)
        assertEquals("{{${out.missing.first()}}}", "{{settings.key}}")
    }

    @Test
    fun `空串是有值，不算缺失`() {
        // 空串是「用户明确填了空」，由调用方决定怎么处理；
        // 这一层不能替它做「等于没填」的判断，否则
        // `Authorization: Bearer ` 和「没有这个头」就分不开了
        val out = render("[{{x}}]", mapOf("x" to ""))
        assertEquals("[]", out.text)
        assertTrue(out.missing.isEmpty())
    }

    @Test
    fun `畸形占位符原样留着且不算缺失`() {
        val out = render("/{{}}/{{a-b}}")
        assertEquals("/{{}}/{{a-b}}", out.text)
        assertTrue(out.missing.isEmpty())
    }

    @Test
    fun `只替换一遍 —— 值里的占位符不会被二次展开`() {
        // 这是安全性质：模型参数里写 {{settings.key}} 只会变成字面文字，
        // 不可能反过来去读用户配置
        val out = render("{{a}}", mapOf("a" to "{{settings.key}}"))
        assertEquals("{{settings.key}}", out.text)
    }

    @Test
    fun `没有占位符时原样返回`() {
        assertEquals("plain", render("plain").text)
    }

    @Test
    fun `同一个占位符出现两次只记一次缺失`() {
        val out = render("{{a}}/{{a}}")
        assertEquals(listOf("a"), out.missing)
    }
}
