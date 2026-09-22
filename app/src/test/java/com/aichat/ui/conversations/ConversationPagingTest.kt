package com.aichat.ui.conversations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「多要一条」那个手法本身。
 *
 * ## 为什么值得单独一条测试
 *
 * 它有两个**都会静默出错**的地方：
 *
 * 1. **漏了 `take`** ⇒ 探针那一条会被显示出去 —— 列表多出一条本不该有的；
 * 2. **判据写成 `>=`** ⇒ 刚好顶到上限时会谎报「还有更多」，
 *    那是方向相反的同一种假话。
 *
 * 而两处调用点（侧边栏、搜索结果）都靠它 ⇒ **这里错一次就是错两处**。
 */
class ConversationPagingTest {

    @Test
    fun `没到上限时全给 也不说还有更多`() {
        val page = pageOf(listOf("a", "b"), limit = 5)

        assertEquals(listOf("a", "b"), page.items)
        assertFalse(page.truncated)
    }

    @Test
    fun `刚好等于上限时不算截断`() {
        val page = pageOf(listOf("a", "b", "c"), limit = 3)

        assertEquals(3, page.items.size)
        assertFalse("拿到 3 条说明到顶了，报「还有更多」是假话", page.truncated)
    }

    @Test
    fun `超过上限时只显示前 limit 条 并说还有更多`() {
        val page = pageOf(listOf("a", "b", "c", "d"), limit = 3)

        assertEquals("探针那一条是用来问的，不能显示出去", listOf("a", "b", "c"), page.items)
        assertTrue(page.truncated)
    }
}
