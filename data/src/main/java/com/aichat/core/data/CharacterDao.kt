package com.aichat.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 角色卡的读写。
 *
 * ## 表名为什么到处都带反引号
 *
 * `CHARACTER` 是 SQLite 的类型名关键字。它出现在 `CREATE TABLE` 后面时
 * SQLite 能正确理解成表名，但手写的 `@Query` 一旦进了别的上下文
 * （子查询、JOIN、`UPDATE ... SET`）就可能被当成类型名解析。
 * 一律加反引号是零成本的保险 —— 而且 Room 自己生成的建表 SQL 也带反引号，
 * 迁移校验比对 `createSql` 时两边写法一致。
 *
 * ## 为什么没有「按名字查」这类方法
 *
 * 现在没有任何调用方需要它。名字不唯一（用户可以建两个「助手」），
 * 拿它当查询键迟早会出事 —— 要按名字找就得先规定「重名怎么办」，
 * 而那个规定现在没有需求支撑。
 */
@Dao
interface CharacterDao {

    /**
     * 列表页。按 `created_at` 倒序 —— 用户想看到的是「我最近捏的那个」。
     *
     * 不按名字排：角色名是用户自己起的，中英文混排时按名字排的结果
     * 在用户眼里是乱的（大写字母全跑到汉字前面）。
     */
    @Query("SELECT * FROM `character` ORDER BY created_at DESC")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM `character` ORDER BY created_at DESC")
    suspend fun list(): List<CharacterEntity>

    @Query("SELECT * FROM `character` WHERE id = :id")
    suspend fun get(id: String): CharacterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CharacterEntity)

    @Query("DELETE FROM `character` WHERE id = :id")
    suspend fun delete(id: String)
}
