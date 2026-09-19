package com.aichat.ui.chat

import com.aichat.core.data.ResolvedProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `ResolvedProvider.toChatConfig()` 的字段映射。
 *
 * ## 为什么要专门测一个「只是搬字段」的函数
 *
 * 因为它搬的是**三个入口共用的那份参数**（发送 / 重新生成 / 编辑重发）。
 * 原先三处各写一遍 `ChatConfig(model = provider.model)` —— 加一个字段必然
 * 漏掉其中一两处，而且漏掉之后**不编译错、不报错**，只是「这个入口的参数不生效」。
 *
 * 用户在「重新生成」上发现温度没起作用时，很难联想到是漏搬了一个参数。
 * 这条用例逐个字段比对，就是为了让「加了字段忘了搬」变成一次测试失败。
 */
class ProviderChatConfigTest {

    private fun provider(
        model: String = "deepseek-chat",
        temperature: Double? = null,
        maxTokens: Int? = null,
        systemPrompt: String? = null,
    ) = ResolvedProvider(
        id = "p1",
        name = "深度求索",
        model = model,
        keyHint = "…1234",
        config = null,
        temperature = temperature,
        maxTokens = maxTokens,
        systemPrompt = systemPrompt,
    )

    @Test
    fun `每个字段都被搬过去`() {
        val config = provider(
            model = "qwen-max",
            temperature = 0.7,
            maxTokens = 4096,
            systemPrompt = "你是一个简洁的助手。",
        ).toChatConfig()

        assertEquals("qwen-max", config.model)
        assertEquals(0.7, config.temperature!!, 1e-9)
        assertEquals(4096, config.maxTokens)
        assertEquals("你是一个简洁的助手。", config.systemPrompt)
    }

    @Test
    fun `没填的参数保持 null 不补默认值`() {
        val config = provider().toChatConfig()

        // 补一个「1.0」会悄悄改掉服务端行为（各家默认不同），而用户看不出来
        assertNull(config.temperature)
        assertNull(config.maxTokens)
        assertNull(config.systemPrompt)
    }
}
