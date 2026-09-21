package com.aichat.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.system.SystemShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 *   W^X 规则封死（§100.1）。
 *   ⚠️ **但这不是「做不到」** —— 二进制放进 `nativeLibraryDir` 就能 exec（§105.3），
 *   整套 proot + rootfs 的代价也量过：**67 MB 起 + 一个 JNI 模块 + 自己写的 VT
 *   模拟器**（§105.5）。所以这一页是**取舍**，不是平台禁令；别写成「装不进来」。
 *
 * ## 为什么不做成交互式终端
 *
 * 真正的交互式终端（`vi`、`top`、Ctrl+C 那套）要一个 PTY，而 PTY 要 `ioctl` ——
 * `android.system.Os` **一个 ioctl 都没有**（反射确认，§100.8 第五节），
 * 纯 JVM 做不出 `openpty`；`ProcessBuilder` 也只能给管道、不能把任意 fd 传给孩子。
 * 所以这里是**一条一条跑**的模式：输入一条、跑完、显示输出和退出码。
 * 想要交互式就得上 JNI，那是另一件事（Termux 走的就是 JNI）。
 *
 * ## 起进程那段代码不在这里
 *
 * 在 [SystemShell] —— 那三个坑（关掉子进程的 stdin、输出另起线程读、超时强杀）
 * 和 `run_command` 工具**共用同一份实现**。写两遍的话，修了 A 忘了 B 不会有
 * 任何报错，只会在另一条路径上偶发。
 *
 * 这个类只负责界面要的那部分：把每条命令和它的结果攒成一个列表、管住
 * 「一次只跑一条」、以及那个「停止」按钮。
 *
 * ## 这个页面**不看**「让 AI 跑命令」那个开关
 *
 * 那个开关（`AppSettings.shellEnabled`）管的是**模型**能不能跑命令。
 * 用户自己敲命令不需要谁的许可，所以这里不读它 —— 关了那个开关，
 * 这个页面照常用。
 */
class TerminalViewModel(private val shell: SystemShell) : ViewModel() {

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
            // 起进程、超时、截断都在 SystemShell 里；这里只是把刚起来的那个进程
            // 留下来，好让「停止」按钮有东西可杀
            val done = shell.run(command) { current = it }
            current = null
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
}
