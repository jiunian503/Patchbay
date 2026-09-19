package com.aichat.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 消息主表读写。
 *
 * 与 FTS 表的同步规则见 AppDatabase 的注释：**主表写入与 FTS 写入必须在同一个事务里**，
 * 这里不提供「只写主表」的便利方法，就是为了避免有人绕过。
 */
@Dao
interface MessageDao {

    /**
     * 纯插入。用 ABORT 而非 REPLACE：REPLACE 在 id 冲突时是「先删后插」，
     * 会静默覆盖已有消息。id 是调用方生成的，撞了就是 bug，应当直接抛出来。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(messages: List<MessageEntity>)

    /**
     * 插入或覆盖。**给流式回复用的**，语义和 [insert] 故意相反。
     *
     * 流式回复的写入路径是「占位 → 节流更新 N 次 → 收尾」，全程都是同一个 id，
     * 必须能反复写。这里用 `REPLACE`（= 先删后插）而不是 `@Upsert`，两个理由：
     *
     * 1. `@Upsert` 在 SQLite < 3.24 的机器上是模拟实现（先查再插/改），
     *    而 minSdk 28 对应 SQLite 3.22 —— 会走模拟分支，多一次查询还多一层不确定性。
     * 2. `message` 表没有外键、没有触发器，`REPLACE` 的「先删后插」不会级联删除任何东西。
     *    FTS 表是独立的表，不受影响，由 Repository 显式同步。
     *
     * 如果将来给 message 加了外键（比如附件表），**必须回来改成真正的 UPDATE**，
     * 否则每次流式更新都会把附件行连带删掉。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun getById(id: Long): MessageEntity?

    /**
     * 按时间正序取整个会话，用于装配上下文。
     *
     * `ORDER BY created_at, id` 里的 `id` 不是多余的：id 是毫秒时间戳，
     * 同一毫秒内连写两条（用户消息 + assistant 占位）时 `created_at` 会相等，
     * 此时 SQLite 的返回顺序**未定义** —— 一旦把 assistant 排到 user 前面，
     * 模型读到的就是一问一答颠倒的对话。id 严格递增，是天然的稳定次序。
     */
    @Query(
        """
        SELECT * FROM message
        WHERE conversation_id = :conversationId AND deleted = 0
        ORDER BY created_at ASC, id ASC
        """
    )
    suspend fun listByConversation(conversationId: String): List<MessageEntity>

