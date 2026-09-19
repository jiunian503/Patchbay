package com.aichat.ui.chat

import com.aichat.chat.ChatMessage
import com.aichat.chat.ChatSession
import com.aichat.chat.ConversationEngine
import com.aichat.chat.ConversationStore
import com.aichat.chat.ConversationSummary
import com.aichat.chat.MessageCursor
import com.aichat.chat.MessageStatus
import com.aichat.chat.StoredMessage
import com.aichat.chat.TranscriptPage
import com.aichat.core.data.ResolvedProvider
import com.aichat.di.ChatDeps
import com.aichat.di.ProviderChoice
import com.aichat.domain.llm.ChatStreamEvent
import com.aichat.domain.llm.FinishReason
import com.aichat.domain.render.InlineSpan
import com.aichat.domain.render.MarkdownBlock
import com.aichat.domain.tool.SimpleToolRegistry
import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolApprover
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolRegistry
import com.aichat.domain.tool.ToolResult
import com.aichat.network.ChatApiException
import com.aichat.network.ChatCompletionClient
import com.aichat.network.ChatCompletionRequest
import com.aichat.network.ProviderConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** 按脚本回放的客户端。 */
private class ScriptedClient : ChatCompletionClient {

    private val rounds = ArrayDeque<Flow<ChatStreamEvent>>()

    fun enqueue(flow: Flow<ChatStreamEvent>) {
        rounds += flow
    }

    fun enqueueText(vararg chunks: String) {
        enqueue(flow { chunks.forEach { emit(ChatStreamEvent.TextDelta(it)) } })
    }

    fun enqueueFailure(error: Throwable) {
        enqueue(flow { throw error })
    }

    override fun stream(request: ChatCompletionRequest): Flow<ChatStreamEvent> =
        rounds.removeFirstOrNull() ?: flow { }
}

/**
 * 极简内存存储。
 *
 * 与 `:chat` 测试里那份的区别：这里只需要「让 ChatSession 能跑起来 +
 * 让 transcript 返回内容」，所以实现得更短。刻意**不**复用 `:data` 的测试夹具 ——
 * 那会让 `:app` 的单测先编译一遍 Room 相关代码，失去秒级反馈的意义。
 */
private class MemoryStore(initial: List<StoredMessage> = emptyList()) : ConversationStore {

    val rows = LinkedHashMap<Long, StoredMessage>()

    init {
        initial.forEach { rows[it.id] = it }
    }

    private fun ordered(conversationId: String) = rows.values
        .filter { it.conversationId == conversationId }
        .sortedWith(compareBy({ it.createdAt }, { it.id }))

    override suspend fun history(conversationId: String, limit: Int): List<ChatMessage> =
        ordered(conversationId)
            .filter { it.status != MessageStatus.Streaming }
            .takeLast(limit)
            .map { it.message }

    override suspend fun transcript(
        conversationId: String,
        limit: Int,
        before: MessageCursor?,
    ): TranscriptPage {
        val all = ordered(conversationId)

        // 游标语义与真实 SQL 一致（复合游标），见 MessageDao.pageBefore ——
        // 假实现只比 id 的话，「翻页不漏消息」这条在假实现上永远测不出来
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
        rows[record.id] = record
    }

    override suspend fun delete(conversationId: String, messageId: Long) {
        rows.remove(messageId)
    }

    /**
     * 「从 [from] 起（含）往后全部丢掉」。
     *
     * 游标是**复合**的（`createdAt` 与 `id` 一起比），与真实 SQL 一致 ——
     * 只比 id 的话，一旦某行的 `createdAt` 与 `id` 不同序就会多删或漏删，
     * 而假实现只比 id 的话，那种写法在测试里永远暴露不出来。
     *
     * 这里是**真删**：假实现里没有 `deleted` 标记，[rows] 里本来就只有
     * 活着的那些行，所以「软删」等价于从这张表里拿掉。
     */
    override suspend fun deleteFrom(conversationId: String, from: MessageCursor) {
        rows.keys.toList().forEach { id ->
            val row = rows.getValue(id)
            if (row.conversationId == conversationId &&
                (row.createdAt > from.createdAt ||
                    (row.createdAt == from.createdAt && row.id >= from.id))
            ) {
                rows.remove(id)
            }
        }
    }

    override suspend fun allMessages(conversationId: String): List<StoredMessage> =
        ordered(conversationId)

    override suspend fun clearMessages(conversationId: String) {
        rows.keys.toList().forEach { if (rows.getValue(it).conversationId == conversationId) rows.remove(it) }
    }

    override suspend fun ensureConversation(conversationId: String, title: String, now: Long) = Unit

    override suspend fun titleIfUntitled(conversationId: String, title: String, now: Long) = Unit

    override suspend fun listConversations(limit: Int): List<ConversationSummary> = emptyList()

    override suspend fun renameConversation(conversationId: String, title: String, now: Long) = Unit

