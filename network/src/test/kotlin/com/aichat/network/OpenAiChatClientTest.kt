package com.aichat.network

import com.aichat.domain.llm.ChatStreamEvent
import com.aichat.domain.llm.FinishReason
import com.aichat.domain.llm.LlmJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 网络层端到端测试：真起一个 HTTP 服务，真的走一遍 OkHttp。
 *
 * 这里用 [runBlocking] 而不是 `runTest` —— 请求发生在 OkHttp 自己的线程上，
 * 是真实时间，`runTest` 的虚拟时钟会让测试在流开始前就结束。
 */
class OpenAiChatClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(includeUsage: Boolean = false) = OpenAiChatClient(
        ProviderConfig(
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-test",
            includeUsage = includeUsage,
        )
    )

    private fun request() = ChatCompletionRequest(
        model = "test-model",
        messages = listOf(ChatMessageDto.user("你好")),
    )

    /** 把若干 JSON 负载包成 SSE 帧。 */
    private fun sse(vararg payloads: String) =
        buildString { payloads.forEach { append("data: $it\n\n") } }

    private val doneFrame = "data: [DONE]\n\n"

    // ---------- 正常流式 ----------

    @Test
    fun `文本增量与收尾事件被逐个发出`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                body = sse(
                    """{"choices":[{"index":0,"delta":{"role":"assistant","content":"你"}}]}""",
                    """{"choices":[{"index":0,"delta":{"content":"好"}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                ) + doneFrame,
            )
        )

        val events = client().stream(request()).toList()

        assertEquals(
            listOf(
                ChatStreamEvent.TextDelta("你"),
                ChatStreamEvent.TextDelta("好"),
                ChatStreamEvent.Finished(FinishReason.Stop),
            ),
            events,
        )
    }

    @Test
    fun `思维链与正文分别发出`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                body = sse(
                    """{"choices":[{"index":0,"delta":{"reasoning_content":"先想"}}]}""",
                    """{"choices":[{"index":0,"delta":{"content":"再答"}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                ) + doneFrame,
            )
        )

        val events = client().stream(request()).toList()

        assertEquals(
            listOf(
                ChatStreamEvent.ReasoningDelta("先想"),
                ChatStreamEvent.TextDelta("再答"),
                ChatStreamEvent.Finished(FinishReason.Stop),
            ),
            events,
        )
    }

    @Test
    fun `工具调用分片被原样透传不做归并`() = runBlocking {
        // 归并是 ToolCallAccumulator 的职责，网络层不该插手
        server.enqueue(
            MockResponse(
                code = 200,
                body = sse(
                    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}""",
                    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"lat\":39}"}}]}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                ) + doneFrame,
            )
        )

        val events = client().stream(request()).toList()

        assertEquals(
            listOf(
                ChatStreamEvent.ToolCallDelta(0, "call_a", "get_weather", ""),
                ChatStreamEvent.ToolCallDelta(0, null, null, """{"lat":39}"""),
                ChatStreamEvent.Finished(FinishReason.ToolCalls),
            ),
            events,
        )
    }

    @Test
    fun `用量帧被识别`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                body = sse(
                    """{"choices":[{"index":0,"delta":{"content":"x"}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                    """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
                ) + doneFrame,
            )
        )

        val events = client().stream(request()).toList()

        assertTrue(events.contains(ChatStreamEvent.Usage(10, 5)))
    }

    // ---------- 容错 ----------

    @Test
    fun `服务端不发 finish_reason 时补一个收尾事件`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                body = sse("""{"choices":[{"index":0,"delta":{"content":"x"}}]}""") + doneFrame,
            )
        )

        val events = client().stream(request()).toList()

        assertEquals(
            listOf(
                ChatStreamEvent.TextDelta("x"),
                ChatStreamEvent.Finished(FinishReason.Unknown),
            ),
            events,
        )
    }

    @Test
    fun `坏帧被跳过而不是中断整场对话`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                body = sse(
                    """{"choices":[{"index":0,"delta":{"content":"a"}}]}""",
                    """这不是 JSON""",
                    """{"choices":[{"index":0,"delta":{"content":"b"}}]}""",
                ) + doneFrame,
            )
        )

        val events = client().stream(request()).toList()

        assertEquals(
            listOf(
                ChatStreamEvent.TextDelta("a"),
                ChatStreamEvent.TextDelta("b"),
                ChatStreamEvent.Finished(FinishReason.Unknown),
            ),
            events,
        )
    }

    @Test
    fun `流没有以空行收尾时最后一个事件不丢`() = runBlocking {
        // 有些服务端发完最后一帧直接断连
        server.enqueue(
            MockResponse(
                code = 200,
                body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"尾\"}}]}",
            )
        )

        val events = client().stream(request()).toList()

        assertEquals(
            listOf(
                ChatStreamEvent.TextDelta("尾"),
                ChatStreamEvent.Finished(FinishReason.Unknown),
            ),
            events,
        )
    }

    // ---------- 请求构造 ----------

    @Test
    fun `序列化时不会丢掉等于默认值的字段`() {
        val body = LlmJson.encodeToString(
            ChatCompletionRequest(model = "m", messages = listOf(ChatMessageDto.user("hi")))
        )

        // kotlinx.serialization 默认 encodeDefaults = false，会把 `stream = true`
        // 当成「没改过」直接丢掉 —— 服务端收到没有 stream 的请求就返回非流式响应，
        // 整个 SSE 解析链路静默失效。这条断言把这个坑钉死。
        assertTrue("请求体必须含 stream:true，实际：$body", body.contains(""""stream":true"""))
    }

    @Test
    fun `请求地址认证头与流式开关都正确`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = doneFrame))

        client().stream(request()).toList()

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.url.encodedPath)
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
        assertEquals("text/event-stream", recorded.headers["Accept"])

        val body = recorded.body!!.utf8()
        assertTrue(body.contains(""""model":"test-model""""))
        assertTrue(body.contains(""""stream":true"""))

        // 用户没调过的旋钮不该出现在请求里 —— 有些服务端对「显式传了默认值」
        // 和「压根没传」的处理并不一致
        assertFalse(body.contains("temperature"))
        assertFalse(body.contains("stream_options"))
        assertFalse(body.contains("tools"))
    }

    @Test
    fun `开启用量统计时才带 stream_options`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = doneFrame))

        client(includeUsage = true).stream(request()).toList()

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains(""""stream_options":{"include_usage":true}"""))
    }

    @Test
    fun `空 apiKey 时不发 Authorization 头`() = runBlocking {
        // 本地推理服务（Ollama / LM Studio）不需要 key，
        // 发一个空的 `Bearer ` 反而可能被拒
        server.enqueue(MockResponse(code = 200, body = doneFrame))

        val anon = OpenAiChatClient(
            ProviderConfig(baseUrl = server.url("/v1").toString(), apiKey = "")
        )
        anon.stream(request()).toList()

        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `消息按 OpenAI 格式序列化`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = doneFrame))

        client().stream(
            ChatCompletionRequest(
                model = "m",
                messages = listOf(
                    ChatMessageDto.system("你是助手"),
                    ChatMessageDto.user("你好"),
                    ChatMessageDto.tool("call_a", "25°C"),
                ),
            )
        ).toList()

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains(""""role":"system","content":"你是助手""""))
        assertTrue(body.contains(""""role":"user","content":"你好""""))
        // tool 结果必须带上对应的 tool_call_id，否则服务端拒绝
        assertTrue(body.contains(""""role":"tool","content":"25°C","tool_call_id":"call_a""""))
    }

    @Test
    fun `工具定义被透传`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = doneFrame))

        client().stream(
            ChatCompletionRequest(
                model = "m",
                messages = listOf(ChatMessageDto.user("hi")),
                tools = listOf(
                    ToolDefinitionDto.of(
                        name = "get_weather",
                        description = "查询天气",
                        parameters = kotlinx.serialization.json.buildJsonObject {
                            put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                        },
                    )
                ),
            )
        ).toList()

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains(""""name":"get_weather""""))
        assertTrue(body.contains(""""description":"查询天气""""))
    }

    // ---------- 错误 ----------

    @Test
    fun `HTTP 错误被翻译成可读消息`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 401,
                body = """{"error":{"message":"Invalid API key","type":"invalid_request_error"}}""",
            )
        )

        val error = runCatching { client().stream(request()).toList() }.exceptionOrNull()

        assertTrue("期望 Http 异常，实际是 $error", error is ChatApiException.Http)
        error as ChatApiException.Http
        assertEquals(401, error.statusCode)
        assertEquals("HTTP 401：Invalid API key", error.message)
    }

    @Test
    fun `错误体不是 JSON 时退回原文片段`() = runBlocking {
        server.enqueue(MockResponse(code = 502, body = "<html>Bad Gateway</html>"))

        val error = runCatching { client().stream(request()).toList() }.exceptionOrNull()

        assertTrue(error is ChatApiException.Http)
        error as ChatApiException.Http
        assertEquals(502, error.statusCode)
        assertTrue(error.message!!.contains("Bad Gateway"))
    }

    @Test
    fun `连不上时抛网络异常`() = runBlocking {
        val port = server.port
        server.close() // 关掉服务，端口没人监听

        val error = runCatching {
            OpenAiChatClient(
                ProviderConfig(baseUrl = "http://127.0.0.1:$port/v1", apiKey = "k")
            ).stream(request()).toList()
        }.exceptionOrNull()

        assertTrue("期望 Network 异常，实际是 $error", error is ChatApiException.Network)
    }

    // ---------- 取消 ----------

    @Test
    fun `取消流会立刻结束而不是挂住`() = runBlocking {
        // 造一个很大的响应，确保取消发生时它还没读完
        val frames = buildString {
            repeat(20_000) {
                append("""data: {"choices":[{"index":0,"delta":{"content":"x"}}]}""").append("\n\n")
            }
        }
        server.enqueue(MockResponse(code = 200, body = frames))

        val job = launch(Dispatchers.IO) { client().stream(request()).collect { } }
        delay(50)
        job.cancelAndJoin()

        // cancelAndJoin 能返回就说明 awaitClose 里的 call.cancel() 生效了，
        // 否则这里会一直挂到测试超时
        assertTrue("取消后协程应处于已取消状态", job.isCancelled)
    }
}