    /**
     * 只取最近 N 条（倒序取、调用方反转）。上下文窗口有限时用它，
     * 别先 listByConversation 再在内存里截断。
     *
     * 排除 `streaming`：那是还没写完的半截回复，喂给模型会让它以为
     * 自己上一轮就是这么说的，于是接着往下写而不是重新回答。
     * 也排除 `deleted`：软删的消息不该再影响模型。
     *
     * `id DESC` 是稳定次序的保证，理由同 [listByConversation]。
     */
    @Query(
        """
        SELECT * FROM message
        WHERE conversation_id = :conversationId
          AND deleted = 0
          AND status != 'streaming'
        ORDER BY created_at DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun recentIn(conversationId: String, limit: Int): List<MessageEntity>

    /**
     * 与 [recentIn] 同，但**不排除 `streaming`**。
     *
     * 给界面用：用户需要看到那条「上次被杀进程打断的半截回复」，
     * 也需要看到正在生成的那条。而喂给模型的上下文必须排除它们（见 [recentIn]）。
     * 一个查询服务两个目的，所以分成两个方法而不是加个布尔参数 ——
     * 布尔参数调用点看不出语义，很容易传错。
     */
    @Query(
        """
        SELECT * FROM message
        WHERE conversation_id = :conversationId AND deleted = 0
        ORDER BY created_at DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun recentAllIn(conversationId: String, limit: Int): List<MessageEntity>

    /**
     * 往上翻一页：取排序位置在 `(beforeCreatedAt, beforeId)` **之前**的那些。
     *
     * ## 为什么游标必须是复合的
     *
     * 排序键是 `created_at DESC, id DESC`，游标就必须与排序键**完全一致**，
     * 否则会静默漏消息。只用 `id < :beforeId` 的话，一旦某行的 `created_at`
     * 与 `id` 不同序（用户改过系统时间、或数据是从别处导入的）就会漏：
     *
     * ```
     *   id=1, created_at=100   ← 排序在前
     *   id=2, created_at=50    ← 排序在后
     * ```
     * 第一页 limit=1 取到 id=1；第二页若用 `id < 1`，得到空 —— **id=2 永远看不到**。
     * 复合条件 `created_at < 100 OR (created_at = 100 AND id < 1)` 才能取到它。
     *
     * ## 排序必须与 [recentAllIn] 一致
     *
     * 两页的排序规则不一样的话，拼接起来会出现「明明更早的消息跑到前面」。
     *
     * 不排除 `streaming`，与 [recentAllIn] 同 —— 这是给界面用的，
     * 用户要能看到那条被打断的半截回复。
     */
    @Query(
        """
        SELECT * FROM message
        WHERE conversation_id = :conversationId AND deleted = 0
          AND (created_at < :beforeCreatedAt
               OR (created_at = :beforeCreatedAt AND id < :beforeId))
        ORDER BY created_at DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun pageBefore(
        conversationId: String,
        beforeCreatedAt: Long,
        beforeId: Long,
        limit: Int,
    ): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM message WHERE conversation_id = :conversationId AND deleted = 0")
    suspend fun countIn(conversationId: String): Int

    /** 所有还挂在 `streaming` 状态的消息。只在启动清理时用（见 [failInterrupted]）。 */
    @Query("SELECT * FROM message WHERE status = 'streaming'")
    suspend fun listStreaming(): List<MessageEntity>

    /**
     * 软删除。带 `conversation_id` 条件不是多余的 —— 调用方传错会话 id 时
     * 应当是「什么都没发生」，而不是删掉别的会话里的同号消息。
     *
     * **返回受影响行数，调用方必须用它决定要不要清索引。**
     * 无条件清索引会在「会话 id 传错」时把一条还在的消息从检索里悄悄抹掉，
     * 而且这种缺失没有任何报错。
     */
    @Query(
        """
        UPDATE message SET deleted = 1, updated_at = :now
        WHERE id = :id AND conversation_id = :conversationId AND deleted = 0
        """
    )
    suspend fun softDelete(id: Long, conversationId: String, now: Long): Int

    /**
     * 从 `(fromCreatedAt, fromId)` 那条起，把它和它**之后**的全部软删。
     *
     * 「重新生成」和「编辑重发」都靠它把对话退回到某个点。
     *
     * ## 条件必须与排序键对齐
     *
     * `created_at > :c OR (created_at = :c AND id >= :id)` —— 和列表的
     * 排序键 `created_at DESC, id DESC` 是同一套。只写 `id >= :id` 的话，
     * `created_at` 与 `id` 不同序的行会被多删或漏删，而且**不报错**：
     * 用户只会发现「我上一条消息不见了」，或者「重新生成之后怎么还留着半截」。
     * 同一条理由见 [pageBefore]。
     *
     * `>=` 而不是 `>`：**包含**起点那条。重新生成要删掉的正是那条
     * assistant 回复自己，编辑重发要删掉的正是那条用户消息自己。
     *
     * **返回受影响行数**，调用方据此决定清索引的范围 —— 与 [softDelete] 同。
     */
    @Query(
        """
        UPDATE message SET deleted = 1, updated_at = :now
        WHERE conversation_id = :conversationId AND deleted = 0
          AND (created_at > :fromCreatedAt
               OR (created_at = :fromCreatedAt AND id >= :fromId))
        """
    )
    suspend fun softDeleteFrom(
        conversationId: String,
        fromCreatedAt: Long,
        fromId: Long,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE message SET deleted = 1, updated_at = :now
        WHERE conversation_id = :conversationId AND deleted = 0
        """
    )
    suspend fun softDeleteIn(conversationId: String, now: Long)

    @Query("DELETE FROM message WHERE conversation_id = :conversationId")
    suspend fun hardDeleteIn(conversationId: String)

    /**
     * 把上次异常退出留下的 `streaming` 消息标成失败。
     *
     * App 启动时调一次。不做的话用户会看到一条永远在「正在输入」的回复，
     * 而且它一直被排除在上下文之外（见 [recentIn]），模型会莫名其妙地
     * 缺少一段它自己说过的话。
     *
     * @return 受影响行数，用于日志。
     */
    @Query(
        """
        UPDATE message SET status = 'failed', error = :reason, updated_at = :now
        WHERE status = 'streaming'
        """
    )
    suspend fun failInterrupted(reason: String, now: Long): Int
}
