package com.aichat.domain.render

/**
 * 内置 Markdown 解析器。
 *
 * ## 覆盖范围
 *
 * **块级**：ATX 标题（`#`…`######`）、setext 标题（`===` / `---` 下划线）、
 * 围栏代码块（``` 与 ~~~，带语言标签）、引用块（可嵌套）、
 * 无序列表 / 有序列表（可嵌套、区分紧凑与松散）、GFM 表格、分隔线、段落。
 *
 * **行内**：粗体、斜体、删除线、行内代码、链接（含 title）、
 * 自动链接（`<url>` 与裸 URL）、反斜杠转义、换行。
 *
 * ## 有意不支持
 *
 * 这些在对话里几乎不出现，支持它们要付出不成比例的复杂度：
 *
 * - **缩进代码块**（行首 4 空格）。它和列表续行的缩进规则直接冲突，
 *   而模型写代码一定用围栏。
 * - **链接引用定义**（`[label]: url`）。模型给链接永远是行内的。
 * - **HTML 块**。`<div>` 这类一律当普通文字 —— 这是安全决定，见 [MarkdownBlock]。
 * - **图片**。`![alt](url)` 会退化成普通链接文字，不做网络加载。
 * - **表格单元格里的 `|` 转义以外**的复杂表格特性（跨列、跨行）。
 * - **Tab 缩进**。`indentOf` 只数空格；Tab 在代码块里原样保留，但不用来判缩进层级。
 *
 * ## 与 CommonMark 的两处有意偏离
 *
 * 1. **段落内的单个换行渲染成换行**（`InlineSpan.LineBreak`），
 *    而不是折叠成空格。CommonMark 是文档排版规则，聊天不是 ——
 *    模型经常用单换行写「要点式」内容，折叠成空格会让它糊成一团。
 * 2. **setext 下划线 `-` 至少 3 个**才算标题。单个 `-` 或 `--` 更可能是
 *    列表标记，冲突时优先按列表解释。
 */
class BuiltinMarkdownParser : MarkdownParser {

    override fun parse(markdown: String): List<MarkdownBlock> {
        if (markdown.isEmpty()) return emptyList()
        // 末尾的换行会产生一个多余空行，切出来是 ""，parseBlocks 会跳过，无需特殊处理
        return parseBlocks(markdown.split('\n'))
    }

    // ---------------------------------------------------------------- 块级

