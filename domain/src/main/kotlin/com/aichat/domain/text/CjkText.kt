package com.aichat.domain.text

/**
 * 中文全文检索的切分与查询构造。
 *
 * 背景：SQLite 内置分词器（unicode61 / simple）把一整段连续汉字当成**单个 token**，
 * 于是「会议」搜不到「帮我把会议纪要整理成表格」；中英混排时更糟——
 * 「类似RikkaHub」整串是一个 token，连 RikkaHub 都搜不出来。
 *
 * 解法：入库前在每个 CJK 字符两侧插空格，让每个汉字成为独立 token；
 * 查询侧做同样切分并组成**短语**（FTS 短语查询要求 token 位置相邻），
 * 从而保证字序，语义上等价于子串匹配。
 *
 * 实测结论见 spike/RESULT.md。纯 Kotlin，零 Android 依赖，
 * 放在 :domain 模块，单测不需要模拟器。
 */
object CjkText {

    /** 是否需要逐字切分。覆盖 CJK 统一表意文字、扩展 A、兼容表意文字与日文假名。 */
    fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x4E00..0x9FFF) ||
            (c in 0x3400..0x4DBF) ||
            (c in 0xF900..0xFAFF) ||
            (c in 0x3040..0x30FF)
    }

    /**
     * 入库用：汉字两侧补空格，拉丁 / 数字 / 标点保持原样。
     * 拉丁词因此仍是完整 token，前缀查询（rikka*）继续可用。
     *
     * 末尾的 `.replace(WS, " ")` 会把**所有 Unicode 空白归一成半角空格**，
     * 包括全角空格 U+3000 和不间断空格 U+00A0。这一步不能省 —— 见 [WS]。
     */
    fun forIndex(text: String): String = buildString(text.length * 2) {
        for (ch in text) {
            if (isCjk(ch)) {
                append(' ').append(ch).append(' ')
            } else {
                append(ch)
            }
        }
    }.replace(WS, " ").trim()

    /**
     * 查询用：把用户输入转成 FTS 查询表达式。
     *
     * - 单个汉字          -> `会`
     * - 连续多个汉字      -> `"会 议"`（短语，保证字序相邻）
     * - 拉丁 / 数字       -> 小写；prefixLast 为 true 时加 `*` 支持边输边搜
     * - 空白分隔的多段    -> 各段之间**空格并置**（见下）
     *
     * **prefixLast 只影响拉丁段**：汉字是逐字索引，短语查询本身就等价于子串匹配
     * （`"会 议"` 能命中 `会 议 纪 要`），所以中文「边输边搜」无需额外开关；
     * 而拉丁词始终是完整 token（`rikkahub`），只有加 `*` 才能让 `rikka` 命中。
     *
     * 返回空串表示无有效查询词，调用方必须直接返回空列表。
     *
     * ## 多段之间为什么用空格并置，而不是 `AND`
     *
     * 两者在 SQLite 的 **Enhanced Query Syntax** 下等价，但 **Android 内置的
     * SQLite 编译时没有开 `SQLITE_ENABLE_FTS3_PARENTHESIS`**，用的是
     * **Standard Query Syntax** —— 在那套语法里 `AND` 根本不是运算符，
     * 而是被当成一个**普通 token**。
     *
     * 后果：`"会 议" AND "纪 要"` 被解析成「三个条件都要满足」——
     * 短语「会 议」、单词 `and`、短语「纪 要」。而索引里永远不会有 `and`
     * 这个 token，于是**带空格的多段查询在真机上永远返回空**。
     *
     * 实测（MuMu / Android / SQLite 3.44.3）：
     * ```
     * MATCH '"北 京" AND "适 合"'   -> 空
     * MATCH '"北 京" "适 合"'       -> 命中
     * ```
     * 同一组表达式在桌面版 SQLite 3.53.1 上**都**命中 —— 所以这个坑
     * 只在真机上才暴露：单测只检查字符串拼得对不对，本地跑 SQL 又用的是
     * Enhanced 语法，两边都发现不了。
     *
     * 空格并置在两种语法下都是隐式 AND，是唯一两边都正确的写法。
     * 同理，`NOT` 也不能用（Standard 语法里用 `-`），所以这里不产出它。
     */
    fun forQuery(raw: String, prefixLast: Boolean = false): String {
        // 先把所有 Unicode 空白归一成半角空格，再 trim / split。
        // 不能只写 raw.trim().split(WS) —— trim() 走的是 Char.isWhitespace()，
        // 它认全角空格却**不认** U+00A0，两处标准不一致会留下漏网的段。
        val segments = raw.replace(WS, " ").trim().split(" ").filter { it.isNotEmpty() }
        if (segments.isEmpty()) return ""

        return segments.mapIndexed { idx, seg ->
            val isLast = idx == segments.lastIndex
            renderSegment(seg, prefixLast && isLast)
        }.joinToString(" ")
    }

    private fun renderSegment(seg: String, prefix: Boolean): String {
        val head = seg.first()
        if (seg.all { isCjk(it) }) {
            val tokens = seg.map { it.toString() }
            return if (tokens.size == 1) tokens[0] else quote(tokens)
        }
        if (seg.none { isCjk(it) }) {
            return escapeLatin(seg) + if (prefix) "*" else ""
        }
        // 中英混排：按「是否 CJK」切成若干段，各自渲染后空格相连（FTS 视为 AND）
        val parts = mutableListOf<String>()
        val buf = StringBuilder()
        var bufIsCjk = isCjk(head)
        for (ch in seg) {
            val c = isCjk(ch)
            if (c != bufIsCjk) {
                parts += renderRun(buf.toString(), bufIsCjk)
                buf.clear()
                bufIsCjk = c
            }
            buf.append(ch)
        }
        if (buf.isNotEmpty()) parts += renderRun(buf.toString(), bufIsCjk)
        return parts.joinToString(" ")
    }

    private fun renderRun(s: String, cjk: Boolean): String =
        if (cjk) quote(s.map { it.toString() }) else escapeLatin(s)

    private fun quote(tokens: List<String>) = "\"" + tokens.joinToString(" ") + "\""

    /**
     * 非 ASCII 字母数字一律加引号包住，并转义内部的 `"`。
     *
     * ## 白名单为什么是「ASCII 字母数字」而不是 `isLetterOrDigit()`
     *
     * 因为 FTS 的默认分词器（simple）只把 **ASCII 字母数字**当作 token 字符，
     * 其余一切都当分隔符。`isLetterOrDigit()` 会把下划线之外的很多东西也算成
     * 「安全」（韩文、带重音的拉丁字母、希腊字母……），但它们在查询里
     * **一个 token 都产生不了**，于是那个位置变成「空」：
     *
     * - `MATCH '会议 _'` → `"会 议" AND _` → `malformed MATCH expression`，
     *   查询**直接失败**。用户只是在搜索框里打了一个下划线。
     * - 下划线本身也在这个坑里：`isLetterOrDigit('_')` 是 false，但代码里
     *   原本额外放行了它（`|| it == '_'`），结果一样会崩。
     *
     * 多包一层引号最坏也只是把语义收成「要求相邻」，而裸着出去是查询报错。
     * 所以白名单宁可窄。
     *
     * 两个分支统一转小写，避免出现 `GPT-4o` 与 `gpt-4o` 构造出不同查询的情况。
     * 前提：FTS 表用默认分词器（Room `@Fts4` 的 `simple`），它本身大小写不敏感。
     * **如果将来改成大小写敏感的分词器，这里必须去掉 lowercase**，
     * 因为 forIndex 入库时保留了原始大小写。
     */
    private fun escapeLatin(s: String): String =
        if (s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
            s.lowercase()
        } else {
            "\"" + s.replace("\"", "\"\"").lowercase() + "\""
        }

    /**
     * 调试用：把索引文本还原成人类可读形式。
     *
     * 正式展示**不要**用这个 —— 正确做法是 FTS 表只返回 rowid，
     * 再 JOIN 主表拿原始 content（见 MessageFtsDao.search）。
     */
    fun stripIndexPadding(indexed: String): String {
        val sb = StringBuilder(indexed.length)
        var i = 0
        while (i < indexed.length) {
            val ch = indexed[i]
            if (ch == ' ') {
                val prev = sb.lastOrNull()
                val next = indexed.getOrNull(i + 1)
                if (prev != null && next != null && isCjk(prev) && isCjk(next)) {
                    i++
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    /**
     * 空白分隔。
     *
     * **不能用 `\\s`** —— Java 的 `\s` 只认 ASCII 空白（空格、`\t`、`\n`、
     * `\x0B`、`\f`、`\r`），不认全角空格 U+3000、不间断空格 U+00A0 这些
     * Unicode 分隔符。
     *
     * 后果很隐蔽：`forIndex("会议　纪要")`（全角空格）产出 `会 议　纪 要`，
     * 全角空格原样留在索引里；查询侧又不在它那里分段，于是整段
     * `会议　纪要` 被当成**一个** segment 走中英混排分支，渲染成
     * `"会 议" "　" "纪 要"` —— 中间那个 `"　"` 是一个**空短语**，
     * 整条查询因此匹配不到任何东西。
     *
     * 用户看到的是「用全角空格搜不到、用半角空格能搜到」，全程没有任何报错。
     * 中文输入法下全角空格很容易打出来（Shift + 空格），所以这不是边角情况。
     *
     * `\p{Z}` 覆盖 Unicode 的 Zs / Zl / Zp 三类分隔符。
     *
     * `internal` 而不是 `private`：同一个模块里的 [textWindow] 要用它把正文
     * 压平。两边必须用**同一个**定义 —— 各写各的 `\\s` 就会出现「查询侧
     * 认全角空格、展示侧不认」这种只在特定输入下才显形的不一致。
     */
    internal val WS = Regex("[\\s\\p{Z}]+")
}
