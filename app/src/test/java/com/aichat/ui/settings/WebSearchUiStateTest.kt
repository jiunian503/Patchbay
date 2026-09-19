package com.aichat.ui.settings

import com.aichat.tools.WebSearchBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 换后端时地址怎么跟着走。
 *
 * 这个文件存在的理由是一条**只在真机上暴露出来的回归**：从「关闭」切到
 * 「Bing（内置）」时，地址框保留了上一个后端留下的残留值，于是用户按提示
 * 选对了「不用配置，装上就能用」的那个后端，搜索照样失败 —— 而失败信息
 * （连不上一个莫名其妙的地址）完全指不出真正的原因。
 *
 * 单测原本覆盖不到它，因为那段逻辑写在 [WebSearchSettingsViewModel.selectBackend]
 * 里，而那个类要一个真的 `AppContainer` 才能构造（Room、KeyStore、一堆
 * Android 依赖）。把状态转换搬到 [WebSearchUiState.afterBackendChange] 之后
 * 就能直接构造和断言了 —— 顺带也让「地址归属」这件事有了一个明确的落点。
 */
class WebSearchUiStateTest {

    /** 真机上看到的那个残留值：上一次折腾 SearXNG 留下的实例地址。 */
    private val leftover = "http://127.0.0.1:8767"

    @Test
    fun `从关闭切到内置后端时用默认地址，而不是留下残留`() {
        // 「关闭」状态下地址字段是没有归属的 —— 它属于上一个被关掉的后端
        val closed = WebSearchUiState(backend = null, endpoint = leftover)

        val next = closed.afterBackendChange(WebSearchBackend.BING_HTML)

        assertEquals(WebSearchBackend.BING_HTML, next.backend)
        assertEquals("https://cn.bing.com/search", next.endpoint)
    }

    @Test
    fun `从别的后端切过来时也用新后端的默认地址`() {
        val searxng = WebSearchUiState(
            backend = WebSearchBackend.SEARXNG,
            endpoint = "https://searx.be",
        )

        val next = searxng.afterBackendChange(WebSearchBackend.BRAVE)

        assertEquals(
            "https://api.search.brave.com/res/v1/web/search",
            next.endpoint,
        )
    }

    @Test
    fun `切到 SearXNG 时地址留空，等用户自己挑实例`() {
        // 没有公共实例可以当默认值，所以这里给空串 —— 界面上就是一个空框，
        // 配合那句「要填一个 SearXNG 实例的地址」的校验提示
        val bing = WebSearchUiState(
            backend = WebSearchBackend.BING_HTML,
            endpoint = "https://cn.bing.com/search",
        )

        val next = bing.afterBackendChange(WebSearchBackend.SEARXNG)

        assertEquals("", next.endpoint)
    }

    @Test
    fun `切到关闭时地址也清空`() {
        val bing = WebSearchUiState(
            backend = WebSearchBackend.BING_HTML,
            endpoint = "https://cn.bing.com/search",
        )

        val next = bing.afterBackendChange(null)

        assertNull(next.backend)
        assertEquals("", next.endpoint)
    }

    @Test
    fun `换后端清掉上一次的测试结果和表单错误`() {
        // 「测试通过：搜到 5 条结果」是上一个后端说的。留着它，用户换到
        // 一个新后端时会以为「这个后端也是通的」—— 而它可能连密钥都没填
        val state = WebSearchUiState(
            backend = WebSearchBackend.TAVILY,
            endpoint = "https://api.tavily.com/search",
            testMessage = "测试通过：搜到 5 条结果",
            formError = "Tavily 需要 API Key。",
        )

        val next = state.afterBackendChange(WebSearchBackend.BING_HTML)

        assertNull(next.testMessage)
        assertNull(next.formError)
    }

    @Test
    fun `换后端时清空密钥输入框和「清除」标记`() {
        // 输入框里的值属于**上一个后端**。留着它，用户切到新后端后直接点保存，
        // 就会把上一个后端的密钥存到新后端名下 —— 两把密钥在服务商那边互不通用，
        // 而搜索报出来的 401 完全指不到这里
        val state = WebSearchUiState(
            backend = WebSearchBackend.BRAVE,
            endpoint = "https://api.search.brave.com/res/v1/web/search",
            apiKeyInput = "BSA-from-brave",
            clearedKey = true,
            keyConfigured = true,
        )

        val next = state.afterBackendChange(WebSearchBackend.TAVILY)

        assertEquals("", next.apiKeyInput)
        assertFalse(next.clearedKey)
        // 真实值要读 KeyStore（挂起），这里先按「没有」算 ——
        // 短暂显示「需要填密钥」会让人再确认一眼，而短暂显示「已保存一个密钥」
        // 会让人直接点保存。两害相权取前者
        assertFalse(next.keyConfigured)
    }
}
