package com.aichat.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [peekTextCapped] 的直接测试。
 *
 * ## 为什么这个类值得单独存在
 *
 * 它替代的那行代码是 `peekBody(limit).string()` —— **看起来完全正确**。
 * 错的地方不在「正常时怎么办」，在「超了会怎样」：它会截断，而截断之后
 * 没有任何人知道。于是症状出现在很远的地方（「对端返回的不是 JSON」），
 * 而且**指向一个无辜的服务端**。
 *
 * 所以这里钉住的正是那三个边界：没到、正好到、多一个字节。
 * 只写「没超时能读到」的话，一个永远返回 null 的实现也能通过。
 *
 * 顺带钉住「按字节而不是按字符」：中文一个字三个字节，用 `String.length`
 * 判断的话上限会静默放宽三倍 —— 而上限本来就是为了兜住内存。
 *
 * 走 MockWebServer 而不是手搓 `Response`：`peekBody` 的语义（能读多少、
 * 会不会把流吃掉）是这里要验的东西之一，手搓出来的 `Response` 验不了它。
 */
class CappedBodyTest {

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

    private fun peek(body: String, limit: Long): String? {
        server.enqueue(MockResponse(code = 200, body = body))
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { response ->
            return response.peekTextCapped(limit)
        }
    }

    @Test
    fun `没到上限时原样返回`() {
        assertEquals("a".repeat(100), peek("a".repeat(100), limit = 200))
    }

    @Test
    fun `正好等于上限时也原样返回`() {
        val body = "a".repeat(200)

        assertEquals(body, peek(body, limit = 200))
    }

    @Test
    fun `多一个字节就返回 null 而不是截断`() {
        assertNull(
            "截断的正文会被下游当成「对端坏了」",
            peek("a".repeat(201), limit = 200),
        )
    }

    @Test
    fun `按字节算而不是按字符算`() {
        // 「中」在 UTF-8 里是 3 个字节。按 `String.length` 比的话，
        // 这段 300 字节的正文会被判成「100 < 299，没超」
        val body = "中".repeat(100)

        assertNull("299 字节的上限装不下 300 字节", peek(body, limit = 299))
        assertEquals(body, peek(body, limit = 300))
    }
}
