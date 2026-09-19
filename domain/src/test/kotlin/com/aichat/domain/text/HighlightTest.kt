package com.aichat.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命中区间。
 *
 * ## 为什么值得单独测
 *
 * 这是「点了搜索结果跳进会话」这整条链路的最后一环。前面几步 ——
 * 检索、定位到那条消息 —— 都已经能用了，但只要最后这一步画错了标记，
 * 用户看到的仍然是「跳过来了，可我还是得自己找那个词」，
 * 而**画错是安静地错**：区间越界会崩，区间偏一位只是标错一个字。
 *
 * ## 区间不变量
 *
 * 所有用例都过一遍 [assertValid]，它守三条：
 *
 * - 区间在文本范围内（越界 → 渲染时 `StringIndexOutOfBoundsException`）
 * - 起点不大于终点（`IntRange` 允许反过来写，渲染层会拿到空串）
 * - **输出两两之间至少隔一个字符**（合并逻辑的语义：重叠和紧邻都要并掉）
 *
 * 第三条是最容易被写错的：搜「北 京」时正文里的「北京」会命中两次，
 * 不合并的话视觉上是一个标记被切成两半，中间多一条接缝。
 */
class HighlightTest {

    private fun assertValid(text: String, ranges: List<IntRange>) {
        var prevLast = -2
        for (r in ranges) {
            assertTrue("区间 $r 越界（文本长度 ${text.length}）", r.first >= 0 && r.last < text.length)
            assertTrue("区间反了：$r", r.first <= r.last)
            assertTrue(
                "区间 $r 和前一个（终点 $prevLast）重叠或紧邻，合并逻辑漏了",
                r.first >= prevLast + 2,
            )
            prevLast = r.last
        }
    }

    /** 每个区间覆盖的那几个字符。 */
    private fun hits(text: String, ranges: List<IntRange>): List<String> =
        ranges.map { text.substring(it.first, it.last + 1) }

    // ---------------------------------------------------------------- 空输入

    @Test
    fun `空查询不高亮任何东西`() {
        assertEquals(emptyList<IntRange>(), highlightRanges("北京适合秋天去", ""))
    }

    @Test
    fun `只有空白的查询不高亮任何东西`() {
        val text = "北京适合秋天去"
        // 半角、全角、不间断空格各来一次 —— 它们都该被当成「没写查询词」
        for (query in listOf(" ", "  ", "\t", "\n", "\u3000", "\u00A0", " \u3000\u00A0 ")) {
            assertEquals("查询 ${query.map { it.code }} 不该高亮", emptyList<IntRange>(), highlightRanges(text, query))
        }
    }

    @Test
    fun `空文本不高亮任何东西`() {
        assertEquals(emptyList<IntRange>(), highlightRanges("", "北京"))
    }

    // ---------------------------------------------------------------- 基本命中

    @Test
    fun `单个命中给出精确区间`() {
        val text = "北京适合秋天去"
        val ranges = highlightRanges(text, "北京")
        assertValid(text, ranges)
        assertEquals(listOf("北京"), hits(text, ranges))
    }

    @Test
    fun `同一处重复出现全部命中`() {
        val text = "北京和南京都是城市"
        val ranges = highlightRanges(text, "京")
        assertValid(text, ranges)
        assertEquals(listOf("京", "京"), hits(text, ranges))
    }

    @Test
    fun `找不到就返回空`() {
        assertEquals(emptyList<IntRange>(), highlightRanges("北京适合秋天去", "上海"))
    }

    @Test
    fun `整段就是查询词时是一个区间`() {
        val text = "北京"
        val ranges = highlightRanges(text, "北京")
        assertValid(text, ranges)
        assertEquals(listOf(0..1), ranges)
    }

    // ---------------------------------------------------------------- 大小写

    @Test
    fun `拉丁大小写不敏感`() {
        val text = "RikkaHub 和 rikkahub 和 RIKKAHUB"
        for (query in listOf("rikkahub", "RikkaHub", "RIKKAHUB", "rIkKaHuB")) {
            val ranges = highlightRanges(text, query)
            assertValid(text, ranges)
            assertEquals("查询 $query", listOf("RikkaHub", "rikkahub", "RIKKAHUB"), hits(text, ranges))
        }
    }

