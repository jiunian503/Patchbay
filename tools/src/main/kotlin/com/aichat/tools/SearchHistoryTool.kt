package com.aichat.tools

import com.aichat.domain.search.ConversationSearch
import com.aichat.domain.search.MessageHit
import com.aichat.domain.text.textWindow
import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject

/**
 * 让**模型**检索历史对话（Hermes 三层记忆的第三层）。
 *
 * ## 为什么这是记忆，而不是「又一个查询工具」
 *
 * 模型每次请求只能看到当前会话的最近若干条消息。用户说「上次我们定的那个
 * 方案」，模型既没有那份上下文，也没法自己去找 —— 它只能道歉或者编。
 *
 * 用户侧其实早就有搜索框了（`ConversationSearchScreen`），但**用户搜得到
 * 不等于模型知道**。三层记忆里最上面那层之所以是「检索」，就是因为它要由
 * 模型在需要的时候自己发起，而不是等人来喂。
 *
 * ## 检索范围是**全部**会话，包括当前这个
 *
 * 不做「只看当前会话」的选项：当前会话的近期消息本来就在上下文里，
 * 模型真要看的是**别的**会话。但当前会话的消息也照样会被搜出来 ——
 * 长会话超出上下文窗口时，那些早期消息反而只能靠检索拿到。
 * 代价是偶尔会搜到刚才说过的话，那只是冗余，不是错误。
 *
 * ## 为什么不需要用户确认
 *
 * 它只读本地数据库、不联网、不改任何东西 —— 和 `device_info` 同一类。
 * 每次调用都弹窗的话，用户会习惯性地点「允许」，确认机制就退化成形式；
 * 更要紧的是**记忆的价值恰恰在于无缝**，要求确认等于没有记忆。
 *
 * 但要说清楚它带来的新隐私面：检索到的内容会随下一次请求发给模型服务商。
 * 以前模型只能看到当前会话，现在它能主动把**任何**历史对话拉进今天的上下文。
 * 这是产品形态的一部分（要长期记忆就必须这样），所以不该藏在每次弹窗里，
 * 而应该在设置里一次性说清楚。
 */
