package com.aichat

import android.app.Application
import com.aichat.crash.CrashContext
import com.aichat.crash.CrashStore
import com.aichat.crash.installCrashHandler
import com.aichat.di.AndroidDeviceInfo
import com.aichat.di.AppContainer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 进程入口。只做四件事：装崩溃留痕、建依赖容器、跑一次启动清理、
 * 把已装插件装进工具列表。
 *
 * ## 为什么不用 DI 框架
 *
 * 依赖图是**一棵树**：DB → DAO → Repository → Container，没有任何
 * 「运行时才知道要注入什么」的场景。手写一个 [AppContainer] 就够了，
 * 而且能一眼看全对象的生命周期。等出现多实现切换、作用域嵌套这类需求
 * 再考虑引入 Hilt —— 那时手写才会真的开始难受。
 *
 * ## 崩溃留痕为什么排在最前面
 *
 * 它是**唯一一件「必须早于其他所有事」的事**。容器构造里要碰数据库、
 * AndroidKeyStore、OkHttp，装配工具列表还要读插件表 —— 这些在个别 ROM 上
 * 都可能炸，而那种「冷启动就崩」的崩溃恰恰是最需要堆栈的一类（用户除了
 * 「打开就闪退」什么也说不出来）。装晚了就抓不到。
 *
 * 详见 `com.aichat.crash` 那两个文件的 KDoc：报告只写在本机、不联网、
 * 不记任何应用状态。
 *
 * ## 启动清理为什么要在这里做
 *
 * 上次被杀进程时可能留下 `status = 'streaming'` 的消息。它会被排除在
 * 上下文之外（见 `MessageDao.recentIn`），界面上也会一直显示「正在输入」。
 * 越早修掉越好，而且这件事与任何界面无关，放在进程启动点最合适。
 *
 * ## 插件为什么异步装
 *
 * 读插件列表要碰数据库和 AndroidKeyStore，不能放在 `onCreate` 的主线程上。
 * 所以容器先给出一个「只有内置工具」的注册表，这里读完数据库再换掉。
 * 代价是**冷启动后的头几百毫秒内插件工具还不可见** —— 但那个窗口里
 * 用户还没来得及打字，而换来的是冷启动不必等两次磁盘 I/O。
 *
 * 两件事串在**同一条协程**里而不是各起一条：它们都要碰数据库，
 * 而且清理要在插件列表被读之前完成（一条 `streaming` 的残留消息
 * 会让会话看起来还在跑）。串起来顺序就是确定的。
 */
class PatchbayApp : Application() {

    lateinit var container: AppContainer
        private set

    /** 只在进程级任务上用。UI 相关的协程一律走 viewModelScope。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 沙箱进程到这里就结束。**下面三件事它一件都不该做**：
        //
        // 1. 建 [AppContainer] —— 那要碰数据库、AndroidKeyStore、OkHttp，
        //    而沙箱只负责跑一段脚本。它的 RSS 基线就是内存预算的起点，
        //    多加载一整套依赖会白白吃掉插件的额度
        // 2. 装崩溃留痕 —— 沙箱**本来就会崩**（插件把引擎搞崩是它要挡住的事），
        //    把那些记进崩溃列表只会把真正的 App 崩溃淹掉
        // 3. 启动清理 —— 那是主进程的事，两个进程同时做会互相打架
        //
        // 于是 [container] 在这个进程里始终是未初始化的。这不是疏漏：
        // 沙箱进程只会走到 `ScriptSandboxService`，而它不碰容器。
        // 真有人在这里引用了容器，会拿到一个明确的 `UninitializedPropertyAccessException`，
        // 而不是一个「为什么沙箱里也在读数据库」的谜题
        if (isSandboxProcess()) return

        installCrashLogging()
        container = AppContainer(this)
        appScope.launch {
            container.recoverInterruptedMessages()
            // 装配失败不该让 App 起不来 —— 那时用户连「去插件页看看出了什么问题」
            // 的入口都没有。所以这里吞掉异常，插件列表页会把问题显示出来
            runCatching { container.tools.refresh() }
        }
    }

    /**
     * 装崩溃留痕。**必须排在 [AppContainer] 之前**，理由见类注释。
     *
     * 元信息包在 lambda 里，是因为它要到**崩溃那一刻**才求值：版本号要读
     * `PackageManager`、机型要读 `Build`，在 `onCreate` 里先读一遍等于给
     * 每次冷启动都加一次没必要的主线程 I/O（而且绝大多数启动不会崩，
     * 那些读到的值全都白读了）。
     *
     * 复用 [AndroidDeviceInfo] 而不是另写一份「读版本号」的代码：它已经是
     * 这个仓库里唯一一处读 `versionName` / `Build.MODEL` 的地方，
     * 再写一份迟早会和它给出不一样的机型名。
     */
    private fun installCrashLogging() {
        val info = AndroidDeviceInfo(this)
        installCrashHandler(
            store = CrashStore(File(filesDir, CrashStore.DIR_NAME)),
            context = {
                CrashContext(
                    appVersion = info.appVersion(),
                    deviceModel = info.deviceModel(),
                    osVersion = info.osVersion(),
                )
            },
        )
    }

    /** 我是不是跑在脚本沙箱那个进程里。见 [SANDBOX_PROCESS_SUFFIX]。 */
    private fun isSandboxProcess(): Boolean = getProcessName().endsWith(SANDBOX_PROCESS_SUFFIX)

    companion object {
        /**
         * 沙箱进程名的后缀。
         *
         * **必须和 `AndroidManifest.xml` 里 `<service>` 的 `android:process=":sandbox"` 一致。**
         * XML 里引用不了 Kotlin 常量，所以这只能靠人保持同步 ——
         * 两处都写了这条注释，改的时候记得一起改。
         *
         * 对不上的后果是**沙箱进程会当成主进程启动**：建容器、读数据库、
         * 装崩溃留痕全都跑一遍，而沙箱本来只想跑一段脚本。
         * 症状是「插件的内存额度莫名其妙少了一大截」和「崩溃列表里
         * 全是插件的崩」—— 两个都不好查，所以这条注释写在这里。
         */
        const val SANDBOX_PROCESS_SUFFIX = ":sandbox"
    }
}
