package com.aichat.chat
import com.aichat.domain.text.errorDetail

/**
 * 一条消息在库里的状态。
 *
 * 分四态而不是「有没有写完」，是因为**这四种情况在界面上长得不一样**：
 *
 * - [Streaming] 光标还在闪，用户可以按停止
 * - [Complete]  正常结束
 * - [Stopped]   用户主动停的 —— 内容是真的，只是没写完，
 *   不该显示成红色错误，也不该让模型以为上次是「说完了」
 * - [Failed]    出错了，[StoredMessage.error] 里有原因
 *
 * [wire] 是落库用的字符串。**不要**用 `enum.name` 或 `ordinal`：
 * 前者改了枚举名就废掉全部历史数据，后者在中间插一个值就会错位。
 */
enum class MessageStatus(val wire: String) {
    Streaming("streaming"),
    Complete("complete"),
    Stopped("stopped"),
    Failed("failed"),
    ;

    companion object {
        /**
         * 反序列化。认不出的值一律当作 [Complete] —— 老版本 App 读到新版本
         * 写的状态时，至少还能正常显示，而不是崩在解析上。
         */
        fun fromWire(raw: String?): MessageStatus =
            entries.firstOrNull { it.wire == raw } ?: Complete
    }
}

/**
 * 待落盘的一条消息。
 *
 * 比 [ChatMessage] 多了「库需要但对话不需要」的东西：主键、状态、时间戳。
 * 两者刻意不合并 —— [ChatMessage] 要发到网络上，多一个字段都是负担。
 */
data class StoredMessage(
    val id: Long,
    val conversationId: String,
    val message: ChatMessage,
    val status: MessageStatus = MessageStatus.Complete,
    val model: String? = null,

    /** [MessageStatus.Failed] 时的原因，界面上直接显示给用户。 */
    val error: String? = null,

    val createdAt: Long,
    val updatedAt: Long = createdAt,
)

/**
 * 会话列表项。
 *
 * [pinned] 让用户钉住的会话排在前面。它和 [updatedAt] 排出来的顺序
 * 是两件不同的事：后者随着聊天不断变化，而用户置顶某条会话，
 * 正是为了让它**别再被挤下去**。
 */
data class ConversationSummary(
    val id: String,
    val title: String,
    val messageCount: Int,
    val updatedAt: Long,
    val pinned: Boolean = false,
)

/**
 * 翻页游标 —— 指向「已经加载到的最早那一条」。
 *
 * 必须是 `(createdAt, id)` **复合**的，不能只存 id。排序键是
 * `created_at DESC, id DESC`，游标与排序键不一致时会**静默漏消息** ——
 * 具体反例见 `MessageDao.pageBefore` 的 KDoc。
 */
data class MessageCursor(val createdAt: Long, val id: Long)

/**
 * 一页消息。
 *
 * 把 [hasMore] 一起返回，而不是让调用方拿 `messages.size == limit` 去猜：
 * **恰好取满时那个判断是错的** —— 其实已经到底了，界面却还显示「加载更早」，
 * 用户点一下什么都不会发生，会以为按钮坏了。
 */
data class TranscriptPage(
    /** 按时间正序。 */
    val messages: List<StoredMessage>,
    /** 再往上一页要传的游标。只有 [hasMore] 为 true 时才有意义。 */
    val cursor: MessageCursor?,
    /** 这页**之前**还有更早的消息。 */
    val hasMore: Boolean,
)

/**
 * 对话的持久化出口。
 *
 * 定义在 :chat（纯 JVM）而不是 :data，是为了让 [ChatSession] 的编排逻辑
 * 能在 JVM 单测里跑 —— 用内存实现替换掉 Room，测试从分钟级降到毫秒级。
 * Room 实现见 `:data` 的 `RoomConversationStore`。
 *
 * ## 实现方必须遵守的两条
 *
 * 1. **全文索引与主表必须同事务**。否则检索会出现幽灵记录：
 *    JOIN 不到，或者 JOIN 到一条已经删掉的消息。
 * 2. [save] 必须是 upsert 语义。流式回复会被反复写入同一条
 *    （占位 → 节流更新 → 收尾），实现成纯 INSERT 会在第二次就撞主键。
 */
