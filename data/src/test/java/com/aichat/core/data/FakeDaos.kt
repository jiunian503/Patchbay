package com.aichat.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版 DAO 集合。
 *
 * ## 为什么值得手写这一套
 *
 * Repository 里的逻辑（谁先谁后、哪些状态不入索引、三态密钥怎么落）全是
 * **编排逻辑**，跟 SQL 本身无关。用 Room 跑这些断言要等模拟器（分钟级），
 * 用假 DAO 跑是毫秒级 —— 于是可以放心地把边界情况写全。
 *
 * 真正的 SQL 正确性由 `AppDatabaseMigrationTest` / `CjkSearchInstrumentedTest`
 * 在设备上保证。分工是刻意的：逻辑错误和 SQL 错误是两类问题。
 *
 * ## 假实现必须复刻的语义
 *
 * 只复刻**被测代码依赖的**那些语义，尤其是容易被忽略的：
 *
 * - `upsert` 是 REPLACE（先删后插），不是「只在不存在时插入」
 * - `softDelete` 带 `conversationId` 条件，会话 id 对不上时什么都不做
 * - `deleteIn` 的子查询**不看 deleted 标记**（真实 SQL 就是这样），
 *   所以如果实现方把 `hardDeleteIn` 排在它前面，这里必须能暴露出来
 * - `recentIn` 排除 `streaming` 与 `deleted`，且是**倒序**返回
 * - `softDeleteFrom` / `deleteFrom` 的游标是**复合**的（`created_at` 与 `id`
 *   一起比），且 `deleteFrom` 的子查询不看 `deleted`
 * - 会话列表一律 `pinned DESC, updated_at DESC`；`setPinned` 不动 `updated_at`
 */
class FakeMessageDao : MessageDao {

    val rows = LinkedHashMap<Long, MessageEntity>()

    override suspend fun insert(message: MessageEntity) {
        check(!rows.containsKey(message.id)) { "主键冲突：${message.id}" }
        rows[message.id] = message
    }

    override suspend fun insertAll(messages: List<MessageEntity>) {
        messages.forEach { insert(it) }
    }

    /** REPLACE 语义：有就覆盖，没有就插入。 */
    override suspend fun upsert(message: MessageEntity) {
        rows[message.id] = message
    }

    override suspend fun getById(id: Long): MessageEntity? = rows[id]

    override suspend fun listByConversation(conversationId: String): List<MessageEntity> =
        rows.values
            .filter { it.conversationId == conversationId && !it.deleted }
            .sortedWith(messageOrderAsc)

    override suspend fun recentIn(conversationId: String, limit: Int): List<MessageEntity> =
        rows.values
            .filter {
                it.conversationId == conversationId &&
                    !it.deleted &&
                    it.status != "streaming"
            }
            .sortedWith(messageOrderDesc)
            .take(limit)

    override suspend fun recentAllIn(conversationId: String, limit: Int): List<MessageEntity> =
        rows.values
            .filter { it.conversationId == conversationId && !it.deleted }
            .sortedWith(messageOrderDesc)
            .take(limit)

    override suspend fun pageBefore(
        conversationId: String,
        beforeCreatedAt: Long,
        beforeId: Long,
        limit: Int,
    ): List<MessageEntity> =
        rows.values
            .filter {
                it.conversationId == conversationId &&
                    !it.deleted &&
                    // 复合游标：与真实 SQL 一致。只比 id 的话，
                    // createdAt 与 id 不同序的行会被漏掉
                    (it.createdAt < beforeCreatedAt ||
                        (it.createdAt == beforeCreatedAt && it.id < beforeId))
            }
            .sortedWith(messageOrderDesc)
            .take(limit)

    override suspend fun countIn(conversationId: String): Int =
        rows.values.count { it.conversationId == conversationId && !it.deleted }

    override suspend fun listStreaming(): List<MessageEntity> =
        rows.values.filter { it.status == "streaming" }

    override suspend fun softDelete(id: Long, conversationId: String, now: Long): Int {
        val row = rows[id] ?: return 0
        // 会话 id 对不上 = 什么都不做（真实 SQL 的 WHERE 条件）
        if (row.conversationId != conversationId || row.deleted) return 0
        rows[id] = row.copy(deleted = true, updatedAt = now)
        return 1
    }

