package com.aichat.core.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 会话消息的全文索引表。
 *
 * 为什么用 @Fts4 而不是 FTS5：
 * Room 只原生支持 FTS4；FTS5 需要手写建表 SQL，且依赖设备内置 SQLite 是否
 * 编译了 FTS5（各厂商 ROM 版本不一）。实测 FTS4 配合 CjkText 切分，
 * 召回结果与 LIKE 基线完全一致（spike/RESULT.md 方案 E），
 * 没有必要为了 FTS5 去冒机型兼容的风险。
 *
 * body 存的是 CjkText.forIndex() 处理过的文本（汉字间插了空格），
 * **绝不能直接拿去显示**。展示时用 rowid 回查 MessageEntity.content。
 */
@Fts4
@Entity(tableName = "message_fts")
data class MessageFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    @ColumnInfo(name = "body")
    val body: String,
)

/**
 * 检索结果投影。
 *
 * ## 正文来自主表，不是 FTS 表
 *
 * `message_fts.body` 是 [CjkText.forIndex] 处理过的（汉字两侧补了空格），
 * 直接显示会得到「会 议 纪 要」。
 *
 * ## 为什么带 `title`
 *
 * 一条搜出来的消息，如果不告诉用户在**哪个会话**里，他没法判断该不该点进去 ——
 * 同一句话可能在好几个会话里都说过。所以这个投影从一开始就该带标题，
 * 「只搜消息不带会话名」不是一个能用的形态。
 *
 * 标题可能是空串（用户还没命名），界面负责显示成「新对话」。
 */
data class MessageSearchHit(
    @ColumnInfo(name = "rowid") val rowId: Long,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "title") val conversationTitle: String,
)

@Dao
interface MessageFtsDao {

    /**
     * 必须与主表写入放在**同一个事务**里。
     * Room 不会自动同步 FTS 表 —— 主表删了消息而 FTS 表没删，
     * 检索结果里就会出现幽灵记录（JOIN 不到，或 JOIN 到已删除的行）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MessageFtsEntity)

    @Query("DELETE FROM message_fts WHERE rowid = :rowId")
    suspend fun delete(rowId: Long)

    /**
     * 按会话批量清索引。
     *
     * 子查询按 `conversation_id` 取全部消息，**不看 `deleted` 标记** ——
     * 所以这个方法的语义是「把这个会话的索引整片删掉」。调用方必须
     * 同时把主表里那些消息软删（同一个事务里），否则会留下一批
     * 「还在、但永远搜不到」的消息。
     *
     * 反过来，如果只想删一条，用 [delete]。
     */
    @Query(
        """
        DELETE FROM message_fts
        WHERE rowid IN (SELECT id FROM message WHERE conversation_id = :conversationId)
        """
    )
    suspend fun deleteIn(conversationId: String)

    /**
     * 从 `(fromCreatedAt, fromId)` 那条起，把它和它之后的索引行全删掉。
     *
     * ## 条件里**不能**带 `deleted = 0`
     *
     * 调用方的顺序是「先软删主表、再清索引」，所以走到这里时那批行已经是
     * `deleted = 1` 了。带上那个条件会一条都删不掉，而症状是
     * 「重新生成之后，被替换掉的那条回复还能被搜到」—— 从界面上看像是
     * 检索坏了，而不是删除没做干净。
     *
     * [deleteIn] 也是同一个道理：它的子查询同样不看 `deleted`。
     *
     * 条件必须与排序键一致（`created_at, id` 复合），理由见
     * `MessageDao.softDeleteFrom`。
     */
    @Query(
        """
        DELETE FROM message_fts
        WHERE rowid IN (
            SELECT id FROM message
            WHERE conversation_id = :conversationId
              AND (created_at > :fromCreatedAt
                   OR (created_at = :fromCreatedAt AND id >= :fromId))
        )
        """
    )
    suspend fun deleteFrom(
        conversationId: String,
        fromCreatedAt: Long,
        fromId: Long,
    )

    @Query("DELETE FROM message_fts")
    suspend fun clear()

    /**
     * 检索历史消息。
     *
     * expr 必须由 CjkText.forQuery() 构造，**不要**把用户原始输入直接拼进来：
     * 用户输入里的 `"`、`-`、`*` 会被 FTS 当成语法，轻则报错重则语义错乱。
     *
     * expr 为空串时调用方须提前短路返回空列表。**注意这里不是「会抛异常」**：
     * 实测 `MATCH ''` 只是返回空结果，抛的是 `MATCH 'OR'` / 落单引号 /
     * 未闭合括号这类畸形表达式。也就是说，**查询不报错不代表转义是对的** ——
     * 转义只有 `CjkText.forQuery` 这一道防线，靠 instrumented test 守。
     *
     * 排序用 created_at 而非 bm25：实测在小样本上 bm25 无区分度
     * （见 RESULT.md 方案 G），且中文单字索引下 IDF 意义不大。
     * 等数据量上来、确有效果再换。
     *
     * ## 几个看起来多余、实际不能省的细节
     *
     * - **`LEFT JOIN conversation`**（而不是 `JOIN`）：万一索引里残留了
     *   一条主表已经不存在、或会话行已被删的消息，`JOIN` 会让整条结果
     *   静默消失。检索结果少一条比多一条难查得多。
     * - **`COALESCE(c.title, '')`**：`LEFT JOIN` 没匹配上时 `title` 是 NULL，
     *   而 Kotlin 侧是非空 `String`，Room 会在映射时抛。
     * - **`m.deleted = 0`**：正常情况下软删的消息在同一个事务里已经清掉索引了
     *   （见 `RoomConversationStore.delete`），这里是**防御性**的 ——
     *   历史数据、或将来某次改动漏了一半，都会让已删除的消息重新出现在
     *   搜索结果里，而那种 bug 从界面上看像是「删除没生效」。
     * - **`ORDER BY` 补 `m.id DESC`**：同一毫秒内写入的多条消息
     *   `created_at` 完全相同（工具循环里 user → assistant → tool 就是），
     *   只按时间排的话每次查询顺序可能不同，结果列表会自己跳动。
     */
    @Query(
        """
        SELECT m.rowid AS rowid,
               m.content AS content,
               m.conversation_id AS conversation_id,
               m.created_at AS created_at,
               COALESCE(c.title, '') AS title
        FROM message_fts f
        JOIN message m ON m.id = f.rowid
        LEFT JOIN conversation c ON c.id = m.conversation_id
        WHERE message_fts MATCH :expr
          AND m.deleted = 0
        ORDER BY m.created_at DESC, m.id DESC
        LIMIT :limit
        """
    )
    suspend fun search(expr: String, limit: Int = 50): List<MessageSearchHit>
}
