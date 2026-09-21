package com.aichat.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 内置终端：**用户自己敲命令的地方**。
 *
 * ## 它跑的是什么
 *
 * `ProcessBuilder("/system/bin/sh", "-c", 用户输入)` —— 就是**系统自带的那个 shell**。
 * 这件事本身是可行的，判据（策略源码 + 实测 + avc 三条）见 SKILL.md **§100.8**。
 *
 * - **不需要打包任何东西**，APK 体积**零增长** —— 命令全是系统自带的
 *   （`/system/bin` 里 189 个软链指向 `toybox`，加上 `sh` 自己）。
 * - **子进程仍然是 App 的身份**（`u:r:untrusted_app:s0`，没有域转换 ⇒ 没有提权）。
 *   它能碰到的和这个 App 一样多：读自己的私有目录、读 `/proc`、列 `/sdcard` 都行；
 *   但 `dumpsys`、`settings` 这类要更高权限的会被 **Android 权限层**挡回来
 *   （是 `Permission Denial` / `SecurityException`，**不是 SELinux**）。
 * - **不能 `apt install`**：那条路要 exec 应用自己目录里的二进制，被 Android 10 起的
 *   W^X 规则封死（§100.1）。这不是「还没做」，是**做不到**。
 *
 * ## 为什么不做成交互式终端
 *
 * 真正的交互式终端（`vi`、`top`、Ctrl+C 那套）要一个 PTY，而 PTY 要 `ioctl` ——
 * `android.system.Os` **一个 ioctl 都没有**（反射确认，§100.8 第五节），
 * 纯 JVM 做不出 `openpty`；`ProcessBuilder` 也只能给管道、不能把任意 fd 传给孩子。
 * 所以这里是**一条一条跑**的模式：输入一条、跑完、显示输出和退出码。
 * 想要交互式就得上 JNI，那是另一件事（Termux 走的就是 JNI）。
 *
 * ## 两个必须做的防护
 *
 * 1. **关掉子进程的 stdin**（`outputStream.close()`）。不关的话 `cat`、`read`
 *    这类等 stdin 的命令会**永久挂住** —— 而且超时也救不了，因为它不是在算，
 *    是在等。
 * 2. **超时 + 强制杀**（[TIMEOUT_SECONDS]）。`top`、`sleep 1000` 不会自己停。
 *
 * ## 输出上限
 *
 * [MAX_OUTPUT_CHARS] 是硬上限：`yes` 或者 `find /` 能吐几百 MB，不设上限就是 OOM。
 * 超了就截断，并在界面上说明截断了 —— 悄悄吞掉后半截比报错更糟。
 */
class TerminalViewModel : ViewModel() {

    /** 一条「命令 + 它的结果」。 */
    data class Entry(
        val id: Long,
        val command: String,
        val output: String,
        /** `null` = 还在跑（或者被杀了、拿不到退出码）。 */
        val exitCode: Int?,
        val timedOut: Boolean,
        val truncated: Boolean,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var nextId = 0L

    /** 当前在跑的进程。[cancel] 要杀的就是它。跨线程读写，所以是 `@Volatile`。 */
    @Volatile
    private var current: Process? = null

    fun run(raw: String) {
        val command = raw.trim()
        // 一次只跑一条：两条并发会让输出交错，而且「停止」该停哪条也说不清
        if (command.isEmpty() || _running.value) return

        val id = nextId++
        _entries.value = _entries.value + Entry(id, command, "", null, false, false)
        _running.value = true

        viewModelScope.launch {
            val done = withContext(Dispatchers.IO) { execute(command) }
            _entries.value =
                _entries.value.map { entry ->
                    if (entry.id != id) {
                        entry
                    } else {
                        entry.copy(
                            output = done.output,
                            exitCode = done.exitCode,
                            timedOut = done.timedOut,
                            truncated = done.truncated,
                        )
                    }
                }
            _running.value = false
        }
    }

    fun cancel() {
        current?.let { runCatching { it.destroyForcibly() } }
    }

    fun clear() {
        if (_running.value) return
        _entries.value = emptyList()
    }

    /** 页面出栈时别留一个跑到一半的进程。 */
    override fun onCleared() {
        cancel()
    }

    private data class Done(
        val output: String,
        val exitCode: Int?,
        val timedOut: Boolean,
        val truncated: Boolean,
    )

    private fun execute(command: String): Done =
        runCatching {
            val process =
                ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
            current = process

            // ⚠️ 必须关：见类 KDoc 第 1 条防护
            runCatching { process.outputStream.close() }

            val buffer = StringBuilder()
            var truncated = false

            // 输出必须**另起一条线程**读：`readText()` 会阻塞到 EOF，
            // 放在主流程里的话，下面那句 `waitFor(超时)` 根本没机会执行 ——
            // 命令一 hang 就永远读不完，超时形同虚设
            val reader =
                Thread {
                    runCatching {
                        process.inputStream.bufferedReader().forEachLine { line ->
                            synchronized(buffer) {
                                if (buffer.length < MAX_OUTPUT_CHARS) {
                                    buffer.appendLine(line)
                                } else {
                                    truncated = true
                                }
                            }
                        }
                    }
                }
            reader.isDaemon = true
            reader.start()

            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            }
            // 进程死了管道就关了，读线程拿到 EOF 会自己退出；这里只是等它收尾
            reader.join(1_000)

            val text = synchronized(buffer) { buffer.toString() }
            Done(
                output = text.trimEnd('\n'),
                exitCode = if (finished) process.exitValue() else null,
                timedOut = !finished,
                truncated = truncated,
            )
        }.getOrElse {
            // 起不来（比如系统里根本没有 /system/bin/sh —— 理论上不会）
            Done(
                output = "${it.javaClass.simpleName}: ${it.message}",
                exitCode = null,
                timedOut = false,
                truncated = false,
            )
        }.also { current = null }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val MAX_OUTPUT_CHARS = 32_768
    }
}
