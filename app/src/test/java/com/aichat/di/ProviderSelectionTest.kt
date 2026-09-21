package com.aichat.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [resolveProviderPin] 的判据。
 *
 * 这条 bug 的原始症状（第七十八轮发现）：**新建对话 → 点顶栏换服务商 →
 * 发第一句话**，换的那个被静默丢掉，顶栏弹回默认。
 *
 * 单测和真机 androidTest 都抓不到它 —— 它只在「行还没建就换服务商」这个时序
 * 上出现，而所有既有用例都会先 `conversation(id)` 把行建出来。
 */
class ProviderSelectionTest {

    private fun resolve(
        conversationId: String = "c1",
        rowExists: Boolean,
        rowProviderId: String? = null,
        pendingConversationId: String? = null,
        pendingProviderId: String? = null,
    ) = resolveProviderPin(
        conversationId = conversationId,
        rowExists = rowExists,
        rowProviderId = rowProviderId,
        pendingConversationId = pendingConversationId,
        pendingProviderId = pendingProviderId,
    )

    @Test
    fun `行还没建又没待定值时交给路由解析`() {
        val choice = resolve(rowExists = false)

        assertNull("不该替路由做决定", choice.providerId)
        assertFalse("没有行可写", choice.promote)
        assertFalse("没有待定值可清", choice.clearPending)
    }

    @Test
    fun `行还没建时待定值优先于默认服务商`() {
        val choice = resolve(
            rowExists = false,
            pendingConversationId = "c1",
            pendingProviderId = "p2",
        )

        assertEquals("用户刚选的必须赢过默认值", "p2", choice.providerId)
        assertFalse("行还没有，不能写库", choice.promote)
        assertFalse("还没用上，不能清 —— 清了就真丢了", choice.clearPending)
    }

    @Test
    fun `行还没建时别的会话的待定值不能拿来用`() {
        val choice = resolve(
            conversationId = "c1",
            rowExists = false,
            pendingConversationId = "c9",
            pendingProviderId = "p2",
        )

        assertNull("那是给 c9 选的，不该用在 c1 上", choice.providerId)
        assertFalse("别人的待定值也不能清", choice.clearPending)
    }

    @Test
    fun `行已建好又没有待定值时交给路由解析`() {
        val choice = resolve(rowExists = true, rowProviderId = "p1")

        assertNull("行上有归属，路由自己会读到", choice.providerId)
        assertFalse("没有待定值要落", choice.promote)
        assertFalse("没有待定值要清", choice.clearPending)
    }

    @Test
    fun `行刚建好且行上没归属时把待定值落进去`() {
        val choice = resolve(
            rowExists = true,
            rowProviderId = null,
            pendingConversationId = "c1",
            pendingProviderId = "p2",
        )

        assertEquals("该用待定的那个", "p2", choice.providerId)
        assertTrue("行在、待定值还没落库 —— 必须提升，否则重启就丢", choice.promote)
        assertTrue("提升之后待定值就过期了", choice.clearPending)
    }

    @Test
    fun `行上已经有归属时待定值算过期数据`() {
        val choice = resolve(
            rowExists = true,
            rowProviderId = "p1",
            pendingConversationId = "c1",
            pendingProviderId = "p2",
        )

        assertNull("以行上的为准，不覆盖用户后来做的选择", choice.providerId)
        assertFalse("不能再写一次", choice.promote)
        assertTrue("过期的待定值要清掉，否则下次读又会拿出来用", choice.clearPending)
    }

    @Test
    fun `行已建好时别的会话的待定值不能动`() {
        val choice = resolve(
            conversationId = "c1",
            rowExists = true,
            rowProviderId = "p1",
            pendingConversationId = "c9",
            pendingProviderId = "p2",
        )

        assertNull("不能改 c1 的归属", choice.providerId)
        assertFalse(choice.promote)
        assertFalse("别人的待定值也不能清", choice.clearPending)
    }

    @Test
    fun `行刚建好但待定值属于别的会话时不提升`() {
        val choice = resolve(
            conversationId = "c1",
            rowExists = true,
            rowProviderId = null,
            pendingConversationId = "c9",
            pendingProviderId = "p2",
        )

        assertNull("c1 没被选过，交给路由走默认", choice.providerId)
        assertFalse("不能把给 c9 的选择落到 c1 上", choice.promote)
        assertFalse(choice.clearPending)
    }

    @Test
    fun `只有会话 id 没有服务商 id 时不算待定值`() {
        val choice = resolve(
            rowExists = false,
            pendingConversationId = "c1",
            pendingProviderId = null,
        )

        assertNull("半截的待定值不能生效", choice.providerId)
        assertFalse(choice.promote)
        assertFalse("也不该去清它 —— 那是另一条路径留下的", choice.clearPending)
    }
}
