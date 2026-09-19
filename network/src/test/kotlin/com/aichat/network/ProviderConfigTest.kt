package com.aichat.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * baseUrl 规范化。
 *
 * 这段逻辑看着琐碎，但它决定「用户填的地址能不能用」——
 * 填错一个 `/v1` 就是 404，而用户根本不知道自己填错了。
 */
class ProviderConfigTest {

    @Test
    fun `裸域名自动补 v1`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ProviderConfig.chatCompletionsUrl("https://api.openai.com"),
        )
    }

    @Test
    fun `已经带 v1 的不重复补`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ProviderConfig.chatCompletionsUrl("https://api.openai.com/v1"),
        )
    }

    @Test
    fun `带自定义路径前缀的原样拼接`() {
        // 智谱用的是 /api/paas/v4，不能被强行改成 /v1
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            ProviderConfig.chatCompletionsUrl("https://open.bigmodel.cn/api/paas/v4"),
        )
    }

    @Test
    fun `结尾多余斜杠被吃掉`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            ProviderConfig.chatCompletionsUrl("https://api.deepseek.com/"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            ProviderConfig.chatCompletionsUrl("https://api.deepseek.com/v1/"),
        )
    }

    @Test
    fun `不写 scheme 时补 https`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            ProviderConfig.chatCompletionsUrl("api.deepseek.com"),
        )
    }

    @Test
    fun `本地地址也能用`() {
        // 端口不算路径段，所以要补 /v1
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            ProviderConfig.chatCompletionsUrl("http://127.0.0.1:8080"),
        )
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            ProviderConfig.chatCompletionsUrl("http://127.0.0.1:8080/v1"),
        )
    }

    @Test
    fun `前后空白被裁掉`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ProviderConfig.chatCompletionsUrl("  https://api.openai.com  "),
        )
    }

    @Test
    fun `空地址直接报错而不是拼出一个坏 URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderConfig.chatCompletionsUrl("   ")
        }
    }

    @Test
    fun `配置对象暴露规范化后的地址`() {
        val config = ProviderConfig(baseUrl = "api.deepseek.com", apiKey = "sk-x")
        assertEquals("https://api.deepseek.com/v1/chat/completions", config.chatCompletionsUrl)
    }
}