interface ConversationStore {

    /**
     * 装配上下文用的历史消息，**按时间正序**。
     *
     * 不含 `Streaming` 状态的消息 —— 那些是还没写完的半截回复，
     * 喂给模型会让它以为自己上一轮就是这么说的。
     */
    suspend fun history(conversationId: String, limit: Int = DEFAULT_HISTORY_LIMIT): List<ChatMessage>

    /**
     * 展示用的完整消息列表，**按时间正序**，含 `Streaming` / `Stopped` / `Failed`。
     *
     * 与 [history] 只差一件事：不过滤未完成的消息。分成两个方法而不是加个
     * 布尔开关，是因为这两个调用点要的东西完全不同 ——
     * 一个是「模型该看到什么」，一个是「用户该看到什么」。
     * 用一个参数切换的话，某处传错会让模型看到半截回复（然后接着往下编）。
     *
     * ## 取的是**最近**一页，不是最早一页
     *
     * 打开会话时用户要看到的是最新几条。往上翻由 [before] 驱动：
     * `null` = 最新一页，非 null = 再往上一页（传上一次返回的
     * [TranscriptPage.cursor]）。
     *
     * 长会话只读一页，不把几百条一次性读进内存 —— 那既是性能问题，
     * 也是内存问题（每条消息的正文可能几千字）。
     */
    suspend fun transcript(
        conversationId: String,
        limit: Int = DEFAULT_TRANSCRIPT_LIMIT,
        before: MessageCursor? = null,
    ): TranscriptPage

    /** 插入或更新一条消息。[MessageStatus.Streaming] 以外都会同步全文索引。 */
    suspend fun save(record: StoredMessage)

    /** 软删除一条消息，同时把它从全文索引里摘掉。 */
    suspend fun delete(conversationId: String, messageId: Long)

    /**
     * 删掉 [from] 这条**及其之后**的全部消息（软删 + 清索引）。
     *
     * 「重新生成」和「编辑重发」都建立在这个动作上 —— 两者都是
     * 「把对话退回到某个点，然后重新往下走」，区别只在于退回之后
     * 要不要塞一条新的用户消息。
     *
     * ## 为什么收 `MessageCursor` 而不是一个 messageId
     *
     * 因为条件必须是 `created_at > :c OR (created_at = :c AND id >= :id)`，
     * 与列表的排序键 `created_at DESC, id DESC` 完全一致。只按 id 比的话，
     * 一旦某行的 `created_at` 与 `id` 不同序就会**多删或漏删**，
     * 而这两种都不报错 —— 用户只会发现「我上一条消息不见了」。
     * 同一条理由见 [MessageCursor] 和 `MessageDao.pageBefore`。
     *
     * ## 只软删，不硬删
     *
     * 和 [delete] 一样：这个动作之后用户可能立刻后悔（点了重新生成
     * 才发现原来那条更好），硬删就没有回头路了。
     */
    suspend fun deleteFrom(conversationId: String, from: MessageCursor)

    /**
     * 整个会话的消息，**按时间正序**，含未完成的那些。
     *
     * 给**导出**用：导出要的是「全部」，不是「最近一页」。
     *
     * 单独开一个方法而不是让调用方拿 [transcript] 循环翻页，是因为
     * 那个循环的终止条件很容易写错（漏最后一页、或多读一页重复），
     * 而错法是静默的 —— 导出的文件少一段，用户不会逐行核对。
     */
    suspend fun allMessages(conversationId: String): List<StoredMessage>

    /** 清空一个会话的全部消息（软删除 + 清索引），但保留会话本身。 */
    suspend fun clearMessages(conversationId: String)

    /** 会话不存在则创建（[title] 只在创建时生效），存在则刷新 [updatedAt]。 */
    suspend fun ensureConversation(conversationId: String, title: String, now: Long)

    /** 会话标题还是空的时候，用 [title] 填上。用户手动改过名就不要再覆盖。 */
    suspend fun titleIfUntitled(conversationId: String, title: String, now: Long)

