package com.aichat.plugin.runtime.script

import com.aichat.plugin.manifest.FilesystemScope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沙箱的线格式：`ScriptRequest` 和 `ScriptOutcome` 编成 JSON 再解回来。
 *
 * ## 为什么要单独一个文件守这件事
 *
 * 这两个类是**跨进程**的 —— 宿主编、沙箱解，中间只有一条 binder 通道。
 * 它们出错的方式全是静默的：
 *
 * - 少一个字段：解出来是默认值，脚本按空配置跑，不报错
 * - 分类丢了：失败仍然是失败，但「别重试」和「可以改参数重试」的区别没了，
 *   模型于是对着一个必然超时的调用反复重试
 * - 多文件插件的 `files` 少一个键：`require` 报「没有这个模块」，
 *   而作者明明把文件写进了清单
 *
 * 三种都不会崩，只会让行为悄悄变错。而这个文件跑在 `:plugin` 的纯 JVM 单测里，
 * 秒级就能跑 —— 真机验一次这些要几分钟，而且验不出「字段少了一个」这种安静的问题。
 */
class ScriptWireTest {

    private val json = Json

    private val sample = ScriptRequest(
        pluginId = "pub.example.csvstat",
        pluginName = "CSV 统计",
        entryFile = "index.js",
        files = mapOf(
            "index.js" to "module.exports = { run: () => 1 };",
            "lib/util.js" to "module.exports = { tag: \"util\" };",
        ),
        toolName = "csv_stats",
        inputJson = """{"csv":"a,b\n1,2","path":null}""",
        settings = mapOf("apiKey" to "sk-x", "lang" to "zh"),
        network = listOf("api.example.com", "127.0.0.1"),
        filesystem = FilesystemScope.Read,
        timeoutMs = 12_345,
        memoryLimitMb = 64,
    )

    @Test
    fun `请求往返一次，每一个字段都还在`() {
        val back = json.decodeFromString<ScriptRequest>(json.encodeToString(sample))
        assertEquals(sample, back)
    }

    @Test
    fun `多文件插件的整份 files 原样过去`() {
        val back = json.decodeFromString<ScriptRequest>(json.encodeToString(sample))

        // 不是「长度对上了」就够：键名决定 require 能不能找到模块，
        // 少一个键的表现是「插件里没有模块 lib/util.js」，
        // 而作者手里那份清单明明写着它
        assertEquals(setOf("index.js", "lib/util.js"), back.files.keys)
        assertEquals(sample.files, back.files)
    }

    @Test
    fun `空 files 往返之后还是空，不会变成 null 或抛错`() {
        val empty = sample.copy(files = emptyMap())
        val back = json.decodeFromString<ScriptRequest>(json.encodeToString(empty))
        assertEquals(emptyMap<String, String>(), back.files)
    }

    @Test
    fun `成功和失败在线上能分开`() {
        val ok = json.encodeToString<ScriptOutcome>(ScriptOutcome.Ok("""{"rows":3}"""))
        val failed = json.encodeToString<ScriptOutcome>(
            ScriptOutcome.Failed("超时了", ScriptOutcome.Kind.Timeout),
        )

        assertTrue(json.decodeFromString<ScriptOutcome>(ok) is ScriptOutcome.Ok)
        assertTrue(json.decodeFromString<ScriptOutcome>(failed) is ScriptOutcome.Failed)
    }

    @Test
    fun `四种失败分类往返之后还是同一个`() {
        // 这条是**分类存在的意义**所在：超时和内存超限别重试，脚本报错可以改参数
        // 重试。如果往返过程中 kind 被压成同一个值（比如某个分支漏了 @Serializable
        // 而落到默认值），这个区别就没了 —— 而且不会有任何报错
        ScriptOutcome.Kind.entries.forEach { kind ->
            val failed = ScriptOutcome.Failed("插件说：$kind", kind)
            val back = json.decodeFromString<ScriptOutcome>(json.encodeToString<ScriptOutcome>(failed))

            assertEquals(kind, (back as ScriptOutcome.Failed).kind)
            assertEquals(failed.message, back.message)
        }
    }

    @Test
    fun `脚本返回的 JSON 文本一个字都不改`() {
        // 沙箱在 JS 侧用 JSON.stringify 生成这段文本，宿主原样交给模型。
        // 中间任何一次「重新格式化」都可能把 undefined 字段补上、
        // 把数字精度改掉 —— 那是模型看不到、作者也查不出的偏差
        val payload = """{"a":1,"b":[1,2,{"c":null}],"d":"引号\"和\\反斜杠"}"""
        val back = json.decodeFromString<ScriptOutcome>(
            json.encodeToString<ScriptOutcome>(ScriptOutcome.Ok(payload)),
        )
        assertEquals(payload, (back as ScriptOutcome.Ok).json)
    }

    @Test
    fun `失败信息里的换行和引号原样过去`() {
        // 沙箱把 JS 的报错原文塞进 message，那里面通常带换行和引号。
        // 转义出问题的话模型看到的是一句断掉的、看不懂的话
        val messy = "第 3 行出错：\n  unexpected \"}\"\n请检查参数"
        val back = json.decodeFromString<ScriptOutcome>(
            json.encodeToString<ScriptOutcome>(ScriptOutcome.Failed(messy, ScriptOutcome.Kind.ScriptError)),
        )
        assertEquals(messy, (back as ScriptOutcome.Failed).message)
    }
}
