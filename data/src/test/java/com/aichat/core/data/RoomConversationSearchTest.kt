package com.aichat.core.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RoomConversationSearch] 的行为测试。
 *
 * 这里验证的是**编排逻辑**：什么时候根本不查库、用户输入怎么变成 FTS 表达式、
 * 投影怎么映射回领域对象。真正的 SQL（`LEFT JOIN`、`deleted = 0`、排序）
 * 由设备上的 `CjkSearchInstrumentedTest` 负责 —— 逻辑错误和 SQL 错误
 * 是两类问题，混在一起测只会让失败信息变得难读。
 */
class RoomConversationSearchTest {

    /**
     * 记录调用的假 DAO。
     *
     * 写操作一律 `error`：检索是只读的，这个类要是被用来写东西，
     * 说明实现跑偏了，应该当场炸而不是静默通过。
     */
    private class RecordingFtsDao(
        private val rows: List<MessageSearchHit> = emptyList(),
    ) : MessageFtsDao {

        var calls = 0
            private set
        var lastExpr: String? = null
            private set
        var lastLimit: Int? = null
            private set

        override suspend fun search(expr: String, limit: Int): List<MessageSearchHit> {
            calls++
            lastExpr = expr
            lastLimit = limit
            return rows
        }

        override suspend fun insert(entity: MessageFtsEntity) = error("检索不该写索引")
        override suspend fun delete(rowId: Long) = error("检索不该写索引")
        override suspend fun deleteIn(conversationId: String) = error("检索不该写索引")
        override suspend fun deleteFrom(conversationId: String, fromCreatedAt: Long, fromId: Long) =
            error("检索不该写索引")
        override suspend fun clear() = error("检索不该写索引")
    }

    private fun hit(
        rowId: Long = 1L,
        content: String = "帮我把会议纪要整理成表格",
        conversationId: String = "c1",
        createdAt: Long = 1000L,
        title: String = "会议相关",
    ) = MessageSearchHit(
        rowId = rowId,
        content = content,
        conversationId = conversationId,
        createdAt = createdAt,
        conversationTitle = title,
    )

    /**
     * 短路必须发生在**到达 DAO 之前**。
     *
     * 只断言「返回了空列表」是不够的 —— 那样一个「先查库、拿到空结果再返回」
     * 的实现也能过。所以这里断言 **DAO 一次都没被调用**。
     *
     * （这条短路的理由不是「防崩溃」：实测 `MATCH ''` 并不会抛异常。
     * 见 `ConversationSearch.search` 的 KDoc。）
     */
    @Test
    fun 空查询根本不碰数据库() = runTest {
        val dao = RecordingFtsDao()
        val search = RoomConversationSearch(dao)

        assertTrue(search.search("").isEmpty())
        assertTrue(search.search("   ").isEmpty())
        assertTrue(search.search("\n\t ").isEmpty())

        assertEquals("空查询不该产生任何数据库调用", 0, dao.calls)
        assertNull(dao.lastExpr)
    }

    /**
     * 用户输入必须经过 `CjkText.forQuery` 才交给 SQLite。
     *
     * 断言收到的**不是原文** —— 那正是「有没有转义」的分水岭。中文逐字索引下
     * 「会议纪要」应该变成带引号的短语 `"会 议 纪 要"`（保证字序相邻），
     * 而不是原样传下去。
     */
    @Test
    fun 用户输入被切成FTS表达式而不是原样下传() = runTest {
        val dao = RecordingFtsDao()
        val search = RoomConversationSearch(dao)

        search.search("会议纪要")

        assertEquals("\"会 议 纪 要\"", dao.lastExpr)
    }

    /**
     * 用户输入里的 FTS 元字符（`"`、`-`、`*`）不能把查询结构搞坏。
     *
     * 这条测的是**调用方不用管转义**这件事：界面把用户随手敲的东西直接传进来，
     * 不该出现「查一下就报错」或者「搜出来的东西莫名其妙」。
     */
    @Test
    fun 用户输入里的FTS元字符不会破坏查询() = runTest {
        val dao = RecordingFtsDao()
        val search = RoomConversationSearch(dao)

        val raw = "会议\" -纪要* OR"
        val hits = search.search(raw)

        // 不抛异常、能返回结果，且表达式确实是经过构造的（引号是成对包住的）
        assertTrue(hits.isEmpty())
        assertEquals(1, dao.calls)
        val expr = dao.lastExpr!!
        assertTrue("表达式不该是原文", expr != raw)
        // 元字符要么被引号包住、要么被拆成独立的段，不会以裸的语法字符出现
        assertTrue("不该有落单的引号：$expr", expr.count { it == '"' } % 2 == 0)
    }

    /**
     * `rowId` 要映射成 `messageId`（名字不一样，最容易写反），
     * 标题也要跟着出来 —— 没有标题的结果列表用户没法判断该点哪个。
     */
    @Test
    fun 投影字段映射到领域对象() = runTest {
        val dao = RecordingFtsDao(
            listOf(hit(rowId = 42L, conversationId = "conv-7", createdAt = 1712L, title = "会议相关"))
        )
        val search = RoomConversationSearch(dao)

        val result = search.search("会议")

        assertEquals(1, result.size)
        val only = result.single()
        assertEquals(42L, only.messageId)
        assertEquals("conv-7", only.conversationId)
        assertEquals("会议相关", only.conversationTitle)
        assertEquals("帮我把会议纪要整理成表格", only.content)
        assertEquals(1712L, only.createdAt)
    }

    /**
     * 标题为空串是「用户还没命名」，必须原样保留成空串。
     * 在这里替换成「新对话」的话，界面就再也分不清「没命名」和
     * 「真的叫新对话」了 —— 显示层的兜底属于显示层。
     */
    @Test
    fun 空标题原样保留不在这一层替换() = runTest {
        val dao = RecordingFtsDao(listOf(hit(title = "")))
        val search = RoomConversationSearch(dao)

        assertEquals("", search.search("会议").single().conversationTitle)
    }

    @Test
    fun limit透传给数据库() = runTest {
        val dao = RecordingFtsDao()
        val search = RoomConversationSearch(dao)

        search.search("会议", limit = 7)

        assertEquals(7, dao.lastLimit)
    }

    /** 不传 limit 时用接口约定的默认值，避免调用方各自发明一个数字。 */
    @Test
    fun 默认limit与接口约定一致() = runTest {
        val dao = RecordingFtsDao()
        val search = RoomConversationSearch(dao)

        search.search("会议")

        assertEquals(
            com.aichat.domain.search.ConversationSearch.DEFAULT_LIMIT,
            dao.lastLimit,
        )
    }
}
