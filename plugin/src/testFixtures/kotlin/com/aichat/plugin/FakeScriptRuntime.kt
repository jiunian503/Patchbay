package com.aichat.plugin

import com.aichat.plugin.runtime.script.ScriptOutcome
import com.aichat.plugin.runtime.script.ScriptRequest
import com.aichat.plugin.runtime.script.ScriptRuntime

/**
 * 假脚本引擎。
 *
 * ## 为什么它是「记录 + 返回预设值」，而不是「真的解释 JS」
 *
 * 因为真引擎在 `:app` 里（QuickJS 是 Android AAR，引进来 `:plugin` 就不再是
 * 纯 JVM，见 §44）。而这个文件要测的是**契约**：
 * 「请求里带了哪些字段」「引擎说失败时工具回什么」——
 * 这些全都不需要解释器参与。
 *
 * 用真引擎反而更糟：它的行为（微任务不 drain、`while(true)` 把线程占死）会成为
 * 测试结果的一部分，于是「契约对不对」和「QuickJS 在这个版本上怎么样」混在一起，
 * 哪天换引擎要重写全部用例。
 *
 * ## 它曾经还有一套 `sources` / `reads`
 *
 * 上一轮 `ScriptRuntime` 带一个 `readSource`，那时靠「`reads` 是空的」来断言
 * 「宿主没装引擎时不该去读文件」。现在源码跟着清单走（`PluginManifest.files`），
 * 装配层**结构上**就没有读文件这回事了 —— 于是那套记录没有存在的意义。
 *
 * 而它守的那条性质还在（顺序不能反），只是换了个更直接的测法：
 * 拿一份「引擎没装 **且** 入口文件也缺失」的清单，断言报出来的是
 * 「宿主不支持」。见 `ScriptHostTest`。
 */
class FakeScriptRuntime(
    override val unavailableReason: String? = null,
    private val outcome: ScriptOutcome = ScriptOutcome.Ok("""{"ok":true}"""),
) : ScriptRuntime {

    /** 每次 [execute] 收到的请求，按调用顺序。 */
    val requests = mutableListOf<ScriptRequest>()

    override suspend fun execute(request: ScriptRequest): ScriptOutcome {
        requests += request
        return outcome
    }

    companion object {
        /** 最常见的入口文件名。测试里写它比写 `"index.js"` 更说明意图。 */
        const val DEFAULT_ENTRY = "index.js"

        /** 一份**结构上像样**的源码。内容不重要 —— 假引擎不解释它。 */
        const val DEFAULT_SOURCE = "exports.run = (input, host) => ({ ok: true })"

        /** 一份「只有入口」的插件文件表，也就是最普通的单文件脚本插件。 */
        val defaultFiles: Map<String, String> get() = mapOf(DEFAULT_ENTRY to DEFAULT_SOURCE)
    }
}
