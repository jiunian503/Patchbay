package com.aichat.plugin.host

import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolResult
import com.aichat.plugin.Manifests
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置工具 + 插件工具的合成规则。
 *
 * ## 这个类守的是「冒充」这件事
 *
 * 一个叫 `fetch_url` 的插件工具，在模型和用户眼里**就是**那个内置的抓网页工具。
 * 它可以在自己的 description 里写「抓取网页请用我」，然后每次抓取都经过它 ——
 * 而用户看到的确认弹窗上写的是「插件『xx』将向 xxx 发起请求」这种看起来
 * 很正常的话。
 *
 * 所以这几条规则（不遮蔽、不重命名、按 id 排序）每一条都要有用例钉住。
 */
class PluginRegistryTest {

    private val client = OkHttpClient()

    /** 一个名字可预测的假内置工具。 */
    private class FakeTool(override val definition: ToolDefinition) : Tool {
        override suspend fun execute(arguments: JsonObject): ToolResult = ToolResult.ok("fake")
    }

    private fun builtin(name: String) = FakeTool(ToolDefinition(name, "内置的 $name", buildJsonObject {}))

    private fun plugin(
        id: String,
        toolNames: List<String>,
        enabled: Boolean = true,
        network: List<String> = listOf("api.example.com"),
    ) = Manifests.installed(
        Manifests.declarative(id = id, name = "插件 $id", tools = toolNames, network = network),
        enabled = enabled,
    )

    private fun build(
        builtins: List<Tool> = listOf(builtin("fetch_url"), builtin("calculate")),
        plugins: List<InstalledPlugin>,
    ) = PluginRegistry.build(builtins, plugins, client)

    // ---------------------------------------------------------------- 冒充

    @Test
    fun `插件工具不能覆盖内置工具`() {
        val registry = build(plugins = listOf(plugin("pub.a.one", listOf("fetch_url"))))

        assertEquals("被丢弃的工具不该出现", 2, registry.all.size)
        assertEquals("内置工具还是内置那个", "内置的 fetch_url", registry.find("fetch_url")?.definition?.description)
        assertNull("ownerOf 不该把它算成插件的", registry.ownerOf("fetch_url"))
    }

    @Test
    fun `被丢弃的内置重名要记录成冲突`() {
        val registry = build(plugins = listOf(plugin("pub.a.one", listOf("fetch_url", "ok_tool"))))

        assertEquals(1, registry.conflicts.size)
        val conflict = registry.conflicts.single()
        assertEquals("fetch_url", conflict.toolName)
        assertEquals(ToolConflict.Reason.ShadowsBuiltin, conflict.reason)
        assertEquals("pub.a.one", conflict.pluginId)
        // 同一个插件里没重名的那个工具要正常进来
        assertNotNull(registry.find("ok_tool"))
        assertTrue(registry.needsAttention)
    }

    // ---------------------------------------------------------------- 插件之间重名

    @Test
    fun `两个插件重名时按 id 排序决定赢家`() {
        // 刻意把 zzz 放在前面传入，验证结果和传入顺序无关
        val registry = build(
            plugins = listOf(
                plugin("pub.z.zzz", listOf("shared")),
                plugin("pub.a.aaa", listOf("shared")),
            ),
        )

        assertEquals("pub.a.aaa", registry.ownerOf("shared"))
        val conflict = registry.conflicts.single()
        assertEquals(ToolConflict.Reason.Duplicate, conflict.reason)
        assertEquals("pub.z.zzz", conflict.pluginId)
        assertEquals("赢家要写进冲突里，界面上那句『和插件 X 重名』才有主", "pub.a.aaa", conflict.winnerPluginId)
    }

    @Test
    fun `合成结果只由装了哪些插件决定`() {
        // 同一组插件，两种传入顺序，结果必须逐字节一致。
        // 按安装顺序排的话，同一组插件在两台设备上会得到不同的工具列表 ——
        // 那是一种极难复现的 bug，而它本来可以不存在
        val a = plugin("pub.a.aaa", listOf("t1", "t2"))
        val b = plugin("pub.b.bbb", listOf("t3"))

        val one = build(plugins = listOf(a, b)).definitions().map { it.name }
        val two = build(plugins = listOf(b, a)).definitions().map { it.name }

        assertEquals(one, two)
        assertEquals(listOf("fetch_url", "calculate", "t1", "t2", "t3"), one)
    }

    @Test
    fun `内置工具永远排在插件工具前面`() {
        val registry = build(plugins = listOf(plugin("pub.a.aaa", listOf("aaa_tool"))))

        assertEquals(
            listOf("fetch_url", "calculate", "aaa_tool"),
            registry.definitions().map { it.name },
        )
    }

    // ---------------------------------------------------------------- 启停

    @Test
    fun `关掉的插件不进注册表`() {
        val registry = build(plugins = listOf(plugin("pub.a.one", listOf("do_thing"), enabled = false)))

        assertEquals(2, registry.all.size)
        assertNull("关掉的插件连定义都不该出现在发给模型的请求里", registry.find("do_thing"))
        assertFalse(registry.needsAttention)
    }

    @Test
    fun `关掉的插件不参与重名判定`() {
        // 关掉的那个如果还参与，用户会看到一个「和已停用插件重名」的冲突，
        // 而那个冲突是他没法通过「留哪个」来解决的
        val registry = build(
            plugins = listOf(
                plugin("pub.a.aaa", listOf("shared"), enabled = false),
                plugin("pub.b.bbb", listOf("shared")),
            ),
        )

        assertEquals("pub.b.bbb", registry.ownerOf("shared"))
        assertTrue(registry.conflicts.isEmpty())
    }

    // ---------------------------------------------------------------- 其它

    @Test
    fun `没有插件时就是内置工具`() {
        val registry = build(plugins = emptyList())

        assertEquals(2, registry.all.size)
        assertEquals(2, registry.definitions().size)
        assertTrue(registry.conflicts.isEmpty())
        assertTrue(registry.plugins.isEmpty())
    }

    @Test
    fun `同一个插件出现两次只装配一次`() {
        val registry = build(plugins = listOf(plugin("pub.a.one", listOf("t")), plugin("pub.a.one", listOf("t"))))

        assertEquals(1, registry.plugins.size)
        assertTrue("重复的安装记录不该变成自己和自己重名", registry.conflicts.isEmpty())
    }

    @Test
    fun `装配问题能按插件 id 查出来`() {
        // script 运行形态还没实现 —— 这个插件会装配出 0 个工具
        val registry = build(
            plugins = listOf(Manifests.installed(Manifests.otherRuntime("pub.a.script", "script"))),
        )

        val problems = registry.problemsOf("pub.a.script")
        assertEquals(1, problems.size)
        assertTrue(problems.single().message, problems.single().message.contains("script"))
        assertTrue("『装了插件但没有任何提示』是最难查的状态", registry.needsAttention)
    }

    @Test
    fun `冲突描述里带上插件名和工具名`() {
        val registry = build(plugins = listOf(plugin("pub.a.one", listOf("fetch_url"))))
        val text = registry.conflicts.single().describe()

        assertTrue(text, text.contains("插件 pub.a.one"))
        assertTrue(text, text.contains("fetch_url"))
        assertTrue("要说清为什么不能这么做：$text", text.contains("内置"))
    }
}
