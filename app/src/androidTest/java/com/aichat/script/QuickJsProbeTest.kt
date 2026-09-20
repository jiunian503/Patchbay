package com.aichat.script

import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quickjs.CommonJSModule
import com.quickjs.JSArray
import com.quickjs.JSObject
import com.quickjs.JavaCallback
import com.quickjs.JavaVoidCallback
import com.quickjs.QuickJS
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * QuickJS 引擎探针（第四版）—— **这不是测试，是测量**。
 *
 * ## 前三版已经定下来的事（别再重测）
 *
 * | 结论 | 怎么知道的 |
 * |---|---|
 * | `executeScript(source, fileName)` —— **源码在前** | 传反了会把文件名当 JS 求值 |
 * | `executeModule(name)` 返回的是 CommonJS 的 **module 记录**，导出在 `.getObject("exports")` | 包装是 `(function(){var module={exports:{}};…;return module;})()` |
 * | `async run()` 的后半段**永远不执行**（微任务队列不 drain） | `globalThis.__done` 从未被赋值 + 收尾那句 `host.log` 从未打印 |
 * | `while(true)` 会永久占死 `QuickJS-0`，连 `close()` 都不返回 | 见 [probeSpin] |
 * | JS 跑在 `QuickJS-0` 这条 HandlerThread 上（不是调用线程） | 宿主回调里的线程名 |
 * | 三个候选绑定的 Java 层都没有 `JS_SetMemoryLimit` 出口，`EventQueue.interrupt()` 是包级私有 | `javap` 逐条扫 `QuickJSNative` |
 *
 * ## 这一版要回答的（都直接决定沙箱怎么写）
 *
 * **一、`JavaCallback` 能不能返回一个对象。** `host.http()` 必须把
 * `{status, headers, body}` 交给 JS。三条路：返回 `JSObject`、返回
 * `org.json.JSONObject`、返回 JSON 字符串让 JS 自己 `JSON.parse`。
 * 哪条通、哪条不通只能实测 —— JNI 那侧的转换函数在 C 里，`javap` 看不到。
 *
 * **二、`convertModuleName` 的默认实现对不对。** 反编译出来是
 * `convertModuleName(parent, child)`，而 `require('./lib/util.js')` 传的 parent 是
 * **发起 require 那个模块的文件名**（`index.js`），不是它的目录。照那个实现，
 * 结果会是 `index.js/lib/util.js` —— 相对 require 根本不能用。
 * 探针要证实这一点，并证实「重写 `convertModuleName` 就能修好」。
 *
 * **三、返回值怎么过到 Java。** 候选是 `JSObject.toJSONObject()`（Java 侧重写
 * 一遍序列化）和「在 JS 里 `JSON.stringify`，Java 只取字符串」。
 * 后者才是对的 —— 序列化的规矩该由 JS 引擎自己定。
 *
 * **四、`/proc/self/status` 的 `VmRSS` 反不反映 JS 的分配。** 这是内存上限
 * 唯一可能的兑现方式（QuickJS 的堆是 native malloc，不是 Java 堆）。
 * 顺带量一件事：**脚本把引用丢掉之后 RSS 会不会还回去** —— 会还的话，
 * 内存预算可以按「每次调用」算；不会还的话就得按「进程一生」算。
 *
 * **五、从 `JavaCallback` 里抛 Java 异常会怎样。** `QuickJS.callJavaCallback`
 * 的字节码里**没有 try/catch**，而它是被 JNI 调进来的静态方法。
 * 所以沙箱的宿主方法一律**不许抛**，失败要包成返回值 —— 这条要有实测撑着。
 *
 * 输出走 logcat，tag `PB_PROBE`。
 */
@RunWith(AndroidJUnit4::class)
class QuickJsProbeTest {

    private fun say(msg: String) {
        Log.i(TAG, msg)
        println("[PROBE] $msg")
    }

