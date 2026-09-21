package com.aichat.ui.characters

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [parseGreetings] 的分隔符处理。
 *
 * ## 和 [parseKeys] 的关键区别：**不认逗号顿号**
 *
 * 触发词是短标签（「老王」「王叔」），里面不可能有标点；开场白是**一整句话**，
 * 逗号顿号在里面是常态。照 [parseKeys] 的规则切的话，一句开场白会被切成三条，
 * 而用户看到的是「我明明只写了一句」—— 界面上完全看不出发生了什么。
 *
 * ## 换行是唯一的分隔符
 *
 * 这一条同时决定了编辑页上那句「一行一条」的说明文字：它是**契约**，
 * 不是排版建议。
 */
class ParseGreetingsTest {

    @Test
    fun `按换行切`() {
        assertEquals(listOf("早", "晚安"), parseGreetings("早\n晚安"))
    }

    @Test
    fun `空行丢掉`() {
        // 按两次回车留个空行是排版习惯，不该变成一条空开场白
        assertEquals(listOf("早", "晚安"), parseGreetings("早\n\n\n晚安\n"))
    }

    @Test
    fun `两端空白裁掉`() {
        assertEquals(listOf("早", "晚安"), parseGreetings("  早  \n  晚安  "))
    }

    @Test
    fun `一句里的逗号顿号不是分隔符`() {
        // 这一条就是它和 `parseKeys` 的区别所在 —— 照 `parseKeys` 切的话
        // 这里会变成 ["你来啦", "我等好久了。"]
        assertEquals(listOf("你来啦，我等好久了。"), parseGreetings("你来啦，我等好久了。"))
        assertEquals(listOf("老王、王叔都来了。"), parseGreetings("老王、王叔都来了。"))
    }

    @Test
    fun `从别处粘来的回车换行也能处理`() {
        // Windows 换行是 \r\n，`split('\n')` 会在每行末尾留下一个 \r ——
        // `trim()` 顺手把它去掉了。粘一份带 \r\n 的文本是很常见的操作
        assertEquals(listOf("早", "晚安"), parseGreetings("早\r\n晚安\r\n"))
    }

    @Test
    fun `空白输入得到空列表`() {
        assertEquals(emptyList<String>(), parseGreetings(""))
        assertEquals(emptyList<String>(), parseGreetings("   "))
        assertEquals(emptyList<String>(), parseGreetings("\n\n"))
    }
}
