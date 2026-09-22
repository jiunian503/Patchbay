package com.aichat.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [errorDetail] 的测试。
 *
 * 三条断言钉住的是同一件事：**给用户/模型的兜底说明里不许出现
 * `null`、空串、或英文类名** —— 这三种都实测出现过（见函数 KDoc）。
 */
class ErrorTextTest {

    @Test
    fun `有 message 就用 message`() {
        assertEquals("Connection reset", errorDetail(java.io.IOException("Connection reset")))
    }

    @Test
    fun `匿名类连 message 都没有时不会吐 null`() {
        // ⚠️ 必须是**匿名对象**：KClass.simpleName 只对匿名类和局部类返回 null，
        // 具名类哪怕不给 message，simpleName 也有值 —— 用 RuntimeException() 测
        // 不出这个坑。这是 JVM 上的行为。
        val anonymous: Throwable = object : RuntimeException() {}
        assertEquals("未知错误", errorDetail(anonymous))
    }

    @Test
    fun `具名类没给 message 也不会把类名摆出来`() {
        // 类名（IllegalStateException）该进日志，不该进给用户看的那句话
        val named = IllegalStateException()
        assertEquals("未知错误", errorDetail(named))
        assertFalse("不许出现类名：${errorDetail(named)}", errorDetail(named).contains("IllegalState"))
    }

    @Test
    fun `只有空白的 message 也算没有`() {
        // 空白 message 会让提示变成「导入失败：   。」，看起来像排版坏了
        assertEquals("未知错误", errorDetail(RuntimeException("   ")))
        assertEquals("未知错误", errorDetail(RuntimeException("")))
    }
}
