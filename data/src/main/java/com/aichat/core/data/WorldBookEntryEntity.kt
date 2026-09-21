package com.aichat.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 世界书条目：一组触发词 + 命中后要注入的内容。
 *
 * ## 为什么没有独立的 `world_book` 表
 *
 * 这一版里世界书是**挂在角色卡上**的（一个角色一本），所以条目直接带
 * [characterId]，不需要中间那层。做成「独立世界书 + 多角色共享」要多一张表
 * 加一层导航（角色 → 世界书 → 条目），而那个能力现在没人需要 ——
 * 真要共享设定，复制一份条目比共享一本更难出错的库。
 *
 * 将来若要独立化，迁移路径是现成的：新建 `world_book` 表，把每个角色的
 * 条目各自包成一本同名世界书，再给这张表加一列 `book_id`。**不是死路**，
 * 所以现在可以放心选简单的那个。
 *
 * ## 为什么 `keys_json` 是一列 JSON 而不是一张子表
 *
 * 和 `tool_calls`、`settings_json`、`mcp_cache_json` 同一个理由：这份数据的
 * 读写形状是**整体**的 —— 编辑一条词条时永远是「读出全部触发词、改一个、
 * 整体写回」，从来没有「按触发词跨角色查询」这种需求。拆成子表要多一次
 * join 和一套级联删除，换来的能力用不上。
 *
 * 这个库里凡是「整体读写的结构化数据」都是 JSON 列，而且**没有
 * `@TypeConverters`** —— 序列化在 Repository 层手写（见 `CharacterRepository`），
 * 这样 `:data` 的实体不依赖任何序列化框架，单测里直接构造对象就行。
 *
 * ## 为什么不用外键
 *
 * 和 [MessageEntity] 一致（那张表的 `conversation_id` 也没建外键）。
 * 删角色时由 `CharacterRepository` 在**同一个事务**里删掉它的条目 ——
 * 外键的级联删除同样要写 `ON DELETE CASCADE`，而 Room 的迁移校验
 * 会逐字比对建表 SQL，多一处外键就多一处只能靠真机测试发现的漂移。
 */
@Entity(
    tableName = "world_book_entry",
    indices = [Index(value = ["character_id", "order_index"])],
)
data class WorldBookEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "character_id")
    val characterId: String,

    /** 触发词列表的 JSON 数组，如 `["老王","王叔"]`。见类注释。 */
    @ColumnInfo(name = "keys_json")
    val keysJson: String,

    /** 命中后追加进系统提示词的内容。 */
    @ColumnInfo(name = "content")
    val content: String,

    /** 关掉之后不再参与匹配，但条目留着。 */
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    /** 注入顺序，小的在前。查询按它排，所以进了复合索引。 */
    @ColumnInfo(name = "order_index")
    val orderIndex: Int = 0,

    @ColumnInfo(name = "case_sensitive")
    val caseSensitive: Boolean = false,
)
