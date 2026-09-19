package com.aichat.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 会话消息主表。
 *
 * 表名故意用单数 `message` —— MessageFtsDao 的检索 SQL 里 JOIN 的就是它，
 * 改名必须同步改那边。
 *
 * id 由调用方生成（不 autoGenerate）：消息需要在前端先落地、流式过程中就要有稳定
 * 身份，等数据库分配自增 id 会让「半截消息续写」「断线恢复」变复杂。
 * 生成策略见 `:chat` 的 `MonotonicIds`。
 *
 * ## v2 补上的字段
 *
 * v1 只有 id / conversation_id / content / created_at —— 那是「先把检索链路
 * 打通」的最小形态。真正要跑对话就必须有下面这些，缺一个都存不下：
 *
 * - [role]：没有它，重启后上下文装配不出正确顺序（谁的发言都分不清）
 * - [status]：没有它，分不清「正在写」「写完了」「用户停了」「报错了」
 * - [reasoning]：推理模型的思维链，用户要能回看
 * - [toolCallsJson] / [toolCallId]：工具循环回灌上下文的必需品。
 *   assistant 消息带的 `tool_calls` 和 tool 消息带的 `tool_call_id` 是配对的，
 *   丢一边模型就会报「找不到对应的调用」
 * - [model]：同一条会话里换过模型时，用户看得出哪句是谁答的
 * - [deleted]：软删除。硬删会让「误删恢复」永远做不到，而聊天记录是用户资产
 * - [updatedAt]：流式回复会被反复改写，需要知道最后改动时间
 *
 * 全部用 `NOT NULL` + 默认值迁移（见 [Migrations]），保证 v1 的老数据不会因为
 * 加列而失败 —— 用户手里可能已经有聊天记录了，迁移必须无损。
 */
@Entity(
    tableName = "message",
    indices = [Index(value = ["conversation_id", "created_at"])],
)
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    /** `system` / `user` / `assistant` / `tool`。存字符串而非序号，加角色不会错位。 */
    @ColumnInfo(name = "role")
    val role: String,

    @ColumnInfo(name = "content")
    val content: String,

    /** 思维链。存下来给用户看，但**不会**回灌给模型。 */
    @ColumnInfo(name = "reasoning")
    val reasoning: String? = null,

    /** `AssistantToolCall` 列表的 JSON。只在 role = assistant 时非空。 */
    @ColumnInfo(name = "tool_calls")
    val toolCallsJson: String? = null,

    /** 这条结果对应哪次调用。只在 role = tool 时非空。 */
    @ColumnInfo(name = "tool_call_id")
    val toolCallId: String? = null,

    /** 见 `:chat` 的 `MessageStatus.wire`。 */
    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "model")
    val model: String? = null,

    /** 失败原因，直接展示给用户的一句话。 */
    @ColumnInfo(name = "error")
    val error: String? = null,

    @ColumnInfo(name = "deleted")
    val deleted: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
