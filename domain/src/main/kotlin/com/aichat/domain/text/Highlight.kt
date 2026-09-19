package com.aichat.domain.text

/**
 * 把查询串切成若干查询词。
 *
 * 用的是和 [CjkText.forQuery] 同一套归一化：先 `replace(WS, " ")` 再 trim 再切。
 * **不能写成 `query.trim().split(CjkText.WS)`** —— `trim()` 走 `Char.isWhitespace()`，
 * 它认全角空格却**不认**不间断空格 U+00A0，两处标准不一致会留下漏网的段。
 *
 * 多段查询在这里是「或」的语义（每一段各自去找）。注意这和检索层的
 * **AND** 语义不同：FTS 要求每一段都命中才召回，而高亮只是画标记，
 * 多画几处不会有任何危害，少画一处才会让人以为「跳过来根本没这个词」。
 */
internal fun queryTerms(query: String): List<String> =
    query.replace(CjkText.WS, " ").trim().split(" ").filter { it.isNotEmpty() }

/**
 * 找出 [query] 在 [text] 里的所有命中区间。
 *
 * 展示层拿它给命中词画背景色（见 `:app` 的 `MarkdownText`）。
 * 之所以返回纯下标区间而不是渲染好的富文本，理由和 [TextWindow] 一样：
 * 富文本是 UI 的事，纯 Kotlin 的下标才能在 JVM 单测里穷举 ——
 * 越界下标会让渲染层直接崩，区间合法性必须被守住。
 *
 * ## 匹配的是「已经渲染出来的文字」，不是原文
 *
 * 和 [textWindow] 有个重要区别：那个匹配的是**原始 Markdown 源串**
 * （搜索卡片展示的也是源串片段），这个匹配的是**解析后的正文**
 * （`**北京**` 已经变成了「北京」两个字）。
 *
 * 两者刻意不做统一。源串和渲染结果本来就是两段不同的文字，硬凑成
 * 一套匹配规则只会让两边都不对。用户真正要的是「我搜的词在**我看到的
 * 这段话**里的哪儿」，所以这里以渲染结果为准。
 *
 * ## 大小写不敏感，但只对拉丁有意义
 *
 * 和 FTS 索引侧保持一致（`simple` 分词器本身大小写不敏感）。汉字没有大小写，
 * 这一步对中文是恒等操作。
 *
 * ## 相邻的区间会被合并
 *
 * 搜「北 京」时正文里的「北京」会命中两次（一次是「北」，一次是「京」），
 * 得到 `[0,0]` 和 `[1,1]`。不合并的话是两个紧贴的标记 —— 视觉上就是
 * 一个标记被切成两半，边缘会多出两条接缝。合并之后是一个完整的区间。
 *
 * ## 不做数量上限
 *
 * 唯一会调用它的是**被定位到的那一条消息**（不是整个会话），长度有界。
 * 设一个「最多标 N 处」的暗上限，会让「搜到了却没标」这种最难解释的状态
 * 出现在恰好很长的消息上。
 */
fun highlightRanges(text: String, query: String): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    val terms = queryTerms(query)
    if (terms.isEmpty()) return emptyList()

    val found = ArrayList<IntRange>()
    for (term in terms) {
        // **每次前进 1 个字符，不是前进 term.length。**
        //
        // 前进 term.length 是 grep 的语义（找不重叠的出现），但高亮要的是
        // 「标出每一个属于某次出现的字符」：`ababa` 里 `aba` 出现在 0 和 2，
        // 两个区间叠在中间的 `a` 上 —— 前进 length 会漏掉第二次出现，
        // 于是末尾那个 `a` 不被标记，看起来像漏标。
        //
        // 复杂度没有变坏：有命中时每次 indexOf 立刻返回（O(term)），
        // 没命中时一次扫描就结束循环。真正的开销只跟**输出区间数**成正比。
        var at = text.indexOf(term, ignoreCase = true)
        while (at >= 0) {
            found += at until (at + term.length)
            at = text.indexOf(term, at + 1, ignoreCase = true)
        }
    }
    return mergeRanges(found)
}

/**
 * 文本被切成的一段，[highlighted] 表示它是否落在命中区间里。
 *
 * 这个类型存在的唯一目的是让「切段」这一步可以被单测：
 * 渲染层拿到它之后只是逐段 `append` 并给标了记的那些套上底色。
 */
data class HighlightSegment(val text: String, val highlighted: Boolean)

/**
 * 按命中区间把 [text] 切成若干片段。
 *
 * ## 为什么不直接给渲染层 `List<IntRange>`
 *
 * 渲染层要的是「逐段追加」。自己按下标 `substring` 的话，游标推进写错
 * （少加 1、多加 1、忘记追加尾部）会**静默地丢字或重字** ——
 * 界面上看起来只是某个字不对劲，而没人会想到是高亮代码干的。
 *
 * 切段放在这里之后，那条不变量就能被穷举：
 * **所有片段按顺序拼回去，必须逐字符等于原文**。
 *
 * 顺带把「空查询怎么办」也收进一处 —— 之前渲染层有两处各自判断
 * （正文文字、行内代码），漏写一处就会出现「代码里标了、正文没标」
 * 或者反过来。
 */
fun highlightSegments(text: String, query: String): List<HighlightSegment> {
    if (text.isEmpty()) return emptyList()
    val ranges = highlightRanges(text, query)
    if (ranges.isEmpty()) return listOf(HighlightSegment(text, highlighted = false))

    val segments = ArrayList<HighlightSegment>(ranges.size * 2 + 1)
    var cursor = 0
    for (range in ranges) {
        if (range.first > cursor) {
            segments += HighlightSegment(text.substring(cursor, range.first), highlighted = false)
        }
        segments += HighlightSegment(text.substring(range.first, range.last + 1), highlighted = true)
        cursor = range.last + 1
    }
    if (cursor < text.length) {
        segments += HighlightSegment(text.substring(cursor), highlighted = false)
    }
    return segments
}

/** 按起点排序后把重叠**或紧邻**的区间并起来。 */
private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
    if (ranges.isEmpty()) return emptyList()
    val sorted = ranges.sortedBy { it.first }
    val merged = ArrayList<IntRange>(sorted.size)
    var current = sorted.first()

    for (next in sorted.subList(1, sorted.size)) {
        current = if (next.first <= current.last + 1) {
            // 被完全包含时 maxOf 保住原来的终点，不会被缩短
            current.first..maxOf(current.last, next.last)
        } else {
            merged += current
            next
        }
    }
    merged += current
    return merged
}

/**
 * 第一个能在 [text] 里定位到的查询词所对应的区间。
 *
 * 供 [textWindow] 用（它只需要一个锚点，用来决定窗口往哪儿开）。
 * 注意是**按查询词的书写顺序**取第一个能命中的，不是取正文里最靠前的那个 ——
 * 搜「北京 适合」而正文里「适合」在前面时，锚点仍然是「北京」。
 * 这个行为从 [textWindow] 第一天起就是这样，改掉会让已有的窗口位置全部平移，
 * 而它并没有错：用户把「北京」写在前面，就说明那才是他关心的词。
 */
internal fun firstHit(text: String, query: String): IntRange? {
    for (term in queryTerms(query)) {
        val at = text.indexOf(term, ignoreCase = true)
        if (at >= 0) return at until (at + term.length)
    }
    return null
}
