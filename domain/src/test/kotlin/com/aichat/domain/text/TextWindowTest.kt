package com.aichat.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 以命中词为中心的正文开窗。
 *
 * ## 为什么值得单独测
 *
 * 一条消息可能几千字，而展示窗口只有几行。**如果命中词不在窗口里**，
 * 用户搜「会议纪要」看到的是「帮我把下面这段整理一下：……」，他会以为搜错了；
 * 模型则会拿一段看不出相关性的文字去当记忆引用。两边都不报任何错。
 *
 * 这个类现在有**两个消费者**（搜索结果卡片、模型的 `search_history` 工具），
 * 所以它必须待在 `:domain` 而不是 UI 层 —— 各写一份的话，两边会慢慢漂移成
 * 「用户看到的片段和模型看到的不是同一段」。
 *
 * 另外，[TextWindow.hitRange] 是一个**下标区间**，越界就是
 * `StringIndexOutOfBoundsException`。渲染时崩溃比显示错更糟，
 * 所以这里对每个用例都检查区间合法性。
 */
class TextWindowTest {

    /** 高亮区间必须在文本范围内，否则渲染时会崩。 */
    private fun assertRangeValid(w: TextWindow) {
        val range = w.hitRange ?: return
        assertTrue(
            "高亮区间 $range 越界（文本长度 ${w.text.length}）",
            range.first >= 0 && range.last < w.text.length,
        )
        assertTrue("区间反了：$range", range.first <= range.last)
    }

    /** 高亮的那几个字符，必须**正好是**查询词。 */
    private fun highlightedText(w: TextWindow): String? {
        val range = w.hitRange ?: return null
        return w.text.substring(range.first, range.last + 1)
    }

    @Test
    fun `短内容原样返回并标出命中位置`() {
        val w = textWindow("帮我把会议纪要整理成表格", "会议")

        assertEquals("帮我把会议纪要整理成表格", w.text)
        assertEquals("会议", highlightedText(w))
        assertRangeValid(w)
    }

    @Test
    fun `长内容以命中词为中心截取`() {
        val content = "前".repeat(200) + "会议" + "后".repeat(200)

        val w = textWindow(content, "会议", maxChars = 100)

        assertEquals("会议", highlightedText(w))
        assertTrue("两端都该有省略号", w.text.startsWith("…") && w.text.endsWith("…"))
        assertRangeValid(w)
    }

    @Test
    fun `命中词在尾部时窗口右对齐`() {
        val content = "前".repeat(300) + "会议"

        val w = textWindow(content, "会议", maxChars = 100)

        assertEquals("会议", highlightedText(w))
        // 贴到正文末尾了，后面没有可省略的东西
        assertTrue("末尾不该有省略号", !w.text.endsWith("…"))
        assertRangeValid(w)
    }

    /**
     * FTS 是 AND 语义而不是子串语义：搜「对话AI」能召回「AI对话软件」，
     * 但 `indexOf("对话AI")` 找不到。这时必须退回从开头截，
     * 而不是假装知道该显示哪一段。
     */
    @Test
    fun `定位不到命中词时从开头截且不高亮`() {
        val content = "AI对话软件" + "x".repeat(300)

        val w = textWindow(content, "对话AI", maxChars = 100)

        assertNull(w.hitRange)
        assertTrue(w.text.startsWith("AI对话软件"))
        assertTrue(w.text.endsWith("…"))
        assertRangeValid(w)
    }

    @Test
    fun `换行与连续空白被压平`() {
        val w = textWindow("a\n\n  b\tc", "b")

        assertEquals("a b c", w.text)
        assertEquals("b", highlightedText(w))
    }

    /**
     * 全角空格（U+3000）和不间断空格（U+00A0）也必须被压平。
     *
     * 只写 `\\s` 的话它们会原样留下 —— 在界面上表现为一段莫名其妙的空隙，
     * 而正文里本来就有换行的消息看起来会像排版坏了。
     * 中文输入法下全角空格很容易打出来（Shift + 空格），不是边角情况。
     */
    @Test
    fun `全角空格与不间断空格也被压平`() {
        assertEquals("a b c", textWindow("a\u3000b\u00A0c", "b").text)
        assertEquals("a b c", textWindow("a\u2003b\u2002c", "b").text)
    }

    @Test
    fun `只在真的截断时才加省略号`() {
        val w = textWindow("短消息", "短")

        assertEquals("短消息", w.text)
    }

    /**
     * 极端情况：查询词比窗口还长。窗口会被拉到命中处，但命中词仍然显示不全 ——
     * 这时**不能**高亮。标出半个词比不标更让人困惑。
     */
    @Test
    fun `命中词被窗口切掉时不标半截词`() {
        val longTerm = "会" + "议".repeat(200)
        val content = "x".repeat(50) + longTerm + "y".repeat(50)

        val w = textWindow(content, longTerm, maxChars = 50)

        assertNull(w.hitRange)
        assertRangeValid(w)
    }

    @Test
    fun `多段查询取第一个能定位到的词`() {
        val content = "x".repeat(300) + "纪要"

        val w = textWindow(content, "会议 纪要", maxChars = 100)

        assertEquals("纪要", highlightedText(w))
        assertRangeValid(w)
    }

    @Test
    fun `英文匹配忽略大小写`() {
        val content = "x".repeat(300) + "RikkaHub"

        val w = textWindow(content, "rikkahub", maxChars = 100)

        assertEquals("RikkaHub", highlightedText(w))
        assertRangeValid(w)
    }

    @Test
    fun `空正文返回空窗口`() {
        assertEquals(TextWindow("", null), textWindow("", "会议"))
        assertEquals(TextWindow("", null), textWindow("   \n\t  ", "会议"))
    }

    /** 查询串为空时不该崩，也不该乱标。 */
    @Test
    fun `空查询不高亮任何东西`() {
        val w = textWindow("一段普通的消息", "")

        assertNull(w.hitRange)
        assertEquals("一段普通的消息", w.text)
    }

    /**
     * `maxChars <= 0` 必须**立刻失败**而不是返回一个语义未定义的窗口。
     *
     * 不校验的话 `end = start + 0` 会让 `substring(from, end)` 抛
     * `StringIndexOutOfBoundsException`，异常信息里只有一个下标，
     * 完全看不出是调用方把窗口配成了 0。
     */
    @Test
    fun `窗口大小非正数时直接失败`() {
        assertThrows(IllegalArgumentException::class.java) { textWindow("内容", "内容", maxChars = 0) }
        assertThrows(IllegalArgumentException::class.java) { textWindow("内容", "内容", maxChars = -1) }
    }

    /**
     * 把各种长度 × 各种命中位置都过一遍区间合法性。
     *
     * 单独几个用例覆盖不到「窗口正好卡在边界上」的那些组合，
     * 而边界正是 `substring` 会崩的地方。
     */
    @Test
    fun `各种长度与命中位置下高亮区间都合法`() {
        val lengths = listOf(1, 50, 119, 120, 121, 200, 400)
        val offsets = listOf(0, 1, 60, 119, 120, 121, 300)

        for (len in lengths) {
            for (offset in offsets) {
                if (offset + 2 > len) continue
                val content = "a".repeat(offset) + "会议" + "b".repeat(len - offset - 2)

                val w = textWindow(content, "会议", maxChars = 120)

                assertRangeValid(w)
                // 命中词一定在正文里，所以只要窗口没把它切掉，就必须被标出来
                if (w.hitRange != null) {
                    assertEquals("会议", highlightedText(w))
                }
            }
        }
    }
}
