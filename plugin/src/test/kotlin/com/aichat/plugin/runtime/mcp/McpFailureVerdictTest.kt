package com.aichat.plugin.runtime.mcp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每一条给模型看的 MCP 失败话，必须自己说清「能不能重试」。
 *
 * ## 为什么值得一条测试
 *
 * `McpFailure.retryable` 的 KDoc 原来写着「它决定写给模型的那句话」——
 * **那句话是错的**：模型只拿到 `message` 一个字符串，`retryable` 被每一条失败
 * 路径设置，**生产代码里却没有一处读它**（只有测试在读）。所以「能不能重试」
 * 这件事**只活在文案里**。
 *
 * 文案漏了它的后果不是「少一句话」，而是**模型的行为变了**：它会对一个永远
 * 不会成功的请求反复重试，白烧好几轮上下文（`ScriptRuntime.Kind` 的 KDoc 里
 * 记着同一课 —— 「每一句 message 自己必须把「能不能重试」说清楚」）。
 *
 * 实测：`readJson` / `readSse` 的两条超限文案、以及 `describeHttpStatus` 的
 * 五个分支**全都没写**这一句，而且**一条测试都没有**断言过它们。反过来，
 * 测试里倒是写着 `assertFalse("超限重试也没用", failure.retryable)` ——
 * 「重试也没用」这个结论只写在**断言消息**里，没写在给模型的那句话里。
 *
 * ## 钉的是什么
 *
 * 钉的是**「有没有给出裁决」**，不是具体措辞：
 *
 * - 每一条 `throw McpFailure(...)` / `throw McpHttpFailure(...)` 的实参里，
 *   要么直接含一个裁决词，要么把裁决外包给 [retryAdvice] / [describeHttpStatus]
 *   （那两个函数有自己的第二条测试兜着）
 * - 状态码那两档（[isRetryableStatus] 与 [retryAdvice]）必须**同进同退**
 *
 * 所以换措辞不会红，但**新加一个 throw 点忘了写裁决会红** —— 那正是它要防的。
 */
class McpFailureVerdictTest {

    /**
     * 裁决词表。
     *
     * ⚠️ 这是一张**手写的**词表，所以换一种全新说法会误报。误报的方向是安全的
     * （红，而不是静默放过），而且失败信息会请你把新说法加进来。
     */
    private val verdicts = listOf(
        "可以稍后重试",
        "重试也不会成功",
        "重试不会改变结果",
        "重试没有用",
        "别重试",
        "不要重试",
    )

    /** 「可以重试」那一侧的裁决词。 */
    private val canRetry = listOf("可以稍后重试")

    /** 「别重试」那一侧的裁决词（不含 [canRetry]）。 */
    private val cannotRetry = verdicts - canRetry

    /** 允许「把裁决外包给一个自己有守卫的函数」。 */
    private val delegated = listOf("retryAdvice(", "describeHttpStatus(")

    // ------------------------------------------------------------------ 一、状态码

    /**
     * `isRetryableStatus` 与 [retryAdvice] 是**同一个判据的两种渲染**：
     * 一个给宿主看（`retryable`），一个给模型看（那句话）。
     *
     * 分成两个函数是为了「一个判据、一处实现」，但**分开写就会漂** ——
     * 所以这里逐档过一遍，两边必须同进同退。
     */
    @Test
    fun `状态码的裁决与 retryable 同进同退`() {
        val statuses = listOf(400, 401, 403, 404, 405, 409, 422, 429, 500, 502, 503, 504)

        statuses.forEach { status ->
            val advice = retryAdvice(status)
            val saysCan = canRetry.any { advice.contains(it) }
            val saysCannot = cannotRetry.any { advice.contains(it) }

            assertTrue(
                "HTTP $status 的裁决里没写清能不能重试：$advice",
                saysCan || saysCannot,
            )
            assertTrue(
                "HTTP $status 的裁决两边都沾，模型读不出结论：$advice",
                !(saysCan && saysCannot),
            )
            assertEquals(
                "HTTP $status 上两条判据不一致：isRetryableStatus = ${isRetryableStatus(status)}，" +
                    "而给模型的话是「$advice」—— 一个判据两个渲染，必须同进同退",
                isRetryableStatus(status),
                saysCan,
            )
        }
    }

