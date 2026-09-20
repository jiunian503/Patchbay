package com.aichat.plugin.runtime.script

import com.aichat.plugin.manifest.FilesystemScope

/**
 * 脚本插件的运行时 —— 宿主必须提供的**一件事**：把它跑起来。
 *
 * ## 为什么是一个窄接口，而不是直接引 QuickJS
 *
 * 因为 `:plugin` 必须保持**纯 JVM**（§44 铁律）。QuickJS 的 Android 绑定是带 `.so`
 * 的 AAR，引进来整个模块就变成 Android library，所有插件测试从秒级变分钟级。
 * 所以这里只声明契约，实现放在 `:app` —— 和 `DeviceInfoSource` / `WebSearchSource`
 * 由 `:app` 注入给 `:tools` 是同一个模式（这已经是第二次用了）。
 *
 * ## 上一轮这里还有一个 `readSource`，现在没有了
 *
 * 当时的想法是「读源码」和「执行」是同一件事的两半，放一起能让装配层区分
 * 「宿主没装引擎」和「插件的入口文件读不到」。**那个想法是错的**，两处都错：
 *
 * 1. **源码不该由引擎提供。** 它现在跟着清单文档走（见 `PluginManifest.files`），
 *    那是**存储**的职责，而存储归数据层 —— `InstalledPlugin` 里本来就带着它。
 *    让引擎去读文件，等于把「插件存在哪」塞进一个只该管「怎么跑」的接口。
 * 2. **那两条失败本来就该在不同的层。** 「入口文件读不到」的真实成因是
 *    **文档自己不自洽**（声明了 `main` 却没把那个文件带进来），所以它现在是
 *    `ManifestParser` 的一条**校验错误** —— 安装时就报出来，而且只有作者能修。
 *    「宿主没装引擎」则仍然是装配期问题（用户等宿主升级）。分层比塞进一个接口更清楚。
 *
 * 于是这个接口只剩一件事，也顺带说明一条判据：**窄接口要窄到只剩它独有的职责**。
 * 凡是别人也能提供的（比如「文件在哪」），就不该出现在这里。
 */
interface ScriptRuntime {

    /** 这个宿主能不能跑脚本。false 时 [execute] 一律返回 [ScriptOutcome.Kind.Unavailable]。 */
    val available: Boolean

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
         * 宿主没装脚本运行时。默认值 —— 现有装配调用点因此不用改，
         * 而且「没接引擎」时用户看到的仍是明确的「宿主还不支持」，
         * 不是静默的空工具列表（§47 的老规矩）。
         */
        val Unavailable: ScriptRuntime = object : ScriptRuntime {
            override val available: Boolean = false

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
 */
data class ScriptRequest(
    val pluginId: String,
    val pluginName: String,

    /** 入口文件名，如 `index.js`。沙箱从 [files] 里按这个键取源码。 */
    val entryFile: String,

    /**
     * 插件自带的**全部**文件（相对路径 → 内容），就是 `PluginManifest.files`。
     *
     * ## 为什么是整份，而不是只给入口那一份
     *
     * 因为多文件插件是正常写法：清单的路径规则明确允许子目录
     * （`lib/util.js` 就是那边举的例子）。只给入口的话，`require('./lib/util.js')`
     * 无处可解 —— **允许写却跑不起来**，是最难查的那种不一致。
     *
     * 整份过去还有一个更好的副作用：沙箱不需要**任何**文件系统访问就能跑一个插件。
     * 于是「读用户文件」那条边界（§45）在脚本形态下压根不存在，
     * 而不是靠沙箱里的一道守卫去挡。
     *
     * 体积不是问题：上限 256 KB（见 `ManifestParser` 的 `FILES_TOTAL_MAX`），
     * 而它本来就要跨一次进程。
     */
    val files: Map<String, String>,

    /** 这次要调的是哪个工具。一个插件的多个工具可以共用一个 `run`，靠它分派。 */
    val toolName: String,

    /** 模型填好的参数，已经是 JSON 文本。 */
    val inputJson: String,

    /** 用户填的配置。**只含有值的键** —— 空串不该出现在 JS 里（和 `PluginSettings.value` 同一条规则）。 */
    val settings: Map<String, String>,

    /** 网络白名单。沙箱必须用它建 `NetworkGuard`，`host.http` 逐跳过它。 */
    val network: List<String>,

    /**
     * 文件系统范围。沙箱据此决定 `host.fs` 存不存在。
     *
     * 它管的是插件的**运行时工作区**（一个能写东西的目录），而不是随包带来的源码 ——
     * 后者走 [files]，就在内存里，沙箱不碰文件系统就能读到。
     * 两者分开之后，「读」和「写」才是两个可以分别授权的动作。
     */
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
