package com.aichat.plugin.host

import com.aichat.plugin.FakeScriptRuntime
import com.aichat.plugin.Manifests
import com.aichat.plugin.manifest.FilesystemScope
import com.aichat.plugin.manifest.ManifestProblem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本形态的装配。
 *
 * ## 这个文件里最值钱的一条是「没装引擎时不许去读源码」
 *
 * 两个失败长得几乎一样，出路却完全相反：
 *
 * | 情况 | 用户该做什么 |
 * |---|---|
 * | 宿主没有脚本运行时 | 等宿主升级 |
 * | 插件的入口文件读不到 | 重装插件 |
 *
 * 如果装配层写成「先读源码、读不到就报入口文件缺失」，那么宿主没装引擎时
 * 用户看到的会是第二条 —— 他会去反复重装一个**根本没坏**的插件。
 *
 * 所以 [FakeScriptRuntime.reads] 必须是空的。这条只能靠「记录调用」来断言，
 * 看返回值看不出来（两种情况下工具数都是 0、都有一条 Warning）。
 */
class ScriptHostTest {

    private val client = OkHttpClient()

    private fun tools(
        json: String,
        runtime: FakeScriptRuntime,
        settings: Map<String, String> = emptyMap(),
        enabled: Boolean = true,
    ) = PluginHost.tools(
        Manifests.installed(json, settings = settings, enabled = enabled),
        client,
        runtime,
    )

    // ---------------------------------------------------------------- 正常装配

    @Test
    fun `脚本插件按 tools 数量产出工具`() {
        val set = tools(
            Manifests.script(tools = listOf("csv_stats", "csv_head")),
            FakeScriptRuntime(),
        )

        assertEquals(listOf("csv_stats", "csv_head"), set.tools.map { it.definition.name })
        assertTrue(set.problems.isEmpty())
        assertEquals("pub.test.script", set.pluginId)
    }

    @Test
    fun `源码在装配期读一次并放进每个工具`() = runBlocking {
        val runtime = FakeScriptRuntime()
        val set = tools(Manifests.script(tools = listOf("csv_stats", "csv_head")), runtime)

        // 装配期读，而且**只读一次** —— 两个工具共用一份脚本，
        // 按工具各读一遍在文件大时是白白的 IO
        assertEquals(listOf("pub.test.script" to "index.js"), runtime.reads)

        // 每个工具拿到的都是同一份源码，但各带自己的名字（脚本靠它分派）
        set.tools.forEach { it.execute(buildJsonObject {}) }
        val requests = runtime.requests
        assertEquals(2, requests.size)
        assertEquals(
            listOf("csv_stats", "csv_head"),
            requests.map { it.toolName },
        )
        assertEquals(
            "两个工具该拿到同一份源码",
            1,
            requests.map { it.source }.toSet().size,
        )
        assertEquals(FakeScriptRuntime.DEFAULT_SOURCE, requests.first().source)
    }

    @Test
    fun `配置项只把有值的键传下去`() = runBlocking {
        val runtime = FakeScriptRuntime()
        val set = tools(
            // 清单里 `settings` 声明的是**配置项**（类型、标题、是不是敏感），
            // 用户填的**值**是另一回事，走 installed() 的 settings 参数 ——
            // 把值写进清单会直接解析失败
            Manifests.script(
                settings = "\"token\":{\"type\":\"string\",\"title\":\"令牌\",\"secret\":true}",
            ),
            runtime,
            settings = mapOf("token" to "SK-1", "empty" to "", "blank" to "   "),
        )

        set.tools.single().execute(buildJsonObject {})

        // 空串不能过去：「用户填了空」和「用户没填」在插件里必须是同一件事。
        // 过去的话脚本会看到 `Authorization: Bearer `，那对服务端是另一种请求
        assertEquals(mapOf("token" to "SK-1"), runtime.requests.single().settings)
    }

