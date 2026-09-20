package com.aichat.plugin.runtime.script

import com.aichat.plugin.manifest.FilesystemScope

/**
 * 脚本插件的运行时 —— 宿主必须提供的两件事：**读源码** 和 **执行**。
 *
 * ## 为什么是一个窄接口，而不是直接引 QuickJS
 *
 * 因为 `:plugin` 必须保持**纯 JVM**（§44 铁律）。QuickJS 的 Android 绑定是带 `.so`
 * 的 AAR，引进来整个模块就变成 Android library，所有插件测试从秒级变分钟级。
 * 所以这里只声明契约，实现放在 `:app` —— 和 `DeviceInfoSource` / `WebSearchSource`
 * 由 `:app` 注入给 `:tools` 是同一个模式（这已经是第二次用了）。
 *
 * ## 为什么「读源码」和「执行」在同一个接口里
 *
 * 因为它们是同一件事的两半，而且**失败时要给的建议不同**：
 * 「宿主没装脚本引擎」和「插件的入口文件读不到」是两条完全不同的出路，
 * 而装配层要能区分它们。放在一起，装配层只需要多一个参数
 * （`PluginHost.tools` 的签名现在只有三样，值得保住）。
 *
 * ## ⚠️ `available = false` 时，装配层**不能**先读源码
 *
 * [Unavailable] 的 [readSource] 返回 null。如果装配层「先读源码、读不到就报
 * 入口文件缺失」，用户看到的会是「入口文件读不到」—— 而真实原因是这个宿主
 * 压根没有脚本运行时，建议完全不同（前者让他去查文件，后者让他等宿主升级）。
 * 所以装配层**必须先看 [available]**。
 */
interface ScriptRuntime {

    /** 这个宿主能不能跑脚本。false 时 [execute] 一律返回 [ScriptOutcome.Kind.Unavailable]。 */
    val available: Boolean

    /**
     * 读插件目录下的一个文件。返回 null 表示**没有这个文件**。
     *
     * 只接受相对路径，且由实现负责挡住 `..` —— 这是「读用户文件」那条边界
     * （§45）在脚本形态下的落点。读不到就返回 null，不要抛。
     */
    fun readSource(pluginId: String, path: String): String?

    /**
     * 跑一次工具调用。**可能长时间阻塞**（脚本是同步的，见下），调用方负责切线程。
     *
     * ## 这个函数为什么不是 suspend 的
     *
     * 因为沙箱实现要跨进程等一个回复，而它本来就该在后台调度器上跑
     * （`Tool.execute` 的线程契约保证了这一点）。写成 suspend 会让人以为
     * 它不阻塞 —— 而它一定会阻塞到脚本跑完或者超时。
     *
     * ## 超时和内存上限**必须**由实现保证
     *
     * 解释器内部没有任何可用的中断入口：三个候选的 QuickJS 绑定都没有
     * `JS_SetMemoryLimit` 的出口，唯一的中断实现 `EventQueue.interrupt()` 是
     * 包级私有的。真机实测 `while(true)` 会把引擎线程永久占死、连 `close()`
     * 都不返回（§82）。所以 [ScriptRequest.timeoutMs] 与
     * [ScriptRequest.memoryLimitMb] 只能靠**进程隔离**兑现 ——
     * 超时杀进程、内存靠沙箱自己盯堆用量自杀。
     *
     * 换句话说：**实现必须是进程外沙箱**。进程内实现这两个字段就是空头支票。
     */
    suspend fun execute(request: ScriptRequest): ScriptOutcome

    companion object {
        /**
         * 宿主没装脚本运行时。默认值 —— 现有 25 处装配调用点因此不用改，
         * 而且「没接引擎」时用户看到的仍是明确的「宿主还不支持」，
         * 不是静默的空工具列表（§47 的老规矩）。
         */
        val Unavailable: ScriptRuntime = object : ScriptRuntime {
            override val available: Boolean = false

            override fun readSource(pluginId: String, path: String): String? = null

            override suspend fun execute(request: ScriptRequest): ScriptOutcome =
                ScriptOutcome.Failed(
                    "这个宿主没有脚本运行时，无法执行插件「${request.pluginName}」。",
                    ScriptOutcome.Kind.Unavailable,
                )
        }
    }
}

/**
 * 一次脚本调用的全部输入。
 *
 * 刻意是**扁平的数据**而不是若干个对象：它要跨进程（沙箱在另一个进程里），
 * 而「能跨进程的东西」和「能序列化成 JSON 的东西」最好是同一份定义 ——
 * 否则迟早出现「本地跑得通、过到沙箱就少一个字段」。
 *
 * [source] 在装配期就读好放进来了（而不是执行时再读）：这样「入口文件缺失」
 * 会变成一条**装配期问题**显示在插件详情页，而不是等用户第一次调用时才炸。
 */
data class ScriptRequest(
    val pluginId: String,
    val pluginName: String,

    /** 入口文件名，如 `index.js`。只用于报错信息与日志。 */
    val entryFile: String,

    /** 入口文件的源码，装配期读好。 */
    val source: String,

    /** 这次要调的是哪个工具。一个插件的多个工具可以共用一个 `run`，靠它分派。 */
    val toolName: String,

    /** 模型填好的参数，已经是 JSON 文本。 */
    val inputJson: String,

    /** 用户填的配置。**只含有值的键** —— 空串不该出现在 JS 里（和 `PluginSettings.value` 同一条规则）。 */
    val settings: Map<String, String>,

    /** 网络白名单。沙箱必须用它建 `NetworkGuard`，`host.http` 逐跳过它。 */
    val network: List<String>,

    /** 文件系统范围。沙箱据此决定 `host.fs` 存不存在。 */
    val filesystem: FilesystemScope,

    /** 墙钟上限。靠**杀沙箱进程**兑现。 */
    val timeoutMs: Long,

    /** 堆上限。靠沙箱自己盯用量自杀兑现。 */
    val memoryLimitMb: Int,
)

/**
 * 一次脚本调用的结果。
 *
 * ## 为什么失败要分类
 *
 * 因为模型拿到之后要做的事不一样：超时和内存超限**不该重试**（同样的输入会
 * 同样地挂），脚本自己抛的错**可以**让它改参数重试，宿主没装引擎则要让它
 * 直接告诉用户。混成一句「执行失败」的话，模型会对一个必然超时的调用反复重试
 * —— 这正是 `NetworkDeniedException` 与 `IOException` 要分开的同一个理由（§45）。
 */
sealed interface ScriptOutcome {

    /** 脚本返回了可 JSON 序列化的值。 */
    data class Ok(val json: String) : ScriptOutcome

    /** 失败。[message] 是**给模型看**的一句话，要写成人能看懂的话。 */
    data class Failed(val message: String, val kind: Kind) : ScriptOutcome

    enum class Kind {
        /** 超过 `timeoutMs`，沙箱已被杀。**别重试。** */
        Timeout,

        /** 超过 `memoryLimitMb`，沙箱已自杀。**别重试**，换个思路。 */
        OutOfMemory,

        /** 宿主没装脚本运行时。让用户等宿主升级。 */
        Unavailable,

        /** 脚本自己抛的错（含返回值不是 JSON、入口没导出 `run`）。可以改参数重试。 */
        ScriptError,
    }
}
