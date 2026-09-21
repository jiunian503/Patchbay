package com.aichat.chat

import com.aichat.domain.llm.ChatStreamEvent
import com.aichat.domain.tool.SimpleToolRegistry
import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolResult
import com.aichat.network.ChatApiException
import com.aichat.network.ChatCompletionClient
import com.aichat.network.ChatCompletionRequest
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 按脚本回放的假客户端。每调用一次 [stream] 消费一个预设的 Flow，
 * 于是「第一轮调工具、第二轮给答案」这种场景能被精确摆出来。
 */
private class ScriptedClient : ChatCompletionClient {

    private val rounds = ArrayDeque<Flow<ChatStreamEvent>>()

    val requests = mutableListOf<ChatCompletionRequest>()

    fun enqueue(flow: Flow<ChatStreamEvent>) {
        rounds += flow
    }

    /** 一轮纯文本回答。 */
    fun enqueueText(vararg chunks: String) {
        enqueue(flow { chunks.forEach { emit(ChatStreamEvent.TextDelta(it)) } })
    }

    fun enqueueFailure(error: Throwable) {
        enqueue(flow { throw error })
    }

    override fun stream(request: ChatCompletionRequest): Flow<ChatStreamEvent> {
        requests += request
        // 没有预设 = 服务端直接断流
        return rounds.removeFirstOrNull() ?: flow { }
    }
}

/**
 * 内存版会话存储。只实现 [ChatSession] 真正会用到的那部分语义，
 * 但**必须**复刻两条：
 *
 * - [history] 排除 `Streaming`（真实实现靠 SQL 的 `status != 'streaming'`）
 * - [save] 是 upsert（同一个 id 反复写）
 */
private class MemoryStore : ConversationStore {

    /** 全部写入记录，按发生顺序。用来断言「写了几次」「先写谁后写谁」。 */
    val writes = mutableListOf<StoredMessage>()

    /**
     * 关键动作的**发生顺序**，形如 `save:100` / `deleteFrom:(1000,100)` / `history`。
     *
     * 「截断必须发生在读 history 之前」这条不变量只能靠顺序钉住 ——
     * 只断言「删掉了」是没用的：先读后删的实现同样会删掉，只是模型
     * 已经看到了被丢弃的内容（然后照着它往下接，用户看到一条重复的回复）。
     */
    val ops = mutableListOf<String>()

    /** 每个 id 的最终状态。 */
    val byId = LinkedHashMap<Long, StoredMessage>()

    val conversationTitles = mutableMapOf<String, String>()

    val ensureCalls = mutableListOf<String>()

    val titleCalls = mutableListOf<Pair<String, String>>()

    val replies: List<StoredMessage> get() = byId.values.filter { it.message.role == ChatMessage.Role.Assistant }

    fun writesOf(id: Long) = writes.filter { it.id == id }

    /** 与真实 SQL 的 `ORDER BY created_at, id` 一致（id 是必须的稳定次序）。 */
    private fun ordered(conversationId: String) = byId.values
        .filter { it.conversationId == conversationId }
        .sortedWith(compareBy({ it.createdAt }, { it.id }))

    override suspend fun history(conversationId: String, limit: Int): List<ChatMessage> {
        ops += "history"
        return ordered(conversationId)
            .filter { it.status != MessageStatus.Streaming }
            .takeLast(limit)
            .map { it.message }
    }

    override suspend fun transcript(
        conversationId: String,
        limit: Int,
        before: MessageCursor?,
    ): TranscriptPage {
        val all = ordered(conversationId)

        // 与真实 SQL 的游标语义保持一致：取排序位置在 (createdAt, id) **之前**
        // 的那一段。只比 id 的话，`createdAt` 与 `id` 不同序的数据会漏 ——
        // 那正是 `MessageDao.pageBefore` 要防的事，假实现也得跟着防，
        // 否则「翻页不漏消息」这条在假实现上永远测不出来
        val remaining = if (before == null) {
            all
        } else {
            all.filter {
                it.createdAt < before.createdAt ||
                    (it.createdAt == before.createdAt && it.id < before.id)
            }
        }

        val page = remaining.takeLast(limit)
        return TranscriptPage(
            messages = page,
            cursor = page.firstOrNull()?.let { MessageCursor(it.createdAt, it.id) },
            hasMore = remaining.size > limit,
        )
    }

    override suspend fun save(record: StoredMessage) {
        ops += "save:${record.id}"
        writes += record
        byId[record.id] = record
    }

    override suspend fun delete(conversationId: String, messageId: Long) {
        byId.remove(messageId)
    }

    /**
     * 截断。游标是**复合**的（`createdAt` 与 `id` 一起比），与真实 SQL 一致。
     *
     * 这里是真删而不是打标记：假实现里 [byId] 只装活着的行，
     * 所以「软删」等价于从这张表里拿掉。
     */
    override suspend fun deleteFrom(conversationId: String, from: MessageCursor) {
        ops += "deleteFrom:(${from.createdAt},${from.id})"
        byId.keys.toList().forEach { id ->
            val row = byId.getValue(id)
            if (row.conversationId == conversationId &&
                (row.createdAt > from.createdAt ||
                    (row.createdAt == from.createdAt && row.id >= from.id))
            ) {
                byId.remove(id)
            }
        }
    }

    override suspend fun allMessages(conversationId: String): List<StoredMessage> =
        ordered(conversationId)

