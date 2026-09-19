package com.aichat.domain.llm

/**
 * Server-Sent Events 的事件聚合器：按行喂入，吐出完整事件的 data 负载。
 *
 * SSE 线上格式是「一行一个字段，空行结束一个事件」：
 *
 * ```
 * event: message
 * data: {"a":1}
 * data: {"b":2}
 * <空行>
 * ```
 *
 * 同一事件的多个 data 行要按 `\n` 拼接后才是完整负载。各家 LLM API 实际上
 * 只用 `data:` 一个字段，但 `event:` / `id:` / `retry:` 以及注释行都必须能
 * 正确跳过 —— 否则遇到反向代理发的 keep-alive 心跳（`: ping`）就会解析失败。
 *
 * ## UTF-8 边界是上游的责任
 *
 * 本类接受的是已经解码好的 [String]。如果上游按字节切分再各自解码，
 * 一个汉字被切在两个 TCP 分片中间就会变成乱码，且**无法在本层补救**
 * （信息已经丢了）。正确做法：
 *
 * - OkHttp：`response.body!!.source().readUtf8Line()`（内部按 UTF-8 增量解码）
 * - 原生：`InputStreamReader(stream, Charsets.UTF_8)` 逐行读
 *
 * 若确实只能拿到任意字节块，请走 [SseParser]，它内部会用同一个
 * [SseEventAssembler] 但额外负责切行。
 */
class SseEventAssembler {

    private val dataLines = mutableListOf<String>()

    /**
     * 喂入一行（**不含**行尾换行符）。
     *
     * @return 该行凑齐了一个完整事件时返回其 data 负载，否则返回 null。
     */
    fun accept(line: String): String? {
        // 兼容 CRLF。OkHttp 的 readUtf8Line 已经吃掉 \r，这里是给自实现 HTTP 兜底。
        val l = line.removeSuffix("\r")

        // 空行 = 事件边界
        if (l.isEmpty()) return drain()

        // 以冒号开头的是注释，SSE 规范要求忽略（keep-alive 心跳走这条）
        if (l.startsWith(":")) return null

        val colon = l.indexOf(':')
        val field = if (colon < 0) l else l.substring(0, colon)

        // event / id / retry 目前用不到。注意不能因为「不认识」就当成 data，
        // 否则 `id: 123` 会被误当负载送去反序列化。
        if (field != "data") return null

        // 规范：冒号后紧跟的**一个**空格是分隔符，不算数据。
        // 单独一行 `data`（无冒号）表示空负载，是合法写法。
        val raw = if (colon < 0) "" else l.substring(colon + 1)
        dataLines += raw.removePrefix(" ")
        return null
    }

    /**
     * 流已结束。若最后一个事件没有以空行收尾（部分服务端直接断连），
     * 这里会把它冲刷出来，避免丢掉最后一帧。
     */
    fun flush(): String? = drain()

    private fun drain(): String? {
        if (dataLines.isEmpty()) return null
        val joined = dataLines.joinToString("\n")
        dataLines.clear()
        return joined
    }
}

/**
 * 按任意文本块喂入的 SSE 解析器，内部负责切行。
 *
 * 用在「HTTP 层只能给出字节块」的场景（自实现 HTTP 客户端、WebSocket 转接等）。
 * 如果上游是 OkHttp，直接用 [SseEventAssembler] + `readUtf8Line()` 更省事，
 * 也天然躲开 UTF-8 截断问题。
 *
 * 本类**不做** UTF-8 增量解码 —— 它收到什么就是什么。详见
 * [SseEventAssembler] 的类注释。
 */
class SseParser {

    private val assembler = SseEventAssembler()
    private val pending = StringBuilder()

    /**
     * 喂入一块文本，返回本次凑齐的所有完整事件负载（可能 0 个或多个）。
     *
     * 不完整的行会留在内部缓冲区，等下一个块到达时拼接 ——
     * 这是必须的：TCP 分片与 SSE 行边界毫无关系，一行被切成三段是常态。
     */
    fun feed(chunk: String): List<String> {
        pending.append(chunk)
        val out = mutableListOf<String>()

        var nl = pending.indexOf("\n")
        while (nl >= 0) {
            val line = pending.substring(0, nl)
            pending.delete(0, nl + 1)
            assembler.accept(line)?.let { out += it }
            nl = pending.indexOf("\n")
        }
        return out
    }

    /** 流结束。冲刷残留的未换行末行，以及没有空行收尾的最后一个事件。 */
    fun finish(): List<String> {
        val out = mutableListOf<String>()
        if (pending.isNotEmpty()) {
            val rest = pending.toString()
            pending.setLength(0)
            assembler.accept(rest)?.let { out += it }
        }
        assembler.flush()?.let { out += it }
        return out
    }
}
