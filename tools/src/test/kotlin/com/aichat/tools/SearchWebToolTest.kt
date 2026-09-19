package com.aichat.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 联网搜索工具。
 *
 * ## 为什么用真实 HTTP（MockWebServer）
 *
 * 三种后端的差别全在**线上行为**上：SearXNG 的路径补全和 json 格式开关、
 * Brave 的自定义头、Tavily 的 POST 体。假客户端能测出「我调用了自己写的解析函数」，
 * 测不出「请求真的长成了那个样子」—— 而后者才是会坏的部分。
 *
 * 用 `runBlocking` 而非 `runTest`：请求发生在 OkHttp 自己的线程上，是真实时间。
 */
class SearchWebToolTest {

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

    // ---------- 夹具 ----------

    private fun source(config: WebSearchConfig?): WebSearchSource =
        object : WebSearchSource {
            override fun configured() = config != null
            override suspend fun config(): WebSearchConfig? = config
        }

    private fun tool(config: WebSearchConfig) = SearchWebTool(source(config))

    /** SearXNG 填的是**实例地址**，不是接口地址 —— 这正是要测的那件事。 */
    private fun searxng(apiKey: String? = null) =
        WebSearchConfig(WebSearchBackend.SEARXNG, server.url("/").toString(), apiKey)

    private fun brave(apiKey: String? = "brave-key") =
        WebSearchConfig(
            WebSearchBackend.BRAVE,
            server.url("/res/v1/web/search").toString(),
            apiKey,
        )

    private fun tavily(apiKey: String? = "tvly-key") =
        WebSearchConfig(WebSearchBackend.TAVILY, server.url("/search").toString(), apiKey)

    private fun args(query: String) = buildJsonObject { put(SearchWebTool.QUERY, query) }

    private fun json(body: String) = MockResponse(
        code = 200,
        headers = headersOf("Content-Type", "application/json"),
        body = body,
    )

    /** 内置后端：零配置，只要一个地址（真实运行时由 `defaultEndpoint` 提供）。 */
    private fun bing() =
        WebSearchConfig(WebSearchBackend.BING_HTML, server.url("/search").toString())

    private fun html(body: String) = MockResponse(
        code = 200,
        headers = headersOf("Content-Type", "text/html; charset=utf-8"),
        body = body,
    )

    // ---------- SearXNG ----------

