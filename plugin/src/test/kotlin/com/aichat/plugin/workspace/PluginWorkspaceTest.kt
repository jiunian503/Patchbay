package com.aichat.plugin.workspace

import com.aichat.plugin.manifest.FilesystemScope
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [PluginWorkspaces] / [PluginWorkspace] 的行为测试。
 *
 * ## 这里守的是「边界」，不是「功能」
 *
 * 读写往返这类用例只占一小半 —— 它们挂了谁都能看出来。真正值钱的是
 * **越界那一组**：路径穿越、跨插件、权限、四条上限。这些在正常使用下
 * 永远不会触发，所以只能靠这里钉住。
 *
 * ## 为什么这些能在 JVM 上跑
 *
 * 因为工作区只用 `java.io.File`，没有一行 Android API。所以这一整个文件的
 * 用例加起来比一条 instrumented 用例还快 —— 这也是把它放在 `:plugin`
 * 而不是 `:app` 的理由。
 */
class PluginWorkspaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun workspaces() = PluginWorkspaces(File(tmp.root, "plugin-workspaces"))

    private fun open(
        id: String = "pub.test.demo",
        scope: FilesystemScope = FilesystemScope.ReadWrite,
    ) = workspaces().open(id, scope)

    // ── 基本读写 ──────────────────────────────────────────────────────────────

    @Test
    fun `写进去读得回来`() {
        val ws = open()
        ws.write("cache/a.txt", "你好")
        assertEquals("你好", ws.read("cache/a.txt"))
    }

    @Test
    fun `父目录会自动建`() {
        val ws = open()
        ws.write("a/b/c/d.txt", "x")
        assertTrue(ws.exists("a/b/c/d.txt"))
        // 中间那几层也得真的建出来，而不是只把文件丢在根上
        assertTrue(ws.exists("a/b/c"))
    }

    @Test
    fun `再写一次同名文件是覆盖_不是报错`() {
        val ws = open()
        ws.write("k.txt", "第一次")
        ws.write("k.txt", "第二次")
        assertEquals("第二次", ws.read("k.txt"))
    }

    @Test
    fun `list 给的是排好序的名字`() {
        val ws = open()
        ws.write("b.txt", "1")
        ws.write("a.txt", "1")
        ws.write("sub/c.txt", "1")
        assertEquals(listOf("a.txt", "b.txt", "sub"), ws.list("."))
    }

    @Test
    fun `点表示工作区根`() {
        val ws = open()
        // open 就该把目录建出来。要等第一次 write 才存在的话，
        // `if (!host.fs.exists(".")) 初始化` 这种写法会每次都重新初始化，
        // 而且看不出任何异常 —— 见 PluginWorkspaces.open 的注释
        assertTrue(ws.exists("."))
        assertEquals(emptyList<String>(), ws.list("."))
    }

    @Test
    fun `没有权限时连目录都不建`() {
        val all = workspaces()
        all.open("pub.test.none", FilesystemScope.None)
        assertFalse(File(tmp.root, "plugin-workspaces/pub.test.none").exists())
    }

    // ── 越界：这一节是重点 ────────────────────────────────────────────────────

    @Test
    fun `路径里有两点就拒`() {
        val ws = open()
        val e = runCatching { ws.read("../outside.txt") }.exceptionOrNull()
        assertTrue("期望 WorkspaceException，实际 $e", e is WorkspaceException)
        assertTrue(e!!.message!!, e.message!!.contains(".."))
    }

    @Test
    fun `中间夹着两点也拒_不只是开头`() {
        val ws = open()
        val e = runCatching { ws.write("cache/../../escape.txt", "x") }.exceptionOrNull()
        assertTrue(e is WorkspaceException)
    }

    @Test
    fun `绝对路径拒`() {
        val ws = open()
        val e = runCatching { ws.read("/etc/hosts") }.exceptionOrNull()
        assertTrue(e is WorkspaceException)
        assertTrue(e!!.message!!, e.message!!.contains("相对"))
    }

    @Test
    fun `空路径拒`() {
        val ws = open()
        assertTrue(runCatching { ws.list("") }.exceptionOrNull() is WorkspaceException)
        assertTrue(runCatching { ws.read("   ") }.exceptionOrNull() is WorkspaceException)
    }

    @Test
    fun `两个插件互相看不到对方的东西`() {
        val all = workspaces()
        all.open("pub.test.a", FilesystemScope.ReadWrite).write("secret.txt", "a 的东西")

        val b = all.open("pub.test.b", FilesystemScope.ReadWrite)
        assertFalse(b.exists("secret.txt"))
        // 连「a 那个目录」这个写法本身都该被拒（它在 b 的工作区之外）
        assertTrue(runCatching { b.read("../pub.test.a/secret.txt") }.exceptionOrNull() is WorkspaceException)
    }

    @Test
    fun `工作区里的软链接指到外面也拒`() {
        // 这条测的是第二道守卫（canonicalFile + 前缀比对）。
        // 第一道（逐段拒 ..）在这条路径上看不出问题，所以它是唯一能
        // 真正验证兜底那道守卫还在的用例。
        //
        // **为什么这里经常跳过**：`Files.createSymbolicLink` 在 Windows 上
        // 要开发者模式或特权（不是权限不足，是 JDK 没走那条路）。
        // 目录联接（junction）倒是普通权限就能建，但 Java 没有对应的 API。
        // 所以这条在 Windows 上跳过、在 Linux/Android 上真跑 ——
        // 真机上还有一份同名的用例（`SandboxWorkspaceTest`）。
        val ws = open()
        ws.write("anchor.txt", "x")

        val outside = File(tmp.root, "outside-secret.txt")
        outside.writeText("不该被读到")

        val link = File(tmp.root, "plugin-workspaces/pub.test.demo/escape")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), Paths.get(outside.absolutePath))
        }.isSuccess
        assumeTrue("Windows 上 JDK 建不了软链接，跳过（真机那份会跑）", created)

        val e = runCatching { ws.read("escape") }.exceptionOrNull()
        assertTrue("软链接没被挡住，实际拿到：${runCatching { ws.read("escape") }.getOrNull()}", e is WorkspaceException)
    }

    @Test
    fun `插件 id 不像目录名就拒`() {
        val all = workspaces()
        for (bad in listOf("", "..", "a/../b", "A.B", "pub.", ".pub", "pub..test")) {
            val e = runCatching { all.open(bad, FilesystemScope.ReadWrite) }.exceptionOrNull()
            assertTrue("「$bad」应该被拒，实际 $e", e is IllegalArgumentException)
        }
    }

    // ── 权限 ──────────────────────────────────────────────────────────────────

    @Test
    fun `只读权限能读不能写`() {
        val ws = open(scope = FilesystemScope.ReadWrite)
        ws.write("a.txt", "内容")

        val ro = open(scope = FilesystemScope.Read)
        assertEquals("内容", ro.read("a.txt"))
        assertTrue(ro.exists("a.txt"))

        val e = runCatching { ro.write("b.txt", "x") }.exceptionOrNull()
        assertTrue(e is WorkspaceException)
        assertTrue(e!!.message!!, e.message!!.contains("read"))
    }

    @Test
    fun `没有权限时读写都拒`() {
        val ws = open(scope = FilesystemScope.None)
        assertTrue(runCatching { ws.read("a.txt") }.exceptionOrNull() is WorkspaceException)
        assertTrue(runCatching { ws.write("a.txt", "x") }.exceptionOrNull() is WorkspaceException)
        assertTrue(runCatching { ws.list(".") }.exceptionOrNull() is WorkspaceException)
        assertTrue(runCatching { ws.exists("a.txt") }.exceptionOrNull() is WorkspaceException)
    }

    // ── 四条上限 ──────────────────────────────────────────────────────────────

    @Test
    fun `单文件超过 1 MB 拒`() {
        val ws = open()
        val e = runCatching { ws.write("big.txt", "x".repeat(1024 * 1024 + 1)) }.exceptionOrNull()
        assertTrue(e is WorkspaceException)
        assertTrue(e!!.message!!, e.message!!.contains("上限"))
        // 拒了就得真的没写进去
        assertFalse(ws.exists("big.txt"))
    }

    @Test
    fun `刚好 1 MB 是允许的`() {
        val ws = open()
        ws.write("exact.txt", "x".repeat(1024 * 1024))
        assertEquals(1024 * 1024, ws.read("exact.txt").length)
    }

    @Test
    fun `合计超过 8 MB 拒`() {
        val ws = open()
        val chunk = "x".repeat(1024 * 1024)
        repeat(8) { ws.write("f$it.txt", chunk) }

        val e = runCatching { ws.write("f8.txt", chunk) }.exceptionOrNull()
        assertTrue(e is WorkspaceException)
        assertTrue(e!!.message!!, e.message!!.contains("工作区"))
        assertFalse(ws.exists("f8.txt"))
    }

    @Test
    fun `合计算的是写完之后的大小_覆盖不重复计`() {
        val ws = open()
        val chunk = "x".repeat(1024 * 1024)
        repeat(8) { ws.write("f$it.txt", chunk) }
        // 已经是 8 MB 了，但覆盖其中一个不该再占新的空间
        ws.write("f0.txt", "小内容")
        assertEquals("小内容", ws.read("f0.txt"))
    }

    @Test
    fun `文件数超过 256 拒`() {
        val ws = open()
        repeat(256) { ws.write("f$it.txt", "x") }

        val e = runCatching { ws.write("f256.txt", "x") }.exceptionOrNull()
        assertTrue(e is WorkspaceException)
        assertTrue(e!!.message!!, e.message!!.contains("256"))
        // 但覆盖已有的那些还是可以的 —— 上限挡的是「越堆越多」，不是「不能用」
        ws.write("f0.txt", "覆盖")
        assertEquals("覆盖", ws.read("f0.txt"))
    }

    @Test
    fun `路径太深拒`() {
        val ws = open()
        val deep = (1..17).joinToString("/") { "d$it" } + "/f.txt"
        val e = runCatching { ws.write(deep, "x") }.exceptionOrNull()
        assertTrue(e is WorkspaceException)
        assertTrue(e!!.message!!, e.message!!.contains("层"))
    }

    @Test
    fun `读一个超限的文件也拒_不只是写的时候挡`() {
        // 上限可能是「上一次运行时还没有」的，所以读这一侧也必须挡 ——
        // 否则插件能一次性把 100 MB 拉进沙箱内存，绕过内存看门狗
        // （它轮询 VmRSS，而这一次分配在两次轮询之间就完成了）
        val ws = open()
        val dir = File(tmp.root, "plugin-workspaces/pub.test.demo")
        dir.mkdirs()
        File(dir, "huge.txt").writeText("x".repeat(2 * 1024 * 1024))

        val e = runCatching { ws.read("huge.txt") }.exceptionOrNull()
        assertTrue(e is WorkspaceException)
        assertTrue(e!!.message!!, e.message!!.contains("上限"))
    }

    // ── 卸载与清扫 ────────────────────────────────────────────────────────────

    @Test
    fun `delete 连目录带内容一起删掉`() {
        val all = workspaces()
        all.open("pub.test.a", FilesystemScope.ReadWrite).write("deep/a/b.txt", "x")

        assertTrue(all.delete("pub.test.a"))
        assertFalse(File(tmp.root, "plugin-workspaces/pub.test.a").exists())
    }

    @Test
    fun `delete 不碰别的插件`() {
        val all = workspaces()
        all.open("pub.test.a", FilesystemScope.ReadWrite).write("a.txt", "a")
        all.open("pub.test.b", FilesystemScope.ReadWrite).write("b.txt", "b")

        all.delete("pub.test.a")
        assertEquals("b", all.open("pub.test.b", FilesystemScope.ReadWrite).read("b.txt"))
    }

    @Test
    fun `delete 一个不存在的插件算成功`() {
        assertTrue(workspaces().delete("pub.test.never"))
    }

    @Test
    fun `sweep 删掉没有插件记录的目录_留下有记录的`() {
        val all = workspaces()
        all.open("pub.test.kept", FilesystemScope.ReadWrite).write("k.txt", "k")
        all.open("pub.test.orphan", FilesystemScope.ReadWrite).write("o.txt", "o")

        assertEquals(1, all.sweep(known = setOf("pub.test.kept")))

        assertTrue(File(tmp.root, "plugin-workspaces/pub.test.kept").exists())
        assertFalse(File(tmp.root, "plugin-workspaces/pub.test.orphan").exists())
    }

    @Test
    fun `sweep 在没有 base 目录时也不炸`() {
        assertEquals(0, PluginWorkspaces(File(tmp.root, "从来没建过")).sweep(emptySet()))
    }

    // ── 报错话术 ──────────────────────────────────────────────────────────────

    @Test
    fun `读不到文件时告诉作者去看 list`() {
        // 插件作者拿到的是这句话本身（它会被包成 JS 的 Error），
        // 所以它必须包含「下一步做什么」，而不是只有「失败了」
        val e = runCatching { open().read("没有这个.txt") }.exceptionOrNull()!!
        assertTrue(e.message!!, e.message!!.contains("list"))
    }

    @Test
    fun `越界的报错说的是权限_不是设置`() {
        // 工作区边界是权限模型的硬边界，不是用户配置错了。说成「设置问题」
        // 会让作者去翻设置页，而不是改自己的代码。
        //
        // 注意别写成「不含『配置』二字」—— 那句话本身就写着「不是配置问题」，
        // 那样断言永远失败，而且失败信息看着像实现错了。
        // 要钉的是**它指向哪里**：说权限，不说设置页。
        val e = runCatching { open().read("../x") }.exceptionOrNull()!!
        assertTrue(e.message!!, e.message!!.contains("权限"))
        assertFalse(e.message!!, e.message!!.contains("设置"))
    }
}
