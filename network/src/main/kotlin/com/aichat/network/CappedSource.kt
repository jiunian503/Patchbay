package com.aichat.network

import java.io.IOException
import okio.Buffer
import okio.ForwardingSource
import okio.Source

/**
 * 响应体超过了上限。单独一个类型，好把它和「网络断了」分开报。
 *
 * 这里只负责说清「发生了什么」；翻成各自的失败类型是消费者的事 ——
 * LLM 侧是 `ChatApiException.Protocol`，MCP 侧是 `McpFailure`。
 */
class ResponseTooLargeException(message: String) : IOException(message)

/**
 * 把**读到的字节数**卡死在上限上。
 *
 * ## 为什么在字节这一层卡，而不是在行这一层
 *
 * 读取是 `readUtf8Line()` 逐行做的，看起来「累计行长度」就够了 ——
 * 但那是**读完一行之后**才更新的。对端把整个工具结果放在一行 JSON 里
 * （很常见，`json.Marshal` 默认就不换行）时，这一行可能是几十 MB，
 * 而计数在那一行读完之后才加上去：内存已经吃满了。
 * 在字节流这一层拦，就没有「先分配再判断」的窗口。
 *
 * ## 为什么放在 :network，而不是各自留一份
 *
 * 有两个消费者，而且**上限值不同**：MCP 的一次工具响应是 256 KB
 * （见 `McpHttpTransport.MAX_BODY_BYTES`），LLM 的一次流式回答要大得多
 * （见 `OpenAiChatClient` 里的 `MAX_STREAM_BYTES`）。
 * 「怎么卡」这件事只留一处实现，**上限由调用方给** —— 这样两边不会各自
 * 漂移出一套算法，也不会因为「照抄一个数字」把长回答掐断。
 */
class CappedSource(delegate: Source, private val limit: Long) : ForwardingSource(delegate) {

    private var read = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
        val n = super.read(sink, byteCount)
        if (n <= 0) return n
        read += n
        if (read > limit) throw ResponseTooLargeException("响应超过 $limit 字节上限")
        return n
    }
}
