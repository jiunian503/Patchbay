package com.aichat.plugin.runtime

import com.aichat.plugin.manifest.HttpMethod
import com.aichat.plugin.manifest.RequestSpec
import com.aichat.plugin.manifest.ToolSpec
import com.aichat.plugin.permission.NetworkGuard
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 声明式运行时。
 *
 * ## 这个文件里最值钱的用例是「值不能变成结构」
 *
 * 模型给的参数是**不可信输入** —— 提示注入可以让它填出任意字符串。
 * 把 `{{参数}}` 替换进 URL 的最直觉写法（字符串拼接）会被下面这些东西打穿：
 * `../../admin`（路径穿越）、`a&token=1`（参数注入）、`a?x=1`（凭空多出查询串）。
 *
 * 所以每个这样的输入都有一个用例，断言的是**服务端实际收到了什么**，
 * 而不是「我们的代码没抛异常」。用 MockWebServer 而不是假客户端，
 * 就是为了能回头看这个。
 */
class DeclarativeToolTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private val baseUrl: HttpUrl get() = server.url("/").newBuilder().build()

    private fun tool(
        request: RequestSpec,
        settings: Map<String, String> = emptyMap(),
        auth: AuthPlan = AuthPlan.None,
        headers: Map<String, String> = emptyMap(),
        guard: NetworkGuard = NetworkGuard(listOf(server.url("/").host)),
        requiresConfirmation: Boolean? = null,
        name: String = "probe",
    ) = DeclarativeTool(
        pluginName = "测试插件",
        baseUrl = baseUrl,
        spec = ToolSpec(
            name = name,
            description = "测试用工具",
            parameters = buildJsonObject {},
            requiresConfirmation = requiresConfirmation,
            request = request,
        ),
        request = request,
        settings = PluginSettings(settings),
        guard = guard,
        client = client,
        auth = auth,
        headers = headers,
    )

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                null -> put(k, kotlinx.serialization.json.JsonNull)
                is String -> put(k, v)
                is Int -> put(k, v)
                is Boolean -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    }

    private fun ok(body: String = "{}") =
        server.enqueue(MockResponse(code = 200, headers = headersOf("Content-Type", "application/json"), body = body))

    // ================================================================ 值不能变成结构

    @Test
    fun `路径参数里的斜杠不会造出新的路径段`() = runBlocking {
        ok()
        // 提示注入可以让模型填出这种值
        tool(RequestSpec(path = "/users/{{id}}")).execute(args("id" to "../../admin"))
        val recorded = server.takeRequest()

        // 关键断言：`/users/` 后面仍然只有一段。如果拼接实现，
        // 这里会变成 /users/../../admin —— 打到另一个接口上
        assertEquals(listOf("users", "../../admin"), recorded.url.pathSegments)
        assertFalse(recorded.url.encodedPath.contains("/admin"))
    }

    @Test
    fun `路径参数里的问号不会凭空造出查询串`() = runBlocking {
        ok()
        tool(RequestSpec(path = "/p/{{id}}")).execute(args("id" to "a?admin=1"))
        val recorded = server.takeRequest()

        assertNull("问号必须被编码掉", recorded.url.queryParameter("admin"))
        // `?` 是路径段的合法字符，会被编码成 %3F —— 关键是它没有
        // 把后面的东西变成查询串，所以整个值仍然只占一段
        assertTrue(recorded.url.encodedPath, recorded.url.encodedPath.contains("%3F"))
        assertEquals(listOf("p", "a?admin=1"), recorded.url.pathSegments)
    }

    @Test
    fun `查询值里的与号不会注入额外参数`() = runBlocking {
        ok()
        tool(RequestSpec(path = "/p", query = mapOf("q" to "{{q}}"))).execute(args("q" to "a&admin=1"))
        val recorded = server.takeRequest()

        assertEquals("a&admin=1", recorded.url.queryParameter("q"))
        assertNull("与号必须被编码掉", recorded.url.queryParameter("admin"))
    }

    @Test
    fun `查询值里的井号不会截断地址`() = runBlocking {
        ok()
        tool(RequestSpec(path = "/p", query = mapOf("q" to "{{q}}"))).execute(args("q" to "a#frag"))
        val recorded = server.takeRequest()

        assertEquals("a#frag", recorded.url.queryParameter("q"))
        assertNull("井号不能被当成锚点", recorded.url.fragment)
    }

    @Test
    fun `请求体里的引号被转义`() = runBlocking {
        ok()
        val note = """he said "hi" and \ done"""
        val request = RequestSpec(
            method = HttpMethod.Post,
            path = "/p",
            body = buildJsonObject { put("note", "{{note}}") },
        )
        tool(request).execute(args("note" to note))
        val body = server.takeRequest().body?.utf8().orEmpty()

        // 解析得回来，说明转义是对的；否则服务端会收到一个坏 JSON。
        // 比 content 而不是比 toString()：后者带引号和转义，
        // 断言会变成在测 JSON 编码器的输出格式
        val parsed = Json.parseToJsonElement(body) as JsonObject
        assertEquals(note, (parsed["note"] as JsonPrimitive).content)
        assertTrue("原文里的反斜杠必须被转义", body.contains("""\\"""))
    }

    @Test
    fun `整段是一个占位符时保留原始类型`() = runBlocking {
        ok()
        val request = RequestSpec(
            method = HttpMethod.Post,
            path = "/p",
            body = buildJsonObject { put("count", "{{count}}") },
        )
        tool(request).execute(args("count" to 5))
        val body = server.takeRequest().body?.utf8().orEmpty()

        // 按字符串替换会得到 {"count":"5"}，而服务端 schema 要的是数字
        assertEquals("""{"count":5}""", body)
    }

    @Test
    fun `baseUrl 的路径前缀被保留`() = runBlocking {
        ok()
        // baseUrl = http://host/api/v2
        val prefixed = server.url("/api/v2").newBuilder().build()
        val t = DeclarativeTool(
            pluginName = "p",
            baseUrl = prefixed,
            spec = ToolSpec(name = "t", description = "d", parameters = buildJsonObject {}, request = null),
            request = RequestSpec(path = "/users"),
            settings = PluginSettings.Empty,
            guard = NetworkGuard(listOf(server.url("/").host)),
            client = client,
        )
        t.execute(buildJsonObject {})
        val recorded = server.takeRequest()

        // 保留前缀是刻意的：丢掉它会让作者写了一个看起来生效、
        // 实际不生效的前缀，然后对着 404 怀疑人生
        assertEquals("/api/v2/users", recorded.url.encodedPath)
    }

    // ================================================================ 缺失值

    @Test
    fun `路径参数缺失时报错并指出占位符原文`() = runBlocking {
        val result = tool(RequestSpec(path = "/users/{{id}}")).execute(buildJsonObject {})

        assertTrue(result.isError)
        // 报出来的必须是作者写的那个写法
        assertTrue(result.content, result.content.contains("{{id}}"))
        assertEquals("不应该真的发出去", 0, server.requestCount)
    }

    @Test
    fun `配置项缺失时报错并带上 settings 前缀`() = runBlocking {
        val result = tool(RequestSpec(path = "/{{settings.token}}")).execute(buildJsonObject {})

        assertTrue(result.isError)
        // 曾经这里会报成 `{{token}}` —— 作者拿着这句话回清单里找，找不到
        assertTrue(result.content, result.content.contains("{{settings.token}}"))
        assertFalse(result.content, result.content.contains("`{{token}}`"))
    }

    @Test
    fun `查询参数缺失时省略而不是发空值`() = runBlocking {
        ok()
        val request = RequestSpec(path = "/p", query = mapOf("q" to "{{q}}", "fixed" to "1"))
        tool(request).execute(buildJsonObject {})
        val recorded = server.takeRequest()

        // `?q=` 和「没有 q」对服务端常常是两种行为，而作者写可选参数时的意图
        // 几乎一定是后者
        assertNull(recorded.url.queryParameter("q"))
        assertEquals("1", recorded.url.queryParameter("fixed"))
    }

    @Test
    fun `请求体里缺失的占位符变成 null 而不是花括号原文`() = runBlocking {
        ok()
        val request = RequestSpec(
            method = HttpMethod.Post,
            path = "/p",
            body = buildJsonObject { put("a", "{{a}}"); put("b", "x{{b}}y") },
        )
        tool(request).execute(buildJsonObject {})
        val body = server.takeRequest().body?.utf8().orEmpty()

        assertEquals("""{"a":null,"b":null}""", body)
        assertFalse("不能把花括号原文发给服务端", body.contains("{{"))
    }

    // ================================================================ 认证

    @Test
    fun `bearer 认证带上密钥`() = runBlocking {
        ok()
        tool(
            RequestSpec(path = "/p"),
            settings = mapOf("key" to "SK-123"),
            auth = AuthPlan.Bearer("key"),
        ).execute(buildJsonObject {})

        assertEquals("Bearer SK-123", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `header 认证带上自定义头`() = runBlocking {
        ok()
        tool(
            RequestSpec(path = "/p"),
            settings = mapOf("key" to "SK-123"),
            auth = AuthPlan.Header("key", "X-Api-Key"),
        ).execute(buildJsonObject {})

        assertEquals("SK-123", server.takeRequest().headers["X-Api-Key"])
    }

    @Test
    fun `query 认证把密钥放进查询串`() = runBlocking {
        ok()
        tool(
            RequestSpec(path = "/p"),
            settings = mapOf("key" to "SK-123"),
            auth = AuthPlan.Query("key", "api_key"),
        ).execute(buildJsonObject {})

        assertEquals("SK-123", server.takeRequest().url.queryParameter("api_key"))
    }

    @Test
    fun `密钥没填时不发裸请求而是报错`() = runBlocking {
        val result = tool(
            RequestSpec(path = "/p"),
            settings = emptyMap(),
            auth = AuthPlan.Bearer("key"),
        ).execute(buildJsonObject {})

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("key"))
        // 关键：没有真的发出去。发出去的话服务端会回 401，
        // 用户会以为密钥填错了，而真正的原因是「还没填」
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `密钥填了空串也当成没填`() = runBlocking {
        val result = tool(
            RequestSpec(path = "/p"),
            settings = mapOf("key" to "   "),
            auth = AuthPlan.Bearer("key"),
        ).execute(buildJsonObject {})

        assertTrue(result.isError)
        assertEquals("`Authorization: Bearer ` 和「没有这个头」对服务端是两种请求", 0, server.requestCount)
    }

    // ================================================================ 插件级请求头

    @Test
    fun `插件级请求头被带上`() = runBlocking {
        ok()
        tool(
            RequestSpec(path = "/p"),
            headers = mapOf("X-Trace" to "abc", "Accept-Language" to "zh-CN"),
        ).execute(buildJsonObject {})

        val recorded = server.takeRequest()
        assertEquals("abc", recorded.headers["X-Trace"])
        assertEquals("zh-CN", recorded.headers["Accept-Language"])
        assertEquals("没声明 Accept 时给默认值", "application/json", recorded.headers["Accept"])
    }

    @Test
    fun `作者声明了 Accept 时默认值不覆盖它`() = runBlocking {
        ok()
        tool(RequestSpec(path = "/p"), headers = mapOf("Accept" to "application/xml"))
            .execute(buildJsonObject {})

        assertEquals("application/xml", server.takeRequest().headers["Accept"])
    }

    // ================================================================ 网络白名单

    @Test
    fun `主机不在白名单时被拦下且不发请求`() = runBlocking {
        val result = tool(
            RequestSpec(path = "/p"),
            guard = NetworkGuard(listOf("api.example.com")),
        ).execute(buildJsonObject {})

        assertTrue(result.isError)
        assertTrue("拒绝原因要写给模型看", result.content.contains("api.example.com"))
        assertEquals(0, server.requestCount)
    }

    // ================================================================ 工具级主机名覆盖

    /**
     * `MockWebServer` 默认绑 `localhost`，而回环上另一个名字是 `127.0.0.1`。
     * 两个名字指向同一台服务器，所以「换主机名」这个行为可以在**同一个**
     * server 上验出来 —— 不需要再起一台。
     */
    private val otherHost: String
        get() = if (server.url("/").host == "localhost") "127.0.0.1" else "localhost"

    @Test
    fun `工具可以用 host 覆盖 baseUrl 的主机名`() = runBlocking {
        ok()
        val result = tool(
            RequestSpec(method = HttpMethod.Get, host = otherHost, path = "/probe"),
            guard = NetworkGuard(listOf(otherHost)),
        ).execute(buildJsonObject {})

        assertFalse("覆盖后应该正常发出并拿到响应：${result.content}", result.isError)

        // 断言 Host 头而不是 `recorded.url.host`：MockWebServer 的 `url`
        // 是拿请求行里的路径拼到**它自己**的 base URL 上算出来的，
        // 永远是 `localhost` —— 拿它断言等于什么也没测
        val recorded = server.takeRequest()
        assertTrue(
            "实际发出去的主机名是 ${recorded.headers["Host"]}，不是 $otherHost",
            recorded.headers["Host"]?.startsWith(otherHost) == true,
        )
    }

    @Test
    fun `host 覆盖也要过白名单`() = runBlocking {
        val result = tool(
            RequestSpec(method = HttpMethod.Get, host = "evil.example.com", path = "/probe"),
            // 白名单里只有 baseUrl 的主机 —— 覆盖出来的主机不在里面
            guard = NetworkGuard(listOf(server.url("/").host)),
        ).execute(buildJsonObject {})

        assertTrue("host 覆盖绕过了白名单，这是最严重的一类问题", result.isError)
        assertTrue("拒绝原因要说清是哪个主机", result.content.contains("evil.example.com"))
        assertEquals("被拦下时一个请求都不该发出去", 0, server.requestCount)
    }

    @Test
    fun `userSummary 报的是覆盖后的主机名`() {
        val summary = tool(
            RequestSpec(method = HttpMethod.Post, host = otherHost, path = "/p"),
            guard = NetworkGuard(listOf(otherHost)),
        ).userSummary

        // 用户是照着这句话决定放不放行的。这里报成 baseUrl 的主机名，
        // 等于让他为 A 主机授权、请求实际发往 B 主机
        assertTrue("弹框里说的是别的主机：$summary", summary.contains(otherHost))
        assertFalse(
            "弹框里不该出现 baseUrl 的主机名：$summary",
            summary.contains(server.url("/").host),
        )
    }

    // ================================================================ 结果处理

    @Test
    fun `responsePath 抽取指定的段`() = runBlocking {
        ok("""{"current":{"temp":21,"code":3},"other":1}""")
        val result = tool(RequestSpec(path = "/p", responsePath = "current")).execute(buildJsonObject {})

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("temp"))
        assertFalse("只该给抽取出来的那一段", result.content.contains("other"))
    }

    @Test
    fun `responsePath 支持数组下标`() = runBlocking {
        ok("""{"results":[{"name":"A"},{"name":"B"}]}""")
        val result = tool(RequestSpec(path = "/p", responsePath = "results[1].name")).execute(buildJsonObject {})

        assertEquals("B", result.content)
    }

    @Test
    fun `响应不是 JSON 时回退到原文`() = runBlocking {
        // 服务端返回 HTML 错误页时，把原文给模型看比报
        // 「responsePath 取不到」有用得多
        server.enqueue(MockResponse(code = 200, body = "<html>maintenance</html>"))
        val result = tool(RequestSpec(path = "/p", responsePath = "a.b")).execute(buildJsonObject {})

        assertFalse(result.isError)
        assertTrue(result.content, result.content.contains("maintenance"))
    }

    @Test
    fun `responsePath 指向不存在的键时回退到原文`() = runBlocking {
        ok("""{"a":1}""")
        val result = tool(RequestSpec(path = "/p", responsePath = "nope")).execute(buildJsonObject {})

        assertFalse(result.isError)
        assertTrue(result.content, result.content.contains("\"a\""))
    }

    @Test
    fun `HTTP 错误带上服务端给的原因`() = runBlocking {
        server.enqueue(MockResponse(code = 401, body = """{"error":"invalid api key"}"""))
        val result = tool(RequestSpec(path = "/p")).execute(buildJsonObject {})

        assertTrue(result.isError)
        // 只回一个状态码的话模型只能瞎猜
        assertTrue(result.content, result.content.contains("401"))
        assertTrue(result.content, result.content.contains("invalid api key"))
    }

    @Test
    fun `超长响应被截断`() = runBlocking {
        ok("\"" + "x".repeat(30_000) + "\"")
        val result = tool(RequestSpec(path = "/p")).execute(buildJsonObject {})

        assertFalse(result.isError)
        assertTrue(result.content, result.content.contains("已截断"))
        assertTrue("截断后不该有 3 万字", result.content.length < 21_000)
    }

    @Test
    fun `网络不通时报成可重试而不是被拒绝`() = runBlocking {
        server.close() // 让连接被拒
        val result = tool(RequestSpec(path = "/p")).execute(buildJsonObject {})

        assertTrue(result.isError)
        // 和「被白名单拒绝」分开：前者可以重试，后者重试没有意义。
        // 混成一句话的话，模型会对着一个永远不会成功的地址反复试
        assertTrue(result.content, result.content.contains("可以稍后重试"))
    }

    @Test
    fun `读响应失败那句也说了可以重试`() {
        // 这条守的是「读 body 时才断」那条分支 —— 它和上面「请求阶段就断」那条
        // 是同一类瞬时故障，动作也必须一样。
        //
        // ⚠️ **为什么测的是这个函数而不是端到端**：那条分支在 JVM 测试栈上造不出来 ——
        // `mockwebserver3` 5.x 移除了 `SocketPolicy`；谎报 `Content-Length` 无效
        // （MockWebServer 自己算）；自定义 `ResponseBody` 要 `Okio.buffer(...)`，
        // 而那个 Java 入口已废弃，换成扩展函数又得改本文件的 import 区。
        // 所以退一步：把「那句话」抽成函数直接测 —— 生产代码里 `readResult` 的
        // catch 调的就是它。**判据是「这句话里有没有那个动作」**，这正是要保证的事。
        val probe = tool(RequestSpec(path = "/p"))
        val msg = probe.readFailedMessage("读 body 时连接断了")

        assertTrue("原始原因要带上：$msg", msg.contains("读 body 时连接断了"))
        assertTrue("得给重试这条路，和「请求失败」那条一致：$msg", msg.contains("可以稍后重试"))

        // detail 由调用点传 `errorDetail(e)` —— 「message 为 null 时说什么」那条语义
        // 现在只有一份实现，在 `:domain` 的 `ErrorTextTest` 里测（这里不再重复测一遍）
    }

    // ================================================================ 确认策略

    @Test
    fun `GET 且作者没声明时不需要确认`() {
        assertFalse(tool(RequestSpec(path = "/p")).requiresConfirmation)
    }

    @Test
    fun `非 GET 且作者没声明时需要确认`() {
        // 作者没写就是「没说」，宿主按 RFC 9110 的安全方法兜底 ——
        // 只有 GET/HEAD 是安全的
        assertTrue(tool(RequestSpec(method = HttpMethod.Post, path = "/p")).requiresConfirmation)
        assertTrue(tool(RequestSpec(method = HttpMethod.Delete, path = "/p")).requiresConfirmation)
    }

    @Test
    fun `作者明确说不用确认时听作者的`() {
        assertFalse(
            tool(RequestSpec(method = HttpMethod.Post, path = "/p"), requiresConfirmation = false)
                .requiresConfirmation,
        )
    }

    @Test
    fun `声明了任意主机时无论作者怎么写都要确认`() {
        // 任意主机 = 把「请求发去哪」交给模型决定，
        // 和内置的 fetch_url 是同一件事，而 fetch_url 是要确认的
        val t = tool(
            RequestSpec(path = "/p"),
            guard = NetworkGuard(listOf("*")),
            requiresConfirmation = false,
        )
        assertTrue(t.requiresConfirmation)
    }

    @Test
    fun `userSummary 说清发给谁以及为什么问`() {
        val summary = tool(RequestSpec(method = HttpMethod.Post, path = "/p")).userSummary

        assertTrue(summary, summary.contains("测试插件"))
        assertTrue(summary, summary.contains(server.url("/").host))
        assertTrue(summary, summary.contains("POST"))
        assertTrue("要解释为什么弹窗，否则用户会觉得 App 在乱问", summary.contains("确认"))
    }
}
