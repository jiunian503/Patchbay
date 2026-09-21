package com.aichat.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「让模型跑一条系统命令」这个工具。
 *
 * ## 这里测的是哪一层
 *
 * 真正起进程、关 stdin、超时强杀、截断输出的是 `:app` 的 `SystemShell`
 * （和内置终端页共用同一份实现，见 SKILL.md §104）。那个东西测不了 ——
 * 它要 `/system/bin/sh`，只能上真机。
 *
 * 所以这一层测的是**它上面那层**：参数怎么校验、结果怎么拼、错误怎么措辞。
 * 注入一个假 [ShellSource]，一个进程都不起。这样「模型看到的到底是什么文字」
 * 就能在秒级的 `:tools:test` 里逐字钉住 —— 而那段文字是模型唯一的判断依据。
 *
 * ## 为什么断言里到处是「退出码」这种字眼
 *
 * 这个工具的错误信息**不是给人看的**，是回灌给模型的。它决定模型下一步是
 * 换命令、还是重试同一条、还是直接告诉用户做不到。所以措辞本身就是行为，
 * 改它等于改行为，得跟着一起测。
 */
class RunCommandToolTest {

    /** 记下被调用的命令，按预设返回。一个进程都不起。 */
    private class FakeShell(
        private val outcome: ShellOutcome,
        private val on: Boolean = true,
    ) : ShellSource {
        var lastCommand: String? = null

        override fun enabled(): Boolean = on

        override suspend fun run(command: String): ShellOutcome {
            lastCommand = command
            return outcome
        }
    }

    private fun shell(outcome: ShellOutcome) = FakeShell(outcome)

    private fun tool(shell: ShellSource) = RunCommandTool(shell)

    private fun args(command: String) = buildJsonObject { put(RunCommandTool.COMMAND, command) }

    // ---------- 正常路径 ----------

    @Test
    fun `成功时把命令和输出都带上`() = runBlocking {
        val result = tool(shell(ShellOutcome("hello_pb", exitCode = 0))).execute(args("echo hello_pb"))

        assertFalse(result.content, result.isError)
        // 命令本身要回显 —— 否则模型在多轮之后分不清这段输出是哪条命令的
        assertTrue(result.content, result.content.contains("$ echo hello_pb"))
        assertTrue(result.content, result.content.contains("hello_pb"))
    }

