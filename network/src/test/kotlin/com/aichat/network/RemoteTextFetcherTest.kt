package com.aichat.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 按 URL 取一段文本。
 *
 * ## 这个类为什么必须自己跟重定向
 *
 * 它是「从 URL 装插件」的取数层，而那个入口**没有白名单**兜底
 * （白名单是插件自己的权限，装之前那个插件还不存在）。所以协议上的把关
 * 只能落在这里：只认 http/https、没写 scheme 补 https、
 * **`https://` 不接受被 302 降级成 `http://`**。
 *
 * 最后一条是本文件里最要紧的断言。它没法用 [MockWebServer] 验
 * （那是明文的 http 服务，起不了 https），所以拆成两个纯函数单独钉
 * （`parseFetchUrl` / `isInsecureDowngrade`），
 * 再用一条 `require` 保证调用方不会把自动重定向打开。
 */
class RemoteTextFetcherTest {

    private lateinit var server: MockWebServer

    /** 刻意关掉自动重定向 —— 这正是 [RemoteTextFetcher] 要求的。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun fetcher(maxChars: Int = 4096) = RemoteTextFetcher(client, maxChars)

    private fun url(path: String = "/manifest.json") = server.url(path).toString()

    private fun redirect(to: String) =
        MockResponse(code = 302, headers = headersOf("Location", to))

    // ---------- 正常路径 ----------

    @Test
    fun `取回文本并报告最终地址`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = """{"id":"pub.a"}"""))

        val result = fetcher().fetch(url())

        val ok = result as FetchResult.Ok
        assertEquals("""{"id":"pub.a"}""", ok.text)
        assertEquals(server.url("/manifest.json").toString(), ok.finalUrl.toString())
    }

    @Test
    fun `明文 http 地址照常取回`() = runBlocking {
        // 用户自己写 http:// 是知情的选择，界面会如实标注这是明文连接。
        // 这里只确认「不会因为不是 https 就拒绝」
        server.enqueue(MockResponse(code = 200, body = "ok"))

        assertTrue(fetcher().fetch(url()) is FetchResult.Ok)
    }

    /** 204 之类的空响应不是错误，是一段空文本 —— 界面据此报「返回了空内容」。 */
    @Test
    fun `空响应体算成功而不是失败`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = ""))

        val ok = fetcher().fetch(url()) as FetchResult.Ok
        assertEquals("", ok.text)
    }

    // ---------- 状态码 ----------

    @Test
    fun `404 报出状态码`() = runBlocking {
        server.enqueue(MockResponse(code = 404, body = "nope"))

        val failed = fetcher().fetch(url()) as FetchResult.Failed
        assertEquals(FetchError.HttpStatus(404), failed.error)
    }

    @Test
    fun `500 报出状态码`() = runBlocking {
        server.enqueue(MockResponse(code = 500, body = "boom"))

        val failed = fetcher().fetch(url()) as FetchResult.Failed
        assertEquals(FetchError.HttpStatus(500), failed.error)
    }

    // ---------- 大小上限 ----------

    @Test
    fun `超过上限时报太大`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = "x".repeat(2000)))

        val failed = fetcher(maxChars = 1024).fetch(url()) as FetchResult.Failed
        assertEquals(FetchError.TooLarge, failed.error)
    }

    @Test
    fun `刚好等于上限可以过`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = "x".repeat(1024)))

        assertTrue(fetcher(maxChars = 1024).fetch(url()) is FetchResult.Ok)
    }

    // ---------- 重定向 ----------

    @Test
    fun `跟随重定向并报告最终地址`() = runBlocking {
        server.enqueue(redirect("/moved.json"))
        server.enqueue(MockResponse(code = 200, body = "final"))

        val ok = fetcher().fetch(url()) as FetchResult.Ok

        assertEquals("final", ok.text)
        // 报告**最终**地址而不是用户输入的那个：界面要显示「实际是从哪取到的」
        assertEquals(server.url("/moved.json").toString(), ok.finalUrl.toString())
    }

    /**
     * 相对 `Location` 必须能解析 —— 这是重定向里最常见的形式。
     *
     * 起点 `/a/b/manifest.json` 遇到 `../other.json` 得到 `/a/other.json`：
     * `../` 只上跳**一级**（从 `/a/b/` 到 `/a/`），不是一路回到根。
     * 这正是 `HttpUrl.resolve` 按 RFC 3986 算出来的结果。
     */
    @Test
    fun `相对地址的重定向也能跟随`() = runBlocking {
        server.enqueue(redirect("../other.json"))
        server.enqueue(MockResponse(code = 200, body = "other"))

        val ok = fetcher().fetch(server.url("/a/b/manifest.json").toString()) as FetchResult.Ok

        assertEquals("other", ok.text)
        assertEquals(server.url("/a/other.json").toString(), ok.finalUrl.toString())
    }

    @Test
    fun `重定向成环时报跳数超限`() = runBlocking {
        // 自指重定向：每一跳都回到自己
        repeat(RemoteTextFetcher.MAX_REDIRECTS + 2) {
            server.enqueue(redirect(url()))
        }

        val failed = fetcher().fetch(url()) as FetchResult.Failed
        assertEquals(FetchError.TooManyRedirects, failed.error)
    }

    // ---------- 网络错误 ----------

    @Test
    fun `连不上时报网络错误`() = runBlocking {
        // 1 号端口不会有服务在听
        val failed = fetcher().fetch("http://127.0.0.1:1/manifest.json") as FetchResult.Failed

        assertTrue("应该是网络类错误，实际是 ${failed.error}", failed.error is FetchError.Network)
    }

    // ---------- 构造时的约束 ----------

    /**
     * 开着自动重定向的 client 会让「https 不被降级」这条静默失效 ——
     * 因为 OkHttp 会自己跟完，`isInsecureDowngrade` 根本没机会跑。
     */
    @Test
    fun `开着自动重定向的 client 直接构造失败`() {
        val bad = OkHttpClient.Builder().followRedirects(true).build()

        assertThrows(IllegalArgumentException::class.java) { RemoteTextFetcher(bad, 1024) }
    }

    // ---------- URL 归一化（纯逻辑） ----------

    @Test
    fun `没写 scheme 时补 https`() {
        val ok = parseFetchUrl("example.com/x.json") as FetchUrlParse.Ok

        // 补 https 而不是 http：默认落在安全的那一边
        assertEquals("https", ok.url.scheme)
        assertEquals("example.com", ok.url.host)
    }

    @Test
    fun `写了 http 就照办`() {
        val ok = parseFetchUrl("http://example.com/x.json") as FetchUrlParse.Ok

        assertEquals("http", ok.url.scheme)
    }

    @Test
    fun `大小写不同的 scheme 也能识别`() {
        val ok = parseFetchUrl("HTTPS://example.com/x.json") as FetchUrlParse.Ok

        assertEquals("https", ok.url.scheme)
    }

    @Test
    fun `前后的空白被忽略`() {
        val ok = parseFetchUrl("  http://example.com/x.json  ") as FetchUrlParse.Ok

        assertEquals("http://example.com/x.json", ok.url.toString())
    }

    @Test
    fun `ftp 被当作不支持的协议`() {
        assertEquals(FetchUrlParse.UnsupportedScheme, parseFetchUrl("ftp://example.com/x"))
    }

    /**
     * `file://` 必须挡住。
     *
     * 已经有「从文件」入口了（走 SAF，有系统的权限模型），而 `file://`
     * 会绕过它直接读应用能碰到的任何文件 —— 那是一条静默的提权。
     */
    @Test
    fun `file 被当作不支持的协议`() {
        assertEquals(FetchUrlParse.UnsupportedScheme, parseFetchUrl("file:///etc/hosts"))
    }

    @Test
    fun `content 被当作不支持的协议`() {
        assertEquals(
            FetchUrlParse.UnsupportedScheme,
            parseFetchUrl("content://com.example/x"),
        )
    }

    /**
     * `javascript:alert(1)` 没有 `://`，所以会被当成「用户没写 scheme」补上 https，
     * 然后被 `HttpUrl` 拒掉（端口 `alert(1)` 非法）。**不能让它变成一个 URL。**
     */
    @Test
    fun `javascript 伪协议不是合法网址`() {
        assertEquals(FetchUrlParse.Bad, parseFetchUrl("javascript:alert(1)"))
    }

    @Test
    fun `空输入不合法`() {
        assertEquals(FetchUrlParse.Bad, parseFetchUrl(""))
        assertEquals(FetchUrlParse.Bad, parseFetchUrl("   \n  "))
    }

    @Test
    fun `没有主机名不合法`() {
        assertEquals(FetchUrlParse.Bad, parseFetchUrl("http://"))
        assertEquals(FetchUrlParse.Bad, parseFetchUrl("https://"))
    }

    // ---------- 降级判定（纯逻辑） ----------

    /**
     * 这条是本文件里最要紧的一条断言。
     *
     * 用户写 `https://` 就是要求加密；中途某一跳被转成明文是**服务器单方面**
     * 改的，他没有同意过。而这件事如果没被拦住，**不会有任何报错** ——
     * 清单会照常拉下来，只是中间人能看到（也能改）它的内容。
     */
    @Test
    fun `https 起点被转到 http 时判定为降级`() {
        assertTrue(isInsecureDowngrade(true, "http://evil.example/x".toHttpUrl()))
    }

    @Test
    fun `https 到 https 不算降级`() {
        assertFalse(isInsecureDowngrade(true, "https://cdn.example/x".toHttpUrl()))
    }

    /** 用户自己写的 http，跳到哪都还是明文，不存在「降级」这回事。 */
    @Test
    fun `用户自己写 http 时不算降级`() {
        assertFalse(isInsecureDowngrade(false, "http://example/x".toHttpUrl()))
    }
}
