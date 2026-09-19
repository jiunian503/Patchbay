package com.aichat.tools

import com.aichat.domain.search.ConversationSearch
import com.aichat.domain.search.MessageHit
import com.aichat.domain.tool.ToolResult
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型用的历史检索工具。
 *
 * ## 这个工具的失败模式和别的不一样
 *
 * 它的输出是**提示词的一部分** —— 模型只能看到 `ToolResult.content` 里那几行
 * 字，然后据此决定「这条记忆算不算数」。所以这里测的不是「有没有崩」，
 * 而是「模型能不能从这段文字里正确理解发生了什么」：
 *
 * - 空结果必须是 `ok` 而不是 `error`，否则模型会以为工具坏了，反复重试；
 * - 达到上限必须说出来，否则模型会把「取到的 10 条」当成「历史里只有这些」；
 * - 每条必须带**时间**和**会话**，否则模型无法判断新旧、也无法在回答里引用。
 *
 * 检索本身（切词、FTS 表达式）由 `ConversationSearch` 的实现负责，
 * 这里用假实现，只验证「参数怎么传下去、结果怎么拼上来」。
 */
class SearchHistoryToolTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-09-19T00:00:00Z，即北京时间 2026-09-19 08:00。 */
    private val fixedNow = Instant.parse("2026-09-19T00:00:00Z").toEpochMilli()

    private fun tool(search: ConversationSearch) =
        SearchHistoryTool(search = search, zone = zone, now = { fixedNow })

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                is String -> put(key, value)
                is Int -> put(key, value)
                else -> error("测试里只用到 String 和 Int")
            }
        }
    }

    private class FakeSearch(private val hits: List<MessageHit>) : ConversationSearch {
        var lastQuery: String? = null
        var lastLimit: Int? = null

        override suspend fun search(query: String, limit: Int): List<MessageHit> {
            lastQuery = query
            lastLimit = limit
            return hits.take(limit)
        }
    }

    private fun hit(
        id: Long = 1,
        title: String = "旅行计划",
        content: String = "北京今天 25°C，适合户外活动",
        at: Long = fixedNow - 3 * 86_400_000L,
    ) = MessageHit(
        messageId = id,
        conversationId = "conv-$id",
        conversationTitle = title,
        content = content,
        createdAt = at,
    )

    // ---------- 参数校验 ----------

    @Test
    fun `缺少 query 时返回错误并说清该怎么填`() = runTest {
        val result = tool(FakeSearch(emptyList())).execute(buildJsonObject { })

        assertTrue("缺参数必须报错，否则模型不知道自己填错了", result.isError)
        assertTrue("错误信息要点出参数名", result.content.contains("query"))
        // 错误信息是给模型读的，得顺手告诉它正确做法，否则它会原样重试
        assertTrue("错误信息要给出示例", result.content.contains("空格"))
    }

    /** `stringOrNull` 把空串和纯空白都过滤成 null，所以这两条走同一条路。 */
    @Test
    fun `query 是空白时也返回错误`() = runTest {
        val t = tool(FakeSearch(emptyList()))

        assertTrue(t.execute(args("query" to "")).isError)
        assertTrue(t.execute(args("query" to "   \n  ")).isError)
    }

    @Test
    fun `参数不对时根本不碰检索`() = runTest {
        val search = FakeSearch(emptyList())

        tool(search).execute(buildJsonObject { })

        assertNull("参数不合法时不该白跑一趟数据库", search.lastQuery)
    }

    // ---------- 空结果 ----------

    /**
     * 「搜了没找到」不是错误。
     *
     * 返回 `error` 的话模型会以为工具坏了，于是换个说法反复重试同一个查询 ——
     * 而正确答案是「确实没聊过」，它应该直接如实回答。
     */
    @Test
    fun `没有命中时返回成功而不是错误`() = runTest {
        val result = tool(FakeSearch(emptyList())).execute(args("query" to "量子计算"))

        assertFalse("空结果是正常情况，不是错误", result.isError)
        assertTrue(result.content.contains("量子计算"))
        // 要明确给出下一步，否则模型可能直接放弃
        assertTrue("要告诉模型可以换个词再试", result.content.contains("再试"))
    }

    // ---------- 结果渲染 ----------

    @Test
    fun `结果里带条数 序号 时间 会话名和正文`() = runTest {
        val search = FakeSearch(
            listOf(
                hit(id = 1, title = "旅行计划", content = "北京今天 25°C，适合户外活动"),
                hit(id = 2, title = "工作", content = "北京的会议改到周三", at = fixedNow - 86_400_000L),
            ),
        )

        val result = tool(search).execute(args("query" to "北京"))

        assertFalse(result.isError)
        val text = result.content
        assertTrue("要给出条数：$text", text.contains("找到 2 条"))
        assertTrue("要带查询词：$text", text.contains("北京"))
        assertTrue("第一条要有序号：$text", text.contains("[1]"))
        assertTrue("第二条要有序号：$text", text.contains("[2]"))
        assertTrue("要带会话名：$text", text.contains("旅行计划"))
        assertTrue("要带正文：$text", text.contains("适合户外活动"))
        assertTrue("要带绝对时间：$text", text.contains("2026-09-16 08:00"))
        assertTrue("要带相对时间：$text", text.contains("3 天前"))
        assertTrue("第二条是 1 天前：$text", text.contains("1 天前"))
    }

    /**
     * 会话标题为空时不能留一片空白 —— 模型会以为这条消息不属于任何会话。
     */
    @Test
    fun `没有标题的会话显示为未命名`() = runTest {
        val result = tool(FakeSearch(listOf(hit(title = "")))).execute(args("query" to "北京"))

        assertTrue(result.content.contains(SearchHistoryTool.UNTITLED))
    }

    /**
     * 长消息必须被截断，而且要截到**包含命中词**的那一段。
     *
     * 一条消息可能几千字，原样回灌会把上下文挤爆；截开头则可能让模型
     * 完全看不到它搜的那个词，于是判定「这条不相关」。
     */
    @Test
    fun `长消息被截断且保留命中词`() = runTest {
        val long = "铺垫".repeat(400) + "北京" + "收尾".repeat(400)

        val result = tool(FakeSearch(listOf(hit(content = long)))).execute(args("query" to "北京"))

        assertTrue("要截断：${result.content.length}", result.content.length < long.length)
        assertTrue("命中词必须留在片段里", result.content.contains("北京"))
        assertTrue("截断处要有省略号", result.content.contains("…"))
    }

    // ---------- limit ----------

    @Test
    fun `不传 limit 时用默认值`() = runTest {
        val search = FakeSearch(emptyList())

        tool(search).execute(args("query" to "北京"))

        assertEquals(SearchHistoryTool.DEFAULT_LIMIT, search.lastLimit)
    }

    @Test
    fun `limit 超过上限时被夹到上限`() = runTest {
        val search = FakeSearch(emptyList())

        tool(search).execute(args("query" to "北京", "limit" to 1000))

        assertEquals(SearchHistoryTool.MAX_LIMIT, search.lastLimit)
    }

    /**
     * `limit` 是 0 或负数时必须夹到 1，而不是原样传给检索。
     *
     * `LIMIT 0` 在 SQL 里合法但返回空，模型会得到「没找到」这个**错误结论**；
     * `LIMIT -1` 在 SQLite 里是「不限制」，直接把整个库倒出来。
     */
    @Test
    fun `limit 为零或负数时被夹到一`() = runTest {
        val zero = FakeSearch(emptyList())
        tool(zero).execute(args("query" to "北京", "limit" to 0))
        assertEquals(1, zero.lastLimit)

        val negative = FakeSearch(emptyList())
        tool(negative).execute(args("query" to "北京", "limit" to -1))
        assertEquals(1, negative.lastLimit)
    }

    /** 模型经常把数字写成字符串，`intOrNull` 要能接住。 */
    @Test
    fun `limit 是字符串数字时也能解析`() = runTest {
        val search = FakeSearch(emptyList())

        tool(search).execute(args("query" to "北京", "limit" to "5"))

        assertEquals(5, search.lastLimit)
    }

    /** 给了个解析不了的 limit 时退回默认值，而不是整次调用失败。 */
    @Test
    fun `limit 解析不了时退回默认值`() = runTest {
        val search = FakeSearch(emptyList())

        tool(search).execute(args("query" to "北京", "limit" to "很多"))

        assertEquals(SearchHistoryTool.DEFAULT_LIMIT, search.lastLimit)
    }

    // ---------- 上限提示 ----------

    /**
     * 取满上限时必须提示「可能还有更多」。
     *
     * 不说的话，模型会把「取到的这几条」当成「历史里只有这些」，
     * 然后拿一个不完整的印象当成全部事实回答用户。
     */
    @Test
    fun `结果取满上限时提示可能还有更多`() = runTest {
        val hits = (1..10L).map { hit(id = it, at = fixedNow - it * 86_400_000L) }

        val result = tool(FakeSearch(hits)).execute(args("query" to "北京"))

        assertTrue("要提示被截断：${result.content}", result.content.contains("可能还有更多"))
    }

    @Test
    fun `结果没取满时不提示被截断`() = runTest {
        val result = tool(FakeSearch(listOf(hit()))).execute(args("query" to "北京"))

        assertFalse(result.content.contains("可能还有更多"))
    }

    // ---------- 传下去的东西 ----------

    /**
     * 查询串**原样**传给检索层，工具自己不做切词。
     *
     * 切词和转义是 `ConversationSearch` 实现的职责（见它的 KDoc）。
     * 如果这里也处理一遍，就会出现两道防线各做各的 —— 而 FTS 的
     * `"`、`-`、`*` 是**语法**，处理两次的结果不是「更安全」而是「更难查」。
     */
    @Test
    fun `查询串原样传给检索层`() = runTest {
        val search = FakeSearch(emptyList())

        tool(search).execute(args("query" to "RikkaHub \"会议\""))

        assertEquals("RikkaHub \"会议\"", search.lastQuery)
    }

    // ---------- 相对时间 ----------

    @Test
    fun `相对时间按档位折算`() {
        assertEquals("刚刚", relativeTime(0))
        assertEquals("刚刚", relativeTime(59_000))
        assertEquals("1 分钟前", relativeTime(60_000))
        assertEquals("59 分钟前", relativeTime(59 * 60_000L))
        assertEquals("1 小时前", relativeTime(60 * 60_000L))
        assertEquals("23 小时前", relativeTime(23 * 3_600_000L))
        assertEquals("1 天前", relativeTime(24 * 3_600_000L))
        assertEquals("29 天前", relativeTime(29 * 86_400_000L))
        assertEquals("1 个月前", relativeTime(30 * 86_400_000L))
        assertEquals("1 年前", relativeTime(365 * 86_400_000L))
    }

    /**
     * 消息时间戳在未来（设备时钟被回拨过）时归到「刚刚」。
     *
     * 报「-3 天前」会让模型以为数据坏了，进而怀疑整个检索结果 ——
     * 而实际上内容是对的，只是时钟不准。
     */
    @Test
    fun `时间戳在未来时显示为刚刚`() {
        assertEquals("刚刚", relativeTime(-1))
        assertEquals("刚刚", relativeTime(-10 * 86_400_000L))
    }

    /** 相对时间在真实输出里也要出现，不能只在单测里对。 */
    @Test
    fun `一分钟内的消息在结果里显示为刚刚`() = runTest {
        val fresh = hit(at = fixedNow - 5_000)

        val result = tool(FakeSearch(listOf(fresh))).execute(args("query" to "北京"))

        assertTrue(result.content.contains("刚刚"))
    }

    /** 工具是只读的，绝不能要求确认 —— 每次弹窗等于没有记忆。 */
    @Test
    fun `不需要用户确认`() {
        val tool = tool(FakeSearch(emptyList()))

        assertFalse(tool.requiresConfirmation)
        assertTrue("要有面向用户的说明", tool.userSummary.length >= 8)
        assertFalse(
            "面向用户的说明不能直接抄模型描述",
            tool.userSummary == tool.definition.description,
        )
    }

    @Test
    fun `工具名和参数名是稳定的`() {
        assertEquals("search_history", SearchHistoryTool.NAME)
        assertEquals("query", SearchHistoryTool.QUERY)
        assertEquals("limit", SearchHistoryTool.LIMIT)

        val properties = tool(FakeSearch(emptyList())).definition.parameters["properties"]!!
        assertTrue(properties.toString().contains(SearchHistoryTool.QUERY))
        assertTrue(properties.toString().contains(SearchHistoryTool.LIMIT))
    }
}
