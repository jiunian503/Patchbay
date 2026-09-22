package com.aichat.plugin.runtime

import com.aichat.plugin.manifest.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认策略的**唯一一处**规则。
 *
 * ## 为什么这些用例值钱
 *
 * 这条规则有四个消费者：声明式工具、MCP 工具、脚本工具、插件详情页的「调用前会问你」。
 * 前三个写错的话工具跑不起来，很快会被发现；**详情页写错没人会发现** ——
 * 界面会安静地显示一个和实际相反的策略。实测就是这么错的（详情页自己抄了一遍
 * `spec.requiresConfirmation == true`）。
 *
 * ⚠️ 脚本工具是**后来才收进来的**：它原来自己写了一遍 `spec.requiresConfirmation ?: true`，
 * 漏了「声明了任意主机就一律确认」，于是 `network: ["*"]` 的脚本插件可以不问用户
 * 就把请求发去任意主机。`ScriptToolTest` 里有那条组合的用例。
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

    // ── 脚本 ─────────────────────────────────────────────────────────────────

    @Test
    fun `脚本形态没写时默认确认`() {
        // 和声明式**相反**：那边没写时按 HTTP 方法判断，GET 免确认。
        // 脚本形态宿主看不到请求，没有「安全方法」这个依据可用
        assertTrue(ToolConfirmation.script(anyHost = false, declared = null))
    }

    @Test
    fun `脚本形态作者担保时免确认`() {
        assertFalse(ToolConfirmation.script(anyHost = false, declared = false))
        assertTrue(ToolConfirmation.script(anyHost = false, declared = true))
    }

    @Test
    fun `脚本这边任意主机也压过作者的担保`() {
        // 少了这一条，`network: ["*"]` 的脚本插件只要作者写 `false`，
        // 就能让模型自己决定请求发去哪而完全不用问用户
        listOf<Boolean?>(null, true, false).forEach { declared ->
            assertTrue(
                "declared=$declared 时 anyHost 也该压过它",
                ToolConfirmation.script(anyHost = true, declared = declared),
            )
        }
    }

    // ── 给用户看的那句话 ─────────────────────────────────────────────────────

    @Test
    fun `「任意主机」那句理由只有一份`() {
        // 这句原来在 DeclarativeTool 和 McpTool 里**各写了一遍**（逐字相同），
        // 中间没有任何东西把两处拴在一起 —— 改一处漏一处不会有东西变红，
        // 而它是弹框里唯一解释「为什么拦你」的字。
        //
        // 现在两处都引用这个常量，漂移在语法上不可能；所以这里钉的是**内容**：
        // 谁想改措辞，必须同时看到这条测试，而不是只改一个渲染点。
        assertEquals(
            "这个插件声明可以访问任意主机，所以每次都要你确认",
            ToolConfirmation.ANY_HOST_REASON,
        )
    }
}
