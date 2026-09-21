package com.aichat.domain.prompt

import com.aichat.domain.text.CjkText

/**
 * 世界书的一个条目：**一组触发词** + 命中后要追加进系统提示词的内容。
 *
 * 就是 SillyTavern 里 world info / lorebook 那个概念：用户把世界观设定拆成
 * 一条条词条，只有聊到相关的词时才把对应内容塞进上下文。好处是不聊到就
 * 不占 token —— 一本写了两万字的设定集，实际每轮可能只激活两三条。
 *
 * ## 为什么触发词是「一组」而不是一个
 *
 * 同一个设定往往有好几种叫法：「老王」「王叔」「王建国」指的是同一个人。
 * 拆成三条的话，用户改一次设定要改三处，而且三份内容迟早会分叉。
 *
 * ## [keys] 为空 / [content] 为空的条目会被跳过
 *
 * 见 [WorldBookMatcher.match]。用户新加一条还没来得及填词，界面不该
 * 因此报错 —— 但它也不该在词填上之前就生效。
 *
 * @property id 稳定标识。同一个会话里两条内容相同的词条不该被当成一条。
 * @property keys 触发词。**任一命中**即激活这个条目。
 * @property content 命中后追加进系统提示词的内容。
 * @property enabled 关掉之后不再参与匹配，但词条本身留着 —— 用户想临时
 *   停用一条设定时，删掉再重新打一遍是最糟的交互。
 * @property orderIndex 注入顺序，小的在前。相同时按 [id] 排，保证稳定 ——
 *   顺序不稳定的后果是同一个会话两次发送得到不同的提示词，
 *   而这类差异极难排查（模型行为变了，但看不出提示词变了）。
 * @property caseSensitive 触发词是否区分大小写。默认不区分。
 */
data class WorldBookEntry(
    val id: String,
    val keys: List<String>,
    val content: String,
    val enabled: Boolean = true,
    val orderIndex: Int = 0,
    val caseSensitive: Boolean = false,
)

/**
 * 从一堆词条里挑出「当前上下文命中的那些」。
 *
 * ## 输入为什么是 `List<String>` 而不是消息对象
 *
 * `:domain` 不认识 `ChatMessage`（那是 `:chat` 的类型），也不该认识 ——
 * 这里只关心「有哪些文本要扫」。调用方把该扫的文本挑出来传进来，
 * 于是这个匹配器既不需要知道消息结构，也不需要知道历史是怎么存的，
 * 单测里直接给几个字符串就能穷举。
 *
 * ## 扫哪些文本由调用方决定
 *
 * 现在的调用方（`:app` 的 `SystemPromptSource`）扫的是**引擎真正会发出去的
 * 那段历史**里的用户与助手正文，跳过工具结果 —— 工具结果是机器产生的
 * （一段 JSON、一个网页摘要），里面的词不该激活用户手写的世界观设定。
 * 而且工具结果动辄几千字，拿它去扫等于把世界书变成随机触发器。
 *
 * ## 匹配规则：中文按子串，纯拉丁按词边界
 *
 * 这一条是刻意的不对称，两个方向都有真实理由：
 *
 * - **含 CJK 的触发词走子串匹配**。中文没有词边界，「北京」本来就该命中
 *   「去北京玩」和「北京大学」。硬加 `\b` 反而会让最常见的输入匹配不上。
 * - **纯拉丁 / 数字的触发词加词边界**。不加的话触发词 `AI` 会在 `said`、
 *   `wait`、`again` 里命中 —— 而这类两三个字母的缩写在做英文设定时非常
 *   常见，世界书会到处乱触发，用户完全看不出为什么。
 */
object WorldBookMatcher {

    /**
     * 一次注入的字符上限。
     *
     * 4000 字符 ≈ 3000–4000 token（中文按 0.6–1 token/字估算），
     * 对 8K 以上上下文的模型是安全的，又不至于让用户觉得「写了十条只生效两条」。
     *
     * 这个上限防的是**词条一多就把上下文撑爆**，不是防「用户只写了一条」——
     * 所以第一条命中永远保留，见 [takeWithinBudget]。
     */
    const val DEFAULT_BUDGET_CHARS = 4000