    /**
     * 用户手动改名，无条件覆盖。
     *
     * 与 [titleIfUntitled] 分开是因为两者是**相反**的语义：那个是
     * 「自动填一个还没名字的会话」（有名字就跳过），这个是
     * 「用户明确要求改成这个」。合成一个方法的话，自动填充会覆盖
     * 用户改过的名字，或者反过来 —— 用户改名被当成自动填充而跳过。
     *
     * 空白标题**不拦**：用户可能想清掉标题，让它回到「新对话」的显示。
     */
    suspend fun renameConversation(conversationId: String, title: String, now: Long)

    /**
     * 置顶 / 取消置顶。
     *
     * 只影响**列表顺序**，不动 `updated_at` —— 后者是「这个会话最后一次
     * 说话是什么时候」，是个事实，不该因为用户整理列表而改变。
     * 所以这个方法没有 `now` 参数：它压根不写时间戳。
     */
    suspend fun setPinned(conversationId: String, pinned: Boolean)

    suspend fun listConversations(limit: Int = DEFAULT_CONVERSATION_LIMIT): List<ConversationSummary>

    /** 删除会话及其全部消息。 */
    suspend fun deleteConversation(conversationId: String)

    /**
     * App 启动时调一次：把上次异常退出留下的 `Streaming` 消息标成失败。
     *
     * 不做这件事的话，用户会看到一条永远在「正在输入」的回复，
     * 而且它会一直占着上下文装配的排除位。
     */
    suspend fun failInterrupted(now: Long): Int

    companion object {
        /**
         * 默认取多少条历史。
         *
         * 50 条是个粗估：按中文一轮 200 字算，大约 2 万字符，多数模型
         * 的上下文放得下。真正该做的是按 token 预算裁剪，等接入
         * tokenizer 之后换掉。
         */
        const val DEFAULT_HISTORY_LIMIT = 50

        /**
         * 界面一次展示多少条历史。
         *
         * 比 [DEFAULT_HISTORY_LIMIT] 大得多 —— 上下文要省 token，
         * 而界面只需要省内存。200 条对 RecyclerView/LazyColumn 完全无压力。
         */
        const val DEFAULT_TRANSCRIPT_LIMIT = 200

        const val DEFAULT_CONVERSATION_LIMIT = 100
    }
}

/** 消息 id 生成器。抽出来是为了在测试里能给出确定的序列。 */
fun interface IdGenerator {
    fun next(): Long
}

/**
 * 严格递增的 id 生成器：拿毫秒时间戳当 id，同一毫秒内自增。
 *
 * 为什么不用 UUID 或自增主键：
 *
 * - 消息要**先有身份再落库**（流式过程中就得反复更新同一条），自增主键做不到。
 * - `MessageEntity.id` 同时是 FTS4 的 `rowid`，必须是整数。
 * - 时间戳天然按插入顺序递增，`ORDER BY id` 和 `ORDER BY created_at` 等价，
 *   省一次排序。
 *
 * 代价是 id 可被推断出创建时间 —— 本地库，无所谓。
 */
class MonotonicIds(private val clock: () -> Long = System::currentTimeMillis) : IdGenerator {

    private var last = 0L

    @Synchronized
    override fun next(): Long {
        val now = clock()
        last = if (now > last) now else last + 1
        return last
    }
}

/**
 * 从第一条用户消息里截出会话标题。
 *
 * 只取第一行、压掉多余空白、超长截断。不做「让模型总结标题」这种花活 ——
 * 那要么多一次网络请求，要么在本地塞一个小模型，都不值得。
 */
internal fun deriveTitle(input: String, maxChars: Int = 24): String {
    val firstLine = input.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val collapsed = firstLine.replace(Regex("\\s+"), " ")
    return when {
        collapsed.isEmpty() -> ""
        collapsed.length <= maxChars -> collapsed
        else -> collapsed.take(maxChars) + "…"
    }
}

/** 把异常翻成能直接显示给用户的一句话。 */
internal fun describeError(t: Throwable): String = when (t) {
    is com.aichat.network.ChatApiException.Http ->
        t.message ?: "服务端返回 HTTP ${t.statusCode}"

    is com.aichat.network.ChatApiException.Network ->
        "连不上服务端：${t.message ?: "网络异常"}"

    is ToolLoopExceededException -> t.message ?: "工具调用轮数超限"

    else -> errorDetail(t)
}
