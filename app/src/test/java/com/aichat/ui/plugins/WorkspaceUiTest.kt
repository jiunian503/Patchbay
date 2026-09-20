package com.aichat.ui.plugins

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [formatBytes] / [readCappedBytes] 的测试。
 *
 * 这两个函数在 `:app` 里，但它们**没有一行 Android API**，所以能在 JVM 上跑 ——
 * 把「按字节读、先判再拼」这条纪律钉在这里，比在真机上跑一条 instrumented
 * 用例便宜得多。
 *
 * `:app` 侧剩下的那部分（读 SAF 的 URI、把失败翻成用户话术）没有在这里测：
 * 它要么依赖 `ContentResolver`，要么依赖 `AppContainer`，都进不了 JVM 测试。
 * 那部分的验收在真机上（导入的文件插件真的读得到）。
 */
class WorkspaceUiTest {

    // ── formatBytes ───────────────────────────────────────────────────────────

    @Test
    fun `小于 1 KB 说字节`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun `到 KB 就进位`() {
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("12 KB", formatBytes(12 * 1024))
    }

    @Test
    fun `到 MB 给一位小数`() {
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("1.5 MB", formatBytes(1024L * 1024 * 3 / 2))
        assertEquals("8.0 MB", formatBytes(8L * 1024 * 1024))
    }

    @Test
    fun `小数点永远是点_不跟着地区变`() {
        // 走 `String.format` 的默认 locale 的话，某些地区会给出 "1,5 MB" ——
        // 而这个字符串会出现在「超过 1.0 MB 的单文件上限」这种话里，
        // 用户要拿它和自己看到的文件大小对照
        val text = formatBytes(1024L * 1024 * 3 / 2)
        assertTrue(text, text.contains("."))
        assertFalse(text, text.contains(","))
    }

    // ── readCappedBytes ───────────────────────────────────────────────────────

    @Test
    fun `刚好到上限是允许的`() {
        val data = ByteArray(100) { it.toByte() }
        val read = readCappedBytes(ByteArrayInputStream(data), 100)
        assertTrue(read is CappedBytes.Ok)
        assertArrayEquals(data, (read as CappedBytes.Ok).bytes)
    }

    @Test
    fun `超过一个字节就拒`() {
        assertEquals(CappedBytes.TooLarge, readCappedBytes(ByteArrayInputStream(ByteArray(101)), 100))
    }

    @Test
    fun `超限时提前停手_不把整个流读进来`() {
        // 这条钉的是「先判再拼」。如果实现写成「先拼完再判」，一个 2 GB 的
        // 流会先把内存吃满才报错 —— 而那时候报出来的错指不到真实原因
        val stream = CountingStream(total = 10 * 1024 * 1024)
        assertEquals(CappedBytes.TooLarge, readCappedBytes(stream, 8 * 1024))
        // 最多多读一个缓冲区：第一次 8 KB 收下（正好等于上限），
        // 第二次读到超了才发现
        assertTrue("实际读了 ${stream.readBytes} 字节", stream.readBytes <= 16 * 1024)
    }

    @Test
    fun `空流给空数组`() {
        val read = readCappedBytes(ByteArrayInputStream(ByteArray(0)), 100)
        assertTrue(read is CappedBytes.Ok)
        assertEquals(0, (read as CappedBytes.Ok).bytes.size)
    }

    @Test
    fun `上限按字节算_不按字符算`() {
        // 「中中中」是 3 个字符、9 个字节。上限给 6：
        // 按字符算会收下（3 <= 6），按字节算必须拒（9 > 6）。
        // 导入一份多字节的 CSV 时，这个差别就是「1 MB」和「3 MB」
        val text = "中中中".toByteArray(Charsets.UTF_8)
        assertEquals(CappedBytes.TooLarge, readCappedBytes(ByteArrayInputStream(text), 6))
    }

    /** 一个「很大」的流，同时数自己被读走了多少字节。 */
    private class CountingStream(private val total: Int) : InputStream() {
        var readBytes = 0
            private set

        // 单字节读法不该被走到 —— 走了就说明实现换了一种读法，
        // 而那种读法的「提前停手」性质没被这条用例覆盖
        override fun read(): Int = throw IOException("不该走到单字节读法")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (readBytes >= total) return -1
            val n = minOf(len, total - readBytes)
            readBytes += n
            return n
        }
    }
}
