package com.aichat.ui.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [permissionLines] / [emptyToolsMessage] 的测试。
 *
 * 守的只有一件事：**清单解析不了的时候，不许说「没有」**。
 *
 * 这两句话原来分别是「没有申请任何权限」和「这个插件没有提供工具」——
 * 两句都是反话（真相是「看不出」），而且都在用户判断「要不要继续用它」
 * 的那一刻出现在屏幕上。它们没有别的触发方式：权限那边
 * [com.aichat.plugin.manifest.describe] 永远不返回空列表，所以空列表
 * 只可能来自 `manifest == null`。
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
}