    /**
     * 把源码按模块名喂给 `CommonJSModule`，并**记下每一个被问到的名字**。
     *
     * [overrideConvert] 为 false 时用引擎自带的模块名归一化（要证实它是坏的），
     * 为 true 时用我们自己的（要证实它是对的）。
     */
    private class ProbeModule(
        runtime: QuickJS,
        private val sources: Map<String, String>,
        private val overrideConvert: Boolean,
    ) : CommonJSModule(runtime) {

        val asked = mutableListOf<String>()

        override fun getModuleScript(moduleName: String): String {
            asked += moduleName
            return sources[moduleName]
                ?: error("没有这个模块：$moduleName（手上只有 ${sources.keys}）")
        }

        override fun convertModuleName(parent: String?, child: String?): String {
            if (!overrideConvert) return super.convertModuleName(parent, child)

            // 正确实现：把 child 当成**相对于发起 require 那个文件所在目录**的路径。
            // 引擎默认那版把 parent 整个当目录用，于是 `index.js` + `./lib/util.js`
            // 变成 `index.js/lib/util.js`。
            val name = child.orEmpty().replace('\\', '/')
            if (name.isEmpty()) return name
            if (name.startsWith("/")) return name.trimStart('/')

            val dir = parent.orEmpty().substringBeforeLast('/', "")
            val joined = if (dir.isEmpty()) name else "$dir/$name"

            val out = ArrayDeque<String>()
            for (seg in joined.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (out.isEmpty()) out += ".." else out.removeLast()
                    else -> out += seg
                }
            }
            return out.joinToString("/")
        }
    }

    /**
     * 造一个宿主对象。两次运行（重写版 / 引擎默认版）用**同一份**注册代码，
     * 否则两边的差异就不只是模块名归一化了。
     */
    private fun makeHost(m: ProbeModule): JSObject {
        val host = JSObject(m)
        host.set("tool", "csv_stats")
        host.set("settings", JSObject(m).set("apiKey", "sk-probe").set("lang", "zh"))
        host.registerJavaMethod(
            JavaVoidCallback { _, args -> say("      [js] ${args.getString(0)}") },
            "log",
        )
        host.registerJavaMethod(JavaCallback { _, args -> "echo:" + args.getString(0) }, "echo")
        host.registerJavaMethod(
            JavaCallback { _, _ ->
                JSObject(m).set("ok", true).set("status", 200).set("body", "from-JSObject")
            },
            "objBack",
        )
        host.registerJavaMethod(
            JavaCallback { _, _ ->
                JSONObject().put("ok", true).put("status", 201).put("body", "from-JSONObject")
            },
            "jsonBack",
        )
        host.registerJavaMethod(
            JavaCallback { _, _ -> "{\"ok\":true,\"status\":202,\"body\":\"from-String\"}" },
            "strBack",
        )
        host.registerJavaMethod(JavaCallback { _, _ -> JSArray(m).push("a").push("b") }, "arrBack")

        // 第五问。**放在最后注册**不是必须的，但它一旦真的把进程带走，
        // 上面那些注册语句已经跑完了 —— 少丢一点现场
        host.registerJavaMethod(
            JavaCallback { _, _ -> throw IllegalStateException("探针故意抛的宿主异常") },
            "boom",
        )
        return host
    }

    // ── 主探针：安全，可以反复跑 ───────────────────────────────────────────────

    @Test
    @org.junit.Ignore(
        "**这不是测试，是测量**，而且最后一步会故意把进程带走（见第五问），" +
            "留在跑批里会让整批以 Process crashed 收场。只在需要重新测量时单独跑它。" +
            "结论已经记在类注释的表里，日常由 SandboxRuntimeTest 守行为。",
    )
    fun probeHostSurface() {
        say("═══ 探针 v4：宿主接口面 ═══")
        say("0. 进程 = ${processName()}  页大小 = ${Os.sysconf(OsConstants._SC_PAGESIZE)}")

        val runtime = QuickJS.createRuntimeWithEventQueue()

        // ── 一 / 三：宿主回调的返回类型 + JS 侧序列化 ──────────────────────────
        val module = ProbeModule(runtime, SOURCES, overrideConvert = true)
        val host = makeHost(module)
        val record = module.executeModule("index.js")
        val exports = record.getObject("exports")
        say("1. exports keys = ${exports.keys.toList()}")

        val args = JSArray(module).push(JSObject(module).set("n", 7)).push(host)
        val report = runCatching { exports.executeStringFunction("run", args) }
            .getOrElse { "✗ 主流程失败：${it.javaClass.name}: ${it.message}" }
        say("2. 报告 = $report")

        // ── 二：require 的模块名归一化 ────────────────────────────────────────
        say("3a. [重写版 convertModuleName] 被问到的模块名 = ${module.asked}")

        val plain = ProbeModule(runtime, SOURCES, overrideConvert = false)
        val plainHost = makeHost(plain)
        val plainRecord = plain.executeModule("index.js")
        val plainOut = runCatching {
            plainRecord.getObject("exports").executeStringFunction(
                "run",
                JSArray(plain).push(JSObject(plain).set("n", 7)).push(plainHost),
            )
        }.getOrElse { "✗ 引擎默认版失败：${it.javaClass.name}: ${it.message}" }
        say("3b. [引擎默认版] 被问到的模块名 = ${plain.asked}")
        say("3b. [引擎默认版] 结果 = $plainOut")

        // ── 四：RSS 反不反映 JS 的分配 ───────────────────────────────────────
        val before = rssKb()
        val kept = runCatching {
            exports.executeIntegerFunction("allocMb", JSArray(module).push(16))
        }.getOrElse { -1 }
        val peak = rssKb()
        val freed = runCatching { exports.executeStringFunction("dropAll", JSArray(module)) }
            .getOrElse { "✗" }
        Thread.sleep(300)
        val after = rssKb()

        say("4. RSS 基线 = $before kB")
        say("4. 分配 16 块（返回 $kept 块）后 = $peak kB（涨了 ${peak - before} kB）")
        say("4. $freed 之后 = $after kB（相对峰值 ${after - peak} kB，相对基线 ${after - before} kB）")

        // ── 五：从 JavaCallback 抛异常 ───────────────────────────────────────
        say("5. 现在故意从宿主回调里抛一个异常（这一句之后可能就没有输出了）")
        val boom = runCatching { exports.executeStringFunction("boom", JSArray(module).push(host)) }
            .getOrElse { "✗ 抛出来了：${it.javaClass.name}: ${it.message}" }
        say("5. boom 的结果 = $boom")
        say("5. 上面这句能打印出来 = 抛异常没有立刻杀掉进程")
    }

    private fun rssKb(): Long = runCatching {
        File("/proc/self/status").readLines()
            .first { it.startsWith("VmRSS:") }
            .filter { it.isDigit() }
            .toLong()
    }.getOrElse { -1 }

    /** 进程名。不用 `ActivityThread.currentProcessName()` —— 那是隐藏 API。 */
    private fun processName(): String =
        runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }
            .getOrElse { "pid=${android.os.Process.myPid()}" }

    // ── 危险探针：默认不跑 ────────────────────────────────────────────────────

    /**
     * `while(true)` 的后果。**别在需要别的结论时顺手跑它** —— 它会漏一个
     * 永久 RUNNABLE 的 `QuickJS-0` 线程，并让那个运行时彻底作废（`close()` 都不返回）。
     *
     * 第三版的实测结论：调用线程没被挂住（`executeScript` 把活投给了 `QuickJS-0`），
     * 那个引擎线程转不停，`runtime.close()` 5 秒不返回。这正是
     * `ScriptEntry.timeoutMs` 只能靠**杀进程**兑现的直接原因。
     */
    @Test
    @org.junit.Ignore("会漏一个永久转动的引擎线程。只在需要重新测量时单独跑这一个方法。")
    fun probeSpin() {
        val runtime = QuickJS.createRuntimeWithEventQueue()
        val ctx = runtime.createContext()

        val pool = Executors.newSingleThreadExecutor { r ->
            Thread(r, "probe-caller").apply { isDaemon = true }
        }
        val fut = pool.submit<Any?> { ctx.executeScript(SPIN_SRC, "spin.js") }
        try {
            say("spin. 竟然返回了：${fut.get(8, TimeUnit.SECONDS)}")
        } catch (e: TimeoutException) {
            say("spin. 8 秒没返回 —— 挂住了（这正是要观察的）")
        } catch (t: Throwable) {
            say("spin. 抛了：${t.javaClass.name}: ${t.message}")
        }
        say("spin. 调用线程还卡着吗 = ${!fut.isDone}")

        val closing = pool.submit<Any?> { runtime.close(); "close() 返回了" }
        try {
            say("spin. ${closing.get(5, TimeUnit.SECONDS)}")
        } catch (e: TimeoutException) {
            say("spin. close() 5 秒没返回 —— 引擎线程被占死，运行时**不能复用**")
        }
    }

    private companion object {
        const val TAG = "PB_PROBE"

        /**
         * 返回**字符串**，不是对象。
         *
         * 这不是偷懒：它正是沙箱打算用的做法 —— 让 JS 自己 `JSON.stringify`，
         * Java 只负责取一个字符串。`toJSONObject()` 那条路要 Java 侧重写一遍
         * 序列化规则（`undefined` 怎么办、嵌套对象怎么办），而那是引擎的活。
         */
        val MODULE_SRC = """
            "use strict";

            const util = require("./lib/util.js");

            let keep = [];

            module.exports = {
              run(input, host) {
                const r = { util: util.tag, input: input.n, tool: host.tool, settings: host.settings };

                try {
                  const o = host.objBack();
                  r.objBackType = typeof o;
                  r.objBackRead = (o && typeof o === "object") ? o.status + "/" + o.body : "(读不到字段)";
                } catch (e) { r.objBackErr = String(e); }

                try {
                  const j = host.jsonBack();
                  r.jsonBackType = typeof j;
                  r.jsonBackRead = (j && typeof j === "object") ? j.status + "/" + j.body : "(读不到字段)";
                } catch (e) { r.jsonBackErr = String(e); }

                try {
                  const s = host.strBack();
                  r.strBackType = typeof s;
                  r.strBackRead = (typeof s === "string") ? JSON.parse(s).body : "(不是字符串)";
                } catch (e) { r.strBackErr = String(e); }

                try {
                  const a = host.arrBack();
                  r.arrBackType = typeof a;
                  r.arrBackIsArray = Array.isArray(a);
                  r.arrBackLen = a.length;
                } catch (e) { r.arrBackErr = String(e); }

                r.echo = host.echo("hi");
                host.log("探针：run 跑完了");
                return JSON.stringify(r);
              },

              allocMb(n) {
                for (let i = 0; i < n; i++) keep.push(new Array(131072).fill(i));
                return keep.length;
              },

              dropAll() {
                keep = [];
                return "丢掉全部引用";
              },

              boom(host) {
                return "宿主没抛：" + host.boom();
              },
            };
        """.trimIndent()

        // **顺序要紧**：companion object 的初始化按声明顺序跑，
        // SOURCES 引用 MODULE_SRC，所以 MODULE_SRC 必须排在前面。
        // 反了不是「拿到 null」，是编译错误 `Variable 'MODULE_SRC' must be initialized`
        val SOURCES = mapOf(
            "index.js" to MODULE_SRC,
            "lib/util.js" to "module.exports = { tag: \"util-ok\" };",
        )

        const val SPIN_SRC = "let i = 0; while (true) { i++; }"
    }
}