    override suspend fun setPinned(conversationId: String, pinned: Boolean) = Unit

    override suspend fun deleteConversation(conversationId: String) {
        clearMessages(conversationId)
    }

    override suspend fun failInterrupted(now: Long): Int = 0
}

private class FakeChatDeps(
    val store: MemoryStore = MemoryStore(),
    val client: ScriptedClient = ScriptedClient(),
    private val provider: ResolvedProvider? = readyProvider,
    private val tools: ToolRegistry = ToolRegistry.Empty,
    /** 额外的服务商，用来测「换到另一个」。 */
    private val others: List<ResolvedProvider> = emptyList(),
) : ChatDeps {

    private var counter = 1_000L

    private val all: List<ResolvedProvider> = listOfNotNull(provider) + others

    /** 记下最近一次「换服务商」的请求，让测试能断言界面真的调了它。 */
    var lastRepin: Pair<String, String>? = null
        private set

    /** 换过之后就是它。简单模拟「黏住」—— 完整语义在 `ConversationRouterTest`。 */
    private var pinned: ResolvedProvider? = provider

    override suspend fun providerForConversation(conversationId: String): ResolvedProvider? = pinned

    override suspend fun selectableProviders(): List<ProviderChoice> =
        all.map { ProviderChoice(it.id, it.name, it.model, it.hasApiKey) }

    override suspend fun repinConversation(conversationId: String, providerId: String): Boolean {
        lastRepin = conversationId to providerId
        val target = all.firstOrNull { it.id == providerId } ?: return false
        pinned = target
        return true
    }

    override suspend fun transcript(
        conversationId: String,
        limit: Int,
        before: MessageCursor?,
    ): TranscriptPage = store.transcript(conversationId, limit, before)

    override suspend fun allMessages(conversationId: String): List<StoredMessage> =
        store.allMessages(conversationId)

    /**
     * 记下最近一次建会话时传进来的审批器 —— 这样测试就能验证
     * 「ViewModel 确实把自己那个闸门交给了引擎」，而不是各用各的
     * （那样弹框永远不出现，但也不报错）。
     */
    var lastApprover: ToolApprover? = null
        private set

    override fun newSession(provider: ResolvedProvider, approver: ToolApprover): ChatSession {
        lastApprover = approver
        return ChatSession(
            engine = ConversationEngine(client, tools = tools, approver = approver),
            store = store,
            ids = { counter++ },
            clock = { counter },
        )
    }

    companion object {
        val readyProvider = ResolvedProvider(
            id = "p1",
            name = "深度求索",
            model = "deepseek-chat",
            keyHint = "…1234",
            config = ProviderConfig(baseUrl = "api.deepseek.com", apiKey = "sk-test"),
        )

        /** 第二个服务商，用来测「换过去」。 */
        val otherProvider = ResolvedProvider(
            id = "p2",
            name = "另一个",
            model = "other-model",
            keyHint = "…5678",
            config = ProviderConfig(baseUrl = "api.other.com", apiKey = "sk-other"),
        )

        /** 配了服务商但没填 Key。 */
        val keylessProvider = ResolvedProvider(
            id = "p1",
            name = "深度求索",
            model = "deepseek-chat",
            keyHint = null,
            config = null,
        )
    }
}

private fun stored(
    id: Long,
    text: String,
    role: ChatMessage.Role = ChatMessage.Role.User,
    status: MessageStatus = MessageStatus.Complete,
    createdAt: Long = id,
) = StoredMessage(
    id = id,
    conversationId = "c1",
    message = ChatMessage(role = role, content = text),
    status = status,
    createdAt = createdAt,
    updatedAt = createdAt,
)

