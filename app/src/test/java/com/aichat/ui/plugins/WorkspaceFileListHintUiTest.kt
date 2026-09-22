package com.aichat.ui.plugins

import com.aichat.plugin.workspace.PluginWorkspace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区文件列表被截断时那句提示。
 *
 * ## 为什么值得一条测试
 *
 * 原来那句是「还有 N 个文件没有列出来（**工作区最多 256 个**）」。两个数都是真的，
 * 但读者会推出「**是 256 这个上限导致只显示 30 个**」—— 而真正的原因是显示上限 30。
 * 工作区上限是「能**存**多少」，显示上限是「能**列**多少」，**两件事**。
 *
 * 这是 §111 那一类里最典型的一种：**一句话里每个数字都对，合起来把人引到错的结论上**。
 * 所以这里有一条专门断言「不许出现工作区上限」的用例。
 */
class WorkspaceFileListHintUiTest {

    @Test
    fun `说了显示上限是多少`() {
        val text = workspaceMoreFilesHint(hidden = 70)

        assertTrue("得报出显示上限：\n$text", text.contains("30"))
        // 常量本身也钉一下：改了显示上限就该有人回来重读这句话
        assertEquals("显示上限改了，这句话得跟着重读", 30, MAX_VISIBLE_FILES)
    }

    /**
     * 得说清被藏起来的是**哪些**。
     *
     * 列表按路径排序取前 30 —— 不说的话用户没法预测哪几个在、哪几个不在，
     * 只能一个个对着数（§111.2 的第二件事）。
     */
    @Test
    fun `说清了被藏起来的是哪些`() {
        val text = workspaceMoreFilesHint(hidden = 70)

        assertTrue("得说排序规则，否则用户无法预测哪几个看不见：\n$text", text.contains("按名称排序"))
    }

    @Test
    fun `最要紧的是「没有删掉」`() {
        val text = workspaceMoreFilesHint(hidden = 70)

        assertTrue(
            "列表到此为止最容易被读成「工作区里就这些」—— 少了这半句，用户会以为丢了：\n$text",
            text.contains("没有删掉"),
        )
    }

    @Test
    fun `给了这一页就能做的动作`() {
        val text = workspaceMoreFilesHint(hidden = 70)

        assertTrue("只说限制不给动作，等于把问题丢回给用户（§111.2）：\n$text", text.contains("删掉"))
    }

    /**
     * ⚠️ 这条是这个文件的**主要理由**。
     *
     * `PluginWorkspace.MAX_ENTRIES`（256）是「工作区能存多少」，
     * `MAX_VISIBLE_FILES`（30）是「这一屏能列多少」。写进同一句话里，
     * 读者会把后者当前者 —— 这正是原来那句的毛病。
     *
     * `hidden` 取 70 而不是 256：免得「还有 256 个」把那个数字合法地带进来，
     * 让这条判据变成假红。
     */
    @Test
    fun `不把「工作区能存多少」当成「这里能显示多少」`() {
        val text = workspaceMoreFilesHint(hidden = 70)

        assertFalse("这句话里不该出现「工作区最多」——那是能存的上限，不是能显示的上限：\n$text",
            text.contains("工作区最多"))
        assertFalse(
            "更不该出现 ${PluginWorkspace.MAX_ENTRIES} —— 它会让读者以为是这个数在截断列表：\n$text",
            text.contains(PluginWorkspace.MAX_ENTRIES.toString()),
        )
    }

    @Test
    fun `文案里没有 markdown 记号`() {
        val text = workspaceMoreFilesHint(hidden = 70)

        assertFalse("Compose 的 Text 不认 markdown，星号会原样显示：\n$text", text.contains("**"))
    }

    @Test
    fun `详情页真的把它显示出来了`() {
        val screen = File(
            sourceRoot(),
            "app/src/main/java/com/aichat/ui/plugins/PluginDetailScreen.kt",
        )
        assertTrue("找不到 ${screen.path}", screen.isFile)

        val code = screen.readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .joinToString("\n")

        assertTrue(
            "PluginDetailScreen 里没有调用 workspaceMoreFilesHint() —— 这句话用户看不到。\n" +
                "抽成纯函数只解决了「能测」，可见性得靠这一条守（§111.7）。",
            code.contains("workspaceMoreFilesHint("),
        )
    }

    private fun sourceRoot(): File {
        val raw = System.getProperty("patchbay.sourceRoot")
        assertNotNull(
            "构建配置没把 patchbay.sourceRoot 传给测试 JVM —— 检查 app/build.gradle.kts 的 testOptions",
            raw,
        )
        return File(raw!!)
    }
}
