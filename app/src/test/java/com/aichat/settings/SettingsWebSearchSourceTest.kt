package com.aichat.settings

import com.aichat.tools.WebSearchBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索密钥的别名。
 *
 * 这个文件钉的是一条**只在真机上才撞得到、单测原本完全没覆盖**的 bug：
 * 密钥别名原来是一个固定的 `web_search_api_key`，于是「配了 Brave 的 token
 * 再切到 Tavily」时，设置页读到的仍然是 Brave 那把 →
 * 显示「已保存一个密钥」→ 校验放行 → 搜索拿着 **Brave 的 token** 去请求 Tavily → **401**。
 * 而用户看到的是「搜索失败」，完全指不到密钥头上。
 *
 * 修法就是让别名带上后端，所以这里最重要的那条断言是
 * **「不同后端的别名必须不同」** —— 它一旦不成立，bug 原样复现。
 */
class SettingsWebSearchSourceTest {

    @Test
    fun `不同后端的别名两两不同`() {
        val aliases = WebSearchBackend.entries.map { SettingsWebSearchSource.alias(it) }

        assertEquals(
            "有两个后端算出了同一个别名 —— 它们的密钥会互相覆盖，" +
                "而且切换时界面会显示「已保存一个密钥」（那一把是别的后端的）",
            aliases.size,
            aliases.toSet().size,
        )
    }

    @Test
    fun `别名里带着后端的 id`() {
        // 出问题时要能在设备上（或日志里）一眼看出这一把是谁的
        WebSearchBackend.entries.forEach { backend ->
            assertTrue(
                "${backend.name} 的别名 ${SettingsWebSearchSource.alias(backend)} 里看不到它的 id",
                SettingsWebSearchSource.alias(backend).contains(backend.id),
            )
        }
    }

    @Test
    fun `同一个后端的别名是稳定的`() {
        // 别名一变，用户已经存进去的密钥就「消失」了 —— 读不到，而且不报错
        WebSearchBackend.entries.forEach { backend ->
            assertEquals(
                SettingsWebSearchSource.alias(backend),
                SettingsWebSearchSource.alias(backend),
            )
        }
    }

    @Test
    fun `后端的 id 唯一，而且能原样读回来`() {
        // `fromId` 是「字符串 → 枚举」的唯一入口。两个后端共用一个 id 的话，
        // 存进去的和读出来的会是不同的后端，而这条路径不会报任何错
        val ids = WebSearchBackend.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)

        ids.forEach { id ->
            assertEquals(id, WebSearchBackend.fromId(id)?.id)
        }
    }

    @Test
    fun `别名不再等于那个已经作废的固定值`() {
        // 旧版本用的是一把全局密钥（`web_search_api_key`）。
        // 这条是防回退的：谁把它改回去，这里会红
        WebSearchBackend.entries.forEach { backend ->
            assertNotEquals(
                "web_search_api_key",
                SettingsWebSearchSource.alias(backend),
            )
        }
    }
}
