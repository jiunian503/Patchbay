package com.aichat.crash

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 崩溃报告落盘与读回的测试。
 *
 * ## 为什么这些断言值得逐条写
 *
 * 这个类的所有代码路径**只在崩溃时才会跑到**，平时一次都不执行 —— 真机上
 * 想验证就得故意造崩溃，而且崩完进程就没了，没法在里面断点。换句话说，
 * 单测是唯一能低成本压到这些分支的地方。更要紧的是它的失败方式是**静默的**：
 * 磁盘写不进去、格式拼错了、截断切坏了字符，都不会报错，只会让那份报告在
 * 最需要它的那一刻没法用。
 */
class CrashStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val meta = CrashContext(
        appVersion = "1.0 (1)",
        deviceModel = "Xiaomi 14",
        osVersion = "Android 13（API 33）",
    )

    private fun store(
        dir: File = File(tmp.root, "crash"),
        maxFiles: Int = CrashStore.MAX_FILES,
        maxBytes: Int = CrashStore.MAX_BYTES,
    ) = CrashStore(dir, maxFiles, maxBytes)

    private fun crashThread() = Thread("崩溃线程")

    // ------------------------------------------------------------ 落盘

    @Test
    fun `记录会落成一个以时间戳命名的文件`() {
        val file = store().record({ meta }, crashThread(), RuntimeException("炸了"), at = 1_700_000_000_000L)

        assertNotNull(file)
        assertEquals("crash-1700000000000.txt", file!!.name)
        assertTrue(file.readText().contains("RuntimeException: 炸了"))
    }

    @Test
    fun `报告含元信息与完整堆栈`() {
        val error = IllegalStateException("外层", RuntimeException("根因"))
        val file = store().record({ meta }, crashThread(), error, at = 1_700_000_000_000L)!!

        val text = file.readText()
        assertTrue(text.contains("版本：1.0 (1)"))
        assertTrue(text.contains("系统：Android 13（API 33）"))
        assertTrue(text.contains("机型：Xiaomi 14"))
        assertTrue(text.contains("线程：崩溃线程 (id="))
        assertTrue(text.contains("java.lang.IllegalStateException: 外层"))
        // Caused by 链必须一起写出来 —— 真正有用的往往是最里层那个
        assertTrue(text.contains("Caused by: java.lang.RuntimeException: 根因"))
    }

    @Test
    fun `时间带时区偏移`() {
        val file = store().record({ meta }, crashThread(), RuntimeException("x"), at = 1_700_000_000_000L)!!
        // 不断死具体时刻：本机在哪个时区都该成立，断的是「绝对时间 + 偏移」这个形状
        assertTrue(
            Regex("""时间：\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} [+-]\d{4}""")
                .containsMatchIn(file.readText()),
        )
    }

    @Test
    fun `元信息里的换行会被压平`() {
        // 某些 ROM 的 Build.MODEL 不干净。压不平的话「空行之后是异常」这条
        // 格式就被破坏了，列表上显示的那一行会变成半句元信息
        val weird = CrashContext(appVersion = "1.0\n(1)", deviceModel = "Xiao\nmi", osVersion = null)
        val file = store().record({ weird }, crashThread(), RuntimeException("x"), at = 1L)!!

        val text = file.readText()
        assertTrue(text.contains("版本：1.0 (1)"))
        assertTrue(text.contains("机型：Xiao mi"))
        // 没给的那项写「未知」，而不是留空 —— 留空会被读成「格式坏了」
        assertTrue(text.contains("系统：未知"))
    }

    @Test
    fun `取元信息自己炸了也不影响落盘`() {
        val file = store().record(
            { throw IllegalStateException("PackageManager 不给") },
            crashThread(),
            RuntimeException("真正的崩溃"),
            at = 1L,
        )

        assertNotNull(file)
        val text = file!!.readText()
        assertTrue(text.contains("版本：未知"))
        // 真正的堆栈必须在。元信息取不到只是缺一块，不是丢掉整份报告
        assertTrue(text.contains("RuntimeException: 真正的崩溃"))
    }

    @Test
    fun `目录不存在时会建出来`() {
        val dir = File(tmp.root, "deep/er/crash")
        assertFalse(dir.exists())

        val file = store(dir).record({ meta }, crashThread(), RuntimeException("x"), at = 1L)

        assertNotNull(file)
        assertTrue(file!!.exists())
    }

    @Test
    fun `目标位置建不出目录时返回 null 而不是抛`() {
        // 让 dir 指向一个**文件**：mkdirs() 必然失败。这是跨平台造出
        // 「目录不可用」最可靠的办法 —— Windows 上把目录设成只读并不能
        // 阻止在里面建文件，用 setWritable 造不出来
        val notADir = File(tmp.root, "not-a-dir").apply { writeText("我是个文件") }

        assertNull(store(notADir).record({ meta }, crashThread(), RuntimeException("x"), at = 1L))
    }

    // ------------------------------------------------------------ 上限

    @Test
    fun `超过上限时最旧的被删掉`() {
        val s = store(maxFiles = 2)
        val thread = crashThread()
        repeat(3) { i -> s.record({ meta }, thread, RuntimeException("第 $i 次"), at = 1000L + i) }

        val kept = s.list()
        assertEquals(2, kept.size)
        assertEquals(listOf(1002L, 1001L), kept.map { it.epochMillis })
    }

    @Test
    fun `同一毫秒连崩两次不会互相覆盖`() {
        val s = store()
        val thread = crashThread()
        val first = s.record({ meta }, thread, RuntimeException("第一次"), at = 500L)!!
        val second = s.record({ meta }, thread, RuntimeException("第二次"), at = 500L)!!

        assertFalse(first.name == second.name)
        // 两份都要在：崩溃循环里第一份往往才是根因，而后续几份长得都差不多，
        // 覆盖掉第一份等于把唯一的线索删了
        val texts = s.list().map { it.text }
        assertEquals(2, texts.size)
        assertTrue(texts.any { it.contains("第一次") })
        assertTrue(texts.any { it.contains("第二次") })
    }

    @Test
    fun `单份超过上限时截断并留标记`() {
        val s = store(maxBytes = 200)
        val file = s.record({ meta }, crashThread(), RuntimeException("长".repeat(500)), at = 1L)!!

        val text = file.readText()
        assertTrue(text.contains("已截断"))
        // 上限是**字节**数：一个汉字 3 字节，按字符算的话这里会超三倍
        assertTrue("实际 ${file.length()} 字节", file.length() < 400)
        // 截断不能把多字节字符切成两半 —— 末尾出现替换符就是切坏了
        assertFalse(text.contains('\uFFFD'))
    }

    // ------------------------------------------------------------ 列表

    @Test
    fun `列表按时间倒序并忽略不相干的文件`() {
        val dir = File(tmp.root, "crash").apply { mkdirs() }
        File(dir, "crash-200.txt").writeText("时间：x\n\njava.lang.RuntimeException: 第二新")
        File(dir, "crash-100.txt").writeText("时间：x\n\njava.lang.RuntimeException: 最旧")
        File(dir, "notes.txt").writeText("不是我们的")
        File(dir, "crash-abc.txt").writeText("时间戳不是数字")
        File(dir, "crash-300").writeText("没有扩展名")
        // 同名目录：isFile 为假，必须一起跳过，否则 readText 会抛
        File(dir, "crash-400.txt").mkdirs()

        val list = store(dir).list()
        assertEquals(listOf(200L, 100L), list.map { it.epochMillis })
        assertEquals("java.lang.RuntimeException: 第二新", list[0].headLine)
    }

    @Test
    fun `折叠那一行取空行之后的第一个非空行`() {
        val dir = File(tmp.root, "crash").apply { mkdirs() }
        File(dir, "crash-1.txt").writeText(
            "时间：2026-09-20 14:35:02 +0800\n" +
                "版本：1.0 (1)\n" +
                "\n" +
                "java.lang.NullPointerException: 试试\n" +
                "\n" +
                "\tat com.aichat.Foo.bar(Foo.kt:42)\n",
        )

        assertEquals("java.lang.NullPointerException: 试试", store(dir).list()[0].headLine)
    }

    @Test
    fun `折叠那一行过长时会掐断`() {
        val dir = File(tmp.root, "crash").apply { mkdirs() }
        File(dir, "crash-1.txt").writeText(
            "时间：x\n\njava.lang.RuntimeException: " + "很长的消息".repeat(100),
        )

        val head = store(dir).list()[0].headLine
        assertTrue(head.endsWith("…"))
        assertTrue("实际 ${head.length} 字符", head.length < 200)
    }

    @Test
    fun `没有空行的文件也能取到头部`() {
        // 格式坏了（比如被手工改过）也不能让列表整页打不开
        val dir = File(tmp.root, "crash").apply { mkdirs() }
        File(dir, "crash-1.txt").writeText("java.lang.RuntimeException: 只有一行")

        assertEquals("java.lang.RuntimeException: 只有一行", store(dir).list()[0].headLine)
    }

    @Test
    fun `长到不可能是我们写的文件会被跳过`() {
        val dir = File(tmp.root, "crash").apply { mkdirs() }
        File(dir, "crash-1.txt").writeText("x".repeat(CrashStore.MAX_BYTES + 2048))

        // 「崩溃记录页自己因为读崩溃记录而 OOM」是最没必要的一种故障
        assertEquals(0, store(dir).list().size)
    }

    @Test
    fun `没能显示出来的文件会被数出来`() {
        // 这一条守的是**空状态的措辞**：列表为空时，「它还没崩过」和
        // 「有东西但读不出来」是两句不同的话，而界面只有靠这个数才分得开。
        val dir = File(tmp.root, "crash").apply { mkdirs() }
        File(dir, "crash-1.txt").writeText("时间：x\n\njava.lang.RuntimeException: 能读")
        File(dir, "notes.txt").writeText("不是我们的")
        File(dir, "crash-abc.txt").writeText("时间戳不是数字")
        File(dir, "crash-2.txt").writeText("x".repeat(CrashStore.MAX_BYTES + 2048))
        // 同名目录：既不算「能显示」，也不算「没能显示」—— 它压根不是文件
        File(dir, "crash-400.txt").mkdirs()

        val store = store(dir)
        assertEquals(1, store.list().size)
        assertEquals(3, store.skippedCount())
    }

    @Test
    fun `目录里一份文件都没有时不算跳过`() {
        // 这一半才是「它还没崩过」—— 上面那条和这条必须给出不同的数
        val dir = File(tmp.root, "crash").apply { mkdirs() }

        assertTrue(store(dir).list().isEmpty())
        assertEquals(0, store(dir).skippedCount())
    }

    // ------------------------------------------------------------ 清除

    @Test
    fun `清除删掉目录里的文件并返回份数`() {
        val dir = File(tmp.root, "crash").apply { mkdirs() }
        File(dir, "crash-1.txt").writeText("a")
        File(dir, "crash-2.txt").writeText("b")
        File(dir, "notes.txt").writeText("认不出的也要删 —— 留着会让用户以为按钮没生效")
        File(dir, "sub").mkdirs()

        val s = store(dir)
        assertEquals(3, s.clear())
        assertEquals(0, s.list().size)
        // 子目录与目录本身都留着：前者不像我们的东西，后者下次崩溃还要用
        assertTrue(File(dir, "sub").exists())
        assertTrue(dir.exists())
    }

    @Test
    fun `目录还不存在时清除是空操作`() {
        assertEquals(0, store(File(tmp.root, "never-created")).clear())
    }
}
