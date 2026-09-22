package com.aichat.sandbox

import com.aichat.domain.text.errorDetail
import com.aichat.plugin.runtime.script.ScriptOutcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.serialization.json.Json

/**
 * 宿主和沙箱之间那一份约定：怎么编解码，以及**沙箱怎么告诉宿主自己为什么死的**。
 *
 * ## 为什么死因要走文件，而不是走返回值
 *
 * 因为要报死因的两种情况（内存超限、引擎崩）发生时，**沙箱已经不能回话了**：
 * 前者是它自己 SIGKILL 自己，后者是它根本没走到返回那一步。跨进程唯一
 * 可靠的「遗言」就是**在死之前往一个双方都能看见的地方写一行字**。
 *
 * 两边是同一个 App、同一个 UID、同一个 `filesDir`，所以这个文件不需要任何权限，
 * 也不会被别的 App 看见。
 *
 * ## 死因文件的生命周期是「一次调用」
 *
 * 客户端在**每次调用之前**先删掉它，调用失败后再读。所以：
 *
 * - 有死因 → 是我们（或沙箱自己）**按规矩杀的**，按死因分类
 * - 没有死因 → 沙箱**意外死了**（native 崩、被系统 LMK 杀），分类为 [ScriptOutcome.Kind.Crashed]
 *
 * 这条判据只有在「每次调用都从干净进程开始」时才成立 —— 而沙箱确实是
 * 一次调用一个进程（见 `ScriptSandboxService.onUnbind`）。所以这个文件
 * 不需要任何过期、时间戳、调用 id 之类的东西。
 *
 * ## 这个类**不碰 Android**
 *
 * 它是 `:app` 里少数能进 JVM 单测的部分（编解码、死因读写、失败映射），
 * 而真正需要设备才能验的是 QuickJS 那一层。分开之后，
 * 「文件里写了 oom、客户端读成了 crash」这种错能在秒级的单测里被抓住。
 */
object SandboxProtocol {

    /**
     * 编解码用的 [Json]。**不设 `ignoreUnknownKeys`** ——
     * 两边是同一个 APK 编出来的，字段对不上就说明代码有问题，
     * 那时候安静地吞掉未知字段只会把问题推到更晚、更难查的地方。
     */
    val json: Json = Json

    private const val DIR = "sandbox"
    private const val DEATH = "death.txt"

    /** 内存超限，沙箱自杀。 */
    const val DEATH_OOM = "oom"

    fun deathFile(filesDir: File): File = File(File(filesDir, DIR), DEATH)

    /** 每次调用之前清掉上一次的遗言。见类注释里的生命周期说明。 */
    fun clearDeath(filesDir: File) {
        deathFile(filesDir).delete()
    }

