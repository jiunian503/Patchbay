package com.aichat.network

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [CappedSource] 的直接测试。
 *
 * 两个消费者（MCP 传输、LLM 流式回答）各自有一条端到端用例，但它们都从很大的
 * 入口进去，验的是「整条链路会停下来」。这个类自己的边界 —— **正好等于上限要
 * 通过、多一个字节要报错**、以及按累计字节而不是单次读取算 —— 在这里钉住。
 *
 * 委托直接给 `Buffer`（它本身就是 `Source`），不套 `buffer()`：那样一次读多少
 * 就由 `RealBufferedSource` 的 8192 预读决定了，用例说不清到底在验谁。
 */
class CappedSourceTest {

    private fun data(n: Int) = Buffer().apply { write(ByteArray(n) { 'a'.code.toByte() }) }

    @Test
    fun `没到上限时原样读完`() {
        val source = CappedSource(data(100), limit = 1024)

        assertEquals(100L, source.read(Buffer(), 4096))
        assertEquals(-1L, source.read(Buffer(), 4096))
    }

    @Test
    fun `正好等于上限时通过`() {
        val source = CappedSource(data(1024), limit = 1024)

        assertEquals(1024L, source.read(Buffer(), 4096))
    }

    @Test
    fun `多一个字节就报错`() {
        val source = CappedSource(data(1025), limit = 1024)

        try {
            source.read(Buffer(), 4096)
            fail("超过上限应该抛 ResponseTooLargeException")
        } catch (e: ResponseTooLargeException) {
            assertTrue("提示里要说清上限是多少，实际是「${e.message}」", e.message!!.contains("1024"))
        }
    }

    @Test
    fun `按累计读到的字节算而不是单次读取`() {
        // 这正是「对端把几十 MB 塞进一行」的形状：每一次读都在限额内，
        // 只有累计起来超了才拦得住
        val source = CappedSource(data(2000), limit = 1000)

        assertEquals(800L, source.read(Buffer(), 800))
        try {
            source.read(Buffer(), 800)
            fail("累计超过上限时应该报错")
        } catch (e: ResponseTooLargeException) {
            // 期望
        }
    }

    @Test
    fun `流读完后的 EOF 不算进上限`() {
        // 读到头返回 -1 是正常收尾，不该因为「多读了一次」把正常的流判成超限
        val source = CappedSource(data(10), limit = 10)

        assertEquals(10L, source.read(Buffer(), 4096))
        assertEquals(-1L, source.read(Buffer(), 4096))
    }
}
