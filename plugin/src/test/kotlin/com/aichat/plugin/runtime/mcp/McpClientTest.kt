package com.aichat.plugin.runtime.mcp

import com.aichat.plugin.permission.NetworkDeniedException
import com.aichat.plugin.permission.NetworkGuard
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * MCP 客户端。
 *
 * ## 这个文件里最值钱的用例是「回退判定」
 *
 * 规范给 HTTP 绑定的检测机制只有一条：**先按现代版发一个请求，
 * 从 4xx 的 body 里读出对端是哪一代**。判错的代价是不对称的，
 * 而且两个方向都不报错、只是行为不对：
 *
 * - 把现代服务端当旧版 → 对着它发一个它不认识的 `initialize`
 * - 把旧版当现代版 → 每次请求都少一个必需的头
 *
 * 所以下面三个用例是分开的：认得出来（不回退）、认不出来（回退）、
 * 是 `UnsupportedProtocolVersionError` 且只列了旧版本（回退）。
 * 只写「能回退」一个用例的话，一个「永远回退」的实现也能通过 ——
 * 而那个实现会把所有现代服务端都打死。
 */
class McpClientTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun mcp(
        guard: NetworkGuard = NetworkGuard(listOf(server.url("/").host)),
        knownEra: McpEra? = null,
    ) = McpClient(
        endpoint = server.url("/mcp"),
        guard = guard,
        client = http,
        knownEra = knownEra,
    )

    private fun json(body: String) = MockResponse(
        code = 200,
        headers = headersOf("Content-Type", "application/json"),
        body = body,
    )

    private fun sse(body: String) = MockResponse(
        code = 200,
        headers = headersOf("Content-Type", "text/event-stream"),
        body = body,
    )

    private fun failure(code: Int, body: String = "") = MockResponse(
        code = code,
        headers = headersOf("Content-Type", "application/json"),
        body = body,
    )

    private fun toolsResult(vararg tools: String) =
        """{"jsonrpc":"2.0","id":1,"result":{"tools":[${tools.joinToString(",")}]}}"""

    private fun callResult(content: String, isError: Boolean = false) =
        """{"jsonrpc":"2.0","id":2,"result":{"content":[$content],"isError":$isError}}"""

    private fun textBlock(text: String) = """{"type":"text","text":"$text"}"""

    private val searchTool =
        """{"name":"search","description":"搜索历史对话","inputSchema":{"type":"object","properties":{"q":{"type":"string"}}}}"""

    private fun RecordedRequest.jsonBody(): JsonObject =
        Json.parseToJsonElement(body?.utf8().orEmpty()).jsonObject

    private fun RecordedRequest.methodName(): String =
        jsonBody()["method"]!!.jsonPrimitive.content

    private fun RecordedRequest.hasMeta(): Boolean =
        (jsonBody()["params"] as? JsonObject)?.containsKey("_meta") == true

    // ------------------------------------------------------------------ 现代版

    @Test
    fun `现代版一次请求就够 头与 body 都带齐元数据`() {
        server.enqueue(json(toolsResult(searchTool)))

        val tools = mcp().listTools()

        assertEquals("现代版没有握手，一次请求就该拿到结果", 1, server.requestCount)
        assertEquals(1, tools.size)
        assertEquals("search", tools[0].name)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/mcp", request.url.encodedPath)
        // 只写 application/json 的话，想用事件流回答的服务端就没法合规地这么做
        assertEquals("application/json, text/event-stream", request.headers["Accept"])
        assertEquals("2026-07-28", request.headers["MCP-Protocol-Version"])
        assertEquals("tools/list", request.headers["Mcp-Method"])
        // tools/list 没有 params.name，就不该有这个头
        assertNull(request.headers["Mcp-Name"])
        // 现代版没有会话
        assertNull(request.headers["Mcp-Session-Id"])

        val meta = request.jsonBody()["params"]!!.jsonObject["_meta"]!!.jsonObject
        assertEquals(
            "2026-07-28",
            meta["io.modelcontextprotocol/protocolVersion"]!!.jsonPrimitive.content,
        )
        // 这两个是**必需**项：缺了属于格式错误，服务端必须回 400 + -32602
        assertTrue(
            "缺 clientCapabilities 的请求会被服务端判成格式错误",
            meta.containsKey("io.modelcontextprotocol/clientCapabilities"),
        )
        assertTrue(meta.containsKey("io.modelcontextprotocol/clientInfo"))
    }

    @Test
    fun `tools_call 带上 Mcp-Name`() {
        server.enqueue(json(toolsResult(searchTool)))
        server.enqueue(json(callResult(textBlock("ok"))))

        val client = mcp()
        val tool = client.listTools().single()
        val result = client.callTool(tool, buildJsonObject { put("q", JsonPrimitive("x")) })

        assertEquals("ok", result.text)
        assertEquals(false, result.isError)

        server.takeRequest()
        val call = server.takeRequest()
        assertEquals("tools/call", call.headers["Mcp-Method"])
        // Mcp-Name 是从 body 的 params_name 镜像出来的 —— 两处不一致时
        // 服务端回 400 HeaderMismatch，而报错里看不出是哪个头
        assertEquals("search", call.headers["Mcp-Name"])
        assertEquals(
            "search",
            call.jsonBody()["params"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `工具名带中文时 Mcp-Name 走哨兵编码`() {
        // OkHttp 会拒绝非 ASCII 的头值。不编码的话这里抛的是
        // `Unexpected char` —— 和 MCP 一点关系都看不出来
        val chineseTool =
            """{"name":"搜索","description":"中文工具名","inputSchema":{"type":"object"}}"""
        server.enqueue(json(toolsResult(chineseTool)))
        server.enqueue(json(callResult(textBlock("ok"))))

        val client = mcp()
        val tool = client.listTools().single()
        assertEquals("搜索", tool.name)
        client.callTool(tool, JsonObject(emptyMap()))

        server.takeRequest()
        val header = server.takeRequest().headers["Mcp-Name"].orEmpty()
        assertTrue("应该是哨兵编码：$header", header.startsWith("=?base64?"))
        assertTrue("编码后必须全是可见 ASCII", header.all { it.code in 0x21..0x7E })
    }

    @Test
    fun `x-mcp-header 把参数镜像成请求头`() {
        val tool =
            """{"name":"geo","description":"按区域查","inputSchema":{"type":"object","properties":{""" +
                """"region":{"type":"string","x-mcp-header":"Region"},"q":{"type":"string"}}}}"""
        server.enqueue(json(toolsResult(tool)))
        server.enqueue(json(callResult(textBlock("ok"))))

        val client = mcp()
        val descriptor = client.listTools().single()
        assertEquals(mapOf("region" to "Region"), descriptor.headerParams)

        client.callTool(
            descriptor,
            buildJsonObject {
                put("region", JsonPrimitive("us-west1"))
                put("q", JsonPrimitive("hi"))
            },
        )

        server.takeRequest()
        val call = server.takeRequest()
        assertEquals("us-west1", call.headers["Mcp-Param-Region"])
        // 参数同时留在 body 里 —— 头是**镜像**，不是替代
        assertEquals(
            "us-west1",
            call.jsonBody()["params"]!!.jsonObject["arguments"]!!.jsonObject["region"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `x-mcp-header 的值含非 ASCII 时也编码`() {
        val tool =
            """{"name":"geo","description":"按区域查","inputSchema":{"type":"object","properties":{""" +
                """"region":{"type":"string","x-mcp-header":"Region"}}}}"""
        server.enqueue(json(toolsResult(tool)))
        server.enqueue(json(callResult(textBlock("ok"))))

        val client = mcp()
        val descriptor = client.listTools().single()
        client.callTool(descriptor, buildJsonObject { put("region", JsonPrimitive("华北")) })

        server.takeRequest()
        val header = server.takeRequest().headers["Mcp-Param-Region"].orEmpty()
        assertTrue(header.startsWith("=?base64?"))
        assertTrue(header.all { it.code in 0x21..0x7E })
    }

    @Test
    fun `现代版忽略对端给的会话头`() {
        // 规范要求：收到 Mcp-Session-Id 要忽略，不铸造也不回显。
        // 回显的话等于对着一个不承认会话的服务端假装有会话
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "application/json", "Mcp-Session-Id", "ignored"),
                body = toolsResult(searchTool),
            ),
        )
        server.enqueue(json(callResult(textBlock("ok"))))

        val client = mcp()
        val tool = client.listTools().single()
        client.callTool(tool, JsonObject(emptyMap()))

        server.takeRequest()
        assertNull(
            "现代版没有会话，不该回显 Mcp-Session-Id",
            server.takeRequest().headers["Mcp-Session-Id"],
        )
    }

    // ------------------------------------------------------------------ 事件流

    @Test
    fun `事件流里先有通知 最终响应在后面`() {
        // 规范说这个流里**先传该请求相关的通知，再传最终响应**。
        // 「取第一条 data」的实现会在这里解析出一个没有 result 的对象
        server.enqueue(
            sse(
                "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}\n\n" +
                    "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[$searchTool]}}\n\n",
            ),
        )

        val tools = mcp().listTools()

        assertEquals(1, tools.size)
        assertEquals("search", tools[0].name)
    }

    @Test
    fun `事件流里的 keep-alive 注释行不影响结果`() {
        server.enqueue(
            sse(
                ":\n\n" +
                    "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[$searchTool]}}\n\n" +
                    ":\r\n\r\n",
            ),
        )
        assertEquals(1, mcp().listTools().size)
    }

    @Test
    fun `事件流最后一条没有空行收尾也能拿到`() {
        // 漏了「流结束补派发」的实现会在这里报「对端没返回结果」，
        // 而响应明明就在流里
        server.enqueue(
            sse("data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[$searchTool]}}"),
        )
        assertEquals(1, mcp().listTools().size)
    }

    @Test
    fun `Content-Type 被改成 text-plain 时按内容嗅探`() {
        // 中间的反向代理改 Content-Type 是很常见的。直接当 JSON 解析的话，
        // 报的是一个和真实原因无关的解析错误
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "text/plain"),
                body = "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[$searchTool]}}\n\n",
            ),
        )
        assertEquals(1, mcp().listTools().size)
    }

    @Test
    fun `事件流超过上限时报错而不是撑爆内存`() {
        server.enqueue(sse("data: " + "x".repeat(300 * 1024) + "\n\n"))

        val failure = expectFailure { mcp().listTools() }

        assertFalse("超限重试也没用", failure.retryable)
        assertTrue(failure.message!!.contains("上限"))
    }

    @Test
    fun `JSON 响应超过上限时报错 而不是截断之后怪对端`() {
        // 直接 `peekBody(MAX).string()` 会把正文悄悄截断，于是 JSON 解析失败，
        // 报出来的是「对端返回的不是 JSON」—— 宿主自己的限制被说成了对端的问题。
        // 这条和上一条是同一件事的两个形态，分开写是因为它们走的是两条不同的读取路径
        server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":{"pad":"${"x".repeat(300 * 1024)}"}}"""))

        val failure = expectFailure { mcp().listTools() }

        assertFalse("超限重试也没用", failure.retryable)
        assertTrue("要说清是太大了：${failure.message}", failure.message!!.contains("上限"))
        assertFalse(
            "不能把锅甩给对端：${failure.message}",
            failure.message!!.contains("不是一个 JSON 对象"),
        )
    }

    // ------------------------------------------------------------------ 回退判定

    @Test
    fun `400 且 body 认不出来时回退到旧协议握手`() {
        server.enqueue(failure(400, ""))
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf(
                    "Content-Type", "application/json",
                    "Mcp-Session-Id", "sess-1",
                ),
                body = """{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2025-11-25",""" +
                    """"capabilities":{},"serverInfo":{"name":"old-server"}}}""",
            ),
        )
        server.enqueue(MockResponse(code = 202))
        server.enqueue(json(toolsResult(searchTool)))

        val tools = mcp().listTools()

        assertEquals(1, tools.size)
        assertEquals(4, server.requestCount)

        val detect = server.takeRequest()
        val initialize = server.takeRequest()
        val initialized = server.takeRequest()
        val list = server.takeRequest()

        assertEquals("tools/list", detect.methodName())
        assertEquals("initialize", initialize.methodName())
        assertEquals("notifications/initialized", initialized.methodName())
        assertEquals("tools/list", list.methodName())

        // 握手请求不带现代版的元数据 —— 带了旧服务端不认识
        assertFalse(initialize.hasMeta())
        assertNull(initialize.headers["MCP-Protocol-Version"])
        assertNull("握手之前还没有会话", initialize.headers["Mcp-Session-Id"])

        // notification **不能有 id 字段**：有 id 就变成一个请求，
        // 对端会等一个永远不会来的响应
        assertFalse(initialized.jsonBody().containsKey("id"))
        assertEquals("sess-1", initialized.headers["Mcp-Session-Id"])

        // 会话 id 是响应头给的，之后每个请求都要带上
        assertEquals("sess-1", list.headers["Mcp-Session-Id"])
        assertFalse(list.hasMeta())
    }

    @Test
    fun `400 且是现代错误时不回退`() {
        // 这一条是「永远回退」那个实现的照妖镜。HeaderMismatch 是现代版
        // 才有的错误码，说明对端认识 `_meta`，只是这个头不对
        server.enqueue(
            failure(
                400,
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32020,"message":"header mismatch"}}""",
            ),
        )

        val failure = expectFailure { mcp().listTools() }

        assertEquals("认得出对端是现代版，就不该再发 initialize", 1, server.requestCount)
        assertFalse(failure.retryable)
        assertTrue(failure.message!!.contains("头与请求体不一致"))
    }

    @Test
    fun `版本不支持的响应里只列了旧版本时回退`() {
        server.enqueue(
            failure(
                400,
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32022,""" +
                    """"message":"Unsupported protocol version","data":{"supported":["2025-11-25"],""" +
                    """"requested":"2026-07-28"}}}""",
            ),
        )
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "application/json"),
                body = """{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2025-11-25"}}""",
            ),
        )
        server.enqueue(MockResponse(code = 202))
        server.enqueue(json(toolsResult(searchTool)))

        assertEquals(1, mcp().listTools().size)
        server.takeRequest()
        assertEquals("initialize", server.takeRequest().methodName())
    }

    @Test
    fun `版本不支持的响应里列了我们的版本时重试现代版而不回退`() {
        server.enqueue(
            failure(
                400,
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32022,""" +
                    """"message":"Unsupported protocol version","data":{"supported":["2026-07-28","2025-11-25"],""" +
                    """"requested":"1900-01-01"}}}""",
            ),
        )
        server.enqueue(json(toolsResult(searchTool)))

        val tools = mcp().listTools()

        assertEquals(1, tools.size)
        assertEquals(2, server.requestCount)
        assertEquals("tools/list", server.takeRequest().methodName())
        val retry = server.takeRequest()
        assertEquals("tools/list", retry.methodName())
        assertEquals("2026-07-28", retry.headers["MCP-Protocol-Version"])
    }

    @Test
    fun `版本完全谈不拢时报错而不是回退`() {
        server.enqueue(
            failure(
                400,
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32022,"message":"nope",""" +
                    """"data":{"supported":["2099-01-01"],"requested":"2026-07-28"}}}""",
            ),
        )
        val failure = expectFailure { mcp().listTools() }
        assertTrue(failure.message!!.contains("2099-01-01"))
    }

    @Test
    fun `403 不回退`() {
        // 403 是「Origin 被拒」，和年代判定无关。回退只会把错误信息搅浑
        server.enqueue(failure(403, "forbidden"))
        expectFailure { mcp().listTools() }
        assertEquals(1, server.requestCount)
    }

    // ------------------------------------------------------------------ 其余

    @Test
    fun `请求收到 202 是错误 notification 收到才是成功`() {
        server.enqueue(MockResponse(code = 202))
        val failure = expectFailure { mcp().listTools() }
        assertFalse(failure.retryable)
        assertTrue(failure.message!!.contains("202"))
    }

    @Test
    fun `白名单拦下时一个请求都不发`() {
        val client = McpClient(
            endpoint = server.url("/mcp"),
            guard = NetworkGuard(listOf("api.example.com")),
            client = http,
        )

        try {
            client.listTools()
            fail("应该被白名单拦下")
        } catch (expected: NetworkDeniedException) {
            // 和网络故障分开：重试没有意义，要让模型转告用户去改权限
            assertTrue(expected.message!!.contains("api.example.com"))
        }
        assertEquals("被拦下就不该真的发出去", 0, server.requestCount)
    }

    @Test
    fun `没有名字的工具被丢掉 其余照常`() {
        val noName = """{"description":"没有名字","inputSchema":{"type":"object"}}"""
        server.enqueue(json(toolsResult(searchTool, noName)))

        val tools = mcp().listTools()

        assertEquals(1, tools.size)
        assertEquals("search", tools[0].name)
    }

    @Test
    fun `没有说明的工具照样注册`() {
        // 对端不给说明是它的问题。把工具丢掉的话，用户会看到
        // 「服务端文档说有 5 个工具、插件里只有 3 个」，那是最难查的一类不一致
        val noDescription = """{"name":"mystery","inputSchema":{"type":"object"}}"""
        server.enqueue(json(toolsResult(noDescription)))

        val tool = mcp().listTools().single()

        assertEquals("mystery", tool.name)
        assertTrue("要明说没有说明，别让模型凭名字猜", tool.description.contains("没有提供说明"))
    }

    @Test
    fun `readOnlyHint 被读出来`() {
        val readOnly =
            """{"name":"lookup","description":"查","inputSchema":{"type":"object"},""" +
                """"annotations":{"readOnlyHint":true}}"""
        val writing =
            """{"name":"write","description":"写","inputSchema":{"type":"object"},""" +
                """"annotations":{"readOnlyHint":false}}"""
        server.enqueue(json(toolsResult(readOnly, writing)))

        val tools = mcp().listTools()

        assertEquals(true, tools[0].readOnly)
        assertEquals(false, tools[1].readOnly)
    }

    // ------------------------------------------------------------------ 结果渲染

    @Test
    fun `多个 text 块拼起来`() {
        server.enqueue(json(toolsResult(searchTool)))
        server.enqueue(json(callResult("${textBlock("第一段")},${textBlock("第二段")}")))

        val client = mcp()
        val tool = client.listTools().single()

        assertEquals("第一段\n\n第二段", client.callTool(tool, JsonObject(emptyMap())).text)
    }

    @Test
    fun `工具结果超过字符上限时截断并说明`() {
        // 工具结果会原样进上下文，而且**每一轮都重发**，直到它滑出窗口 ——
        // 不截断的话，一次超长返回会把后面几十轮的每一次请求全撑大
        val long = "x".repeat(25_000)
        server.enqueue(json(toolsResult(searchTool)))
        server.enqueue(json(callResult(textBlock(long))))

        val client = mcp()
        val tool = client.listTools().single()
        val text = client.callTool(tool, JsonObject(emptyMap())).text

        assertTrue("要说明白截断了，否则模型会拿前半截当全部", text.contains("已截断"))
        assertTrue("要说清原文多长", text.contains("25000"))
        assertTrue("正文不该把上限整个丢掉：${text.length}", text.length in 20_000..21_000)
    }

    @Test
    fun `只有非文本内容时要说出来`() {
        // 沉默会让模型以为「工具什么都没返回」，然后据此下结论
        server.enqueue(json(toolsResult(searchTool)))
        server.enqueue(
            json(callResult("""{"type":"image","mimeType":"image/png","data":"..."}""")),
        )

        val client = mcp()
        val tool = client.listTools().single()
        val result = client.callTool(tool, JsonObject(emptyMap()))

        assertEquals(false, result.isError)
        assertTrue(result.text.contains("非文本内容"))
    }

    @Test
    fun `内容为空时是正常结果而不是错误`() {
        // 报成错误会让模型以为工具坏了、反复换参数重试
        server.enqueue(json(toolsResult(searchTool)))
        server.enqueue(json(callResult("")))

        val client = mcp()
        val tool = client.listTools().single()
        val result = client.callTool(tool, JsonObject(emptyMap()))

        assertEquals(false, result.isError)
        assertTrue(result.text.contains("没有返回任何内容"))
    }

    @Test
    fun `对端说失败时传给模型的是错误`() {
        server.enqueue(json(toolsResult(searchTool)))
        server.enqueue(json(callResult(textBlock("权限不足"), isError = true)))

        val client = mcp()
        val tool = client.listTools().single()
        val result = client.callTool(tool, JsonObject(emptyMap()))

        assertEquals(true, result.isError)
        assertEquals("权限不足", result.text)
    }

    @Test
    fun `structuredContent 在没有文本块时顶上`() {
        server.enqueue(json(toolsResult(searchTool)))
        server.enqueue(
            json(
                """{"jsonrpc":"2.0","id":2,"result":{"content":[],"structuredContent":{"n":3}}}""",
            ),
        )

        val client = mcp()
        val tool = client.listTools().single()

        assertEquals("""{"n":3}""", client.callTool(tool, JsonObject(emptyMap())).text)
    }

    // ------------------------------------------------------------------ McpTool

    @Test
    fun `只读的对端工具免确认 其余要确认`() {
        val guard = NetworkGuard(listOf(server.url("/").host))
        val client = mcp(guard)

        val readOnly = McpTool("天气", client, descriptor(readOnly = true), guard)
        val writing = McpTool("天气", client, descriptor(readOnly = false), guard)

        assertEquals(false, readOnly.requiresConfirmation)
        assertEquals(true, writing.requiresConfirmation)
    }

    @Test
    fun `声明了任意主机时只读也要确认`() {
        // readOnlyHint 是每次连接时从线上下来的，对端随时能改；
        // 而 network: ["*"] 意味着「请求发去哪由模型决定」——
        // 那种场景下不能靠对端的一句提示免掉确认
        val guard = NetworkGuard(listOf("*"))
        val tool = McpTool("天气", mcp(guard), descriptor(readOnly = true), guard)

        assertEquals(true, tool.requiresConfirmation)
        assertTrue(tool.userSummary.contains("任意主机"))
    }

    @Test
    fun `McpTool 把失败翻成给模型看的话`() = runBlocking {
        server.enqueue(json(toolsResult(searchTool)))
        val guard = NetworkGuard(listOf(server.url("/").host))
        val client = mcp(guard)
        val tool = McpTool("天气", client, client.listTools().single(), guard)

        // 对端回 500：这句话要能让模型知道该不该重试，而不是一句「调用失败」
        server.enqueue(MockResponse(code = 500, body = "boom"))
        val result = tool.execute(JsonObject(emptyMap()))

        assertEquals(true, result.isError)
        assertTrue(result.content.contains("500"))
        assertTrue("要带上服务端说的原因", result.content.contains("boom"))
    }

    @Test
    fun `工具定义直接透传对端的 schema`() {
        // schema 决定模型填不填得对参数，中间做任何转换都会让
        // 对端精心写的描述失效
        val schema =
            """{"type":"object","properties":{"q":{"type":"string","description":"关键词"}},"required":["q"]}"""
        val tool = """{"name":"search","description":"搜索","inputSchema":$schema}"""
        server.enqueue(json(toolsResult(tool)))

        val descriptor = mcp().listTools().single()
        val wrapper = McpTool("天气", mcp(), descriptor, NetworkGuard(listOf(server.url("/").host)))

        assertEquals("搜索", wrapper.definition.description)
        assertEquals(Json.parseToJsonElement(schema), wrapper.definition.parameters)
        assertEquals("search", wrapper.definition.name)
    }

    // ------------------------------------------------------------------ 缓存带回来的年代

    /**
     * 装配路径**读缓存**，拿不到一次 `tools/list` 来触发判定，而 `callTool`
     * 走的 `rpc` 也不会判定。所以上一次谈成的年代必须能从缓存带回来 ——
     * 不带的话，一个旧版对端会在 `tools/call` 上被当成现代版发出去。
     *
     * 这四条是**成对的**：两条正例（带回来之后确实少发/多发请求）
     * 加两条反例（不该重判的时候一次都不多重试）。
     * 只写正例的话，一个「每次失败都重新判定」的实现也能通过 ——
     * 而那个实现会把每一次网络抖动都变成多一轮请求。
     */

    @Test
    fun `缓存说现代版时跳过判定`() {
        server.enqueue(json(toolsResult(searchTool)))

        val tools = mcp(knownEra = McpEra.Modern).listTools()

        assertEquals("已经知道对端是现代版，就不该再探一次", 1, server.requestCount)
        assertEquals("search", tools.single().name)
        assertEquals("2026-07-28", server.takeRequest().headers["MCP-Protocol-Version"])
    }

    @Test
    fun `缓存说旧版时直接握手`() {
        server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25"}}"""))
        server.enqueue(MockResponse(code = 202))
        server.enqueue(json(toolsResult(searchTool)))

        val tools = mcp(knownEra = McpEra.Legacy).listTools()

        assertEquals(3, server.requestCount)
        val initialize = server.takeRequest()
        assertEquals("initialize", initialize.methodName())
        assertNull("旧版才有会话头", initialize.headers["MCP-Protocol-Version"])
        assertEquals("notifications/initialized", server.takeRequest().methodName())
        assertEquals("tools/list", server.takeRequest().methodName())
        assertEquals("search", tools.single().name)
    }

    @Test
    fun `缓存说旧版但握手被拒时按现代版重发一次`() {
        // 对端升级了，缓存还是旧的。404 + 认不出来的 body 正是
        // 「这个请求在旧服务端上不成立」的信号
        server.enqueue(failure(404))
        server.enqueue(json(callResult(textBlock("ok"))))

        val result = mcp(knownEra = McpEra.Legacy).callTool(descriptor(readOnly = true), JsonObject(emptyMap()))

        assertEquals("ok", result.text)
        assertEquals("握手被拒之后只重发一次，不该反复试", 2, server.requestCount)
        assertEquals("initialize", server.takeRequest().methodName())
        val retry = server.takeRequest()
        assertEquals("tools/call", retry.methodName())
        assertEquals("重发时要按现代版发，元数据一个都不能少", "2026-07-28", retry.headers["MCP-Protocol-Version"])
        assertTrue(retry.hasMeta())
    }

    @Test
    fun `自己探出来的旧版不会因为握手失败就重新判定`() {
        // 反例。年代是客户端自己探出来的（不是缓存带来的）时，
        // 握手失败就是失败 —— 再判一次只会把一次真实故障变成两轮请求
        server.enqueue(failure(404))
        server.enqueue(failure(404))

        val e = expectFailure { mcp().listTools() }

        assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("404"))
        assertEquals("只发两个请求：探测一次、握手一次", 2, server.requestCount)
    }

    // ------------------------------------------------------------------ 工具

    private fun descriptor(readOnly: Boolean) = McpToolDescriptor(
        name = "search",
        description = "搜索",
        inputSchema = JsonObject(emptyMap()),
        readOnly = readOnly,
    )

    private fun expectFailure(block: () -> Unit): McpFailure = try {
        block()
        fail("应该失败")
        error("unreachable")
    } catch (e: McpFailure) {
        e
    }
}
