package com.aichat.chat

import com.aichat.domain.llm.AssistantToolCall
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * 一轮回复在库里的归属信息。收在一处，免得每个私有方法都接八个参数。
 *
 * **一轮一个实例**：引擎每多跑一轮工具调用，就新开一条记录。
 */
private class ReplyRecord(
    val id: Long,
    val conversationId: String,
    val model: String?,
    val createdAt: Long,
) {
    val text = StringBuilder()
    val reasoning = StringBuilder()
    var calls: List<AssistantToolCall> = emptyList()
    var status: MessageStatus = MessageStatus.Streaming
    var error: String? = null
}

/**
 * 把 [ConversationEngine] 接上 [ConversationStore] —— 「一次用户输入」的完整生命周期。
 *
 * ## 它比引擎多做了什么
 *
 * 引擎只负责「问模型、跑工具、再问」，它不知道有数据库这回事。
 * 本类负责把过程**落盘**，并且要做到「用户随时杀进程，回来看到的东西是对的」：
 *
 * ```
 * 用户点发送
 *   ├─ 存 user 消息（Complete）        ← 先存用户的话，再发请求
 *   ├─ 取 history（含刚存的这条）       ← 引擎要的是完整上下文
 *   ├─ 轮 0：文字到达 → 建 assistant 行（Streaming）
 *   │        工具调用到达 → 建 tool 行，并把这一轮收尾
 *   ├─ 轮 1：文字到达 → 建**新的** assistant 行
 *   └─ Completed → 收尾
 * ```
 *
 * ## 为什么一轮一条 assistant 行，而不是整场一条
 *
 * 整场一条更省事，但会**丢掉工具卡片的位置**。assistant 行的 `created_at`
 * 是它第一次出现的时间，比后面所有 tool 行都早；整场合并成一条的话，
 * 重新打开会话时那一条会排到所有工具卡片前面 ——
 * 用户看到的是「一大段文字，下面跟着几张卡片」，
 * 而当时明明是「说了半句 → 卡片 → 接着说完」。
 *
 * 分开之后，`ORDER BY created_at` 天然还原了交错顺序，不需要额外存
 * 「段落顺序」这种元信息。代价是一次回答可能对应多行，删除/重新生成时要
 * 一起处理。
 *
 * ## 两个容易做错的地方
 *
 * 1. **不能每来一个 token 就写库**。一个 2000 字的回复会触发上千次事务，
 *    手机发热、界面卡顿都是这么来的。所以按时间节流（默认 400ms）。
 *    代价是「最后 400ms 的内容可能丢」—— 所以收尾时必须**无条件**再写一次。
 *
 * 2. **取消时也要落盘**。协程被取消后普通 `suspend` 调用会立刻抛
 *    `CancellationException`，直接写会写不进去。必须包在
 *    [NonCancellable] 里 —— 用户按了停止，恰恰是最需要保住已有内容的时候。
 */