    private fun parseBlocks(lines: List<String>): List<MarkdownBlock> {
        val out = mutableListOf<MarkdownBlock>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].isBlank()) {
                i++
                continue
            }
            val (block, next) = parseBlockAt(lines, i)
            out += block
            // 防御：任何分支都必须至少前进一行，否则死循环
            i = if (next > i) next else i + 1
        }
        return out
    }

    private fun parseBlockAt(lines: List<String>, start: Int): Pair<MarkdownBlock, Int> {
        val trimmed = lines[start].trimStart()

        // 顺序有讲究，几处不能换：
        // - 分隔线要在列表之前，否则 `* * *` 会被当成无序列表
        // - 围栏要在分隔线之前，`~~~` 不是分隔线（`~` 不在分隔线字符集里，但显式些更清楚）
        fenceAt(trimmed)?.let { return parseFence(lines, start, it) }

        atxHeading(trimmed)?.let { (level, content) ->
            return MarkdownBlock.Heading(level, parseInline(content)) to (start + 1)
        }

        if (isThematicBreak(trimmed)) {
            return MarkdownBlock.ThematicBreak to (start + 1)
        }

        if (trimmed.startsWith(">")) return parseQuote(lines, start)

        itemMarker(lines[start])?.let { return parseList(lines, start, it) }

        tableAt(lines, start)?.let { return it }

        return parseParagraph(lines, start)
    }

    // ---- 围栏代码块 ----

    private fun parseFence(lines: List<String>, start: Int, open: Fence): Pair<MarkdownBlock, Int> {
        val language = open.info
            .split(' ', '\t')
            .firstOrNull { it.isNotBlank() }

        val body = StringBuilder()
        var i = start + 1
        while (i < lines.size) {
            if (isClosingFence(lines[i], open)) {
                i++
                break
            }
            body.append(lines[i]).append('\n')
            i++
        }
        // 去掉最后多补的那个换行。代码块内容保持原样（含内部空行与缩进），
        // 代码里的 `**` 绝不能被当成粗体
        if (body.isNotEmpty()) body.setLength(body.length - 1)

        return MarkdownBlock.CodeBlock(
            language = language?.takeIf { it.isNotEmpty() },
            code = body.toString(),
        ) to i
    }

    private data class Fence(val char: Char, val length: Int, val info: String)

    /** 开围栏：≤3 空格缩进 + 3 个以上 `` ` `` 或 `~`，后面可以跟 info string。 */
    private fun fenceAt(trimmed: String): Fence? {
        val (char, length) = fenceRun(trimmed) ?: return null
        return Fence(char, length, trimmed.substring(length).trim())
    }

    /** 闭围栏：同种字符、不短于开围栏，且**后面只有空白**。 */
    private fun isClosingFence(line: String, open: Fence): Boolean {
        val trimmed = line.trimStart()
        val (char, length) = fenceRun(trimmed) ?: return false
        if (char != open.char || length < open.length) return false
        return trimmed.substring(length).isBlank()
    }

    /** 从行首（已去缩进）数连续的反引号或波浪号；不足 3 个返回 null。 */
    private fun fenceRun(trimmed: String): Pair<Char, Int>? {
        if (trimmed.isEmpty()) return null
        val char = trimmed[0]
        if (char != '`' && char != '~') return null
        var n = 0
        while (n < trimmed.length && trimmed[n] == char) n++
        return if (n >= 3) char to n else null
    }

    // ---- ATX 标题 ----

    /** 返回 (级别, 内容)；不是 ATX 标题返回 null。 */
    private fun atxHeading(trimmed: String): Pair<Int, String>? {
        var n = 0
        while (n < trimmed.length && trimmed[n] == '#') n++
        if (n == 0 || n > 6) return null
        // CommonMark 要求 `#` 后面必须有空格。不放宽这条：
        // `#1` 这类更可能是「第 1 项」而不是标题
        if (n < trimmed.length && trimmed[n] != ' ' && trimmed[n] != '\t') return null
        val content = trimmed.substring(n).trim().replace(TRAILING_HASHES, "")
        return n to content
    }

    // ---- 分隔线 ----

    private fun isThematicBreak(trimmed: String): Boolean {
        if (trimmed.length < 3) return false
        val char = trimmed[0]
        if (char != '-' && char != '*' && char != '_') return false
        var count = 0
        for (c in trimmed) {
            when {
                c == char -> count++
                c == ' ' -> Unit
                else -> return false
            }
        }
        return count >= 3
    }

    // ---- 引用块 ----

    private fun parseQuote(lines: List<String>, start: Int): Pair<MarkdownBlock, Int> {
        val inner = mutableListOf<String>()
        var i = start
        while (i < lines.size) {
            val trimmed = lines[i].trimStart()
            when {
                trimmed.startsWith(">") -> {
                    val rest = trimmed.substring(1)
                    inner += if (rest.startsWith(" ")) rest.substring(1) else rest
                    i++
                }
                // 引用块里的空行以 `>` 结尾（`> 甲\n>\n> 乙`）时，
                // 上面那条已经处理了；真正的空行表示引用结束
                trimmed.isBlank() -> break
                // 懒续行：没写 `>` 的续行。但不能让它吞掉一个新块的开始
                startsNewBlock(lines, i) -> break
                else -> {
                    inner += trimmed
                    i++
                }
            }
        }
        return MarkdownBlock.Quote(parseBlocks(inner)) to i
    }

    // ---- 列表 ----

    private data class ItemMarker(
        val ordered: Boolean,
        /** 有序列表的起始序号；无序列表为 0。 */
        val number: Int,
        /** 标记所在列的缩进。 */
        val indent: Int,
        /** 内容起始列。`- 甲` 是 2，`1. 甲` 是 3。 */
        val contentStart: Int,
    )

    private fun itemMarker(line: String): ItemMarker? {
        val indent = indentOf(line)
        // 4 个以上空格是缩进代码块，本实现不支持，也就不能当列表项
        if (indent > 3) return null
        val trimmed = line.substring(indent)
        if (trimmed.isEmpty()) return null

        val first = trimmed[0]
        if (first == '-' || first == '*' || first == '+') {
            // `**粗体**` 不能当成 `*` 开头的列表项：标记后面必须是空白或行尾
            if (trimmed.length > 1 && trimmed[1] != ' ' && trimmed[1] != '\t') return null
            val contentStart = if (trimmed.length > 1) indent + 2 else indent + 1
            return ItemMarker(false, 0, indent, contentStart)
        }

        var digits = 0
        while (digits < trimmed.length && trimmed[digits].isDigit() && digits < 9) digits++
        if (digits == 0 || digits >= trimmed.length) return null
        if (trimmed[digits] != '.' && trimmed[digits] != ')') return null
        if (digits + 1 < trimmed.length && trimmed[digits + 1] != ' ' && trimmed[digits + 1] != '\t') return null

        val number = trimmed.substring(0, digits).toIntOrNull() ?: return null
        return ItemMarker(true, number, indent, indent + digits + 2)
    }

    private fun parseList(lines: List<String>, start: Int, first: ItemMarker): Pair<MarkdownBlock, Int> {
        val items = mutableListOf<List<MarkdownBlock>>()
        var loose = false
        var i = start

        while (i < lines.size) {
            val marker = itemMarker(lines[i]) ?: break
            // 只有「同缩进 + 同类型」才算同一个列表的兄弟项
            if (marker.indent != first.indent || marker.ordered != first.ordered) break

            val contentIndent = marker.contentStart
            val itemLines = mutableListOf<String>()
            itemLines += lines[i].substring(minOf(contentIndent, lines[i].length))
            i++

            var blankSeen = false
            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) {
                    // 空行之后还有内容吗？
                    var j = i
                    while (j < lines.size && lines[j].isBlank()) j++
                    if (j >= lines.size) {
                        i = j
                        break
                    }
                    val next = itemMarker(lines[j])
                    if (next != null && next.indent == first.indent && next.ordered == first.ordered) {
                        // 下一个兄弟项
                        blankSeen = true
                        i = j
                        break
                    }
                    if (indentOf(lines[j]) >= contentIndent) {
                        // 本项的续段（松散列表）
                        blankSeen = true
                        itemLines += ""
                        i++
                        continue
                    }
                    // 列表结束
                    i = j
                    break
                }

                val indent = indentOf(line)
                if (indent >= contentIndent) {
                    itemLines += line.substring(contentIndent)
                    i++
                } else if (indent > first.indent && !startsNewBlock(lines, i)) {
                    // 缩进不够但比标记深，且不是新块的开始 —— 按懒续行收进本项
                    itemLines += line.trimStart()
                    i++
                } else {
                    break
                }
            }

            if (blankSeen) loose = true
            items += parseBlocks(itemLines)
        }

        val block = if (first.ordered) {
            MarkdownBlock.OrderedList(start = first.number, items = items, tight = !loose)
        } else {
            MarkdownBlock.BulletList(items = items, tight = !loose)
        }
        return block to i
    }

    // ---- 表格 ----

    private fun tableAt(lines: List<String>, start: Int): Pair<MarkdownBlock, Int>? {
        if (start + 1 >= lines.size) return null
        val header = lines[start]
        if (!header.contains('|')) return null

        val aligns = delimiterRow(lines[start + 1]) ?: return null
        val headerCells = splitRow(header)
        // 列数必须对上，否则更可能是「正文里恰好有个竖线」
        if (headerCells.size != aligns.size) return null

        val rows = mutableListOf<List<List<InlineSpan>>>()
        var i = start + 2
        while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
            val cells = splitRow(lines[i])
            rows += (0 until aligns.size).map { c ->
                parseInline(cells.getOrElse(c) { "" }.trim())
            }
            i++
        }

        val block = MarkdownBlock.Table(
            headers = headerCells.map { parseInline(it.trim()) },
            aligns = aligns,
            rows = rows,
        )
        return block to i
    }

    /** 解析 `| --- | :---: |` 这样的分隔行；不是分隔行返回 null。 */
    private fun delimiterRow(line: String): List<ColumnAlign>? {
        if (!line.contains('-')) return null
        val cells = splitRow(line)
        if (cells.isEmpty()) return null
        val out = mutableListOf<ColumnAlign>()
        for (raw in cells) {
            val cell = raw.trim()
            if (cell.isEmpty()) return null
            val left = cell.startsWith(":")
            val right = cell.endsWith(":")
            val core = cell.removePrefix(":").removeSuffix(":")
            if (core.isEmpty() || !core.all { it == '-' }) return null
            out += when {
                left && right -> ColumnAlign.Center
                left -> ColumnAlign.Left
                right -> ColumnAlign.Right
                else -> ColumnAlign.None
            }
        }
        return out
    }

    /** 按未转义的 `|` 切分一行，并去掉首尾的包裹竖线。 */
    private fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)

        val cells = mutableListOf<String>()
        val buf = StringBuilder()
        var k = 0
        while (k < s.length) {
            val c = s[k]
            if (c == '\\' && k + 1 < s.length && s[k + 1] == '|') {
                // `\|` 是单元格里的字面竖线，不能当分隔符
                buf.append('|')
                k += 2
                continue
            }
            if (c == '|') {
                cells += buf.toString()
                buf.setLength(0)
                k++
                continue
            }
            buf.append(c)
            k++
        }
        cells += buf.toString()
        return cells
    }

    // ---- 段落（含 setext 标题） ----

    private fun parseParagraph(lines: List<String>, start: Int): Pair<MarkdownBlock, Int> {
        val buf = mutableListOf<String>()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) break
            val trimmed = line.trimStart()

            if (buf.isNotEmpty()) {
                setextLevel(trimmed)?.let { level ->
                    return MarkdownBlock.Heading(level, parseInline(buf.joinToString("\n"))) to (i + 1)
                }
                if (startsNewBlock(lines, i)) break
            }

            buf += trimmed
            i++
        }
        return MarkdownBlock.Paragraph(parseInline(buf.joinToString("\n"))) to i
    }

    private fun setextLevel(trimmed: String): Int? {
        val s = trimmed.trimEnd()
        if (s.isEmpty()) return null
        if (s.all { it == '=' }) return 1
        // 单个或两个 `-` 更可能是列表标记，要求 3 个以上（见类注释的偏离说明）
        if (s.length >= 3 && s.all { it == '-' }) return 2
        return null
    }

    // ---- 共用 ----

    /** 这一行是否会开启一个新的块。用来终止段落与引用块的懒续行。 */
    private fun startsNewBlock(lines: List<String>, i: Int): Boolean {
        val line = lines[i]
        if (line.isBlank()) return true
        val trimmed = line.trimStart()
        if (fenceAt(trimmed) != null) return true
        if (atxHeading(trimmed) != null) return true
        if (isThematicBreak(trimmed)) return true
        if (trimmed.startsWith(">")) return true
        if (itemMarker(line) != null) return true
        return false
    }

    private fun indentOf(line: String): Int {
        var n = 0
        while (n < line.length && line[n] == ' ') n++
        return n
    }

    private companion object {
        /** ATX 标题结尾可选的 `#` 串。 */
        val TRAILING_HASHES = Regex("\\s*#+\\s*$")
    }
}

