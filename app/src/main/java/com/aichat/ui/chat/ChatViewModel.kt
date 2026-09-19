package com.aichat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.chat.ChatConfig
import com.aichat.chat.ChatEvent
import com.aichat.chat.ChatMessage
import com.aichat.chat.MessageCursor
import com.aichat.chat.MessageStatus
import com.aichat.chat.StoredMessage
import com.aichat.chat.ToolApprovalGate
import com.aichat.core.data.ResolvedProvider
import com.aichat.di.ChatDeps
import com.aichat.di.ProviderChoice
import com.aichat.domain.llm.AssistantToolCall
import com.aichat.domain.render.MarkdownBlock
import com.aichat.domain.render.StreamingMarkdownRenderer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 流式回复的**分段**视图。
 *
 * 与 [MessageUi] 的区别：一条持久化消息是「一坨文本 + 若干工具调用」，
 * 而流式过程中「文本 → 工具卡片 → 更多文本」的先后顺序是有意义的，
 * 必须按段保存。顺序错乱会让用户看到「答案已经在下面了，卡片还在往上涨」。
 *
 * 收尾后这些段会被持久化数据替换掉（那里是一条 assistant 行 + 若干 tool 行，
 * 顺序靠 `created_at, id` 还原）。
 */
sealed interface LiveSegment {
    data class Text(
        val text: String,
        /**
         * 解析好的 Markdown 块。
         *
         * 解析放在 ViewModel 里做，而不是渲染时做：一条回复会被切上百个增量，
         * 每个增量都触发一次重组，如果解析写在 composable 里，
         * 每帧都要重新解析 —— 那就白费了 [StreamingMarkdownRenderer] 的块缓存。
         *
         * 默认值是空列表，只为兼容测试里的构造；真实路径一定会填上。
         */
        val blocks: List<MarkdownBlock> = emptyList(),
    ) : LiveSegment

    data class Reasoning(val text: String) : LiveSegment

    data class Tool(
        val callId: String,
        val name: String,
        val output: String? = null,
        val isError: Boolean = false,
        val running: Boolean = true,
    ) : LiveSegment
}

/**
 * 一条可显示的消息。从 [StoredMessage] 映射而来，外加一个「刚发出、还没落库」的临时态。
 *
 * ## 为什么带着 [createdAt]
 *
 * 「重新生成」和「编辑重发」都要把库里的消息**从某条开始截断**，而那个动作
 * 收的是 [MessageCursor] —— 复合游标，`(createdAt, id)` 两半缺一不可。
 * 只带 id 的话 ViewModel 就得再去库里查一次 createdAt，而那次查询和
 * 列表之间没有任何一致性保证（用户可能正好在流式写入）。
 */
data class MessageUi(
    val id: Long,
    val role: ChatMessage.Role,
    val content: String,
    val reasoning: String? = null,
    val toolCalls: List<AssistantToolCall> = emptyList(),
    val toolCallId: String? = null,
    val status: MessageStatus = MessageStatus.Complete,
    val error: String? = null,
    val createdAt: Long = 0L,
) {
    val isPending: Boolean get() = id < 0

    /** 截断用的游标。语义见 [MessageCursor] 与 `ConversationStore.deleteFrom`。 */
    fun cursor(): MessageCursor = MessageCursor(createdAt, id)

    /**
     * 这条消息能不能「重新生成」。
     *
     * **只有助手消息可以。** 对一条用户消息做「重新生成」在库里的效果是
     * 「把它和它后面的一起删掉，然后重跑」—— 于是模型会去回答**上一个问题**，
     * 对话里出现两条连着、都在回答上一问的助手消息。想改自己那句问话，
     * 用户要的是 [canEdit]（编辑重发）。
     *
     * 工具消息也不行：它是上一轮的产物，要重跑的是产生它的那一轮。
     * 没落库的乐观消息同样不行 —— 它还没有真实 id，拿它当游标会截到空处。
     */
    val canRegenerate: Boolean get() = !isPending && role == ChatMessage.Role.Assistant

    val canEdit: Boolean get() = !isPending && role == ChatMessage.Role.User
}

