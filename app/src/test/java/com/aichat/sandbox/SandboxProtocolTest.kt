package com.aichat.sandbox

import com.aichat.plugin.runtime.script.ScriptOutcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 沙箱的「遗言」机制：死因怎么落盘、怎么读回来、以及怎么变成给模型的一句话。
 *
 * ## 为什么这些断言值得逐条写
 *
 * 死因这条路**只在出事的时候才走**：沙箱超时自杀、内存超限自杀、引擎崩。
 * 平时一次都不执行，真机上想验就得故意让插件跑飞 —— 而那时进程已经没了，
 * 断不了点。更要紧的是它失败的方式是**静默且方向性错误**的：
 *
 * - 少了 `fsync`，SIGKILL 会把缓冲里的字丢掉，客户端读到空文件 →
 *   「内存超限」被报成「引擎崩了」，用户被引向完全错误的方向
 * - 分类映射写反了，模型会对着一个必然超时的调用反复重试
 *
 * 这两条都不会崩、不会报错，只会让诊断结论是错的。单测是唯一能低成本压到的地方。
 */
class SandboxProtocolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val filesDir: File get() = tmp.root

    // ── 落盘与读回 ────────────────────────────────────────────────────────────

    @Test
    fun `写下的死因能立刻读回来`() {
        SandboxProtocol.writeDeath(filesDir, SandboxProtocol.DEATH_OOM)
        assertEquals(SandboxProtocol.DEATH_OOM, SandboxProtocol.readDeath(filesDir))
    }

    @Test
    fun `没写过死因时读出来是 null`() {
        // null 的语义是「沙箱是意外死的」，这是分类 Crashed 的唯一判据。
        // 这里如果返回空串或抛错，「意外死亡」就永远判不出来
        assertNull(SandboxProtocol.readDeath(filesDir))
    }

    @Test
    fun `清掉之后读不到 —— 死因的生命周期是一次调用`() {
        SandboxProtocol.writeDeath(filesDir, SandboxProtocol.DEATH_OOM)
        SandboxProtocol.clearDeath(filesDir)
        assertNull(SandboxProtocol.readDeath(filesDir))
    }

    @Test
    fun `清掉一个不存在的死因文件不报错`() {
        // 每次调用开始都会先清一次，而绝大多数调用之前根本没有死因文件
        SandboxProtocol.clearDeath(filesDir)
        SandboxProtocol.clearDeath(filesDir)
        assertNull(SandboxProtocol.readDeath(filesDir))
    }

    @Test
    fun `空文件当成没有死因`() {
        // 进程在写死因的中途被杀（写了 0 字节），留下的就是这个形状。
        // 把它当成一种死因的话，客户端会拿着一个空字符串去匹配 —— 匹配不上，
        // 落到 Crashed 分支。那是对的结果，但要有用例钉住它**是**这么落的
        SandboxProtocol.deathFile(filesDir).apply {
            parentFile?.mkdirs()
            writeText("")
        }
        assertNull(SandboxProtocol.readDeath(filesDir))
    }

    @Test
    fun `父目录不存在时写死因会自己建出来`() {
        // filesDir/sandbox/ 平时不存在 —— 只有真出过事才会有。
        // 这里不建目录的话，第一次内存超限会以一个「写不进去」的异常收场，
        // 而那时进程正忙着自杀，没人看那个异常
        assertFalse(SandboxProtocol.deathFile(filesDir).parentFile!!.exists())
        SandboxProtocol.writeDeath(filesDir, SandboxProtocol.DEATH_OOM)
        assertEquals(SandboxProtocol.DEATH_OOM, SandboxProtocol.readDeath(filesDir))
    }

    @Test
    fun `死因文件不在 filesDir 根上，另起一个目录`() {
        // 根目录上已经有 crash/ 之类的目录，而这是沙箱的内部状态。
        // 混在一起的话，清理 filesDir 的人会顺手把它删掉 —— 而它删掉之后
        // 死因读不出来，内存超限就变成了「引擎崩了」
        val file = SandboxProtocol.deathFile(filesDir)
        assertEquals("sandbox", file.parentFile!!.name)
        assertEquals(filesDir, file.parentFile!!.parentFile)
    }

    // ── 死因 → 给模型的那句话 ─────────────────────────────────────────────────

    @Test
    fun `内存超限报成 OutOfMemory，并且指向 memoryLimitMb 这个旋钮`() {
        val outcome = SandboxProtocol.deathOutcome("CSV 统计", SandboxProtocol.DEATH_OOM)

        assertEquals(ScriptOutcome.Kind.OutOfMemory, outcome.kind)
        assertTrue(outcome.message.contains("CSV 统计"))
        // 作者能改的就是清单里那个字段名。只说「内存超了」等于没说下一步
        assertTrue(outcome.message.contains("memoryLimitMb"))
        assertTrue(outcome.message.contains("别"))
    }

    @Test
    fun `没有死因报成 Crashed，并且说清是插件的问题`() {
        val outcome = SandboxProtocol.deathOutcome("CSV 统计", null)

        assertEquals(ScriptOutcome.Kind.Crashed, outcome.kind)
        assertTrue(outcome.message.contains("CSV 统计"))
        assertTrue(outcome.message.contains("别"))
    }

    @Test
    fun `有死因和没死因是两个不同的分类`() {
        // 这条是整条判据的**分界**：有遗言 = 我们按规矩杀的，没遗言 = 它自己崩的。
        // 两边映射成同一个 kind 的话，这条判据就白设了
        val oom = SandboxProtocol.deathOutcome("P", SandboxProtocol.DEATH_OOM)
        val crashed = SandboxProtocol.deathOutcome("P", null)
        assertFalse(oom.kind == crashed.kind)
    }

    @Test
    fun `认不出来的死因当成崩溃，不会静默吞掉`() {
        // 以后新增一种死因、而客户端还是旧版时会走到这里。
        // 落到「没死因」那一支是对的：至少它说的是「宿主没主动终止它」，
        // 比编一个不存在的分类要好
        val outcome = SandboxProtocol.deathOutcome("P", "将来才有的原因")
        assertEquals(ScriptOutcome.Kind.Crashed, outcome.kind)
    }

    @Test
    fun `超时那句话指向 timeoutMs，不是 memoryLimitMb`() {
        val outcome = SandboxProtocol.timeoutOutcome("CSV 统计", 30_000)

        assertEquals(ScriptOutcome.Kind.Timeout, outcome.kind)
        assertTrue(outcome.message.contains("30 秒"))
        assertTrue(outcome.message.contains("timeoutMs"))
        // 两个旋钮挨得很近，复制粘贴时最容易串台
        assertFalse(outcome.message.contains("memoryLimitMb"))
    }

    @Test
    fun `沙箱起不来报成 Unavailable，并且明说不是插件的问题`() {
        val outcome = SandboxProtocol.sandboxBrokenOutcome("CSV 统计", "系统拒绝绑定沙箱服务")

        assertEquals(ScriptOutcome.Kind.Unavailable, outcome.kind)
        assertTrue(outcome.message.contains("系统拒绝绑定沙箱服务"))
        // 这一句要让用户别去折腾插件 —— 他没做错什么
        assertTrue(outcome.message.contains("不是插件的问题"))
    }
}
