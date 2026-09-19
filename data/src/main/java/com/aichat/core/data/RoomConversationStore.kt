package com.aichat.core.data

import com.aichat.chat.ChatMessage
import com.aichat.chat.ConversationStore
import com.aichat.chat.ConversationSummary
import com.aichat.chat.MessageCursor
import com.aichat.chat.MessageStatus
import com.aichat.chat.StoredMessage
import com.aichat.chat.TranscriptPage
import com.aichat.domain.text.CjkText

/**
 * [ConversationStore] 的 Room 实现。
 *
 * ## 这个类存在的意义是「事务边界」
 *
 * 主表 `message` 和索引表 `message_fts` 是两张独立的表，Room **不会**
 * 自动同步它们。任何一次写入只要漏掉一半，就会出现：
 *
 * - 主表有、索引没有 → 这条消息搜不到（静默的功能缺失，最难发现）
 * - 主表删了、索引还在 → 检索结果里出现幽灵记录
 *
 * 所以这里的每一个写方法都只有一种形态：**一个 [TransactionRunner] 里
 * 同时写两张表**。不提供「只写主表」的方法，就是为了让绕过这件事在
 * 代码里做不到。
 *
 * ## 依赖的是 DAO 接口而不是 AppDatabase
 *
 * 这样 JVM 单测能塞进内存假实现，把事务逻辑（谁先谁后、哪些状态不入索引）
 * 在毫秒级验证完。真正的 SQL 正确性交给 instrumented test —— 分工明确，
 * 因为 SQL 错误和逻辑错误是两类完全不同的问题。
 */
