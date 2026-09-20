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
 * ## 这个文件里最值钱的一条是「引擎没装时报的是宿主不支持」
 *
 * 两个失败长得几乎一样，出路却完全相反：
 *
 * | 情况 | 用户该做什么 |
 * |---|---|
 * | 宿主没有脚本运行时 | 等宿主升级 |
 * | 插件的入口文件读不到 | 重装插件 |
 *
 * 而**同一份清单可以同时有这两个毛病**。这时报哪一条，取决于
 * `PluginHost.script()` 里那两处检查的**先后** —— 先看引擎，才会报「宿主不支持」。
 * 反过来的话，用户会去反复重装一个根本没坏的插件。
 *
 * ## 这条性质上一轮是靠「记录 `readSource` 被调过几次」断言的，现在换了测法
 *
 * 源码现在跟着清单走（`PluginManifest.files`），装配层**结构上**就没有
 * 「读文件」这回事了 —— 那套记录没有存在的意义。所以换成一个**行为**断言：
 * 拿一份两个毛病都有的清单，看报出来的是哪一条。
 *
 * 比记录调用更直接，而且不会因为实现换个写法就失效。
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
    fun `每个工具都拿到同一份插件文件，但各带自己的名字`() = runBlocking {
        val runtime = FakeScriptRuntime()
        val set = tools(Manifests.script(tools = listOf("csv_stats", "csv_head")), runtime)

        set.tools.forEach { it.execute(buildJsonObject {}) }
        val requests = runtime.requests

        assertEquals(listOf("csv_stats", "csv_head"), requests.map { it.toolName })
        // 一个插件的多个工具共用同一份源码，脚本靠 toolName 分派 ——
        // 按工具各读一遍在文件大时是白白的 IO
        assertEquals(1, requests.map { it.files }.toSet().size)
        assertEquals(setOf("index.js"), requests.first().files.keys)
    }

    @Test
    fun `子目录里的文件也一起带过去`() = runBlocking {
        val runtime = FakeScriptRuntime()
        val set = tools(
            Manifests.script(
                main = "lib/main.js",
                files = mapOf(
                    "lib/main.js" to "exports.run = () => require('./util.js').n()",
                    "lib/util.js" to "exports.n = () => 2",
                ),
            ),
            runtime,
        )

        set.tools.single().execute(buildJsonObject {})
        val request = runtime.requests.single()

        // 入口放子目录是合法写法（校验层明确允许），所以模块解析必须能看见
        // 同一棵树里的其它文件 —— 「允许写却跑不起来」是最难查的那种不一致
        assertEquals(setOf("lib/main.js", "lib/util.js"), request.files.keys)
        assertEquals("lib/main.js", request.entryFile)
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
        val set = tools(Manifests.script(), FakeScriptRuntime(), enabled = false)

        assertTrue(set.tools.isEmpty())
        // 关掉是用户的选择，不是错误。给它报问题会让插件管理页一直亮着小红点，
        // 而用户已经处理过了
        assertTrue(set.problems.isEmpty())
    }

    // ---------------------------------------------------------------- 失败路径

    @Test
    fun `引擎没装时报的是宿主不支持，不是入口文件缺失`() {
        // 用 raw() 绕过校验：这份清单**自己也是坏的**（entry 指向 index.js，
        // 而 files 是空的）。校验层会拦下这种清单，所以只能手工造一个 ——
        // 这也正是 raw() 存在的理由。
        //
        // 两个毛病同时存在时报哪一条，就是这条用例要钉的东西
        val broken = Manifests.raw(Manifests.scriptObject(files = emptyMap()))
        val set = PluginHost.tools(
            broken,
            client,
            FakeScriptRuntime(
                unavailableReason =
                    "当前版本的宿主还不支持 script 运行形态，这个插件暂时不会提供任何工具。",
            ),
        )

        assertTrue(set.tools.isEmpty())
        val problem = set.problems.single()
        assertEquals("$.runtime", problem.path)
        assertTrue(problem.message, problem.message.contains("不支持"))
        assertEquals(
            "作者没写错，不该报成错误",
            ManifestProblem.Severity.Warning,
            problem.severity,
        )
    }

    @Test
    fun `宿主给的原因原样进那条警告_装配层不自己编一句`() {
        // 「跑不了」有两种，而出路不一样：宿主没有引擎（等升级）与这台设备跑不了引擎
        // （换设备，或者换插件形态）。装配层只知道「不能跑」，所以那句话必须由宿主给 ——
        // 这条用例钉的就是**它一个字都没被改过**：改过就说明这里又编了一句，
        // 而那句「编的」只可能是前者，于是 16 KB 内存页的设备会读到「等宿主升级」
        val reason = "这台设备的内存页是 16 KB，引擎的原生库加载会崩，宿主关掉了脚本运行时。"
        val set = tools(Manifests.script(), FakeScriptRuntime(unavailableReason = reason))

        assertTrue(set.tools.isEmpty())
        val problem = set.problems.single()
        assertEquals("$.runtime", problem.path)
        assertEquals(reason, problem.message)
        assertEquals(ManifestProblem.Severity.Warning, problem.severity)
    }

    @Test
    fun `引擎可用但清单里没有入口文件时兜住`() {
        // 同样绕过校验。PluginHost 是 public API，不能假设调用方一定先跑过
        // ManifestParser（和 declarative 分支同一个理由）。
        //
        // 这条**正常路径下已经到不了了** —— 校验层新增的 checkEntryFileExists
        // 会在安装时就拦下它，而那是更好的地方：只有作者能修，用户重装没用
        val broken = Manifests.raw(Manifests.scriptObject(files = emptyMap()))
        val set = PluginHost.tools(broken, client, FakeScriptRuntime())

        assertTrue(set.tools.isEmpty())
        val problem = set.problems.single()
        assertEquals("$.entry.script.main", problem.path)
        assertTrue(problem.message, problem.message.contains("index.js"))
        assertTrue("要给下一步动作", problem.message.contains("重装"))
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
    fun `装配脚本插件不发任何网络请求`() {
        // 脚本能碰什么由沙箱管，装配层不该有网络行为。用一台没配过的
        // OkHttpClient 也能装配，说明装配路径上没有任何请求
        val set = PluginHost.tools(
            Manifests.installed(Manifests.script()),
            OkHttpClient(),
            FakeScriptRuntime(),
        )

        assertFalse(set.tools.isEmpty())
        assertTrue(set.problems.isEmpty())
    }
}
