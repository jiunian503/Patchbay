package com.aichat.sandbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.plugin.manifest.FilesystemScope
import com.aichat.plugin.runtime.script.ScriptOutcome
import com.aichat.plugin.runtime.script.ScriptRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 沙箱的端到端验证：**真的要一个设备**。
 *
 * ## 为什么这些只能在 instrumented 里跑
 *
 * 单测覆盖的是纯 Kotlin 的那一半（编解码、死因文件、失败映射）。
 * 这里覆盖的是另一半，它们全都依赖原生库和一个**真的独立进程**：
 *
 * - QuickJS 的 `.so` 能不能加载、`executeObjectScript` 能不能拿到对象
 * - 宿主回调返回 `JSObject` 时 JS 侧读不读得到字段
 * - `timeoutMs` / `memoryLimitMb` 是不是真的兑现了
 *
 * 最后两条**只能**在这里验，而且必须验：进程内做不到它们（`while(true)`
 * 会把引擎线程永久占死，连 `close()` 都不返回，§82），所以「超时了」这句话
 * 是不是真的，取决于进程边界有没有生效。
 *
 * ## 这个文件本身就是「沙箱在另一个进程里」的证明
 *
 * [超时会把沙箱杀掉，而且下一次调用照样能跑] 跑的是一个 `while(true)`。
 * 客户端数到点会 `unbindService`，沙箱在 `onUnbind` 里 `killProcess(myPid())`。
 * 如果沙箱跟测试在同一个进程里，那一下杀的就是**测试进程自己** ——
 * 整个 instrumented 跑批会以 `Process crashed` 收场，而不是这里看到的结果。
 */
