package com.aichat.core.data

import com.aichat.chat.ChatMessage
import com.aichat.chat.MessageCursor
import com.aichat.chat.MessageStatus
import com.aichat.chat.StoredMessage
import com.aichat.domain.llm.AssistantToolCall
import com.aichat.domain.text.CjkText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RoomConversationStore] 的行为测试。
 *
 * 这里验证的全是**事务边界与状态机**，不是 SQL —— SQL 由设备上的
 * instrumented test 负责。最要紧的三件事：
 *
 * 1. 主表与 FTS 表必须一起写、一起删（漏一半就是静默的功能缺失）
 * 2. `streaming` 不能进索引（内容还在变）
 * 3. 删除会话时 `fts.deleteIn` 必须排在 `hardDeleteIn` **前面**
 */
class RoomConversationStoreTest {

    private class Env {
        val messages = FakeMessageDao()
        val fts = FakeFtsDao(messages)
        val conversations = FakeConversationDao(messages)
        var now = 10_000L
        val store = RoomConversationStore(
            messages = messages,
            fts = fts,
            conversations = conversations,
            tx = NoTransactionRunner,
            clock = { now },
        )
    }

    private fun stored(
        id: Long,
        text: String,
        status: MessageStatus = MessageStatus.Complete,
        role: ChatMessage.Role = ChatMessage.Role.User,
        conversationId: String = "c1",
        createdAt: Long = id,
        toolCalls: List<AssistantToolCall> = emptyList(),
        toolCallId: String? = null,
        error: String? = null,
    ) = StoredMessage(
        id = id,
        conversationId = conversationId,
        message = ChatMessage(role = role, content = text, toolCalls = toolCalls, toolCallId = toolCallId),
        status = status,
        error = error,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    // ------------------------------------------------------------ 索引同步

    @Test
    fun `streaming 状态不写索引`() = runTest {
        val env = Env()

        env.store.save(stored(1L, "正在生成的半句话", status = MessageStatus.Streaming))

        assertEquals(1, env.messages.rows.size)
        assertTrue("半截内容不该进索引", env.fts.rows.isEmpty())
    }

    @Test
    fun `complete 状态写入切分后的索引`() = runTest {
        val env = Env()

        env.store.save(stored(1L, "帮我把会议纪要整理成表格"))

        val body = env.fts.rows.getValue(1L).body
        assertEquals(CjkText.forIndex("帮我把会议纪要整理成表格"), body)
        // 索引文本汉字间插了空格，**绝不能**直接拿去显示 —— 存错了就会在
        // 界面上出现「帮 我 把 会 议…」这种结果
        assertNotEquals("帮我把会议纪要整理成表格", body)
    }

    @Test
    fun `从 streaming 变成 complete 时补建索引`() = runTest {
        val env = Env()

        env.store.save(stored(1L, "半句", status = MessageStatus.Streaming))
        assertTrue(env.fts.rows.isEmpty())

        env.store.save(stored(1L, "半句说完了", status = MessageStatus.Complete))

        assertEquals(CjkText.forIndex("半句说完了"), env.fts.rows.getValue(1L).body)
    }

    @Test
    fun `stopped 与 failed 也进索引`() = runTest {
        val env = Env()

        env.store.save(stored(1L, "用户停在这", status = MessageStatus.Stopped))
        env.store.save(stored(2L, "报错前的内容", status = MessageStatus.Failed))

        // 屏幕上看得到的内容就该搜得到，跟它是怎么结束的无关
        assertEquals(CjkText.forIndex("用户停在这"), env.fts.rows.getValue(1L).body)
        assertEquals(CjkText.forIndex("报错前的内容"), env.fts.rows.getValue(2L).body)
    }

    @Test
    fun `正文为空的 complete 清掉索引`() = runTest {
        val env = Env()
        env.store.save(stored(1L, "先有内容"))
        assertEquals(1, env.fts.rows.size)

        // 纯工具调用的 assistant 消息：有 tool_calls 但没正文
        env.store.save(
            stored(
                id = 1L,
                text = "",
                role = ChatMessage.Role.Assistant,
                toolCalls = listOf(AssistantToolCall("call_a", "get_weather", "{}")),
            )
        )

        assertTrue("空 body 的索引行会让 MATCH 命中一个空结果", env.fts.rows.isEmpty())
        // 但主表里的消息还在，tool_calls 也还在
        assertEquals(1, env.messages.rows.getValue(1L).toolCallsJson?.let { 1 } ?: 0)
    }

    @Test
    fun `同一 id 重复保存是覆盖而不是插入`() = runTest {
        val env = Env()

        env.store.save(stored(1L, "第一版"))
        env.store.save(stored(1L, "第二版"))

        assertEquals(1, env.messages.rows.size)
        assertEquals("第二版", env.messages.rows.getValue(1L).content)
        assertEquals(CjkText.forIndex("第二版"), env.fts.rows.getValue(1L).body)
    }

    @Test
    fun `工具消息能落盘并进索引`() = runTest {
        val env = Env()

        env.store.save(
            stored(
                id = 5L,
                text = "北京今天 25 度 晴",
                role = ChatMessage.Role.Tool,
                toolCallId = "call_a",
            )
        )

        assertEquals("call_a", env.messages.rows.getValue(5L).toolCallId)
        assertEquals(CjkText.forIndex("北京今天 25 度 晴"), env.fts.rows.getValue(5L).body)
    }

    // ------------------------------------------------------------ 删除

    @Test
    fun `软删一条消息同时清索引`() = runTest {
        val env = Env()
        env.store.save(stored(1L, "要删掉的"))
        env.now = 20_000L

        env.store.delete("c1", 1L)

        assertTrue(env.messages.rows.getValue(1L).deleted)
        assertEquals(20_000L, env.messages.rows.getValue(1L).updatedAt)
        assertTrue(env.fts.rows.isEmpty())
    }

    @Test
    fun `会话 id 对不上时什么都不做`() = runTest {
        val env = Env()
        env.store.save(stored(1L, "别人的消息", conversationId = "c2"))

        env.store.delete("c1", 1L)

        assertTrue("传错会话 id 不该误删别的会话里的消息", !env.messages.rows.getValue(1L).deleted)
        assertEquals(1, env.fts.rows.size)
    }

    @Test
    fun `清空会话是软删主表加清索引`() = runTest {
        val env = Env()
        env.store.save(stored(1L, "a"))
        env.store.save(stored(2L, "b"))
        env.store.save(stored(3L, "别的会话", conversationId = "c2"))

        env.store.clearMessages("c1")

        assertTrue(env.messages.rows.getValue(1L).deleted)
        assertTrue(env.messages.rows.getValue(2L).deleted)
        assertTrue(!env.messages.rows.getValue(3L).deleted)
        assertEquals(setOf(3L), env.fts.rows.keys)
    }

    @Test
    fun `删除会话时索引必须清干净`() = runTest {
        val env = Env()
        env.store.save(stored(1L, "a"))
        env.store.save(stored(2L, "b"))

        env.store.deleteConversation("c1")

        // 这条断言拦的是「先硬删主表、再删索引」的写法：
        // fts.deleteIn 的 SQL 是子查询 `SELECT id FROM message WHERE conversation_id = ?`，
        // 主表先删掉就一条都查不到，索引行会全部残留成幽灵记录。
        assertTrue("索引行残留了 —— deleteIn 排在了 hardDeleteIn 后面", env.fts.rows.isEmpty())
        assertTrue(env.messages.rows.isEmpty())
        assertNull(env.conversations.rows["c1"])
    }

    @Test
    fun `删除会话不影响别的会话`() = runTest {
        val env = Env()
        env.store.save(stored(1L, "c1 的消息"))
        env.store.save(stored(2L, "c2 的消息", conversationId = "c2"))

        env.store.deleteConversation("c1")

        assertEquals(setOf(2L), env.messages.rows.keys)
        assertEquals(setOf(2L), env.fts.rows.keys)
    }

    // ------------------------------------------------------------ 上下文装配

    @Test
    fun `history 按时间正序返回`() = runTest {
        val env = Env()
        env.store.save(stored(30L, "第三句", createdAt = 300L))
        env.store.save(stored(10L, "第一句", createdAt = 100L))
        env.store.save(stored(20L, "第二句", createdAt = 200L))

        val texts = env.store.history("c1").map { it.content }

        assertEquals(listOf("第一句", "第二句", "第三句"), texts)
    }

    @Test
    fun `history 排除 streaming 与已删除`() = runTest {
        val env = Env()
        env.store.save(stored(1L, "正常一问"))
        env.store.save(stored(2L, "还没写完", status = MessageStatus.Streaming))
        env.store.save(stored(3L, "删掉的"))
        env.store.delete("c1", 3L)

        val texts = env.store.history("c1").map { it.content }

        assertEquals(listOf("正常一问"), texts)
    }

    @Test
    fun `history 取最近 N 条后仍按正序返回`() = runTest {
        val env = Env()
        (1..5).forEach { env.store.save(stored(it.toLong(), "第 $it 句", createdAt = it * 100L)) }

        val texts = env.store.history("c1", limit = 3).map { it.content }

        // 取的是最近 3 条（3/4/5），但顺序必须是正序 —— 反了模型会读成
        // 「第 5 句、第 4 句、第 3 句」这种一问一答颠倒的对话
        assertEquals(listOf("第 3 句", "第 4 句", "第 5 句"), texts)
    }

    @Test
    fun `transcript 包含未完成的消息而 history 不包含`() = runTest {
        val env = Env()
        env.store.save(stored(1L, "正常"))
        env.store.save(stored(2L, "半截", status = MessageStatus.Streaming))
        env.store.save(stored(3L, "删掉的"))
        env.store.delete("c1", 3L)

        // 一个给模型看（要干净），一个给用户看（要完整）
        assertEquals(listOf("正常"), env.store.history("c1").map { it.content })
        assertEquals(
            listOf("正常", "半截"),
            env.store.transcript("c1").messages.map { it.message.content },
        )
    }

    @Test
    fun `transcript 保留状态与失败原因`() = runTest {
        val env = Env()
        env.store.save(stored(1L, "被中断的半句", status = MessageStatus.Stopped))
        env.store.save(stored(2L, "出错了", status = MessageStatus.Failed, error = "HTTP 401"))

        val rows = env.store.transcript("c1").messages
        assertEquals(MessageStatus.Stopped, rows[0].status)
        assertEquals(MessageStatus.Failed, rows[1].status)
        assertEquals("HTTP 401", rows[1].error)
    }

    @Test
    fun `transcript 与 history 都用 id 作为同毫秒的稳定次序`() = runTest {
        val env = Env()
        // 同一毫秒连写两条：用户消息 + assistant 占位（真实场景就是这样）
        env.store.save(stored(1L, "用户问", createdAt = 5_000L))
        env.store.save(stored(2L, "助手答", role = ChatMessage.Role.Assistant, createdAt = 5_000L))

        // created_at 相等时，若不带 id 排序，SQLite 的返回顺序是未定义的 ——
        // 一旦颠倒，模型读到的就是一问一答反过来的对话
        assertEquals(
            listOf("用户问", "助手答"),
            env.store.history("c1").map { it.content },
        )
    }

    @Test
    fun `history 还原角色 思维链与工具调用`() = runTest {
        val env = Env()
        env.store.save(
            StoredMessage(
                id = 1L,
                conversationId = "c1",
                message = ChatMessage.assistant(
                    content = "我查一下",
                    toolCalls = listOf(AssistantToolCall("call_a", "get_weather", """{"city":"北京"}""")),
                ).copy(reasoning = "先看天气"),
                status = MessageStatus.Complete,
                createdAt = 1L,
            )
        )
        env.store.save(stored(2L, "结果", role = ChatMessage.Role.Tool, toolCallId = "call_a", createdAt = 2L))

        val history = env.store.history("c1")

        assertEquals(ChatMessage.Role.Assistant, history[0].role)
        assertEquals("先看天气", history[0].reasoning)
        assertEquals("call_a", history[0].toolCalls.single().id)
        assertEquals("""{"city":"北京"}""", history[0].toolCalls.single().argumentsJson)
        assertEquals(ChatMessage.Role.Tool, history[1].role)
        assertEquals("call_a", history[1].toolCallId)
    }

    // ------------------------------------------------------------ 会话表

    @Test
    fun `ensureConversation 幂等且不覆盖已有标题`() = runTest {
        val env = Env()

        env.store.ensureConversation("c1", title = "", now = 100L)
        env.store.titleIfUntitled("c1", "用户改过的标题", 200L)
        env.now = 300L
        env.store.ensureConversation("c1", title = "", now = 300L)

        val row = env.conversations.rows.getValue("c1")
        assertEquals("用户改过的标题", row.title)
        assertEquals(100L, row.createdAt)
        assertEquals(300L, row.updatedAt)
    }

    @Test
    fun `titleIfUntitled 只在标题为空时生效`() = runTest {
        val env = Env()
        env.store.ensureConversation("c1", title = "", now = 100L)

        env.store.titleIfUntitled("c1", "第一次的问题", 200L)
        env.store.titleIfUntitled("c1", "第二次的问题", 300L)

        assertEquals("第一次的问题", env.conversations.rows.getValue("c1").title)
    }

    @Test
    fun `空标题不写库`() = runTest {
        val env = Env()
        env.store.ensureConversation("c1", title = "", now = 100L)

        env.store.titleIfUntitled("c1", "   ", 200L)

        assertEquals("", env.conversations.rows.getValue("c1").title)
    }

    @Test
    fun `会话列表带上消息数且排除已删除`() = runTest {
        val env = Env()
        env.store.ensureConversation("c1", title = "甲", now = 100L)
        env.store.ensureConversation("c2", title = "乙", now = 200L)
        env.store.save(stored(1L, "a", conversationId = "c1", createdAt = 1L))
        env.store.save(stored(2L, "b", conversationId = "c1", createdAt = 2L))
        env.store.save(stored(3L, "c", conversationId = "c1", createdAt = 3L))
        env.store.delete("c1", 3L)

        val summaries = env.store.listConversations()

        assertEquals(listOf("c2", "c1"), summaries.map { it.id })
        assertEquals(0, summaries.first { it.id == "c2" }.messageCount)
        assertEquals(2, summaries.first { it.id == "c1" }.messageCount)
    }

    @Test
    fun `改名无条件覆盖 且刷新最后活跃时间`() = runTest {
        val env = Env()
        env.store.ensureConversation("c1", title = "自动生成的第一句", now = 100L)

        env.store.renameConversation("c1", "我改的名字", now = 900L)

        val row = env.conversations.rows.getValue("c1")
        assertEquals("我改的名字", row.title)
        // 和 titleIfUntitled 相反：这个是「用户明确要求改成这个」，
        // 所以覆盖，而且改完会话会跳到列表顶部（他刚动过它）
        assertEquals(900L, row.updatedAt)
    }

    @Test
    fun `改名允许清空标题`() = runTest {
        val env = Env()
        env.store.ensureConversation("c1", title = "旧标题", now = 100L)

        env.store.renameConversation("c1", "   ", now = 900L)

        // 空标题是有意义的：清掉它就回到「新对话」的显示。
        // 拦下来的话用户会以为改名功能坏了，而界面上没有任何说明
        assertEquals("", env.conversations.rows.getValue("c1").title)
    }

    @Test
    fun `置顶不动最后活跃时间`() = runTest {
        val env = Env()
        env.store.ensureConversation("c1", title = "甲", now = 100L)

        env.now = 900L
        env.store.setPinned("c1", true)

        val row = env.conversations.rows.getValue("c1")
        assertTrue(row.pinned)
        // `updated_at` 同时用来显示「最后活跃时间」。置顶一下把它变成「刚刚」
        // 是在撒谎 —— 用户会以为自己刚才在这个会话里说过话
        assertEquals(100L, row.updatedAt)
    }

    @Test
    fun `置顶的会话排在最前 取消后回到时间序`() = runTest {
        val env = Env()
        env.store.ensureConversation("c1", title = "甲", now = 100L)
        env.store.ensureConversation("c2", title = "乙", now = 200L)
        env.store.ensureConversation("c3", title = "丙", now = 300L)

        env.store.setPinned("c1", true)
        assertEquals(listOf("c1", "c3", "c2"), env.store.listConversations().map { it.id })
        assertTrue(env.store.listConversations().first().pinned)

        env.store.setPinned("c1", false)
        assertEquals(listOf("c3", "c2", "c1"), env.store.listConversations().map { it.id })
    }

    @Test
    fun `置顶只影响顺序 不影响消息数`() = runTest {
        val env = Env()
        env.store.ensureConversation("c1", title = "甲", now = 100L)
        env.store.save(stored(1L, "a", createdAt = 1L))
        env.store.save(stored(2L, "b", createdAt = 2L))

        env.store.setPinned("c1", true)

        assertEquals(2, env.store.listConversations().single().messageCount)
    }

    // ------------------------------------------------------------ 从某条截断

    @Test
    fun `从某条起截断是软删并清索引`() = runTest {
        val env = Env()
        env.store.save(stored(1L, "第一问", createdAt = 1L))
        env.store.save(stored(2L, "第一答", role = ChatMessage.Role.Assistant, createdAt = 2L))
        env.store.save(stored(3L, "第二问", createdAt = 3L))
        env.store.save(stored(4L, "第二答", role = ChatMessage.Role.Assistant, createdAt = 4L))

        env.store.deleteFrom("c1", MessageCursor(3L, 3L))

        assertEquals(listOf(1L, 2L), env.store.allMessages("c1").map { it.id })
        // 索引也必须一起清。留着的话就是「搜得到、点进去看不到」的幽灵命中 ——
        // 而这条路径**没有**走 deleteIn，是单独的一条 SQL
        assertEquals(setOf(1L, 2L), env.fts.rows.keys)
    }

    @Test
    fun `截断是软删 行还在库里`() = runTest {
        val env = Env()
        env.store.save(stored(1L, "问", createdAt = 1L))
        env.store.save(stored(2L, "答", role = ChatMessage.Role.Assistant, createdAt = 2L))

        env.store.deleteFrom("c1", MessageCursor(2L, 2L))

        // 用户可能立刻后悔（点了重新生成才发现原来那条更好）。
        // 硬删就没有回头路了，所以这里必须是软删
        assertTrue(env.messages.rows.getValue(2L).deleted)
        assertEquals(2, env.messages.rows.size)
    }

    @Test
    fun `截断的游标是复合的 id 与时间不同序时不会漏`() = runTest {
        val env = Env()
        // id 递增但 created_at 回退（用户改过系统时间）。排序键是
        // `created_at, id`，所以「之后」是按时间算的 —— 只比 id 的实现
        // 会把 id 小的那条**留下**，症状是旧回复和新回复并排出现
        env.store.save(stored(20L, "更早", createdAt = 400L))
        env.store.save(stored(30L, "当前", createdAt = 500L))
        env.store.save(stored(10L, "之后", createdAt = 600L))

        env.store.deleteFrom("c1", MessageCursor(500L, 30L))

        assertEquals(listOf(20L), env.store.allMessages("c1").map { it.id })
    }

    @Test
    fun `截断不动别的会话`() = runTest {
        val env = Env()
        env.store.save(stored(1L, "c1 的问", conversationId = "c1", createdAt = 1L))
        env.store.save(stored(2L, "c1 的答", conversationId = "c1", createdAt = 2L))
        env.store.save(stored(3L, "c2 的问", conversationId = "c2", createdAt = 1L))

        env.store.deleteFrom("c1", MessageCursor(1L, 1L))

        assertEquals(listOf(3L), env.store.allMessages("c2").map { it.id })
    }

    @Test
    fun `allMessages 返回全部而不受分页限制`() = runTest {
        val env = Env()
        (1..205).forEach { env.store.save(stored(it.toLong(), "第 $it 条", createdAt = it.toLong())) }

        // 对照组：界面读的是最近一页
        assertEquals(200, env.store.transcript("c1").messages.size)
        // 导出要的是「全部」—— 一份少了 5 条的导出文件比不导出更糟，
        // 用户不会逐行核对，只会以为文件是完整的
        assertEquals(205, env.store.allMessages("c1").size)
    }

    // ------------------------------------------------------------ 启动清理

    @Test
    fun `failInterrupted 把残留的 streaming 标成失败并补索引`() = runTest {
        val env = Env()
        env.store.save(stored(1L, "正常消息"))
        env.store.save(stored(2L, "被中断的半句", status = MessageStatus.Streaming))
        assertTrue(env.fts.rows.containsKey(1L))
        assertTrue(!env.fts.rows.containsKey(2L))

        env.now = 50_000L
        val affected = env.store.failInterrupted(env.now)

        assertEquals(1, affected)
        val row = env.messages.rows.getValue(2L)
        assertEquals("failed", row.status)
        assertEquals(50_000L, row.updatedAt)
        // 内容其实已经显示在屏幕上了，补上索引用户才搜得到
        assertEquals(CjkText.forIndex("被中断的半句"), env.fts.rows.getValue(2L).body)
    }

    @Test
    fun `failInterrupted 没有残留时返回 0 且不碰任何数据`() = runTest {
        val env = Env()
        env.store.save(stored(1L, "正常消息"))

        assertEquals(0, env.store.failInterrupted(99_000L))
        assertEquals("complete", env.messages.rows.getValue(1L).status)
        assertNull(env.messages.rows.getValue(1L).error)
    }

    @Test
    fun `清理后这些消息能进上下文`() = runTest {
        val env = Env()
        env.store.save(stored(1L, "用户问", createdAt = 1L))
        env.store.save(stored(2L, "半句回复", status = MessageStatus.Streaming, createdAt = 2L))

        assertTrue(env.store.history("c1").none { it.content == "半句回复" })

        env.store.failInterrupted(9_000L)

        // 标成 failed 之后就不再是「正在写」，可以进上下文了 ——
        // 否则模型会莫名其妙地缺一段它自己说过的话
        assertTrue(env.store.history("c1").any { it.content == "半句回复" })
    }

    // ------------------------------------------------------------ 分页

    @Test
    fun `消息取不满一页时没有更早的`() = runTest {
        val env = Env()
        repeat(3) { env.store.save(stored(it + 1L, "第 ${it + 1} 条")) }

        val page = env.store.transcript("c1", limit = 10)

        assertEquals(3, page.messages.size)
        assertFalse("取不满一页说明已经到底", page.hasMore)
    }

    @Test
    fun `取的是最近一页而不是最早一页`() = runTest {
        val env = Env()
        repeat(10) { env.store.save(stored(it + 1L, "第 ${it + 1} 条")) }

        val page = env.store.transcript("c1", limit = 3)

        assertEquals(
            "打开会话时用户要看的是最新几条，不是最早几条",
            listOf("第 8 条", "第 9 条", "第 10 条"),
            page.messages.map { it.message.content },
        )
        assertTrue("前面还有 7 条", page.hasMore)
    }

    @Test
    fun `逐页往上翻不重复也不漏`() = runTest {
        val env = Env()
        repeat(10) { env.store.save(stored(it + 1L, "第 ${it + 1} 条")) }

        val first = env.store.transcript("c1", limit = 4)
        val second = env.store.transcript("c1", limit = 4, before = first.cursor)
        val third = env.store.transcript("c1", limit = 4, before = second.cursor)

        // 按「更早的在前」拼起来，必须正好是全部 10 条且顺序正确
        val all = (third.messages + second.messages + first.messages)
            .map { it.message.content }
        assertEquals("翻页拼起来必须不重不漏", (1..10).map { "第 $it 条" }, all)

        assertTrue(first.hasMore)
        assertTrue(second.hasMore)
        assertFalse("第三页只剩 2 条，到底了", third.hasMore)
    }

    @Test
    fun `游标指向这一页最早的那条`() = runTest {
        val env = Env()
        repeat(5) { env.store.save(stored(it + 1L, "第 ${it + 1} 条")) }

        val page = env.store.transcript("c1", limit = 3)

        // 这页是第 3、4、5 条，最早的是第 3 条
        assertEquals(MessageCursor(createdAt = 3L, id = 3L), page.cursor)
    }

    @Test
    fun `created_at 与 id 不同序时翻页也不漏`() = runTest {
        val env = Env()
        // 用户改过系统时间：id=1 的 created_at 比 id=2 更晚。
        // 排序是 created_at DESC，所以顺序是「最新的 / 早写的但时间戳晚 / 晚写的但时间戳早」
        env.store.save(stored(3L, "最新的", createdAt = 200L))
        env.store.save(stored(1L, "早写的但时间戳晚", createdAt = 100L))
        env.store.save(stored(2L, "晚写的但时间戳早", createdAt = 50L))

        val first = env.store.transcript("c1", limit = 2)
        val second = env.store.transcript("c1", limit = 2, before = first.cursor)

        assertEquals(
            // 返回的是**正序**：created_at 是 100 和 200，所以 100 在前
            listOf("早写的但时间戳晚", "最新的"),
            first.messages.map { it.message.content },
        )
        assertEquals(
            "游标只比 id 的话这里会返回空 —— id=2 永远看不到。" +
                "游标必须与排序键 (created_at, id) 完全一致",
            listOf("晚写的但时间戳早"),
            second.messages.map { it.message.content },
        )
        assertFalse(second.hasMore)
    }

    @Test
    fun `恰好取满一页时 hasMore 为 false`() = runTest {
        val env = Env()
        repeat(4) { env.store.save(stored(it + 1L, "第 ${it + 1} 条")) }

        val page = env.store.transcript("c1", limit = 4)

        assertEquals(4, page.messages.size)
        // 这正是「多取一条」的价值：恰好取满时 size == limit，
        // 拿它当判据的话界面会显示一个点了没反应的按钮，用户以为它坏了
        assertFalse("正好 4 条，已经到底了", page.hasMore)
    }
}
