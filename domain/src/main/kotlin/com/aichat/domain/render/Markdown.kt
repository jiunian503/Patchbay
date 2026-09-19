package com.aichat.domain.render

/**
 * Markdown 的**块级**模型。
 *
 * ## 为什么是块模型，不是 HTML 字符串
 *
 * 一开始的设计是「解析成 HTML 字符串，交给 WebView 或 `Html.fromHtml` 显示」。
 * 放弃了，原因有三条：
 *
 * 1. **`Html.fromHtml` 只认一个很小的子集** —— 表格、嵌套列表、代码块的语言标签
 *    全都不支持，而这些恰好是模型最爱输出的东西。
 * 2. **WebView 放进 `LazyColumn` 是出了名的难缠**：高度要异步测量、
 *    嵌套滚动打架、每条消息一个 WebView 内存直接失控。
 * 3. **HTML 是字符串，没法做「分块解析拼接 == 整篇解析」的结构比较。**
 *    块模型用 data class，等价性是结构等价 —— 测试里一个 `assertEquals` 就够，
 *    而且失败时 diff 直接告诉你哪一块不一样。
 *
 * 顺带还拿到两个好处：文字可以选中、链接可以点、代码块可以放复制按钮。
 *
 * ## 关于原始 HTML
 *
 * 模型输出里的 `<div>`、`<script>` 这类标签**一律当普通文字显示**，
 * 不做任何解释。这是有意的安全决定：模型输出属于不完全可信的输入，
 * 而我们没有理由为了支持 HTML 去引入一个注入面。
 *
 * ## 等价性
 *
 * 全部是 data class / data object，所以 `==` 就是结构等价 ——
 * 这正是流式分块解析一致性测试所依赖的。
 */
sealed interface MarkdownBlock {

    /** 段落。 */
    data class Paragraph(val spans: List<InlineSpan>) : MarkdownBlock

    /** ATX 标题（`#` 到 `######`）与 setext 标题（`===` / `---` 下划线）。 */
    data class Heading(val level: Int, val spans: List<InlineSpan>) : MarkdownBlock

    /**
     * 围栏代码块。
     *
     * [code] 保持原样（含内部空行与缩进），**不做行内解析** ——
     * 代码里的 `**` 不该变成粗体。
     *
     * [language] 来自围栏后的 info string，取第一个词。
     */
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock

    /** 引用块。内容可以再套任意块，包括嵌套引用。 */
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    data class BulletList(
        val items: List<List<MarkdownBlock>>,
        /**
         * 紧凑列表：项之间没有空行。
         *
         * 界面据此决定项间距 —— 紧凑列表要贴得紧，松散列表（每项之间空行）
         * 要拉开。这个区分不是洁癖：`- 甲\n- 乙` 和 `- 甲\n\n- 乙`
         * 在视觉上本来就该不一样，模型也靠它表达「这是一组」还是「这是几条独立的话」。
         */
        val tight: Boolean,
    ) : MarkdownBlock

    data class OrderedList(
        /** 起始序号。模型可能从 3 开始写（`3. xxx`），要尊重它。 */
        val start: Int,
        val items: List<List<MarkdownBlock>>,
        val tight: Boolean,
    ) : MarkdownBlock

    /** 表格（GFM 风格）。 */
    data class Table(
        val headers: List<List<InlineSpan>>,
        val aligns: List<ColumnAlign>,
        val rows: List<List<List<InlineSpan>>>,
    ) : MarkdownBlock

    /** 分隔线（`---` / `***` / `___`）。 */
    data object ThematicBreak : MarkdownBlock
}

/** 表格列对齐，来自分隔行里的 `:---` / `:---:` / `---:`。 */
enum class ColumnAlign { None, Left, Center, Right }

/**
 * 行内元素。
 *
 * 刻意做成**树**而不是「带标记的文本」：`**粗体里有 `代码`**` 这种嵌套
 * 用扁平结构表达不了，而模型很喜欢这么写。
 */
sealed interface InlineSpan {

    data class Text(val text: String) : InlineSpan

    /** 行内代码。内部不再解析任何标记。 */
    data class Code(val text: String) : InlineSpan

    data class Strong(val spans: List<InlineSpan>) : InlineSpan

    data class Emphasis(val spans: List<InlineSpan>) : InlineSpan

    data class Strikethrough(val spans: List<InlineSpan>) : InlineSpan

    /**
     * 链接。
     *
     * [spans] 是显示文字（链接文字本身也可以带格式），[href] 是目标。
     *
     * ## 不变量：[href] 一定是「可以安全打开」的地址
     *
     * 解析阶段就做了协议白名单（`http` / `https` / `mailto` / `tel`），
     * 不在白名单里的一律**退化成普通文字**，不会产生 [Link]。
     *
     * 放在解析阶段而不是渲染阶段，是因为这是一条**数据有效性**约束：
     * 渲染层（现在有 Compose，将来可能有别的）不该各自重新实现一遍
     * 「什么地址能点」。而且放在 `:domain` 里能用 JVM 单测钉住，
     * 不用起模拟器。
     *
     * 被拦下的典型：`javascript:`（模型被注入时会写）、`file:`、
     * 相对路径 `./a.md`（打不开，显示成可点的蓝字会让人以为坏了）。
     */
    data class Link(
        val spans: List<InlineSpan>,
        val href: String,
        val title: String? = null,
    ) : InlineSpan

    /**
     * 硬换行。
     *
     * 行尾两个空格，或者行尾一个反斜杠。段落内的换行默认按软换行处理
     * （折叠成一个空格）—— 中文写作里换行往往只是排版，不该变成 `<br>`。
     * 只有显式写了两个空格才当硬换行。
     */
    data object LineBreak : InlineSpan
}

/** 递归地把所有 [InlineSpan.Text] 拼起来。测试与「取纯文本」都用它。 */
fun List<InlineSpan>.plainText(): String = buildString { appendPlainText(this@plainText) }

private fun StringBuilder.appendPlainText(spans: List<InlineSpan>) {
    for (span in spans) {
        when (span) {
            is InlineSpan.Text -> append(span.text)
            is InlineSpan.Code -> append(span.text)
            is InlineSpan.Strong -> appendPlainText(span.spans)
            is InlineSpan.Emphasis -> appendPlainText(span.spans)
            is InlineSpan.Strikethrough -> appendPlainText(span.spans)
            is InlineSpan.Link -> appendPlainText(span.spans)
            InlineSpan.LineBreak -> append('\n')
        }
    }
}
