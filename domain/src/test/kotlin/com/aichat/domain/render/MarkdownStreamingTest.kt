package com.aichat.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 块缓存的一致性测试。
 *
 * ## 这个测试守的是什么
 *
 * [StreamingMarkdownRenderer] 会把文档按「稳定块边界」切开，边界之前解析一次就缓存，
 * 之后每帧只重解析尾部。这个优化成立的前提是一条性质：
 *
 * > 在稳定边界处切开，`parse(前半) + parse(后半) == parse(整篇)`
 *
 * 如果前提不成立，用户会看到「流式过程中内容是对的，一旦越过某个边界就变了」
 * —— 而且只在特定内容上出现，极难复现。
 *
 * ## 用例来源
 *
 * 13 个用例是从 `spike/renderer_check.js` 逐字移植的。那份 spike 用 marked
 * 做代理验证，报告里明确写了「换库后请重跑全部脚本」—— 现在换成了自研解析器，
 * 所以这里的每一条都必须重新过一遍，**不能假定原来的结论还成立**。
 *
 * ## 为什么比较「块」而不是「HTML」
 *
 * spike 比较的是 HTML 字符串，只能告诉你「两个长串不一样」。
 * 块模型是 data class，`assertEquals` 失败时 diff 会直接指出是哪一块不同。
 */
class MarkdownStreamingTest {

    private val parser = MarkdownParser.Default

    private fun whole(doc: String) = parser.parse(doc)

    /** 一次性喂完整篇（只会在边界处封一次块）。 */
    private fun renderOnce(doc: String): List<MarkdownBlock> =
        StreamingMarkdownRenderer(parser).render(doc)

    /**
     * 逐字符喂进去，模拟真实的流式增量。
     *
     * 比一次性喂完更强：它会在**每一个前缀**上重新算边界，
     * 于是所有可能的封块时机都被走了一遍。
     */
    private fun renderIncrementally(doc: String): List<MarkdownBlock> {
        val renderer = StreamingMarkdownRenderer(parser)
        var blocks: List<MarkdownBlock> = emptyList()
        for (n in 1..doc.length) {
            blocks = renderer.render(doc.substring(0, n))
        }
        return blocks
    }

    // ================================================================ 13 个用例

    private val cases: Map<String, String> = mapOf(
        "A 纯段落" to "第一段。\n\n第二段。\n\n第三段。\n",
        "B 列表项之间有空行" to "- 第一项\n\n- 第二项\n\n- 第三项\n",
        "C 引用块跨空行" to "> 第一行引用\n\n> 第二行引用\n",
        "D 段落 + 分隔线" to "这是一个标题\n\n---\n\n正文。\n",
        "E 有序列表 + 松散项" to "1. 甲\n\n2. 乙\n\n3. 丙\n",
        "F 围栏内含空行" to "前文\n\n```js\nconst a = 1;\n\nconst b = 2;\n```\n\n后文\n",
        "G 嵌套列表" to "- 甲\n  - 甲一\n  - 甲二\n\n- 乙\n",
        "H 完整流式文档" to listOf(
            "好的，我先看一下这段代码。",
            "",
            "核心逻辑是这样的：",
            "",
            "```kotlin",
            "fun stream(prompt: String) {",
            "    val call = provider.chat(prompt)",
            "}",
            "```",
            "",
            "参数对照如下：",
            "",
            "| 参数 | 默认值 |",
            "| --- | --- |",
            "| temperature | 0.7 |",
            "",
            "参考 [官方文档](https://example.com/docs) 了解更多。",
        ).joinToString("\n"),
        "I 列表后接段落" to "- 甲\n- 乙\n\n这是一段普通正文。\n\n又一段。\n",
        "J 原始 HTML 块" to "<div>\n\n内容\n\n</div>\n\n正文。\n",
        "K 引用块内用 > 续空行" to "> 第一行\n>\n> 第二行\n\n正文。\n",
        "L 围栏后紧跟列表" to "```js\nconst a = 1;\n```\n\n- 甲\n- 乙\n\n正文。\n",
        "M 有序列表嵌套 + 松散" to "1. 甲\n\n   说明段\n\n2. 乙\n\n3. 丙\n",
    )

