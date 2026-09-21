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
 * 三个文本列（[description] / [persona] / [firstMessage]）都不加 NOT NULL
 * 之外的约束：用户可以只填名字就保存（先建个壳、人设回头再写），
 * 空人设的注入会被 [PromptComposer] 跳过，空开场白不会落成任何消息。
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

    /**
     * 开场白：新会话里**角色先说的那一句**。空串 = 没有开场白。
     *
     * ## 为什么它不能并进 [persona]
     *
     * 两者的**生命周期完全不同**：人设每一轮都进系统提示词，开场白只在
     * 会话的第一句出现一次，之后它就是一条普通的历史消息。并进人设的话，
     * 模型会把那句「（茶山脚下，苏晚提着灯站在门口）……你来啦。」当成
     * **设定**，于是每轮都想再说一遍。
     *
     * ## 为什么在角色卡上，而不是在 `message` 表里
     *
     * 它是作者写在卡里的东西（SillyTavern 的 `first_mes`），属于角色、
     * 跨会话复用。存成消息的话，每开一个新会话都要从别处抄一份进来，
     * 而「抄进来的那份和原卡不一致」就成了一个新的、没人测试过的坏状态。
     *
     * 这里存的是**原样的文本**，不预先拼进任何提示词 —— 怎么用它
     * （落成消息 / 折进系统提示词）是 `:chat` 和宿主的事。
     */
    @ColumnInfo(name = "first_message")
    val firstMessage: String = "",

    /**
     * 备用开场白：和 [firstMessage] 一起构成「新会话里可以说的开场白」。
     *
     * ## 为什么是 JSON 列
     *
     * 和 `world_book_entry.keys_json` 同一个理由（见 [WorldBookEntryEntity]）：
     * 这份数据的读写形状是**整体**的 —— 编辑页永远是「读出全部、改一条、
     * 整体写回」，从来没有「按某一条开场白查询」这种需求。而且这个库里凡是
     * 「整体读写的结构化数据」都是 JSON 列，**没有 `@TypeConverters`**，
     * 编解码在 [CharacterRepository] 里手写（实体不依赖序列化框架）。
     *
     * ## 为什么不并进 [firstMessage]（合成一个列表）
     *
     * 主开场白有**独立的语义**：卡里 `first_mes` 是作者指定的那一句，
     * `alternate_greetings` 是「另外还能这么说」。分成两列，「只看主开场白」
     * 这条路径（编辑页那个单独的输入框、不想要备用的用户）就一直是简单的，
     * 不需要在列表下标和「哪一条是主」之间来回换算。
     *
     * 至于**挑哪一条去说**，不在这里 —— 那是宿主层的事
     * （`:app` 的 `CharacterGreetingSource` 抽签），`:chat` 只知道
     * 「这一轮该说这句」。
     *
     * 空串 = 没有备用开场白（不是 `[]`：v8 升上来的老数据就是空串）。
     */
    @ColumnInfo(name = "alternate_greetings_json")
    val alternateGreetingsJson: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
