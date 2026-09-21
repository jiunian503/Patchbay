package com.aichat.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ChatConfig.systemMessage] —— 两段提示词怎么合成一条 system 消息。
 *
 * 这个函数是「角色卡」这个功能对老会话**唯一的风险点**：它替换了原来
 * 直接取 `systemPrompt` 的那一行。所以下面第一组用例钉的就是
 * 「没选角色时行为逐字节不变」。
 */
class ChatConfigSystemMessageTest {

    @Test
    fun `两段都为空时不发系统消息`() {
        assertNull(ChatConfig(model = "m").systemMessage())
        assertNull(ChatConfig(model = "m", systemPrompt = "   ").systemMessage())
        assertNull(ChatConfig(model = "m", injectedPrompt = "\n").systemMessage())
    }

    @Test
    fun `只有服务商附加提示词时，结果就是它`() {
        // 「没选角色的老会话」那条路径。加这个能力之前，引擎直接发
        // systemPrompt；现在经过一层拼接，结果必须一模一样 ——
        // 多一个换行都算回归
        assertEquals(
            "请用中文回答。",
            ChatConfig(model = "m", systemPrompt = "请用中文回答。").systemMessage(),
        )
    }

    @Test
    fun `只有角色注入时，结果就是它`() {
        assertEquals(
            "你是一位诗人。",
            ChatConfig(model = "m", injectedPrompt = "你是一位诗人。").systemMessage(),
        )
    }

    @Test
    fun `角色注入在前，服务商附加在后`() {
        assertEquals(
            "人设\n\n附加",
            ChatConfig(model = "m", systemPrompt = "附加", injectedPrompt = "人设").systemMessage(),
        )
    }

    @Test
    fun `两端空白被裁掉，中间留一个空行`() {
        assertEquals(
            "人设\n\n附加",
            ChatConfig(model = "m", systemPrompt = " 附加 ", injectedPrompt = "\n人设\n").systemMessage(),
        )
    }

    @Test
    fun `空白的那一段不会留下多余空行`() {
        assertEquals(
            "人设",
            ChatConfig(model = "m", systemPrompt = "   ", injectedPrompt = "人设").systemMessage(),
        )
        assertEquals(
            "附加",
            ChatConfig(model = "m", systemPrompt = "附加", injectedPrompt = "").systemMessage(),
        )
    }
}
