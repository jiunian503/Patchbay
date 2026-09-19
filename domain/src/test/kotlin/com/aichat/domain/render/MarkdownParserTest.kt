package com.aichat.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 Markdown 解析器的行为测试。
 *
 * 这里守的不是「符合 CommonMark」——实现有意只覆盖聊天场景需要的那部分，
 * 还刻意偏离了两处（见 [BuiltinMarkdownParser] 的类注释）。
 * 守的是**我们想要的行为**，所以每个偏离点都有专门的用例钉住。
 */
class MarkdownParserTest {

    private fun parse(md: String) = MarkdownParser.Default.parse(md)

    private fun t(s: String) = InlineSpan.Text(s)
    private fun code(s: String) = InlineSpan.Code(s)
    private fun strong(vararg s: InlineSpan) = InlineSpan.Strong(s.toList())
    private fun em(vararg s: InlineSpan) = InlineSpan.Emphasis(s.toList())
    private fun strike(vararg s: InlineSpan) = InlineSpan.Strikethrough(s.toList())
    private fun link(href: String, vararg label: InlineSpan) = InlineSpan.Link(label.toList(), href)
    private val br = InlineSpan.LineBreak

    private fun p(vararg s: InlineSpan) = MarkdownBlock.Paragraph(s.toList())
    private fun h(level: Int, vararg s: InlineSpan) = MarkdownBlock.Heading(level, s.toList())
    private fun bullets(tight: Boolean, vararg items: List<MarkdownBlock>) =
        MarkdownBlock.BulletList(items.toList(), tight)

    private fun ordered(start: Int, tight: Boolean, vararg items: List<MarkdownBlock>) =
        MarkdownBlock.OrderedList(start, items.toList(), tight)

    // ================================================================ 段落

    @Test
    fun `空行分隔的段落`() {
        assertEquals(listOf(p(t("甲")), p(t("乙"))), parse("甲\n\n乙"))
    }

    /**
     * 有意偏离 CommonMark：段落内的单个换行渲染成换行，不折叠成空格。
     *
     * CommonMark 是文档排版规则（换行只是折行），聊天不是 ——
     * 模型经常用单换行写要点，折叠成空格会糊成一团。
     */
    @Test
    fun `段落内的单个换行是换行而不是空格`() {
        assertEquals(listOf(p(t("第一行"), br, t("第二行"))), parse("第一行\n第二行"))
    }

    @Test
    fun `空输入没有块`() {
        assertEquals(emptyList<MarkdownBlock>(), parse(""))
        assertEquals(emptyList<MarkdownBlock>(), parse("\n\n\n"))
    }

    @Test
    fun `首尾多余的空白行被忽略`() {
        assertEquals(listOf(p(t("甲"))), parse("\n\n甲\n\n"))
    }

    // ================================================================ 标题

    @Test
    fun `各级 ATX 标题`() {
        assertEquals(listOf(h(1, t("一级"))), parse("# 一级"))
        assertEquals(listOf(h(3, t("三级"))), parse("### 三级"))
        assertEquals(listOf(h(6, t("六级"))), parse("###### 六级"))
    }

    @Test
    fun `标题结尾的井号串被去掉`() {
        assertEquals(listOf(h(2, t("二级"))), parse("## 二级 ##"))
    }

    /** 不放宽 `#` 后必须有空格：`#1` 更可能是「第 1 项」而不是标题。 */
    @Test
    fun `井号后没有空格不是标题`() {
        assertEquals(listOf(p(t("#标题"))), parse("#标题"))
    }

    @Test
    fun `七个井号不是标题`() {
        assertEquals(listOf(p(t("####### 太多"))), parse("####### 太多"))
    }

    @Test
    fun `setext 标题`() {
        assertEquals(listOf(h(1, t("标题"))), parse("标题\n==="))
        assertEquals(listOf(h(2, t("标题"))), parse("标题\n---"))
    }

    /**
     * setext 与分隔线的分界就是「前面有没有段落行」。
     * 这条最容易写错，两边都钉住。
     */
    @Test
    fun `减号是 setext 还是分隔线取决于前面有没有段落行`() {
        assertEquals(listOf(h(2, t("标题"))), parse("标题\n---"))
        assertEquals(listOf(p(t("标题")), MarkdownBlock.ThematicBreak), parse("标题\n\n---"))
    }

