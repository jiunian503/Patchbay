package com.aichat.plugin.runtime.script

import com.aichat.plugin.FakeScriptRuntime
import com.aichat.plugin.manifest.FilesystemScope
import com.aichat.plugin.manifest.ToolSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本工具：清单里的一段声明 + 一份 JS → 一个模型能调的工具。
 *
 * ## 这个文件里守的两条
 *
 * **一、失败信息一个字都不加工。** 沙箱写的那句话里带着「能不能重试」的暗示
 * （超时/内存超限别重试，脚本报错可以改参数重试）。`ScriptTool` 没有能力判断
 * 哪句该怎么说，所以它只能原样传 —— 而「原样传」这件事很容易在以后被改成
 * 「包一层前缀」，那时模型就再也分不出这两种失败了。
 *
 * **二、确认开关的默认值是 `true`，和声明式相反。** 声明式能按 HTTP 方法判断
 * （只有 GET/HEAD 是安全方法），脚本形态**看不到请求**，所以 `null` 落到 `true`。
 * 三条用例（null / false / true）分别锁住，因为「默认值」这种东西只在
 * 边界上体现，中间值看不出来。
 */
class ScriptToolTest {

    private fun tool(
        runtime: FakeScriptRuntime = FakeScriptRuntime(),
        requiresConfirmation: Boolean? = null,
        network: List<String> = emptyList(),
        filesystem: FilesystemScope = FilesystemScope.None,
        entryFile: String = FakeScriptRuntime.DEFAULT_ENTRY,
        files: Map<String, String> = mapOf(entryFile to FakeScriptRuntime.DEFAULT_SOURCE),
        toolName: String = "csv_stats",
    ) = ScriptTool(
        pluginName = "测试插件",
        spec = ToolSpec(
            name = toolName,
            description = "统计 CSV 的行数与列数",
            parameters = buildJsonObject { put("type", "object") },
            requiresConfirmation = requiresConfirmation,
        ),
        template = ScriptRequest(
            pluginId = "pub.test.script",
            pluginName = "测试插件",
            entryFile = entryFile,
            files = files,
            toolName = toolName,
            // 调用期才有的字段，装配期留空 —— 由 execute 填
            inputJson = "",
            settings = emptyMap(),
            network = network,
            filesystem = filesystem,
            timeoutMs = 30_000,
            memoryLimitMb = 128,
        ),
        runtime = runtime,
    )

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    // ================================================================ 定义

    @Test
    fun `工具定义来自清单声明而不是脚本`() {
        // 脚本能改的只有「怎么算」，不能改「叫什么、参数长什么样」——
        // 后者要提前给模型（列表里就得有），而那时脚本还没跑过
        val definition = tool().definition

        assertEquals("csv_stats", definition.name)
        assertEquals("统计 CSV 的行数与列数", definition.description)
        assertTrue(definition.parameters.containsKey("type"))
    }

    // ================================================================ 请求内容

    @Test
    fun `模型给的参数以 JSON 文本传给沙箱`() = runBlocking {
        val runtime = FakeScriptRuntime()
        tool(runtime).execute(args("arg" to "a,b\n1,2"))

        // 用文本而不是 JsonObject：这个请求要跨进程，而「能跨进程的东西」
        // 和「能序列化成 JSON 的东西」最好是同一份定义（见 ScriptRequest 的 KDoc）
        assertEquals("""{"arg":"a,b\n1,2"}""", runtime.requests.single().inputJson)
    }

    @Test
    fun `插件自带的全部文件都进请求`() = runBlocking {
        val runtime = FakeScriptRuntime()
        tool(
            runtime,
            files = mapOf(
                "index.js" to "exports.run = () => require('./lib/util.js').n()",
                "lib/util.js" to "exports.n = () => 42",
            ),
        ).execute(buildJsonObject {})

        // **整份**过去，不只入口那一份：多文件插件要靠它做模块解析
        // （`require('./lib/util.js')`），而只给入口的话那是「允许写却跑不起来」——
        // 清单的路径规则明确允许子目录，两边必须一致。
        //
        // 顺带一个好副作用：沙箱完全不用碰文件系统就能跑插件，
        // 于是「读用户文件」那条边界（§45）在脚本形态下压根不存在，
        // 而不是靠沙箱里的一道守卫去挡
        assertEquals(
            setOf("index.js", "lib/util.js"),
            runtime.requests.single().files.keys,
        )
    }

