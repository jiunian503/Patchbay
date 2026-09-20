package com.aichat.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.aichat.plugin.runtime.script.ScriptOutcome
import com.aichat.plugin.runtime.script.ScriptRequest
import com.aichat.plugin.runtime.script.ScriptRuntime
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ScriptRuntime] 的宿主实现：把一次调用交给 `:sandbox` 进程去做。
 *
 * ## 每次调用都是「绑一次、跑一次、断开」
 *
 * 断开会让沙箱的 `onUnbind` 自杀（见 `ScriptSandboxService`），
 * 于是下一次调用从一个全新进程开始。这不是浪费，是**唯一**能让
 * `timeoutMs` 兑现的办法：脚本 `while(true)` 之后，进程内没有任何人能
 * 停下来（连 `runtime.close()` 都不返回，§82），只有杀掉整个进程。
 *
 * 顺带解决了两件事：JS 全局变量不会漏给下一个插件，内存也不会越用越多
 * （实测一个脚本分配 42 MB 再释放，RSS 只还回去 32 MB）。
 *
 * ## 超时**不能**用 `withTimeoutOrNull` 直接包住那个调用
 *
 * AIDL 调用是阻塞的，而**阻塞的代码不是挂起点** —— 协程的取消只在挂起点生效，
 * 所以 `withTimeoutOrNull { sandbox.execute(...) }` 会一直等到调用自己回来，
 * 超时形同虚设，而且不报任何错。真正的做法是把它扔进另一个协程，
 * 对 `await()` 设超时：`await()` 是挂起点，取消得掉。
 *
 * 那个被扔出去的调用不会因此停下 —— 它要等 `finally` 里的 `unbindService`
 * 把沙箱杀掉，才会带着 `DeadObjectException` 结束。所以这里需要一个
 * **隔离的作用域**（见 [calls]）。
 */
