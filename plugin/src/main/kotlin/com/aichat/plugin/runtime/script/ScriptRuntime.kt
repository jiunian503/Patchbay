package com.aichat.plugin.runtime.script

import com.aichat.plugin.manifest.FilesystemScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
     * 跑一次工具调用。
     *
     * ## 它**一定阻塞**，所以实现必须是进程外沙箱
     *
     * 解释器内部没有任何可用的中断入口：三个候选的 QuickJS 绑定都没有
     * `JS_SetMemoryLimit` 的出口，唯一的中断实现 `EventQueue.interrupt()` 是
     * 包级私有的。真机实测 `while(true)` 会把引擎线程永久占死、连 `close()`
     * 都不返回（§82）。所以 [ScriptRequest.timeoutMs] 与
     * [ScriptRequest.memoryLimitMb] 只能靠**进程隔离**兑现 ——
     * 超时杀进程、内存靠沙箱自己盯用量自杀。进程内实现这两个字段就是空头支票。
     *
     * ## 它为什么是 `suspend`
     *
     * 这里上一版写着「这个函数为什么不是 suspend 的」—— **那句话和签名自相矛盾**
     * （签名一直是 `suspend`），而它想表达的意思是对的，只是说反了方向。
     *
     * 真正该说的是：**`suspend` 不表示它不阻塞。** 它一定会阻塞到脚本跑完、
     * 超时被杀、或者沙箱进程死掉。加 `suspend` 只是因为调用链本来就是挂起的
     * （`Tool.execute` 是 suspend），实现没必要为此硬造一个阻塞签名再让调用方包一层。
     *
     * 线程契约由 `Tool.execute` 那边给：引擎在**后台调度器**上调用，
     * 所以实现里可以直接等一个跨进程的回复，不需要自己 `withContext`。
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
 *
 * ## `@Serializable` 不是顺手加的
 *
 * 上面那句「最好是同一份定义」原来只是注释里的愿望。加上这个注解之后它才是
 * **类型上的事实**：客户端把 `ScriptRequest` 编码成一段 JSON 塞进 binder，
 * 沙箱解出**同一个类**。中间没有任何手写的字段搬运，所以「少一个字段」
 * 这种错在编译期就不存在，而不是靠两边对齐。
 */
@Serializable
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
     *
     * ## 这一轮它还没有消费者
     *
     * 工作区本身（目录在哪、卸载时怎么清）是另一件事，排在沙箱之后。
     * 所以当前 `:app` 的沙箱**不注入 `host.fs`**：声明了 `filesystem` 的插件
     * 调用 `host.fs.readText` 会拿到一个 `TypeError`。这条不做掩饰 ——
     * 写在字段上，而不是让它看起来「已经支持了」。
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
 * 因为**下一步该做什么不一样**：超时和内存超限换个参数也一样挂（别重试），
 * 脚本自己抛的错则可能改个参数就好了。混成一句「执行失败」的话，
 * 宿主就再也分不清这两种情况 —— 而这正是 `NetworkDeniedException` 与
 * `IOException` 要分开的同一个理由（§45）。
 *
 * 分类的划界依据是**下一步**，不是「哪一层抛的」。
 */
@Serializable
sealed interface ScriptOutcome {

    /** 脚本返回了可 JSON 序列化的值。 */
    @Serializable
    @SerialName("ok")
    data class Ok(val json: String) : ScriptOutcome

    /** 失败。[message] 是**给模型看**的一句话，要写成人能看懂的话。 */
    @Serializable
    @SerialName("failed")
    data class Failed(val message: String, val kind: Kind) : ScriptOutcome

    /**
     * 失败分类。**给宿主看的**（诊断、日志、以后可能给界面）。
     *
     * ## 模型看不到这个枚举 —— 它只看 [Failed.message]
     *
     * `ScriptTool` 把结果交给 `ToolResult.error(message)`，那条路上没有位置放分类。
     * 所以**每一句 message 自己必须把「能不能重试」说清楚**，不能指望模型
     * 从这个枚举里推断 —— 它拿到的只有一句话。
     *
     * 这一点是写到这里才看清楚的：这个枚举最早的理由写的是「模型拿到之后要做的事
     * 不一样」，而实现落地时才发现模型根本拿不到它。枚举仍然有用（宿主自己的账本、
     * 以后给界面看），但它的读者要说清楚是宿主。
     */
    enum class Kind {
        /**
         * 超过 `timeoutMs`，沙箱已被杀。
         *
         * **别重试** —— 同样的输入会同样地超时。要么把参数改小（少处理一些数据），
         * 要么让作者调大清单里的 `timeoutMs`。
         */
        Timeout,

        /**
         * 超过 `memoryLimitMb`，沙箱已自杀。
         *
         * **别重试**，换个思路 —— 或者让作者调大清单里的 `memoryLimitMb`。
         */
        OutOfMemory,

        /**
         * 沙箱进程**意外终止**：它没留下死因就死了。
         *
         * ## 判据就是「有没有死因」
         *
         * 超时和内存超限这两条路都会**先写下死因再自杀**，所以沙箱的死亡
         * 分得干干净净：有死因 = 我们杀的，没有死因 = 它自己崩的
         * （引擎的 native 崩，或者吃掉太多内存被系统杀掉）。
         *
         * **别重试** —— 这是插件的问题，不是这次参数的问题。
         */
        Crashed,

        /** 宿主没装脚本运行时。让用户等宿主升级。 */
        Unavailable,

        /** 脚本自己抛的错（含返回值不是 JSON、入口没导出 `run`）。**可以改参数重试。** */
        ScriptError,
    }
}
