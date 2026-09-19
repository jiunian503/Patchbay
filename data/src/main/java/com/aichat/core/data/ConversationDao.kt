package com.aichat.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 会话表读写。
 *
 * 这里没有 `insert`（纯插入）—— 建会话是「确保存在」语义，
 * 重复调用必须无副作用，所以只有 [insertIfAbsent]。
 */
@Dao
interface ConversationDao {

    /**
     * 会话列表。**置顶的排最前**，其余按最后活跃时间倒序。
     *
     * `pinned DESC` 放在 SQL 里而不是拿回内存再排：列表由 Flow 驱动，
     * 内存排序要等下一次数据库变化才生效，而用户点完「置顶」
     * 期待的是立刻看到它跳上去。
     */
    @Query("SELECT * FROM conversation ORDER BY pinned DESC, updated_at DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation ORDER BY pinned DESC, updated_at DESC LIMIT :limit")
    suspend fun list(limit: Int): List<ConversationEntity>

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    /**
     * 建会话。[ConversationEntity.title] 只在真正插入时生效 ——
     * 已经存在的会话不会被这次调用改名（改名走 [rename] 或 [titleIfUntitled]）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: ConversationEntity)

    @Query("UPDATE conversation SET updated_at = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    /**
     * 只在标题还是空串时填上。
     *
     * 条件写在 SQL 里而不是「先读出来判断再写」—— 后者在两个协程同时
     * 发第一条消息时会互相覆盖。这里一条 UPDATE 就够，天然原子。
     */
    @Query("UPDATE conversation SET title = :title, updated_at = :now WHERE id = :id AND title = ''")
    suspend fun titleIfUntitled(id: String, title: String, now: Long)

    /** 用户手动改名。无条件覆盖。 */
    @Query("UPDATE conversation SET title = :title, updated_at = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    /**
     * 置顶 / 取消置顶。
     *
     * **刻意不更新 `updated_at`** —— 那一列同时被用来显示「最后活跃时间」，
     * 置顶一下就把「3 小时前」变成「刚刚」是在撒谎。而且置顶本身就是
     * 排序操作，再顺带改一次排序键是双重影响，行为会变得难以预测。
     *
     * 对比 [rename]：那个更新 `updated_at`，因为它沿用的是一个既有行为，
     * 而且改名之后会话跳到列表顶部符合预期（用户刚动过它）。
     */
    @Query("UPDATE conversation SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversation SET provider_id = :providerId, model = :model, updated_at = :now WHERE id = :id")
    suspend fun setRoute(id: String, providerId: String?, model: String?, now: Long)

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * 会话列表（带消息数）。一次 JOIN 拿完，不要 N+1 地去数每条会话的消息。
     *
     * `LEFT JOIN` 是必须的：空会话也要出现在列表里。
     * 聚合条件里带 `m.deleted = 0`，否则软删的消息仍会被计入。
     */
    @Query(
        """
        SELECT c.id AS id,
               c.title AS title,
               c.updated_at AS updated_at,
               c.pinned AS pinned,
               COUNT(m.id) AS message_count
        FROM conversation c
        LEFT JOIN message m ON m.conversation_id = c.id AND m.deleted = 0
        GROUP BY c.id, c.title, c.updated_at, c.pinned
        ORDER BY c.pinned DESC, c.updated_at DESC
        LIMIT :limit
        """
    )
    suspend fun listSummaries(limit: Int): List<ConversationSummaryRow>
}