class QuickJsSandboxRuntime(
    private val context: Context,
    private val filesDir: File,
) : ScriptRuntime {

    /**
     * 这台设备能不能跑脚本。`null` 表示能。
     *
     * ## 为什么用「页大小」判断，而不是试着加载一次原生库
     *
     * 已知的加载失败只有一种：这个 QuickJS 打包里的 `.so` 只有 4 KB 对齐
     * （实测 8 个 `.so` 全是 `p_align = 0x1000`），而 16 KB 页的设备要求更大的对齐。
     * 那是唯一能用一条**免费**判据预测出来的失败。
     *
     * 换成 `Class.forName("com.quickjs.QuickJS")` 会更彻底（它真的 dlopen 一次），
     * 代价是**宿主进程**也会把那 4.58 MB 的原生库映射进来并跑一遍重定位 ——
     * 而它只在沙箱进程里用得上。为了一个「罕见失败时报告得更准」去让每次冷启动
     * 都付这笔钱，不划算。
     *
     * ## 为什么宁可误判也不去试一次
     *
     * Android 15/16 的 16 KB 设备有一套**向后兼容模式**，理论上可能让这类库跑起来。
     * 那时 `sysconf` 仍然返回 16384，于是这里会判成「不能跑」—— 一次误判。
     * 宿主不做这个赌：赌赢了只是多一个可用功能，赌输了是在**用户的一次工具调用里**
     * 崩掉，而那种崩溃没人看得懂。
     *
     * 其它原因的加载失败（ABI 不支持之类）会以「沙箱没能启动」的形式报出来，
     * 那句话里也写清了「这是宿主的问题」。
     */
    override val unavailableReason: String? = if (PAGE_SIZE_OK) null else PAGE_SIZE_MESSAGE

    /**
     * 跨进程调用串行化。
     *
     * 不是为了线程安全（`execute` 本来就在 IO 调度器上），而是为了**不让一个
     * 卡住的调用把沙箱进程叠起来**：一次工具循环里模型是一个一个调工具的，
     * 真并发了也只是排队。
     */
    private val gate = Mutex()

    override suspend fun execute(request: ScriptRequest): ScriptOutcome {
        if (!available) {
            return ScriptOutcome.Failed(
                "这个宿主没有可用的脚本运行时，无法执行插件「${request.pluginName}」。",
                ScriptOutcome.Kind.Unavailable,
            )
        }
        return withContext(Dispatchers.IO) { gate.withLock { runOnce(request) } }
    }

    private suspend fun runOnce(request: ScriptRequest): ScriptOutcome {
        // 死因文件的生命周期是「一次调用」：先清掉上一次的，失败后再读
        SandboxProtocol.clearDeath(filesDir)

        val payload = runCatching { SandboxProtocol.json.encodeToString(request) }
            .getOrElse {
                return SandboxProtocol.sandboxBrokenOutcome(
                    request.pluginName,
                    "请求编码失败：${it.message}",
                )
            }

        val connection = try {
            bind()
        } catch (t: Throwable) {
            return SandboxProtocol.sandboxBrokenOutcome(
                request.pluginName,
                t.message ?: t::class.simpleName.orEmpty(),
            )
        }

        try {
            val call = calls.async { connection.sandbox.execute(payload) }

            val reply = withTimeoutOrNull(request.timeoutMs) { call.await() }
            if (reply == null) {
                // 不等它回来。下面 finally 的 unbind 会让沙箱自杀，
                // 那个卡住的 AIDL 调用随之结束
                return SandboxProtocol.timeoutOutcome(request.pluginName, request.timeoutMs)
            }

            return runCatching { SandboxProtocol.json.decodeFromString<ScriptOutcome>(reply) }
                .getOrElse {
                    SandboxProtocol.sandboxBrokenOutcome(
                        request.pluginName,
                        "沙箱回了一段读不懂的结果：${it.message}",
                    )
                }
        } catch (t: Throwable) {
            // 调用没回来 = 沙箱死了。它是怎么死的，只有它自己写下的那行字知道
            return SandboxProtocol.deathOutcome(request.pluginName, SandboxProtocol.readDeath(filesDir))
        } finally {
            connection.close()
        }
    }

    /**
     * 绑定沙箱服务。
     *
     * ## 为什么等连接要设超时
     *
     * `bindService` 返回 true 只表示「请求交给了系统」，进程起来要时间。
     * 不设超时的话，沙箱因为任何原因起不来（原生库加载失败、被系统拦）都会
     * 表现为**整轮对话卡死**，而不是一条能读的错误。
     */
    private suspend fun bind(): SandboxConnection {
        // 重试是**安全的**，因为它只包住「绑定」这一段：还没发出任何请求，
        // 所以不存在「脚本被执行两次」的问题。而这正是上面 close() 里那个
        // 「连上了一个正在死的进程」窗口的兜底
        var last: Throwable? = null
        repeat(BIND_ATTEMPTS) { attempt ->
            try {
                return connectOnce()
            } catch (t: Throwable) {
                last = t
                if (attempt < BIND_ATTEMPTS - 1) delay(BIND_RETRY_DELAY_MS)
            }
        }
        throw last ?: IllegalStateException("沙箱进程起不来")
    }

    private suspend fun connectOnce(): SandboxConnection {
        val connected = CompletableDeferred<IScriptSandbox>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                connected.complete(IScriptSandbox.Stub.asInterface(service))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected.completeExceptionally(IllegalStateException("沙箱进程在绑定过程中退出了"))
            }
        }

        val intent = Intent(context, ScriptSandboxService::class.java)
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            error("系统拒绝绑定沙箱服务")
        }

        return try {
            val sandbox = withTimeoutOrNull(BIND_TIMEOUT_MS) { connected.await() }
                ?: error("沙箱进程 ${BIND_TIMEOUT_MS} 毫秒内没有起来")
            SandboxConnection(context, connection, sandbox)
        } catch (t: Throwable) {
            // 绑定失败也要解绑：不解的话系统那边还留着一个待建立的连接，
            // 沙箱进程会起来然后又挂在那里
            runCatching { context.unbindService(connection) }
            throw t
        }
    }

    private class SandboxConnection(
        private val context: Context,
        private val connection: ServiceConnection,
        val sandbox: IScriptSandbox,
    ) {
        /**
         * 解绑，并且**等沙箱真的死透**。
         *
         * ## 「停」这一步就是解绑
         *
         * 沙箱在 `onUnbind` 里自杀，于是无论脚本是正常结束、超时、还是正在
         * `while(true)`，它都会停下来。
         *
         * ## 为什么还要等
         *
         * 因为「解绑」和「进程死掉」之间隔着一次 AMS 的异步调度：`unbindService`
         * 只是把请求交出去，AMS 再去通知沙箱，沙箱才自杀。而**下一次调用紧跟着
         * 就会 `bindService`** —— 那时候 AMS 眼里那个进程可能还活着，于是它把
         * 新的绑定交给一个正在死的进程：`onServiceConnected` 之后立刻
         * `onServiceDisconnected`，而调用方看到的是一句凭空的「沙箱崩了」。
         *
         * 症状会非常费解：**连着用同一个插件，第二次必失败**。所以这里等到
         * `isBinderAlive()` 变 false（进程真的没了）再返回，
         * 下一次绑定就一定是在一个新进程上。
         *
         * 等不到也不报错 —— 上限之后照常返回，由 [bind] 的重试兜底。
         */
        fun close() {
            runCatching { context.unbindService(connection) }

            val binder = sandbox.asBinder()
            val deadline = SystemClock.uptimeMillis() + DEATH_WAIT_MS
            while (binder.isBinderAlive() && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(DEATH_POLL_MS)
            }
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 10_000L

        /** 绑定重试次数。见 [bind] —— 只包住绑定，所以重试不会让脚本跑第二遍。 */
        const val BIND_ATTEMPTS = 3
        const val BIND_RETRY_DELAY_MS = 100L

        /**
         * 等沙箱死透的上限。
         *
         * 正常情况下是几毫秒（`unbindService` → AMS → `onUnbind` → SIGKILL）。
         * 给到 1 秒是因为这个等待**失败也不会报错**：真等不到就交给 [bind] 的重试，
         * 而多等一会儿的代价只是这一次调用慢一点
         */
        const val DEATH_WAIT_MS = 1_000L
        const val DEATH_POLL_MS = 5L

        /**
         * 跑跨进程调用的作用域。
         *
         * ## 为什么单独一个 `SupervisorJob`，而不是直接 `async`
         *
         * 因为**一定有一条「失败但没人 await」的路径**：超时的时候调用方不等了，
         * 而那个调用随后会带着 `DeadObjectException` 结束。普通的 `async`
         * 会把子协程的失败往上冒到父 job —— 也就是把整轮对话取消掉，
         * 而且用户看到的会是「发送失败」这种和真正原因无关的现象。
         *
         * `SupervisorJob` 把这个异常隔离在这个作用域里：它就只是
         * 「一个没人看的 Deferred 失败了」。
         */
        val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val PAGE_SIZE_OK: Boolean = runCatching {
            Os.sysconf(OsConstants._SC_PAGESIZE) < 16 * 1024L
        }.getOrDefault(false)

        /**
         * 16 KB 内存页的设备上，用户读到的那句话。
         *
         * ## 为什么不说「等上游更新」
         *
         * 因为这个库的上游**停在 2021 年**（`io.github.taoweiji.quickjs` 最后一版是
         * 1.4.6，2021-06-19），而 `.so` 的 4 KB 对齐是编译期定死的 ——
         * 要修只能自己重编一份 JNI 绑定，那正是 §82 权衡之后不做的事。
         * 所以这里给的是**现在就能走的两条路**：换一台 4 KB 内存页的设备，
         * 或者改用声明式 / MCP 形态的插件（那两个不碰原生库）。
         *
         * 说「等宿主升级」是不诚实的：宿主升级也救不了它。
         */
        const val PAGE_SIZE_MESSAGE =
            "这台设备的内存页是 16 KB，脚本引擎的原生库只按 4 KB 对齐，" +
                "在这类设备上加载它可能把宿主进程弄崩 —— 所以宿主关掉了脚本运行时。" +
                "这个插件的清单是合法的：在 4 KB 内存页的设备上可以直接用，" +
                "也可以改用声明式或 MCP 形态的插件。"
    }
}
