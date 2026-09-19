package com.aichat.domain.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 一次完整的工具调用，已经可以执行了。
 *
 * [argumentsJson] 保持**字符串**形态而不是解析成对象，因为回传给模型时
 * OpenAI 协议要求的也是字符串。提前解析成对象再序列化回去，会因为
 * 键序、浮点格式变化产生无意义的 diff，部分服务端还会因此判定
 * 上下文不连续而拒绝请求。
 */
data class AssistantToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
) {
    /** 名字为空说明服务端没发全，这种调用无法执行，必须挡在执行之前。 */
    val isExecutable: Boolean get() = name.isNotBlank()
}

/**
 * 把 [ChatStreamEvent.ToolCallDelta] 的碎片拼成可执行的工具调用。
 *
 * ## 为什么要单独一个累加器
 *
 * 流式 tool_call 的参数是**逐字符**吐出来的。真实抓包长这样：
 *
 * ```
 * {"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"get_weather","arguments":""}}]}}
 * {"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"la"}}]}}
 * {"delta":{"tool_calls":[{"index":0,"function":{"arguments":"titude\":"}}]}}
 * {"delta":{"tool_calls":[{"index":0,"function":{"arguments":"39.9}"}}]}}
 * ```
 *
 * 只有第一片有 `id` 和 `name`，之后全是 `arguments` 的碎片。
 * 同时模型可能一次发起**多个**调用，它们的分片按 `index` 交错到达：
 *
 * ```
 * index:0 arguments:"{\"a"      index:1 id/name/arguments:"{\"c"
 * index:0 arguments:":1}"       index:1 arguments:":2}"
 * ```
 *
 * 所以必须按 `index` 分槽归并，不能简单「拼到最后一个」。
 *
 * ## 已知的服务端差异
 *
 * - **重复发 id/name**：部分网关（尤其是做协议转换的）会在每个分片都带
 *   `id` 和 `name`。本类取首个非空值，重复值忽略，保证幂等。
 * - **不发 index**：少数实现省略 `index`。DTO 里默认 0，退化为「全部归并
 *   到一个槽」。单工具调用场景下这是正确的；多工具调用场景下会串味，
 *   但那种实现本身就违反了协议，不为其增加复杂度。
 *
 * 本类**不是线程安全**的，一个流一个实例。
 */
class ToolCallAccumulator {

    private val slots = sortedMapOf<Int, Slot>()

    private class Slot {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    fun accept(delta: ChatStreamEvent.ToolCallDelta) {
        val slot = slots.getOrPut(delta.index) { Slot() }

        // 只在当前为空时赋值：首个非空值胜出，后续重复忽略（幂等）
        if (slot.id.isNullOrEmpty()) delta.id?.takeIf { it.isNotEmpty() }?.let { slot.id = it }
        if (slot.name.isNullOrEmpty()) delta.name?.takeIf { it.isNotEmpty() }?.let { slot.name = it }

        delta.argumentsDelta?.takeIf { it.isNotEmpty() }?.let { slot.arguments.append(it) }
    }

    /** 是否收到过任何工具调用分片。 */
    fun isEmpty(): Boolean = slots.isEmpty()

    /**
     * 产出完整调用列表，按 `index` 升序 —— 这个顺序就是模型期望的执行顺序，
     * 回传结果时也应按同样顺序，否则模型会困惑于「谁先谁后」。
     *
     * 参数为空时补 `{}`：无参数工具（如「获取当前时间」）服务端经常
     * 一个字符都不发，直接给空串会让下游 JSON 解析失败。
     */
    fun build(): List<AssistantToolCall> = slots.map { (index, slot) ->
        AssistantToolCall(
            id = slot.id ?: syntheticId(index),
            name = slot.name.orEmpty(),
            argumentsJson = slot.arguments.toString().ifBlank { "{}" },
        )
    }

    /**
     * 服务端没给 `id` 时的兜底。
     *
     * 后续回传工具结果必须带 `tool_call_id`，空串会被服务端拒绝，
     * 所以这里合成一个稳定值。用 index 保证同一轮内不重名。
     */
    private fun syntheticId(index: Int) = "call_synthetic_$index"
}

/** 工具参数解析。 */
object ToolArguments {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 把模型给的参数串解析成对象。
     *
     * - 空串 / 纯空白 -> 空对象（无参数工具的正常形态）
     * - 合法 JSON 对象 -> 原样
     * - 坏 JSON 或顶层不是对象 -> null，**调用方必须当作执行失败处理**
     *
     * 最后一种情况比想象中常见：模型偶尔会吐出不闭合的 JSON，
     * 或在 JSON 前后带一句自然语言解释。静默吞掉会让工具拿到空参数
     * 然后返回错误结果，反而更难排查，所以这里明确返回 null。
     */
    fun parseOrNull(raw: String): JsonObject? {
        val text = raw.trim()
        if (text.isEmpty()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }
}