/**
 * [ChatViewModel] 的状态机测试。
 *
 * 这个类里最容易出错的是「流式过程中画什么、结束后画什么」——
 * 两条数据源（live / messages）如果切错了时机，用户会看到内容闪一下、
 * 或者同一句话出现两遍。下面每个用例都在钉住其中一条边界。
 *
 * ## 为什么用 runBlocking 而不是 runTest
 *
 * `runTest` 会拿自己的调度器跟 `Dispatchers.Main` 比对，两者不一致时直接抛异常；
 * 而这里的假客户端全是同步 flow，用 [UnconfinedTestDispatcher] 让
 * `viewModelScope` 里的协程立刻执行、状态在调用返回时就已更新，
 * 断言可以写成同步的 —— 不需要虚拟时间，也就没有调度器可比。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `进入页面时从库里加载历史`() = runBlocking {
        val deps = FakeChatDeps(
            store = MemoryStore(
                listOf(
                    stored(1L, "你好"),
                    stored(2L, "你好，有什么可以帮你的", role = ChatMessage.Role.Assistant),
                )
            )
        )

        val vm = ChatViewModel(deps, "c1")

        assertEquals(
            listOf("你好", "你好，有什么可以帮你的"),
            vm.state.value.messages.map { it.content },
        )
        assertFalse(vm.state.value.loading)
        assertEquals("深度求索", vm.state.value.providerName)
        assertEquals("deepseek-chat", vm.state.value.model)
        assertFalse(vm.state.value.needsProvider)
    }

    @Test
    fun `没有填 Key 时标记 needsProvider`() = runBlocking {
        val vm = ChatViewModel(FakeChatDeps(provider = FakeChatDeps.keylessProvider), "c1")

        assertTrue(vm.state.value.needsProvider)
        assertFalse(vm.state.value.canSend)
    }

    @Test
    fun `没有可用服务商时不发请求 只给提示`() = runBlocking {
        val deps = FakeChatDeps(provider = null)
        val vm = ChatViewModel(deps, "c1")

        vm.onInputChange("在吗")
        vm.send()

        assertTrue(vm.state.value.needsProvider)
        assertTrue(vm.state.value.error!!.contains("服务商"))
        // 输入内容保留，用户配好之后不用重打
        assertEquals("在吗", vm.state.value.input)
        assertTrue(deps.store.rows.isEmpty())
    }

    @Test
    fun `换服务商之后标题跟着变`() = runBlocking {
        val deps = FakeChatDeps(others = listOf(FakeChatDeps.otherProvider))
        val vm = ChatViewModel(deps, "c1")
        assertEquals("深度求索", vm.state.value.providerName)

        vm.repin("p2")

        assertEquals("换的必须是**这个会话**", "c1" to "p2", deps.lastRepin)
        assertEquals(
            "换完必须刷新标题 —— 不刷的话用户会以为没换成功，" +
                "而实际上下一条消息已经用新服务商发出去了",
            "另一个",
            vm.state.value.providerName,
        )
        assertEquals("p2", vm.state.value.providerId)
    }

    @Test
    fun `选择器候选在点开之前是 null 而不是空列表`() = runBlocking {
        val deps = FakeChatDeps(others = listOf(FakeChatDeps.otherProvider))
        val vm = ChatViewModel(deps, "c1")

        assertNull(
            "还没点开时必须是 null，不是空列表 —— 界面要能区分「还没读出来」和" +
                "「一个都没配」。混成一个的话用户点开会看到一片空白，" +
                "分不清是在加载还是真没有",
            vm.state.value.choices,
        )

        vm.loadProviders()

        assertEquals(listOf("p1", "p2"), vm.state.value.choices?.map { it.id })
        assertTrue("两个都填了 Key", vm.state.value.choices?.all { it.hasApiKey } == true)
    }

    // ---- 长会话分页 ----

    /** 造一个比默认一页（200 条）多一条的会话。 */
    private fun longConversation() = MemoryStore(
        (1..201).map { stored(it.toLong(), "第 $it 条") }
    )

    @Test
    fun `打开长会话时只加载最近一页 并知道前面还有`() = runBlocking {
        val vm = ChatViewModel(FakeChatDeps(store = longConversation()), "c1")

        assertEquals("默认一页 200 条", 200, vm.state.value.messages.size)
        assertTrue("还有 1 条更早的没加载", vm.state.value.hasMore)
        assertEquals(
            "取的是最近一页 —— 最早那条（第 1 条）不该在里面",
            "第 2 条",
            vm.state.value.messages.first().content,
        )
    }

    @Test
    fun `往上翻页把更早的消息拼在前面`() = runBlocking {
        val vm = ChatViewModel(FakeChatDeps(store = longConversation()), "c1")

        vm.loadEarlier()

        assertEquals(201, vm.state.value.messages.size)
        assertEquals("第 1 条", vm.state.value.messages.first().content)
        assertEquals("最新那条没动", "第 201 条", vm.state.value.messages.last().content)
        assertFalse("已经到最早了，按钮该消失", vm.state.value.hasMore)
    }

    @Test
    fun `翻页拼起来不重不漏`() = runBlocking {
        val vm = ChatViewModel(FakeChatDeps(store = longConversation()), "c1")

        vm.loadEarlier()

        assertEquals(
            "拼起来必须正好是全部 201 条，一条不多一条不少",
            (1..201).map { "第 $it 条" },
            vm.state.value.messages.map { it.content },
        )
    }

    @Test
    fun `已经到最早之后再点不会重复加载`() = runBlocking {
        val vm = ChatViewModel(FakeChatDeps(store = longConversation()), "c1")

        vm.loadEarlier()
        assertFalse(vm.state.value.hasMore)

        // 按钮这时候已经消失了，用户点不到；但状态机自己也得挡住 ——
        // 靠界面隐藏按钮来保证正确性，等于把不变量放在了视图层
        vm.loadEarlier()

        assertEquals(201, vm.state.value.messages.size)
    }

    @Test
    fun `消息不足一页时没有加载更早的入口`() = runBlocking {
        val store = MemoryStore((1..5).map { stored(it.toLong(), "第 $it 条") })

        val vm = ChatViewModel(FakeChatDeps(store = store), "c1")

        assertEquals(5, vm.state.value.messages.size)
        assertFalse(vm.state.value.hasMore)
    }

    @Test
    fun `空输入不发送`() = runBlocking {
        val deps = FakeChatDeps()
        val vm = ChatViewModel(deps, "c1")

        vm.onInputChange("   ")
        vm.send()

        assertFalse(vm.state.value.streaming)
        assertTrue(deps.store.rows.isEmpty())
    }

    @Test
    fun `发送后立刻清空输入并乐观显示用户消息`() = runBlocking {
        val deps = FakeChatDeps()
        val vm = ChatViewModel(deps, "c1")
        deps.client.enqueueText("在的")

        vm.onInputChange("在吗")
        vm.send()

        // 用户那句必须立刻可见 —— 等网络往返再显示会有明显的「点了没反应」感。
        // 结束时 refresh() 会用库里的真实数据覆盖它，所以不会重复
        assertEquals("", vm.state.value.input)
        assertEquals(1, vm.state.value.messages.count { it.role == ChatMessage.Role.User })
        assertEquals("在吗", vm.state.value.messages.first().content)
    }

    @Test
    fun `结束后 streaming 归位 live 清空 并从库里刷新`() = runBlocking {
        val deps = FakeChatDeps()
        val vm = ChatViewModel(deps, "c1")
        deps.client.enqueueText("答", "案")

        vm.onInputChange("问")
        vm.send()

        val state = vm.state.value
        assertFalse(state.streaming)
        assertTrue("结束后必须清空 live，否则同一句话会显示两遍", state.live.isEmpty())
        // 用户消息 + 助手回复，两条都来自数据库
        assertEquals(listOf("问", "答案"), state.messages.map { it.content })
        assertEquals(ChatMessage.Role.Assistant, state.messages.last().role)
    }

    @Test
    fun `流式文本追加到同一个段而不是每片一段`() = runBlocking {
        val deps = FakeChatDeps()
        val vm = ChatViewModel(deps, "c1")
        val snapshots = mutableListOf<List<LiveSegment>>()
        deps.client.enqueue(
            flow {
                emit(ChatStreamEvent.TextDelta("一"))
                snapshots += vm.state.value.live
                emit(ChatStreamEvent.TextDelta("二"))
                snapshots += vm.state.value.live
                emit(ChatStreamEvent.TextDelta("三"))
            }
        )

        vm.onInputChange("问")
        vm.send()

        // 每个中间态都只有**一个**文本段，内容在增长。
        // 每片一段的话列表会疯狂重建，滚动位置也会跳
        assertEquals(1, snapshots[0].size)
        assertEquals("一", (snapshots[0].single() as LiveSegment.Text).text)
        assertEquals(1, snapshots[1].size)
        assertEquals("一二", (snapshots[1].single() as LiveSegment.Text).text)
    }

    /**
     * 流式正文要**边收边解析**，而不是等结束了再解析。
     *
     * 解析放在 ViewModel 里做（不是 composable 里）有两个原因：
     * 一是 composable 每帧都可能重组，放那儿等于每帧重新解析；
     * 二是解析器要跨增量复用才有块缓存，而 composable 没有稳定的持有位置。
     */
    @Test
    fun `流式文本被增量解析成 Markdown 块`() = runBlocking {
        val deps = FakeChatDeps()
        val vm = ChatViewModel(deps, "c1")
        val snapshots = mutableListOf<List<LiveSegment>>()

        deps.client.enqueue(
            flow {
                emit(ChatStreamEvent.TextDelta("## 标题\n\n"))
                snapshots += vm.state.value.live
                emit(ChatStreamEvent.TextDelta("正文**粗**"))
                snapshots += vm.state.value.live
            }
        )

        vm.onInputChange("问")
        vm.send()

        val early = (snapshots[0].single() as LiveSegment.Text).blocks
        assertEquals(
            listOf(MarkdownBlock.Heading(2, listOf(InlineSpan.Text("标题")))),
            early,
        )

        val late = (snapshots[1].single() as LiveSegment.Text).blocks
        assertEquals(2, late.size)
        assertTrue(late[0] is MarkdownBlock.Heading)
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph(
                    listOf(
                        InlineSpan.Text("正文"),
                        InlineSpan.Strong(listOf(InlineSpan.Text("粗"))),
                    )
                )
            ),
            listOf(late[1]),
        )
    }

    /**
     * 一次回复里「文本 → 工具 → 文本」会出现**多个**文本段，
     * 每个段都要用自己的解析器状态。
     *
     * 这条用例守的是「忘了重置解析器」：不重置的话，第二个段会拿第一个段
     * 攒下的偏移量去切字符串 —— 新段比旧段短时直接越界崩掉，
     * 比旧段长时切出来的内容是错的。
     */
    @Test
    fun `工具调用之后的文本段重新开始解析`() = runBlocking {
        val deps = FakeChatDeps()
        val vm = ChatViewModel(deps, "c1")

        deps.client.enqueue(
            flow {
                emit(ChatStreamEvent.TextDelta("前一段\n\n"))
                emit(ChatStreamEvent.ToolCallDelta(0, "call_a", "get_weather", "{}"))
            }
        )
        var finalSegments: List<LiveSegment> = emptyList()
        deps.client.enqueue(
            flow {
                emit(ChatStreamEvent.TextDelta("后一段"))
                // 第二轮开始后才有完整的三个段，在这一刻取快照
                finalSegments = vm.state.value.live
            }
        )

        vm.onInputChange("北京天气")
        vm.send()

        val texts = finalSegments.filterIsInstance<LiveSegment.Text>()
        assertEquals(2, texts.size)
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(InlineSpan.Text("前一段")))),
            texts[0].blocks,
        )
        // 关键：只有一个段落。带着上一段的缓存继续解析的话，
        // 这里要么崩，要么多出一段「前一段」的内容
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(InlineSpan.Text("后一段")))),
            texts[1].blocks,
        )
    }

    @Test
    fun `文本 工具 文本 的先后顺序被保留`() = runBlocking {
        val deps = FakeChatDeps()
        val vm = ChatViewModel(deps, "c1")
        val mid = mutableListOf<List<LiveSegment>>()
        val late = mutableListOf<List<LiveSegment>>()

        deps.client.enqueue(
            flow {
                emit(ChatStreamEvent.TextDelta("我查一下"))
                emit(ChatStreamEvent.ToolCallDelta(0, "call_a", "get_weather", "{}"))
            }
        )
        // 第二轮开始时，第一轮的文本与工具卡片都已经画完了 ——
        // 在这一刻取快照才有意义
        deps.client.enqueue(
            flow {
                mid += vm.state.value.live
                emit(ChatStreamEvent.TextDelta("北京 25°C"))
                late += vm.state.value.live
            }
        )

        vm.onInputChange("北京天气")
        vm.send()

        val first = mid.single()
        assertEquals(2, first.size)
        assertEquals("我查一下", (first[0] as LiveSegment.Text).text)
        val tool = first[1] as LiveSegment.Tool
        assertEquals("get_weather", tool.name)
        // 工具已经执行完（第一轮结束前就执行了），卡片不该还在转圈
        assertFalse(tool.running)

        // 第二轮的文本必须接在工具卡片**后面**，不能并进第一段里 ——
        // 并进去的话用户会看到「答案已经在上面了，卡片还在往上涨」
        val second = late.single()
        assertEquals(3, second.size)
        assertEquals("我查一下", (second[0] as LiveSegment.Text).text)
        assertEquals("北京 25°C", (second[2] as LiveSegment.Text).text)
    }

    @Test
    fun `工具执行失败时把错误回显在卡片上`() = runBlocking {
        val deps = FakeChatDeps()
        val vm = ChatViewModel(deps, "c1")
        val mid = mutableListOf<List<LiveSegment>>()
        deps.client.enqueue(flow { emit(ChatStreamEvent.ToolCallDelta(0, "call_a", "get_weather", "{}")) })
        deps.client.enqueue(
            flow {
                mid += vm.state.value.live
                emit(ChatStreamEvent.TextDelta("好"))
            }
        )

        vm.onInputChange("问")
        vm.send()

        val tool = mid.single().first { it is LiveSegment.Tool } as LiveSegment.Tool
        // 没有注册同名工具时，引擎会回灌「没有名为 get_weather 的工具」，
        // 用户应该能在卡片上看到，而不是只看到一张空白卡片
        assertTrue(tool.output!!.contains("get_weather"))
        assertTrue(tool.isError)
        assertFalse(tool.running)
    }

    @Test
    fun `接口报错时把原因放进 error 并结束 streaming`() = runBlocking {
        val deps = FakeChatDeps()
        val vm = ChatViewModel(deps, "c1")
        deps.client.enqueueFailure(ChatApiException.Http(401, "", "HTTP 401：Invalid API key"))

        vm.onInputChange("问")
        vm.send()

        assertFalse(vm.state.value.streaming)
        assertTrue(vm.state.value.error!!.contains("401"))
        // 失败也要落库，刷新后能看到那条失败记录
        assertTrue(vm.state.value.messages.any { it.status == MessageStatus.Failed })
    }

    @Test
    fun `网络异常时给出人话`() = runBlocking {
        val deps = FakeChatDeps()
        val vm = ChatViewModel(deps, "c1")
        deps.client.enqueueFailure(ChatApiException.Network(IOException("连接被重置")))

        vm.onInputChange("问")
        vm.send()

        assertTrue(vm.state.value.error!!.contains("连接被重置"))
    }

    @Test
    fun `用户可以关掉错误提示`() = runBlocking {
        val deps = FakeChatDeps()
        val vm = ChatViewModel(deps, "c1")
        deps.client.enqueueFailure(ChatApiException.Network(IOException("boom")))

        vm.onInputChange("问")
        vm.send()
        vm.dismissError()

        assertNull(vm.state.value.error)
    }

    @Test
    fun `发送中不能重复发送`() = runBlocking {
        val deps = FakeChatDeps()
        val vm = ChatViewModel(deps, "c1")
        // 第一轮挂住不返回
        deps.client.enqueue(flow { awaitCancellation() })

        vm.onInputChange("第一问")
        vm.send()
        assertTrue(vm.state.value.streaming)

        vm.onInputChange("第二问")
        vm.send()

        // 第二问被挡下：既没进库，输入也没被清掉
        assertEquals("第二问", vm.state.value.input)
        assertEquals(1, deps.store.rows.values.count { it.message.role == ChatMessage.Role.User })

        vm.stop()
    }

    @Test
    fun `停止后状态收敛且内容保留`() = runBlocking {
        val deps = FakeChatDeps()
        val vm = ChatViewModel(deps, "c1")
        deps.client.enqueue(
            flow {
                emit(ChatStreamEvent.TextDelta("写了一半"))
                awaitCancellation()
            }
        )

        vm.onInputChange("问")
        vm.send()
        vm.stop()

        // stop() 取消协程后，收尾要跑完 —— 用户按了停止，
        // 更需要看到「刚才那半截已经存下来了」
        assertFalse(vm.state.value.streaming)
        assertTrue(vm.state.value.live.isEmpty())
        val reply = vm.state.value.messages.last()
        assertEquals(ChatMessage.Role.Assistant, reply.role)
        assertEquals("写了一半", reply.content)
        assertEquals(MessageStatus.Stopped, reply.status)
    }

    @Test
    fun `canSend 的判定`() = runBlocking {
        val vm = ChatViewModel(FakeChatDeps(), "c1")

        assertFalse("空输入不能发", vm.state.value.canSend)
        vm.onInputChange("  ")
        assertFalse("纯空白不能发", vm.state.value.canSend)
        vm.onInputChange("有内容")
        assertTrue(vm.state.value.canSend)
    }

    // ---------- 工具审批 ----------

    /**
     * 需要确认的工具必须真的把界面挂起来。
     *
     * 这条链路容易在「接线」上断掉：ViewModel 自己造一个闸门、引擎用默认的
     * `AllowAll`，两边各自都对，但弹框永远不出现 —— 而且不报任何错。
     * 所以这里断言的是**端到端**：从引擎请求工具，到界面拿到待确认请求。
     */
    @Test
    fun `需要确认的工具会让界面收到待确认请求`() = runBlocking {
        val deps = FakeChatDeps(tools = SimpleToolRegistry(listOf(confirmingTool)))
        deps.client.enqueue(
            flow {
                emit(
                    ChatStreamEvent.ToolCallDelta(
                        index = 0,
                        id = "call_1",
                        name = "fetch_url",
                        argumentsDelta = """{"url":"https://example.com"}""",
                    )
                )
                emit(ChatStreamEvent.Finished(FinishReason.ToolCalls))
            }
        )
        deps.client.enqueueText("抓完了")

        val vm = ChatViewModel(deps, "c1")
        vm.onInputChange("帮我看看")
        vm.send()

        val request = vm.pendingApproval.value
        assertNotNull("应该弹出一个待确认请求", request)
        assertEquals("fetch_url", request!!.toolName)
        // 参数要能让人读懂
        assertTrue(request.argumentsJson, request.argumentsJson.contains("https://example.com"))

        // 弹框期间对话是挂起的：还没写出工具结果
        assertTrue(
            "用户没点之前不该执行工具",
            deps.store.rows.values.none { it.message.role == ChatMessage.Role.Tool },
        )

        vm.decideTool(true)
        waitUntil { deps.store.rows.values.any { it.message.role == ChatMessage.Role.Tool } }
        assertNull("决定之后弹框要收掉", vm.pendingApproval.value)
    }

    @Test
    fun `拒绝后工具不执行且模型收到拒绝`() = runBlocking {
        val deps = FakeChatDeps(tools = SimpleToolRegistry(listOf(confirmingTool)))
        deps.client.enqueue(
            flow {
                emit(
                    ChatStreamEvent.ToolCallDelta(
                        index = 0,
                        id = "call_1",
                        name = "fetch_url",
                        argumentsDelta = """{"url":"https://example.com"}""",
                    )
                )
                emit(ChatStreamEvent.Finished(FinishReason.ToolCalls))
            }
        )
        deps.client.enqueueText("那我不查了")

        val vm = ChatViewModel(deps, "c1")
        vm.onInputChange("帮我看看")
        vm.send()
        vm.decideTool(false)

        waitUntil { deps.store.rows.values.any { it.message.role == ChatMessage.Role.Tool } }

        val toolRow = deps.store.rows.values.first { it.message.role == ChatMessage.Role.Tool }
        assertTrue(
            "拒绝要作为工具结果回灌给模型，让它有机会换个方式：${toolRow.message.content}",
            toolRow.message.content.contains("拒绝"),
        )
    }

    /**
     * 用户在弹框上按了停止 —— 这是最容易挂死的路径。
     *
     * 挂死的表现是「模型一直转圈」，没有任何报错。
     */
    @Test
    fun `弹框期间点停止不会挂死且弹框会收掉`() = runBlocking {
        val deps = FakeChatDeps(tools = SimpleToolRegistry(listOf(confirmingTool)))
        deps.client.enqueue(
            flow {
                emit(
                    ChatStreamEvent.ToolCallDelta(
                        index = 0,
                        id = "call_1",
                        name = "fetch_url",
                        argumentsDelta = """{"url":"https://example.com"}""",
                    )
                )
                emit(ChatStreamEvent.Finished(FinishReason.ToolCalls))
            }
        )

        val vm = ChatViewModel(deps, "c1")
        vm.onInputChange("帮我看看")
        vm.send()
        assertNotNull(vm.pendingApproval.value)

        vm.stop()

        waitUntil { !vm.state.value.streaming }
        assertNull("停止之后弹框必须收掉，否则会留一个点不动的框", vm.pendingApproval.value)
        assertFalse(vm.state.value.streaming)
    }

    @Test
    fun `不需要确认的工具直接执行不弹框`() = runBlocking {
        val deps = FakeChatDeps(tools = SimpleToolRegistry(listOf(quietTool)))
        deps.client.enqueue(
            flow {
                emit(
                    ChatStreamEvent.ToolCallDelta(
                        index = 0,
                        id = "call_1",
                        name = "get_current_time",
                        argumentsDelta = "{}",
                    )
                )
                emit(ChatStreamEvent.Finished(FinishReason.ToolCalls))
            }
        )
        deps.client.enqueueText("现在是下午")

        val vm = ChatViewModel(deps, "c1")
        vm.onInputChange("几点了")
        vm.send()

        waitUntil { deps.store.rows.values.any { it.message.role == ChatMessage.Role.Tool } }
        assertNull("只读工具不该弹框", vm.pendingApproval.value)
    }

    @Test
    fun `没有待确认请求时点允许是安全的空操作`() = runBlocking {
        val vm = ChatViewModel(FakeChatDeps(), "c1")
        vm.decideTool(true)
        vm.decideTool(false)
        assertNull(vm.pendingApproval.value)
    }

    // ------------------------------------------------------------ 重新生成 / 编辑重发

    /** 一问一答的两轮对话：id 1..4，`createdAt` 与 id 同序。 */
    private fun twoTurns() = MemoryStore(
        listOf(
            stored(1L, "第一问"),
            stored(2L, "第一答", role = ChatMessage.Role.Assistant),
            stored(3L, "第二问"),
            stored(4L, "第二答", role = ChatMessage.Role.Assistant),
        )
    )

    @Test
    fun `重新生成把那条及其之后立刻从界面抹掉`() = runBlocking {
        val deps = FakeChatDeps(store = twoTurns())
        lateinit var vm: ChatViewModel
        var duringReply: List<Long> = emptyList()

        // 在「模型开始回答」的那一刻取快照 —— 此时库里还没截断完，
        // 所以它测的正是「界面自己有没有先抹掉」
        deps.client.enqueue(
            flow {
                duringReply = vm.state.value.messages.map { it.id }
                emit(ChatStreamEvent.TextDelta("新答"))
            }
        )
        vm = ChatViewModel(deps, "c1")

        vm.regenerate(4L)

        // 不先抹掉的话，新回复会和旧的那条并排显示到 refresh() 为止 ——
        // 用户看到的是「它生成了两遍」，而其中一遍马上又要凭空消失
        assertEquals(listOf(1L, 2L, 3L), duringReply)
        assertEquals(
            listOf("第一问", "第一答", "第二问", "新答"),
            vm.state.value.messages.map { it.content },
        )
    }

    @Test
    fun `重新生成只认最后一条助手消息`() = runBlocking {
        val deps = FakeChatDeps(store = twoTurns())
        val vm = ChatViewModel(deps, "c1")

        // 对更早的那条做，等于把它后面的对话一起删掉再重跑 ——
        // 用户点它时想的是「这条答得不好，换一个」，不是「把后面的都删了」
        vm.regenerate(2L)

        assertEquals(4, deps.store.rows.size)
        assertFalse(vm.state.value.streaming)
    }

    @Test
    fun `用户消息不能重新生成`() = runBlocking {
        // 会话停在一句问话上（模型还没答，或者答的那条被删了）
        val deps = FakeChatDeps(
            store = MemoryStore(
                listOf(
                    stored(1L, "第一问"),
                    stored(2L, "第一答", role = ChatMessage.Role.Assistant),
                    stored(3L, "第二问"),
                )
            )
        )
        val vm = ChatViewModel(deps, "c1")

        // 对用户消息重新生成，效果是「把它和它后面的一起删掉，然后重跑」——
        // 于是模型会去回答**上一个问题**，对话里出现两条连着答同一问的助手消息。
        // 想改自己那句问话，用户要的是「编辑重发」
        vm.regenerate(3L)

        assertEquals(3, deps.store.rows.size)
        assertFalse(vm.state.value.streaming)
    }

    @Test
    fun `编辑重发把那条用户消息换成新的`() = runBlocking {
        val deps = FakeChatDeps(store = twoTurns())
        lateinit var vm: ChatViewModel
        var duringReply: List<Long> = emptyList()

        deps.client.enqueue(
            flow {
                duringReply = vm.state.value.messages.map { it.id }
                emit(ChatStreamEvent.TextDelta("新答"))
            }
        )
        vm = ChatViewModel(deps, "c1")

        vm.editAndResend(3L, "第二问（改过）")

        // 原来的位置补一条待发的用户消息（id 是负的临时值），
        // 用户应该立刻看到自己改过的那句话，而不是空一段
        // 负数 id 是「还没落库」的临时标记（见 ChatViewModel.PENDING_ID）
        assertEquals(listOf(1L, 2L, -1L), duringReply)
        assertEquals(
            listOf("第一问", "第一答", "第二问（改过）", "新答"),
            vm.state.value.messages.map { it.content },
        )
    }

    @Test
    fun `编辑重发空白内容什么都不做`() = runBlocking {
        val deps = FakeChatDeps(store = twoTurns())
        val vm = ChatViewModel(deps, "c1")

        vm.editAndResend(3L, "   ")

        // 先截断后校验的实现会在这里少掉两条，而界面上给出的理由是
        // 「内容不能为空」—— 和实际发生的事完全对不上
        assertEquals(4, deps.store.rows.size)
        assertFalse(vm.state.value.streaming)
    }

    @Test
    fun `助手消息不能编辑重发`() = runBlocking {
        val deps = FakeChatDeps(store = twoTurns())
        val vm = ChatViewModel(deps, "c1")

        vm.editAndResend(2L, "我改一下助手的话")

        assertEquals(4, deps.store.rows.size)
    }

    @Test
    fun `流式过程中重新生成会被忽略`() = runBlocking {
        val deps = FakeChatDeps(store = twoTurns())
        // 永不返回的流，把界面钉在「正在回复」上
        deps.client.enqueue(flow { awaitCancellation() })
        val vm = ChatViewModel(deps, "c1")

        vm.onInputChange("新问题")
        vm.send()
        assertTrue(vm.state.value.streaming)

        vm.regenerate(4L)
        vm.editAndResend(3L, "改过")

        // 流式时列表里还挂着乐观消息，截断的边界没有意义 ——
        // 放行的话会把用户刚发出去那句话当成游标
        assertTrue("乐观消息还在，说明截断没被放行", vm.state.value.messages.any { it.id < 0 })
        vm.stop()
    }

    @Test
    fun `没有可用服务商时截断不会发生`() = runBlocking {
        val deps = FakeChatDeps(store = twoTurns(), provider = FakeChatDeps.keylessProvider)
        val vm = ChatViewModel(deps, "c1")

        vm.regenerate(4L)

        // 先删后检查服务商的实现会把消息删掉，然后发一个注定 401 的请求 ——
        // 用户丢掉的是**已经拿到的回答**，新回复也没有
        assertEquals(4, deps.store.rows.size)
        assertTrue(vm.state.value.needsProvider)
    }

    /** 轮询等待异步结果，超时即失败。用真实时间，因为引擎跑在真实调度器上。 */
    private fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("等待超时（${timeoutMs}ms）")
    }
}

/** 需要用户确认的工具，用来验证弹框链路。 */
private val confirmingTool = object : Tool {
    override val definition = ToolDefinition(
        name = "fetch_url",
        description = "抓取一个网页",
        parameters = buildJsonObject { put("type", "object") },
    )
    override val userSummary = "向你指定的地址发起一次网络请求"
    override val requiresConfirmation = true
    override suspend fun execute(arguments: JsonObject) = ToolResult.ok("抓到了")
}

/** 只读工具，不该弹框。 */
private val quietTool = object : Tool {
    override val definition = ToolDefinition(
        name = "get_current_time",
        description = "获取当前时间",
        parameters = buildJsonObject { put("type", "object") },
    )
    override val userSummary = "读取设备当前的日期和时间"
    override suspend fun execute(arguments: JsonObject) = ToolResult.ok("2026-09-18 21:00")
}