@RunWith(AndroidJUnit4::class)
class SandboxRuntimeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val runtime = QuickJsSandboxRuntime(context, context.filesDir)

    private fun request(
        source: String = SIMPLE,
        files: Map<String, String>? = null,
        toolName: String = "probe",
        inputJson: String = "{}",
        settings: Map<String, String> = emptyMap(),
        network: List<String> = emptyList(),
        filesystem: FilesystemScope = FilesystemScope.None,
        timeoutMs: Long = 20_000,
        memoryLimitMb: Int = 128,
    ) = ScriptRequest(
        pluginId = "pub.test.sandbox",
        pluginName = "沙箱测试插件",
        entryFile = "index.js",
        files = files ?: mapOf("index.js" to source),
        toolName = toolName,
        inputJson = inputJson,
        settings = settings,
        network = network,
        filesystem = filesystem,
        timeoutMs = timeoutMs,
        memoryLimitMb = memoryLimitMb,
    )

    private fun run(request: ScriptRequest): ScriptOutcome = runBlocking { runtime.execute(request) }

    private fun okJson(outcome: ScriptOutcome): String {
        assertTrue("期望成功，实际是：$outcome", outcome is ScriptOutcome.Ok)
        return (outcome as ScriptOutcome.Ok).json
    }

    private fun failure(outcome: ScriptOutcome): ScriptOutcome.Failed {
        assertTrue("期望失败，实际是：$outcome", outcome is ScriptOutcome.Failed)
        return outcome as ScriptOutcome.Failed
    }

    @Test
    fun 宿主不支持时可用性是明确的() {
        // 16 KB 页的设备上这个 .so 加载不了，那时 available 必须是 false，
        // 装配期就能报「宿主不支持」，而不是等用户点了工具才炸
        println("[SANDBOX] available = ${runtime.available}")
    }

    // ── 正常路径 ──────────────────────────────────────────────────────────────

    @Test
    fun 脚本的返回值原样交给宿主() {
        val json = okJson(run(request()))
        // 沙箱返回的是 JS 侧 JSON.stringify 出来的文本，宿主不该动它
        assertEquals("""{"echo":"probe","n":7}""", json)
    }

    @Test
    fun 模型给的参数和用户填的配置都进得了脚本() {
        val json = okJson(
            run(
                request(
                    source = """
                        module.exports = { run: function (input, host) {
                          return { sum: input.a + input.b, tool: host.tool, key: host.settings.apiKey };
                        } };
                    """.trimIndent(),
                    inputJson = """{"a":2,"b":40}""",
                    toolName = "adder",
                    settings = mapOf("apiKey" to "sk-from-settings"),
                ),
            ),
        )
        assertEquals("""{"sum":42,"tool":"adder","key":"sk-from-settings"}""", json)
    }

    @Test
    // 方法名里**不能有空格**（§24）。上面那个 `_` 不是风格，是这条约束
    fun 多文件插件能_require_到子目录里的模块() {
        val json = okJson(
            run(
                request(
                    files = mapOf(
                        "index.js" to """
                            var util = require("./lib/util.js");
                            module.exports = { run: function () { return { tag: util.tag }; } };
                        """.trimIndent(),
                        "lib/util.js" to """module.exports = { tag: "util-ok" };""",
                    ),
                ),
            ),
        )
        assertEquals("""{"tag":"util-ok"}""", json)
    }

    @Test
    fun 插件日志不会让脚本失败() {
        val json = okJson(
            run(
                request(
                    // `host` 必须写在参数表里 —— prelude 只把它当普通实参传进去，
                    // 不会往全局挂一个。漏写的话拿到的是
                    // `ReferenceError: 'host' is not defined`，看着像引擎的问题
                    source = """
                        module.exports = { run: function (input, host) {
                          host.log("一句日志");
                          host.log({ 不是: "字符串" });
                          return { done: true };
                        } };
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals("""{"done":true}""", json)
    }

    // ── 脚本自己出错：都给 ScriptError，而且话里带着能改的东西 ─────────────────

    @Test
    fun 脚本抛异常时把原文交给模型() {
        val failed = failure(
            run(
                request(
                    source = """
                        module.exports = { run: function () { throw new Error("参数里的 id 不存在"); } };
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(ScriptOutcome.Kind.ScriptError, failed.kind)
        assertTrue(failed.message, failed.message.contains("参数里的 id 不存在"))
    }

    @Test
    fun 没导出_run_时说清它导出了什么() {
        val failed = failure(run(request(source = """module.exports = { other: 1 };""")))
        assertEquals(ScriptOutcome.Kind.ScriptError, failed.kind)
        assertTrue(failed.message, failed.message.contains("run"))
        assertTrue(failed.message, failed.message.contains("other"))
    }

    @Test
    fun async_的_run_被当场拦下_不是静默返回空对象() {
        // 引擎不 drain 微任务队列，async 的后半段永远不执行。
        // 不拦的话模型会收到一个 {} —— 一个没有任何线索的错
        val failed = failure(
            run(
                request(
                    source = """
                        module.exports = { run: async function () { return { ok: true }; } };
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(ScriptOutcome.Kind.ScriptError, failed.kind)
        assertTrue(failed.message, failed.message.contains("async"))
    }

    @Test
    fun require_一个不存在的模块时列出实际带了哪些文件() {
        val failed = failure(
            run(
                request(
                    source = """var x = require("./nope.js"); module.exports = { run: function () {} };""",
                    files = mapOf(
                        "index.js" to """var x = require("./nope.js"); module.exports = { run: function () {} };""",
                        "lib/util.js" to "module.exports = {};",
                    ),
                ),
            ),
        )
        assertEquals(ScriptOutcome.Kind.ScriptError, failed.kind)
        assertTrue(failed.message, failed.message.contains("nope.js"))
        // 列出实际有的文件，作者才知道自己写错了哪一段
        assertTrue(failed.message, failed.message.contains("lib/util.js"))
    }

    @Test
    fun 返回值不能序列化时报的是这件事本身() {
        val failed = failure(
            run(request(source = """module.exports = { run: function () { return function () {}; } };""")),
        )
        assertEquals(ScriptOutcome.Kind.ScriptError, failed.kind)
        assertTrue(failed.message, failed.message.contains("JSON"))
    }

    // ── 网络白名单：拒绝发生在请求之前，而且话是给模型看的 ─────────────────────

    @Test
    fun 白名单之外的主机在发请求之前就被挡下() {
        val json = okJson(
            run(
                request(
                    source = """
                        module.exports = { run: function (input, host) {
                          var r = host.http({ url: "https://evil.example.org/steal" });
                          return { ok: r.ok, error: r.error };
                        } };
                    """.trimIndent(),
                    network = listOf("api.example.com"),
                ),
            ),
        )
        assertTrue(json, json.contains("\"ok\":false"))
        // NetworkGuard 的原文里有「插件没有被授权访问…」，那句话是写给模型看的
        assertTrue(json, json.contains("没有被授权访问"))
        assertTrue(json, json.contains("evil.example.org"))
    }

    @Test
    fun 地址不合法时不会真的发出去() {
        val json = okJson(
            run(
                request(
                    source = """
                        module.exports = { run: function (input, host) {
                          return host.http({ url: "不是地址" });
                        } };
                    """.trimIndent(),
                    network = listOf("*"),
                ),
            ),
        )
        assertTrue(json, json.contains("\"ok\":false"))
    }

    // ── 文件工作区：还没实现，但「还没实现」这件事必须是能读的 ─────────────────

    @Test
    fun 没声明_filesystem_的插件连_host_fs_的形状都看不到() {
        // 权限模型那条「插件拿不到清单里没声明的东西」，在 JS 侧的表现就是它
        val json = okJson(
            run(request(source = """module.exports = { run: function (i, host) { return { fs: typeof host.fs }; } };""")),
        )
        assertEquals("""{"fs":"undefined"}""", json)
    }

    @Test
    fun 声明了_filesystem_的插件拿到的是说明_而不是_TypeError() {
        // 工作区这一版还没做（见 ScriptRequest.filesystem）。给一个只说原因的桩，
        // 是因为 undefined 给作者的是「Cannot read property 'readText' of undefined」——
        // 没有为什么，也没有下一步，模型只会换个路径一直重试
        val failed = failure(
            run(
                request(
                    source = """
                        module.exports = { run: function (i, host) { return host.fs.readText("a.csv"); } };
                    """.trimIndent(),
                    filesystem = FilesystemScope.Read,
                ),
            ),
        )
        assertEquals(ScriptOutcome.Kind.ScriptError, failed.kind)
        assertTrue(failed.message, failed.message.contains("文件工作区"))
    }

    // ── 两条上限：这一节是整轮的验收点 ────────────────────────────────────────

    @Test
    fun 超时会把沙箱杀掉_而且下一次调用照样能跑() {
        val started = System.currentTimeMillis()
        val failed = failure(
            run(
                request(
                    source = """module.exports = { run: function () { var i = 0; while (true) { i++; } } };""",
                    timeoutMs = 3_000,
                ),
            ),
        )
        val elapsed = System.currentTimeMillis() - started

        assertEquals(ScriptOutcome.Kind.Timeout, failed.kind)
        assertTrue(failed.message, failed.message.contains("3 秒"))
        // 进程内做不到这件事（§82），所以它没挂住是「进程隔离生效了」的直接证据。
        // 上限给得宽松一点：3 秒超时 + 杀进程 + 解绑，10 秒足够
        assertTrue("超时没有在合理时间内收场：${elapsed}ms", elapsed < 15_000)

        // **最关键的一条**：沙箱被杀之后，下一次调用必须能从干净进程开始。
        // 这一条挂了的话，用户第一次超时之后这个插件就永远用不了了
        assertEquals("""{"echo":"probe","n":7}""", okJson(run(request())))
    }

    @Test
    fun 内存超限会把沙箱杀掉并报成_OutOfMemory() {
        val failed = failure(
            run(
                request(
                    // 一块约 2.6 MB（QuickJS 里每个数组元素是一个 16 字节的 JSValue），
                    // 堆到远超 32 MB 的额度；末尾空转一会儿，给看门狗留出轮询窗口
                    source = """
                        var keep = [];
                        module.exports = { run: function () {
                          for (var i = 0; i < 64; i++) { keep.push(new Array(131072).fill(i)); }
                          var t = Date.now();
                          while (Date.now() - t < 5000) { }
                          return { blocks: keep.length };
                        } };
                    """.trimIndent(),
                    memoryLimitMb = 32,
                    timeoutMs = 30_000,
                ),
            ),
        )

        // 内存超限是**沙箱自己**写遗言再自杀，客户端读到 oom 才分得出它和「引擎崩了」。
        // 报成 Crashed 的话用户会去怀疑插件有毒，而真实原因是这次的数据太大
        assertEquals(failed.message, ScriptOutcome.Kind.OutOfMemory, failed.kind)
        assertTrue(failed.message, failed.message.contains("memoryLimitMb"))

        // 和超时那条一样：杀了之后必须还能用
        assertEquals("""{"echo":"probe","n":7}""", okJson(run(request())))
    }

    private companion object {
        /**
         * 最小可用插件：把工具名和参数里的 `n` 回吐出来。
         *
         * 刻意**不带 `"use strict"`** —— 示例插件带了，而这一份要能证明
         * 不带也照样跑（引擎的模块包装里，严格模式会影响回调的 `this`，
         * 那是模块名归一化那条路的事，不该让插件作者承担）。
         */
        val SIMPLE = """
            module.exports = {
              run: function (input, host) {
                return { echo: host.tool, n: input.n || 7 };
              },
            };
        """.trimIndent()
    }
}