    @Test
    fun `高亮区间里的大小写是原文的而不是查询词的`() {
        val text = "用 OkHttp 发请求"
        val ranges = highlightRanges(text, "okhttp")
        assertValid(text, ranges)
        // 标的是正文里那 6 个字符，不是把查询词回填进去
        assertEquals(listOf("OkHttp"), hits(text, ranges))
    }

    // ---------------------------------------------------------------- 多段查询

    @Test
    fun `多段查询每一段各自高亮`() {
        val text = "北京适合秋天去，上海适合春天去"
        val ranges = highlightRanges(text, "北京 上海")
        assertValid(text, ranges)
        assertEquals(listOf("北京", "上海"), hits(text, ranges))
    }

    @Test
    fun `多段查询里定位不到的那段被忽略`() {
        val text = "北京适合秋天去"
        val ranges = highlightRanges(text, "北京 上海")
        assertValid(text, ranges)
        assertEquals(listOf("北京"), hits(text, ranges))
    }

    @Test
    fun `全角空格分隔的多段查询同样有效`() {
        val text = "北京适合秋天去，上海适合春天去"
        val ranges = highlightRanges(text, "北京\u3000上海")
        assertValid(text, ranges)
        assertEquals(listOf("北京", "上海"), hits(text, ranges))
    }

    @Test
    fun `不间断空格分隔的多段查询同样有效`() {
        val text = "北京适合秋天去，上海适合春天去"
        val ranges = highlightRanges(text, "\u00A0北京\u00A0上海\u00A0")
        assertValid(text, ranges)
        assertEquals(listOf("北京", "上海"), hits(text, ranges))
    }

    @Test
    fun `查询词两端多余的空白不影响命中`() {
        val text = "北京适合秋天去"
        val ranges = highlightRanges(text, "  北京  ")
        assertValid(text, ranges)
        assertEquals(listOf("北京"), hits(text, ranges))
    }

    // ---------------------------------------------------------------- 合并

    @Test
    fun `相邻的两处命中被并成一个区间`() {
        val text = "北京"
        // 「北」和「京」各命中一次，紧挨着 —— 并成一个完整的「北京」
        val ranges = highlightRanges(text, "北 京")
        assertValid(text, ranges)
        assertEquals(listOf(0..1), ranges)
        assertEquals(listOf("北京"), hits(text, ranges))
    }

    @Test
    fun `重叠的两处命中被并成一个区间`() {
        val text = "abc"
        // 「ab」占 0..1，「bc」占 1..2，重叠在 b 上
        val ranges = highlightRanges(text, "ab bc")
        assertValid(text, ranges)
        assertEquals(listOf(0..2), ranges)
    }

    @Test
    fun `被包含的短区间不会把长区间截短`() {
        val text = "abcd"
        // 「ab」占 0..1，「abcd」占 0..3。先并入长的、再遇到短的，
        // 终点必须保持 3 而不是被回退成 1
        val ranges = highlightRanges(text, "abcd ab")
        assertValid(text, ranges)
        assertEquals(listOf(0..3), ranges)
    }

    @Test
    fun `合并之后中间仍有间隔的两处保持分开`() {
        val text = "北京和上海"
        // 中间隔着「和」，隔了 1 个字符就必须是两个标记
        val ranges = highlightRanges(text, "北京 上海")
        assertValid(text, ranges)
        assertEquals(listOf("北京", "上海"), hits(text, ranges))
    }

    @Test
    fun `自我重叠的多次出现全部标出`() {
        val text = "aaaaa"
        // `aa` 在 0、1、2、3 各出现一次，叠在一起 → 五个字符全被标上。
        // 如果实现是「找到一处就跳过 term.length 个字符」，最后一次会被漏掉，
        // 末尾那个 a 不亮 —— 表现为「同一段文字里有的标了有的没标」
        val ranges = highlightRanges(text, "aa")
        assertValid(text, ranges)
        assertEquals(listOf(0..4), ranges)
    }