class ChatSession(
    private val engine: ConversationEngine,
    private val store: ConversationStore,
    private val ids: IdGenerator = MonotonicIds(),
    private val clock: () -> Long = System::currentTimeMillis,

    /**
     * 流式过程中两次写库的最小间隔（毫秒）。
     *
     * 调小：更耐崩溃，但事务变多。调大：更省电，但断电时丢的内容更多。
     * 400ms 是个折中 —— 比人眼感知「卡顿」的阈值低，又比 token 到达频率低两个数量级。
     */
    private val persistIntervalMs: Long = DEFAULT_PERSIST_INTERVAL_MS,

    /**
     * 每轮开始前解析要注入的系统提示词（角色人设 + 世界书命中条目）。
     *
     * **默认 `null`**：不注入，行为与加这个参数之前完全一样。现有测试
     * 一个都不用改 —— 这是刻意的，这个能力是加法不是替换。
     *
     * 放在 `ChatSession` 而不是 `ChatViewModel` 里，是因为解析**需要历史**
     * （世界书就是扫历史命中的），而历史只有 [reply] 里才有。
     */
    private val promptSource: SystemPromptSource? = null,
) {

    /**
     * 发一条消息，并把全过程落盘。
     *
     * [input] 为空或纯空白时**不做任何事**（不建会话、不落库、不发请求）——
     * 直接把失败事件透出去。UI 应该在此之前就把发送按钮禁掉。
     */
    fun send(
        conversationId: String,
        input: String,
        config: ChatConfig,
    ): Flow<ChatEvent> = flow {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            emit(ChatEvent.Failed(IllegalArgumentException(EMPTY_INPUT)))
            return@flow
        }

        val startedAt = clock()
        store.ensureConversation(conversationId, title = "", now = startedAt)

        // 先把用户的话存下来。顺序很重要：先存再取 history，
        // 否则这次输入就不在上下文里了
        store.save(
            StoredMessage(
                id = ids.next(),
                conversationId = conversationId,
                message = ChatMessage.user(trimmed),
                status = MessageStatus.Complete,
                createdAt = startedAt,
            )
        )
        store.titleIfUntitled(conversationId, deriveTitle(trimmed), startedAt)

        emitAll(reply(conversationId, config))
    }

    /**
     * 编辑重发：把 [from] 这条（**含**）及其之后的全部消息丢掉，用新内容重新问一遍。
     *
     * ## 与 [send] 的区别只有一处
     *
     * [from] 那条用户消息是**被替换**掉的，不是被追加。所以先截断、再走 [send]
     * —— 新的用户消息由 [send] 正常写进去，位置正好是原来那条的位置。
     *
     * ## 空白输入不能先截断
     *
     * 输入为空时直接失败返回，**一个字节都不删**。先删后校验的话，
     * 用户手滑把输入框清空再点确定，后面几条消息就凭空没了 ——
     * 而界面上给出的理由是「消息内容不能为空」，和实际发生的事完全对不上。
     *
     * 和 [send] 用同一个错误文案（[EMPTY_INPUT]）：两条路径对同一种错误
     * 给出两种说法，用户会以为其中一个是另一个的问题。
     */
    fun editAndResend(
        conversationId: String,
        from: MessageCursor,
        input: String,
        config: ChatConfig,
    ): Flow<ChatEvent> = flow {
        if (input.isBlank()) {
            emit(ChatEvent.Failed(IllegalArgumentException(EMPTY_INPUT)))
            return@flow
        }
        store.deleteFrom(conversationId, from)
        emitAll(send(conversationId, input, config))
    }

    /**
     * 重新生成：把 [from] 这条**及其之后**的全部消息丢掉，用剩下的上下文重跑。
     *
     * ## 为什么截断必须在这里做，而不是交给调用方
     *
     * 因为「截断」和「读 history」有**顺序依赖**：必须先把那批消息删掉再读历史，
     * 否则刚被丢弃的内容仍然在上下文里 —— 模型会看到自己上一次的回答，
     * 然后照着它往下接。用户看到的是一条**重复**的回复，而不是新的回答。
     *
     * 把两件事放进同一个方法，「顺序写反」这种错误在代码里就表达不出来了。
     *
     * ## 删掉的包含 [from] 自己
     *
     * 调用方传的是那条**要被替换掉的** assistant 消息的位置，它自己也要删 ——
     * 否则重跑出来的新回复会和旧的并排显示在界面上。
     *
     * 这里**不新增用户消息**：重新生成的语义是「同一句话，再答一次」。
     */
    fun regenerate(
        conversationId: String,
        from: MessageCursor,
        config: ChatConfig,
    ): Flow<ChatEvent> = flow {
        store.deleteFrom(conversationId, from)
        emitAll(reply(conversationId, config))
    }

    /**
     * 用「库里现有的历史」跑一轮回复，并全程落盘。
     *
     * [send] 和 [regenerate] 只差前半段（要不要先写一条用户消息、
     * 要不要先截断），从读历史开始的部分**完全一样**。抽出来是为了让落盘规则
     * 只有一处 —— 那一段里有「按时间节流写入」「取消时用 [NonCancellable] 落盘」
     * 「收尾无条件再写一次」三条容易漏的规则，复制一份迟早会分叉。
     */
    private fun reply(conversationId: String, config: ChatConfig): Flow<ChatEvent> = flow {
        val history = store.history(conversationId)

        // 角色人设与世界书在这里解析 —— 它要扫历史，而历史只有这里才有
        // （send 是先落库再读历史，所以本次输入已经在 history 末尾了）。
        // send / editAndResend / regenerate 三条入口全部经过 reply()，
        // 所以「选中的角色每轮都生效」只需要改这一处。
        val effective = promptSource
            ?.resolve(conversationId, history)
            ?.takeIf { it.isNotBlank() }
            ?.let { config.copy(injectedPrompt = it) }
            ?: config

        /** 本轮正在写的 assistant 行。null = 本轮还没产生任何输出。 */
        var record: ReplyRecord? = null
        var lastPersistAt = clock()

        /** 把当前这轮写一次库。没有开着的轮次时是 no-op。 */
        suspend fun flush(status: MessageStatus) {
            val r = record ?: return
            store.save(r.toStoredMessage(status))
            lastPersistAt = clock()
        }

        /** 收尾并关闭当前轮，下一次输出会开新的一行。 */
        suspend fun closeRound(status: MessageStatus) {
            flush(status)
            record = null
        }

        /**
         * 拿到本轮的记录，没有就开一条。
         *
         * 先写一条空内容的 `Streaming` 占位 —— 它让「模型正在回复」这件事
         * 在库里也成立：界面刷新、甚至杀进程重进，都能看到这条回复存在过。
         */
        suspend fun ensure(): ReplyRecord = record ?: ReplyRecord(
            id = ids.next(),
            conversationId = conversationId,
            model = effective.model,
            createdAt = clock(),
        ).also {
            record = it
            store.save(it.toStoredMessage(MessageStatus.Streaming))
            lastPersistAt = clock()
        }

        try {
            engine.send(history, effective).collect { event ->
                emit(event)

                when (event) {
                    is ChatEvent.RoundStarted -> {
                        // round > 0 说明上一轮是以工具调用收尾的（否则引擎早就结束了），
                        // 那一轮的行到此为止，接下来是新一轮
                        if (event.round > 0) closeRound(MessageStatus.Complete)
                    }

                    is ChatEvent.TextDelta -> {
                        val r = ensure()
                        r.text.append(event.text)
                        val now = clock()
                        if (now - lastPersistAt >= persistIntervalMs) {
                            store.save(r.toStoredMessage(MessageStatus.Streaming))
                            lastPersistAt = now
                        }
                    }

                    is ChatEvent.ReasoningDelta -> ensure().reasoning.append(event.text)

                    is ChatEvent.ToolCallStarted -> {
                        // 把调用记在本轮的 assistant 行上。回传上下文时
                        // tool 结果必须能找到对应的 tool_calls，否则服务端会拒绝
                        val r = ensure()
                        r.calls = r.calls + event.call
                    }

                    is ChatEvent.ToolCallFinished -> {
                        // ensure 一次：模型可能只调工具、一个字都不说，
                        // 那种情况下也要有 assistant 行来承载 tool_calls
                        ensure()

                        // 工具结果立刻落盘：它是模型下一轮的输入，
                        // 丢了会导致「重启后模型看到的上下文和上次不一致」
                        store.save(
                            StoredMessage(
                                id = ids.next(),
                                conversationId = conversationId,
                                message = ChatMessage.tool(event.callId, event.result.content),
                                status = if (event.result.isError) {
                                    MessageStatus.Failed
                                } else {
                                    MessageStatus.Complete
                                },
                                createdAt = clock(),
                            )
                        )
                        lastPersistAt = clock()
                    }

                    is ChatEvent.Completed -> {
                        // ensure 一次：模型可能一个字都没返回。
                        // 留一条空行，界面能明确显示「模型没有返回内容」，
                        // 而不是让用户对着一个转圈的界面猜发生了什么
                        ensure()
                        closeRound(MessageStatus.Complete)
                    }

                    is ChatEvent.Failed -> {
                        // ensure 一次：请求可能一个字都没吐就失败了（401、连不上）。
                        // 不留行的话，用户重新打开会话只看到自己那句问话，
                        // 完全不知道当时其实失败过 —— 只能凭记忆
                        val r = ensure()
                        r.status = MessageStatus.Failed
                        r.error = describeError(event.error)
                        closeRound(MessageStatus.Failed)
                    }

                    is ChatEvent.Usage -> Unit
                }
            }
        } catch (e: CancellationException) {
            // 用户按了停止。半截内容必须保住 —— 这是最需要落盘的时刻，
            // 而协程已经取消，普通挂起函数会立刻再抛一次，所以要 NonCancellable。
            withContext(NonCancellable) {
                record?.status = MessageStatus.Stopped
                flush(MessageStatus.Stopped)
            }
            throw e
        }

        // 兜底：引擎既没给 Completed 也没给 Failed 就结束了（服务端直接断流、
        // 或被上层提前取消收集）。留一条永远 `streaming` 的行会让它
        // 永久被排除在上下文之外，界面上也一直转圈。
        flush(MessageStatus.Complete)
    }

    private fun ReplyRecord.toStoredMessage(status: MessageStatus) = StoredMessage(
        id = id,
        conversationId = conversationId,
        message = ChatMessage(
            role = ChatMessage.Role.Assistant,
            content = text.toString(),
            reasoning = reasoning.toString().ifBlank { null },
            toolCalls = calls,
        ),
        status = status,
        model = model,
        error = error,
        createdAt = createdAt,
        updatedAt = clock(),
    )

    companion object {
        const val DEFAULT_PERSIST_INTERVAL_MS = 400L

        /**
         * 空输入的报错文案。**两条入口共用一句** —— [send] 和 [editAndResend]
         * 对同一种错误给出两种说法，用户会以为其中一个是另一个的问题。
         */
        internal const val EMPTY_INPUT = "消息内容不能为空"
    }
}