data class ChatUiState(
    val messages: List<MessageUi> = emptyList(),
    val live: List<LiveSegment> = emptyList(),
    val input: String = "",
    val streaming: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,

    /**
     * **这个会话**正在用的服务商显示名。
     *
     * 注意不是「当前默认服务商」—— 会话是黏住的（见 `ConversationRouter`）。
     * 用户改了默认服务商之后，这个页面显示的仍然是这个会话钉住的那个。
     */
    val providerName: String? = null,
    val model: String? = null,

    /** 这个会话钉着的服务商 id，用来在选择器里标出「当前」。 */
    val providerId: String? = null,

    /**
     * 「换服务商」选择器的候选。
     *
     * **null 表示还没读过**，空列表表示「确实一个都没配」—— 这两种状态
     * 在界面上必须分开：前者是一瞬间的事（本地库查询），后者要引导用户
     * 去设置页。混成一个空列表的话，用户点开选择器会看到一片空白，
     * 分不清是「加载中」还是「没配」。
     *
     * 只在用户点开选择器时加载（[ChatViewModel.loadProviders]）——
     * 每次进对话页都读一遍服务商表是白费的，而用户刚在设置页加的那个
     * 反而需要**最新**的列表，按需加载正好两头都满足。
     */
    val choices: List<ProviderChoice>? = null,

    /**
     * 还有更早的消息没加载 —— 界面据此显示「加载更早的消息」。
     *
     * 这个值由存储层算出来（多取一条判断），**不是**拿
     * `messages.size == 200` 猜的：恰好取满时那个判断是错的，界面会显示
     * 一个「点了没反应」的按钮，用户会以为它坏了。
     */
    val hasMore: Boolean = false,

    /** 正在加载更早的消息。用来禁用按钮 —— 连点两次会用同一个游标取回同一页，拼两遍。 */
    val loadingEarlier: Boolean = false,

    /** 没有任何可用服务商（或没填 Key）。界面据此引导用户去设置页。 */
    val needsProvider: Boolean = false,
) {
    val canSend: Boolean get() = input.isNotBlank() && !streaming && !needsProvider
}

/**
 * 对话页的状态机。
 *
 * ## 数据来源有两条，刻意分开
 *
 * - [ChatUiState.messages] —— 来自数据库（`ConversationStore.transcript`）。
 *   只在进入页面和每次发送结束后刷新，是**唯一真相**。
 * - [ChatUiState.live] —— 只在流式过程中存在，直接由 [ChatEvent] 拼出来。
 *
 * 之所以不把流式内容也写进 `messages`：那样每来一个 token 就要改一次列表，
 * 而且刷新时机和数据库写入时机很难对齐，容易出现「同一句话显示两遍」。
 * 分两条之后，规则简单到不会错：**streaming = true 时看 live，结束后看 messages。**
 */
