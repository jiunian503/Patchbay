package com.aichat.plugin.runtime.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 头值的哨兵编码。
 *
 * ## 为什么这不是「顺手加的编码」
 *
 * OkHttp 会**拒绝**任何含非 ASCII 的头值（抛 `Unexpected char`）。而 MCP 的
 * `Mcp-Name` 是从 body 里的工具名镜像出来的 —— 工具名可以是中文。
 * 所以不编码的话，症状不是「对端看不懂」，而是**请求根本发不出去**，
 * 报的是 OkHttp 的一句 `Unexpected char`，和 MCP 一点关系都看不出来。
 *
 * ## 下面几条用例的期望值**来自规范原文的示例表**
 *
 * 不是我自己算的 base64。这正是这类编码最容易出错的地方：
 * 自己算一遍、再拿自己算的结果当期望值，两边一起错的话测试全绿。
 */
class McpWireTest {

    @Test
    fun `纯 ASCII 明文不编码`() {
        assertEquals("us-west1", McpWire.encodeHeaderValue("us-west1"))
    }

    @Test
    fun `含非 ASCII 的值编码成哨兵`() {
        // 规范示例：`"Hello, 世界"` → `=?base64?SGVsbG8sIOS4lueVjA==?=`
        assertEquals(
            "=?base64?SGVsbG8sIOS4lueVjA==?=",
            McpWire.encodeHeaderValue("Hello, 世界"),
        )
    }

    @Test
    fun `首尾空格必须编码`() {
        // 规范示例：`" padded "` → `=?base64?IHBhZGRlZCA=?=`
        // 不编码的话，HTTP 层会把首尾空格剥掉，对端拿到的是一个不同的值
        assertEquals("=?base64?IHBhZGRlZCA=?=", McpWire.encodeHeaderValue(" padded "))
    }

    @Test
    fun `换行必须编码`() {
        // 规范示例：`"line1\nline2"` → `=?base64?bGluZTEKbGluZTI=?=`
        assertEquals("=?base64?bGluZTEKbGluZTI=?=", McpWire.encodeHeaderValue("line1\nline2"))
    }

    @Test
    fun `长得像哨兵的值也要编码`() {
        // 规范示例：`"=?base64?literal?="` → `=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=`
        //
        // 这一条是**消歧义**用的：不编码的话，对端看到 `=?base64?literal?=`
        // 会先解码一次，解出来是别的字节，两边比出来的值就对不上 ——
        // 而报的是「头与 body 不一致」，看不出是这个工具名长得太巧
        assertEquals(
            "=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=",
            McpWire.encodeHeaderValue("=?base64?literal?="),
        )
    }

    @Test
    fun `编码后的值一定是 ASCII`() {
        // 这条是「OkHttp 不会拒收」的直接保证 —— 也是做编码的**唯一**目的
        val encoded = McpWire.encodeHeaderValue("中文工具名")
        assertEquals(
            "编码结果必须全是可见 ASCII，否则 OkHttp 会拒绝这个头",
            true,
            encoded.all { it.code in 0x21..0x7E },
        )
    }

    @Test
    fun `前缀后缀是小写的`() {
        // 规范明确要求大小写敏感、必须严格小写。写成 `=?Base64?` 对端不认
        val encoded = McpWire.encodeHeaderValue("中文")
        assertEquals(true, encoded.startsWith("=?base64?"))
        assertEquals(true, encoded.endsWith("?="))
    }

    @Test
    fun `空串编码而不是明文`() {
        // 明文空串会变成「没有这个头」，而对端要的是「这个值确实是空的」
        assertEquals("=?base64??=", McpWire.encodeHeaderValue(""))
    }

    @Test
    fun `id 按文本比较 数字和字符串都认`() {
        // 规范说整数 id 按数值比较（42.0 和 42 相等），字符串 id 也允许。
        // 收成 Long 会在对端回字符串 id 时解析失败 —— 那是一个合规的服务端
        assertEquals(true, resp("""{"jsonrpc":"2.0","id":1,"result":{}}""").idMatches(1))
        assertEquals(true, resp("""{"jsonrpc":"2.0","id":"1","result":{}}""").idMatches(1))
        assertEquals(false, resp("""{"jsonrpc":"2.0","id":2,"result":{}}""").idMatches(1))
        assertEquals(false, resp("""{"jsonrpc":"2.0","result":{}}""").idMatches(1))
    }

    private fun resp(json: String): JsonRpcResponse =
        McpWire.json.decodeFromJsonElement(
            JsonRpcResponse.serializer(),
            McpWire.json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject,
        )
}