    /** [describeHttpStatus] 的每一档都得带上裁决 —— 它原来五档一个都没带。 */
    @Test
    fun `describeHttpStatus 的每一档都带裁决`() {
        // 418 是故意挑的「谁也没提过的状态码」，走 else 分支
        val statuses = listOf(400, 403, 404, 405, 418, 500, 503)

        statuses.forEach { status ->
            val message = describeHttpStatus(status, "api.example.com", "服务端原话")
            val saysCan = canRetry.any { message.contains(it) }
            val saysCannot = cannotRetry.any { message.contains(it) }

            assertTrue(
                "HTTP $status 这句话没告诉模型该不该重试，它只会对着同一个请求反复试：$message",
                saysCan || saysCannot,
            )
            assertEquals(
                "HTTP $status 的裁决和 isRetryableStatus 对不上：$message",
                isRetryableStatus(status),
                saysCan,
            )
        }
    }

    // ------------------------------------------------------------------ 二、源码扫描

    private val sourceRoot: File = run {
        val raw = System.getProperty("patchbay.sourceRoot")
        assertNotNull(
            "构建配置没把 patchbay.sourceRoot 传给测试 JVM —— " +
                "检查 plugin/build.gradle.kts 的 tasks.test",
            raw,
        )
        File(raw!!).also {
            assertTrue("patchbay.sourceRoot 指的不是目录：${it.absolutePath}", it.isDirectory)
        }
    }

    private fun mcpSources(): List<File> {
        val dir = File(sourceRoot, "plugin/src/main/kotlin/com/aichat/plugin/runtime/mcp")
        assertTrue("找不到 MCP 源码目录：${dir.absolutePath}", dir.isDirectory)
        return dir.listFiles { f -> f.isFile && f.extension == "kt" }?.sorted().orEmpty()
    }

    private val throwSite = Regex("""throw Mcp(?:Http)?Failure\(""")

    /**
     * 从那个 `(` 开始配平，返回括号**里面**的原文。
     *
     * ⚠️ 数括号前先把字符串字面量整段遮掉：文案里可能出现 `（`（全角，本来
     * 就不算），也可能出现 `take(300)`（配平的，算不算都对）—— 但不该**假设**
     * 它们都配平。遮掉之后这条判据只依赖代码结构，不依赖文案里写了什么。
     *
     * 返回 `null` 表示没配平（源码被改坏了），调用方要把它当成失败而不是跳过。
     */
    private fun argsFrom(text: String, openParen: Int): String? {
        val masked = LITERAL.replace(text) { " ".repeat(it.value.length) }
        var depth = 0
        for (i in openParen until masked.length) {
            when (masked[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(openParen + 1, i)
                }
            }
        }
        return null
    }

    private companion object {
        /** 普通字符串字面量（Kotlin 里不能跨行，所以这条正则够用）。 */
        val LITERAL = Regex(""""(?:\\.|[^"\\\n])*"""")
    }

    /**
     * 逐个 throw 点过一遍。
     *
     * ⚠️ 为什么是扫源码而不是跑用例：**跑用例只能覆盖已经存在的那几条路径**，
     * 而这条规则要防的恰恰是「以后新加一个 throw 点忘了写裁决」。扫描会在
     * 新代码加进来的那一刻就发现它。
     *
     * 源码本来就是编译输入，所以这里**不需要**额外声明 `inputs`。
     */
    @Test
    fun `每个 throw 点的文案里都有裁决`() {
        val files = mcpSources()
        assertTrue("一个 MCP 源文件都没扫到 —— 扫描变成了一句空话", files.size >= 3)

        val checked = mutableListOf<String>()
        val missing = mutableListOf<String>()

        files.forEach { file ->
            val text = file.readText()
            throwSite.findAll(text).forEach { m ->
                checked += file.name
                // `m.range.last` 就是 `throw McpFailure(` 的那个 `(`
                val args = argsFrom(text, m.range.last)
                if (args == null) {
                    missing += "${file.name}: 括号没配平，扫描没取到实参"
                } else if (verdicts.none { args.contains(it) } &&
                    delegated.none { args.contains(it) }
                ) {
                    missing += "${file.name}: ${args.trim().lines().first()}"
                }
            }
        }

        assertTrue(
            "只在 ${checked.size} 个 throw 点上扫过 —— 源码结构变了，这条守卫在看空气。" +
                "扫到的文件：${files.map { it.name }}",
            checked.size >= 8,
        )
        assertTrue(
            "这些 McpFailure 没告诉模型该不该重试。模型只能从这句话里读出来，" +
                "漏了它就会对着同一个请求反复重试：\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }
}