// ==================================================================== 行内

/**
 * 行内解析。
 *
 * 做成「找闭合位置 → 递归解析中间那段」而不是 CommonMark 的定界符栈：
 * 栈能正确处理 `**甲 *乙* 丙**` 这种交叉嵌套的全部情形，但实现复杂、容易出错，
 * 而模型写出来的行内嵌套基本只有一层（`**粗体里的 `代码`**`）。
 * 递归版本的取舍是：**遇到歧义时退化成纯文本，而不是猜**。
 */
private fun parseInline(text: String, autolink: Boolean = true): List<InlineSpan> =
    InlineScanner(text, autolink).run()

private class InlineScanner(
    private val s: String,
    private val autolink: Boolean,
) {
    private var i = 0
    private val out = mutableListOf<InlineSpan>()
    private val buf = StringBuilder()

    fun run(): List<InlineSpan> {
        while (i < s.length) step()
        flush()
        return out
    }

    private fun step() {
        val c = s[i]
        when {
            c == '\\' -> escape()
            c == '\n' -> {
                flush()
                out += InlineSpan.LineBreak
                i++
            }
            c == '`' -> if (!codeSpan()) literal(c)
            c == '~' && s.startsWith("~~", i) -> if (!wrapped("~~") { InlineSpan.Strikethrough(it) }) literalRun('~', 2)
            c == '*' || c == '_' -> emphasis(c)
            c == '[' -> if (!link()) literal(c)
            c == '<' -> if (!angleAutolink()) literal(c)
            // 注意不能写成 `autolink && c == 'h' && !bareUrl() -> literal(c)`：
            // 那样 bareUrl() 返回 true（已经吃掉了 URL）时条件整体为 false，
            // 会掉进 else 分支，把同一个字符再追加一次、游标再退一格。
            c == 'h' && autolink -> if (!bareUrl()) literal(c)
            else -> literal(c)
        }
    }

    /** 反斜杠转义；`\` 后跟换行是硬换行。 */
    private fun escape() {
        if (i + 1 >= s.length) {
            literal('\\')
            return
        }
        if (s[i + 1] == '\n') {
            flush()
            out += InlineSpan.LineBreak
            i += 2
            return
        }
        // 只对 ASCII 标点做转义。`\中` 应当原样显示成 `\中`，
        // 否则模型输出里的 Windows 路径（`C:\用户`）会掉字符
        val next = s[i + 1]
        if (next.isAsciiPunctuation()) {
            buf.append(next)
            i += 2
        } else {
            literal('\\')
        }
    }

    /** `*` / `_` 系列：优先当强（两个），其次当强调（一个）。 */
    private fun emphasis(char: Char) {
        val double = "$char$char"
        if (s.startsWith(double, i) && canOpen(double)) {
            if (wrapped(double) { InlineSpan.Strong(it) }) return
        }
        val single = char.toString()
        if (canOpen(single)) {
            if (wrapped(single) { InlineSpan.Emphasis(it) }) return
        }
        literal(char)
    }

    /**
     * 通用「成对包裹」：找到闭合定界符，把中间那段递归解析。
     * 找不到闭合就返回 false，调用方按纯文本处理。
     */
    private fun wrapped(delim: String, wrap: (List<InlineSpan>) -> InlineSpan): Boolean {
        val close = findClosing(delim, i + delim.length)
        if (close < 0) return false
        val inner = s.substring(i + delim.length, close)
        // 空的包裹（`****`）没有意义，当纯文本
        if (inner.isEmpty()) return false
        val spans = parseInline(inner, autolink)
        if (spans.isEmpty()) return false
        flush()
        out += wrap(spans)
        i = close + delim.length
        return true
    }

    /** 定界符能否开启一个包裹：后面不能紧跟空白。 */
    private fun canOpen(delim: String): Boolean {
        val after = i + delim.length
        if (after >= s.length) return false
        if (s[after].isWhitespace()) return false
        // 词内下划线不算强调，否则 `snake_case_name` 会变成 `snake<em>case</em>name`
        if (delim[0] == '_' && i > 0 && s[i - 1].isLetterOrDigit()) return false
        return true
    }

    /** 从 [from] 起找闭合定界符，跳过转义与行内代码。 */
    private fun findClosing(delim: String, from: Int): Int {
        var k = from
        while (k <= s.length - delim.length) {
            val c = s[k]
            if (c == '\\') {
                k += 2
                continue
            }
            if (c == '`') {
                // 行内代码里的 `*` 不算定界符
                val run = runLength(k, '`')
                val close = findBacktickRun(k + run, run)
                k = if (close < 0) k + run else close + run
                continue
            }
            if (c == delim[0] && s.startsWith(delim, k)) {
                val run = runLength(k, delim[0])
                if (run >= delim.length) {
                    // 闭定界符的条件只有一个：**前面**不能是空白。
                    //
                    // 这里曾经多写了一条「后面也不能是空白」，是错的 ——
                    // 那条属于开定界符（left-flanking）。加上它的后果是
                    // `**粗体** 后面还有字` 识别不出粗体：闭合的 `**` 后面
                    // 恰好是个空格，于是 findClosing 一路找到结尾都找不到，
                    // 整段退化成纯文本 `*粗体* 后面还有字`。
                    val before = if (k > 0) s[k - 1] else SENTINEL
                    if (!before.isWhitespace()) return k
                }
            }
            k++
        }
        return -1
    }

    /** 行内代码：反引号个数必须与开头一致。 */
    private fun codeSpan(): Boolean {
        val run = runLength(i, '`')
        val close = findBacktickRun(i + run, run)
        if (close < 0) return false
        var inner = s.substring(i + run, close)
        // CommonMark：首尾各有一个空格时各去掉一个，这样才写得出「内容就是反引号」
        if (inner.length >= 2 && inner.startsWith(" ") && inner.endsWith(" ") && inner.isNotBlank()) {
            inner = inner.substring(1, inner.length - 1)
        }
        flush()
        out += InlineSpan.Code(inner.replace('\n', ' '))
        i = close + run
        return true
    }

    /** `[文字](地址 "标题")` */
    private fun link(): Boolean {
        val close = matchBracket(i)
        if (close < 0) return false
        if (close + 1 >= s.length || s[close + 1] != '(') return false
        val paren = matchParen(close + 1)
        if (paren < 0) return false

        val (url, title) = splitUrlTitle(s.substring(close + 2, paren).trim())
        if (url.isEmpty()) return false
        // 协议不在白名单里就整段按字面量显示。返回 false 之后调用方会把 `[`
        // 当普通字符处理，于是 `[点我](javascript:alert(1))` 原样显示成文字 ——
        // 这正是我们想要的：不安全的东西不该看起来像能点
        if (!isOpenable(url)) return false

        // 链接文字里不再做自动链接，否则 `[https://a](https://a)` 会套出嵌套链接
        val label = parseInline(s.substring(i + 1, close), autolink = false)
        flush()
        out += InlineSpan.Link(
            spans = label.ifEmpty { listOf(InlineSpan.Text(url)) },
            href = url,
            title = title,
        )
        i = paren + 1
        return true
    }

    /** `<https://...>` 或 `<someone@example.com>` */
    private fun angleAutolink(): Boolean {
        if (!autolink) return false
        val close = s.indexOf('>', i + 1)
        if (close < 0) return false
        val body = s.substring(i + 1, close)
        val url = when {
            body.startsWith("http://") || body.startsWith("https://") -> body
            body.contains('@') && body.none { it.isWhitespace() } -> "mailto:$body"
            else -> return false
        }
        flush()
        out += InlineSpan.Link(listOf(InlineSpan.Text(body)), url)
        i = close + 1
        return true
    }

    /**
     * 裸 URL。
     *
     * 这是流式渲染里抖动最明显的一处（URL 每长一个字符，链接就重建一次），
     * 但只在最后一行，且模型输出链接时基本都是裸 URL，不识别体验更差。
     */
    private fun bareUrl(): Boolean {
        if (!s.startsWith("http://", i) && !s.startsWith("https://", i)) return false
        // 前面紧贴字母数字或 `@` / `/` 说明这不是 URL 的开头（`xhttp://`、`//http://`）
        if (i > 0) {
            val prev = s[i - 1]
            if (prev.isLetterOrDigit() || prev == '@' || prev == '/') return false
        }

        var k = i
        // 按「是不是 URL 合法字符」停，而不是按「是不是空白」停。
        //
        // 只按空白停会吃掉后面紧跟的中文：`见 https://a.com，然后这样`
        // 整串都会变成链接（中文不是空白），点开必然 404。
        // URL 里的非 ASCII 只出现在国际化域名里，对话中极其罕见，
        // 而「URL 后面紧跟中文」是常态，所以按 ASCII 字符集收敛。
        while (k < s.length && s[k].isAsciiUrlChar()) k++
        var url = s.substring(i, k)

        // 中文标点和句末标点通常是句子的，不属于 URL
        while (url.isNotEmpty() && url.last() in TRAILING_PUNCTUATION) url = url.dropLast(1)
        // 右括号要配平：`(见 https://a.com/b)` 里的 `)` 不属于 URL，
        // 但 `https://en.wikipedia.org/wiki/Foo_(bar)` 里的属于
        while (url.endsWith(")") && url.count { it == '(' } < url.count { it == ')' }) {
            url = url.dropLast(1)
        }

        if (url.length <= "https://".length) return false
        flush()
        out += InlineSpan.Link(listOf(InlineSpan.Text(url)), url)
        i += url.length
        return true
    }

    // ---- 小工具 ----

    private fun flush() {
        if (buf.isNotEmpty()) {
            out += InlineSpan.Text(buf.toString())
            buf.setLength(0)
        }
    }

    private fun literal(c: Char) {
        buf.append(c)
        i++
    }

    private fun literalRun(c: Char, n: Int) {
        repeat(n) { buf.append(c) }
        i += n
    }

    private fun runLength(from: Int, c: Char): Int {
        var n = 0
        while (from + n < s.length && s[from + n] == c) n++
        return n
    }

    private fun findBacktickRun(from: Int, length: Int): Int {
        var k = from
        while (k < s.length) {
            if (s[k] == '`') {
                val run = runLength(k, '`')
                if (run == length) return k
                k += run
            } else {
                k++
            }
        }
        return -1
    }

    /** 从 `[` 找匹配的 `]`，跳过转义、行内代码与嵌套方括号。 */
    private fun matchBracket(from: Int): Int {
        var depth = 0
        var k = from
        while (k < s.length) {
            when {
                s[k] == '\\' -> k += 2
                s[k] == '`' -> {
                    val run = runLength(k, '`')
                    val close = findBacktickRun(k + run, run)
                    k = if (close < 0) k + run else close + run
                }
                s[k] == '[' -> {
                    depth++
                    k++
                }
                s[k] == ']' -> {
                    depth--
                    if (depth == 0) return k
                    k++
                }
                else -> k++
            }
        }
        return -1
    }

    /** 从 `(` 找匹配的 `)`，跳过转义与 `<...>` 包裹的地址。 */
    private fun matchParen(from: Int): Int {
        var depth = 0
        var inAngle = false
        var k = from
        while (k < s.length) {
            val c = s[k]
            when {
                c == '\\' -> k += 2
                c == '<' -> {
                    inAngle = true
                    k++
                }
                c == '>' -> {
                    inAngle = false
                    k++
                }
                inAngle -> k++
                c == '(' -> {
                    depth++
                    k++
                }
                c == ')' -> {
                    depth--
                    if (depth == 0) return k
                    k++
                }
                else -> k++
            }
        }
        return -1
    }

    private companion object {
        /** 越过字符串边界的哨兵。不能用空格 —— 空格会让「结尾的定界符」判为不可闭合。 */
        const val SENTINEL = '\u0000'

        val TITLE = Regex("""^(\S*)\s+(?:"([^"]*)"|'([^']*)'|\(([^()]*)\))$""")

        val TRAILING_PUNCTUATION = ".,;:!?，。；：！？、）】」》"

        fun splitUrlTitle(inner: String): Pair<String, String?> {
            if (inner.isEmpty()) return "" to null
            TITLE.find(inner)?.let { m ->
                val title = m.groupValues[2]
                    .ifEmpty { m.groupValues[3] }
                    .ifEmpty { m.groupValues[4] }
                return unwrapAngle(m.groupValues[1]) to title
            }
            return unwrapAngle(inner) to null
        }

        /** `[文字](<带 空格 的地址>)` 允许用尖括号包住地址。 */
        fun unwrapAngle(url: String): String =
            if (url.length >= 2 && url.startsWith("<") && url.endsWith(">")) {
                url.substring(1, url.length - 1)
            } else {
                url
            }

        /**
         * 能安全打开的协议白名单。
         *
         * 判断的是**协议**而不是域名 —— 我们不需要判断「这个网站可不可信」
         * （那是用户自己的事），只需要挡住那些「点下去会做别的事」的协议。
         * `javascript:` 是唯一真正危险的那个，其余是「点了没反应」的体验问题。
         *
         * 相对路径（没有冒号）也在拦下之列：模型偶尔会写 `[文档](./a.md)`，
         * 显示成可点的蓝字但点不动，比直接显示成普通文字更让人困惑。
         */
        val OPENABLE_SCHEMES = setOf("http", "https", "mailto", "tel")

        fun isOpenable(href: String): Boolean {
            val colon = href.indexOf(':')
            if (colon <= 0) return false
            return href.substring(0, colon).lowercase() in OPENABLE_SCHEMES
        }

        /** 只转义 ASCII 标点，理由见 [InlineScanner.escape]。 */
        fun Char.isAsciiPunctuation(): Boolean =
            this in '!'..'/' || this in ':'..'@' || this in '['..'`' || this in '{'..'~'

        /**
         * URL 里合法的字符。
         *
         * 不含 `[` `]`（只在 IPv6 字面量里出现，日常见不到，但很容易吃掉
         * markdown 的方括号）和 `<>"` 与反引号（会截断 HTML / 代码片段）。
         * 保留 `(` `)` 交给裸 URL 尾部的括号配平逻辑处理 ——
         * Wikipedia 链接里有括号，而「URL 被括号包住」也很常见。
         */
        const val URL_CHARS = "-._~:/?#@!$&'()*+,;=%"

        fun Char.isAsciiUrlChar(): Boolean =
            (this in 'a'..'z') || (this in 'A'..'Z') || (this in '0'..'9') || this in URL_CHARS
    }
}
