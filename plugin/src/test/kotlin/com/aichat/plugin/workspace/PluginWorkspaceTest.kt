package com.aichat.plugin.workspace

import com.aichat.plugin.manifest.FilesystemScope
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertArrayEquals
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

    // ── 宿主侧入口（adminOf）：用户是主人 ─────────────────────────────────────

    @Test
    fun `adminOf 不受插件声明的读写方向约束`() {
        // 插件声明 read 是**插件对自己**的约束。用户往工作区里放文件时，
        // 插件依然只是读它 —— 拿插件的声明去限制用户会得出「只读插件的
        // 工作区不能导入文件」这种结论，而那显然是错的
        val ws = workspaces().adminOf("pub.test.readonly")
        ws.writeBytes("user.csv", byteArrayOf(1, 2, 3))
        assertEquals(3L, ws.usage().bytes)
    }

    @Test
    fun `adminOf 看一眼不会把目录建出来`() {
        // open() 建目录是为了插件作者那句 `if (!host.fs.exists("."))`；
        // 宿主只是查看的话不该凭空造一个目录 —— 它不在卸载清理的触发路径上
        // （插件记录还在，sweep 不会碰它），会一直留着
        val ws = workspaces().adminOf("pub.test.never")
        assertEquals(emptyList<WorkspaceFile>(), ws.allFiles())
        assertEquals(0, ws.usage().entries)
        assertFalse(File(tmp.root, "plugin-workspaces/pub.test.never").exists())
    }

    @Test
    fun `adminOf 也拦不合法的插件 id`() {
        // 校验贴着危险操作，不贴着调用者 —— 换一条路进来照样拦得住
        val e = runCatching { workspaces().adminOf("../evil") }.exceptionOrNull()!!
        assertTrue(e.message!!, e.message!!.contains("插件 id"))
    }

    // ── 按字节写 ──────────────────────────────────────────────────────────────

    @Test
    fun `writeBytes 原样存字节_不经过文本`() {
        // 这四个字节是 GBK 的「中文」。走 write(text) 的话它们会被当成
        // UTF-8 解码成替换字符、再编码回去 —— 写进工作区的就不是用户
        // 选中那份文件了，而且界面上什么都看不出来
        val gbk = byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte())
        val ws = open()
        ws.writeBytes("gbk.csv", gbk)

        val onDisk = File(tmp.root, "plugin-workspaces/pub.test.demo/gbk.csv").readBytes()
        assertArrayEquals(gbk, onDisk)
    }

    @Test
    fun `write 和 writeBytes 共用同一套限额`() {
        // 两条写入路径各写一遍限额的话，漏掉一条的后果是「有一条路不受
        // 上限约束」，而那种洞从界面上完全看不出来
        val e = runCatching {
            open().writeBytes("big.bin", ByteArray(PluginWorkspace.MAX_FILE_BYTES + 1))
        }.exceptionOrNull()!!
        assertTrue(e is WorkspaceException)
        assertTrue(e.message!!, e.message!!.contains("单文件上限"))
    }

    @Test
    fun `writeBytes 也受合计上限`() {
        val ws = open()
        val full = ByteArray(PluginWorkspace.MAX_FILE_BYTES)
        // 正好用满：8 MB / 1 MB = 8 个
        repeat(PluginWorkspace.MAX_TOTAL_BYTES / PluginWorkspace.MAX_FILE_BYTES) { i ->
            ws.writeBytes("f$i.bin", full)
        }
        val e = runCatching { ws.writeBytes("f9.bin", full) }.exceptionOrNull()!!
        assertTrue(e.message!!, e.message!!.contains("上限"))
    }

    // ── 文件清单与删除 ────────────────────────────────────────────────────────

    @Test
    fun `allFiles 递归列出来_路径一律用斜杠`() {
        val ws = open()
        ws.write("a.txt", "1")
        ws.write("sub/b.txt", "22")

        // 斜杠这条在 Windows 上才有意义：默认的 relativeTo().path 会给
        // "sub\b.txt"，而插件在 JS 里看到的是 "sub/b.txt" —— 用户照着
        // 界面填工具参数（csvstat 的 path）就会填错
        assertEquals(listOf("a.txt", "sub/b.txt"), ws.allFiles().map { it.path })
    }

    @Test
    fun `allFiles 带上每个文件的大小`() {
        val ws = open()
        ws.write("a.txt", "12345")
        assertEquals(5L, ws.allFiles().single().bytes)
    }

    @Test
    fun `allFiles 只给文件_不给目录`() {
        val ws = open()
        ws.write("sub/b.txt", "1")
        assertEquals(listOf("sub/b.txt"), ws.allFiles().map { it.path })
    }

    @Test
    fun `usage 只数文件_不数目录`() {
        val ws = open()
        ws.write("sub/b.txt", "123")
        ws.write("c.txt", "12")
        val usage = ws.usage()
        assertEquals(2, usage.entries)
        assertEquals(5L, usage.bytes)
    }

    @Test
    fun `remove 删掉一个文件`() {
        val ws = open()
        ws.write("a.txt", "1")
        assertTrue(ws.remove("a.txt"))
        assertFalse(ws.exists("a.txt"))
    }

    @Test
    fun `remove 一个本来就不存在的文件返回 false`() {
        // 「删掉了一个不存在的东西」和「删成功了」是两件事，
        // 界面要据此决定提示什么
        assertFalse(open().remove("没有这个.txt"))
    }

    @Test
    fun `remove 一个目录返回 false_不递归删`() {
        val ws = open()
        ws.write("sub/b.txt", "1")
        assertFalse(ws.remove("sub"))
        // 整棵子树必须还在 —— 静默递归删掉是个太重的后果
        assertTrue(ws.exists("sub/b.txt"))
    }

    @Test
    fun `remove 越界路径照样拒`() {
        val e = runCatching { open().remove("../x") }.exceptionOrNull()!!
        assertTrue(e is WorkspaceException)
    }

    // ── 洗名字：SAF 给什么就是什么，用户没法改名 ──────────────────────────────

    @Test
    fun `洗名字去掉路径前缀`() {
        // 文件名由文件提供方说了算，可能是 "Download/foo.csv"
        assertEquals("foo.csv", PluginWorkspace.cleanName("Download/foo.csv"))
        assertEquals("c.csv", PluginWorkspace.cleanName("a\\b\\c.csv"))
    }

    @Test
    fun `洗名字去掉控制字符和前后空白`() {
        assertEquals("ab.txt", PluginWorkspace.cleanName("a\u0000b.txt"))
        assertEquals("foo.csv", PluginWorkspace.cleanName("  foo.csv  "))
    }

    @Test
    fun `洗名字去掉前导的点`() {
        // `.foo` 在文件管理器里默认是隐藏文件，用户导入完会发现「什么都没看到」
        assertEquals("hidden.csv", PluginWorkspace.cleanName(".hidden.csv"))
        // 纯点的名字（`.` 和 `..`）洗完什么都不剩，退回兜底
        assertEquals("file", PluginWorkspace.cleanName("."))
        assertEquals("file", PluginWorkspace.cleanName(".."))
        assertEquals("file", PluginWorkspace.cleanName("..."))
    }

    @Test
    fun `洗名字对空串给兜底`() {
        assertEquals("file", PluginWorkspace.cleanName(""))
        assertEquals("file", PluginWorkspace.cleanName("   "))
    }

    @Test
    fun `洗名字超长时截断但留住扩展名`() {
        // 不截断的话 createNewFile 会抛 ENAMETOOLONG，那个报错里只有一个
        // errno，指不到「文件名太长」这件事上
        val cleaned = PluginWorkspace.cleanName("x".repeat(200) + ".csv")
        assertTrue(cleaned, cleaned.length <= 60)
        assertTrue(cleaned, cleaned.endsWith(".csv"))
    }

    @Test
    fun `洗名字不碰正常的中文名`() {
        assertEquals("月度报表.csv", PluginWorkspace.cleanName("月度报表.csv"))
    }

    @Test
    fun `洗过的名字一定能写进工作区`() {
        // 这条把「洗名字」和「路径校验」接起来：清洗规则改了、却没跟
        // resolve 的两道检查对齐的话，用户导入一个怪名字的文件就会失败
        val ws = open()
        val dirty = listOf(
            "Download/foo.csv", "a\\b\\c.csv", "..", ".", "", "a\u0000b", "  ",
            "x".repeat(200) + ".csv", "...", "  ..  ", ".hidden",
        )
        dirty.forEach { raw ->
            val name = PluginWorkspace.cleanName(raw)
            assertTrue("「$raw」洗成了空名字", name.isNotEmpty())
            assertFalse("「$raw」洗出来还带路径分隔符", name.contains('/') || name.contains('\\'))
            ws.writeBytes(name, byteArrayOf(1))
        }
    }
}