    /**
     * 有意偏离：单个或两个减号不当 setext。
     *
     * `-` 更可能是列表标记，冲突时优先按列表解释；
     * 而且模型写 setext 时用的就是 `---`。
     */
    @Test
    fun `单个减号不当 setext`() {
        val blocks = parse("标题\n-")
        assertEquals(2, blocks.size)
        assertEquals(p(t("标题")), blocks[0])
        assertTrue("第二个块应该是列表，实际是 ${blocks[1]}", blocks[1] is MarkdownBlock.BulletList)
    }

    // ================================================================ 围栏代码块

    @Test
    fun `围栏代码块带语言标签`() {
        assertEquals(
            listOf(MarkdownBlock.CodeBlock("kotlin", "val a = 1")),
            parse("```kotlin\nval a = 1\n```"),
        )
    }

    @Test
    fun `没有语言标签的围栏代码块`() {
        assertEquals(
            listOf(MarkdownBlock.CodeBlock(null, "plain")),
            parse("```\nplain\n```"),
        )
    }

    @Test
    fun `波浪号围栏`() {
        assertEquals(
            listOf(MarkdownBlock.CodeBlock("js", "const a = 1;")),
            parse("~~~js\nconst a = 1;\n~~~"),
        )
    }

    @Test
    fun `代码块内部的空行与缩进原样保留`() {
        val md = "```\nline1\n\n    indented\n```"
        assertEquals(
            listOf(MarkdownBlock.CodeBlock(null, "line1\n\n    indented")),
            parse(md),
        )
    }

    /** 代码里的标记不该被解析成格式 —— 否则复制出来的代码是错的。 */
    @Test
    fun `代码块内部不做行内解析`() {
        val block = parse("```\n**不是粗体** `也不是代码`\n```").single()
        assertEquals(MarkdownBlock.CodeBlock(null, "**不是粗体** `也不是代码`"), block)
    }

    @Test
    fun `未闭合的围栏一直到结尾`() {
        assertEquals(
            listOf(MarkdownBlock.CodeBlock("js", "val a = 1\nval b = 2")),
            parse("```js\nval a = 1\nval b = 2"),
        )
    }

    @Test
    fun `更长的闭围栏可以闭合更短的开围栏`() {
        assertEquals(
            listOf(MarkdownBlock.CodeBlock(null, "x")),
            parse("```\nx\n`````"),
        )
    }

    /** 反过来不行：短的闭不上长的。 */
    @Test
    fun `更短的闭围栏不能闭合更长的开围栏`() {
        val block = parse("````\nx\n```\ny\n````").single()
        assertEquals(MarkdownBlock.CodeBlock(null, "x\n```\ny"), block)
    }

    @Test
    fun `围栏后面跟正文`() {
        assertEquals(
            listOf(MarkdownBlock.CodeBlock(null, "x"), p(t("后文"))),
            parse("```\nx\n```\n\n后文"),
        )
    }

    // ================================================================ 引用块

    @Test
    fun `引用块`() {
        assertEquals(
            listOf(MarkdownBlock.Quote(listOf(p(t("甲"))))),
            parse("> 甲"),
        )
    }

    /** `>` 结尾的空行是引用块**内部**的空行，应当分成两段而不是结束引用。 */
    @Test
    fun `引用块内的空行分成两段`() {
        assertEquals(
            listOf(MarkdownBlock.Quote(listOf(p(t("甲")), p(t("乙"))))),
            parse("> 甲\n>\n> 乙"),
        )
    }

    @Test
    fun `嵌套引用块`() {
        assertEquals(
            listOf(MarkdownBlock.Quote(listOf(MarkdownBlock.Quote(listOf(p(t("甲"))))))),
            parse("> > 甲"),
        )
    }

    @Test
    fun `引用块里的代码块`() {
        assertEquals(
            listOf(MarkdownBlock.Quote(listOf(MarkdownBlock.CodeBlock("js", "x")))),
            parse("> ```js\n> x\n> ```"),
        )
    }

    /** 懒续行：引用块里没写 `>` 的续行也算引用。 */
    @Test
    fun `引用块的懒续行`() {
        assertEquals(
            listOf(MarkdownBlock.Quote(listOf(p(t("甲"), br, t("乙"))))),
            parse("> 甲\n乙"),
        )
    }

