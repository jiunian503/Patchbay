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

    /**
     * 这个会话选的角色卡（`character.id`）。
     *
     * ## 为什么可空，而且老会话全是 null
     *
     * 没选角色 = 不注入任何东西。所以 v6 升上来的会话（这一列一律 null）
     * 发出的请求和升级前**逐字节相同** —— 这是这次改动最重要的向后兼容保证。
     * 加一列 NOT NULL + 默认值也能跑，但那样就表达不出「这个会话还没选角色」
     * 和「用户明确选了空角色」的区别，而前者才是升级后的真实状态。
     *
     * ## 为什么不建外键
     *
     * 和本文件其它列一致。角色被删掉时，`CharacterRepository` 会在同一个
     * 事务里把引用它的会话这一列清成 null（而不是删会话）—— 用户删了一个
     * 角色卡，不该连带着把用它的聊天记录也删了。
     */
    @ColumnInfo(name = "character_id")
    val characterId: String? = null,
)

/** 会话列表项的查询投影 —— 消息数由 JOIN 聚合出来，不额外查一次。 */
data class ConversationSummaryRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "pinned") val pinned: Boolean,
    @ColumnInfo(name = "message_count") val messageCount: Int,
)
