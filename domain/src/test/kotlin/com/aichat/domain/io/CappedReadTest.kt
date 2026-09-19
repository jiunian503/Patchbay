package com.aichat.domain.io

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读输入流时的长度上限。
 *
 * ## 为什么值得单独测
 *
 * 两个消费者都面对**用户控制不了的输入**：
 *
 * - 「从文件」：用户完全可能手滑选到一个几百 MB 的视频 —— 文件选择器拦不住他
 *   （清单就是个 `.json`，没有专属 MIME 类型可以过滤掉视频）
 * - 「从 URL」：对端可以回一个任意大的响应体，或者一个不会结束的流
 *
 * 而 `readText()` 在读之前不会问一句「这个多大」。少了这道闸，App 会在一个
 * 「装插件」的页面上 OOM，报错信息完全指不到真实原因。
 *
 * 这里守三件事：**超限要报错**（而不是截断出一段解析不了的 JSON）、
 * **超限时提前停手**（而不是读完再判 —— 那样内存已经爆了）、
 * 以及**按字符数而不是字节数**判。
 */
class CappedReadTest {

    private fun read(text: String, maxChars: Int) =
        readCapped(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), maxChars)

    /** 一个「读了多少字节就记下来」的流，用来验证超限时提前停手。 */
    private class CountingStream(private val total: Int) : InputStream() {
        var consumed = 0
            private set

        override fun read(): Int = if (consumed >= total) -1 else 'x'.code.also { consumed++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= total) return -1
            val n = minOf(len, total - consumed)
            for (i in 0 until n) b[off + i] = 'x'.code.toByte()
            consumed += n
            return n
        }
    }

    @Test
    fun `小文件完整读出来`() {
        val text = """{"id":"pub.a","version":"1.0.0"}"""
        assertEquals(CappedRead.Ok(text), read(text, 1024))
    }

    @Test
    fun `空文件读出空串而不是失败`() {
        // 「空」和「读不到」是两件事：前者是用户选错了文件 / 对端返回了空，
        // 后者是文件没了 / 连不上。提示语不一样，所以不能都走 TooLarge 那条路
        assertEquals(CappedRead.Ok(""), read("", 1024))
    }

    @Test
    fun `刚好等于上限可以过`() {
        val text = "x".repeat(64)
        assertEquals(CappedRead.Ok(text), read(text, 64))
    }

    @Test
    fun `多一个字符就报太大`() {
        assertEquals(CappedRead.TooLarge, read("x".repeat(65), 64))
    }

    @Test
    fun `中文按字符数算而不是字节数`() {
        // 清单里全是中文注释和 description 是常态。要是按字节数判，
        // 一份 100 KB 的中文清单会被算成 300 KB —— 上限就形同虚设了
        val text = "上海今天多少度".repeat(10)
        assertEquals(CappedRead.Ok(text), read(text, text.length))
        assertEquals(CappedRead.TooLarge, read(text, text.length - 1))
    }

    @Test
    fun `超限时提前停手 不会把整个流读完`() {
        // 这是这条上限**真正**的意义所在。读完再判长度的话，
        // 一个 200 MB 的流在判之前就已经把内存吃满了 ——
        // 那时候再报「太大」已经来不及
        val stream = CountingStream(total = 64 * 1024 * 1024)
        val result = readCapped(stream, 1024)

        assertEquals(CappedRead.TooLarge, result)
        assertTrue(
            "只该读几千字节就停手，实际读了 ${stream.consumed} 字节",
            stream.consumed < 1024 * 1024,
        )
    }

    /**
     * 上限为零时**任何**非空内容都超限，而不是抛异常。
     *
     * 这条边界以前没人走（上限是个 256 KB 的常量），但现在上限由调用方传，
     * 就变成一个真实存在的取值了。
     */
    @Test
    fun `上限为零时非空内容算超限 空内容仍然可以过`() {
        assertEquals(CappedRead.Ok(""), read("", 0))
        assertEquals(CappedRead.TooLarge, read("x", 0))
    }

    /** 负上限是调用方的编程错误，要立刻失败而不是变成一个奇怪的语义。 */
    @Test
    fun `负上限直接失败`() {
        assertThrows(IllegalArgumentException::class.java) { read("x", -1) }
    }
}