    override suspend fun clearMessages(conversationId: String) {
        byId.keys.toList().forEach { id ->
            if (byId.getValue(id).conversationId == conversationId) byId.remove(id)
        }
    }

    override suspend fun ensureConversation(conversationId: String, title: String, now: Long) {
        ensureCalls += conversationId
        if (!conversationTitles.containsKey(conversationId)) conversationTitles[conversationId] = title
    }

    override suspend fun titleIfUntitled(conversationId: String, title: String, now: Long) {
        titleCalls += conversationId to title
        if (conversationTitles[conversationId].isNullOrEmpty()) conversationTitles[conversationId] = title
    }

    override suspend fun listConversations(limit: Int): List<ConversationSummary> = emptyList()

    override suspend fun renameConversation(conversationId: String, title: String, now: Long) = Unit

    override suspend fun setPinned(conversationId: String, pinned: Boolean) = Unit

    override suspend fun deleteConversation(conversationId: String) {
        conversationTitles.remove(conversationId)
        clearMessages(conversationId)
    }

    override suspend fun failInterrupted(now: Long): Int = 0
}

private fun weatherTool(): Tool = object : Tool {
    override val definition = ToolDefinition(
        name = "get_weather",
        description = "查天气",
        parameters = buildJsonObject { put("type", JsonPrimitive("object")) },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult = ToolResult.ok("北京 25°C 晴")
}

private fun failingTool(): Tool = object : Tool {
    override val definition = ToolDefinition(
        name = "get_weather",
        description = "查天气",
        parameters = buildJsonObject { put("type", JsonPrimitive("object")) },
    )

    override suspend fun execute(arguments: JsonObject): ToolResult = throw IOException("上游超时")
}

private fun toolCallDelta(
    index: Int = 0,
    id: String? = "call_a",
    name: String? = "get_weather",
    args: String? = "{}",
) = ChatStreamEvent.ToolCallDelta(index = index, id = id, name = name, argumentsDelta = args)

/**
 * [ChatSession] 的落盘行为测试。
 *
 * 这个类的价值全在「用户随时杀进程，回来看到的东西是对的」。
 * 所以下面每组测试都在回答同一个问题：**某一刻进程死了，库里的状态对不对。**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionTest {

    private class Env(
        tools: List<Tool> = emptyList(),
        promptSource: SystemPromptSource? = null,
        greetingSource: GreetingSource? = null,
    ) {
        val store = MemoryStore()
        val client = ScriptedClient()
        var now = 1_000L
        private var nextId = 100L
        val session = ChatSession(
            engine = ConversationEngine(client, SimpleToolRegistry(tools)),
            store = store,
            ids = { nextId++ },
            clock = { now },
            persistIntervalMs = 400L,
            promptSource = promptSource,
            greetingSource = greetingSource,
        )

        /** 用户消息拿 100，第一条回复行拿 101。 */
        val replyId = 101L
    }

    private val config = ChatConfig(model = "test-model")

    // ------------------------------------------------------------ 基本流程

    @Test
    fun `先存用户消息再发请求`() = runTest {
        val env = Env()
        env.client.enqueueText("你好")

        env.session.send("c1", "在吗", config).toList()

        // 用户消息必须是第一次写入 —— 否则取 history 时这次输入就不在上下文里
        assertEquals(ChatMessage.Role.User, env.store.writes.first().message.role)
        assertEquals("在吗", env.store.writes.first().message.content)
        assertEquals(MessageStatus.Complete, env.store.writes.first().status)
    }

    @Test
    fun `传给模型的上下文包含刚存的用户消息`() = runTest {
        val env = Env()
        env.client.enqueueText("在的")

        env.session.send("c1", "在吗", config).toList()

        val sent = env.client.requests.single().messages
        assertEquals(1, sent.size)
        assertEquals("user", sent.single().role)
        assertEquals("在吗", sent.single().content)
    }

    @Test
    fun `第二轮请求能看到第一轮的回复`() = runTest {
        val env = Env()
        env.client.enqueueText("第一轮回答")
        env.session.send("c1", "第一问", config).toList()

        env.client.enqueueText("第二轮回答")
        env.session.send("c1", "第二问", config).toList()

        val second = env.client.requests[1].messages
        assertEquals(listOf("user", "assistant", "user"), second.map { it.role })
        assertEquals("第一轮回答", second[1].content)
    }

    @Test
    fun `回复最终落成 complete 并带上模型名`() = runTest {
        val env = Env()
        env.client.enqueueText("答", "案")

        env.session.send("c1", "问", config).toList()

        val reply = env.store.replies.single()
        assertEquals(MessageStatus.Complete, reply.status)
        assertEquals("答案", reply.message.content)
        assertEquals("test-model", reply.model)
        assertNull(reply.error)
    }

    @Test
    fun `先落一条 streaming 占位再更新`() = runTest {
        val env = Env()
        env.client.enqueueText("答")

        env.session.send("c1", "问", config).toList()

        val writes = env.store.writesOf(env.replyId)
        assertTrue("至少要有占位 + 收尾两次写入", writes.size >= 2)
        // 第一次写必须是空内容的 streaming 占位 —— 这样「模型正在回复」
        // 这件事在库里也成立，界面刷新后还能看到它
        assertEquals(MessageStatus.Streaming, writes.first().status)
        assertEquals("", writes.first().message.content)
        assertEquals(MessageStatus.Complete, writes.last().status)
        assertEquals("答", writes.last().message.content)
    }

    // ------------------------------------------------------------ 节流

    @Test
    fun `流式过程中按时间节流 不会每来一个 token 就写库`() = runTest {
        val env = Env()
        // 20 个分片，但时钟只走了 3 次（每次 500ms > 400ms 阈值）
        env.client.enqueue(
            flow {
                repeat(20) { i ->
                    if (i % 7 == 0) env.now += 500L
                    emit(ChatStreamEvent.TextDelta("字$i"))
                }
            }
        )

        env.session.send("c1", "问", config).toList()

        val writes = env.store.writesOf(env.replyId)
        // 占位 1 次 + 节流 3 次（第 0、7、14 片）+ 收尾 1 次
        assertTrue("20 个分片不该产生 20 次写入，实际 ${writes.size} 次", writes.size <= 6)
        // 但收尾那次必须写全
        assertEquals(
            (0..19).joinToString("") { "字$it" },
            writes.last().message.content,
        )
    }

    @Test
    fun `时钟不动时只有占位与收尾两次写入`() = runTest {
        val env = Env()
        env.client.enqueueText("一", "二", "三")

        env.session.send("c1", "问", config).toList()

        assertEquals(2, env.store.writesOf(env.replyId).size)
    }

    @Test
    fun `收尾写入是无条件的`() = runTest {
        val env = Env()
        env.client.enqueueText("最后一点点")

        env.session.send("c1", "问", config).toList()

        // 节流可能把最后几百毫秒的内容留在内存里，收尾必须补上 ——
        // 否则用户会看到「回复少了个尾巴」
        val last = env.store.writesOf(env.replyId).last()
        assertEquals("最后一点点", last.message.content)
    }

    // ------------------------------------------------------------ 工具

    @Test
    fun `工具结果会作为独立消息落盘`() = runTest {
        val env = Env(tools = listOf(weatherTool()))
        env.client.enqueue(flow { emit(toolCallDelta()) })
        env.client.enqueueText("北京 25°C")

        env.session.send("c1", "北京天气", config).toList()

        val toolMessage = env.store.byId.values.single { it.message.role == ChatMessage.Role.Tool }
        assertEquals("北京 25°C 晴", toolMessage.message.content)
        assertEquals("call_a", toolMessage.message.toolCallId)
        assertEquals(MessageStatus.Complete, toolMessage.status)
    }

    @Test
    fun `工具报错时落盘的状态是 failed 但内容保留`() = runTest {
        val env = Env(tools = listOf(failingTool()))
        env.client.enqueue(flow { emit(toolCallDelta()) })
        env.client.enqueueText("查询失败了")

        env.session.send("c1", "北京天气", config).toList()

        val toolMessage = env.store.byId.values.single { it.message.role == ChatMessage.Role.Tool }
        assertEquals(MessageStatus.Failed, toolMessage.status)
        assertTrue(toolMessage.message.content.contains("上游超时"))
    }

    @Test
    fun `每轮的 assistant 行各自带上自己那轮的调用`() = runTest {
        val env = Env(tools = listOf(weatherTool()))
        env.client.enqueue(flow { emit(toolCallDelta()) })
        env.client.enqueueText("北京 25°C")

        env.session.send("c1", "北京天气", config).toList()

        val replies = env.store.replies
        // 调工具那一轮 + 给答案那一轮 = 两行
        assertEquals(2, replies.size)
        assertEquals("call_a", replies[0].message.toolCalls.single().id)
        // 最终答案那轮没有工具调用，否则回传上下文时模型会以为还要继续调
        assertTrue(replies[1].message.toolCalls.isEmpty())
        assertEquals("北京 25°C", replies[1].message.content)
    }

    @Test
    fun `工具卡片的位置在重启后仍然正确`() = runTest {
        val env = Env(tools = listOf(weatherTool()))
        env.client.enqueue(
            flow {
                emit(ChatStreamEvent.TextDelta("我查一下"))
                emit(toolCallDelta())
            }
        )
        env.client.enqueueText("北京 25°C")

        env.session.send("c1", "北京天气", config).toList()

        // 这是「一轮一条 assistant 行」这个设计要换来的东西：
        // 按 created_at, id 排序就能还原当时的交错顺序，
        // 不需要额外存「段落顺序」这种元信息。
        // 整场合成一条的话，重新打开会话会变成「我查一下北京 25°C + 卡片在下面」
        val rows = env.store.transcript("c1").messages
        assertEquals(
            listOf(
                ChatMessage.Role.User,
                ChatMessage.Role.Assistant,
                ChatMessage.Role.Tool,
                ChatMessage.Role.Assistant,
            ),
            rows.map { it.message.role },
        )
        assertEquals("我查一下", rows[1].message.content)
        assertEquals("call_a", rows[1].message.toolCalls.single().id)
        assertEquals("北京 25°C 晴", rows[2].message.content)
        assertEquals("北京 25°C", rows[3].message.content)
    }

    @Test
    fun `连续两轮工具调用会留下三行 assistant`() = runTest {
        val env = Env(tools = listOf(weatherTool()))
        env.client.enqueue(flow { emit(toolCallDelta(id = "call_1")) })
        env.client.enqueue(flow { emit(toolCallDelta(id = "call_2")) })
        env.client.enqueueText("终于好了")

        env.session.send("c1", "问", config).toList()

        assertEquals(3, env.store.replies.size)
        assertEquals(listOf("call_1"), env.store.replies[0].message.toolCalls.map { it.id })
        assertEquals(listOf("call_2"), env.store.replies[1].message.toolCalls.map { it.id })
        assertEquals("终于好了", env.store.replies[2].message.content)
    }

    @Test
    fun `模型只调工具不说话时也有承载 tool_calls 的行`() = runTest {
        val env = Env(tools = listOf(weatherTool()))
        // 一轮里一个字都不吐，只给工具调用
        env.client.enqueue(flow { emit(toolCallDelta()) })
        env.client.enqueueText("北京 25°C")

        env.session.send("c1", "北京天气", config).toList()

        val first = env.store.replies.first()
        assertEquals("", first.message.content)
        assertEquals("call_a", first.message.toolCalls.single().id)
        // 回传上下文时，tool 结果必须能找到对应的 tool_calls，
        // 否则服务端会报「tool_call_id 找不到对应的调用」
        assertEquals(listOf("call_a"), env.store.history("c1").flatMap { it.toolCalls }.map { it.id })
    }

    @Test
    fun `引擎给出的工具调用参数被原样存下来`() = runTest {
        val env = Env(tools = listOf(weatherTool()))
        env.client.enqueue(
            flow {
                emit(ChatStreamEvent.ToolCallDelta(0, "call_a", "get_weather", """{"city":"北京"}"""))
            }
        )
        env.client.enqueueText("好了")

        env.session.send("c1", "问", config).toList()

        val calls = env.store.replies.first { it.message.toolCalls.isNotEmpty() }.message.toolCalls
        // 参数保持 JSON 字符串形态，不解析再序列化 —— 那会改变键序和浮点格式，
        // 部分服务端会因此判定上下文不连续
        assertEquals("""{"city":"北京"}""", calls.single().argumentsJson)
    }

    // ------------------------------------------------------------ 失败与取消

    @Test
    fun `接口报错时保留已收到的内容并记下原因`() = runTest {
        val env = Env()
        env.client.enqueue(
            flow {
                emit(ChatStreamEvent.TextDelta("前半句"))
                throw ChatApiException.Http(401, "", "HTTP 401：Invalid API key")
            }
        )

        val events = env.session.send("c1", "问", config).toList()

        assertTrue(events.last() is ChatEvent.Failed)
        val reply = env.store.replies.single()
        assertEquals(MessageStatus.Failed, reply.status)
        assertEquals("前半句", reply.message.content)
        assertNotNull(reply.error)
        assertTrue(reply.error!!.contains("401"))
    }

    @Test
    fun `网络异常也落成 failed`() = runTest {
        val env = Env()
        env.client.enqueueFailure(ChatApiException.Network(IOException("连接被重置")))

        env.session.send("c1", "问", config).toList()

        // 一个字都没吐就失败，也必须留一行 —— 否则用户重新打开会话
        // 只看到自己那句问话，完全不知道当时其实失败过
        val reply = env.store.replies.single()
        assertEquals(MessageStatus.Failed, reply.status)
        assertEquals("", reply.message.content)
        assertTrue(reply.error!!.contains("连不上服务端"))
    }

    @Test
    fun `连不上服务端时失败记录会出现在 transcript 里`() = runTest {
        val env = Env()
        env.client.enqueueFailure(ChatApiException.Network(IOException("连接被重置")))

        env.session.send("c1", "问", config).toList()

        val rows = env.store.transcript("c1").messages
        assertEquals(2, rows.size)
        assertEquals(ChatMessage.Role.User, rows[0].message.role)
        assertEquals(ChatMessage.Role.Assistant, rows[1].message.role)
        assertEquals(MessageStatus.Failed, rows[1].status)
    }

    @Test
    fun `用户取消时状态是 stopped 且半截内容保留`() = runTest {
        val env = Env()
        env.client.enqueue(
            flow {
                emit(ChatStreamEvent.TextDelta("写了一半"))
                throw CancellationException("用户按了停止")
            }
        )

        try {
            env.session.send("c1", "问", config).toList()
            fail("取消异常应当继续往上抛，不能被吞掉")
        } catch (expected: CancellationException) {
            // 预期
        }

        val reply = env.store.replies.single()
        // 不是 Failed —— 用户主动停的不该显示成红色错误
        assertEquals(MessageStatus.Stopped, reply.status)
        assertEquals("写了一半", reply.message.content)
        assertNull(reply.error)
    }

    @Test
    fun `真实取消协程时同样落盘`() = runTest {
        val env = Env()
        env.client.enqueue(
            flow {
                emit(ChatStreamEvent.TextDelta("被中断的半句"))
                // 永不返回，等外部取消
                awaitCancellation()
            }
        )

        val job = launch { env.session.send("c1", "问", config).collect { } }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()

        val reply = env.store.replies.single()
        assertEquals(MessageStatus.Stopped, reply.status)
        assertEquals("被中断的半句", reply.message.content)
    }

    @Test
    fun `服务端直接断流时不留下永远 streaming 的行`() = runTest {
        val env = Env()
        env.client.enqueueText("说了一半")
        // 第二轮不给预设 = 服务端直接断流

        env.session.send("c1", "问", config).toList()

        val stuck = env.store.byId.values.filter { it.status == MessageStatus.Streaming }
        assertTrue("不能留下 streaming 状态的行，否则它永远进不了上下文", stuck.isEmpty())
    }

    @Test
    fun `空输入不发请求也不落库`() = runTest {
        val env = Env()

        val events = env.session.send("c1", "   ", config).toList()

        assertTrue(events.single() is ChatEvent.Failed)
        assertTrue(env.store.writes.isEmpty())
        assertTrue(env.client.requests.isEmpty())
    }

    @Test
    fun `空输入也不会建会话`() = runTest {
        val env = Env()
        env.session.send("c1", "", config).toList()
        assertTrue(env.store.ensureCalls.isEmpty())
    }

    @Test
    fun `模型一个字都没返回时也留一条空行`() = runTest {
        val env = Env()
        // 没有预设 = 服务端断流，一个字都没有
        env.session.send("c1", "问", config).toList()

        val reply = env.store.replies.single()
        assertEquals(MessageStatus.Complete, reply.status)
        assertEquals("", reply.message.content)
    }

    // ------------------------------------------------------------ 重新生成 / 编辑重发

    /**
     * 造一条已经在库里的消息。
     *
     * `createdAt = id` 是有意的：让「排序键」和「id」同序，游标里那一半
     * 才不会喧宾夺主。**不同序**的情况单独用一个用例去撞（见
     * `截断的游标与排序键一致 同毫秒的消息不会漏掉`）。
     */
    private fun msg(
        id: Long,
        text: String,
        role: ChatMessage.Role,
        conversationId: String = "c1",
        createdAt: Long = id,
    ) = StoredMessage(
        id = id,
        conversationId = conversationId,
        message = ChatMessage(role = role, content = text),
        status = MessageStatus.Complete,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun MemoryStore.seed(vararg rows: StoredMessage) {
        rows.forEach { byId[it.id] = it }
    }

    /** 一问一答的两轮对话，id 1..4。 */
    private fun MemoryStore.seedTwoTurns() = seed(
        msg(1, "第一问", ChatMessage.Role.User),
        msg(2, "第一答", ChatMessage.Role.Assistant),
        msg(3, "第二问", ChatMessage.Role.User),
        msg(4, "第二答", ChatMessage.Role.Assistant),
    )

    private suspend fun MemoryStore.contents() = allMessages("c1").map { it.message.content }

    @Test
    fun `重新生成会丢掉那条及其之后`() = runTest {
        val env = Env()
        env.store.seedTwoTurns()
        env.client.enqueueText("第二答（重来）")

        env.session.regenerate("c1", MessageCursor(4, 4), config).toList()

        assertEquals(
            listOf("第一问", "第一答", "第二问", "第二答（重来）"),
            env.store.contents(),
        )
    }

    @Test
    fun `重新生成必须先删再读历史`() = runTest {
        val env = Env()
        env.store.seedTwoTurns()
        env.client.enqueueText("重来")

        env.session.regenerate("c1", MessageCursor(4, 4), config).toList()

        // 顺序反了的话，「删掉」这件事照样发生 —— 断言「库里少了那条」是看不出来的。
        // 真正的后果是模型仍然看到了被丢弃的那条，然后照着它往下接：
        // 用户拿到一条**重复**的回复，而不是新的回答
        val deleted = env.store.ops.indexOfFirst { it.startsWith("deleteFrom") }
        val read = env.store.ops.indexOf("history")
        assertTrue("deleteFrom 必须排在 history 之前，实际顺序：${env.store.ops}", deleted in 0..<read)

        // 上下文里不能有被丢掉的那条
        assertEquals(
            listOf("第一问", "第一答", "第二问"),
            env.client.requests.single().messages.map { it.content },
        )
    }

    @Test
    fun `重新生成不新增用户消息`() = runTest {
        val env = Env()
        env.store.seedTwoTurns()
        env.client.enqueueText("重来")

        env.session.regenerate("c1", MessageCursor(4, 4), config).toList()

        // 「同一句话，再答一次」—— 多出一条用户消息就变成「又问了一遍」
        assertEquals(
            listOf("第一问", "第二问"),
            env.store.allMessages("c1").filter { it.message.role == ChatMessage.Role.User }
                .map { it.message.content },
        )
    }

    @Test
    fun `截断包含游标那条自己`() = runTest {
        val env = Env()
        // 三条落在同一毫秒里 —— 真机上「用户消息 + 助手占位」就是这样连写的
        env.store.seed(
            msg(10, "问", ChatMessage.Role.User, createdAt = 500L),
            msg(11, "旧答", ChatMessage.Role.Assistant, createdAt = 500L),
            msg(12, "追问", ChatMessage.Role.User, createdAt = 500L),
        )
        env.client.enqueueText("新答")

        env.session.regenerate("c1", MessageCursor(500L, 12L), config).toList()

        // 游标那条自己也要删。用 `>` 而不是 `>=` 的实现会把它留下 ——
        // 症状是重新生成之后，旧的那条和新回复并排显示
        assertEquals(listOf("问", "旧答", "新答"), env.store.contents())
    }

    @Test
    fun `截断按时间算 不是按 id`() = runTest {
        val env = Env()
        // id 递增但 created_at 回退（用户改过系统时间）。排序键是
        // `created_at, id`，所以「之后」是按**时间**算的 ——
        // 只比 id 的实现会把 id 小的那条留下，而它其实排在更后面
        env.store.seed(
            msg(10, "第一问", ChatMessage.Role.User, createdAt = 500L),
            msg(12, "第二问", ChatMessage.Role.User, createdAt = 600L),
            msg(11, "第二答", ChatMessage.Role.Assistant, createdAt = 700L),
        )
        env.client.enqueueText("新答")

        env.session.regenerate("c1", MessageCursor(600L, 12L), config).toList()

        assertEquals(listOf("第一问", "新答"), env.store.contents())
    }

    @Test
    fun `截断不会动到别的会话`() = runTest {
        val env = Env()
        env.store.seedTwoTurns()
        env.store.seed(msg(9, "别的会话", ChatMessage.Role.User, conversationId = "c2", createdAt = 9L))
        env.client.enqueueText("重来")

        env.session.regenerate("c1", MessageCursor(1, 1), config).toList()

        assertEquals(listOf("别的会话"), env.store.contents2())
    }

    private suspend fun MemoryStore.contents2() = allMessages("c2").map { it.message.content }

    @Test
    fun `编辑重发替换掉那条用户消息而不是追加`() = runTest {
        val env = Env()
        env.store.seedTwoTurns()
        env.client.enqueueText("新答案")

        env.session.editAndResend("c1", MessageCursor(3, 3), "第二问（改过）", config).toList()

        assertEquals(
            listOf("第一问", "第一答", "第二问（改过）", "新答案"),
            env.store.contents(),
        )
        // 替换意味着旧的那条**不在**了。追加的实现会让两句问话同时存在，
        // 而模型看到两个几乎一样的问题会以为用户改主意了
        assertFalse(env.store.contents().contains("第二问"))
    }

    @Test
    fun `编辑重发也是先删再读历史`() = runTest {
        val env = Env()
        env.store.seedTwoTurns()
        env.client.enqueueText("新答案")

        env.session.editAndResend("c1", MessageCursor(3, 3), "改过的问题", config).toList()

        val deleted = env.store.ops.indexOfFirst { it.startsWith("deleteFrom") }
        val read = env.store.ops.indexOf("history")
        assertTrue("实际顺序：${env.store.ops}", deleted in 0..<read)
        assertEquals(
            listOf("第一问", "第一答", "改过的问题"),
            env.client.requests.single().messages.map { it.content },
        )
    }

    @Test
    fun `编辑重发空白输入一条都不删`() = runTest {
        val env = Env()
        env.store.seedTwoTurns()

        val events = env.session.editAndResend("c1", MessageCursor(3, 3), "   ", config).toList()

        assertTrue(events.single() is ChatEvent.Failed)
        // 先删后校验的实现会在这里少掉两条 —— 而界面上给出的理由
        // 是「消息内容不能为空」，和实际发生的事完全对不上
        assertEquals(4, env.store.contents().size)
        assertTrue(env.client.requests.isEmpty())
    }

    @Test
    fun `编辑重发的空白报错与发送用同一句文案`() = runTest {
        val env = Env()

        val edit = env.session.editAndResend("c1", MessageCursor(3, 3), "", config).toList()
        val send = env.session.send("c1", "  ", config).toList()

        // 两条路径对同一种错误给出两种说法，用户会以为其中一个是另一个的问题
        assertEquals(
            (send.single() as ChatEvent.Failed).error.message,
            (edit.single() as ChatEvent.Failed).error.message,
        )
    }

    // ------------------------------------------------------------ 会话与标题

    @Test
    fun `发送前确保会话存在`() = runTest {
        val env = Env()
        env.client.enqueueText("答")

        env.session.send("c1", "问", config).toList()

        assertEquals(listOf("c1"), env.store.ensureCalls)
    }

    @Test
    fun `第一条消息用来生成会话标题`() = runTest {
        val env = Env()
        env.client.enqueueText("答")

        env.session.send("c1", "帮我把会议纪要整理成表格", config).toList()

        assertEquals("帮我把会议纪要整理成表格", env.store.conversationTitles["c1"])
    }

    @Test
    fun `标题只取第一行且超长会截断`() = runTest {
        val env = Env()
        env.client.enqueueText("答")

        env.session.send("c1", "\n\n这是第一行\n这是第二行", config).toList()

        assertEquals("这是第一行", env.store.conversationTitles["c1"])

        env.client.enqueueText("答")
        env.session.send("c2", "很长的一段话".repeat(10), config).toList()
        assertEquals(25, env.store.conversationTitles["c2"]!!.length)
        assertTrue(env.store.conversationTitles["c2"]!!.endsWith("…"))
    }

    @Test
    fun `思维链存下来但不进上下文`() = runTest {
        val env = Env()
        env.client.enqueue(
            flow {
                emit(ChatStreamEvent.ReasoningDelta("让我想想…"))
                emit(ChatStreamEvent.TextDelta("答案是 42"))
            }
        )
        env.session.send("c1", "问", config).toList()

        val reply = env.store.replies.single()
        assertEquals("让我想想…", reply.message.reasoning)
        assertEquals("答案是 42", reply.message.content)

        // 下一轮请求里不该出现思维链 —— 模型看到自己上一轮的草稿
        // 会接着往下写，而不是重新回答
        env.client.enqueueText("答")
        env.session.send("c1", "再问", config).toList()
        val sent = env.client.requests[1].messages
        assertFalse(sent.any { it.content?.contains("让我想想") == true })
    }

    @Test
    fun `工具调用轮数超限时最后一轮标记失败`() = runTest {
        val env = Env(tools = listOf(weatherTool()))
        repeat(3) { env.client.enqueue(flow { emit(toolCallDelta(id = "call_$it")) }) }

        env.session.send("c1", "问", config.copy(maxToolRounds = 3)).toList()

        val last = env.store.replies.last()
        assertEquals(MessageStatus.Failed, last.status)
        assertTrue(last.error!!.contains("3"))
    }

    @Test
    fun `id 严格递增 时钟回拨也不重复`() {
        var t = 1_000L
        val ids = MonotonicIds { t }

        assertEquals(1_000L, ids.next())
        assertEquals(1_001L, ids.next())
        t = 2_000L
        assertEquals(2_000L, ids.next())
        t = 500L // 用户手动改过系统时间
        assertEquals(2_001L, ids.next())
    }

    @Test
    fun `标题生成会压掉多余空白`() {
        assertEquals("你好 世界", deriveTitle("你好   世界"))
        assertEquals("单行", deriveTitle("单行"))
        assertEquals("", deriveTitle("   "))
        assertEquals("", deriveTitle("\n\n"))
    }

    // ------------------------------------------------- 角色注入（promptSource）

    @Test
    fun `promptSource 解析出的提示词被插在消息列表最前面`() = runTest {
        val env = Env(promptSource = { _, _ -> "你是一位诗人。" })
        env.client.enqueueText("好")

        env.session.send("c1", "在吗", config).toList()

        val sent = env.client.requests.single().messages
        assertEquals(listOf("system", "user"), sent.map { it.role })
        assertEquals("你是一位诗人。", sent.first().content)
    }

    @Test
    fun `promptSource 拿到的历史里已经包含本次输入`() = runTest {
        var seen: List<ChatMessage> = emptyList()
        val env = Env(promptSource = { _, history -> seen = history; null })
        env.client.enqueueText("好")

        env.session.send("c1", "我住在北京", config).toList()

        // 世界书就是扫这段历史来命中的。少这一条，「用户刚说的话触发不了词条」
        assertTrue(seen.any { it.role == ChatMessage.Role.User && it.content == "我住在北京" })
    }

    @Test
    fun `promptSource 收到的是当前会话 id`() = runTest {
        var seenId: String? = null
        val env = Env(promptSource = { id, _ -> seenId = id; null })
        env.client.enqueueText("好")

        env.session.send("c-42", "在吗", config).toList()

        // 角色是**会话的属性**，只给 history 的话推不出「这是哪个会话」
        assertEquals("c-42", seenId)
    }

    @Test
    fun `promptSource 返回空白时不注入系统消息`() = runTest {
        val env = Env(promptSource = { _, _ -> "   " })
        env.client.enqueueText("好")

        env.session.send("c1", "在吗", config).toList()

        val sent = env.client.requests.single().messages
        assertTrue(sent.none { it.role == "system" })
    }

    @Test
    fun `没有 promptSource 时系统消息只来自服务商配置`() = runTest {
        val env = Env()
        env.client.enqueueText("好")

        env.session.send("c1", "在吗", config.copy(systemPrompt = "请用中文回答。")).toList()

        val sent = env.client.requests.single().messages
        assertEquals(listOf("system", "user"), sent.map { it.role })
        assertEquals("请用中文回答。", sent.first().content)
    }

    @Test
    fun `角色注入与服务商附加拼成一条系统消息`() = runTest {
        val env = Env(promptSource = { _, _ -> "你是一位诗人。" })
        env.client.enqueueText("好")

        env.session.send("c1", "在吗", config.copy(systemPrompt = "请用中文回答。")).toList()

        val sent = env.client.requests.single().messages
        // 合成**一条**而不是两条 system —— 部分兼容服务端只认第一条
        assertEquals(listOf("system", "user"), sent.map { it.role })
        assertEquals("你是一位诗人。\n\n请用中文回答。", sent.first().content)
    }

    @Test
    fun `重新生成会重新解析提示词`() = runTest {
        var calls = 0
        val env = Env(promptSource = { _, _ -> calls++; null })
        env.client.enqueueText("一")
        env.client.enqueueText("二")

        env.session.send("c1", "在吗", config).toList()
        val reply = env.store.replies.single()
        env.session.regenerate("c1", MessageCursor(reply.createdAt, reply.id), config).toList()

        // 世界书是按上下文命中的，只在会话开始时解析一次的话，
        // 聊到第三章还带着第一章的设定
        assertEquals(2, calls)
    }

    @Test
    fun `编辑重发会重新解析提示词`() = runTest {
        var calls = 0
        val env = Env(promptSource = { _, _ -> calls++; null })
        env.client.enqueueText("一")
        env.client.enqueueText("二")

        env.session.send("c1", "在吗", config).toList()
        val user = env.store.writes.first()
        env.session.editAndResend("c1", MessageCursor(user.createdAt, user.id), "改了", config).toList()

        assertEquals(2, calls)
    }

    @Test
    fun `一次发送只解析一次提示词，工具循环不重复解析`() = runTest {
        var calls = 0
        val env = Env(
            tools = listOf(weatherTool()),
            promptSource = { _, _ -> calls++; "人设" },
        )
        env.client.enqueue(flow { emit(toolCallDelta()) })
        env.client.enqueueText("北京 25°C 晴")

        env.session.send("c1", "查天气", config).toList()

        // 一次 send = 一次完整对话（含工具循环），所以只该解析一次。
        // 每轮都重解析的话，第二轮的工具结果会改变命中结果 ——
        // 而那时第一条 system 早就发出去了，改了也没用，只会让
        // 「这一轮到底注入了什么」变得无法复现
        assertEquals(1, calls)
    }

    // ------------------------------------------------------------ 开场白

    @Test
    fun `新会话里开场白落成角色说的第一句话`() = runTest {
        val env = Env(greetingSource = { "（茶山脚下）……你来啦。" })
        env.client.enqueueText("你好")

        env.session.send("c1", "你好", config).toList()

        // 落盘顺序就是显示顺序（排序键是 created_at, id）：
        // 开场白必须在用户那句话**之前**
        assertEquals(
            listOf(ChatMessage.Role.Assistant, ChatMessage.Role.User),
            env.store.writes.take(2).map { it.message.role },
        )
        val greeting = env.store.writes.first()
        assertEquals("（茶山脚下）……你来啦。", greeting.message.content)
        assertEquals(MessageStatus.Complete, greeting.status)
    }

    @Test
    fun `开场白在请求里折进系统提示词，而不是当成 assistant 消息发出去`() = runTest {
        val env = Env(greetingSource = { "你来啦。" })
        env.client.enqueueText("嗯")

        env.session.send("c1", "你好", config).toList()

        // 消息数组的第一条**不能**是 assistant：Llama-3 那类对话模板要求
        // 以 user 开头，会直接拒（"Conversation roles must alternate"）
        val sent = env.client.requests.single().messages
        assertEquals(listOf("system", "user"), sent.map { it.role })
        assertTrue(
            "开场白要以「你已经说过」的形式进系统提示词：${sent.first().content}",
            sent.first().content.orEmpty().contains("你来啦。"),
        )
    }

    @Test
    fun `开场白会进历史，所以世界书扫得到它`() = runTest {
        var seen: List<ChatMessage> = emptyList()
        val env = Env(
            promptSource = { _, history -> seen = history; null },
            greetingSource = { "你来啦。" },
        )
        env.client.enqueueText("好")

        env.session.send("c1", "在吗", config).toList()

        // 开场白是角色说的第一句话，属于对话内容 —— 世界书是按内容命中的，
        // 卡里那句开场白提到的名字/地名就该能激活词条
        assertEquals(listOf(ChatMessage.Role.Assistant, ChatMessage.Role.User), seen.map { it.role })
        assertEquals("你来啦。", seen.first().content)
    }

    @Test
    fun `会话里已经有消息时不再落开场白`() = runTest {
        val env = Env(greetingSource = { "你来啦。" })
        env.store.seed(msg(1, "之前说过的话", ChatMessage.Role.User))

        env.client.enqueueText("好")
        env.session.send("c1", "第二句", config).toList()

        // 中途给老会话换角色是允许的。那种情况下插一条「角色先开口」会把它
        // 塞到对话中间 —— 界面上就是「聊到一半角色突然自我介绍了一下」
        assertFalse(env.store.writes.any { it.message.content == "你来啦。" })
    }

    @Test
    fun `开场白只在第一条消息时落一次`() = runTest {
        val env = Env(greetingSource = { "你来啦。" })
        env.client.enqueueText("一")
        env.client.enqueueText("二")

        env.session.send("c1", "第一句", config).toList()
        env.session.send("c1", "第二句", config).toList()

        assertEquals(1, env.store.writes.count { it.message.content == "你来啦。" })
    }

    @Test
    fun `开场白空白时什么也不落`() = runTest {
        val env = Env(greetingSource = { "   " })
        env.client.enqueueText("好")

        env.session.send("c1", "在吗", config).toList()

        assertEquals(ChatMessage.Role.User, env.store.writes.first().message.role)
    }

    @Test
    fun `没有开场白来源时不会在用户消息之前插任何东西`() = runTest {
        val env = Env()
        env.client.enqueueText("好")

        env.session.send("c1", "在吗", config).toList()

        // 第一次写入就是用户那条 —— 和加 `greetingSource` 之前**完全一样**。
        // （一次 send 总共写三次：用户那条、assistant 占位、收尾）
        assertEquals(ChatMessage.Role.User, env.store.writes.first().message.role)
    }

    @Test
    fun `编辑重发第一条用户消息不会把开场白删掉`() = runTest {
        val env = Env(greetingSource = { "你来啦。" })
        env.client.enqueueText("一")
        env.client.enqueueText("二")
        env.session.send("c1", "在吗", config).toList()

        val user = env.store.writes.first { it.message.role == ChatMessage.Role.User }
        env.session
            .editAndResend("c1", MessageCursor(user.createdAt, user.id), "改了", config)
            .toList()

        // 开场白的 created_at 与第一条用户消息**相同**，但 id 更小 ——
        // 而截断的条件是 `created_at > c OR (created_at = c AND id >= id)`，
        // 所以它落在「删掉」的那一侧之外。这一条钉住的就是那个同毫秒的边界
        assertEquals(listOf("你来啦。", "改了"), env.store.contents().take(2))
    }
}
