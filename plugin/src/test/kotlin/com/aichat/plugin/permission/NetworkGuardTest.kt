package com.aichat.plugin.permission

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 网络白名单。
 *
 * 这个类是整个插件系统里最需要穷举验证的一块：白名单一旦能被绕过，
 * 「插件只能访问 api.example.com」这句话就是假的，而用户是照着这句话
 * 决定要不要装这个插件的。
 *
 * 每个用例对应一种已知的绕过手法，或者一条「看起来该放行」的正常路径
 * （不能为了安全把正常用法也拦了 —— 那样作者会去关权限）。
 */
class NetworkGuardTest {

    private lateinit var allowed: MockWebServer
    private lateinit var other: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        allowed = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
        client = OkHttpClient()
    }

    @After
    fun tearDown() {
        allowed.close()
        other.close()
    }

    private fun guard(vararg hosts: String) = NetworkGuard(hosts.toList())

    /**
     * 白名单放行 MockWebServer 自己。
     *
     * 从 `server.url()` 里取主机名而不是写死 `127.0.0.1`：
     * MockWebServer 默认绑的是 `localhost`，写死会让所有「应该放行」的用例
     * 变成「被拦下」—— 而且看起来像是白名单坏了，实际是测试写错了。
     */
    private fun localGuard() = guard(allowedHost)

    private val allowedHost: String get() = allowed.url("/").host

    /**
     * 第二个「主机名」。
     *
     * ## 为什么需要它
     *
     * 两台 MockWebServer 默认都叫 `localhost`，只是端口不同 ——
     * 而白名单**刻意不匹配端口**（本机调试要用随机端口）。所以它们
     * 在守卫眼里是同一台机器，用它们测「跨主机」是测不出来的：
     * 请求会照常放行，用例看起来像是白名单坏了。
     *
     * `localhost` 和 `127.0.0.1` 指向同一个回环地址但**是两个不同的主机名**，
     * 于是连接照样能到达 `other`，而守卫看到的是一个没被声明的 host。
     */
    private val otherHost: String get() = if (allowedHost == "localhost") "127.0.0.1" else "localhost"

    /** 指向 [other] 的地址，但主机名换成 [otherHost]。 */
    private fun otherUrl(path: String): String =
        other.url(path).newBuilder().host(otherHost).build().toString()

    // ---------- 主机匹配 ----------

    @Test
    fun `后缀相同但不是同一个域名时不放行`() {
        // 经典漏洞：host.endsWith("example.com") 会放行 evilexample.com
        val g = guard("example.com")
        assertTrue(g.allows("example.com"))
        assertFalse("evilexample.com 不能因为后缀相同就放行", g.allows("evilexample.com"))
        assertFalse("子域也不放行 —— 不支持子域通配，避免静默的权限扩张", g.allows("api.example.com"))
    }

    @Test
    fun `大小写不敏感`() {
        val g = guard("API.Example.COM")
        assertTrue(g.allows("api.example.com"))
        assertTrue(g.allows("API.EXAMPLE.COM"))
    }

    @Test
    fun `星号放行任意主机`() {
        val g = guard("*")
        assertTrue(g.allowsAnyHost)
        assertTrue(g.allows("anything.at.all"))
    }

    @Test
    fun `星号和具体域名混写时星号生效但列表里不留星号`() {
        val g = guard("*", "api.example.com")
        assertTrue(g.allowsAnyHost)
        assertTrue(g.allows("whatever"))
    }

    @Test
    fun `没声明任何主机时一律拒绝`() {
        val g = guard()
        assertFalse(g.allows("127.0.0.1"))
        assertNotNull("拒绝原因要说清声明了什么", g.check("http://127.0.0.1/x".toHttpUrl()))
    }

    @Test
    fun `空串和空白项被忽略而不是当成主机`() {
        val g = guard("", "  ", "api.example.com")
        assertFalse(g.allows(""))
        assertTrue(g.allows("api.example.com"))
    }

    // ---------- 端口 ----------

    @Test
    fun `端口不参与匹配`() {
        // 刻意放行任意端口：本机调试要用 127.0.0.1:随机端口，
        // 而约束「连到哪台机器」的是主机名
        val g = guard("127.0.0.1")
        assertTrue(g.allows("127.0.0.1"))
        assertEquals(null, g.check("http://127.0.0.1:9/x".toHttpUrl()))
    }

    // ---------- 实际发请求 ----------

    @Test
    fun `白名单内的地址正常拿到响应`() {
        allowed.enqueue(MockResponse(code = 200, body = "ok"))

        val response = localGuard().call(client, Request.Builder().url(allowed.url("/a")).build())

        response.use { assertEquals(200, it.code) }
    }

    @Test
    fun `白名单外的地址在发请求前就被拦下`() {
        other.enqueue(MockResponse(code = 200, body = "不该被访问到"))

        val g = guard("example.com")
        val request = Request.Builder().url(other.url("/secret")).build()

        val e = runCatching { g.call(client, request) }.exceptionOrNull()
        assertTrue("应该是 NetworkDeniedException，实际是 $e", e is NetworkDeniedException)
        // 关键：请求根本没发出去。服务端的记录是空的
        assertEquals("被拦下的请求不能真的发出去", 0, other.requestCount)
    }

    // ---------- 重定向 ----------

    @Test
    fun `白名单内跳到白名单外时被拦下`() {
        // 这是最危险的一条：白名单里的域名只要回一个 302，
        // 就能把请求（连同认证头）引到任意地方
        allowed.enqueue(
            MockResponse(code = 302, headers = headersOf("Location", otherUrl("/stolen"))),
        )
        other.enqueue(MockResponse(code = 200, body = "不该被访问到"))

        val e = runCatching {
            localGuard().call(client, Request.Builder().url(allowed.url("/start")).build())
        }.exceptionOrNull()

        assertTrue("跨出白名单的跳转必须被拦，实际是 $e", e is NetworkDeniedException)
        assertEquals("被拦下的第二跳不能真的发出去", 0, other.requestCount)
    }

    @Test
    fun `同主机内的重定向正常跟随`() {
        allowed.enqueue(
            MockResponse(code = 302, headers = headersOf("Location", allowed.url("/final").toString())),
        )
        allowed.enqueue(MockResponse(code = 200, body = "final"))

        localGuard().call(client, Request.Builder().url(allowed.url("/start")).build()).use {
            assertEquals(200, it.code)
            assertEquals("final", it.body.string())
        }
    }

    @Test
    fun `跨主机重定向会剥掉认证头`() {
        // 两台机器都在白名单里，所以跳转本身是允许的 ——
        // 但用户的 API Key 不该跟着跳到第二台机器上
        allowed.enqueue(
            MockResponse(code = 302, headers = headersOf("Location", otherUrl("/next"))),
        )
        other.enqueue(MockResponse(code = 200, body = "ok"))

        guard(allowedHost, otherHost).call(
            client,
            Request.Builder()
                .url(allowed.url("/start"))
                .header("Authorization", "Bearer SECRET")
                .header("X-Api-Key", "SECRET2")
                .header("Accept", "application/json")
                .build(),
        ).close()

        val second = other.takeRequest()
        assertNull("跨主机跳转必须剥掉 Authorization", second.headers["Authorization"])
        assertNull("X-Api 系列也要剥掉", second.headers["X-Api-Key"])
        assertEquals("无关的头应该保留", "application/json", second.headers["Accept"])
    }

    @Test
    fun `同主机重定向保留认证头`() {
        allowed.enqueue(
            MockResponse(code = 307, headers = headersOf("Location", allowed.url("/next").toString())),
        )
        allowed.enqueue(MockResponse(code = 200, body = "ok"))

        localGuard().call(
            client,
            Request.Builder().url(allowed.url("/start")).header("Authorization", "Bearer SECRET").build(),
        ).close()

        allowed.takeRequest()
        val second = allowed.takeRequest()
        assertEquals("Bearer SECRET", second.headers["Authorization"])
    }

    @Test
    fun `重定向成环时报错而不是无限跟`() {
        repeat(8) {
            allowed.enqueue(
                MockResponse(code = 302, headers = headersOf("Location", allowed.url("/loop").toString())),
            )
        }

        val e = runCatching {
            localGuard().call(client, Request.Builder().url(allowed.url("/loop")).build())
        }.exceptionOrNull()

        assertTrue("应该因为跳数超限而停下，实际是 $e", e is NetworkDeniedException)
    }

    @Test
    fun `303 重定向后方法变成 GET`() {
        allowed.enqueue(
            MockResponse(code = 303, headers = headersOf("Location", allowed.url("/result").toString())),
        )
        allowed.enqueue(MockResponse(code = 200, body = "ok"))

        localGuard().call(
            client,
            Request.Builder().url(allowed.url("/submit")).post("x".toRequestBody()).build(),
        ).close()

        assertEquals("POST", allowed.takeRequest().method)
        assertEquals("303 按 RFC 9110 一律变 GET", "GET", allowed.takeRequest().method)
    }

    @Test
    fun `Location 不是合法地址时报错`() {
        allowed.enqueue(MockResponse(code = 302, headers = headersOf("Location", "http://")))

        val e = runCatching {
            localGuard().call(client, Request.Builder().url(allowed.url("/start")).build())
        }.exceptionOrNull()

        assertTrue("应该报成拒绝而不是崩掉，实际是 $e", e is NetworkDeniedException)
    }

    @Test
    fun `相对 Location 按当前地址解析`() {
        allowed.enqueue(MockResponse(code = 302, headers = headersOf("Location", "/relative")))
        allowed.enqueue(MockResponse(code = 200, body = "ok"))

        localGuard().call(client, Request.Builder().url(allowed.url("/start")).build()).use {
            assertEquals(200, it.code)
        }
        // 第一跳是原始请求，第二跳才是解析后的相对地址
        allowed.takeRequest()
        assertEquals("/relative", allowed.takeRequest().url.encodedPath)
    }
}
