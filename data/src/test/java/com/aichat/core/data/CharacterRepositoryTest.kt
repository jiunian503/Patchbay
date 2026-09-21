package com.aichat.core.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CharacterRepository] 的落盘规则。
 *
 * 全部跑在假 DAO 上，秒级完成 —— 这正是把 DAO 做成接口的意义。
 * 真机上另有一条 `AppDatabaseMigrationTest` 验 6→7 的迁移，两者分工不同：
 * 这里验「逻辑对不对」，那边验「库建得对不对」。
 */
class CharacterRepositoryTest {

    private val characters = FakeCharacterDao()
    private val entries = FakeWorldBookEntryDao()
    private val conversations = FakeConversationDao(FakeMessageDao())

    private var nextId = 0
    private var now = 1_000L

    private val repo = CharacterRepository(
        characters = characters,
        entries = entries,
        conversations = conversations,
        tx = NoTransactionRunner,
        newId = { "id-${nextId++}" },
        clock = { now },
    )

    private fun draft(
        id: String? = null,
        name: String = "助手",
        description: String = "",
        persona: String = "你是一位诗人。",
        firstMessage: String = "",
        alternateGreetings: List<String> = emptyList(),
        entries: List<WorldBookEntryDraft> = emptyList(),
    ) = CharacterDraft(
        id = id,
        name = name,
        description = description,
        persona = persona,
        firstMessage = firstMessage,
        alternateGreetings = alternateGreetings,
        entries = entries,
    )

    /**
     * 开场白要**原样**存下来：它里面通常有换行、括号、中文标点，
     * 而且那是作者写的文本 —— 这一层谁都不该做转义或截断，只裁两端空白。
     */
    @Test
    fun `开场白原样落库，两端空白裁掉`() = runTest {
        val greeting = "（茶山脚下，苏晚提着灯站在门口）\n……你来啦。"
        val id = repo.save(draft(firstMessage = "  $greeting  "))
        assertEquals(greeting, characters.rows.getValue(id).firstMessage)
    }

    /**
     * `save` 是**整体替换**：草稿里没带的字段就等于清空。
     *
     * 这一条钉住的是个容易踩的坑 —— 开场白是后加的字段，任何一个拼
     * `CharacterDraft` 的调用点漏了它，用户的开场白就会被一次
     * 「只改了个名字」的保存悄悄抹掉，而且**不报错**。
     */
    @Test
    fun `更新时开场白整体替换，草稿没带就清空`() = runTest {
        val id = repo.save(draft(firstMessage = "你好呀。"))
        assertEquals("你好呀。", characters.rows.getValue(id).firstMessage)

        repo.save(draft(id = id, name = "新名字"))

        val row = characters.rows.getValue(id)
        assertEquals("新名字", row.name)
        assertEquals("", row.firstMessage)
    }

    @Test
    fun `没填开场白时是空串`() = runTest {
        val id = repo.save(draft())
        assertEquals("", characters.rows.getValue(id).firstMessage)
    }

    // ---- 备用开场白（v9 加的那一列）----

    /**
     * 落库是 JSON 列，但**读出来必须是 `List<String>`** ——
     * `alternate_greetings_json` 这个存储形状不该漏到调用方。
     */
    @Test
    fun `备用开场白以 List 形态读出，不是 JSON 字符串`() = runTest {
        val id = repo.save(draft(alternateGreetings = listOf("早", "晚安")))
        assertEquals("[\"早\",\"晚安\"]", characters.rows.getValue(id).alternateGreetingsJson)
        assertEquals(listOf("早", "晚安"), repo.alternateGreetingsFor(id))
    }

    @Test
    fun `备用开场白落库前裁空白、滤空串`() = runTest {
        val id = repo.save(draft(alternateGreetings = listOf(" 早 ", "", "  ", "晚安")))
        assertEquals(listOf("早", "晚安"), repo.alternateGreetingsFor(id))
    }

    /**
     * 和主开场白同一条规则：`save` 是**整体替换**，草稿没带就等于清空。
     * 漏了这条，一次「只改了个名字」的保存会把备用开场白悄悄抹掉，而且不报错。
     */
    @Test
    fun `更新时备用开场白整体替换，草稿没带就清空`() = runTest {
        val id = repo.save(draft(alternateGreetings = listOf("早")))
        assertEquals(listOf("早"), repo.alternateGreetingsFor(id))

        repo.save(draft(id = id, name = "新名字"))

        assertTrue(repo.alternateGreetingsFor(id).isEmpty())
    }

    @Test
    fun `坏掉的备用开场白 JSON 不抛异常，返回空列表`() = runTest {
        characters.rows["broken"] = CharacterEntity(
            id = "broken",
            name = "坏的",
            alternateGreetingsJson = "{这不是 JSON",
            createdAt = 1L,
            updatedAt = 1L,
        )
        // 一份写坏的 JSON 最坏只该让「这次没有备用开场白」，
        // 而不是让整个会话发不出消息
        assertTrue(repo.alternateGreetingsFor("broken").isEmpty())
    }