    @Test
    fun `重叠出现跨越中间字符时也全部标出`() {
        val text = "ababa"
        // `aba` 出现在 0 和 2，两个区间在中间那个 a 上重叠
        val ranges = highlightRanges(text, "aba")
        assertValid(text, ranges)
        assertEquals(listOf(0..4), ranges)
    }

    // ---------------------------------------------------------------- 不是正则

    @Test
    fun `查询词里的正则元字符按字面处理`() {
        val text = "a.b*c(d)"
        val ranges = highlightRanges(text, ".")
        assertValid(text, ranges)
        // 只有那个真正的点，不是「任意字符」
        assertEquals(listOf("."), hits(text, ranges))

        val star = highlightRanges(text, "*")
        assertValid(text, star)
        assertEquals(listOf("*"), hits(text, star))
    }

    @Test
    fun `查询词整体作为字面串匹配`() {
        val text = "价格是 a.b 元"
        val ranges = highlightRanges(text, "a.b")
        assertValid(text, ranges)
        assertEquals(listOf("a.b"), hits(text, ranges))
    }

    // ---------------------------------------------------------------- 真实形态

    @Test
    fun `中文长句里的命中位置正确`() {
        val text = "帮我把上次会议的纪要整理成表格，会议上定的三件事分别是……"
        val ranges = highlightRanges(text, "会议")
        assertValid(text, ranges)
        assertEquals(listOf("会议", "会议"), hits(text, ranges))
        // 再确认一下下标真的指对了地方（「帮我把上次」占 0..4）
        assertEquals(5, ranges[0].first)
    }

    @Test
    fun `中英混排的查询`() {
        val text = "用 RikkaHub 对比 Operit AI"
        val ranges = highlightRanges(text, "RikkaHub Operit")
        assertValid(text, ranges)
        assertEquals(listOf("RikkaHub", "Operit"), hits(text, ranges))
    }

    @Test
    fun `命中在末尾时区间不越界`() {
        val text = "结尾是北京"
        val ranges = highlightRanges(text, "北京")
        assertValid(text, ranges)
        assertEquals(3..4, ranges.single())
    }

    @Test
    fun `只有换行的文本不高亮任何东西`() {
        assertEquals(emptyList<IntRange>(), highlightRanges("\n\n", "北京"))
    }

    @Test
    fun `查询词比正文还长时不命中`() {
        val text = "北京"
        assertEquals(emptyList<IntRange>(), highlightRanges(text, "北京适合秋天去"))
    }

    // ---------------------------------------------------------------- 与 textWindow 共用切词

    // ---------------------------------------------------------------- 切段