    @Test
    fun `这次调的是哪个工具也传下去`() = runBlocking {
        val runtime = FakeScriptRuntime()
        // 一个插件可以有多个工具共用同一份脚本，脚本靠 toolName 分派
        tool(runtime, toolName = "csv_stats").execute(buildJsonObject {})

        assertEquals("csv_stats", runtime.requests.single().toolName)
    }

    // ================================================================ 结果映射

    @Test
    fun `脚本返回的值成为工具结果`() = runBlocking {
        val runtime = FakeScriptRuntime(outcome = ScriptOutcome.Ok("""{"rows":3}"""))
        val result = tool(runtime).execute(buildJsonObject {})

        assertFalse(result.isError)
        assertEquals("""{"rows":3}""", result.content)
    }

    @Test
    fun `失败信息原样传给模型不加工`() = runBlocking {
        val runtime = FakeScriptRuntime(
            outcome = ScriptOutcome.Failed(
                "脚本抛了错：TypeError: input.arg is undefined",
                ScriptOutcome.Kind.ScriptError,
            ),
        )
        val result = tool(runtime).execute(buildJsonObject {})

        assertTrue(result.isError)
        // 一个字都不能多。加了前缀之后，「超时别重试」和「脚本报错可以改参数
        // 重试」这两种失败在模型眼里就没有区别了 —— 它会对着一个必然超时的
        // 调用反复重试
        assertEquals("脚本抛了错：TypeError: input.arg is undefined", result.content)
    }

    @Test
    fun `超时和内存超限同样按失败回灌`() = runBlocking {
        // 它们**不该**让调用方抛异常中断整轮对话：模型需要知道发生了什么，
        // 才有机会换个工具或直接告诉用户做不到（ToolResult 的契约）
        listOf(ScriptOutcome.Kind.Timeout, ScriptOutcome.Kind.OutOfMemory).forEach { kind ->
            val runtime = FakeScriptRuntime(
                outcome = ScriptOutcome.Failed("沙箱被杀了（$kind）", kind),
            )
            val result = tool(runtime).execute(buildJsonObject {})

            assertTrue("$kind 应该是错误结果而不是异常", result.isError)
            assertTrue(result.content, result.content.contains(kind.name))
        }
    }

    // ================================================================ 确认策略（三态）

    @Test
    fun `作者没写确认开关时默认要确认`() {
        // 和声明式**相反**：那边没写时按 HTTP 方法判断，GET 是免确认的。
        // 脚本形态宿主看不到请求，没有「安全方法」这个依据可用 ——
        // 往一个白名单主机 POST 完全可能，而那可能改服务端数据。
        // 看不到就确认：漏写的后果该是「多弹一次窗」，不是「本该问的没问」
        assertTrue(tool(requiresConfirmation = null).requiresConfirmation)
    }

    @Test
    fun `作者明确担保时免确认`() {
        // 三态设计里「签字」那个位置：作者说这个工具只读，宿主信他
        assertFalse(tool(requiresConfirmation = false).requiresConfirmation)
    }

    @Test
    fun `作者明确要求确认时确认`() {
        assertTrue(tool(requiresConfirmation = true).requiresConfirmation)
    }

    // ================================================================ 弹窗文案

    @Test
    fun `userSummary 说的是可达范围而不是「会运行脚本」`() {
        val summary = tool(
            network = listOf("api.example.com", "cdn.example.com"),
            filesystem = FilesystemScope.ReadWrite,
        ).userSummary

        // 用户是照着这句话决定放不放行的。「它会运行一个脚本」等于没说 ——
        // 他能判断的只有「这个插件能碰到什么」
        assertTrue(summary, summary.contains("测试插件"))
        assertTrue(summary, summary.contains("api.example.com"))
        assertTrue(summary, summary.contains("cdn.example.com"))
        assertTrue("要说清是读写还是只读", summary.contains("读写"))
        assertTrue("要解释为什么弹窗，否则用户会觉得 App 在乱问", summary.contains("确认"))
    }

    @Test
    fun `没有任何权限时说明它只能处理给它的数据`() {
        val summary = tool(network = emptyList(), filesystem = FilesystemScope.None).userSummary

        // 空着不写的话，用户看到的是一个没说清能干什么的弹窗，
        // 而他必须在几秒内决定放不放行
        assertTrue(summary, summary.contains("只能处理你给它的数据"))
    }

    @Test
    fun `只读和读写要分开说`() {
        val read = tool(filesystem = FilesystemScope.Read).userSummary
        val write = tool(filesystem = FilesystemScope.ReadWrite).userSummary

        assertTrue(read, read.contains("读它自己目录"))
        assertFalse("只读不该说成读写", read.contains("读写"))
        assertTrue(write, write.contains("读写它自己目录"))
    }
}