    /**
     * v8 升上来的老行是 `DEFAULT ''`（不是 `[]`）—— 空串解不开，
     * 那条路径必须**安静地**给出「没有备用开场白」。
     */
    @Test
    fun `老数据的空串解成空列表`() = runTest {
        val id = repo.save(draft())
        assertEquals("[]", characters.rows.getValue(id).alternateGreetingsJson)
        assertTrue(repo.alternateGreetingsFor(id).isEmpty())
    }

    // ---- greetingsFor：注入侧的候选 ----

    @Test
    fun `候选是主开场白在前、备用在后`() = runTest {
        val id = repo.save(
            draft(firstMessage = "你来啦", alternateGreetings = listOf("早", "晚安"))
        )
        assertEquals(listOf("你来啦", "早", "晚安"), repo.greetingsFor(id))
    }

    @Test
    fun `主开场白为空时候选只剩备用`() = runTest {
        val id = repo.save(draft(alternateGreetings = listOf("早")))
        assertEquals(listOf("早"), repo.greetingsFor(id))
    }

    @Test
    fun `两条都没有时候选是空列表`() = runTest {
        val id = repo.save(draft())
        assertTrue(repo.greetingsFor(id).isEmpty())
    }

    /**
     * 角色不存在（会话指向一张已删掉的卡）时返回空，**不抛** ——
     * 正确行为是「这次不落开场白」，抛异常会让用户看到一条和聊天无关的报错。
     */
    @Test
    fun `角色不存在时返回空列表，不抛`() = runTest {
        assertTrue(repo.greetingsFor("没有这个角色").isEmpty())
        assertTrue(repo.alternateGreetingsFor("没有这个角色").isEmpty())
    }

    @Test
    fun `新建角色返回新 id 并落库`() = runTest {
        val id = repo.save(draft())
        assertEquals("id-0", id)

        val row = characters.rows.getValue(id)
        assertEquals("助手", row.name)
        assertEquals("你是一位诗人。", row.persona)
        assertEquals(1_000L, row.createdAt)
        assertEquals(1_000L, row.updatedAt)
    }

    @Test
    fun `角色名为空时抛异常，且不落库`() = runTest {
        val thrown = runCatching { repo.save(draft(name = "   ")) }.exceptionOrNull()
        assertEquals("角色名不能为空", thrown?.message)
        assertTrue(characters.rows.isEmpty())
    }

    @Test
    fun `角色名与简介两端空白被裁掉`() = runTest {
        val id = repo.save(draft(name = "  老王  ", description = " 一个测试角色 "))
        val row = characters.rows.getValue(id)
        assertEquals("老王", row.name)
        assertEquals("一个测试角色", row.description)
    }

    @Test
    fun `再次保存保留 created_at，只刷新 updated_at`() = runTest {
        val id = repo.save(draft())
        val createdAt = characters.rows.getValue(id).createdAt

        now = 9_999L
        repo.save(draft(id = id, name = "改了名"))

        val row = characters.rows.getValue(id)
        assertEquals(createdAt, row.createdAt)
        assertEquals(9_999L, row.updatedAt)
        assertEquals("改了名", row.name)
    }

    @Test
    fun `词条按列表下标编号`() = runTest {
        val id = repo.save(
            draft(
                entries = listOf(
                    WorldBookEntryDraft(keys = listOf("a"), content = "A"),
                    WorldBookEntryDraft(keys = listOf("b"), content = "B"),
                    WorldBookEntryDraft(keys = listOf("c"), content = "C"),
                )
            )
        )

        val stored = entries.rows.values.filter { it.characterId == id }.sortedBy { it.orderIndex }
        assertEquals(listOf(0, 1, 2), stored.map { it.orderIndex })
        assertEquals(listOf("A", "B", "C"), stored.map { it.content })
    }

    @Test
    fun `滤掉残条之后重新编号，order_index 不留空洞`() = runTest {
        val id = repo.save(
            draft(
                entries = listOf(
                    WorldBookEntryDraft(keys = listOf("a"), content = "A"),
                    // 触发词和内容都空 = 用户新建了一条还没填
                    WorldBookEntryDraft(),
                    WorldBookEntryDraft(keys = listOf("c"), content = "C"),
                )
            )
        )

        val stored = entries.rows.values.filter { it.characterId == id }.sortedBy { it.orderIndex }
        // 用 mapIndexedNotNull 直接编号的话这里会是 0 和 2
        assertEquals(listOf(0, 1), stored.map { it.orderIndex })
        assertEquals(listOf("A", "C"), stored.map { it.content })
    }