    /**
     * 切段的四条不变量。
     *
     * 这一层存在的理由就是让这些能被测：渲染层自己按下标 `substring` 的话，
     * 游标推进写错会**静默丢字或重字** —— 界面上只是某个字不对劲，
     * 没人会想到是高亮干的。
     */
    private fun assertSegmentsValid(text: String, query: String, segments: List<HighlightSegment>) {
        assertEquals(
            "所有片段拼回去必须逐字符等于原文",
            text,
            segments.joinToString("") { it.text },
        )

        // 标了记的片段拼起来，必须正好是 highlightRanges 给出的那些区间
        val marked = segments.filter { it.highlighted }.joinToString("") { it.text }
        val expected = highlightRanges(text, query)
            .joinToString("") { text.substring(it.first, it.last + 1) }
        assertEquals("标了记的内容和区间算出来的不一致", expected, marked)

        segments.forEach { assertTrue("不该产出空片段：${segments.map { it.text }}", it.text.isNotEmpty()) }
        segments.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "相邻片段的标记状态相同（${a.text} / ${b.text}），说明该切开的地方没切",
                a.highlighted != b.highlighted,
            )
        }
    }

    private fun segmentsOf(text: String, query: String): List<HighlightSegment> =
        highlightSegments(text, query).also { assertSegmentsValid(text, query, it) }

    @Test
    fun `空文本切出空列表`() {
        assertEquals(emptyList<HighlightSegment>(), highlightSegments("", "北京"))
    }

    @Test
    fun `没有命中时是一整段不带标记`() {
        val text = "北京适合秋天去"
        assertEquals(listOf(HighlightSegment(text, highlighted = false)), segmentsOf(text, "上海"))
    }

    @Test
    fun `空查询时是一整段不带标记`() {
        val text = "北京适合秋天去"
        for (query in listOf("", "   ", "\u3000")) {
            assertEquals(
                "空白查询 ${query.map { it.code }} 不该标任何东西",
                listOf(HighlightSegment(text, highlighted = false)),
                segmentsOf(text, query),
            )
        }
    }

    @Test
    fun `命中在中间时切出三段`() {
        val text = "我爱北京天安门"
        val segments = segmentsOf(text, "北京")
        assertEquals(
            listOf(
                HighlightSegment("我爱", highlighted = false),
                HighlightSegment("北京", highlighted = true),
                HighlightSegment("天安门", highlighted = false),
            ),
            segments,
        )
    }

    /** 命中贴着开头：不该产出一个空的前导片段。 */
    @Test
    fun `命中在开头时只有两段`() {
        val text = "北京天安门"
        assertEquals(
            listOf(
                HighlightSegment("北京", highlighted = true),
                HighlightSegment("天安门", highlighted = false),
            ),
            segmentsOf(text, "北京"),
        )
    }

    /** 命中贴着结尾：尾部那一段不该被追加出来。 */
    @Test
    fun `命中在结尾时只有两段`() {
        val text = "我爱北京"
        assertEquals(
            listOf(
                HighlightSegment("我爱", highlighted = false),
                HighlightSegment("北京", highlighted = true),
            ),
            segmentsOf(text, "北京"),
        )
    }

    @Test
    fun `整段都是命中时只有一段`() {
        assertEquals(
            listOf(HighlightSegment("北京", highlighted = true)),
            segmentsOf("北京", "北京"),
        )
    }

    @Test
    fun `多处命中切成交替的片段`() {
        val text = "北京和上海"
        assertEquals(
            listOf(
                HighlightSegment("北京", highlighted = true),
                HighlightSegment("和", highlighted = false),
                HighlightSegment("上海", highlighted = true),
            ),
            segmentsOf(text, "北京 上海"),
        )
    }

    @Test
    fun `合并后的区间在切段里表现为一个整体`() {
        val text = "北京"
        // 「北」和「京」各命中一次，合并成一个区间 ——
        // 切成两个相邻的标记片段是多余的，还会在中间留一条接缝
        assertEquals(
            listOf(HighlightSegment("北京", highlighted = true)),
            segmentsOf(text, "北 京"),
        )
    }

    @Test
    fun `单字命中分散出现时片段数正确`() {
        val text = "a京b京c"
        assertEquals(
            listOf(
                HighlightSegment("a", highlighted = false),
                HighlightSegment("京", highlighted = true),
                HighlightSegment("b", highlighted = false),
                HighlightSegment("京", highlighted = true),
                HighlightSegment("c", highlighted = false),
            ),
            segmentsOf(text, "京"),
        )
    }

    @Test
    fun `标了记的片段按正文顺序拼起来就是那些命中词`() {
        val text = "先聊上海，再聊北京"
        val marked = segmentsOf(text, "北京 上海")
            .filter { it.highlighted }
            .joinToString("") { it.text }
        // 顺序是**正文里的先后**，不是查询词的书写顺序：
        // 「上海」在正文里靠前，就先拼到
        assertEquals("上海北京", marked)
    }

    @Test
    fun `和 textWindow 对同一个查询给出同一个锚点`() {
        // 两边都用 Highlight.kt 里的 queryTerms / firstHit。
        // 不共用的话会出现「卡片上标的是这个词、跳进去标的是另一个」。
        val content = "先聊上海，再聊北京"
        val query = "北京 上海"

        val w = textWindow(content, query, maxChars = 100)
        val anchor = w.hitRange
        assertTrue("开窗应该命中「北京」", anchor != null)
        assertEquals("北京", w.text.substring(anchor!!.first, anchor.last + 1))

        // 卡片上标的是「北京」（按查询词书写顺序取第一个能命中的），
        // 会话里的高亮则把「上海」也标上 —— 两者是包含关系，不是矛盾
        val ranges = highlightRanges(content, query)
        assertValid(content, ranges)
        assertEquals(listOf("上海", "北京"), hits(content, ranges))
    }
}
