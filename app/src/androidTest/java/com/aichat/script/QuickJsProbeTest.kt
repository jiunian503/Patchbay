package com.aichat.script

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quickjs.CommonJSModule
import com.quickjs.JSObject
import com.quickjs.JavaCallback
import com.quickjs.JavaVoidCallback
import com.quickjs.QuickJS
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * QuickJS 引擎探针（第三版）—— **这不是测试，是测量**。
 *
 * ## 前两版教的两件事
 *
 * **一、参数顺序是 `executeScript(source, fileName)`** —— 源码在前、文件名在后。
 * 前两版我把文件名当源码传了，于是 `"pre.js"` 被当成 JS 求值，报
 * `ReferenceError: 'pre' is not defined`，看着像模块解析的毛病，其实是参数传反了。
 *
 * **二、`CommonJSModule.executeModule(name)` 返回的是 CommonJS 的 module 记录，
 * 不是导出。** 包装长这样：
 *
 *     (function () {var module = { exports: {}, children: [] }; <源码>; return module;})();
 *
 * 所以真正的导出在 `module.getObject("exports")` 上。前两版直接拿返回值当 exports，
 * 于是 `keys` 是 `[exports, children, id, filename]`，`executeFunction2` 全返回 null。
 *
 * ## 这一版要回答（前两版都没测到）
 *
 *   1. 普通上下文上 `executeScript` 能不能跑（prelude 的位置）
 *   2. `async run()` 里的 `await` 能不能拿到结果 —— **微任务队列有没有被 drain**
 *   3. `while(true)` 会怎样：挂住引擎线程？挂住调用线程？App 会不会死？
 *   4. 挂住之后 `close()` 还能不能返回（决定引擎能不能复用）
 *
 * 第 2 条决定 `csvstat` 那种写法能不能用；第 3、4 条决定沙箱能不能靠解释器自带的闸
 * （已知三个候选绑定都没有可用的中断入口，所以大概率要靠**进程隔离**）。
 *
 * 输出走 logcat，tag `PB_PROBE`。
 */
@RunWith(AndroidJUnit4::class)
class QuickJsProbeTest {

    private fun say(msg: String) {
        Log.i(TAG, msg)
        println("[PROBE] $msg")
    }

    /** 把源码按模块名喂给 CommonJSModule。`require` 也走这里。 */
    private class ProbeModule(
        runtime: QuickJS,
        private val sources: Map<String, String>,
    ) : CommonJSModule(runtime) {
        override fun getModuleScript(moduleName: String): String =
            sources[moduleName] ?: error("没有这个模块：$moduleName")
    }