class ChatViewModel(
    private val deps: ChatDeps,
    private val conversationId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /**
     * 当前已加载的**最早**那条消息的游标，往上翻页用。
     *
     * 放字段而不是放进 [ChatUiState]：界面不需要它（界面看 [ChatUiState.hasMore]
     * 就够了），而它必须在 `refresh` 和 `loadEarlier` 之间保持一致。
     * 放进状态里会诱使界面去读它、然后自己算翻页 —— 那就把游标语义泄漏出去了，
     * 而游标一旦算错（比如漏掉 created_at 那一半）是**静默漏消息**。
     */
    private var earliest: MessageCursor? = null

    /**
     * 需要用户确认的工具调用。
     *
     * 刻意**不放进 [ChatUiState]**：弹框是独立于消息列表的一件事，
     * 混进同一个状态对象会让每次 token 更新都顺带重算弹框的可见性，
     * 也让「消息列表」这个状态变脏。分成两条流，界面各收各的。
     */
    private val gate = ToolApprovalGate()
    val pendingApproval: StateFlow<ToolApprovalGate.Request?> = gate.pending

    /**
     * 流式正文的 Markdown 解析器，**跨增量复用**。
     *
     * 复用才有块缓存：每次增量传完整文本进来，它只重解析最后一块。
     * 每次新建一个的话，就退化成每帧解析全文，O(n²)。
     */
    private val markdown = StreamingMarkdownRenderer()

    private var sendJob: Job? = null

    init {
        viewModelScope.launch { refresh() }
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** 用户点了停止。取消协程会把 socket 一起掐断，不会继续计费。 */
    fun stop() {
        // 先收掉弹框再取消协程。反过来的话，弹框会一直挂到取消传导过来为止，
        // 用户看到的是一个点不动的框
        gate.cancelPending()
        sendJob?.cancel()
    }

    /** 用户在弹框上点了「允许」或「拒绝」。 */
    fun decideTool(approved: Boolean) {
        gate.pending.value?.let { gate.decide(it.id, approved) }
    }

    /**
     * 重新读一遍会话与服务商。
     *
     * 界面在**回到前台**时调它。ViewModel 的 `init` 只跑一次，而用户可能
     * 刚从设置页回来 —— 改了服务商的模型名、把 Key 删了、或者新加了一个。
     * 那些变化不会通知到这里，不重读的话标题栏会一直显示旧值。
     */
    fun reload() {
        viewModelScope.launch { refresh() }
    }

    /**
     * 用户点开了「换服务商」选择器 —— 拉一份最新的服务商列表。
     *
     * 按需拉而不是进页面就拉：用户可能刚在设置页加了个服务商，
     * 那时**最新的**列表才有用。
     */
    fun loadProviders() {
        viewModelScope.launch {
            _state.update { it.copy(choices = deps.selectableProviders()) }
        }
    }

    /**
     * 把**这个会话**换到另一个服务商。
     *
     * 只影响这一个会话 —— 其它会话各自钉在它们原来的服务商上。
     *
     * 换完立刻刷新标题栏：不刷的话用户会以为没换成功，
     * 而实际上下一条消息已经用新的服务商发出去了。
     */
    fun repin(providerId: String) {
        viewModelScope.launch {
            if (deps.repinConversation(conversationId, providerId)) refresh()
        }
    }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.streaming) return

        sendJob = viewModelScope.launch {
            // **本会话**的服务商，不是当前默认的 —— 会话是黏住的。
            // 第一次发消息时这里会顺手把它钉上（见 ConversationRouter）
            val provider = providerOrExplain() ?: return@launch

            _state.update {
                it.copy(
                    input = "",
                    // 乐观地把用户这条挂上去。落库是同一次调用里做的，
                    // 结束时 refresh() 会用真实数据覆盖，不会重复
                    messages = it.messages + MessageUi(
                        id = PENDING_ID,
                        role = ChatMessage.Role.User,
                        content = text,
                    ),
                )
            }

            runReply(
                provider,
                deps.newSession(provider, gate)
                    .send(conversationId, text, provider.toChatConfig()),
            )
        }
    }

    /**
     * 重新生成：把 [messageId] 这条（含）及其之后全部丢掉，用同样的上下文再答一次。
     *
     * ## 界面上也要立刻抹掉
     *
     * 库里截断是异步的，而界面读的是 [ChatUiState.messages]。不先抹掉的话，
     * 新回复会**和旧的那条并排显示** —— 直到 `refresh()` 才消失。用户看到的是
     * 「它生成了两遍」，而其中一遍马上又要凭空消失。
     *
     * ## 只认最后一条助手消息
     *
     * 对更早的那条做「重新生成」，等于把**后面所有对话一起删掉**再重跑。
     * 用户点它的时候想的是「这条答得不好，换一个」，而不是「把后面的都删了」——
     * 菜单里也不会出现它（见 `ChatScreen` 的 `canRegenerate`）。这里再拦一道，
     * 是为了让这条不变量在 ViewModel 这一层也成立：菜单将来改坏了，
     * 也不会静默地删掉一段对话。
     */
    fun regenerate(messageId: Long) {
        if (_state.value.messages.lastOrNull { it.canRegenerate }?.id != messageId) return
        val target = actionable(messageId, MessageUi::canRegenerate) ?: return
        sendJob = viewModelScope.launch {
            val provider = providerOrExplain() ?: return@launch
            truncateFrom(messageId)
            runReply(
                provider,
                deps.newSession(provider, gate)
                    .regenerate(conversationId, target.cursor(), provider.toChatConfig()),
            )
        }
    }

    /**
     * 编辑重发：把 [messageId] 那条用户消息**替换**成 [text]，后面全部丢掉重跑。
     *
     * 与 [regenerate] 的差别在库里：那个不新增用户消息，这个新增一条
     * （由 `ChatSession.send` 写进去），位置正好是被删掉那条的位置。
     *
     * 空白输入直接返回：截断是**不可撤销**的（用户可能后悔），
     * 不能因为一次手滑就先把后面几条删掉再报错。
     */
    fun editAndResend(messageId: Long, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val target = actionable(messageId, MessageUi::canEdit) ?: return

        sendJob = viewModelScope.launch {
            val provider = providerOrExplain() ?: return@launch
            truncateFrom(messageId, replaceWith = trimmed)
            runReply(
                provider,
                deps.newSession(provider, gate)
                    .editAndResend(
                        conversationId,
                        target.cursor(),
                        trimmed,
                        provider.toChatConfig(),
                    ),
            )
        }
    }

    /**
     * 取出可操作的那条消息。`null` 表示**什么都不做**：
     *
     * - 正在流式 —— 此时列表里还有乐观消息，截断的边界没有意义
     * - 找不到这个 id（列表刚被刷新过，界面手里的还是上一帧的）
     * - 是还没落库的乐观消息 —— 它没有真实 id，拿它当游标会截到空处
     * - 不满足 [allows]（角色不对，或者不是最后一条助手消息）
     */
    private fun actionable(messageId: Long, allows: (MessageUi) -> Boolean): MessageUi? {
        if (_state.value.streaming) return null
        return _state.value.messages
            .firstOrNull { it.id == messageId }
            ?.takeIf { !it.isPending && allows(it) }
    }

    /**
     * 乐观地把界面上从 [messageId] 起的内容抹掉。
     *
     * [replaceWith] 非空时在原来的位置补一条待发的用户消息 —— 编辑重发之后，
     * 用户应该立刻看到自己改过的那句话，而不是空一段。
     *
     * 按**下标**截断而不是按 id 过滤：`messages` 的顺序就是库里的
     * `created_at, id` 顺序，而下标是「从这条起往后」最不容易写错的表达。
     */
    private fun truncateFrom(messageId: Long, replaceWith: String? = null) {
        _state.update { state ->
            val index = state.messages.indexOfFirst { it.id == messageId }
            if (index < 0) return@update state
            val kept = state.messages.take(index)
            state.copy(
                messages = if (replaceWith == null) {
                    kept
                } else {
                    kept + MessageUi(
                        id = PENDING_ID,
                        role = ChatMessage.Role.User,
                        content = replaceWith,
                    )
                },
            )
        }
    }

    /**
     * 本会话的服务商，没有就说明原因并返回 null。
     *
     * 三个入口（发送 / 重新生成 / 编辑重发）都要先过这一关：不拦的话，
     * 重新生成会把消息删掉、然后发一个注定 401 的请求 —— 消息没了，
     * 新回复也没有，用户丢掉的是**已经拿到的回答**。
     */
    private suspend fun providerOrExplain(): ResolvedProvider? {
        val provider = deps.providerForConversation(conversationId)
        if (provider?.config == null) {
            _state.update {
                it.copy(
                    needsProvider = true,
                    error = "还没有可用的服务商。去「设置」填一个接口地址和 API Key。",
                )
            }
            return null
        }
        return provider
    }

    /**
     * 跑一轮回复：切到流式态、收集事件、无条件收尾刷新。
     *
     * [events] 是**冷流**，构造它本身不产生任何副作用（`ChatSession` 的
     * `send` / `regenerate` 都是 `flow { }`），所以调用方可以放心地先拼出来再传进来。
     *
     * `finally` 里的收尾必须无条件执行，而且取消时也要跑 —— 用户按了停止，
     * 更需要把「刚才那半截已经存下来了」反映到界面上。
     */
    private suspend fun runReply(provider: ResolvedProvider, events: Flow<ChatEvent>) {
        _state.update {
            it.copy(
                streaming = true,
                live = emptyList(),
                error = null,
                providerName = provider.name,
                model = provider.model,
            )
        }
        // 上一轮的块缓存必须丢掉，否则新一轮的正文会接着上一轮的边界算
        markdown.reset()

        try {
            events.collect { event -> onEvent(event) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _state.update { it.copy(error = t.message ?: "发送失败") }
        } finally {
            withContext(NonCancellable) {
                _state.update { it.copy(streaming = false, live = emptyList()) }
                refresh()
            }
        }
    }

    private fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.TextDelta -> _state.update { it.appendText(event.text, markdown) }

            is ChatEvent.ReasoningDelta -> _state.update { it.appendReasoning(event.text) }

            is ChatEvent.ToolCallStarted -> _state.update {
                it.copy(
                    live = it.live + LiveSegment.Tool(
                        callId = event.call.id,
                        name = event.call.name.ifBlank { "未命名工具" },
                    )
                )
            }

            is ChatEvent.ToolCallFinished -> _state.update { state ->
                state.copy(
                    live = state.live.map { segment ->
                        if (segment is LiveSegment.Tool && segment.callId == event.callId) {
                            segment.copy(
                                output = event.result.content,
                                isError = event.result.isError,
                                running = false,
                            )
                        } else {
                            segment
                        }
                    }
                )
            }

            is ChatEvent.Failed -> _state.update { it.copy(error = event.error.message ?: "请求失败") }

            // 轮次、用量、最终消息都不需要单独呈现：
            // 文本与工具已经通过上面的增量事件画出来了，
            // 最终消息由 refresh() 从库里取
            is ChatEvent.RoundStarted,
            is ChatEvent.Usage,
            is ChatEvent.Completed,
            -> Unit
        }
    }

    /**
     * 往上加载一页更早的消息。
     *
     * 游标取**当前已加载的最早那条**，而不是记一个页码 —— 用户边看边发消息时，
     * 页码会错位（新消息插到尾部会让「第 2 页」的边界整体移动），游标不会。
     *
     * 加载出来的消息**拼在前面**，不动已经显示的那些。
     */
    fun loadEarlier() {
        val cursor = earliest ?: return
        // 连点两次会用同一个游标取回同一页，然后拼两遍 —— 界面上会出现
        // 两份一模一样的历史。这里的检查和下面的赋值都在主线程上，
        // 中间没有挂起点，所以是安全的
        if (_state.value.loadingEarlier) return
        _state.update { it.copy(loadingEarlier = true) }

        viewModelScope.launch {
            val page = deps.transcript(conversationId, before = cursor)
            earliest = page.cursor
            _state.update {
                it.copy(
                    messages = page.messages.map { row -> row.toUi() } + it.messages,
                    loadingEarlier = false,
                    hasMore = page.hasMore,
                )
            }
        }
    }

    private suspend fun refresh() {
        val page = deps.transcript(conversationId)
        val provider = deps.providerForConversation(conversationId)
        // 刷新会把视图**拉回最新一页** —— 用户往上翻了很久之后发一条消息，
        // 回到最新一页是符合预期的（他刚说完话，要看的是自己的话和回复）
        earliest = page.cursor
        _state.update {
            it.copy(
                messages = page.messages.map { row -> row.toUi() },
                loading = false,
                hasMore = page.hasMore,
                providerName = provider?.name,
                model = provider?.model,
                providerId = provider?.id,
                needsProvider = provider?.config == null,
            )
        }
    }

    private companion object {
        /**
         * 还没落库的那条用户消息用的 id。
         *
         * 负数是有意的：真实 id 是毫秒时间戳，恒为正。这样
         * [MessageUi.isPending] 一眼能判出来，也不会和任何真实消息撞上。
         */
        const val PENDING_ID = -1L
    }
}

