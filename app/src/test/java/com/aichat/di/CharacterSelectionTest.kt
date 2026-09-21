package com.aichat.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「新建对话 → 先选角色 → 再说第一句话」这条路径上的判断逻辑。
 *
 * 这里全是纯函数用例，因为真正的失败方式**不报错**：会话行还不存在时
 * 那次 `UPDATE` 影响 0 行，用户看到的是「人设选了但不生效」。
 * 没有测试的话，这种错会一直安静地待着。
 */
class CharacterSelectionTest {

    private val conv = "conv-1"

    @Test
    fun `会话行还没建、也没有待定值时返回 null`() {
        val choice = resolveCharacterChoice(
            conversationId = conv,
            rowExists = false,
            rowCharacterId = null,
            pendingConversationId = null,
            pendingCharacterId = null,
        )
        assertNull(choice.characterId)
        assertFalse(choice.promote)
        assertFalse(choice.clearPending)
    }

    @Test
    fun `会话行还没建、但有待定值时先用待定值`() {
        val choice = resolveCharacterChoice(
            conversationId = conv,
            rowExists = false,
            rowCharacterId = null,
            pendingConversationId = conv,
            pendingCharacterId = "ch-poet",
        )
        assertEquals("ch-poet", choice.characterId)
        // 行还不存在，没地方提升 —— 这次不能写库
        assertFalse(choice.promote)
        assertFalse(choice.clearPending)
    }

    @Test
    fun `待定值属于别的会话时不能被误用`() {
        val choice = resolveCharacterChoice(
            conversationId = conv,
            rowExists = false,
            rowCharacterId = null,
            pendingConversationId = "conv-2",
            pendingCharacterId = "ch-poet",
        )
        assertNull("别的会话的待定角色不该落到这个会话上", choice.characterId)
    }

    @Test
    fun `会话行刚建出来、待定值还没落库时要提升进库`() {
        // 这正是主路径：选角色（行不存在）→ 发第一条消息（行被建出来，character_id 是 NULL）
        val choice = resolveCharacterChoice(
            conversationId = conv,
            rowExists = true,
            rowCharacterId = null,
            pendingConversationId = conv,
            pendingCharacterId = "ch-poet",
        )
        assertEquals("ch-poet", choice.characterId)
        assertTrue("必须写进会话行，否则重启后选择器会显示「不用角色」", choice.promote)
        assertTrue("提升完就该清掉，不能留着污染下一轮", choice.clearPending)
    }

    @Test
    fun `行上已经有值时以待定值为准会丢掉用户后来的选择`() {
        // 行上有值 + 待定值也在 ⇒ 待定值是上一轮遗留的过期数据，以行为准
        val choice = resolveCharacterChoice(
            conversationId = conv,
            rowExists = true,
            rowCharacterId = "ch-new",
            pendingConversationId = conv,
            pendingCharacterId = "ch-old",
        )
        assertEquals("ch-new", choice.characterId)
        assertFalse(choice.promote)
        assertTrue("过期待定值要清掉", choice.clearPending)
    }

    @Test
    fun `行在且没有待定值时用行上的值`() {
        val choice = resolveCharacterChoice(
            conversationId = conv,
            rowExists = true,
            rowCharacterId = "ch-poet",
            pendingConversationId = null,
            pendingCharacterId = null,
        )
        assertEquals("ch-poet", choice.characterId)
        assertFalse(choice.promote)
        assertFalse(choice.clearPending)
    }

    @Test
    fun `行在且没选角色时返回 null`() {
        val choice = resolveCharacterChoice(
            conversationId = conv,
            rowExists = true,
            rowCharacterId = null,
            pendingConversationId = null,
            pendingCharacterId = null,
        )
        assertNull(choice.characterId)
        assertFalse(choice.promote)
        assertFalse(choice.clearPending)
    }

    @Test
    fun `待定的角色 id 为空时不算待定值`() {
        // 用户在会话行建出来之前点了「不用角色」——
        // 那没什么可记的，更不该把别的会话的角色算进来
        val choice = resolveCharacterChoice(
            conversationId = conv,
            rowExists = false,
            rowCharacterId = null,
            pendingConversationId = conv,
            pendingCharacterId = null,
        )
        assertNull(choice.characterId)
        assertFalse(choice.promote)
    }

    @Test
    fun `会话 id 对得上但行在且值也是同一个时不重复提升`() {
        // 提升过一次之后不该再提升（幂等）—— 待定值此时已经被清掉了，
        // 所以这里模拟的是「清干净之后」的状态
        val choice = resolveCharacterChoice(
            conversationId = conv,
            rowExists = true,
            rowCharacterId = "ch-poet",
            pendingConversationId = null,
            pendingCharacterId = null,
        )
        assertEquals("ch-poet", choice.characterId)
        assertFalse(choice.promote)
    }
}
