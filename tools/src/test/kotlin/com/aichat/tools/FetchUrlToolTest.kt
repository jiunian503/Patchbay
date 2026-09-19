package com.aichat.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 抓取工具。
 *
 * 用真实 HTTP（MockWebServer）而不是假客户端：这个工具的价值全在
 * 「跟真实服务端打交道时不出事」—— 状态码、内容类型、超大响应、
 * 超时，这些行为假客户端模拟不出来。
 *
 * 用 `runBlocking` 而非 `runTest`：请求发生在 OkHttp 自己的线程上，是真实时间。
 */
class FetchUrlToolTest {

    private lateinit var server: MockWebServer
    private lateinit var tool: FetchUrlTool

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tool = FetchUrlTool()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun args(url: String) = buildJsonObject { put(FetchUrlTool.URL, url) }

    private fun url(path: String) = server.url(path).toString()

    // ---------- 正常抓取 ----------

    @Test
    fun `抓纯文本并带上来源`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "text/plain"),
                body = "hello world",
            ),
        )

        val result = tool.execute(args(url("/a.txt")))

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("hello world"))
        assertTrue(result.content, result.content.contains("来源："))
    }

    @Test
    fun `JSON 原样返回`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "application/json"),
                body = """{"temperature": 25}""",
            ),
        )

        val result = tool.execute(args(url("/api")))

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("\"temperature\": 25"))
    }

    @Test
    fun `HTML 会被转成可读文本`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "text/html; charset=utf-8"),
                body = """
                    <html><head><title>标题</title>
                    <style>body{color:red}</style></head>
                    <body><h1>正文</h1><p>第一段</p><p>第二段</p></body></html>
                """.trimIndent(),
            ),
        )

        val result = tool.execute(args(url("/page")))

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("正文"))
        assertTrue(result.content, result.content.contains("第一段"))
        // script/style 的正文对模型是噪声，必须丢掉
        assertFalse(result.content, result.content.contains("color:red"))
        assertFalse(result.content, result.content.contains("<h1>"))
    }

    @Test
    fun `请求带上 User-Agent`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = "ok"))

        tool.execute(args(url("/ua")))

        val recorded = server.takeRequest()
        assertTrue(recorded.headers["User-Agent"].orEmpty().isNotEmpty())
    }

    // ---------- 错误路径 ----------

    @Test
    fun `404 报错并带上地址`() = runBlocking {
        server.enqueue(MockResponse(code = 404, body = "not found"))

        val result = tool.execute(args(url("/missing")))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("404"))
        assertTrue(result.content, result.content.contains("/missing"))
    }

    @Test
    fun `非文本类型被拒绝`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "image/png"),
                body = "not really a png",
            ),
        )

        val result = tool.execute(args(url("/pic.png")))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("image/png"))
    }

    @Test
    fun `内容为空时明确说明`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = ""))

        val result = tool.execute(args(url("/empty")))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("为空"))
    }

    /**
     * 超大响应必须被截断。
     *
     * 这是这个工具最危险的失败模式：地址是模型给的，它可能指向一个
     * 几百 MB 的文件。不加限制会直接 OOM —— 而 OOM 是不可捕获的。
     */
    @Test
    fun `超大响应会被截断并说明`() = runBlocking {
        val big = "A".repeat(FetchUrlTool.MAX_BYTES * 3)
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "text/plain"),
                body = big,
            ),
        )

        val result = tool.execute(args(url("/big")))

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("截断"))
        // 结果不该比上限大太多（正文上限 + 头部几行说明）
        assertTrue(
            "结果长度 ${result.content.length} 超出预期",
            result.content.length <= FetchUrlTool.MAX_CHARS + 500,
        )
    }

    @Test
    fun `内容超过字符上限时只保留前一段`() = runBlocking {
        val long = "B".repeat(FetchUrlTool.MAX_CHARS * 2)
        server.enqueue(
            MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "text/plain"),
                body = long,
            ),
        )

        val result = tool.execute(args(url("/long")))

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("只保留前"))
        assertTrue(result.content.length <= FetchUrlTool.MAX_CHARS + 500)
    }

    @Test
    fun `连不上时报错而不是抛异常`() = runBlocking {
        // 先关掉服务端，制造连接失败
        val deadUrl = server.url("/gone").toString()
        server.close()

        val result = tool.execute(args(deadUrl))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("连不上"))
    }

    // ---------- 参数不可信 ----------

    @Test
    fun `缺参数时提示怎么填`() = runBlocking {
        val result = tool.execute(buildJsonObject { })
        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains(FetchUrlTool.URL))
    }

    @Test
    fun `不是地址的字符串被挡住`() = runBlocking {
        val result = tool.execute(args("这不是地址"))
        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("合法"))
    }

    @Test
    fun `没有协议的地址被挡住`() = runBlocking {
        val result = tool.execute(args("example.com/page"))
        assertTrue(result.isError)
    }

    /** file:// 能读本地文件，是典型的越权路径，必须在协议白名单里被拦掉。 */
    @Test
    fun `非 http 协议被挡住`() = runBlocking {
        val result = tool.execute(args("file:///etc/hosts"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("只支持"))
    }

    // ---------- 纯函数 ----------

    @Test
    fun `HTML 清洗丢掉脚本与样式`() {
        val html = """
            <div>前<script>var x=1;</script>中<style>.a{}</style>后</div>
        """.trimIndent()

        val text = FetchUrlTool.stripHtml(html)

        assertTrue(text, text.contains("前"))
        assertTrue(text, text.contains("后"))
        assertFalse(text, text.contains("var x"))
        assertFalse(text, text.contains(".a{}"))
    }

    @Test
    fun `HTML 清洗解常见实体`() {
        val text = FetchUrlTool.stripHtml("<p>a &amp; b &lt;c&gt; &quot;d&quot;&nbsp;e</p>")
        assertTrue(text, text.contains("a & b <c> \"d\" e"))
    }

    @Test
    fun `HTML 清洗保留块级换行`() {
        val text = FetchUrlTool.stripHtml("<p>第一段</p><p>第二段</p>")
        // 不能粘成「第一段第二段」，否则模型分不清句子边界
        assertTrue(text, text.contains("第一段\n第二段"))
    }

    @Test
    fun `内容类型判断只认文本`() {
        assertTrue(FetchUrlTool.isTextLike("text/html; charset=utf-8"))
        assertTrue(FetchUrlTool.isTextLike("application/json"))
        assertTrue(FetchUrlTool.isTextLike("application/ld+json"))
        assertTrue(FetchUrlTool.isTextLike("application/atom+xml"))
        assertFalse(FetchUrlTool.isTextLike("image/png"))
        assertFalse(FetchUrlTool.isTextLike("application/pdf"))
        assertFalse(FetchUrlTool.isTextLike("application/octet-stream"))
    }

    // ---------- 元数据 ----------

    /**
     * 这个工具的地址是**模型选的**，属于对外动作，必须走确认。
     * 分类写错的后果是：等审批 UI 接上时它会默认放行。
     */
    @Test
    fun `需要用户确认`() {
        assertTrue(tool.requiresConfirmation)
    }

    @Test
    fun `工具定义完整`() {
        assertEquals(FetchUrlTool.NAME, tool.definition.name)
        assertTrue(tool.definition.description.length > 30)
    }
}