    @Test
    fun `SearXNG 正常搜索`() = runBlocking {
        server.enqueue(
            json(
                """
                {"query":"kotlin","results":[
                  {"title":"Kotlin 官网","url":"https://kotlinlang.org/","content":"Kotlin 是一门静态类型语言"},
                  {"title":"Kotlin 文档","url":"https://kotlinlang.org/docs","content":"官方文档"}
                ]}
                """.trimIndent(),
            ),
        )

        val result = tool(searxng()).execute(args("kotlin"))

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("Kotlin 官网"))
        assertTrue(result.content, result.content.contains("https://kotlinlang.org/"))
        assertTrue(result.content, result.content.contains("静态类型语言"))
        assertTrue(result.content, result.content.contains("2 条"))
    }

    /**
     * 用户填 `https://searx.be`，我们要请求的是 `https://searx.be/search`。
     *
     * 这是这个工具最容易出错的一处：少补一段就是 404，多补一段就是 404，
     * 而两种 404 从错误信息上完全一样。
     */
    @Test
    fun `SearXNG 的实例地址会自动补上 search 路径`() = runBlocking {
        server.enqueue(json("""{"results":[]}"""))

        tool(searxng()).execute(args("a"))

        assertEquals("/search", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `SearXNG 子路径部署也能补对`() = runBlocking {
        server.enqueue(json("""{"results":[]}"""))
        val config = WebSearchConfig(WebSearchBackend.SEARXNG, server.url("/searx").toString())

        tool(config).execute(args("a"))

        assertEquals("/searx/search", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `SearXNG 用户自己写全了 search 路径时不重复补`() = runBlocking {
        server.enqueue(json("""{"results":[]}"""))
        val config = WebSearchConfig(WebSearchBackend.SEARXNG, server.url("/search").toString())

        tool(config).execute(args("a"))

        assertEquals("/search", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `SearXNG 请求带 q 和 format 参数`() = runBlocking {
        server.enqueue(json("""{"results":[]}"""))

        tool(searxng()).execute(args("android 16"))

        val recorded = server.takeRequest()
        assertEquals("android 16", recorded.url.queryParameter("q"))
        // 不带 format=json 的话 SearXNG 返回的是 HTML 页面
        assertEquals("json", recorded.url.queryParameter("format"))
    }

    @Test
    fun `SearXNG 不需要密钥也能跑`() = runBlocking {
        server.enqueue(json("""{"results":[]}"""))

        val result = tool(searxng(apiKey = null)).execute(args("a"))

        assertFalse(result.content, result.isError)
    }

    /**
     * SearXNG 默认不输出 JSON，这一条是最常见的配置坑。
     * 错误信息必须点名 `settings.yml` —— 只说「403」用户查不到任何东西。
     */
    @Test
    fun `SearXNG 的 403 会指出 json 格式没开`() = runBlocking {
        server.enqueue(MockResponse(code = 403, body = "<html>Forbidden</html>"))

        val result = tool(searxng()).execute(args("a"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("settings.yml"))
        assertTrue(result.content, result.content.contains("json"))
    }

    // ---------- Brave ----------

    @Test
    fun `Brave 正常搜索并解析 web 层`() = runBlocking {
        server.enqueue(
            json(
                """
                {"web":{"results":[
                  {"title":"Rust","url":"https://rust-lang.org/","description":"Rust 是一门系统编程语言"}
                ]}}
                """.trimIndent(),
            ),
        )

        val result = tool(brave()).execute(args("rust"))

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("Rust"))
        assertTrue(result.content, result.content.contains("系统编程语言"))
    }

    @Test
    fun `Brave 用 X-Subscription-Token 头传密钥`() = runBlocking {
        server.enqueue(json("""{"web":{"results":[]}}"""))

        tool(brave("my-brave-key")).execute(args("a"))

        assertEquals("my-brave-key", server.takeRequest().headers["X-Subscription-Token"])
    }

    @Test
    fun `Brave 带上 count 参数`() = runBlocking {
        server.enqueue(json("""{"web":{"results":[]}}"""))

        tool(brave()).execute(args("a"))

        assertEquals(
            SearchWebTool.MAX_RESULTS.toString(),
            server.takeRequest().url.queryParameter("count"),
        )
    }

    @Test
    fun `Brave 没填密钥时给出可操作的提示`() = runBlocking {
        val result = tool(brave(apiKey = null)).execute(args("a"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("API Key"))
        assertTrue(result.content, result.content.contains("设置"))
        // 不能一个请求都没发出去却让服务端收到东西
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `Brave 的 401 指向设置页而不是让模型重试`() = runBlocking {
        server.enqueue(MockResponse(code = 401, body = "unauthorized"))

        val result = tool(brave()).execute(args("a"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("401"))
        assertTrue(result.content, result.content.contains("联网搜索"))
    }

    // ---------- Tavily ----------

    @Test
    fun `Tavily 正常搜索`() = runBlocking {
        server.enqueue(
            json(
                """
                {"results":[
                  {"title":"Go","url":"https://go.dev/","content":"Go 是 Google 开发的语言"}
                ]}
                """.trimIndent(),
            ),
        )

        val result = tool(tavily()).execute(args("golang"))

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("Go 是 Google 开发的语言"))
    }

    @Test
    fun `Tavily 用 POST 并把查询放进请求体`() = runBlocking {
        server.enqueue(json("""{"results":[]}"""))

        tool(tavily()).execute(args("golang"))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("Bearer tvly-key", recorded.headers["Authorization"])

        val body = recorded.body?.utf8().orEmpty()
        assertTrue(body, body.contains("golang"))
        assertTrue(body, body.contains("max_results"))
    }

    @Test
    fun `Tavily 没填密钥时不发请求`() = runBlocking {
        val result = tool(tavily(apiKey = null)).execute(args("a"))

        assertTrue(result.isError)
        assertEquals(0, server.requestCount)
    }

    // ---------- 结果处理 ----------

    @Test
    fun `结果条数被截到上限`() = runBlocking {
        val items = (1..30).joinToString(",") {
            """{"title":"T$it","url":"https://e.com/$it","content":"c$it"}"""
        }
        server.enqueue(json("""{"results":[$items]}"""))

        val result = tool(searxng()).execute(args("a"))

        assertTrue(result.content, result.content.contains("[${SearchWebTool.MAX_RESULTS}]"))
        assertFalse("第 7 条不该出现", result.content.contains("[${SearchWebTool.MAX_RESULTS + 1}]"))
    }

    /** 摘要里的 `<b>` 是搜索引擎的高亮标签，原样给模型会让它以为那是正文的一部分。 */
    @Test
    fun `摘要里的 HTML 被清掉`() = runBlocking {
        server.enqueue(
            json(
                """{"results":[{"title":"<b>Kotlin</b>","url":"https://e.com",
                   "content":"这是 <b>加粗</b> 的<script>var x=1;</script>摘要"}]}""",
            ),
        )

        val result = tool(searxng()).execute(args("a"))

        assertFalse(result.content, result.content.contains("<b>"))
        assertFalse(result.content, result.content.contains("var x"))
        assertTrue(result.content, result.content.contains("加粗"))
    }

    @Test
    fun `没有网址的结果被丢掉`() = runBlocking {
        server.enqueue(
            json(
                """{"results":[{"title":"没有网址","content":"x"},
                   {"title":"有网址","url":"https://e.com","content":"y"}]}""",
            ),
        )

        val result = tool(searxng()).execute(args("a"))

        assertTrue(result.content, result.content.contains("1 条"))
        assertTrue(result.content, result.content.contains("https://e.com"))
    }

    /**
     * 空结果**不是错误**。
     *
     * 返回 error 会让模型以为工具坏了，于是换个说法反复重试同一个查询 ——
     * 而正确答案是「网上确实没有」。这跟 `search_history` 是同一个判断。
     */
    @Test
    fun `零结果返回成功并引导换关键词`() = runBlocking {
        server.enqueue(json("""{"results":[]}"""))

        val result = tool(searxng()).execute(args("asdfghjkl"))

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("没有返回任何结果"))
        assertTrue(result.content, result.content.contains("关键词"))
    }

    /** 自建实例可以改模板、网关可以包一层 —— 解析不出来时不能崩。 */
    @Test
    fun `响应结构不认识时当成零结果而不是崩溃`() = runBlocking {
        server.enqueue(json("""{"unexpected":{"deeply":{"nested":true}}}"""))

        val result = tool(searxng()).execute(args("a"))

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("没有返回任何结果"))
    }

    /**
     * 「200 但不是 JSON」和「200 但没有结果」必须分开报。
     *
     * 实测公共 SearXNG 实例里，大多数要么关掉了 json 输出、要么挡在一层
     * 人机验证后面 —— 两者都是 HTTP 200，返回的都是 HTML。报成
     * 「没有结果」的话，模型会去换关键词重试，而真正该做的是换实例。
     */
    @Test
    fun `返回 HTML 时指出这是配置问题而不是没搜到`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "text/html"),
                body = "<!doctype html><html><title>Making sure you're not a bot!</title></html>",
            ),
        )

        val result = tool(searxng()).execute(args("a"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("HTML"))
        assertTrue(result.content, result.content.contains("人机验证"))
        assertFalse("不能说成『没搜到』", result.content.contains("没有返回任何结果"))
    }

    @Test
    fun `响应根本不是 JSON 时也不崩`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = "随便一段不是 JSON 的文本"))

        val result = tool(searxng()).execute(args("a"))

        assertTrue(result.isError)
        assertFalse("不能说成『没搜到』", result.content.contains("没有返回任何结果"))
    }

    /**
     * 总长度上限兜的是「字段长度不由我们决定」的那部分。
     *
     * 条数和单条摘要都封顶之后，正常响应够不到总上限 —— 所以这条用例
     * 必须用**超长标题 + 超长网址**去构造，那才是这个上限真正要挡的东西。
     */
    @Test
    fun `结果过长时截断并说明`() = runBlocking {
        val longTitle = "标题".repeat(400)
        val longUrl = "https://e.com/" + "p".repeat(600)
        val longSnippet = "很长的摘要".repeat(200)
        val items = (1..6).joinToString(",") {
            """{"title":"$longTitle","url":"$longUrl/$it","content":"$longSnippet"}"""
        }
        server.enqueue(json("""{"results":[$items]}"""))

        val result = tool(searxng()).execute(args("a"))

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("截断"))
        assertTrue(
            "结果长度 ${result.content.length} 超出预期",
            result.content.length <= SearchWebTool.MAX_CHARS + 600,
        )
    }

    /** 单条标题也要封顶 —— 垃圾站会往 title 里塞整段 SEO 文本。 */
    @Test
    fun `超长标题被截到上限`() = runBlocking {
        server.enqueue(
            json(
                """{"results":[{"title":"${"T".repeat(500)}","url":"https://e.com",
                   "content":"摘要"}]}""",
            ),
        )

        val result = tool(searxng()).execute(args("a"))

        assertFalse(result.content, result.isError)
        assertFalse("超长标题没被截断", result.content.contains("T".repeat(SearchWebTool.MAX_TITLE_CHARS + 1)))
    }

    // ---------- 错误路径 ----------

    @Test
    fun `缺 query 参数时提示怎么填`() = runBlocking {
        val result = tool(searxng()).execute(buildJsonObject { })

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains(SearchWebTool.QUERY))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `后端没配好时让模型别再试`() = runBlocking {
        val result = SearchWebTool(source(null)).execute(args("a"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("不要再尝试"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `地址不合法时报错并指向设置页`() = runBlocking {
        val config = WebSearchConfig(WebSearchBackend.SEARXNG, "这不是地址")

        val result = tool(config).execute(args("a"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("联网搜索"))
    }

    @Test
    fun `429 提示配额而不是重试`() = runBlocking {
        server.enqueue(MockResponse(code = 429, body = "rate limited"))

        val result = tool(brave()).execute(args("a"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("429"))
        assertTrue(result.content, result.content.contains("配额"))
    }

    @Test
    fun `5xx 说明是服务端问题`() = runBlocking {
        server.enqueue(MockResponse(code = 503, body = "unavailable"))

        val result = tool(brave()).execute(args("a"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("503"))
        assertTrue(result.content, result.content.contains("服务端"))
    }

    @Test
    fun `连不上时报错而不是抛异常`() = runBlocking {
        val config = brave()
        server.close()

        val result = tool(config).execute(args("a"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("连不上"))
    }

    // ---------- 纯函数：地址解析 ----------

    @Test
    fun `SearXNG 实例地址补成 search 接口`() {
        val url = WebSearchConfig(WebSearchBackend.SEARXNG, "https://searx.be").resolveEndpoint()
        assertEquals("https://searx.be/search", url.toString())
    }

    @Test
    fun `SearXNG 地址结尾带斜杠也补对`() {
        val url = WebSearchConfig(WebSearchBackend.SEARXNG, "https://searx.be/").resolveEndpoint()
        assertEquals("https://searx.be/search", url.toString())
    }

    @Test
    fun `非 SearXNG 后端不改路径`() {
        val url = WebSearchConfig(WebSearchBackend.TAVILY, "https://api.tavily.com/search").resolveEndpoint()
        assertEquals("https://api.tavily.com/search", url.toString())
    }

    @Test
    fun `Brave 与 Tavily 留空时回退到官方地址`() {
        val braveUrl = WebSearchConfig(WebSearchBackend.BRAVE, "").resolveEndpoint()
        val tavilyUrl = WebSearchConfig(WebSearchBackend.TAVILY, "  ").resolveEndpoint()
        assertEquals("https://api.search.brave.com/res/v1/web/search", braveUrl.toString())
        assertEquals("https://api.tavily.com/search", tavilyUrl.toString())
    }

    /** SearXNG 没有公共默认实例 —— 留空必须解析不出来，而不是偷偷用一个示例地址。 */
    @Test
    fun `SearXNG 留空时没有默认地址`() {
        assertNull(WebSearchConfig(WebSearchBackend.SEARXNG, "").resolveEndpoint())
    }

    @Test
    fun `非 http 协议的地址解析不出来`() {
        assertNull(WebSearchConfig(WebSearchBackend.SEARXNG, "file:///etc/hosts").resolveEndpoint())
    }

    // ---------- 纯函数：完整性判断 ----------

    @Test
    fun `SearXNG 不填密钥也算配好了`() {
        val config = WebSearchConfig(WebSearchBackend.SEARXNG, "https://searx.be")
        assertTrue(config.isComplete())
    }

    @Test
    fun `Brave 和 Tavily 缺密钥时算没配好`() {
        assertFalse(WebSearchConfig(WebSearchBackend.BRAVE, "").isComplete())
        assertFalse(WebSearchConfig(WebSearchBackend.BRAVE, "", "  ").isComplete())
        assertFalse(WebSearchConfig(WebSearchBackend.TAVILY, "", null).isComplete())
        assertTrue(WebSearchConfig(WebSearchBackend.BRAVE, "", "k").isComplete())
    }

    // ---------- 后端 id ----------

    @Test
    fun `后端 id 能双向对上`() {
        WebSearchBackend.entries.forEach { backend ->
            assertEquals(backend, WebSearchBackend.fromId(backend.id))
        }
    }

    @Test
    fun `认不出来的后端 id 返回 null`() {
        assertNull(WebSearchBackend.fromId(null))
        assertNull(WebSearchBackend.fromId(""))
        assertNull(WebSearchBackend.fromId("   "))
        assertNull(WebSearchBackend.fromId("google"))
    }

    // ---------- 设置页的「测试」按钮 ----------

    @Test
    fun `probe 走的是与模型调用完全相同的路径`() = runBlocking {
        server.enqueue(
            json("""{"results":[{"title":"T","url":"https://e.com","content":"c"}]}"""),
        )

        val result = SearchWebTool.probe(searxng())

        assertFalse(result.content, result.isError)
        // 探测也要真的发出去，并且用上了配置里的地址
        assertEquals("/search", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `probe 失败时把原因原样带回来`() = runBlocking {
        server.enqueue(MockResponse(code = 401, body = "nope"))

        val result = SearchWebTool.probe(brave())

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("401"))
    }

    /**
     * `probe` 必须在**调用方线程之外**跑。
     *
     * ## 这条断言为什么是这个形状
     *
     * `Tool.execute` 的约定是「调用方已经切到后台调度器」—— 对话路径由
     * `ConversationEngine` 的 `withContext(Dispatchers.IO)` 保证，设置页没有
     * 这个保证（它从 `viewModelScope` 直接调）。少了这一句，在真机上点
     * 「测试」会抛 `NetworkOnMainThreadException` **并且整个 App 当场闪退**
     * （实测踩到过）。
     *
     * 而 `NetworkOnMainThreadException` 是 Android 运行时才有的检查，
     * 纯 JVM 的 `:tools:test` 里复现不出来 —— 所以这里断言的是最接近它的
     * 那个可观测事实：**请求不在调用方线程上发出**。
     */
    @Test
    fun `probe 不在调用方线程上执行`() = runBlocking {
        val callerThread = Thread.currentThread().name
        var requestThread = ""

        val capturing = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestThread = Thread.currentThread().name
                chain.proceed(chain.request())
            }
            .build()

        server.enqueue(json("""{"results":[]}"""))

        SearchWebTool.probe(searxng(), client = capturing)

        assertTrue("拦截器没被调到", requestThread.isNotEmpty())
        assertNotEquals(
            "probe 在调用方线程（$callerThread）上发了请求 —— 真机上会 NetworkOnMainThreadException",
            callerThread,
            requestThread,
        )
    }

    /** 异常不能漏出去：设置页的「测试」按钮崩掉的话，整个页面跟着挂。 */
    @Test
    fun `probe 把异常转成错误而不是抛出去`() = runBlocking {
        val exploding = OkHttpClient.Builder()
            .addInterceptor { throw IllegalStateException("boom") }
            .build()

        val result = SearchWebTool.probe(searxng(), client = exploding)

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("测试失败"))
    }

    // ---------- 内置后端（必应结果页 HTML） ----------

    /**
     * 一段贴近真实必应结果页的片段。
     *
     * 形态按实测的中国版写：标题链接是 `<a href="..."><h2>标题</h2></a>`
     * （`<a>` 在外面，不是 `<h2>` 里面），而且 `<li>` 上除 `b_algo`
     * 还有别的属性 —— 解析要是写死了 `class="b_algo"`，这里就该红。
     */
    private fun bingPage(vararg items: Triple<String, String, String>): String = buildString {
        append("""<html><body><ol id="b_results">""")
        items.forEach { (url, title, snippet) ->
            append("""<li class="b_algo" data-id iid=SERP.1>""")
            append("""<div class="b_tpcn"><a class="tilk" href="$url">icon</a></div>""")
            append("""<a href="$url"><h2 class="">$title</h2></a>""")
            append("""<div class="b_caption"><p class="b_lineclamp3">$snippet</p></div>""")
            append("</li>")
        }
        append("</ol></body></html>")
    }

    @Test
    fun `内置后端从结果页里抠出标题网址和摘要`() = runBlocking {
        server.enqueue(
            html(
                bingPage(
                    Triple("https://example.com/a", "第一条 <strong>标题</strong>", "第一段摘要"),
                    Triple("https://example.com/b", "第二条", "第二段摘要"),
                ),
            ),
        )

        val result = tool(bing()).execute(args("test"))

        assertFalse(result.content, result.isError)
        // 标题里的 <strong> 要去掉，但不能把词也去掉
        assertTrue(result.content, result.content.contains("第一条 标题"))
        assertTrue(result.content, result.content.contains("https://example.com/a"))
        assertTrue(result.content, result.content.contains("第一段摘要"))
        assertTrue(result.content, result.content.contains("https://example.com/b"))
    }

    @Test
    fun `内置后端的网址做了实体解码`() = runBlocking {
        server.enqueue(
            html(bingPage(Triple("https://example.com/x?a=1&amp;b=2", "带参数的", "摘要"))),
        )

        val result = tool(bing()).execute(args("test"))

        // 不解码的话模型拿到的网址是坏的 —— 交给 fetch_url 会 404
        assertTrue(result.content, result.content.contains("https://example.com/x?a=1&b=2"))
        assertFalse(result.content, result.content.contains("&amp;"))
    }

    @Test
    fun `内置后端不需要密钥，请求里也没有鉴权头`() = runBlocking {
        server.enqueue(html(bingPage(Triple("https://example.com/a", "标题", "摘要"))))

        val result = tool(bing()).execute(args("test"))

        assertFalse(result.content, result.isError)
        val recorded = server.takeRequest()
        assertNull(recorded.headers["Authorization"])
        assertNull(recorded.headers["X-Subscription-Token"])
    }

    @Test
    fun `内置后端请求的是 q 参数`() = runBlocking {
        server.enqueue(html(bingPage(Triple("https://example.com/a", "标题", "摘要"))))

        tool(bing()).execute(args("android 16"))

        val recorded = server.takeRequest()
        assertEquals("android 16", recorded.url.queryParameter("q"))
    }

    /**
     * 下面这两条是一对，也是这个后端最重要的两条 ——
     * 「页面结构变了」和「真的没搜到」必须给出**相反**的建议。
     */
    @Test
    fun `没有结果容器时指出是后端的问题而不是没搜到`() = runBlocking {
        // 被反爬挡了 / 对方改版了 —— 拿到的根本不是结果页
        server.enqueue(html("<html><body><h1>Making sure you're not a bot</h1></body></html>"))

        val result = tool(bing()).execute(args("test"))

        assertTrue(result.content, result.isError)
        assertTrue(result.content, result.content.contains("找不到搜索结果"))
        // 必须说清「和配置无关」：否则用户会去检查一个根本不存在的密钥
        assertTrue(result.content, result.content.contains("和用户的配置无关"))
        // 而且不能让模型换关键词重试
        assertTrue(result.content, result.content.contains("重试也没有用"))
    }

    @Test
    fun `有结果容器但没有条目时算没搜到`() = runBlocking {
        server.enqueue(html("""<html><body><ol id="b_results"></ol></body></html>"""))

        val result = tool(bing()).execute(args("test"))

        // 和上一条相反：这里是**成功**（零结果），引导换关键词
        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("没有返回任何结果"))
    }

    @Test
    fun `缺网址的条目被丢掉`() = runBlocking {
        val page = """
            <html><body><ol id="b_results">
            <li class="b_algo"><h2 class="">没有链接的条目</h2></li>
            <li class="b_algo"><a href="https://example.com/ok"><h2>有链接的条目</h2></a></li>
            </ol></body></html>
        """.trimIndent()
        server.enqueue(html(page))

        val result = tool(bing()).execute(args("test"))

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("有链接的条目"))
        // 没有网址的条目给模型也没用 —— 它没法把网址交给 fetch_url
        assertFalse(result.content, result.content.contains("没有链接的条目"))
    }

    @Test
    fun `内置后端的 HTTP 失败不指向密钥`() = runBlocking {
        server.enqueue(MockResponse(code = 403, body = "blocked"))

        val result = tool(bing()).execute(args("test"))

        assertTrue(result.content, result.isError)
        // 这个后端没有密钥可查 —— 说「去检查密钥」会把用户引到错的地方
        assertFalse(result.content, result.content.contains("检查 API Key"))
        assertTrue(result.content, result.content.contains("换一个后端"))
    }

    // ---------- 内置后端的配置层 ----------

    @Test
    fun `内置后端有默认地址`() {
        assertEquals(
            "https://cn.bing.com/search",
            defaultEndpoint(WebSearchBackend.BING_HTML),
        )
    }

    @Test
    fun `内置后端地址留空时回落到默认`() {
        // 设置页保存的是空串（用户没填）时也要能跑起来
        val config = WebSearchConfig(WebSearchBackend.BING_HTML, "")

        assertEquals("cn.bing.com", config.resolveEndpoint()?.host)
    }

    @Test
    fun `内置后端不需要密钥就算配好了`() {
        val config = WebSearchConfig(WebSearchBackend.BING_HTML, "", apiKey = null)

        assertTrue(config.isComplete())
    }

    @Test
    fun `内置后端的 id 能被读回来`() {
        // 存进 SharedPreferences 的是这个 id，读不回来 = 默认值形同虚设
        assertEquals(WebSearchBackend.BING_HTML, WebSearchBackend.fromId("bing"))
    }

    // ---------- 元数据 ----------

    @Test
    fun `工具定义完整`() {
        val definition = tool(searxng()).definition
        assertEquals(SearchWebTool.NAME, definition.name)
        assertTrue(definition.description.length > 30)
        // description 里必须写清「什么时候用它」，这是模型会不会调它的唯一依据
        assertTrue(definition.description, definition.description.contains("不要凭记忆回答"))
        // 摘要不是全文 —— 不写清楚的话模型会把摘要当正文引用
        assertTrue(definition.description, definition.description.contains("fetch_url"))
    }

    @Test
    fun `不需要逐次确认`() {
        assertFalse(tool(searxng()).requiresConfirmation)
    }

    @Test
    fun `面向用户的说明与模型描述是两份不同的文本`() {
        val t = tool(searxng())
        assertNotNull(t.userSummary)
        assertTrue(t.userSummary, t.userSummary != t.definition.name)
        assertTrue(t.userSummary, t.userSummary != t.definition.description)
    }
}
