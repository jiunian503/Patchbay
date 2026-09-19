package com.aichat.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 服务商配置读写。
 *
 * 注意：**这里不碰 API Key**。密钥在 SecretStore 里，由
 * [ProviderRepository] 负责把两边拼起来。DAO 只认 [ProviderEntity]，
 * 于是「密钥有没有被写进数据库」这件事在类型层面就不可能发生。
 */
@Dao
interface ProviderDao {

    @Query("SELECT * FROM provider ORDER BY is_default DESC, updated_at DESC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM provider ORDER BY is_default DESC, updated_at DESC")
    suspend fun list(): List<ProviderEntity>

    @Query("SELECT * FROM provider WHERE id = :id")
    suspend fun get(id: String): ProviderEntity?

    @Query("SELECT * FROM provider WHERE is_default = 1 LIMIT 1")
    suspend fun getDefault(): ProviderEntity?

    /** REPLACE 语义：同一个 id 反复保存就是更新。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProviderEntity)

    @Query("DELETE FROM provider WHERE id = :id")
    suspend fun delete(id: String)

    /** 清空默认标记。[ProviderRepository.setDefault] 会先调它再调 [markDefault]。 */
    @Query("UPDATE provider SET is_default = 0 WHERE is_default = 1")
    suspend fun clearDefault()

    @Query("UPDATE provider SET is_default = 1, updated_at = :now WHERE id = :id")
    suspend fun markDefault(id: String, now: Long)

    @Query("SELECT COUNT(*) FROM provider")
    suspend fun count(): Int
}