private fun StoredMessage.toUi() = MessageUi(
    id = id,
    role = message.role,
    content = message.content,
    reasoning = message.reasoning,
    toolCalls = message.toolCalls,
    toolCallId = message.toolCallId,
    status = status,
    error = error,
    createdAt = createdAt,
)

/**
 * 追加到最后一个文本段，而不是每来一个 token 就多一段 —— 否则列表会疯狂重建。
 *
 * 顺带把 Markdown 解析也做了：解析器带块缓存，所以每个增量只重解析尾部，
 * 不是整条消息。放在这里（而不是 composable 里）是为了让解析每帧只发生一次。
 */
private fun ChatUiState.appendText(
    text: String,
    renderer: StreamingMarkdownRenderer,
): ChatUiState {
    val last = live.lastOrNull()
    val next = if (last is LiveSegment.Text) {
        val full = last.text + text
        live.dropLast(1) + last.copy(text = full, blocks = renderer.render(full))
    } else {
        // 上一个段不是文本（刚做完一次工具调用）→ 这是一个**新**的文本段，
        // 渲染器要从头开始，否则会把上一段的块缓存带过来
        renderer.reset()
        live + LiveSegment.Text(text, renderer.render(text))
    }
    return copy(live = next)
}

private fun ChatUiState.appendReasoning(text: String): ChatUiState {
    val last = live.lastOrNull()
    val next = if (last is LiveSegment.Reasoning) {
        live.dropLast(1) + LiveSegment.Reasoning(last.text + text)
    } else {
        live + LiveSegment.Reasoning(text)
    }
    return copy(live = next)
}

/**
 * 把服务商配置翻译成一次请求的参数。
 *
 * ## 为什么要有这个函数（而不是在调用点写 `ChatConfig(...)`）
 *
 * 三个入口（发送 / 重新生成 / 编辑重发）都要构造 `ChatConfig`。原先三处
 * 各写一遍 `ChatConfig(model = provider.model)`，于是**加一个字段必然漏掉其中一两处** ——
 * 而且漏掉之后不会编译错、不会报错，只是「这个入口不生效」。
 *
 * 这类「同一份东西在多处拼装」的地方，收敛成一个函数是唯一可靠的办法。
 * `ChatViewModelTest` 里有一条用例逐个字段比对，就是防这个。
 *
 * ## 三个参数直接透传，不做任何兜底
 *
 * `null` 一律原样传下去 —— `ChatConfig` 和 `ChatRequest` 都用 `null` 表达
 * 「请求里不发这个字段」。在这里补一个默认值会**悄悄改掉服务端行为**
 * （各家默认 temperature 不同），而且用户看不出来。
 */
internal fun ResolvedProvider.toChatConfig(): ChatConfig = ChatConfig(
    model = model,
    systemPrompt = systemPrompt,
    temperature = temperature,
    maxTokens = maxTokens,
)