    /** 但懒续行不能吞掉一个新块的开始，否则 `# 标题` 会被吸进引用里。 */
    @Test
    fun `懒续行不会吞掉标题`() {
        assertEquals(
            listOf(MarkdownBlock.Quote(listOf(p(t("甲")))), h(1, t("标题"))),
            parse("> 甲\n# 标题"),
        )
    }

    // ================================================================ 列表

    @Test
    fun `紧凑无序列表`() {
        assertEquals(
            listOf(bullets(true, listOf(p(t("甲"))), listOf(p(t("乙"))))),
            parse("- 甲\n- 乙"),
        )
    }

    @Test
    fun `三种无序标记都是列表`() {
        for (marker in listOf("-", "*", "+")) {
            assertEquals(
                "标记 $marker",
                listOf(bullets(true, listOf(p(t("甲"))))),
                parse("$marker 甲"),
            )
        }
    }

    /** 松散列表（项之间有空行）与紧凑列表要能区分 —— 界面靠它决定项间距。 */
    @Test
    fun `松散无序列表`() {
        assertEquals(
            listOf(bullets(false, listOf(p(t("甲"))), listOf(p(t("乙"))))),
            parse("- 甲\n\n- 乙"),
        )
    }

    @Test
    fun `有序列表`() {
        assertEquals(
            listOf(ordered(1, true, listOf(p(t("甲"))), listOf(p(t("乙"))))),
            parse("1. 甲\n2. 乙"),
        )
    }

    /** 模型可能从 3 开始写，序号要尊重。 */
    @Test
    fun `有序列表的起始序号被保留`() {
        val list = parse("3. 丙\n4. 丁").single() as MarkdownBlock.OrderedList
        assertEquals(3, list.start)
    }

    @Test
    fun `右括号也是有序标记`() {
        val list = parse("1) 甲").single() as MarkdownBlock.OrderedList
        assertEquals(1, list.start)
    }

    @Test
    fun `嵌套列表`() {
        assertEquals(
            listOf(
                bullets(
                    true,
                    listOf(p(t("甲")), bullets(true, listOf(p(t("甲一"))), listOf(p(t("甲二"))))),
                    listOf(p(t("乙"))),
                )
            ),
            parse("- 甲\n  - 甲一\n  - 甲二\n- 乙"),
        )
    }

    @Test
    fun `松散列表项里的续段`() {
        assertEquals(
            listOf(
                ordered(
                    1, false,
                    listOf(p(t("甲")), p(t("说明段"))),
                    listOf(p(t("乙"))),
                )
            ),
            parse("1. 甲\n\n   说明段\n\n2. 乙"),
        )
    }

    @Test
    fun `列表后的段落不属于列表`() {
        assertEquals(
            listOf(
                bullets(true, listOf(p(t("甲")))),
                p(t("正文")),
            ),
            parse("- 甲\n\n正文"),
        )
    }