    /**
     * 「跑完了但什么都没输出」必须说清楚。
     *
     * 留一段空白的话，模型会以为工具坏了、或者输出丢了，于是重试 ——
     * 而重试一次就要用户再点一次确认。
     */
    @Test
    fun `没有输出时明确说明`() = runBlocking {
        val result = tool(shell(ShellOutcome("", exitCode = 0))).execute(args("true"))

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("没有产生输出"))
    }

    @Test
    fun `命令前后的空白会被去掉`() = runBlocking {
        val fake = shell(ShellOutcome("", exitCode = 0))
        tool(fake).execute(args("  ls /sdcard  "))

        // 不去掉的话 `$ ` 后面会多出一段空白，输出里也有
        assertEquals("ls /sdcard", fake.lastCommand)
    }

    // ---------- 失败路径 ----------

    /**
     * 127 是这个工具最该被解释的一个退出码。
     *
     * 模型在 Linux 上的经验里「命令不存在」意味着「装一下就有了」，
     * 而这个系统里装不了（§100.1）。不告诉它「别重试」的话，用户会看到
     * 模型连着试 `apt install`、`pkg install`、`pip install`，每一条都弹一个框。
     */
    @Test
    fun `退出码 127 明确说没有这个命令且别重试`() = runBlocking {
        val result =
            tool(shell(ShellOutcome("sh: nosuchcmd_zzz: inaccessible or not found", exitCode = 127)))
                .execute(args("nosuchcmd_zzz"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("127"))
        assertTrue(result.content, result.content.contains("没有这个命令"))
        assertTrue(result.content, result.content.contains("别重试"))
        // 命令自己的报错也要带上 —— 那是「为什么没有」的线索
        assertTrue(result.content, result.content.contains("inaccessible or not found"))
    }

    @Test
    fun `退出码 126 说是没有执行权限`() = runBlocking {
        val result = tool(shell(ShellOutcome("", exitCode = 126))).execute(args("./x"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("126"))
        assertTrue(result.content, result.content.contains("执行权限"))
    }

    /**
     * 非 0 不总是「命令坏了」：`grep` 没匹配到就是 1。
     *
     * 不写这一句的话，模型会把「没找到」当成错误，然后换个方式再搜一遍。
     */
    @Test
    fun `一般非 0 退出码提示看输出判断`() = runBlocking {
        val result = tool(shell(ShellOutcome("", exitCode = 1))).execute(args("grep x /dev/null"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("退出码 1"))
        assertTrue(result.content, result.content.contains("grep"))
    }

    /**
     * 拿不到退出码 ≠ 退出码 0。
     *
     * 当成 0 的话，一次「进程被系统杀掉、什么都没做」会被报告成成功 ——
     * 模型据此继续往下推理，而底层根本没执行。
     */
    @Test
    fun `拿不到退出码时报错而不是当成功`() = runBlocking {
        val result = tool(shell(ShellOutcome("", exitCode = null))).execute(args("ls"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("没有正常退出"))
    }

    /**
     * 超时要单独说清「跑不了交互式命令」。
     *
     * 这是这个工具最常见的误用：模型写一个 `cat` 或者 `read`，等标准输入，
     * 一直等到被杀。不解释的话它会认为「这条命令太慢」，于是重试 ——
     * 每重试一次用户就要点一次确认。
     */
    @Test
    fun `超时时解释原因并带上已经打印的部分`() = runBlocking {
        val result =
            tool(shell(ShellOutcome("part1\npart2", exitCode = null, timedOut = true)))
                .execute(args("find / -name x"))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("15 秒"))
        assertTrue(result.content, result.content.contains("交互式"))
        // 部分输出是「它为什么被杀」的唯一线索，不能丢
        assertTrue(result.content, result.content.contains("part1"))
        assertTrue(result.content, result.content.contains("part2"))
    }

    @Test
    fun `输出被截断时说明只保留了前面部分`() = runBlocking {
        val result =
            tool(shell(ShellOutcome("A".repeat(100), exitCode = 0, truncated = true)))
                .execute(args("yes"))

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("只保留了前面部分"))
    }

    // ---------- 参数不可信 ----------

    @Test
    fun `缺参数时提示字段名`() = runBlocking {
        val fake = shell(ShellOutcome("", exitCode = 0))
        val result = tool(fake).execute(buildJsonObject { })

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains(RunCommandTool.COMMAND))
        // 参数不对时**不能真的去跑** —— 否则等于把一条空命令交给 shell
        assertNull("参数不合法时不该调用 shell", fake.lastCommand)
    }

    @Test
    fun `空白命令被挡住且不执行`() = runBlocking {
        val fake = shell(ShellOutcome("", exitCode = 0))
        val result = tool(fake).execute(args("   "))

        assertTrue(result.isError)
        assertNull(fake.lastCommand)
    }

    @Test
    fun `命令是对象而不是字符串时被挡住且不执行`() = runBlocking {
        val fake = shell(ShellOutcome("", exitCode = 0))
        val bad = buildJsonObject { put(RunCommandTool.COMMAND, buildJsonObject { put("x", "y") }) }

        val result = tool(fake).execute(bad)

        assertTrue(result.isError)
        assertNull(fake.lastCommand)
    }

    // ---------- 元数据 ----------

    /**
     * 这个工具是唯一一个**能改用户数据**的，必须逐次确认。
     *
     * 分类写错的后果不是「多弹一次框」，而是「默认放行」——
     * 一条模型写错的 `rm -rf` 直接跑掉。所以这条断言是硬要求。
     */
    @Test
    fun `需要用户确认`() {
        assertTrue(tool(shell(ShellOutcome("", 0))).requiresConfirmation)
    }

    /** 弹框里那句话必须说清「跑起来和 App 一样有权限」。那是用户唯一的判断依据。 */
    @Test
    fun `面向用户的说明说清了权限范围`() {
        val summary = tool(shell(ShellOutcome("", 0))).userSummary

        assertTrue(summary, summary.contains("权限"))
        assertTrue(summary, summary.contains("文件"))
    }

    /**
     * 描述里必须写明「做不到的事」。
     *
     * 这段文字是提示词，不是注释 —— 不写的话模型会反复试 `apt install python3`，
     * 每一次都要用户点一下「拒绝」。
     */
    @Test
    fun `描述里写明了做不到的事`() {
        val description = tool(shell(ShellOutcome("", 0))).definition.description

        assertTrue(description, description.contains("apt"))
        assertTrue(description, description.contains("curl"))
        assertTrue(description, description.contains("python"))
    }

    @Test
    fun `工具定义完整`() {
        val definition = tool(shell(ShellOutcome("", 0))).definition

        assertEquals(RunCommandTool.NAME, definition.name)
        assertTrue(definition.description.length > 30)
    }
}
