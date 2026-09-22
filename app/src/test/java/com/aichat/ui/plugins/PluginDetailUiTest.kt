package com.aichat.ui.plugins

import com.aichat.plugin.manifest.HttpMethod
import com.aichat.plugin.manifest.PluginManifest
import com.aichat.plugin.manifest.PluginPermissions
import com.aichat.plugin.manifest.PluginRuntimeKind
import com.aichat.plugin.manifest.RequestSpec
import com.aichat.plugin.manifest.ToolSpec
import com.aichat.plugin.runtime.mcp.McpToolSnapshot
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [permissionLines] / [emptyToolsMessage] / [toolRows] 的测试。
 *
 * 守的是同一件事：**界面上那句关于插件的话，必须是真的。**
 *
 * - 前两个：清单解析不了的时候，不许说「没有」。它们原来分别是
 *   「没有申请任何权限」和「这个插件没有提供工具」—— 两句都是反话
 *   （真相是「看不出」），而且都在用户判断「要不要继续用它」的那一刻
 *   出现在屏幕上。它们没有别的触发方式：权限那边
 *   [com.aichat.plugin.manifest.describe] 永远不返回空列表，所以空列表
 *   只可能来自 `manifest == null`。
 * - [toolRows]：注册表里查不到时，兜底算出来的确认策略必须和**真正生效**
 *   的那条规则一致。原来它自己抄了一遍（`spec.requiresConfirmation == true`），
 *   于是「作者没写 + POST」被显示成「不会打扰你」，声明了任意主机的也一样漏。
 */
class PluginDetailUiTest {

    // ── 权限区 ────────────────────────────────────────────────────────────────

    @Test
    fun `清单解析不了时不说没有申请任何权限`() {
        val lines = permissionLines(broken = true, permissions = emptyList())

        assertEquals("得给一句实话，而不是空列表：\n$lines", 1, lines.size)
        assertTrue("得说清是「看不出」：\n$lines", lines.single().contains("解析不了"))
        assertFalse(
            "这时候说「没有申请任何权限」是反话：\n$lines",
            lines.single().contains("没有申请任何权限"),
        )
    }

    @Test
    fun `清单好着的时候原样返回`() {
        val described = listOf("网络：不访问网络", "文件：只读（限插件工作区）")

        assertEquals(described, permissionLines(broken = false, permissions = described))

        // 空列表也原样返回 —— 「没有申请任何权限」那句由界面自己决定什么时候说，
        // 这里**不许**替它把话说死
        assertTrue(permissionLines(broken = false, permissions = emptyList()).isEmpty())
    }

    // ── 工具区 ────────────────────────────────────────────────────────────────

    @Test
    fun `清单解析不了时不说没有提供工具`() {
        val message = emptyToolsMessage(broken = true, isMcp = false, hasCache = false)

        assertTrue("得说清是「看不出」：$message", message.contains("解析不了"))
        assertFalse("这时候说「没有提供工具」是反话：$message", message.contains("没有提供工具"))
    }

    @Test
    fun `清单解析不了压过 MCP 的两种说法`() {
        // 清单坏掉时 `isMcp` 本来就是 false（运行形态也读不出来），但这条
        // 参数组合万一出现，也不能让「还没拉取过」之类的说法盖过真相
        val never = emptyToolsMessage(true, isMcp = true, hasCache = false)
        val empty = emptyToolsMessage(true, isMcp = true, hasCache = true)

        assertTrue("没拉取过也不能盖过「解析不了」：$never", never.contains("解析不了"))
        assertTrue("拉到了空清单也不能盖过「解析不了」：$empty", empty.contains("解析不了"))
    }

    @Test
    fun `MCP 没拉取过和拉到了空清单要分开说`() {
        val never = emptyToolsMessage(broken = false, isMcp = true, hasCache = false)
        val empty = emptyToolsMessage(broken = false, isMcp = true, hasCache = true)

        // 两句合一句的话，MCP 用户会去卸载重装 —— 而真正该做的是点「连接并刷新」
        assertNotEquals("这两句说的是两件不同的事，不能合成一句：$never", never, empty)
        assertTrue(never, never.contains("还没拉取过"))
        assertTrue(empty, empty.contains("一个工具都没提供"))
    }

    @Test
    fun `声明式插件没有工具时仍然说的是清单里 tools 是空的`() {
        val message = emptyToolsMessage(broken = false, isMcp = false, hasCache = false)

        assertTrue(message, message.contains("没有提供工具"))
        assertFalse("清单没坏，就别提解析不了：$message", message.contains("解析不了"))
    }

    // ── 工具行的确认策略 ──────────────────────────────────────────────────────

