package com.aichat.domain.text

/**
 * 以命中词为中心截出来的一段正文。
 *
 * [text] 是**已经截好的字符串**（两端可能带省略号），[hitRange] 是其中
 * 该被强调的区间，`null` 表示没有可强调的位置。
 *
 * ## 为什么用 `IntRange` 而不是直接返回渲染好的富文本
 *
 * 因为富文本是 UI 的事。这里返回一个纯 Kotlin 的下标区间，这个类就能在
 * JVM 单测里被穷举（越界下标会让渲染层崩，所以区间合法性必须被守住），
 * 而调用方（Compose 界面）自己决定怎么强调 —— 现在是加粗，将来要换成
 * 背景色也不用改这里。
 */
data class TextWindow(
    val text: String,
    val hitRange: IntRange?,
) {
    companion object {
        /**
         * 默认窗口大小。
         *
         * 120 个汉字大致是搜索结果卡片四行的容量。模型用的窗口要大得多
         * （见 `:tools` 的 `SearchHistoryTool`）—— 用户只需要认出「是不是这条」，
         * 模型要靠它判断「这条有没有用」，所以给它更多的上下文。
         */
        const val DEFAULT_MAX_CHARS = 120
    }
}

/**
 * 从一段正文里截出**包含查询词**的窗口。
 *
 * ## 为什么不能直接取开头
 *
 * 一条消息可能几千字。取开头的话，用户搜「会议纪要」看到的是
 * 「帮我把下面这段整理一下：……」—— 里面根本没有他搜的词，
 * 他会以为搜错了。**命中词必须出现在窗口里**，这是这个函数存在的全部理由。
 *
 * 模型侧同理，而且更严重：模型只能靠这段文字判断这条历史消息是否相关，
 * 看不到命中词的话它会把不相关的东西当成记忆引用出来。
 *
 * ## 找不到命中位置是正常的
 *
 * FTS 是 AND 语义而非子串语义：搜「对话AI」能召回「AI对话软件」，
 * 但 `indexOf("对话AI")` 找不到（见 `CjkSearchInstrumentedTest` 里
 * 那条同名用例）。这时退回从开头截 —— 不假装知道该显示哪一段。
 *
 * ## 换行与所有 Unicode 空白都会被压平
 *
 * 正文里有换行、制表符、全角空格。原样塞进一行会变成一堆空白。
 * 用的是和 [CjkText] 同一个空白定义（`\s` + `\p{Z}`），所以全角空格
 * U+3000 和不间断空格 U+00A0 也在这里被归一 —— 只写 `\s` 的话它们会
 * 原样留下，在界面上表现为一段莫名其妙的空隙。
 *
 * @param maxChars 窗口上限（字符数）。必须为正数。
 */
fun textWindow(
    content: String,
    query: String,
    maxChars: Int = TextWindow.DEFAULT_MAX_CHARS,
): TextWindow {
    // 0 会让下面的 end <= start，substring 直接抛。宁可在这里明确失败，
    // 也不要让调用方拿到一个语义未定义的窗口。
    require(maxChars > 0) { "maxChars 必须为正数，收到 $maxChars" }

    val flat = content.replace(CjkText.WS, " ").trim()
    if (flat.isEmpty()) return TextWindow("", null)

    // 多段查询（空白分隔）里，取**第一个能在正文里定位到**的那段作为锚点。
    // 定位不到就继续试下一段 —— 整条查询的每一段都可能不在原文里
    // （AND 语义），全试完还是找不到就退回从开头截。
    // 切词与定位都走 Highlight.kt 里那一份定义，不在这里另写一遍。
    val hit = firstHit(flat, query)

    if (flat.length <= maxChars) {
        return TextWindow(flat, hit)
    }

    // 以命中位置为中心开窗。命中词比 maxChars 还长时（极端情况）
    // 这个 maxOf 会把窗口起点拉到命中处，至少能看到词的开头。
    val start = if (hit != null) {
        maxOf(0, hit.first - maxOf(0, (maxChars - hit.count()) / 2))
    } else {
        0
    }
    val end = minOf(flat.length, start + maxChars)
    // 窗口贴到尾部时右对齐，否则末尾会剩一小截空白
    val from = if (end == flat.length) maxOf(0, flat.length - maxChars) else start

    val prefix = if (from > 0) "…" else ""
    val suffix = if (end < flat.length) "…" else ""

    val range = if (hit != null && hit.first >= from && hit.last + 1 <= end) {
        val s = prefix.length + (hit.first - from)
        s until (s + hit.count())
    } else {
        // 命中词被窗口切掉了一半时不强调 —— 只标半个词比不标更让人困惑
        null
    }

    return TextWindow(prefix + flat.substring(from, end) + suffix, range)
}