    /**
     * 「从某条开始（含）往后全部软删」。
     *
     * 游标语义与真实 SQL 一致，也是**复合游标**：`created_at > :c OR
     * (created_at = :c AND id >= :id)`。这里刻意不偷懒写成「按 id 比」——
     * 同一毫秒内会连写多条（用户消息 + assistant 占位），只比 id 会在
     * 排序键与 id 不同序时静默多删或漏删，而这正是它唯一的难点。
     */
    override suspend fun softDeleteFrom(
        conversationId: String,
        fromCreatedAt: Long,
        fromId: Long,
        now: Long,
    ): Int {
        var affected = 0
        rows.keys.toList().forEach { id ->
            val row = rows.getValue(id)
            val inRange = row.createdAt > fromCreatedAt ||
                (row.createdAt == fromCreatedAt && row.id >= fromId)
            if (row.conversationId == conversationId && !row.deleted && inRange) {
                rows[id] = row.copy(deleted = true, updatedAt = now)
                affected++
            }
        }
        return affected
    }

    override suspend fun softDeleteIn(conversationId: String, now: Long) {
        rows.keys.toList().forEach { id ->
            val row = rows.getValue(id)
            if (row.conversationId == conversationId && !row.deleted) {
                rows[id] = row.copy(deleted = true, updatedAt = now)
            }
        }
    }

    override suspend fun hardDeleteIn(conversationId: String) {
        rows.keys.toList().forEach { id ->
            if (rows.getValue(id).conversationId == conversationId) rows.remove(id)
        }
    }

    override suspend fun failInterrupted(reason: String, now: Long): Int {
        var affected = 0
        rows.keys.toList().forEach { id ->
            val row = rows.getValue(id)
            if (row.status == "streaming") {
                rows[id] = row.copy(status = "failed", error = reason, updatedAt = now)
                affected++
            }
        }
        return affected
    }

    private companion object {
        /**
         * 真实 SQL 是 `ORDER BY created_at, id` —— id 是必需的稳定次序，
         * 因为同一毫秒内会连写多条（用户消息 + assistant 占位），
         * `created_at` 相等时 SQLite 的返回顺序是未定义的。
         */
        val messageOrderAsc = compareBy<MessageEntity> { it.createdAt }.thenBy { it.id }
        val messageOrderDesc = compareByDescending<MessageEntity> { it.createdAt }.thenByDescending { it.id }
    }
}

class FakeFtsDao(private val messages: FakeMessageDao) : MessageFtsDao {

    val rows = LinkedHashMap<Long, MessageFtsEntity>()

    override suspend fun insert(entity: MessageFtsEntity) {
        rows[entity.rowId] = entity
    }

    override suspend fun delete(rowId: Long) {
        rows.remove(rowId)
    }

    /**
     * 复刻真实 SQL 的子查询：`WHERE rowid IN (SELECT id FROM message WHERE conversation_id = ?)`
     *
     * **不看 `deleted`，也不看消息是否还在** —— 所以如果调用方先执行了
     * `hardDeleteIn`，这里就查不到任何 id，索引行会残留。这正是
     * `RoomConversationStore.deleteConversation` 里顺序必须正确的理由。
     */
    override suspend fun deleteIn(conversationId: String) {
        val ids = messages.rows.values
            .filter { it.conversationId == conversationId }
            .map { it.id }
            .toSet()
        rows.keys.removeAll(ids)
    }

    /**
     * 复刻真实 SQL：子查询从 `message` 表里捞 id，**不看 `deleted`**。
     *
     * 调用方（`RoomConversationStore.deleteFrom`）的顺序是「先软删主表、
     * 再清索引」，所以到这里时那些行已经是 `deleted = 1` 了 —— 条件里
     * 一旦带上 `deleted = 0`，一条都删不掉，索引里会留下永远搜得到、
     * 点进去却看不到的幽灵命中。这里保持与真实 SQL 相同的宽容度，
     * 好让那种写法在测试里就暴露出来。
     */
    override suspend fun deleteFrom(conversationId: String, fromCreatedAt: Long, fromId: Long) {
        val ids = messages.rows.values
            .filter {
                it.conversationId == conversationId &&
                    (it.createdAt > fromCreatedAt ||
                        (it.createdAt == fromCreatedAt && it.id >= fromId))
            }
            .map { it.id }
            .toSet()
        rows.keys.removeAll(ids)
    }