class RoomConversationStore(
    private val messages: MessageDao,
    private val fts: MessageFtsDao,
    private val conversations: ConversationDao,
    private val tx: TransactionRunner,
    private val clock: () -> Long = System::currentTimeMillis,
) : ConversationStore {

    /**
     * `recentIn` 返回的是倒序（为了能用 `LIMIT` 取最近的），
     * 这里翻回正序 —— 上下文必须是时间正序，反了模型会读成一问一答颠倒。
     */
    override suspend fun history(
        conversationId: String,
        limit: Int,
    ): List<ChatMessage> = messages.recentIn(conversationId, limit)
        .asReversed()
        .map { it.toChatMessage() }

    override suspend fun transcript(
        conversationId: String,
        limit: Int,
        before: MessageCursor?,
    ): TranscriptPage {
        // **多取一条**用来判断「还有没有更早的」。
        //
        // 比再发一次 COUNT 便宜，而且没有「查完 COUNT 又插进来一条」的竞态 ——
        // 那种情况下按钮会莫名其妙地出现或消失。
        val rows = if (before == null) {
            messages.recentAllIn(conversationId, limit + 1)
        } else {
            messages.pageBefore(conversationId, before.createdAt, before.id, limit + 1)
        }

        val page = rows.take(limit).asReversed().map { it.toStoredMessage() }

        return TranscriptPage(
            messages = page,
            // 游标指向这页**最早**的那条 —— 正序的第一个
            cursor = page.firstOrNull()?.let { MessageCursor(it.createdAt, it.id) },
            hasMore = rows.size > limit,
        )
    }

    override suspend fun save(record: StoredMessage) {
        val entity = record.toEntity()
        tx.run {
            messages.upsert(entity)
            syncIndex(entity)
        }
    }

    override suspend fun delete(conversationId: String, messageId: Long) {
        tx.run {
            // 只有真的删到了才清索引。无条件清的话，会话 id 传错时
            // 会把一条还在的消息从检索里悄悄抹掉 —— 没有任何报错，
            // 用户只会觉得「明明说过这句话，怎么搜不到」
            val affected = messages.softDelete(messageId, conversationId, clock())
            if (affected > 0) fts.delete(messageId)
        }
    }

    /**
     * 截断：删掉 [from] 及其之后的全部消息。
     *
     * 和 [delete] 不同，这里**不拿受影响行数做判断**。理由：即便主表
     * 一行都没更新到（游标指向一条已经被删掉的消息），索引也该照清一遍 ——
     * FTS 那边的条件是「按 `created_at, id` 从主表找 id」，游标失效时
     * 它自然一条都匹配不到，代价只是一次空 DELETE；而反过来漏清索引的
     * 后果是「被替换掉的那条回复还能被搜到」，且没有任何报错。
     */
    override suspend fun deleteFrom(conversationId: String, from: MessageCursor) {
        tx.run {
            messages.softDeleteFrom(conversationId, from.createdAt, from.id, clock())
            fts.deleteFrom(conversationId, from.createdAt, from.id)
        }
    }

    override suspend fun allMessages(conversationId: String): List<StoredMessage> =
        messages.listByConversation(conversationId).map { it.toStoredMessage() }

    override suspend fun clearMessages(conversationId: String) {
        tx.run {
            messages.softDeleteIn(conversationId, clock())
            fts.deleteIn(conversationId)
        }
    }

    override suspend fun ensureConversation(conversationId: String, title: String, now: Long) {
        tx.run {
            conversations.insertIfAbsent(
                ConversationEntity(
                    id = conversationId,
                    title = title,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            // 已存在时 insertIfAbsent 是 no-op，用 touch 把活跃时间推上去
            conversations.touch(conversationId, now)
        }
    }

    override suspend fun titleIfUntitled(conversationId: String, title: String, now: Long) {
        if (title.isBlank()) return
        conversations.titleIfUntitled(conversationId, title, now)
    }

    /**
     * 用户改名。**空标题也照写** —— 那是「清掉标题」的意思，
     * 会话回到「新对话」的显示。这里不做 [titleIfUntitled] 那种空白拦截。
     */
    override suspend fun renameConversation(conversationId: String, title: String, now: Long) {
        conversations.rename(conversationId, title.trim(), now)
    }

    override suspend fun setPinned(conversationId: String, pinned: Boolean) {
        conversations.setPinned(conversationId, pinned)
    }

    override suspend fun listConversations(limit: Int): List<ConversationSummary> =
        conversations.listSummaries(limit).map {
            ConversationSummary(
                id = it.id,
                title = it.title,
                messageCount = it.messageCount,
                updatedAt = it.updatedAt,
                pinned = it.pinned,
            )
        }

    /**
     * 删除会话：消息**硬删**，不软删。
     *
     * 与「删一条消息」不同 —— 会话都没了，它下面的消息没有恢复的入口，
     * 留着只是白占空间。而且软删会让 `message` 表随着用户反复建删会话无限膨胀。
     *
     * 顺序不能反：`fts.deleteIn` 的子查询要从主表里按 conversation_id 找出
     * 这批 id，主表先删了就一条都找不到了，索引行会全部残留。
     */
    override suspend fun deleteConversation(conversationId: String) {
        tx.run {
            fts.deleteIn(conversationId)
            messages.hardDeleteIn(conversationId)
            conversations.delete(conversationId)
        }
    }

    /**
     * 启动清理：把上次异常退出留下的 `streaming` 消息标成失败。
     *
     * 先把这批消息读出来，是因为它们在 `streaming` 期间**从没入过索引**
     * （见 [syncIndex]）。状态一改就成了正常消息，得补建索引，
     * 否则用户搜不到那段其实已经显示在屏幕上的内容。
     */
    override suspend fun failInterrupted(now: Long): Int = tx.run {
        val stranded = messages.listStreaming()
        if (stranded.isEmpty()) return@run 0

        val affected = messages.failInterrupted(INTERRUPTED_REASON, now)
        stranded.forEach { old ->
            syncIndex(old.copy(status = MessageStatus.Failed.wire, error = INTERRUPTED_REASON))
        }
        affected
    }

    /**
     * 主表与索引表的同步规则，只有这一处。
     *
     * - `streaming` **不入索引**：内容还在一个字一个字地变，
     *   每写一次就重建一次索引是纯粹的浪费，而且中途的残句被搜到也没意义
     * - 正文为空（纯工具调用的 assistant 消息）**清掉索引**：
     *   留着一条空 body 的索引行会让 `MATCH` 命中它，结果点进去什么都没有
     */
    private suspend fun syncIndex(entity: MessageEntity) {
        if (MessageStatus.fromWire(entity.status) == MessageStatus.Streaming) return

        val body = CjkText.forIndex(entity.content)
        if (body.isBlank() || entity.deleted) {
            fts.delete(entity.id)
        } else {
            fts.insert(MessageFtsEntity(rowId = entity.id, body = body))
        }
    }

    private companion object {
        const val INTERRUPTED_REASON = "上次回复被中断（App 已退出）"
    }
}