    /**
     * 写下死因。**必须 fsync。**
     *
     * 下一行代码就是 SIGKILL，而 SIGKILL 不给任何机会刷缓冲。
     * 少了这个 `sync()`，客户端读到的会是一个空文件，
     * 于是「内存超限」被报成「引擎崩了」—— 一个把用户引向完全错误方向的诊断。
     */
    fun writeDeath(filesDir: File, reason: String) {
        val file = deathFile(filesDir)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            out.write(reason.toByteArray())
            out.flush()
            out.fd.sync()
        }
    }

    /** 读死因。没有、读不出来、或者是空的，一律返回 null（= 意外死亡）。 */
    fun readDeath(filesDir: File): String? = runCatching {
        deathFile(filesDir).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
    }.getOrNull()

    /**
     * 沙箱没把回复交回来时，给模型的那句话。
     *
     * ## 两句都写「别重试」，但理由不同
     *
     * 内存超限是**这次的数据太大**，改小参数可能就好了；意外崩溃是**这个插件有毒**，
     * 同样的调用会再崩一次。模型能做的动作一样（别重试），但它该告诉用户的话不一样 ——
     * 所以两句都要说清楚「下一步是什么」，而不是只给一个分类名。
     */
    fun deathOutcome(pluginName: String, reason: String?): ScriptOutcome.Failed = when (reason) {
        DEATH_OOM -> ScriptOutcome.Failed(
            "插件「$pluginName」的脚本用掉了超过清单允许的内存，已被宿主终止。" +
                "别用同样的参数重试 —— 要么让它一次处理少一点数据，" +
                "要么请作者调大清单里的 memoryLimitMb。",
            ScriptOutcome.Kind.OutOfMemory,
        )

        else -> ScriptOutcome.Failed(
            "插件「$pluginName」的脚本把沙箱进程弄崩了（宿主没有主动终止它）。" +
                "同样的调用大概率会再崩一次，别重试 —— 请把这个插件的名字报给作者。",
            ScriptOutcome.Kind.Crashed,
        )
    }

    /**
     * 超时那句话。
     *
     * 和 [deathOutcome] 分开：超时是**客户端**判定的（它自己数着墙钟，然后断开绑定），
     * 沙箱那时还在转、根本来不及留遗言。所以这条不需要读文件，
     * 也因此不会和「意外死亡」混淆。
     */
    fun timeoutOutcome(pluginName: String, timeoutMs: Long): ScriptOutcome.Failed =
        ScriptOutcome.Failed(
            "插件「$pluginName」的脚本跑了超过 ${timeoutMs / 1000} 秒还没结束，已被宿主终止。" +
                "别用同样的参数重试 —— 要么让它处理少一点数据，" +
                "要么请作者调大清单里的 timeoutMs。",
            ScriptOutcome.Kind.Timeout,
        )

    /** 沙箱进程起不来（绑定失败、解不开请求）。和「脚本出错」是两回事，所以单独一句话。 */
    fun sandboxBrokenOutcome(pluginName: String, detail: String): ScriptOutcome.Failed =
        ScriptOutcome.Failed(
            "跑插件「$pluginName」的沙箱没能启动：$detail。这是宿主的问题，不是插件的问题 —— " +
                "重试一次，如果一直这样请反馈给宿主作者。",
            ScriptOutcome.Kind.Unavailable,
        )

    /**
     * 脚本自己抛的错（语法错、抛异常、没导出 `run`、返回值不能序列化）。
     *
     * ## 为什么动作必须写在这句话里
     *
     * 分类是 `ScriptError`，而它的 KDoc 写着「可以改参数重试」—— 但**模型看不到
     * 分类**，它只拿到 `ToolResult.error(message)` 这一个字符串
     * （见 `ScriptOutcome.Kind` 的 KDoc）。分类说得再准，模型也读不到。
     *
     * ## 为什么不能只写「可以改参数重试」
     *
     * 脚本崩有两种原因，宿主**分不出来**：
     *
     * - 参数不符合脚本的预期（模型改一下就能过）
     * - 插件自己的 bug（改多少次都一样）
     *
     * 所以给的是「试一次 + 不行就换」，而不是替插件打包票说「改参数就好了」（§110）。
     *
     * ⚠️ 这句和「缺模块」那条（`SandboxEngine.missingModule`）**动作相反**，
     * 尽管两者同为 `ScriptError` —— 那边是插件少带了文件，改参数不会让文件出现。
     */
    fun scriptErrorOutcome(pluginName: String, detail: String): ScriptOutcome.Failed =
        ScriptOutcome.Failed(
            "插件「$pluginName」的脚本出错了：$detail。" +
                "这可能和你给的参数有关 —— 换个参数再试一次；" +
                "如果还是同样的错，那是这个插件自己的问题，换一个工具或者告诉用户。",
            ScriptOutcome.Kind.ScriptError,
        )

    /**
     * `host.http` 失败时给脚本的那句话（`SandboxEngine.httpCall` 的兜底）。
     *
     * ## 为什么两条分支都必须给动作
     *
     * `IOException` 那条本来就有「网络恢复后可以重试」，而 `else` 那条
     * （原来只写「请求出错：…。」）**没有** —— 两条是同一个兜底里的兄弟，
     * 一条给了出路一条没给（§111.15）。
     *
     * ## 为什么 `else` 那条不写「这是宿主的问题」
     *
     * 走到 `else` 的**不一定是宿主 bug**：`builder.method(method, payload)` 对不合法的
     * HTTP 方法名会抛 `IllegalArgumentException`，而 `method` 是脚本传进来的。
     * 所以宿主分不出「参数不合预期」和「自己内部出问题」—— 给的是
     * 「试一次 + 不行就换」，而不是替哪一边打包票（§110）。
     *
     * ⚠️ 也不写「重试没有用」：宿主不知道这个异常是不是瞬时的。
     *
     * 白名单拒绝（`NetworkDeniedException`）**不经过这里** —— 它那句原文已经是
     * 写给模型看的，调用点直接用它。
     */
    fun httpFailedMessage(t: Throwable): String = when (t) {
        is IOException ->
            "网络请求失败：${errorDetail(t)}。网络恢复后可以重试。"

        else ->
            "请求出错：${errorDetail(t)}。换个参数再试一次；" +
                "如果还是同样的错，就换一个工具，或者如实告诉用户这个功能现在用不了。"
    }
}
