package com.aichat.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 会话。
 *
 * 单开一张表而不是「靠 message 里的 conversation_id 去重」，是因为会话列表
 * 要显示标题和最后活跃时间，而这两样在只有消息表的情况下都得靠聚合查询 ——
 * 消息一多就慢，而且**空会话（用户点了新建还没说话）根本表达不出来**。
 *
 * [title] 允许为空串。空串表示「用户还没命名」，界面上显示成「新对话」，
 * 并且可以被第一条用户消息自动填上（`titleIfUntitled`）。
 * 用空串而不是 NULL，是为了让那条 UPDATE 的 WHERE 条件能写成简单的 `title = ''`。
 *
 * [providerId] / [model] 记的是这个会话**上次用的**服务商和模型。
 * 允许为空：用户可以建了会话再去配 Key。
 */
@Entity(tableName = "conversation")
data class ConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "title")
    val title: String = "",

    @ColumnInfo(name = "provider_id")
    val providerId: String? = null,

    @ColumnInfo(name = "model")
    val model: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    /**
     * 用户手动置顶。
     *
     * 单独一列而不是复用 [updatedAt]：两者语义不同 —— 这一列表达
     * 「用户希望它留在上面」，[updatedAt] 表达「最后一次说话是什么时候」。
     * 用同一个字段表达两件事的话，用户一置顶，那条会话显示的
     * 「最后活跃时间」就变成了「刚刚」，而这是假的。
     */
    @ColumnInfo(name = "pinned")
    val pinned: Boolean = false,
)

/** 会话列表项的查询投影 —— 消息数由 JOIN 聚合出来，不额外查一次。 */
data class ConversationSummaryRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "pinned") val pinned: Boolean,
    @ColumnInfo(name = "message_count") val messageCount: Int,
)
