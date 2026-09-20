package com.aichat.sandbox

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.aichat.di.pluginHttpClient
import com.aichat.plugin.runtime.script.ScriptRequest

/**
 * 脚本沙箱。**跑在 `:sandbox` 进程里**（见清单里的 `android:process`）。
 *
 * ## 它的生命周期是「一次调用」
 *
 * 客户端每次调用都 `bindService` → `execute` → `unbindService`，而
 * [onUnbind] 里**直接把自己杀掉**。所以：
 *
 * - 没有「上一次的全局变量还留着」——JS 上下文每次都是新的
 * - 没有「上一次的内存还没还回去」——进程没了，内存自然全还
 * - 超时不需要在进程内做——客户端数到点就断开绑定，这一侧收到 `onUnbind`
 *   就自杀，那个转不停的 `QuickJS-0` 线程跟着进程一起消失
 *
 * ## 为什么自杀放在 [onUnbind]，而不是放一个「延迟 N 毫秒」的定时器
 *
 * 因为 `onUnbind` 的时机是**确定**的：它只在最后一个客户端断开之后被调，
 * 而客户端只会在**收到回复之后**才断开（或者数到超时之后）。
 * 换句话说，「回复已经发出去了」这件事由 `onUnbind` 这个时机本身保证，
 * 不需要猜一个延迟。定时器那种写法要么太短（回复还在路上就被杀了）、
 * 要么太长（进程白白多活一会儿），而且两个方向都不报错。
 *
 * ## 代价
 *
 * 每次调用多一次进程启动（zygote fork + `Application.onCreate` + 加载
 * QuickJS 原生库）。相对一次工具调用（本来就要等模型）可以忽略，
 * 换来的是「每次都是干净环境」这条硬保证。
 */
class ScriptSandboxService : Service() {

    /**
     * 引擎**懒建**：绑定成功但一次都没调用就断开时，不该白白建一个 OkHttp 客户端。
     */
    private val engine by lazy { SandboxEngine(pluginHttpClient(), filesDir) }

    private val binder = object : IScriptSandbox.Stub() {
        override fun execute(requestJson: String): String {
            val request = runCatching {
                SandboxProtocol.json.decodeFromString<ScriptRequest>(requestJson)
            }.getOrElse { t ->
                // 解不开 = 宿主和沙箱不是同一份代码编出来的（理论上不可能）。
                // **不写遗言**：死因文件的语义是「沙箱为什么死了」，
                // 而这里它是活的、正常返回了一句话
                return SandboxProtocol.json.encodeToString(
                    SandboxProtocol.sandboxBrokenOutcome(
                        pluginName = "（未知插件）",
                        detail = "请求解不开：${t.message ?: t::class.simpleName}",
                    ),
                )
            }
            return SandboxProtocol.json.encodeToString(engine.run(request))
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * 最后一个客户端断开 = 这一轮调用结束了（成或败）。**把进程带走。**
     *
     * 用 `killProcess` 而不是 `stopSelf()`：`stopSelf` 只是让系统「可以考虑」
     * 回收这个进程，而这里需要的是**确定**的结束 —— 尤其是超时那条路上，
     * 还有一个 `while(true)` 的引擎线程在烧一个核，只有 SIGKILL 能停下它。
     *
     * 返回 false：不需要 `onRebind`，反正下一次绑定一定是新进程。
     */
    override fun onUnbind(intent: Intent?): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }
}
