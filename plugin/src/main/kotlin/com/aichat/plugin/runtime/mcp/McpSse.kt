package com.aichat.plugin.runtime.mcp

/**
 * 一个 SSE 事件。
 *
 * 只保留 [event] 和 [data]：`id` 字段在 MCP 里没用（规范明确不支持
 * `Last-Event-ID` 恢复），`retry` 字段是给浏览器自动重连用的。
 * 留着不用等于给读代码的人一个「这里是不是该处理 id」的错觉。
 */
internal data class SseEvent(val event: String?, val data: String)

/**
 * 把 SSE 的**行**拼成事件。
 *
 * ## 为什么单独一个类，而不是在读取循环里 `split("\n\n")`
 *
 * 因为「事件怎么切」和「从网络读字节」是两件事，混在一起之后**切分规则
 * 就没法单独测了** —— 而这里恰好有一条必须逐字符验的规则：
 * 事件的边界是**空行**，而空行可能来自 `\n\n` 或 `\r\n\r\n`，
 * 数据里的换行则是多行 `data:` 拼接出来的。切错一处的症状是
 * 「JSON 少了一半」，报的是解析错误，看不出是切分错了。
 *
 * 这个类和 `:domain` 的切段逻辑是同一个形状：把纯计算从 IO 里摘出来，
 * 于是可以喂一堆字符串进去逐条断言。
 *
 * ## 几条容易漏的规则
 *
 * - **以冒号开头的行是注释，必须忽略。** MCP 服务端被鼓励周期性发
 *   `:\r\n` 做 keep-alive —— 不忽略的话，每个心跳都会被当成一个事件边界。
 * - **字段名和值之间只剥一个空格**（`data:  x` 的值是 `" x"`，不是 `"x"`）。
 *   多剥的话，正文里靠前导空格排版的 JSON 会被改掉。
 * - **没有冒号的行是「字段名 + 空值」**，不是错误。
 * - 流结束时最后一条事件可能没有以空行收尾，要靠 [finish] 补一次派发 ——
 *   漏掉的话，**只在最后一个事件里给出响应**的服务端会被判成「没返回结果」。
 */
internal class SseParser {

    private val data = StringBuilder()
    private var event: String? = null
    private var pending = false

    /** 喂一行（**不含**换行符）。返回一个凑齐的事件，或 null。 */
    fun line(raw: String): SseEvent? {
        val line = raw.removeSuffix("\r")

        if (line.isEmpty()) return dispatch()

        // 注释行：`:\r\n` 这种 keep-alive 就走这里
        if (line.startsWith(":")) return null

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        // 只剥一个空格
        if (value.startsWith(" ")) value = value.substring(1)

        when (field) {
            // 多行 data 用换行拼起来 —— 这是 SSE 里放多行正文的唯一办法
            "data" -> {
                data.append(value).append('\n')
                pending = true
            }

            "event" -> {
                event = value
                pending = true
            }

            // `id` / `retry` 与 MCP 无关，认出来但不做事
            else -> Unit
        }

        return null
    }

    /** 流结束。可能还有一条没派发的事件。 */
    fun finish(): SseEvent? = dispatch()

    private fun dispatch(): SseEvent? {
        if (!pending) return null
        // 拼进去的换行是分隔符，不属于数据
        val payload = data.toString().removeSuffix("\n")
        val result = SseEvent(event, payload)
        data.setLength(0)
        event = null
        pending = false
        return result
    }
}
