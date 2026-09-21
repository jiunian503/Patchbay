package com.aichat.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PromptComposer] 的拼接顺序与空值处理。
 *
 * 这里只管**角色这一侧**（人设 + 世界书）。服务商附加提示词的拼接在
 * `:chat` 的 `ChatConfig.systemMessage()`，那边有自己的测试。
 */
class PromptComposerTest {

    private fun entry(id: String, content: String) =
        WorldBookEntry(id = id, keys = listOf("k"), content = content)

    @Test
    fun `两层都为空时返回 null`() {
        // 返回 null 而不是空串：调用方靠它判断「这一轮不需要注入」，
        // 而空串会被当成「注入了一段空内容」发出去
        assertNull(PromptComposer.compose(null, emptyList()))
        assertNull(PromptComposer.compose("", emptyList()))
        assertNull(PromptComposer.compose("   ", emptyList()))
        assertNull(PromptComposer.compose("\n", emptyList()))
    }

    @Test
    fun `只有人设时返回人设本身`() {
        assertEquals("你是一位诗人。", PromptComposer.compose("你是一位诗人。"))
    }

    @Test
    fun `只有世界书时返回词条内容`() {
        assertEquals("词条A", PromptComposer.compose(null, listOf(entry("a", "词条A"))))
    }

    @Test
    fun `顺序是人设在前、世界书在后`() {
        val composed = PromptComposer.compose(
            persona = "人设",
            entries = listOf(entry("a", "词条A"), entry("b", "词条B")),
        )
        assertEquals("人设\n\n词条A\n\n词条B", composed)
    }

    @Test
    fun `世界书条目按传入顺序拼接`() {
        // 传入顺序由 WorldBookMatcher 保证（按 orderIndex 排过），
        // 这里只负责不重排 —— 重排会让「界面上排好的顺序」和实际注入的
        // 顺序不一致，而那种不一致用户永远查不出来
        val composed = PromptComposer.compose(
            persona = null,
            entries = listOf(entry("z", "Z"), entry("a", "A")),
        )
        assertEquals("Z\n\nA", composed)
    }

    @Test
    fun `两端空白被裁掉`() {
        assertEquals("人设", PromptComposer.compose("  人设  "))
        assertEquals("人设\n\nA", PromptComposer.compose(" 人设 ", listOf(entry("a", " A "))))
    }

    @Test
    fun `只有空白的层被跳过，不留下多余空行`() {
        val composed = PromptComposer.compose(
            persona = "人设",
            entries = listOf(entry("blank", "   "), entry("real", "真词条")),
        )
        assertEquals("人设\n\n真词条", composed)
    }
}
