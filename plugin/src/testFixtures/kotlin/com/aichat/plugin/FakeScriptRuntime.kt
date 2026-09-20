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
 * 「装配期读几次源码」「请求里带了哪些字段」「引擎说失败时工具回什么」——
 * 这些全都不需要解释器参与。
 *
 * 用真引擎反而更糟：它的行为（微任务不 drain、`while(true)` 把线程占死）会成为
 * 测试结果的一部分，于是「契约对不对」和「QuickJS 在这个版本上怎么样」混在一起，
 * 哪天换引擎要重写全部用例。
 *
 * ## 所以它把「收到了什么」留下来
 *
 * [reads] 和 [requests] 是这个类的重点，不是 [sources] / [outcome]。
 * 「宿主没装引擎时**不该去读源码**」这条性质只能靠 [reads] 是空的来断言 ——
 * 而它是这一轮最重要的一个设计决定（报错该指向引擎，不该指向文件）。
 */
class FakeScriptRuntime(
    override val available: Boolean = true,
    private val sources: Map<String, String> = mapOf(DEFAULT_ENTRY to DEFAULT_SOURCE),
    private val outcome: ScriptOutcome = ScriptOutcome.Ok("""{"ok":true}"""),
) : ScriptRuntime {

    /** 每次 [execute] 收到的请求，按调用顺序。 */
    val requests = mutableListOf<ScriptRequest>()

    /** 每次 [readSource] 收到的 `(pluginId, path)`，按调用顺序。 */
    val reads = mutableListOf<Pair<String, String>>()

    override fun readSource(pluginId: String, path: String): String? {
        reads += pluginId to path
        return sources[path]
    }

    override suspend fun execute(request: ScriptRequest): ScriptOutcome {
        requests += request
        return outcome
    }

    companion object {
        const val DEFAULT_ENTRY = "index.js"

        /**
         * 一份**结构上像样**的源码。
         *
         * 内容不重要（假引擎不解释它），但它必须存在：装配期会把源码放进
         * [ScriptRequest.source]，而「源码真的从 `readSource` 来、不是空串」
         * 是一条要断言的契约。
         */
        const val DEFAULT_SOURCE = "exports.run = (input, host) => ({ ok: true })"

        /**
         * 装了引擎，但**任何文件都读不到**。
         *
         * 用来区分「引擎没装」和「插件文件不全」这两条完全不同的出路 ——
         * 它们的用户可见文案不一样，而混成一句是最容易犯的错。
         */
        fun empty(available: Boolean = true) = FakeScriptRuntime(available = available, sources = emptyMap())
    }
}