    @Test
    @org.junit.Ignore(
        "探针不是测试，默认不跑。它**故意**跑一个 while(true)：那会漏一个永久 RUNNABLE 的 " +
            "QuickJS-0 线程，并让那个运行时彻底作废（连 close() 都不返回）。" +
            "只在需要重新测量时去掉这个注解，并且单独跑这一个类。",
    )
    fun probe() {
        val caller = Thread.currentThread().name
        say("0. 测试线程 = $caller")

        // ── 1. 运行时 + 普通上下文（prelude 的位置）───────────────────────────
        val t0 = System.nanoTime()
        val runtime = QuickJS.createRuntimeWithEventQueue()
        say("1a. ✓ 运行时 = $runtime，建它花了 ${(System.nanoTime() - t0) / 1_000_000} ms")

        val plain = runtime.createContext()
        try {
            say("1b. ✓ executeScript('1 + 1', 'hello.js') = ${plain.executeScript("1 + 1", "hello.js")}")
            plain.executeScript("globalThis.__mark = 'prelude 生效了';", "prelude.js")
            say("1c. ✓ prelude 后读回来 = ${plain.executeScript("__mark", "read.js")}")
        } catch (t: Throwable) {
            say("1b. ✗ 普通上下文失败：${t.javaClass.name}: ${t.message}")
        }

        // ── 2. 宿主能力 + CommonJS 导出 + async/await ────────────────────────
        val jsThread = AtomicReference("（宿主回调一次都没被调到）")
        val module = ProbeModule(runtime, mapOf("index.js" to MODULE_SRC))
        try {
            val host = JSObject(module)
            host.registerJavaMethod(
                JavaCallback { _, args ->
                    jsThread.set(Thread.currentThread().name)
                    "pong:" + args.getString(0)
                },
                "probe",
            )
            host.registerJavaMethod(
                JavaVoidCallback { _, args -> say("       [js log] ${args.getString(0)}") },
                "log",
            )
            val fs = JSObject(module)
            fs.registerJavaMethod(JavaCallback { _, _ -> "（假的 fs.readText 结果）" }, "readText")
            host.set("fs", fs)
            say("2a. ✓ host keys = ${host.keys.toList()}  fs keys = ${fs.keys.toList()}")

            val input = JSObject(module).set("n", 41)

            val record = module.executeModule("index.js")
            say("2b. module 记录 keys = ${record.keys.toList()}")
            val exports = record.getObject("exports")
            say("2b. ✓ exports keys = ${exports.keys.toList()}")

            val sync = exports.executeFunction2("sync", input)
            say("2c. [对照·同步] 类型 = ${sync?.javaClass?.name}  内容 = $sync")
            if (sync is JSObject) {
                say("2c. [对照·同步] JSON = ${runCatching { sync.toJSONObject() }.getOrElse { "✗ $it" }}")
            }

            val result = exports.executeFunction2("run", input, host)
            say("2d. [主角·async] 类型 = ${result?.javaClass?.name}  内容 = $result")
            if (result is JSObject) {
                say("2d. [主角·async] JSON = ${runCatching { result.toJSONObject() }.getOrElse { "✗ $it" }}")
            }
            // 决定性判据：async 函数的收尾有没有真的跑到
            say("2e. 决定性判据 __done = ${runCatching { module.executeScript("__done === true", "check.js") }.getOrElse { "✗ $it" }}")
            say("2e. 宿主回调线程 = ${jsThread.get()}")
            say("2e. 调用线程     = $caller（相同 = JS 跑在调用线程上）")
        } catch (t: Throwable) {
            say("2. ✗ 失败：${t.javaClass.name}: ${t.message}")
        }

        // ── 3. while(true)：放最后，它会漏一个转不停的引擎线程 ──────────────
        val pool = Executors.newSingleThreadExecutor { r ->
            Thread(r, "probe-caller").apply { isDaemon = true }
        }
        val spinCtx = runtime.createContext()
        val fut = pool.submit<Any?> { spinCtx.executeScript(SPIN_SRC, "spin.js") }
        try {
            say("3a. 竟然返回了：${fut.get(8, TimeUnit.SECONDS)}")
        } catch (e: TimeoutException) {
            say("3a. 8 秒没返回 —— 挂住了（这正是要观察的）")
        } catch (t: Throwable) {
            say("3a. 抛了：${t.javaClass.name}: ${t.message}")
        }
        say("3b. 调用线程还卡着吗 = ${!fut.isDone}（done = ${fut.isDone}）")
        dumpThreads()

        // ── 4. 挂住之后 close() 还能不能返回 ────────────────────────────────
        val closing = pool.submit<Any?> { runtime.close(); "close() 返回了" }
        try {
            say("4. ${closing.get(5, TimeUnit.SECONDS)}")
        } catch (e: TimeoutException) {
            say("4. close() 5 秒没返回 —— 引擎线程被占死，运行时**不能复用**")
        } catch (t: Throwable) {
            say("4. close() 抛了：${t.javaClass.name}: ${t.message}")
        }
        dumpThreads()
        say("5. 测试方法正常收尾 —— 测试线程始终没被挂住")
    }

    private fun dumpThreads() {
        say("      ── 线程快照 ──")
        Thread.getAllStackTraces().forEach { (t, frames) ->
            val n = t.name
            if (n.contains("quickjs", true) || n.contains("Handler", true) || n.contains("probe", true)) {
                say("      · $n  state=${t.state}  alive=${t.isAlive}  栈顶=${frames.firstOrNull()}")
            }
        }
    }

    private companion object {
        const val TAG = "PB_PROBE"

        val MODULE_SRC = """
            "use strict";
            module.exports = {
              sync: function (input) {
                return { n: input.n, doubled: input.n * 2, kind: "sync" };
              },
              async run(input, host) {
                host.log("run 进来了");
                let hostSays = "(host 没给)";
                try { hostSays = host.probe("x"); } catch (e) { hostSays = "host 调用失败: " + e; }
                const file = await host.fs.readText("a.csv");
                globalThis.__done = true;
                host.log("run 收尾到了");
                return {
                  n: input.n,
                  sum: input.n + 1,
                  hostSays: hostSays,
                  file: file,
                  kind: "async",
                };
              },
            };
        """.trimIndent()

        const val SPIN_SRC = "let i = 0; while (true) { i++; }"
    }
}
