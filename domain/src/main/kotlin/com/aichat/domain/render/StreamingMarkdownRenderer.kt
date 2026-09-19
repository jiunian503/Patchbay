package com.aichat.domain.render

/**
 * 流式 Markdown 的渲染调度。
 *
 * ── 先说结论（实测依据见 spike/RESULT-streaming.md）────────────────────
 *
 * 不需要容错解析器。实测表明：未闭合的代码围栏，现成解析器本来就处理正确
 * （闭合前后逐字节相同，零抖动）；表格与链接的重排只影响自身所在行，
 * 上方的已渲染内容从不跳动。最坏情况是链接闭合时约 66 字符的变化，局限在最后一行。
 *
 * ── 所以这个类解决的不是「抖动」，而是「性能」──────────────────────
 *
 * 每次增量都重新解析整条消息，总开销是 O(n²)：
 * 一条 5,000 字符的回复 × 5,000 个增量 ≈ 1,250 万字符的解析量，手机上会掉帧。
 *
 * 做法：找出最后一个稳定的块边界，边界之前逐块解析并缓存，之后不再重解析。
 * 实测字符处理量降到 26.3%（spike/renderer_check.js）。
 *
 * ── 缓存的是「块」，不是「渲染结果」──────────────────────────────
 *
 * 早期版本缓存的是解析出来的 HTML 字符串。改成块列表之后：
 *
 * - 主题、字号、代码配色变了，缓存的块**不需要失效** —— 渲染层重新画就行。
 *   缓存 HTML 的话，换主题得整个重置。
 * - 一致性测试可以比较**结构**（`assertEquals(List<MarkdownBlock>)`），
 *   失败时 diff 直接指出是哪一块不一样；比较 HTML 字符串只能看到两个长串不同。
 *
 * 纯 Kotlin，零 Android 依赖 —— 放在 :domain 模块，单测不需要模拟器。
 */
class StreamingMarkdownRenderer(
    private val parser: MarkdownParser = MarkdownParser.Default,
    /** 尾部超过这个长度就强制切分，防止「整条回复是一个超长段落」退化成 O(n²)。 */
    private val maxTailLength: Int = 2_000,
) {

    private val sealed = mutableListOf<MarkdownBlock>()
    private var sealedUpTo = 0

    /** 累计解析次数，供性能测试断言用。 */
    var parseCount: Int = 0
        private set

    /**
     * 用**完整原文**刷新。
     *
     * 传完整文本而不是增量，是为了让调用方不必维护拼接状态，
     * 也便于重试、编辑、重新生成时直接复用同一个实例。
     *
     * 返回的是**完整**块列表（已封块 + 尾部重解析），调用方直接渲染即可。
     */
    fun render(fullText: String): List<MarkdownBlock> {
        val boundary = stableBoundary(fullText)
        if (boundary > sealedUpTo) {
            sealed += parser.parse(fullText.substring(sealedUpTo, boundary))
            sealedUpTo = boundary
            parseCount++
        }

        val tail = fullText.substring(sealedUpTo)
        if (tail.isEmpty()) return sealed.toList()

        val head = oversizedHead(tail)
        if (head.isNotEmpty()) {
            sealed += parser.parse(head)
            sealedUpTo += head.length
            parseCount++
            return sealed + parser.parse(fullText.substring(sealedUpTo))
        }

        parseCount++
        return sealed + parser.parse(tail)
    }

    /** 重新生成 / 编辑时重置。 */
    fun reset() {
        sealed.clear()
        sealedUpTo = 0
        parseCount = 0
    }

    /**
     * 尾部超长时，从上限处往回找最近的句末标点或换行，避免把一句话劈成两半。
     *
     * **起点必须是 `maxTailLength`，不能是 `tail.length - 1`。**
     * 从末尾往回找的话，第一个撞上的往往就是最后一个字符本身
     * （回复通常以句号结尾），于是切点落在字符串末尾 ——
     * 结果是「什么都没切」，超长尾部的保护形同不存在。
     */
    private fun oversizedHead(tail: String): String {
        if (tail.length <= maxTailLength) return ""
        val floor = maxTailLength / 2
        var cut = -1
        var i = (maxTailLength - 1).coerceAtMost(tail.length - 1)
        while (i >= floor) {
            val c = tail[i]
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.') {
                cut = i
                break
            }
            i--
        }
        val at = if (cut >= 0) cut + 1 else maxTailLength
        return tail.substring(0, at.coerceAtMost(tail.length))
    }

    companion object {

        private val LIST_ITEM = Regex("^([-*+]|\\d+[.)])\\s+")

        /**
         * 返回最后一个**稳定块边界**的偏移：该位置之前的块不会再变化。
         *
         * 边界来源：空行，以及代码围栏的闭合行之后。
         *
         * 两条排除规则，都是踩过的坑：
         *
         * 1. **围栏内部的空行不算边界**。否则代码块会被从中间劈开，
         *    前后两半各自独立解析，结果都与完整代码块不一致。
         *
         * 2. **列表上下文内不封块**。CommonMark 里列表项之间可以有空行
         *    （松散列表），空行并不结束列表。若在此封块，
         *    `- 甲\n\n- 乙` 会被解析成两个独立的列表，第二个还会丢掉段落包裹。
         *    实测 13 个用例中，未加此规则时有 3 个产生不一致的结果。
         *
         * `internal` 而不是 `private`：一致性测试要拿它算分块位置。
         */
        internal fun stableBoundary(text: String): Int {
            var boundary = 0
            var inFence = false
            var fenceChar = '\u0000'
            var fenceLen = 0
            var listIndent = -1
            var blankPending = false
            var i = 0

            while (i < text.length) {
                val nl = text.indexOf('\n', i)
                if (nl == -1) break
                val line = text.substring(i, nl)
                val after = nl + 1

                val fence = matchFence(line)
                if (fence != null) {
                    val (ch, len) = fence
                    if (!inFence) {
                        inFence = true
                        fenceChar = ch
                        fenceLen = len
                    } else if (ch == fenceChar && len >= fenceLen) {
                        inFence = false
                        boundary = after
                    }
                    blankPending = false
                    i = after
                    continue
                }

                if (inFence) {
                    i = after
                    continue
                }

                val trimmed = line.trimStart()
                val indent = line.length - trimmed.length
                val isBlank = trimmed.isEmpty()

                if (isBlank) {
                    blankPending = true
                    // 只有不在列表里，空行才是安全的块边界
                    if (listIndent == -1) boundary = after
                } else {
                    if (LIST_ITEM.containsMatchIn(trimmed)) {
                        if (listIndent == -1 || indent <= listIndent) listIndent = indent
                    } else if (listIndent != -1 && blankPending && indent <= listIndent) {
                        // 空行之后的非列表行，且缩进不深于列表 —— 列表结束
                        listIndent = -1
                    }
                    blankPending = false
                }

                i = after
            }

            return boundary.coerceIn(0, text.length)
        }

        /** 识别围栏行，返回 (围栏字符, 连续长度)；不是围栏行返回 null。 */
        private fun matchFence(line: String): Pair<Char, Int>? {
            var k = 0
            while (k < line.length && k < 3 && line[k] == ' ') k++
            if (k >= line.length) return null
            val ch = line[k]
            if (ch != '`' && ch != '~') return null
            var n = 0
            while (k + n < line.length && line[k + n] == ch) n++
            return if (n >= 3) ch to n else null
        }
    }
}
