package com.aichat.di

import com.aichat.core.data.PluginRepository
import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolRegistry
import com.aichat.plugin.host.PluginRegistry
import com.aichat.plugin.runtime.script.ScriptRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * 当前生效的工具集合，**可以被换掉**。
 *
 * ## 为什么需要这一层
 *
 * [AppContainer.tools] 是 `by lazy` 的、同步构造的，而插件列表要读数据库
 * 和 AndroidKeyStore（都是挂起操作）。于是装配不可能在容器构造时完成。
 *
 * 更要紧的是：**装完插件必须立刻生效**。如果注册表只在进程启动时建一次，
 * 用户装完插件得杀进程重开才能用 —— 而且他不会知道要这么做，
 * 只会觉得「装了这个插件但模型还是不调用它」。
 *
 * 所以这个类持有一个**可替换**的注册表：启动时先只有内置工具，
 * 数据库读完就换成「内置 + 插件」；之后每次安装/启停/卸载都再换一次。
 *
 * ## 线程安全
 *
 * 对话引擎在后台调度器上每轮都会调 [definitions]，而 [refresh] 在另一条
 * 协程上跑。`current` 用 `@Volatile` 保证可见性 —— 换注册表这件事本身
 * 是「换一个引用」，不需要锁；而**构造新注册表**的过程不能并发，
 * 否则两次 refresh 交错会浪费解析和 KeyStore 解密，还可能让后到的
 * 旧结果覆盖新结果。所以构造过程用 [Mutex] 串起来。
 */
class ToolRegistryHolder(
    /**
     * **函数而不是列表** —— 内置工具会随设置变化。
     *
     * 长期记忆关掉时 `search_history` 不该注册（见 `BuiltinTools.all`）。
     * 拿一个固定列表的话，用户关掉开关后模型仍然看得到那个工具，
     * 直到杀进程重开 —— 而他会以为「关了没生效」。
     */
    private val builtins: () -> List<Tool>,
    private val plugins: PluginRepository,
    private val client: OkHttpClient,

    /**
     * 脚本运行时的宿主实现。
     *
     * 它一路透传到 [PluginRegistry.build] —— 装配期要回答的问题之一是
     * 「这台设备能不能跑脚本」，而答案是**由宿主提供的**（`:plugin` 是纯 JVM，
     * 自己碰不到 QuickJS）。默认值让不需要脚本的调用点不用知道它的存在。
     */
    private val scripts: ScriptRuntime = ScriptRuntime.Unavailable,
) : ToolRegistry {

    private val refreshLock = Mutex()

    /**
     * 启动时先只有内置工具。
     *
     * 不先建成 `Empty` 而是建成「内置工具」很重要：数据库读失败时
     * App 至少还能用内置工具干活，而不是变成一个只会聊天的模型。
     */
    @Volatile
    private var current: PluginRegistry = PluginRegistry.builtinsOnly(builtins())

    /** 当前的完整注册表。插件管理界面要用它看冲突和装配问题。 */
    val registry: PluginRegistry get() = current

    /**
     * 重新装配。
     *
     * 三个触发点：启动读完插件表、插件的安装/启停/卸载、**长期记忆开关被切换**。
     *
     * @return 新的注册表，方便调用方（比如插件管理页）立刻拿到冲突列表。
     */
    suspend fun refresh(): PluginRegistry = refreshLock.withLock {
        val installed = plugins.installed()
        PluginRegistry.build(builtins(), installed, client, scripts).also { current = it }
    }

    override fun definitions(): List<ToolDefinition> = current.definitions()

    override fun find(name: String): Tool? = current.find(name)
}
