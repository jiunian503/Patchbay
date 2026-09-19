package com.aichat.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 已装插件的读写。
 *
 * 和 [ProviderDao] 一样，**这里不碰任何密钥**：DAO 只认 [PluginEntity]，
 * 而那个实体里没有放密钥的地方。于是「插件密钥被写进数据库」这件事
 * 在类型层面就不可能发生。
 *
 * 排序按 `installed_at`（装的时间）而不是 id：列表页上用户想看到的是
 * 「我最近装了什么」。而**注册表合成时另按 id 排序**（见 `PluginRegistry`）——
 * 那两个顺序服务于不同的目的，不要合并。
 */
@Dao
interface PluginDao {

    @Query("SELECT * FROM plugin ORDER BY installed_at DESC")
    fun observeAll(): Flow<List<PluginEntity>>

    @Query("SELECT * FROM plugin ORDER BY installed_at DESC")
    suspend fun list(): List<PluginEntity>

    @Query("SELECT * FROM plugin WHERE id = :id")
    suspend fun get(id: String): PluginEntity?

    @Query("SELECT COUNT(*) FROM plugin")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PluginEntity)

    @Query("UPDATE plugin SET enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long)

    @Query("UPDATE plugin SET settings_json = :settingsJson, updated_at = :now WHERE id = :id")
    suspend fun setSettings(id: String, settingsJson: String?, now: Long)

    /**
     * 写入 MCP 工具清单缓存。
     *
     * ## 为什么只有「写」没有「清」
     *
     * 因为清缓存这件事**不需要单独的动作**：这份缓存能不能用由
     * `McpToolCache.fingerprint` 决定（装配侧比对，不一致就忽略）。
     * 加一个 `clearMcpCache` 的话，就会出现两个回答「这份缓存还算不算数」
     * 的地方 —— 而它们迟早会给出不同的答案（比如配置改了、指纹对不上，
     * 但清理逻辑没覆盖那种改法）。
     *
     * 唯一的例外是**卸载**：那一行整个没了，缓存跟着走，不需要额外处理。
     */
    @Query("UPDATE plugin SET mcp_cache_json = :json, updated_at = :now WHERE id = :id")
    suspend fun setMcpCache(id: String, json: String?, now: Long)

    @Query("DELETE FROM plugin WHERE id = :id")
    suspend fun delete(id: String)
}
