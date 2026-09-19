package com.aichat

import android.app.Application
import com.aichat.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 进程入口。只做三件事：建依赖容器、跑一次启动清理、把已装插件装进工具列表。
 *
 * ## 为什么不用 DI 框架
 *
 * 依赖图是**一棵树**：DB → DAO → Repository → Container，没有任何
 * 「运行时才知道要注入什么」的场景。手写一个 [AppContainer] 就够了，
 * 而且能一眼看全对象的生命周期。等出现多实现切换、作用域嵌套这类需求
 * 再考虑引入 Hilt —— 那时手写才会真的开始难受。
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
        container = AppContainer(this)
        appScope.launch {
            container.recoverInterruptedMessages()
            // 装配失败不该让 App 起不来 —— 那时用户连「去插件页看看出了什么问题」
            // 的入口都没有。所以这里吞掉异常，插件列表页会把问题显示出来
            runCatching { container.tools.refresh() }
        }
    }
}
