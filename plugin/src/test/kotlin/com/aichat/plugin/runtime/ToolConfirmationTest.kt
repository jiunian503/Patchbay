package com.aichat.plugin.runtime

import com.aichat.plugin.manifest.HttpMethod
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认策略的**唯一一处**规则。
 *
 * ## 为什么这些用例值钱
 *
 * 这条规则有三个消费者：声明式工具、MCP 工具、插件详情页的「调用前会问你」。
 * 前两个写错的话工具跑不起来，很快会被发现；**详情页写错没人会发现** ——
 * 界面会安静地显示一个和实际相反的策略。实测就是这么错的（详情页自己抄了一遍
 * `spec.requiresConfirmation == true`）。
 *
 * 所以这里把规则**每一条分支**都钉住：改了它，详情页和运行时一起跟着变，
 * 而不是各错各的。
 */
class ToolConfirmationTest {

    // ── 声明式 ────────────────────────────────────────────────────────────────

    @Test
    fun `作者没写时按 HTTP 方法判断：只有 GET 免确认`() {
        // RFC 9110 只把 GET/HEAD 定义为「安全方法」
        assertFalse(
            "GET 是安全方法，不该打扰用户",
            ToolConfirmation.declarative(anyHost = false, declared = null, method = HttpMethod.Get),
        )
        assertTrue(
            "POST 可能改动服务端数据",
            ToolConfirmation.declarative(anyHost = false, declared = null, method = HttpMethod.Post),
        )
        assertTrue(
            "DELETE 更不用说了",
            ToolConfirmation.declarative(anyHost = false, declared = null, method = HttpMethod.Delete),
        )
    }

    @Test
    fun `作者写了就听作者的`() {
        // 三态设计里 `false` 是作者签的字：他担保这个工具不改动服务端数据。
        // 大量只读接口确实用 POST（翻译、搜索、embeddings），一刀切会弹一堆没必要的窗
        assertTrue(
            ToolConfirmation.declarative(anyHost = false, declared = true, method = HttpMethod.Get),
        )
        assertFalse(
            ToolConfirmation.declarative(anyHost = false, declared = false, method = HttpMethod.Post),
        )
    }

    @Test
    fun `声明了任意主机就一律确认，作者写 false 也一样`() {
        // 这条保护的是「**请求发去哪由模型决定**」，不是「这次请求危不危险」——
        // 所以作者那句 `false` 担保不了它。和内置的 `fetch_url` 是同一件事，
        // 而 `fetch_url` 是要确认的
        listOf<Boolean?>(null, true, false).forEach { declared ->
            assertTrue(
                "declared=$declared 时 anyHost 也该压过它",
                ToolConfirmation.declarative(anyHost = true, declared = declared, method = HttpMethod.Get),
            )
        }
    }

    // ── MCP ──────────────────────────────────────────────────────────────────

    @Test
    fun `对端声明只读时免确认`() {
        assertFalse(ToolConfirmation.mcp(anyHost = false, readOnly = true))
    }

    @Test
    fun `对端没声明只读时要确认`() {
        // `readOnlyHint` 是每次连接时从线上下来的，对端随时可以改、用户看不到改动 ——
        // 它比清单里那句 `requiresConfirmation` 弱得多，所以只信它「能免掉」的那一半
        assertTrue(ToolConfirmation.mcp(anyHost = false, readOnly = false))
    }

    @Test
    fun `MCP 这边任意主机也压过只读`() {
        // 少了这一条，`network: ["*"]` 的 MCP 插件只要对端把工具标成只读，
        // 就能让模型自己决定请求发去哪而完全不用问用户
        assertTrue(ToolConfirmation.mcp(anyHost = true, readOnly = true))
    }
}
