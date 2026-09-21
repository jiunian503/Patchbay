package com.aichat.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 角色卡：一套可复用的「人设」。
 *
 * ## 为什么单独一张表，而不是继续用 `provider.system_prompt`
 *
 * v6 时把提示词放在 `provider` 表上，理由是「一个会话 = 一套配置」已经成立。
 * 但那条判断有个前提：**同一个服务商下用不同人设**这件事的收益不确定。
 * 现在它确定了，而且一旦确定，放在 `provider` 上就不成立了 ——
 * 提示词跟着服务商走意味着「换个服务商就得把人设重打一遍」，
 * 而人设和用哪家 API 毫无关系。
 *
 * 所以这一版把它提升成独立实体：**一个角色卡可以被任意多个会话、
 * 跨任意多个服务商复用**。`provider.system_prompt` 保留但换了定位，
 * 见 [ProviderEntity.systemPrompt]（现在是「附加提示词」，对所有人都生效的补充）。
 *
 * ## 为什么 `persona` 和 `description` 是两列
 *
 * 它们会去**两个不同的地方**：[description] 只在界面上显示（列表页的
 * 一行简介，帮用户在十几个角色里认出想要的那个），[persona] 会原样
 * 进系统提示词。合成一列的话，用户写的那句「这是我照着小说捏的」
 * 也会被发进提示词里，而模型会当真。
 *
 * 两列都不加 NOT NULL 之外的约束：用户可以只填名字就保存
 * （先建个壳、人设回头再写），空人设的注入会被 [PromptComposer] 跳过。
 *
 * ## 没有头像列
 *
 * 头像要么存文件、要么存 base64，两条路都要引入一套新的存储与清理逻辑
 * （删角色时要连文件一起删）。先不做 —— 需要的话是个独立的一轮。
 */
@Entity(tableName = "character")
data class CharacterEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** 界面上的简介。**不会**进提示词，见类注释。 */
    @ColumnInfo(name = "description")
    val description: String = "",

    /** 人设正文。会作为系统提示词的第一层注入。 */
    @ColumnInfo(name = "persona")
    val persona: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