    @Test
    fun `有序与无序列表相邻时是两个列表`() {
        val blocks = parse("- 甲\n\n1. 乙")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.BulletList)
        assertTrue(blocks[1] is MarkdownBlock.OrderedList)
    }

    @Test
    fun `列表项里的代码块`() {
        // 项里出现空行 → 松散列表（tight=false），界面据此把项间距拉开
        assertEquals(
            listOf(bullets(false, listOf(p(t("甲")), MarkdownBlock.CodeBlock(null, "x")))),
            parse("- 甲\n\n  ```\n  x\n  ```"),
        )
    }

    /** `**粗体**` 不能因为以 `*` 开头就被当成列表项。 */
    @Test
    fun `星号开头的粗体不是列表项`() {
        assertEquals(listOf(p(strong(t("粗体")))), parse("**粗体**"))
    }

    /** `* * *` 是分隔线，不是三项空列表。 */
    @Test
    fun `星号分隔线不是列表`() {
        assertEquals(listOf(MarkdownBlock.ThematicBreak), parse("* * *"))
    }

    // ================================================================ 表格

    @Test
    fun `表格`() {
        assertEquals(
            listOf(
                MarkdownBlock.Table(
                    headers = listOf(listOf(t("参数")), listOf(t("默认值"))),
                    aligns = listOf(ColumnAlign.None, ColumnAlign.None),
                    rows = listOf(listOf(listOf(t("temperature")), listOf(t("0.7")))),
                )
            ),
            parse("| 参数 | 默认值 |\n| --- | --- |\n| temperature | 0.7 |"),
        )
    }

    @Test
    fun `表格对齐`() {
        val table = parse("| a | b | c |\n| :-- | :-: | --: |\n| 1 | 2 | 3 |")
            .single() as MarkdownBlock.Table
        assertEquals(
            listOf(ColumnAlign.Left, ColumnAlign.Center, ColumnAlign.Right),
            table.aligns,
        )
    }

    @Test
    fun `没有首尾竖线的表格`() {
        val table = parse("a | b\n--- | ---\n1 | 2").single() as MarkdownBlock.Table
        assertEquals(2, table.headers.size)
        assertEquals(1, table.rows.size)
    }

    /** 列数对不上时更可能是「正文里恰好有个竖线」，不能误判成表格。 */
    @Test
    fun `列数不匹配就不是表格`() {
        val blocks = parse("| a | b |\n| --- |\n| 1 |")
        assertEquals(1, blocks.size)
        assertTrue("不该解析成表格，实际是 ${blocks[0]}", blocks[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `单元格里的竖线可以用反斜杠转义`() {
        val table = parse("| a | b |\n| --- | --- |\n| x \\| y | 2 |").single() as MarkdownBlock.Table
        assertEquals(listOf(t("x | y")), table.rows[0][0])
    }

    @Test
    fun `表格单元格里的行内格式`() {
        val table = parse("| a |\n| --- |\n| **粗** |").single() as MarkdownBlock.Table
        assertEquals(listOf(strong(t("粗"))), table.rows[0][0])
    }

    @Test
    fun `缺列的行按空单元格补齐`() {
        val table = parse("| a | b |\n| --- | --- |\n| 1 |").single() as MarkdownBlock.Table
        // 补齐出来的是**空 span 列表**而不是 `Text("")`：
        // 渲染时两者都没内容，但空列表不会在测量时多出一个零宽文本节点
        assertEquals(listOf(listOf(t("1")), emptyList()), table.rows[0])
    }

    // ================================================================ 分隔线

    @Test
    fun `各种分隔线`() {
        for (md in listOf("---", "***", "___", "- - -", "*****")) {
            assertEquals("输入 $md", listOf(MarkdownBlock.ThematicBreak), parse(md))
        }
    }

    @Test
    fun `不足三个字符不是分隔线`() {
        assertEquals(listOf(p(t("--"))), parse("--"))
    }

    // ================================================================ 行内格式

    @Test
    fun `粗体与斜体`() {
        assertEquals(listOf(p(strong(t("粗")))), parse("**粗**"))
        assertEquals(listOf(p(strong(t("粗")))), parse("__粗__"))
        assertEquals(listOf(p(em(t("斜")))), parse("*斜*"))
        assertEquals(listOf(p(em(t("斜")))), parse("_斜_"))
    }

    @Test
    fun `删除线`() {
        assertEquals(listOf(p(strike(t("删")))), parse("~~删~~"))
    }

    @Test
    fun `嵌套：粗体里的行内代码`() {
        assertEquals(
            listOf(p(strong(t("重点 "), code("x = 1")))),
            parse("**重点 `x = 1`**"),
        )
    }

    @Test
    fun `行内代码`() {
        assertEquals(listOf(p(code("x = 1"))), parse("`x = 1`"))
    }

    /** CommonMark：首尾各一个空格时各去掉一个，这样才写得出「内容就是反引号」。 */
    @Test
    fun `行内代码用双反引号包住单个反引号`() {
        assertEquals(listOf(p(code("`"))), parse("`` ` ``"))
    }

    @Test
    fun `行内代码内部不做行内解析`() {
        assertEquals(listOf(p(code("**不是粗体**"))), parse("`**不是粗体**`"))
    }

    @Test
    fun `未闭合的定界符退化成纯文本`() {
        assertEquals(listOf(p(t("**没闭合"))), parse("**没闭合"))
        assertEquals(listOf(p(t("`没闭合"))), parse("`没闭合"))
        assertEquals(listOf(p(t("~~没闭合"))), parse("~~没闭合"))
    }

    /**
     * 定界符后面紧跟空白就不能开启强调。
     *
     * 用例必须放在**行内**：`* 这不是斜体 *` 单独成行时，
     * `* ` 本来就是一个合法的列表标记，那是列表不是斜体 ——
     * 拿它当用例会测出「列表」这个正确结果，然后误判成解析器错了。
     */
    @Test
    fun `定界符后面紧跟空白不能开启`() {
        assertEquals(
            listOf(p(t("甲 * 这不是斜体 * 乙"))),
            parse("甲 * 这不是斜体 * 乙"),
        )
    }

    /** 词内下划线不算强调，否则 `snake_case_name` 会被吃掉两个下划线。 */
    @Test
    fun `词内下划线不产生斜体`() {
        assertEquals(listOf(p(t("snake_case_name"))), parse("snake_case_name"))
    }

    @Test
    fun `乘法星号不产生斜体`() {
        assertEquals(listOf(p(t("2 * 3 = 6"))), parse("2 * 3 = 6"))
    }

    @Test
    fun `未闭合的代码围栏不影响行内反引号`() {
        // 行内的 ` 与围栏是两回事
        assertEquals(listOf(p(code("x"))), parse("`x`"))
    }

    // ================================================================ 链接

    @Test
    fun `行内链接`() {
        assertEquals(
            listOf(p(t("见 "), link("https://a.com", t("文档")))),
            parse("见 [文档](https://a.com)"),
        )
    }

    @Test
    fun `链接带 title`() {
        assertEquals(
            listOf(p(InlineSpan.Link(listOf(t("文档")), "https://a.com", "标题"))),
            parse("""[文档](https://a.com "标题")"""),
        )
        // 单引号和圆括号包裹的 title 也要认
        assertEquals(
            InlineSpan.Link(listOf(t("文档")), "https://a.com", "标题"),
            (parse("[文档](https://a.com '标题')").single() as MarkdownBlock.Paragraph).spans.single(),
        )
    }

    @Test
    fun `链接文字可以带格式`() {
        assertEquals(
            listOf(p(link("https://a.com", strong(t("粗"))))),
            parse("[**粗**](https://a.com)"),
        )
    }

    @Test
    fun `尖括号自动链接`() {
        assertEquals(
            listOf(p(link("https://a.com", t("https://a.com")))),
            parse("<https://a.com>"),
        )
    }

    @Test
    fun `邮箱自动链接`() {
        assertEquals(
            listOf(p(link("mailto:a@b.com", t("a@b.com")))),
            parse("<a@b.com>"),
        )
    }

    @Test
    fun `裸 URL 被识别成链接`() {
        assertEquals(
            listOf(p(t("见 "), link("https://a.com/b", t("https://a.com/b")))),
            parse("见 https://a.com/b"),
        )
    }

    /** 句末标点是句子的，不属于 URL —— 否则点开链接会 404。 */
    @Test
    fun `裸 URL 尾部的中文标点被剥掉`() {
        assertEquals(
            listOf(p(link("https://a.com", t("https://a.com")), t("。"))),
            parse("https://a.com。"),
        )
        assertEquals(
            listOf(p(link("https://a.com", t("https://a.com")), t("，然后"))),
            parse("https://a.com，然后"),
        )
    }

    /**
     * 右括号要配平。
     *
     * `(见 https://a.com/b)` 里的 `)` 是句子的；
     * `https://en.wikipedia.org/wiki/Foo_(bar)` 里的属于 URL。
     */
    @Test
    fun `裸 URL 的右括号配平`() {
        assertEquals(
            listOf(p(t("(见 "), link("https://a.com/b", t("https://a.com/b")), t(")"))),
            parse("(见 https://a.com/b)"),
        )
        assertEquals(
            listOf(p(link("https://a.com/b_(c)", t("https://a.com/b_(c)")))),
            parse("https://a.com/b_(c)"),
        )
    }

    /** 链接文字里不再做自动链接，否则会套出嵌套链接。 */
    @Test
    fun `链接文字里的 URL 不会变成嵌套链接`() {
        val span = (parse("[https://a.com](https://a.com)").single() as MarkdownBlock.Paragraph).spans.single()
        assertEquals(InlineSpan.Link(listOf(t("https://a.com")), "https://a.com"), span)
    }

    /**
     * 未闭合的链接 —— 流式过程中必然会经过这个中间状态（`)` 还没吐出来）。
     *
     * 方括号按字面显示，URL 仍然被自动链接成可点。
     * 这比「整段都不可点」要好：用户至少能点开，
     * 而 `)` 一到就自动变回正常链接。
     */
    @Test
    fun `未闭合的链接退化成文字加自动链接`() {
        assertEquals(
            listOf(p(t("[文档]("), link("https://a.com", t("https://a.com")))),
            parse("[文档](https://a.com"),
        )
    }

    /** 图片不做网络加载，退化成带 alt 文字的链接。 */
    @Test
    fun `图片退化成链接`() {
        assertEquals(
            listOf(p(t("!"), link("https://a.com/b.png", t("图")))),
            parse("![图](https://a.com/b.png)"),
        )
    }

    // ================================================================ 转义

    @Test
    fun `反斜杠转义标点`() {
        assertEquals(listOf(p(t("*不是斜体*"))), parse("\\*不是斜体\\*"))
        assertEquals(listOf(p(t("[不是链接]"))), parse("\\[不是链接\\]"))
    }

    /**
     * 只转义 ASCII 标点。
     *
     * 否则模型输出里的 Windows 路径（`C:\用户`）会掉字符 ——
     * 用户看到的路径是错的，而且很难联想到是转义规则的问题。
     */
    @Test
    fun `反斜杠后跟非标点字符时原样保留`() {
        assertEquals(listOf(p(t("C:\\用户"))), parse("C:\\用户"))
    }

    @Test
    fun `行尾反斜杠是硬换行`() {
        assertEquals(listOf(p(t("甲"), br, t("乙"))), parse("甲\\\n乙"))
    }

    // ================================================================ 链接协议白名单

    /**
     * 不在白名单里的协议不能变成可点链接。
     *
     * `javascript:` 是唯一真正危险的一个（模型被提示注入时会写），
     * 其余属于「点了没反应」的体验问题 —— 显示成蓝字但点不动，
     * 比直接显示成普通文字更让人困惑。
     */
    @Test
    fun `不安全的协议退化成普通文字`() {
        assertEquals(
            listOf(p(t("[点我](javascript:alert(1))"))),
            parse("[点我](javascript:alert(1))"),
        )
        assertEquals(
            listOf(p(t("[看文件](file:///etc/hosts)"))),
            parse("[看文件](file:///etc/hosts)"),
        )
    }

    @Test
    fun `相对路径也退化成普通文字`() {
        assertEquals(listOf(p(t("[文档](./a.md)"))), parse("[文档](./a.md)"))
    }

    @Test
    fun `白名单里的协议正常成为链接`() {
        assertEquals(
            listOf(p(link("mailto:a@b.com", t("写信")))),
            parse("[写信](mailto:a@b.com)"),
        )
        assertEquals(
            listOf(p(link("tel:10086", t("打电话")))),
            parse("[打电话](tel:10086)"),
        )
        // 协议大小写不敏感
        assertEquals(
            listOf(p(link("HTTPS://a.com", t("站点")))),
            parse("[站点](HTTPS://a.com)"),
        )
    }

    // ================================================================ 原始 HTML

    /**
     * 原始 HTML 一律当普通文字。
     *
     * 这是**安全决定**：模型输出属于不完全可信的输入，
     * 我们没有理由为了支持 HTML 去引入一个注入面。
     */
    @Test
    fun `原始 HTML 当普通文字`() {
        assertEquals(
            listOf(p(t("<script>alert(1)</script>"))),
            parse("<script>alert(1)</script>"),
        )
    }

    // ================================================================ 综合

    @Test
    fun `一段真实的模型输出`() {
        val md = """
            好的，我先看一下。

            ## 核心逻辑

            ```kotlin
            fun stream() = 1
            ```

            | 参数 | 默认值 |
            | --- | --- |
            | temperature | 0.7 |

            - 第一点
            - 第二点

            > 注意：这只是示例。

            参考 [官方文档](https://example.com)。
        """.trimIndent()

        val blocks = parse(md)
        assertEquals(7, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertEquals(h(2, t("核心逻辑")), blocks[1])
        assertEquals(MarkdownBlock.CodeBlock("kotlin", "fun stream() = 1"), blocks[2])
        assertTrue(blocks[3] is MarkdownBlock.Table)
        assertTrue(blocks[4] is MarkdownBlock.BulletList)
        assertTrue(blocks[5] is MarkdownBlock.Quote)
        assertTrue(blocks[6] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `取纯文本`() {
        val spans = parse("**粗** 与 `代码` 与 [链接](https://a.com)").single()
            .let { (it as MarkdownBlock.Paragraph).spans }
        assertEquals("粗 与 代码 与 链接", spans.plainText())
    }
}
