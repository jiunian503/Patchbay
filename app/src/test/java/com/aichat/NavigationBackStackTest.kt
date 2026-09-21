package com.aichat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回栈的出栈判据。
 *
 * ## 这里钉住的是一件「崩在别处」的事
 *
 * 判据写错的后果不在这个函数里 —— `NavDisplay(backStack = …)` 的第一行是
 * `require(backStack.isNotEmpty())`，所以栈一旦被弹空，**下一帧重组时**就会抛
 * `IllegalArgumentException: NavDisplay backstack cannot be empty`，
 * 而堆栈上是 `Recomposer.performRecompose` → `NavDisplay`，
 * **完全看不出是谁弹的**。线上撞到过一次（v1.2，从设备上的崩溃记录里还原出来的）。
 *
 * 所以这里不测「返回好不好用」（那得跑起来才知道），只测**这个不变量**：
 * 无论怎么弹，栈里至少留一项。
 */
class NavigationBackStackTest {

    @Test
    fun `栈里只剩一项时什么都不做`() {
        val backStack = mutableListOf<Any>("首页")

        popBackStack(backStack)

        assertEquals(listOf<Any>("首页"), backStack)
    }

    @Test
    fun `栈里有两项时弹掉最后一项`() {
        val backStack = mutableListOf<Any>("首页", "设置")

        popBackStack(backStack)

        assertEquals(listOf<Any>("首页"), backStack)
    }

    @Test
    fun `连着弹到底也不会把栈弹空`() {
        val backStack = mutableListOf<Any>("首页", "设置", "角色")

        // 弹的次数比栈深多 —— 模拟「一下接一下点返回箭头」
        repeat(10) { popBackStack(backStack) }

        assertEquals(listOf<Any>("首页"), backStack)
    }

    @Test
    fun `空栈也不抛（防御性，正常路径不该出现）`() {
        val backStack = mutableListOf<Any>()

        popBackStack(backStack)

        assertTrue(backStack.isEmpty())
    }

    @Test
    fun `判据是 size 大于 1，不是「非空」`() {
        assertFalse(canPopBackStack(0))
        assertFalse("栈深 1 时那是首页，该让系统结束 Activity，而不是把栈弹空", canPopBackStack(1))
        assertTrue(canPopBackStack(2))
    }
}
