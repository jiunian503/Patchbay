package com.aichat.tools

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间工具。
 *
 * 全部用固定时钟，不用 `Instant.now()` —— 否则「星期几对不对」这种断言
 * 会在某一天突然失败，而那是测试的问题不是代码的问题。
 */
class CurrentTimeToolTest {

    // 2026-09-18 是星期五
    private val fixed = Instant.parse("2026-09-18T12:34:56Z")

    private val shanghai = ZoneId.of("Asia/Shanghai")

    private val tool = CurrentTimeTool(clock = { fixed }, systemZone = shanghai)

    @Test
    fun `不传时区时用设备时区`() = runTest {
        val result = tool.execute(buildJsonObject { })

        assertFalse(result.content, result.isError)
        // UTC 12:34 在东八区是 20:34
        assertTrue(result.content, result.content.contains("2026-09-18 20:34:56"))
        assertTrue(result.content, result.content.contains("Asia/Shanghai"))
    }

    @Test
    fun `星期几是算出来的而不是查表`() = runTest {
        val result = tool.execute(buildJsonObject { })
        assertTrue(result.content, result.content.contains("星期五"))
    }

    @Test
    fun `可以指定别的时区`() = runTest {
        val result = tool.execute(buildJsonObject { put(CurrentTimeTool.TIMEZONE, "America/New_York") })

        assertFalse(result.content, result.isError)
        // UTC 12:34 在纽约（夏令时 EDT，UTC-4）是 08:34，且仍是 9 月 18 日
        assertTrue(result.content, result.content.contains("2026-09-18 08:34:56"))
        assertTrue(result.content, result.content.contains("America/New_York"))
    }

    @Test
    fun `跨日期边界的时区会算到正确的那一天`() = runTest {
        // 同一个瞬间，在东京已经是 19 日了
        val result = tool.execute(buildJsonObject { put(CurrentTimeTool.TIMEZONE, "Asia/Tokyo") })
        assertTrue(result.content, result.content.contains("2026-09-18 21:34:56"))

        val later = Instant.parse("2026-09-18T16:00:00Z")
        val tokyoTool = CurrentTimeTool(clock = { later }, systemZone = shanghai)
        val next = tokyoTool.execute(buildJsonObject { put(CurrentTimeTool.TIMEZONE, "Asia/Tokyo") })
        // UTC 16:00 → 东京次日 01:00
        assertTrue(next.content, next.content.contains("2026-09-19 01:00:00"))
    }

    @Test
    fun `带偏移量的时区写法也认`() = runTest {
        val result = tool.execute(buildJsonObject { put(CurrentTimeTool.TIMEZONE, "UTC") })
        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("2026-09-18 12:34:56"))
    }

    /**
     * 时区写错是模型最常见的失误之一（它爱写 `Beijing`、`GMT+8`）。
     * 报错里必须给出正确写法，否则模型只能瞎猜第二次。
     */
    @Test
    fun `非法时区会指出正确格式`() = runTest {
        val result = tool.execute(buildJsonObject { put(CurrentTimeTool.TIMEZONE, "Beijing") })

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("Beijing"))
        assertTrue(result.content, result.content.contains("Asia/Shanghai"))
    }

    @Test
    fun `空字符串时区退回设备时区`() = runTest {
        val result = tool.execute(buildJsonObject { put(CurrentTimeTool.TIMEZONE, "  ") })
        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("Asia/Shanghai"))
    }

    @Test
    fun `输出里有时间戳供模型做算术`() = runTest {
        val result = tool.execute(buildJsonObject { })
        assertTrue(result.content, result.content.contains(fixed.epochSecond.toString()))
    }

    @Test
    fun `工具定义完整`() {
        assertEquals(CurrentTimeTool.NAME, tool.definition.name)
        assertTrue(tool.definition.description.length > 20)
        assertFalse(tool.requiresConfirmation)
    }
}
