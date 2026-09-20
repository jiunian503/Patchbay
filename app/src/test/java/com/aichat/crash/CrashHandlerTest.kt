package com.aichat.crash

import java.io.File
import java.lang.Thread.UncaughtExceptionHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 未捕获异常处理器的测试。
 *
 * ## 这里真正要钉住的是「交回系统」那一步
 *
 * 留痕谁都想得到，但**把异常交回上一个处理器**才是这个类最容易被改坏的地方：
 * 漏掉那一行的后果不是「记录丢了」，而是「崩溃之后进程还在跑」—— 界面卡死、
 * 协程半死不活、数据库连接半开，用户以为 App 活着，然后在完全无关的地方
 * 再崩一次，而那时拿到的堆栈已经和最初那次没关系了。这种 bug 在真机上
 * 表现为「偶尔会卡住」，极难追。
 */
class CrashHandlerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val meta = CrashContext("1.0 (1)", "Xiaomi 14", "Android 13（API 33）")

    private fun dir() = File(tmp.root, "crash")

    @Test
    fun `崩溃时写盘并把异常交回上一个处理器`() {
        var handedError: Throwable? = null
        var handedThread: Thread? = null
        val previous = UncaughtExceptionHandler { thread, error ->
            handedThread = thread
            handedError = error
        }

        val handler = crashHandler(CrashStore(dir()), { meta }, previous)
        val thread = Thread("崩溃线程")
        val error = RuntimeException("炸了")
        handler.uncaughtException(thread, error)

        val record = CrashStore(dir()).list().single()
        assertTrue(record.text.contains("RuntimeException: 炸了"))
        assertEquals("java.lang.RuntimeException: 炸了", record.headLine)
        // 交回系统是硬要求：不交回的话进程会带着半死的状态继续跑
        assertEquals(error, handedError)
        assertEquals(thread, handedThread)
    }

    @Test
    fun `没有上一个处理器时不会抛`() {
        val handler = crashHandler(CrashStore(dir()), { meta }, previous = null)

        handler.uncaughtException(Thread.currentThread(), RuntimeException("炸了"))

        // 记录照写 ——「没有系统处理器」只影响交回那一步，不影响留痕
        assertEquals(1, CrashStore(dir()).list().size)
    }

    @Test
    fun `取元信息抛异常也不会让处理器抛出去`() {
        val handler = crashHandler(
            CrashStore(dir()),
            { throw IllegalStateException("读不到版本号") },
            previous = null,
        )

        // 处理器自己抛出去等于把一次崩溃变成两次，而第二次发生在异常处理器里，
        // 连堆栈都拼不出来
        handler.uncaughtException(Thread.currentThread(), RuntimeException("真正的崩溃"))

        assertTrue(CrashStore(dir()).list().single().text.contains("真正的崩溃"))
    }

    @Test
    fun `install 会把自己装成默认处理器`() {
        val saved = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val handler = installCrashHandler(CrashStore(dir()), { meta })
            assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            // 还原：这个字段是全局的，污染了会影响同一次测试运行里的其他用例
            Thread.setDefaultUncaughtExceptionHandler(saved)
        }
    }

    @Test
    fun `install 会记住并沿用装之前的那个处理器`() {
        val saved = Thread.getDefaultUncaughtExceptionHandler()
        var handed = false
        val before = UncaughtExceptionHandler { _, _ -> handed = true }
        try {
            Thread.setDefaultUncaughtExceptionHandler(before)
            installCrashHandler(CrashStore(dir()), { meta })
                .uncaughtException(Thread.currentThread(), RuntimeException("炸了"))
            assertTrue("上一个处理器必须被交回，否则崩溃之后进程还在跑", handed)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(saved)
        }
    }
}
