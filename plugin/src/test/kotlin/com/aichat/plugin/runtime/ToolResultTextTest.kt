package com.aichat.plugin.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「插件工具结果 → 模型」那道闸的边界。
 *
 * ## 为什么单独给这三条
 *
 * 声明式、MCP、脚本三条路都过 [ToolResultText.clip]，但**各自那条路上**很难验边界：
 * 造一个「正好 20 000 字」的响应要绕一圈。而边界恰恰是这里唯一会错的地方 ——
 * `<=` 写成 `<` 就会把一条正好到上限的结果白白截掉。
 *
 * 三条用例咬着同一份实现：改 `MAX_CHARS` 或去掉 `clip`，三条一起红。
 */
class ToolResultTextTest {

    @Test
    fun `没到上限时原样返回`() {
        val text = "x".repeat(10)

        assertEquals(text, ToolResultText.clip(text))
    }

    @Test
    fun `正好等于上限时也原样返回`() {
        // `<=` 写成 `<` 的话这条会红 —— 而「正好到上限」是完全正常的一条结果
        val text = "x".repeat(ToolResultText.MAX_CHARS)

        assertEquals(text, ToolResultText.clip(text))
    }

    @Test
    fun `多一个字就截断，并把原文长度和上限都说出来`() {
        val text = "x".repeat(ToolResultText.MAX_CHARS + 1)

        val clipped = ToolResultText.clip(text)

        assertTrue("要说明白截断了，否则模型会拿前半截当全部", clipped.contains("已截断"))
        assertTrue("要说清原文有多长", clipped.contains("${ToolResultText.MAX_CHARS + 1} 字"))
        assertTrue("要说清留下了多少", clipped.contains("前 ${ToolResultText.MAX_CHARS} 字"))
        assertTrue("正文只保留上限那么长", clipped.startsWith("x".repeat(ToolResultText.MAX_CHARS)))
    }
}
