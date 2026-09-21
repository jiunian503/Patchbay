package com.aichat.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 世界书条目的读写。
 *
 * ## 排序键和索引是对齐的
 *
 * 三个方法都按 `(character_id, order_index, id)` 排，而实体上的复合索引
 * 正好是 `(character_id, order_index)` —— 查询走索引，不会退化成全表扫 + 排序。
 *
 * 末尾那个 `id` 是**稳定性的兜底**：两条 `order_index` 相同的词条如果顺序
 * 不确定，同一个会话两次发送就可能注入不同的顺序，而这类差异极难排查
 * （模型行为变了，但提示词看起来「差不多」）。`WorldBookMatcher` 里也用了
 * 同一套排序规则，两边必须一致。
 */
@Dao
interface WorldBookEntryDao {

    /** 一个角色的全部条目，按注入顺序。 */
    @Query(
        "SELECT * FROM world_book_entry WHERE character_id = :characterId " +
            "ORDER BY order_index, id"
    )
    suspend fun listFor(characterId: String): List<WorldBookEntryEntity>

    /**
     * 整体替换一个角色的条目。
     *
     * 编辑页保存时用它：先 `deleteFor` 再 `upsertAll`，同一个事务里。
     * 不做「逐条 diff」是因为词条没有稳定身份 —— 用户改一下触发词再改回来，
     * diff 算法会认为「没变」，而实际上中间那次「删除+新增」的顺序变化
     * 是要保留的（`order_index` 是用户拖出来的）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<WorldBookEntryEntity>)

    /** 删角色时清掉它的条目。必须和 `CharacterDao.delete` 在同一个事务里。 */
    @Query("DELETE FROM world_book_entry WHERE character_id = :characterId")
    suspend fun deleteFor(characterId: String)

    @Query("DELETE FROM world_book_entry WHERE id = :id")
    suspend fun delete(id: String)
}
