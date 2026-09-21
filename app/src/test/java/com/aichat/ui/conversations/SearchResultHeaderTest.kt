package com.aichat.ui.conversations

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索结果区顶部那一行。
 *
 * ## 为什么值得一条测试
 *
 * 检索**是有上限的**（一次最多 `ConversationSearch.DEFAULT_LIMIT` 条），
 * 而这一行原来写的是「找到 N 条」—— 匹配 200 条时它是**事实错误**：
 * 用户读到的结论是「库里只有 50 条匹配」，于是不再往下找，也永远不会知道
 * 剩下那些是因为上限没列出来。判据见 §111：**没说的上限会把人引到错结论上。**
 *
 * 这里守三件事：
 *
 * 1. 没截断时说「找到 N 条」；
 * 2. **截断时不许说「找到 N 条」** —— 这是这条测试存在的全部理由；
 * 3. 截断时要给**出路**（把词写具体）—— 检索没有翻页，再搜一次还是前 50 条。
 *
 * 再加一条**可见性**守卫：函数抽出来、测试全绿，但没人调用的话用户照样看不到。
 */
class SearchResultHeaderTest {

    @Test
    fun `没截断时说找到多少条`() {
        val text = searchResultHeader(shown = 7, truncated = false)

        assertTrue("得报出条数：\n$text", text.contains("7"))
        assertFalse("没截断就不该提「只列了一部分」：\n$text", text.contains("只列了前"))
    }

    @Test
    fun `截断时不能说找到多少条`() {
        val text = searchResultHeader(shown = 50, truncated = true)

        assertFalse(
            "说「找到 50 条」是事实错误 —— 库里可能有两百条。这是这条测试的全部理由：\n$text",
            text.startsWith("找到"),
        )
        assertTrue("要说清只列了一部分：\n$text", text.contains("只列了前 50 条"))
    }

    @Test
    fun `截断时给出路 —— 检索没有翻页`() {
        val text = searchResultHeader(shown = 50, truncated = true)

        assertTrue("唯一能做的是换一个更窄的词，得说出来：\n$text", text.contains("关键词"))
    }

    @Test
    fun `搜索结果页真的把它显示出来了`() {
        val screen = File(
            sourceRoot(),
            "app/src/main/java/com/aichat/ui/conversations/ConversationSearchScreen.kt",
        )
        assertTrue("找不到 ${screen.path}", screen.isFile)

        val code = screen.readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .joinToString("\n")

        assertTrue(
            "ConversationSearchScreen 里没有调用 searchResultHeader() —— 这句话用户看不到。\n" +
                "抽成纯函数只解决了「能测」，可见性得靠这一条守（§111）。",
            code.contains("searchResultHeader("),
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
