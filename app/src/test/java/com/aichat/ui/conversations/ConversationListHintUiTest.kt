package com.aichat.ui.conversations

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 侧边栏列表底部那句「更早的没列出来」。
 *
 * ## 为什么值得一条测试
 *
 * 侧边栏一次最多列 100 个会话、**没有分页**，而原来一个字都没说 ——
 * 第 101 个往后的会话静默消失。用户看到的不是「列表有上限」，
 * 而是「我的会话没了」。**这是这一整轮里最像丢数据的一处假话**（§111）。
 *
 * 所以这句话里最要紧的不是那个数字，是「**没有删掉**」四个字。
 */
class ConversationListHintUiTest {

    @Test
    fun `说清是「没显示」而不是「没了」`() {
        val text = drawerMoreHint(shown = 100)

        assertTrue("得报出上限：\n$text", text.contains("100"))
        assertTrue(
            "最要紧的就是这一句 —— 少了它，用户会以为会话被删了：\n$text",
            text.contains("没有删掉"),
        )
    }

    @Test
    fun `给出路 —— 搜索历史搜的是全部消息`() {
        val text = drawerMoreHint(shown = 100)

        assertTrue("侧边栏没有「加载更多」，唯一的路是搜索历史：\n$text", text.contains("搜索历史"))
    }

    /**
     * 文案里不能带 markdown 记号。
     *
     * `Text` 是**原样渲染**的：写成 `**没有删掉**` 会把星号一起显示给用户。
     * 这条是实测踩出来的 —— 第一版就带着星号（§111 的同一类：写的时候觉得
     * 「这是强调」，用户看到的是两个多余的字）。
     */
    @Test
    fun `文案里没有 markdown 记号`() {
        val text = drawerMoreHint(shown = 100)

        assertFalse("Compose 的 Text 不认 markdown，星号会原样显示：\n$text", text.contains("**"))
    }

    @Test
    fun `抽屉真的把它显示出来了`() {
        val drawer = File(
            sourceRoot(),
            "app/src/main/java/com/aichat/ui/conversations/ConversationDrawer.kt",
        )
        assertTrue("找不到 ${drawer.path}", drawer.isFile)

        val code = drawer.readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .joinToString("\n")

        assertTrue(
            "ConversationDrawer 里没有调用 drawerMoreHint() —— 这句话用户看不到。\n" +
                "抽成纯函数只解决了「能测」，可见性得靠这一条守（§111.7）。",
            code.contains("drawerMoreHint("),
        )
    }

    private fun sourceRoot(): File {
        val raw = System.getProperty("patchbay.sourceRoot")
        assertNotNull(
            "构建配置没把 patchbay.sourceRoot 传给测试 JVM —— 检查 app/build.gradle.kts 的 testOptions",
            raw,
        )
        return File(raw!!)
    }
}
