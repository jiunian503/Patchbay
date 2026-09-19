package com.aichat.domain.llm

import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {

    private val f1 = """{"a":1}"""
    private val f2 = """{"b":2}"""

    // ---------- 基本事件 ----------

    @Test
    fun `单个事件以空行结束`() {
        val p = SseParser()
        assertEquals(listOf(f1), p.feed("data: $f1\n\n"))
    }

    @Test
    fun `一次喂入多个事件`() {
        val p = SseParser()
        assertEquals(listOf(f1, f2), p.feed("data: $f1\n\ndata: $f2\n\n"))
    }

    @Test
    fun `连续空行不产生空事件`() {
        val p = SseParser()
        assertEquals(emptyList<String>(), p.feed("\n\n\n"))
    }

    // ---------- 跨块拼行（TCP 分片与行边界无关） ----------

    @Test
    fun `一行被切成多个块也能拼回`() {
        val p = SseParser()
        val chunks = listOf("da", "ta: {\"a\"", ":1}\n", "\n")

        val out = chunks.flatMap { p.feed(it) }
        assertEquals(listOf(f1), out)
    }

    @Test
    fun `事件边界本身也可能被切开`() {
        val p = SseParser()
        // 第一个事件与第二个事件的 data 落在同一块，但分隔空行的两个 \n 分别落在两块
        val out = mutableListOf<String>()
        out += p.feed("data: $f1\n")
        out += p.feed("\ndata: $f2\n")
        out += p.feed("\n")
        assertEquals(listOf(f1, f2), out)
    }

    // ---------- 行尾与字段语法 ----------

    @Test
    fun `CRLF 行尾同样识别`() {
        val p = SseParser()
        assertEquals(listOf(f1), p.feed("data: $f1\r\n\r\n"))
    }

    @Test
    fun `冒号后没有空格也合法`() {
        val p = SseParser()
        assertEquals(listOf(f1), p.feed("data:$f1\n\n"))
    }

    @Test
    fun `冒号后只吃掉一个空格`() {
        val p = SseParser()
        // 负载本身以空格开头时必须保留（这里是两个空格，吃掉一个还剩一个）
        assertEquals(listOf(" $f1"), p.feed("data:  $f1\n\n"))
    }

    @Test
    fun `单独一行 data 表示空负载`() {
        val p = SseParser()
        assertEquals(listOf(""), p.feed("data\n\n"))
    }

    @Test
    fun `同一事件的多个 data 行按换行拼接`() {
        val p = SseParser()
        // 规范要求多行 data 用 \n 连接
        assertEquals(listOf("$f1\n$f2"), p.feed("data: $f1\ndata: $f2\n\n"))
    }

    // ---------- 非 data 字段必须被跳过 ----------

    @Test
    fun `注释行被忽略`() {
        val p = SseParser()
        // 反向代理常用 `: ping` 做 keep-alive，不能让它污染负载
        assertEquals(listOf(f1), p.feed(": ping\ndata: $f1\n\n"))
    }

    @Test
    fun `event 与 id 字段不会被当成负载`() {
        val p = SseParser()
        val out = p.feed("event: message\nid: 42\ndata: $f1\n\n")
        assertEquals(listOf(f1), out)
    }

    @Test
    fun `只有 event 没有 data 时什么也不产出`() {
        val p = SseParser()
        assertEquals(emptyList<String>(), p.feed("event: ping\n\n"))
    }

    // ---------- 流结束时的冲刷 ----------

    @Test
    fun `finish 冲刷未换行的末行`() {
        val p = SseParser()
        assertEquals(emptyList<String>(), p.feed("data: $f1"))
        assertEquals(listOf(f1), p.finish())
    }

    @Test
    fun `finish 冲刷没有空行收尾的最后一个事件`() {
        val p = SseParser()
        assertEquals(emptyList<String>(), p.feed("data: $f1\n"))
        assertEquals(listOf(f1), p.finish())
    }

    @Test
    fun `正常结束后 finish 不重复产出`() {
        val p = SseParser()
        assertEquals(listOf(f1), p.feed("data: $f1\n\n"))
        assertEquals(emptyList<String>(), p.finish())
    }

    // ---------- 直接按行喂（OkHttp 路径） ----------

    @Test
    fun `按行喂入的组装器行为一致`() {
        val a = SseEventAssembler()
        assertNull(a.accept("event: message"))
        assertNull(a.accept("data: $f1"))
        assertEquals(f1, a.accept(""))
        assertNull(a.accept("data: $f2"))
        assertNull(a.accept(": keep-alive"))
        assertEquals(f2, a.flush())
    }

    @Test
    fun `按行喂入时 CRLF 残留的回车被吃掉`() {
        val a = SseEventAssembler()
        assertNull(a.accept("data: $f1\r"))
        assertEquals(f1, a.accept("\r"))
    }

    // ---------- UTF-8 边界 ----------

    /**
     * 上游按字节切分（切点必然落在某个汉字中间）时，只要用 [InputStreamReader]
     * 做增量解码，内容就完好无损。
     *
     * 这是对上游的**契约测试**：如果谁把 HTTP 层改成
     * `String(bytes, 0, n, UTF_8)` 逐块解码，这个测试会立刻变红。
     */
    @Test
    fun `按字节切分的中文流经增量解码后完好`() {
        val payload = "data: {\"content\":\"中文测试内容\"}\n\n"
        val reader = InputStreamReader(
            ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8)),
            Charsets.UTF_8,
        )

        val parser = SseParser()
        val out = mutableListOf<String>()
        val buf = CharArray(1) // 每次只读 1 个字符，最大化切分点数量
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            out += parser.feed(String(buf, 0, n))
        }
        out += parser.finish()

        assertEquals(listOf("""{"content":"中文测试内容"}"""), out)
    }
}