    /**
     * 按 [texts] 匹配出要注入的词条，已排序、已按预算截断。
     *
     * @param texts 要扫描的文本。空串会被忽略。
     * @param budgetChars 注入内容的字符上限，见 [DEFAULT_BUDGET_CHARS]。
     */
    fun match(
        entries: List<WorldBookEntry>,
        texts: List<String>,
        budgetChars: Int = DEFAULT_BUDGET_CHARS,
    ): List<WorldBookEntry> {
        if (entries.isEmpty() || texts.isEmpty()) return emptyList()

        val corpus = texts.filter { it.isNotEmpty() }
        if (corpus.isEmpty()) return emptyList()

        val candidates = entries
            .filter { it.enabled }
            .filter { entry -> entry.content.isNotBlank() && entry.keys.any { it.isNotBlank() } }
            .sortedWith(ORDER)
        if (candidates.isEmpty()) return emptyList()

        // 拉丁触发词的正则编译一次、整个 match 里复用。
        // 扫全部历史 × 几十个词条时，「每个词条都重新编译一遍同一个正则」
        // 是纯粹浪费 —— 而这正好是最常走的那条路。
        val latinCache = HashMap<LatinKey, Regex>()

        val hits = candidates.filter { entry ->
            entry.keys.any { key ->
                key.isNotBlank() && corpus.any { text -> contains(text, key, entry.caseSensitive, latinCache) }
            }
        }

        return takeWithinBudget(hits, budgetChars)
    }

    /** [orderIndex] 相同时按 [WorldBookEntry.id] 兜底，让顺序完全确定。 */
    private val ORDER = compareBy<WorldBookEntry>({ it.orderIndex }, { it.id })

    /**
     * 一条触发词是否出现在 [text] 里。
     *
     * 见类注释里「匹配规则」那节：含 CJK 走子串，纯拉丁走词边界。
     */
    private fun contains(
        text: String,
        key: String,
        caseSensitive: Boolean,
        cache: MutableMap<LatinKey, Regex>,
    ): Boolean {
        if (key.any { CjkText.isCjk(it) }) {
            return text.contains(key, ignoreCase = !caseSensitive)
        }

        // `\b` 只认 ASCII 字母数字，所以这一支只对纯拉丁/数字的触发词成立。
        // 大小写敏感与否是编译期选项，所以它也得进缓存的键。
        val regex = cache.getOrPut(LatinKey(key, caseSensitive)) {
            Regex(
                pattern = "\\b" + Regex.escape(key) + "\\b",
                options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE),
            )
        }
        return regex.containsMatchIn(text)
    }

    /**
     * 按顺序累加内容长度，超预算就停。
     *
     * **第一条命中的词条无条件保留**：预算是防「词条一多撑爆上下文」，
     * 不是防「用户只写了一条」。让预算小到把唯一命中的条目也砍掉，
     * 用户看到的是「我明明配了却没生效」，而界面上没有任何解释 ——
     * 这是最难自查的一类故障。
     *
     * 截断按整条进行，不切内容。半截的世界观设定比没有更糟：
     * 模型会照着半句话编下去。
     */
    private fun takeWithinBudget(
        entries: List<WorldBookEntry>,
        budgetChars: Int,
    ): List<WorldBookEntry> {
        if (entries.isEmpty()) return emptyList()

        val kept = ArrayList<WorldBookEntry>(entries.size)
        var used = 0
        for (entry in entries) {
            val cost = entry.content.length
            if (kept.isNotEmpty() && used + cost > budgetChars) break
            kept += entry
            used += cost
        }
        return kept
    }

    /** 拉丁触发词的正则缓存键 —— 同一个词、不同的大小写策略是两个不同的正则。 */
    private data class LatinKey(val pattern: String, val caseSensitive: Boolean)
}
