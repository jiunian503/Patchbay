package com.aichat.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 查 GitHub 最新 release。
 *
 * ## 这个文件的重点在**失败怎么分类**
 *
 * 「检查更新」这个功能的正常路径只有一条（查到、比版本），而失败路径有一堆，
 * 且每一条对用户说的话都不一样：限流要说「过一会儿再试」、仓库还没有发布要说
 * 「等多久都一样」、连不上要说「看你的网络」。
 *
 * 把这几类混成一句「检查更新失败」的话，用户唯一能做的就是反复点同一个按钮 ——
 * 所以分类是**功能的一部分**，不是错误处理细节。
 */
class GitHubReleaseClientTest {

    private lateinit var server: MockWebServer

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun apiUrl() = server.url("/repos/o/r/releases/latest").toString()

    private fun lookup() = GitHubReleaseClient(client = client, apiUrl = apiUrl())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ---------- 正常路径 ----------

    @Test
    fun `读到 tag 和页面地址`() {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """
                    {
                      "tag_name": "v1.2",
                      "html_url": "https://github.com/o/r/releases/tag/v1.2",
                      "name": "1.2",
                      "body": "## 这一版加了什么\n- 检查更新"
                    }
                """.trimIndent(),
            ),
        )

        val result = runBlocking { lookup().latest() }

        assertEquals(
            ReleaseLookup.Found(
                tag = "v1.2",
                pageUrl = "https://github.com/o/r/releases/tag/v1.2",
            ),
            result,
        )
    }

    @Test
    fun `响应里多出来的字段不影响解析`() {
        // GitHub 的响应有几十个字段，而且**会加字段**（那不算破坏性变更）。
        // 写成非空 DTO 的话，任何一次字段调整都会让这个功能开始报「读不懂」，
        // 而我们这边收不到任何信号 —— 用户只会发现「检查更新」忽然不能用了
        server.enqueue(
            MockResponse(
                code = 200,
                body = """
                    {
                      "tag_name": "v1.2",
                      "html_url": "https://github.com/o/r/releases/tag/v1.2",
                      "assets": [{"name": "a.apk", "size": 5308445}],
                      "author": {"login": "someone"},
                      "prerelease": false,
                      "draft": false,
                      "reactions": {"total_count": 3},
                      "未来才会有的字段": true
                    }
                """.trimIndent(),
            ),
        )

        val result = runBlocking { lookup().latest() }

        assertEquals(
            ReleaseLookup.Found("v1.2", "https://github.com/o/r/releases/tag/v1.2"),
            result,
        )
    }

    @Test
    fun `请求带上了 GitHub 要求的头`() {
        // 不带 User-Agent 会被 GitHub 直接 403 —— 那看起来像限流，
        // 但等多久都不会好。这条断言就是为了不让它静默地变成那种情况
        server.enqueue(MockResponse(code = 200, body = """{"tag_name":"v1","html_url":"https://x/y"}"""))

        runBlocking { lookup().latest() }

        val recorded = server.takeRequest()
        assertEquals("/repos/o/r/releases/latest", recorded.url.encodedPath)
        assertTrue(
            "User-Agent 不能为空 —— GitHub 会对没有 UA 的请求返回 403",
            !recorded.headers["User-Agent"].isNullOrBlank(),
        )
        assertEquals("application/vnd.github+json", recorded.headers["Accept"])
        assertEquals("2022-11-28", recorded.headers["X-GitHub-Api-Version"])
    }

    // ---------- 失败分类 ----------

    @Test
    fun `404 是没有发布过，不是限流`() {
        server.enqueue(MockResponse(code = 404, body = """{"message":"Not Found"}"""))

        assertEquals(
            ReleaseLookup.Failed(ReleaseError.NoRelease),
            runBlocking { lookup().latest() },
        )
    }

    @Test
    fun `403 且配额用尽算限流`() {
        server.enqueue(
            MockResponse(
                code = 403,
                headers = headersOf("X-RateLimit-Remaining", "0"),
                body = """{"message":"API rate limit exceeded"}""",
            ),
        )

        assertEquals(
            ReleaseLookup.Failed(ReleaseError.RateLimited),
            runBlocking { lookup().latest() },
        )
    }

    @Test
    fun `429 也算限流`() {
        // 文档写的是 403，但实际也见过 429。两者对用户的含义一样（等一会儿），
        // 所以归成一类 —— 分成两类的话界面得写两句话，而它们意思相同
        server.enqueue(MockResponse(code = 429, body = "too many requests"))

        assertEquals(
            ReleaseLookup.Failed(ReleaseError.RateLimited),
            runBlocking { lookup().latest() },
        )
    }

    @Test
    fun `403 但配额还有，就不是限流`() {
        // 这条守的是「别把 403 一律当成限流」—— 那样的话一个真正的权限问题
        // 会被说成「过一会儿再试」，而用户会一直等下去
        server.enqueue(
            MockResponse(
                code = 403,
                headers = headersOf("X-RateLimit-Remaining", "57"),
                body = """{"message":"Forbidden"}""",
            ),
        )

        assertEquals(
            ReleaseLookup.Failed(ReleaseError.HttpStatus(403)),
            runBlocking { lookup().latest() },
        )
    }

    @Test
    fun `其它状态码原样带出来`() {
        server.enqueue(MockResponse(code = 500, body = "boom"))

        assertEquals(
            ReleaseLookup.Failed(ReleaseError.HttpStatus(500)),
            runBlocking { lookup().latest() },
        )
    }

    @Test
    fun `200 但不是 JSON 算读不懂`() {
        // 真实的触发场景：公司网络 / 运营商插了一个登录页进来，
        // 状态码是 200，内容是一段 HTML
        server.enqueue(MockResponse(code = 200, body = "<html>请先登录</html>"))

        assertEquals(
            ReleaseLookup.Failed(ReleaseError.Malformed),
            runBlocking { lookup().latest() },
        )
    }

    @Test
    fun `200 但缺 tag 或 url 都算读不懂`() {
        // 缺 tag 就比不了版本，缺 url 就没有「去下载」——
        // 任何一个缺了都给不出可用的答案，宁可说读不懂
        server.enqueue(MockResponse(code = 200, body = """{"html_url":"https://x/y"}"""))
        assertEquals(
            ReleaseLookup.Failed(ReleaseError.Malformed),
            runBlocking { lookup().latest() },
        )

        server.enqueue(MockResponse(code = 200, body = """{"tag_name":"v1.2"}"""))
        assertEquals(
            ReleaseLookup.Failed(ReleaseError.Malformed),
            runBlocking { lookup().latest() },
        )
    }

    @Test
    fun `空字符串的 tag 也算读不懂`() {
        server.enqueue(
            MockResponse(code = 200, body = """{"tag_name":"","html_url":"https://x/y"}"""),
        )

        assertEquals(
            ReleaseLookup.Failed(ReleaseError.Malformed),
            runBlocking { lookup().latest() },
        )
    }

    @Test
    fun `响应过大时不硬解析，直接报读不懂`() {
        // 截断的 JSON 一定解析失败，所以不如在读到上限的那一刻就说清楚
        server.enqueue(MockResponse(code = 200, body = "x".repeat(70 * 1024)))

        assertEquals(
            ReleaseLookup.Failed(ReleaseError.Malformed),
            runBlocking { lookup().latest() },
        )
    }

    @Test
    fun `连不上时报网络错误`() {
        // 先把服务停掉，再请求同一个地址 —— 得到的是连接被拒
        server.close()

        val result = runBlocking { lookup().latest() }

        assertTrue(
            "连不上时应当是 Network，实际是 $result",
            result is ReleaseLookup.Failed && result.error is ReleaseError.Network,
        )
    }
}
