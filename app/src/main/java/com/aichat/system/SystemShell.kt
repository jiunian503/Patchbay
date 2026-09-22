package com.aichat.system

import com.aichat.domain.text.errorDetail
import com.aichat.settings.AppSettings
import com.aichat.tools.ShellOutcome
import com.aichat.tools.ShellSource
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「在这台设备上跑一条命令」的**唯一实现**。
 *
 * ## 两个使用者，一份实现
 *
 * - **终端页**（`TerminalViewModel`）：用户自己敲
 * - **`run_command` 工具**（`RunCommandTool`）：模型敲、用户点头
 *
 * 两者的差别只在「谁决定那条命令」，底层完全一样。所以起进程这段代码
 * 只有这一份 —— 下面那三个坑，每一个的失败模式都是「命令永久挂住」或者
 * 「超时形同虚设」，**写两遍的话，修了 A 忘了 B 不会有任何报错**，
 * 只会在另一条路径上偶发。完整推导见 SKILL.md §104。
 *
 * ## 它跑的是什么
 *
 * `ProcessBuilder("/system/bin/sh", "-c", 命令)` —— 系统自带的那个 shell。
 * 不需要打包任何东西，APK 体积零增长；子进程仍然是 App 的身份
 * （`u:r:untrusted_app:s0`，没有域转换 ⇒ 没有提权）。边界见
 * `TerminalViewModel` 的 KDoc 和 SKILL.md §100.8。
 *
 * ## 为什么 [enabled] 在这里
 *
 * 它是 [ShellSource] 要求的，而这个类正好是唯一能同时回答「开关怎么读」
 * （[AppSettings.shellEnabled]）和「命令怎么跑」的地方。拆成两个对象的话，
 * 会出现「开关读的是 A、执行走的是 B」的错配 —— 那种错配没有任何运行时症状：
 * 用户以为关掉了，模型照样能跑。
 */
class SystemShell(private val settings: AppSettings) : ShellSource {

    /** 同步读设置。装配路径上要问它，见 [ShellSource.enabled]。 */
    override fun enabled(): Boolean = settings.shellEnabled()

    override suspend fun run(command: String): ShellOutcome =
        withContext(Dispatchers.IO) { execute(command, onStart = {}) }

    /**
     * 和 [run] 是同一份实现，只是额外把刚起来的那个进程交给调用方。
     *
     * 终端页要它来做那个「停止」按钮（[TerminalViewModel.cancel] 杀的就是
     * 这个进程）。工具那条路不需要 —— 它没有停止按钮，靠超时兜底。
     */
    suspend fun run(command: String, onStart: (Process) -> Unit): ShellOutcome =
        withContext(Dispatchers.IO) { execute(command, onStart) }

    private fun execute(command: String, onStart: (Process) -> Unit): ShellOutcome =
        runCatching {
            val process =
                ProcessBuilder("/system/bin/sh", "-c", command)
                    // stderr 并进 stdout。对使用者来说「命令说了什么」是一件事，
                    // 分开只会让调用方多拼一次
                    .redirectErrorStream(true)
                    .start()
            onStart(process)

            // ⚠️ 坑一：必须关掉子进程的 stdin。
            // 不关的话 `cat`、`read` 这类等标准输入的命令会**永久挂住** ——
            // 而且超时也救不了它，因为它不是在算，是在等
            runCatching { process.outputStream.close() }

            val buffer = StringBuilder()
            var truncated = false

            // ⚠️ 坑二：输出必须**另起一条线程**读。
            // `readText()` / `forEachLine` 会一直阻塞到 EOF，放在主流程里的话，
            // 下面那句 `waitFor(超时)` 根本没机会执行 —— 命令一 hang 就永远
            // 读不完，超时形同虚设
            val reader =
                Thread {
                    runCatching {
                        process.inputStream.bufferedReader().forEachLine { line ->
                            synchronized(buffer) {
                                if (buffer.length < ShellSource.MAX_OUTPUT_CHARS) {
                                    buffer.appendLine(line)
                                } else {
                                    // 超了就丢，但**记下来** —— 悄悄吞掉后半截
                                    // 比报错更糟，调用方得能告诉用户/模型「截断了」
                                    truncated = true
                                }
                            }
                        }
                    }
                }
            reader.isDaemon = true
            reader.start()

            // ⚠️ 坑三：超时 + 强制杀。`top`、`sleep 1000` 不会自己停
            val finished = process.waitFor(ShellSource.TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                // 等它真的死掉，否则下面读 exitValue 会拿到一个中间态
                runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            }
            // 进程死了管道就关了，读线程拿到 EOF 会自己退出；这里只是等它收尾
            reader.join(1_000)

            ShellOutcome(
                output = synchronized(buffer) { buffer.toString() }.trimEnd('\n'),
                // 被杀了就没有退出码 —— 返回 null 而不是编一个 0，
                // 「跑完了什么都没输出」和「没跑完」是两件事（见 ShellOutcome 的 KDoc）
                exitCode = if (finished) process.exitValue() else null,
                timedOut = !finished,
                truncated = truncated,
            )
        }.getOrElse {
            // 起不来（比如系统里根本没有 /system/bin/sh —— 理论上不会）
            ShellOutcome(
                output = "没能启动 shell：${errorDetail(it)}",
                exitCode = null,
            )
        }
}