    override suspend fun clear() {
        rows.clear()
    }

    /** 真实检索在设备上验证（CjkSearchInstrumentedTest），这里不假装能跑 FTS。 */
    override suspend fun search(expr: String, limit: Int): List<MessageSearchHit> =
        throw UnsupportedOperationException(
            "FTS 检索语义请在设备上用 CjkSearchInstrumentedTest 验证"
        )
}

class FakeConversationDao(private val messages: FakeMessageDao) : ConversationDao {

    val rows = LinkedHashMap<String, ConversationEntity>()

    private val changes = MutableStateFlow(0)

    override fun observeAll(): Flow<List<ConversationEntity>> =
        changes.map { rows.values.sortedWith(conversationOrder) }

    override suspend fun list(limit: Int): List<ConversationEntity> =
        rows.values.sortedWith(conversationOrder).take(limit)

    override suspend fun get(id: String): ConversationEntity? = rows[id]

    override suspend fun insertIfAbsent(entity: ConversationEntity) {
        rows.putIfAbsent(entity.id, entity)
        changes.value++
    }

    override suspend fun touch(id: String, now: Long) {
        rows[id]?.let { rows[id] = it.copy(updatedAt = now) }
    }

    override suspend fun titleIfUntitled(id: String, title: String, now: Long) {
        val row = rows[id] ?: return
        if (row.title.isNotEmpty()) return
        rows[id] = row.copy(title = title, updatedAt = now)
    }

    override suspend fun rename(id: String, title: String, now: Long) {
        rows[id]?.let { rows[id] = it.copy(title = title, updatedAt = now) }
    }

    /**
     * 复刻真实 SQL：只改 `pinned`，**不碰 `updated_at`**。
     *
     * 假实现顺手动一下时间戳是很容易发生的事，而真实 SQL 不动 ——
     * 那样这个假 DAO 会替实现方掩盖一个 bug：置顶把「最后活跃时间」
     * 抹成了「刚刚」，列表上的时间显示全乱。
     */
    override suspend fun setPinned(id: String, pinned: Boolean) {
        rows[id]?.let { rows[id] = it.copy(pinned = pinned) }
    }

    override suspend fun setRoute(
        id: String,
        providerId: String?,
        model: String?,
        now: Long,
    ) {
        rows[id]?.let {
            rows[id] = it.copy(providerId = providerId, model = model, updatedAt = now)
        }
    }

    /**
     * 复刻真实 SQL：只改 `character_id`，**不碰 `updated_at`**。
     * 理由和 [setPinned] 一样 —— 假实现顺手更新时间戳会替实现方掩盖 bug。
     */
    override suspend fun setCharacter(id: String, characterId: String?) {
        rows[id]?.let { rows[id] = it.copy(characterId = characterId) }
    }

    override suspend fun clearCharacter(characterId: String) {
        rows.keys.toList().forEach { id ->
            val row = rows.getValue(id)
            if (row.characterId == characterId) rows[id] = row.copy(characterId = null)
        }
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
        changes.value++
    }

    override suspend fun listSummaries(limit: Int): List<ConversationSummaryRow> =
        rows.values
            .sortedWith(conversationOrder)
            .take(limit)
            .map { c ->
                ConversationSummaryRow(
                    id = c.id,
                    title = c.title,
                    updatedAt = c.updatedAt,
                    pinned = c.pinned,
                    // 复刻真实 SQL 的 `LEFT JOIN ... AND m.deleted = 0`：
                    // 软删的消息不计入，空会话计数为 0
                    messageCount = messages.rows.values.count {
                        it.conversationId == c.id && !it.deleted
                    },
                )
            }

    private companion object {
        /** 与真实 SQL 的 `ORDER BY pinned DESC, updated_at DESC` 一致。 */
        val conversationOrder = compareByDescending<ConversationEntity> { it.pinned }
            .thenByDescending { it.updatedAt }
    }
}

class FakeProviderDao : ProviderDao {

    val rows = LinkedHashMap<String, ProviderEntity>()