class SearchHistoryTool(
    private val search: ConversationSearch,
    /** 只影响展示给模型的时间文本。注入是为了让单测的输出可预期。 */
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** 计算「几天前」用的当前时刻。注入理由同上。 */
    private val now: () -> Long = System::currentTimeMillis,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        description = "搜索用户过去的对话记录（跨全部历史会话，不只是当前这个）。" +
            "当用户提到「上次」「之前」「你还记得」这类指向历史对话的内容，" +
            "或者需要用户的长期信息（偏好、项目、之前定下的结论、个人信息）" +
            "而在当前对话里找不到时，调用它。" +
            "query 要用**具体名词**（项目名、地名、人名、产品名），" +
            "不要用「那个东西」「之前说的」这类指代 —— 检索是关键词匹配，" +
            "指代词匹配不到任何东西。",
        parameters = schema(
            properties = mapOf(
                QUERY to stringParam(
                    "要搜索的关键词。用具体的名词，多个词之间用空格分隔" +
                        "（表示每个词都要出现）。不要写完整的问句。",
                ),
                LIMIT to integerParam(
                    "返回条数上限，默认 $DEFAULT_LIMIT，最多 $MAX_LIMIT。一般不用填。",
                ),
            ),
            required = listOf(QUERY),
        ),
    )

    override val userSummary: String
        get() = "搜索你过去的所有对话记录，把命中的片段读给模型（只读本地数据，不联网）"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        // `stringOrNull` 已经把空串和纯空白过滤成 null 了，所以这里一条判断
        // 同时挡住「没给 query」和「给了一个空 query」。
        val query = arguments.stringOrNull(QUERY)
            ?: return ToolResult.error(
                "缺少 query 参数。请给出要搜索的关键词，" +
                    "例如项目名、地名、人名，多个词用空格分隔。",
            )

        // 模型给的数字不可信（可能是 "3"、可能是 1000）。
        // clamp 而不是报错：多要几条不值得让整次调用失败。
        val limit = (arguments.intOrNull(LIMIT) ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val hits = search.search(query, limit)

        // 空结果**不是错误**。返回 error 会让模型以为工具坏了，
        // 于是换个说法反复重试同一个查询 —— 而正确答案是「确实没聊过」。
        if (hits.isEmpty()) {
            return ToolResult.ok(
                "没有找到包含「$query」的历史消息。" +
                    "可以换一个更具体或更常见的关键词再试一次；" +
                    "如果仍然没有，就说明用户过去没有聊过这个，直接如实回答。",
            )
        }

        return ToolResult.ok(render(hits, query, limit))
    }

    private fun render(hits: List<MessageHit>, query: String, limit: Int): String = buildString {
        append("找到 ${hits.size} 条包含「$query」的历史消息")
        // 达到上限时必须说出来。不说的话模型会认为「历史里就只有这些」，
        // 然后拿一个不完整的印象当成全部事实。
        if (hits.size >= limit) {
            append("（已取到本次上限 $limit 条，可能还有更多）")
        }
        appendLine("：")

        hits.forEachIndexed { index, hit ->
            appendLine()
            appendLine(
                "[${index + 1}] ${describeTime(hit.createdAt)}" +
                    " · 会话「${hit.conversationTitle.ifBlank { UNTITLED }}」",
            )
            appendLine(textWindow(hit.content, query, CONTENT_WINDOW_CHARS).text)
        }
    }.trimEnd()

    /**
     * 绝对时间 + 相对时间都要给。
     *
     * 只有绝对时间的话，模型要自己算「2026-09-16 离现在多久」—— 它算这个
     * 经常出错，而「多久之前」恰恰是判断「这条记忆还算不算数」的关键。
     * 只有相对时间则没法让模型在回答里引用具体日期。
     */
    private fun describeTime(at: Long): String {
        val zoned = Instant.ofEpochMilli(at).atZone(zone)
        return "${zoned.format(TIME_FORMAT)}（${relativeTime(now() - at)}）"
    }

    companion object {
        const val NAME = "search_history"
        const val QUERY = "query"
        const val LIMIT = "limit"

        /** 10 条 × 每条约 400 字，大致 2000 token —— 值得花，再多就挤占对话了。 */
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30

        /**
         * 给模型的窗口比给用户的（120 字）大得多。
         *
         * 用户只需要认出「是不是这条」，模型要靠这段文字判断「这条有没有用」，
         * 所以得给它足够的上下文。
         */
        const val CONTENT_WINDOW_CHARS = 400

        /**
         * 会话没有标题时给模型看的占位。
         *
         * 刻意**不**用界面上的「新对话」：那是给用户看的（对用户而言「新」
         * 是相对他当下的时间感），模型需要的是「这个会话没有名字」这个事实。
         */
        const val UNTITLED = "未命名"

        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
private const val MONTH = 30 * DAY
private const val YEAR = 365 * DAY

/**
 * 把时间差说成人话。
 *
 * 负数（消息时间戳在未来，通常意味着设备时钟被回拨过）归到「刚刚」——
 * 报「-3 天前」会让模型以为数据坏了，进而怀疑整个检索结果。
 *
 * 月份按 30 天、年份按 365 天折算。这是**给人（和模型）读的近似值**，
 * 不是精确的日历计算；精确日期在上面那半句里已经有了。
 */
internal fun relativeTime(deltaMillis: Long): String = when {
    deltaMillis < MINUTE -> "刚刚"
    deltaMillis < HOUR -> "${deltaMillis / MINUTE} 分钟前"
    deltaMillis < DAY -> "${deltaMillis / HOUR} 小时前"
    deltaMillis < MONTH -> "${deltaMillis / DAY} 天前"
    deltaMillis < YEAR -> "${deltaMillis / MONTH} 个月前"
    else -> "${deltaMillis / YEAR} 年前"
}
