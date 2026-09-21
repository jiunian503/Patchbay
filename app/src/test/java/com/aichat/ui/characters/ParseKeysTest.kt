package com.aichat.ui.characters

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [parseKeys] 的分隔符处理。
 *
 * 这个函数看着简单，但它错掉的方式很隐蔽：**只认其中一种分隔符**的话，
 * 另外两种会被当成**一个超长的触发词**，于是它永远匹配不上 ——
 * 而界面上看不出任何异常，用户只会觉得「世界书不生效」。
 */
class ParseKeysTest {

    @Test
    fun `中文顿号分隔`() {
        assertEquals(listOf("老王", "王叔"), parseKeys("老王、王叔"))
    }

    @Test
    fun `中英文逗号都能用`() {
        assertEquals(listOf("老王", "王叔"), parseKeys("老王,王叔"))
        assertEquals(listOf("老王", "王叔"), parseKeys("老王，王叔"))
    }

    @Test
    fun `换行也算分隔符`() {
        // 从别处粘一整列触发词时最常见的形式
        assertEquals(listOf("北京", "上海"), parseKeys("北京\n上海"))
    }

    @Test
    fun `两端空白被裁掉，空段丢掉`() {
        assertEquals(listOf("老王", "王叔"), parseKeys("  老王 、 、 王叔  "))
    }

    @Test
    fun `全是空白或分隔符时返回空列表`() {
        assertEquals(emptyList<String>(), parseKeys(""))
        assertEquals(emptyList<String>(), parseKeys("   "))
        assertEquals(emptyList<String>(), parseKeys("、、,"))
    }

    @Test
    fun `不拆开词内部的空格`() {
        // 「New York」是一个触发词，不是两个。空格**不是**分隔符 ——
        // 英文地名、带空格的人名都很常见，拆开之后两个词都会到处误命中
        assertEquals(listOf("New York"), parseKeys("New York"))
    }

    @Test
    fun `单个触发词原样返回`() {
        assertEquals(listOf("北京"), parseKeys("北京"))
    }
}