    override fun observeAll(): Flow<List<ProviderEntity>> =
        MutableStateFlow(rows.values.sortedWith(providerOrder))

    override suspend fun list(): List<ProviderEntity> =
        rows.values.sortedWith(providerOrder)

    override suspend fun get(id: String): ProviderEntity? = rows[id]

    override suspend fun getDefault(): ProviderEntity? =
        rows.values.firstOrNull { it.isDefault }

    override suspend fun upsert(entity: ProviderEntity) {
        rows[entity.id] = entity
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }

    override suspend fun clearDefault() {
        rows.keys.toList().forEach { id ->
            val row = rows.getValue(id)
            if (row.isDefault) rows[id] = row.copy(isDefault = false)
        }
    }

    override suspend fun markDefault(id: String, now: Long) {
        rows[id]?.let { rows[id] = it.copy(isDefault = true, updatedAt = now) }
    }

    override suspend fun count(): Int = rows.size

    private companion object {
        val providerOrder = compareByDescending<ProviderEntity> { it.isDefault }
            .thenByDescending { it.updatedAt }
    }
}

class FakeCharacterDao : CharacterDao {

    val rows = LinkedHashMap<String, CharacterEntity>()

    private val changes = MutableStateFlow(0)

    /** 与真实 SQL 的 `ORDER BY created_at DESC` 一致。 */
    override fun observeAll(): Flow<List<CharacterEntity>> =
        changes.map { rows.values.sortedByDescending { it.createdAt } }

    override suspend fun list(): List<CharacterEntity> =
        rows.values.sortedByDescending { it.createdAt }

    override suspend fun get(id: String): CharacterEntity? = rows[id]

    override suspend fun upsert(entity: CharacterEntity) {
        rows[entity.id] = entity
        changes.value++
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
        changes.value++
    }
}

class FakeWorldBookEntryDao : WorldBookEntryDao {

    val rows = LinkedHashMap<String, WorldBookEntryEntity>()

    override suspend fun listFor(characterId: String): List<WorldBookEntryEntity> =
        rows.values
            .filter { it.characterId == characterId }
            .sortedWith(entryOrder)

    override suspend fun upsertAll(entities: List<WorldBookEntryEntity>) {
        entities.forEach { rows[it.id] = it }
    }

    override suspend fun deleteFor(characterId: String) {
        rows.keys.toList().forEach { id ->
            if (rows.getValue(id).characterId == characterId) rows.remove(id)
        }
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }

    private companion object {
        /** 与真实 SQL 的 `ORDER BY order_index, id` 一致。 */
        val entryOrder = compareBy<WorldBookEntryEntity>({ it.orderIndex }, { it.id })
    }
}

/**
 * 会抛异常的事务执行器，用来验证「数据库写失败时密钥被回滚」。
 */
class FailingTransactionRunner(private val message: String = "磁盘满了") : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = throw IllegalStateException(message)
}

/**
 * 假插件表。
 *
 * 排序复刻真实 SQL 的 `ORDER BY installed_at DESC` —— 列表页上用户
 * 想看到的是「我最近装了什么」。
 */
class FakePluginDao : PluginDao {

    val rows = LinkedHashMap<String, PluginEntity>()

    override fun observeAll(): Flow<List<PluginEntity>> =
        MutableStateFlow(rows.values.sortedByDescending { it.installedAt })

    override suspend fun list(): List<PluginEntity> =
        rows.values.sortedByDescending { it.installedAt }

    override suspend fun get(id: String): PluginEntity? = rows[id]

    override suspend fun count(): Int = rows.size

    override suspend fun upsert(entity: PluginEntity) {
        rows[entity.id] = entity
    }

    override suspend fun setEnabled(id: String, enabled: Boolean, now: Long) {
        rows[id]?.let { rows[id] = it.copy(enabled = enabled, updatedAt = now) }
    }

    override suspend fun setSettings(id: String, settingsJson: String?, now: Long) {
        rows[id]?.let { rows[id] = it.copy(settingsJson = settingsJson, updatedAt = now) }
    }

    override suspend fun setMcpCache(id: String, json: String?, now: Long) {
        rows[id]?.let { rows[id] = it.copy(mcpCacheJson = json, updatedAt = now) }
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }
}