    @Test
    fun `触发词两端空白被裁掉，空串被滤掉`() = runTest {
        val id = repo.save(
            draft(entries = listOf(WorldBookEntryDraft(keys = listOf(" 老王 ", "", "  "), content = "内容")))
        )
        val row = entries.rows.values.single { it.characterId == id }
        assertEquals(listOf("老王"), repo.entriesFor(id).single().keys)
        assertEquals("[\"老王\"]", row.keysJson)
    }

    @Test
    fun `再次保存时旧词条被整体替换`() = runTest {
        val id = repo.save(
            draft(entries = listOf(WorldBookEntryDraft(keys = listOf("a"), content = "A")))
        )
        assertEquals(1, entries.rows.size)

        repo.save(draft(id = id, entries = listOf(WorldBookEntryDraft(keys = listOf("b"), content = "B"))))

        // 旧的那条必须没了 —— 逐条 diff 的话，改一下触发词再改回来
        // 会被判成「没变」，而中间那次顺序变化是用户拖出来的、要保留
        assertEquals(1, entries.rows.size)
        assertEquals(listOf("B"), repo.entriesFor(id).map { it.content })
    }

    @Test
    fun `不带词条保存会清空该角色的全部词条`() = runTest {
        val id = repo.save(
            draft(entries = listOf(WorldBookEntryDraft(keys = listOf("a"), content = "A")))
        )
        repo.save(draft(id = id, entries = emptyList()))
        assertTrue(repo.entriesFor(id).isEmpty())
    }

    @Test
    fun `读出来的词条已按 order_index 排好`() = runTest {
        val id = repo.save(
            draft(
                entries = listOf(
                    WorldBookEntryDraft(keys = listOf("1"), content = "第一"),
                    WorldBookEntryDraft(keys = listOf("2"), content = "第二"),
                )
            )
        )
        assertEquals(listOf("第一", "第二"), repo.entriesFor(id).map { it.content })
    }

    @Test
    fun `触发词以 List 形态读出，不是 JSON 字符串`() = runTest {
        val id = repo.save(
            draft(entries = listOf(WorldBookEntryDraft(keys = listOf("老王", "王叔"), content = "C")))
        )
        assertEquals(listOf("老王", "王叔"), repo.entriesFor(id).single().keys)
    }

    @Test
    fun `坏掉的 keys_json 不抛异常，返回空触发词`() = runTest {
        entries.rows["broken"] = WorldBookEntryEntity(
            id = "broken",
            characterId = "c1",
            keysJson = "{这不是 JSON",
            content = "内容",
            orderIndex = 0,
        )
        // 一份写坏的 JSON 最坏只该让「这一条世界书不生效」，
        // 而不是让整个会话发不出消息
        assertTrue(repo.entriesFor("c1").single().keys.isEmpty())
    }

    @Test
    fun `删角色会同时删掉它的词条`() = runTest {
        val id = repo.save(
            draft(entries = listOf(WorldBookEntryDraft(keys = listOf("a"), content = "A")))
        )
        repo.delete(id)
        assertTrue(characters.rows.isEmpty())
        assertTrue(entries.rows.isEmpty())
    }

    @Test
    fun `删角色只清空会话的引用，不删会话本身`() = runTest {
        val id = repo.save(draft())
        conversations.rows["conv-1"] = ConversationEntity(
            id = "conv-1",
            title = "一次聊天",
            createdAt = 1L,
            updatedAt = 1L,
            characterId = id,
        )

        repo.delete(id)

        // 用户删了一张角色卡，不该连带着把用它的聊天记录一起带走
        val conv = conversations.rows.getValue("conv-1")
        assertNull(conv.characterId)
        assertEquals("一次聊天", conv.title)
    }

    @Test
    fun `清空会话引用时不碰 updated_at`() = runTest {
        val id = repo.save(draft())
        conversations.rows["conv-1"] = ConversationEntity(
            id = "conv-1",
            title = "t",
            createdAt = 1L,
            updatedAt = 555L,
            characterId = id,
        )

        repo.delete(id)

        // 换角色/删角色都不是「说了一句话」，改 updated_at 会让
        // 会话列表按「最后活跃时间」排序的结果莫名其妙地变
        assertEquals(555L, conversations.rows.getValue("conv-1").updatedAt)
    }

    @Test
    fun `列表按创建时间倒序`() = runTest {
        now = 1L
        repo.save(draft(name = "早"))
        now = 2L
        repo.save(draft(name = "晚"))

        assertEquals(listOf("晚", "早"), repo.list().map { it.name })
    }

    @Test
    fun `新建时 description 与 persona 可以为空`() = runTest {
        // 用户先建个壳、人设回头再写，这条路必须走得通
        val id = repo.save(draft(description = "", persona = ""))
        assertEquals("", characters.rows.getValue(id).persona)
    }
}
