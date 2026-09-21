package com.aichat.core.data

import com.aichat.domain.prompt.WorldBookEntry
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 表单里的一条世界书词条。
 *
 * 没有 `orderIndex` —— 注入顺序由它在 [CharacterDraft.entries] 里的**下标**
 * 决定。界面上那就是拖拽排序的结果，让用户另外填一个序号是把内部表示
 * 泄漏到表单上。
 */
data class WorldBookEntryDraft(
    /** null = 新建的词条。 */
    val id: String? = null,
    val keys: List<String> = emptyList(),
    val content: String = "",
    val enabled: Boolean = true,
    val caseSensitive: Boolean = false,
)

/**
 * 新建 / 编辑角色卡时的输入。
 *
 * 和 [ProviderDraft] 一样，字段是「用户填得出来的形态」，
 * 而不是 [CharacterEntity] 那种「库里存的形态」（那边触发词是 JSON 字符串）。
 */
data class CharacterDraft(
    /** null = 新建。 */
    val id: String? = null,
    val name: String = "",
    /** 界面上的简介。**不会**进提示词。 */
    val description: String = "",
    /** 人设正文。会作为系统提示词的第一层注入。 */
    val persona: String = "",
    val entries: List<WorldBookEntryDraft> = emptyList(),
)

/**
 * 角色卡与世界书的读写。
 *
 * ## 为什么删角色要跨三张表，而这三件事必须在一个事务里
 *
 * 删一张角色卡要动：`character`（删这一行）、`world_book_entry`（删它的词条）、
 * `conversation`（把引用它的会话的 `character_id` 清空）。三件事分开做的话，
 * 中途失败会留下两种坏状态：孤儿词条（用户看不见但一直占着库），
 * 或者**指向不存在角色的会话** —— 后者更糟，因为每轮对话都要去查一次角色，
 * 查不到时的行为就成了一个没人测试过的分支。
 *
 * ## 序列化在 Repository 层手写，实体不依赖序列化框架
 *
 * 和 `ProviderRepository` 处理 `extra_headers` 是同一个做法。好处是
 * [CharacterEntity] / [WorldBookEntryEntity] 是纯数据类，
 * 单测里直接构造对象就行，不需要任何 JSON 夹具。
 */
class CharacterRepository(
    private val characters: CharacterDao,
    private val entries: WorldBookEntryDao,
    private val conversations: ConversationDao,
    private val tx: TransactionRunner,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observe(): Flow<List<CharacterEntity>> = characters.observeAll()

    suspend fun list(): List<CharacterEntity> = characters.list()

    suspend fun get(id: String): CharacterEntity? = characters.get(id)

    /**
     * 一个角色的世界书条目，已按注入顺序排好，并转成 `:domain` 的类型。
     *
     * 返回 `WorldBookEntry` 而不是实体：调用方（编辑页、注入侧）要的都是
     * 「触发词是 `List<String>`」这个形态，没人想看 `keys_json`。
     */
    suspend fun entriesFor(characterId: String): List<WorldBookEntry> =
        entries.listFor(characterId).map { it.toDomain() }

    /**
     * 新建或更新一张角色卡（含它的全部词条）。
     *
     * @return 角色 id（新建时是新生成的）。
     */
    suspend fun save(draft: CharacterDraft): String {
        val name = draft.name.trim()
        require(name.isNotEmpty()) { "角色名不能为空" }

        val id = draft.id ?: newId()
        val now = clock()

        // created_at 要保住。每次保存都刷新的话，「最近创建」这个排序键
        // 就变成了「最近修改」，而列表页按它排 —— 改一下错别字就把角色
        // 顶到最前面，用户会以为列表乱了
        val createdAt = draft.id?.let { characters.get(it)?.createdAt } ?: now

        // 先滤掉「触发词和内容都空」的残条，再按下标编号。
        // 用 mapIndexedNotNull 直接干的话，下标会带着被滤掉的位置，
        // order_index 出现空洞 —— 排序仍然对，但「为什么是 0,2,3」
        // 会让下一个读这段代码的人以为有 bug
        val valid = draft.entries.filterNot { it.isBlank() }
        val rows = valid.mapIndexed { index, entry -> entry.toEntity(id, index) }

        tx.run {
            characters.upsert(
                CharacterEntity(
                    id = id,
                    name = name,
                    description = draft.description.trim(),
                    persona = draft.persona.trim(),
                    createdAt = createdAt,
                    updatedAt = now,
                )
            )

            // 词条整体替换，不做逐条 diff：词条没有稳定身份 ——
            // 用户把触发词改一下再改回来，diff 会认为「没变」，
            // 而中间那次顺序变化（order_index 是拖出来的）是要保留的
            entries.deleteFor(id)
            if (rows.isNotEmpty()) entries.upsertAll(rows)
        }
        return id
    }

    /** 删一张角色卡。三张表一起动，见类注释。 */
    suspend fun delete(id: String) {
        tx.run {
            entries.deleteFor(id)
            conversations.clearCharacter(id)
            characters.delete(id)
        }
    }

    private fun WorldBookEntryEntity.toDomain() = WorldBookEntry(
        id = id,
        keys = decodeKeys(keysJson),
        content = content,
        enabled = enabled,
        orderIndex = orderIndex,
        caseSensitive = caseSensitive,
    )

    private fun WorldBookEntryDraft.toEntity(
        characterId: String,
        index: Int,
    ) = WorldBookEntryEntity(
        id = id ?: newId(),
        characterId = characterId,
        keysJson = JSON.encodeToString(KEYS, keys.map { it.trim() }.filter { it.isNotEmpty() }),
        content = content.trim(),
        enabled = enabled,
        orderIndex = index,
        caseSensitive = caseSensitive,
    )

    /** 触发词和内容都空 = 用户新建了一条还没填，不该落库。 */
    private fun WorldBookEntryDraft.isBlank(): Boolean =
        keys.all { it.isBlank() } && content.isBlank()

    /**
     * 解触发词 JSON。
     *
     * **坏数据返回空列表，不抛异常。** 这一列是用户数据的投影，一份写坏的
     * JSON 不该让整场对话崩掉 —— 它的最坏后果只是「这一条世界书不生效」，
     * 而抛异常的最坏后果是「这个会话再也发不出消息，而用户看不懂报错」。
     */
    private fun decodeKeys(json: String): List<String> =
        runCatching { Json.decodeFromString(KEYS, json) }.getOrDefault(emptyList())

    companion object {
        private val KEYS = ListSerializer(String.serializer())
        private val JSON = Json
    }
}
