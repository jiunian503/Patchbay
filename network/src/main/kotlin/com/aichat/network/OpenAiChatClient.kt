package com.aichat.network

import com.aichat.domain.llm.ChatCompletionChunk
import com.aichat.domain.llm.ChatStreamEvent
import com.aichat.domain.llm.FinishReason
import com.aichat.domain.llm.LlmJson
import com.aichat.domain.llm.SseEventAssembler
import com.aichat.domain.llm.toStreamEvents
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.buffer

private const val DONE_SENTINEL = "[DONE]"

/**
 * 一次回答的流式响应最多读多少字节。
 *
 * **不是照抄 MCP 那边的 256 KB** —— 两者量的不是同一种东西。MCP 的 256 KB
 * 卡的是一次工具调用的结果；这里卡的是模型整段输出。按最坏情况算：推理模型
 * 吐 32k token、网关一个 token 发一个 SSE 帧、每帧的 JSON 包装约 200 字节
 * ⇒ 量级在 7 MB 上下。给一倍余量，取 16 MB。
 *
 * 照抄 256 KB 的话，一个正常的长回答会被从中间掐断 —— 那比「理论上可能吃满
 * 内存」严重得多：它天天发生，而且用户没法绕过。
 *
 * 有这个上限的意义也不是省内存，而是**给「对端无休止地推」一个终点**：
 * 没有它，一个坏掉或恶意的服务端可以一直推下去，直到把内存吃光。
 */
private const val MAX_STREAM_BYTES = 16L * 1024 * 1024

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * OpenAI 兼容的流式对话客户端。
 *
 * 一次 [stream] 调用 = 一次 HTTP 请求，返回的事件流覆盖模型的整段输出
 * （正文、思维链、工具调用、用量、收尾）。**不重试、不拼接上下文** ——
 * 那些属于上层「对话核心」的职责，这一层只管把网络字节变成事件。
 *
 * ## 为什么用 OkHttp 的异步 enqueue 而不是阻塞 execute
 *
 * 阻塞式读取要让协程可取消，就得在取消时主动 `call.cancel()` 去打断阻塞中的
 * socket read，而阻塞点本身不在协程的检查点上，`ensureActive()` 叫不醒它 ——
 * 得靠额外线程或 `runInterruptible` 绕，很容易写错。
 *
 * [callbackFlow] + `awaitClose { call.cancel() }` 天然解决这件事：协程一取消，
 * `awaitClose` 立刻执行，socket 被关掉，回调线程随即退出。
 *
 * ## 背压
 *
 * 事件由 OkHttp 的线程 `trySend` 推入，消费者（UI 渲染）可能比网络慢。
 * 这里用 [Channel.UNLIMITED] 让 `trySend` 永不失败 —— 一次回答撑死几千个
 * token 事件，量级在几百 KB，用有限缓冲反而会丢内容。丢事件比多占点内存严重得多。
 *
 * 「几百 KB」是**正常情况**，不能拿它当上限的依据：对端不遵守这个假设时
 * （坏掉或恶意的服务端一直推），`UNLIMITED` 就是没有底。所以读取本身另有一道
 * 字节上限，见下面的 `maxStreamBytes`。
 */
class OpenAiChatClient(
    private val config: ProviderConfig,
    private val httpClient: OkHttpClient = config.newHttpClient(),

    /**
     * 一次回答的流式响应最多读多少字节。
     *
     * **这是留给测试的口子**：生产用默认值（见文件头的 `MAX_STREAM_BYTES`）。
     * 真去造一个 16 MB 的响应来验上限，会让这条用例变成整个模块最慢的一条，
     * 而它要验的只是「超了会不会停下来」，与上限具体是几 MB 无关。
     */
    private val maxStreamBytes: Long = MAX_STREAM_BYTES,
) : ChatCompletionClient {

    /**
     * 发起一次流式请求。
     *
     * Flow 正常完成 = 模型说完了。Flow 抛异常 = 见 [ChatApiException] 的三种类型。
     * 协程被取消 = 用户点了停止，此时连接会被立刻掐断，不会继续计费。
     */
    override fun stream(request: ChatCompletionRequest): Flow<ChatStreamEvent> = callbackFlow {
        val call = httpClient.newCall(buildRequest(request))
        val out = channel

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(ChatApiException.Network(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val body = runCatching { resp.body.string() }.getOrDefault("")
                        close(
                            ChatApiException.Http(
                                statusCode = resp.code,
                                errorBody = body,
                                message = extractApiErrorMessage(resp.code, body),
                            )
                        )
                        return
                    }

                    try {
                        pump(resp, out)
                        close()
                    } catch (t: Throwable) {
                        close(t)
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }.buffer(Channel.UNLIMITED)

    private fun buildRequest(request: ChatCompletionRequest): Request {
        val effective = request.copy(
            stream = true,
            streamOptions = if (config.includeUsage) StreamOptionsDto(includeUsage = true) else null,
        )

        return Request.Builder()
            .url(config.chatCompletionsUrl)
            .header("Accept", "text/event-stream")
            .apply {
                // 本地推理服务（Ollama / LM Studio）不需要 key，
                // 发一个空的 `Bearer ` 反而可能被拒，所以留空就不发。
                if (config.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
                config.extraHeaders.forEach { (name, value) -> header(name, value) }
            }
            .post(LlmJson.encodeToString(effective).toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /**
     * 从响应体里持续抽事件，直到 `[DONE]` 或流结束。
     *
     * 用 `readUtf8Line()` 而不是自己按字节切 —— 它内部按 UTF-8 增量解码，
     * 中文被切在两个 TCP 分片中间也不会乱码。这一点见 `SseParser` 的类注释。
     */
    private fun pump(response: Response, out: SendChannel<ChatStreamEvent>) {
        // 卡在字节这一层而不是行这一层：对端把几十 MB 塞进一行时，
        // 「读完一行再判断」的窗口关不掉（见 `CappedSource` 的注释）
        val source = CappedSource(response.body.source(), maxStreamBytes).buffer()
        val assembler = SseEventAssembler()
        var sawFinish = false

        fun consume(payload: String): Boolean {
            if (payload == DONE_SENTINEL) return false

            // 单帧坏掉不该中断整场对话：部分网关偶尔会插入非 JSON 的心跳负载
            val chunk = runCatching { LlmJson.decodeFromString<ChatCompletionChunk>(payload) }
                .getOrNull() ?: return true

            for (event in chunk.toStreamEvents()) {
                if (event is ChatStreamEvent.Finished) sawFinish = true
                out.trySend(event)
            }
            return true
        }

        try {
            var done = false
            while (!done) {
                val line = source.readUtf8Line() ?: break
                val payload = assembler.accept(line) ?: continue
                done = !consume(payload)
            }

            // 有些服务端最后一个事件不带空行就直接断连
            if (!done) assembler.flush()?.let { consume(it) }
        } catch (e: ResponseTooLargeException) {
            // **不能当成正常结束**：已经收到的那部分是半截回答，
            // 按成功收尾等于把半截话当成说完了
            throw ChatApiException.Protocol(
                "这次回答的流式响应超过了 ${maxStreamBytes / (1024 * 1024)} MB，已停止读取。" +
                    "正常回答远小于这个量级，多半是服务端出了问题。",
                e,
            )
        }

        // 另一些干脆不发 finish_reason。补一个，让下游能统一走收尾逻辑，
        // 而不必到处判空。真正的「是否要调工具」由上层看累积的 tool_calls 决定。
        if (!sawFinish) out.trySend(ChatStreamEvent.Finished(FinishReason.Unknown))
    }
}
