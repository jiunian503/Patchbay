package com.aichat.ui.settings

import com.aichat.crash.CrashStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「诊断 → 崩溃记录」那一行的正文。
 *
 * ## 为什么值得一条测试
 *
 * 这一行有**三种**情况，而其中一种（`count = 0` 且 `skipped > 0`）只有在
 * 「崩溃目录里有文件、但一份都解析不出来」时才会出现 —— 那是个没人会手动
 * 构造的场景，靠手测永远压不到。而它恰恰是这一行**还留在设置页上**的唯一
 * 理由：省掉它，崩溃记录页上那句「有 N 份没能显示出来」就没有入口了。
 *
 * 每条都**两个方向都断**：既断「说了该说的」，也断「没说不该说的」——
 * 只断前者的话，换成另一句假话照样绿。
 */
class CrashRowBodyTest {

    @Test
    fun `有记录、没跳过时只说有几条`() {
        val text = crashRowBody(
            CrashSummary(count = 3, latestAt = 1_700_000_000_000L, skipped = 0),
        )

        assertTrue("得说清有几条：\n$text", text.contains("有 3 条记录"))
        assertTrue("有记录就该给出时间：\n$text", text.contains("最近一次在"))
        assertFalse("一份都没跳过，就别提这件事：\n$text", text.contains("没能显示出来"))
    }

    @Test
    fun `一份都解析不出来但有文件时不说还没有崩溃记录`() {
        val text = crashRowBody(CrashSummary(count = 0, latestAt = null, skipped = 2))

        assertTrue("得说清是有东西但读不出来：\n$text", text.contains("2 份文件没能显示出来"))
        assertFalse("这时候说「还没有崩溃记录」是反话：\n$text", text.contains("还没有崩溃记录"))
        assertFalse("一份记录都没有，就不该提「最近一次」：\n$text", text.contains("最近一次"))
    }

    @Test
    fun `两种都有时两句话都要说`() {
        val text = crashRowBody(
            CrashSummary(count = 1, latestAt = 1_700_000_000_000L, skipped = 2),
        )

        assertTrue("记录数不能漏：\n$text", text.contains("有 1 条记录"))
        assertTrue("被跳过的份数也不能漏 —— 用户要判断的正是「还有东西」：\n$text",
            text.contains("另有 2 份没能显示出来"))
        assertTrue("有记录时时间照样要给：\n$text", text.contains("最近一次在"))
    }

    /**
     * 「记录只留最近几份」这个上限也必须说出来。
     *
     * 不说的话，「有 5 条记录」会被读成「一共崩过 5 次」—— 而更早的那些
     * **已经被新的挤掉了**（`CrashStore.MAX_FILES`）。崩溃循环时这一点
     * 恰恰是用户想知道的信号。判据见 §111。
     *
     * 数字从常量取、不写字面量：上限改了文案跟着走。
     */
    @Test
    fun `说了记录只留最近几份`() {
        val text = crashRowBody(
            CrashSummary(count = 5, latestAt = 1_700_000_000_000L, skipped = 0),
        )
        val cap = CrashStore.MAX_FILES.toString()

        assertTrue(
            "得说清上限是 $cap 份 —— 否则「有 $cap 条记录」会被读成只崩过 $cap 次：\n$text",
            text.contains(cap),
        )
        assertTrue("得说清更早的那几份会怎样：\n$text", text.contains("挤掉"))
    }
}