    @Test
    fun `注册表里查不到时，兜底和真正生效的规则一致`() {
        // 插件被停用 / 清单坏了 / 还没装配完时注册表里查不到，详情页得自己算一遍。
        // 算错的话界面会安静地显示一个**和实际相反**的策略，而那句文案的注释里
        // 写着「显示实际生效的策略，不是清单里写的那个值」。
        // 实测就是错的：原来写的是 `spec.requiresConfirmation == true`，
        // 于是「作者没写 + POST」（真正会问你）被显示成「不会打扰你」
        val manifest = manifestOf(
            tools = listOf(
                spec("post_lookup", HttpMethod.Post),
                spec("get_lookup", HttpMethod.Get),
            ),
        )

        val rows = toolRows(
            isMcp = false,
            cache = null,
            manifest = manifest,
            registered = { null },
            anyHost = false,
        )

        assertTrue(
            "作者没写 + POST ⇒ 真正会问你：${rows.map { it.name to it.requiresConfirmation }}",
            rows.single { it.name == "post_lookup" }.requiresConfirmation,
        )
        assertFalse(
            "GET 是安全方法 ⇒ 不会打扰你",
            rows.single { it.name == "get_lookup" }.requiresConfirmation,
        )
    }

    @Test
    fun `声明了任意主机时，兜底也要说要确认`() {
        // `network: ["*"]` 意味着「请求发去哪由模型决定」，无论作者怎么写都一律确认。
        // 兜底漏掉这一条的话，用户在决定要不要启用这个插件时看到的是反话
        val manifest = manifestOf(
            network = listOf("*"),
            tools = listOf(spec("get_lookup", HttpMethod.Get)),
        )

        val rows = toolRows(
            isMcp = false,
            cache = null,
            manifest = manifest,
            registered = { null },
            anyHost = true,
        )

        assertTrue(
            "任意主机压过 GET：${rows.single().requiresConfirmation}",
            rows.single().requiresConfirmation,
        )
    }

    @Test
    fun `注册表里查得到时用它的值，不再自己算`() {
        // 装配好的那个 Tool 才是真正拦下调用的东西。两边算法现在是同一份，
        // 但**来源**仍然要认它 —— 否则以后哪天运行时改了规则，这里会各说各的
        val manifest = manifestOf(tools = listOf(spec("post_lookup", HttpMethod.Post)))

        val rows = toolRows(
            isMcp = false,
            cache = null,
            manifest = manifest,
            registered = { true },
            anyHost = false,
        )

        assertTrue(rows.single().requiresConfirmation)
    }

    @Test
    fun `MCP 的兜底看对端的 readOnly，且任意主机压过它`() {
        val cache = listOf(
            snapshot("ro", readOnly = true),
            snapshot("rw", readOnly = false),
        )

        val rows = toolRows(
            isMcp = true,
            cache = cache,
            manifest = null,
            registered = { null },
            anyHost = false,
        )
        assertFalse("对端声明只读 ⇒ 免确认", rows.single { it.name == "ro" }.requiresConfirmation)
        assertTrue("对端没声明只读 ⇒ 要确认", rows.single { it.name == "rw" }.requiresConfirmation)

        val anyHostRows = toolRows(
            isMcp = true,
            cache = cache,
            manifest = null,
            registered = { null },
            anyHost = true,
        )
        assertTrue(
            "任意主机压过只读：${anyHostRows.map { it.name to it.requiresConfirmation }}",
            anyHostRows.all { it.requiresConfirmation },
        )
    }

    @Test
    fun `MCP 没连过时一个工具行都不给，而不是给一行假的`() {
        // `cache == null` 是「还没拉取过」。这时候**没有**工具行 ——
        // 那句话由 `emptyToolsMessage` 说，不该在这里编出内容来
        val rows = toolRows(
            isMcp = true,
            cache = null,
            manifest = null,
            registered = { null },
            anyHost = false,
        )

        assertTrue("没拉取过就没有行：$rows", rows.isEmpty())
    }

    // ── 造数据 ────────────────────────────────────────────────────────────────

    private fun spec(name: String, method: HttpMethod) = ToolSpec(
        name = name,
        description = "$name 的说明",
        parameters = JsonObject(emptyMap()),
        request = RequestSpec(method = method, path = "/$name"),
    )

    private fun manifestOf(
        network: List<String> = emptyList(),
        tools: List<ToolSpec> = emptyList(),
    ) = PluginManifest(
        id = "pub.test.detail",
        name = "测试插件",
        version = "1.0",
        runtime = PluginRuntimeKind.Declarative,
        permissions = PluginPermissions(network = network),
        tools = tools,
    )

    private fun snapshot(name: String, readOnly: Boolean) = McpToolSnapshot(
        name = name,
        description = "$name 的说明",
        inputSchema = JsonObject(emptyMap()),
        readOnly = readOnly,
    )
}