    @Test
    fun `权限与两条上限原样传下去`() = runBlocking {
        val runtime = FakeScriptRuntime()
        val set = tools(
            Manifests.script(
                network = listOf("api.example.com", "cdn.example.com"),
                filesystem = "readwrite",
                extraEntry = ""","timeoutMs":1500,"memoryLimitMb":32""",
            ),
            runtime,
        )

        set.tools.single().execute(buildJsonObject {})
        val request = runtime.requests.single()

        assertEquals(listOf("api.example.com", "cdn.example.com"), request.network)
        assertEquals(FilesystemScope.ReadWrite, request.filesystem)
        // 这两个不是建议值，是沙箱真会执行的阈值 —— 沙箱在另一个进程里，
        // 拿不到清单，只能靠这个请求带过去
        assertEquals(1500L, request.timeoutMs)
        assertEquals(32, request.memoryLimitMb)
    }

    @Test
    fun `没写两条上限时用清单默认值`() = runBlocking {
        val runtime = FakeScriptRuntime()
        val set = tools(Manifests.script(), runtime)

        set.tools.single().execute(buildJsonObject {})
        val request = runtime.requests.single()

        assertEquals(30_000L, request.timeoutMs)
        assertEquals(128, request.memoryLimitMb)
    }

    @Test
    fun `关掉的插件不装配也不报问题`() {
        val runtime = FakeScriptRuntime()
        val set = tools(Manifests.script(), runtime, enabled = false)

        assertTrue(set.tools.isEmpty())
        // 关掉是用户的选择，不是错误。给它报问题会让插件管理页一直亮着小红点，
        // 而用户已经处理过了
        assertTrue(set.problems.isEmpty())
        assertTrue("关掉的插件不该被读源码", runtime.reads.isEmpty())
    }

    // ---------------------------------------------------------------- 失败路径

    @Test
    fun `引擎不可用时报的是宿主不支持而不是文件缺失`() {
        val runtime = FakeScriptRuntime(available = false)
        val set = tools(Manifests.script(), runtime)

        assertTrue(set.tools.isEmpty())
        val problem = set.problems.single()
        assertEquals("$.runtime", problem.path)
        assertTrue(problem.message, problem.message.contains("不支持"))
        assertTrue(
            "作者没写错，不该报成错误",
            problem.severity == ManifestProblem.Severity.Warning,
        )
        // 这条是本文件存在的理由：**没去读源码**。反过来的话用户看到的会是
        // 「入口文件读不到」，然后去反复重装一个根本没坏的插件
        assertTrue("宿主没装引擎时不该去读文件", runtime.reads.isEmpty())
    }

    @Test
    fun `引擎装了但读不到入口脚本时说得清该干什么`() {
        val set = tools(Manifests.script(main = "lib/main.js"), FakeScriptRuntime.empty())

        assertTrue(set.tools.isEmpty())
        val problem = set.problems.single()
        assertEquals("$.entry.script.main", problem.path)
        // 报出来的是作者写的那个路径 —— 他拿着这句话回清单里对，对得上
        assertTrue(problem.message, problem.message.contains("lib/main.js"))
        assertTrue("要给下一步动作", problem.message.contains("重装"))
    }

    @Test
    fun `入口脚本缺失是 Error 而不是 Warning`() {
        // 和「宿主不支持」分开：那个是环境问题、等升级；这个是这个插件坏了、
        // 现在就用不了。都报成 Warning 的话插件管理页看不出哪个该管
        val set = tools(Manifests.script(main = "missing.js"), FakeScriptRuntime.empty())

        assertEquals(ManifestProblem.Severity.Error, set.problems.single().severity)
    }

    @Test
    fun `脚本工具默认要确认`() {
        // 和声明式相反。声明式能按 HTTP 方法判断（只有 GET/HEAD 安全），
        // 脚本形态宿主看不到请求 —— 这条走一遍完整装配，防止
        // 「装配时把三态压成了布尔」
        val set = tools(Manifests.script(), FakeScriptRuntime())

        assertTrue(set.tools.single().requiresConfirmation)
    }

    @Test
    fun `脚本工具不依赖网络客户端`() {
        // 装配脚本插件时**一个请求都不该发**：脚本能碰什么由沙箱管，
        // 装配层不该有网络行为。用一台不存在的 OkHttpClient 也能装配
        val set = PluginHost.tools(
            Manifests.installed(Manifests.script()),
            OkHttpClient(),
            FakeScriptRuntime(),
        )

        assertFalse(set.tools.isEmpty())
        assertTrue(set.problems.isEmpty())
    }
}