    @Test
    fun `用例齐了`() {
        assertEquals(13, cases.size)
    }

    @Test
    fun `分块解析与整篇解析一致`() {
        val failures = mutableListOf<String>()
        for ((name, doc) in cases) {
            val expected = whole(doc)
            val once = renderOnce(doc)
            if (expected != once) {
                failures += "$name\n  整篇: $expected\n  分块: $once"
            }
        }
        assertTrue(
            "以下用例分块解析与整篇解析不一致：\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    @Test
    fun `逐字符流式渲染与整篇解析一致`() {
        val failures = mutableListOf<String>()
        for ((name, doc) in cases) {
            val expected = whole(doc)
            val incremental = renderIncrementally(doc)
            if (expected != incremental) {
                failures += "$name\n  整篇: $expected\n  流式: $incremental"
            }
        }
        assertTrue(
            "以下用例逐字符流式渲染与整篇解析不一致：\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    // ================================================================ 边界规则

    /**
     * 围栏内部的空行**不能**当边界。
     *
     * 否则代码块会被从中间劈开，前后两半各自独立解析 ——
     * 而「未闭合的围栏」在解析器眼里是合法的，不报错，只是内容被拆成两块。
     */
    @Test
    fun `围栏内部的空行不封块`() {
        val doc = "```\na\n\nb\n```"
        val inside = doc.indexOf("\n\n") + 1
        assertTrue(
            "围栏内的空行位置不该是稳定边界，实际边界 ${StreamingMarkdownRenderer.stableBoundary(doc)}",
            StreamingMarkdownRenderer.stableBoundary(doc) != inside,
        )
    }

    /**
     * 列表内部的空行**不能**当边界。
     *
     * CommonMark 里列表项之间可以有空行（松散列表），空行并不结束列表。
     * 若在此封块，`- 甲\n\n- 乙` 会被切成两个独立列表 —— 第一个是紧凑的、
     * 第二个还丢掉段落包裹，和整篇解析的结果完全不同。
     * spike 记录：未加此规则时 13 个用例里有 3 个不一致。
     */
    @Test
    fun `列表内部的空行不封块`() {
        val doc = "- 甲\n\n- 乙"
        val inside = doc.indexOf("\n\n") + 1
        assertTrue(
            "列表内的空行位置不该是稳定边界，实际边界 ${StreamingMarkdownRenderer.stableBoundary(doc)}",
            StreamingMarkdownRenderer.stableBoundary(doc) != inside,
        )
    }

    /** 列表结束后的空行恢复成安全边界。 */
    @Test
    fun `列表结束后的空行是边界`() {
        val doc = "- 甲\n\n正文\n\n又一段\n"
        assertTrue(StreamingMarkdownRenderer.stableBoundary(doc) > 0)
    }

    // ================================================================ 性能

    /**
     * 统计真正送进解析器的字符数。
     *
     * 这才是块缓存要解决的问题：每帧重解析整条消息的总开销是 O(n²)，
     * 一条 5,000 字符的回复 × 5,000 个增量约等于 1,250 万字符的解析量。
     */
    private class CountingParser(private val inner: MarkdownParser = MarkdownParser.Default) : MarkdownParser {
        var chars = 0
            private set
        var calls = 0
            private set

        override fun parse(markdown: String): List<MarkdownBlock> {
            chars += markdown.length
            calls++
            return inner.parse(markdown)
        }
    }

    @Test
    fun `块缓存把解析字符量降到朴素做法的三分之一以下`() {
        val doc = cases.getValue("H 完整流式文档")
        val counter = CountingParser()
        val renderer = StreamingMarkdownRenderer(counter)

        for (n in 1..doc.length) renderer.render(doc.substring(0, n))

        // 朴素做法：每个前缀都解析一遍全文
        val naive = doc.length * (doc.length + 1) / 2
        val ratio = counter.chars.toDouble() / naive

        // 把实测数字打出来 —— 这个比例是块缓存存在的唯一理由，
        // 值得留在测试输出里而不是只写在断言里
        println(
            "[块缓存] 文档 ${doc.length} 字符，逐字符流式 ${doc.length} 帧：" +
                "解析 ${counter.chars} 字符 / ${counter.calls} 次调用，" +
                "朴素做法 $naive 字符，降至 ${String.format(java.util.Locale.US, "%.1f", ratio * 100)}%"
        )

        assertTrue(
            "解析字符量 ${counter.chars} / 朴素 $naive = ${"%.1f".format(ratio * 100)}%，期望低于 33%",
            ratio < 0.33,
        )
    }

    @Test
    fun `块缓存减少了调用次数`() {
        val doc = cases.getValue("H 完整流式文档")
        val counter = CountingParser()
        val renderer = StreamingMarkdownRenderer(counter)
        for (n in 1..doc.length) renderer.render(doc.substring(0, n))

        // 朴素做法是每个前缀一次；块缓存把「已封块的部分」免掉了，
        // 剩下的调用都是尾部重解析
        assertTrue("调用次数 ${counter.calls} 不该超过前缀数 ${doc.length}", counter.calls <= doc.length)
        assertTrue("解析字符量应该非零", counter.chars > 0)
    }

    // ================================================================ 超长尾部

    /**
     * 尾部长度上限：整条回复是一个没有空行的超长段落时，
     * 尾部会退化成整段，块缓存就白做了。此时在最近的句末标点处强制切分。
     *
     * 这个切分**不保证块结构等价**（切点落在段落中间，一个段落会变成两个），
     * 是有意的取舍：换来的是解析量有上界。所以这里只断言
     * 「不丢字符、不爆掉」，不断言块结构。
     */
    @Test
    fun `超长单段落会被强制切分且不丢内容`() {
        val sentence = "这是一句话，用来把段落撑长。"
        val doc = sentence.repeat(300) // 约 4500 字符，远超 2000 的上限

        val renderer = StreamingMarkdownRenderer(parser)
        val blocks = renderer.render(doc)

        assertTrue("应该被切成多个块，实际 ${blocks.size}", blocks.size > 1)
        val text = blocks.filterIsInstance<MarkdownBlock.Paragraph>()
            .joinToString("") { it.spans.plainText() }
        assertEquals("切分不能丢字符", doc, text)
    }

    @Test
    fun `reset 之后重新开始`() {
        val renderer = StreamingMarkdownRenderer(parser)
        renderer.render("甲\n\n乙\n\n")
        val before = renderer.parseCount
        assertTrue(before > 0)

        renderer.reset()
        assertEquals(0, renderer.parseCount)

        // 重置后重新渲染同一段，结果必须和第一次一样
        assertEquals(listOf(whole("甲\n\n乙\n\n")), listOf(renderer.render("甲\n\n乙\n\n")))
    }

    @Test
    fun `空文本返回空`() {
        val renderer = StreamingMarkdownRenderer(parser)
        assertEquals(emptyList<MarkdownBlock>(), renderer.render(""))
    }

    /** 同一实例反复渲染同一段文本，结果必须稳定（幂等）。 */
    @Test
    fun `重复渲染同一段文本结果稳定`() {
        val renderer = StreamingMarkdownRenderer(parser)
        val doc = cases.getValue("H 完整流式文档")
        val first = renderer.render(doc)
        val second = renderer.render(doc)
        val third = renderer.render(doc)
        assertEquals(first, second)
        assertEquals(second, third)
    }
}
