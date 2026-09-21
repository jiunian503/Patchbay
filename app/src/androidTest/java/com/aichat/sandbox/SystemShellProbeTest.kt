package com.aichat.sandbox

import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 「App 能不能跑**系统自带**的 shell 和命令」探针 —— **这不是测试，是测量**。
 *
 * 与 `ExecProbeTest` 的区别：那个测的是「exec **应用主目录**里的文件」（= 自带 rootfs 的机制，
 * Android 10 起被 W^X 规则封死）；这个测的是「exec **`/system/bin`** 里的文件」，
 * 而 Android 10 那条规则只针对应用主目录 —— **`/system` 不受它约束**。
 *
 * ## 为什么这条值得单独测
 *
 * `/system/bin` 里有 **425 个**条目，其中 `sh`（真实文件，322 KB，标签 `shell_exec`）、
 * `toybox`（真实文件，标签 `toolbox_exec`）和几百个指向 toybox 的软链
 * （`ls` `grep` `sed` `find` `ps` `top` `awk` `tar` `gzip` `vi` …）。
 * 如果 `untrusted_app` 能 exec 它们，那「让 AI 跑命令」**一个字节都不用自带**。
 *
 * ## 判据：只看 avc，不看成败
 *
 * 这台设备是 **Permissive**，所以「跑通了」**什么都证明不了**。真正的判据是
 * avc 日志里**有没有**这两条：
 *
 * ```
 * avc: denied { execute_no_trans } … tcontext=u:object_r:shell_exec:s0
 * avc: denied { execute_no_trans } … tcontext=u:object_r:toolbox_exec:s0
 * ```
 *
 * - **没有 denied** ⇒ 策略真的允许 ⇒ Enforcing 设备上也能跑
 * - **有 denied 但跑通了** ⇒ Permissive 的假象 ⇒ Enforcing 上必然 `Permission denied`
 *
 * 所以跑完必须 `adb logcat -d | grep avc` 对一遍。
 *
 * ## 实测结论（2026-09-21，模拟器 x86_64 / Android 15 / **Permissive** / targetSdk 36）
 *
 * **avc 里一条 exec 相关的 denied 都没有** ⇒ 下面这些不是 Permissive 假象。
 * （只有 4 条无关的：`/apex/apex-info-list.xml` 与 `/mnt/vm` 的 `getattr`、
 * `/dev/pts` 的 `open`/`read`。）
 *
 * 旁证：AOSP `system/sepolicy/private/app.te` 里
 * `allow { appdomain -ephemeral_app -sdk_sandbox_all } shell_exec:file rx_file_perms;`
 * （`toolbox_exec` 同款），而 `x_file_perms = { getattr execute execute_no_trans map }`
 * —— **`execute_no_trans` 在里面**。
 *
 * - **`sh` + toybox 全放行**：`echo` `id` `ps` `df` `uname` `cat` `for` `重定向` `ls /sdcard`
 *   全成功；子进程域仍是 `u:r:untrusted_app:s0`（**没有域转换 = 没有提权**）。
 * - **`ls /system/bin` 被拒** —— 但那是 **DAC**：`/system/bin` 是 `drwxr-x--x`（751），
 *   只有 `search` 没有 `read`。**能 exec 已知路径、不能列目录**。Permissive 也拦。
 * - **约 40 个工具逐个 exec 全部成功**（toybox 全家 + `sqlite3` `getprop` `logcat` `dumpsys`
 *   `pm` `am` `wm` `settings` `input` `ip` `ss` `ping`）。
 * - **但「能 exec」≠「能干活」** —— `--help` 只证明进程起得来。真实操作实测：
 *   `getprop` `ps` `df` `ip` `ifconfig` `netstat` `ss` `date` `cat /proc/meminfo`
 *   `pm list packages` **真读到了数据**；而 `dumpsys battery`（`Permission Denial`）、
 *   `settings get`（`SecurityException`）**被 Android 权限层拒**（不是 SELinux）。
 * - **PTY**：`/dev/ptmx` 能 `Os.open` 且 `isatty=true`，`/dev/pts` 列得出。
 *   **但 `android.system.Os` 没有 `ioctl`**（反射确认；tty 家族只有 `isatty` `setsid`）
 *   ⇒ 拿不到从端编号（`TIOCGPTN`）⇒ **纯 JVM 做不了 openpty，必须 JNI**。
 *   另外 `/dev/pts` 目录的 `open`/`read` 在 avc 里是 **denied** ⇒ Enforcing 设备上
 *   访问从端还有风险，要单独验。
 */
@org.junit.Ignore(
    "**这不是测试，是测量** —— 它只往 logcat 打结果、不做断言。" +
        "结论已经记在类注释和 SKILL.md §100.8 里。要重新测量时单独跑它，" +
        "跑之前先 `adb shell getenforce`，跑完 `adb logcat -d | grep avc` 对一遍。",
)
@RunWith(AndroidJUnit4::class)
class SystemShellProbeTest {

    private fun say(msg: String) {
        Log.i(TAG, msg)
        println("[PROBE] $msg")
    }

    /** 一条命令 = 一次 fork+exec。`exit=` 是 sh 的退出码。 */
    private fun sh(script: String): String = runCatching {
        val p = ProcessBuilder("/system/bin/sh", "-c", script)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        val code = p.waitFor()
        "exit=$code out=${out.replace("\n", " ⏎ ")}"
    }.getOrElse { "✗ ${it.javaClass.simpleName}: ${it.message}" }

    @Test
    fun probeSystemShell() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        say("═══ 系统 shell 探针 ═══")
        say("0. 域=${readText("/proc/self/attr/current")}  uid=${Process.myUid()}  " +
            "targetSdk=${ctx.applicationInfo.targetSdkVersion}")

        // ── 最基本：能不能 exec /system/bin/sh ───────────────────────────────
        say("1.  echo        → ${sh("echo hello_from_system_sh")}")
        say("2.  id          → ${sh("id")}")
        say("3.  域（子进程） → ${sh("cat /proc/self/attr/current")}")

        // ── 管道 + 外部命令：sh 还要能 exec toybox ───────────────────────────
        say("4.  ls|wc       → ${sh("ls /system/bin | wc -l")}")
        say("5.  grep        → ${sh("ls /system/bin | grep -c '^s'")}")
        say("6.  ps|wc       → ${sh("ps -A 2>/dev/null | wc -l")}")

        // ── 是不是真 shell：变量、循环、重定向 ───────────────────────────────
        say("7.  \$PATH       → ${sh("echo \$PATH")}")
        say("8.  for 循环    → ${sh("for i in 1 2 3; do echo n=\$i; done")}")
        say("9.  重定向      → ${sh("echo written > ${ctx.filesDir}/probe.txt; cat ${ctx.filesDir}/probe.txt")}")

        // ── 环境与边界 ──────────────────────────────────────────────────────
        say("10. uname -a    → ${sh("uname -a")}")
        say("11. df /data    → ${sh("df -h /data 2>&1 | tail -2")}")
        say("12. HOME/TMPDIR → ${sh("echo HOME=\$HOME TMPDIR=\$TMPDIR")}")
        say("13. 读 /sdcard  → ${sh("ls /sdcard 2>&1 | head -3")}")
        say("14. 写 /sdcard  → ${sh("touch /sdcard/pb_probe.txt 2>&1 && echo OK || echo FAIL")}")
        say("15. 读 /proc    → ${sh("cat /proc/version")}")
        say("16. 能看到的域  → ${sh("ls -Z /system/bin/sh /system/bin/toybox")}")
    }

    @Test
    fun probePty() {
        say("═══ PTY 探针 ═══")
        say("P1. /dev/ptmx exists=${File("/dev/ptmx").exists()} " +
            "canRead=${File("/dev/ptmx").canRead()} canWrite=${File("/dev/ptmx").canWrite()}")
        say("P1. /dev/pts 可列举=" +
            runCatching { File("/dev/pts").list()?.joinToString() }.getOrElse { "✗ ${it.message}" })

        // P2：主端能不能打开。这是 PTY 的**前提**。
        val r = runCatching {
            val fd = android.system.Os.open(
                "/dev/ptmx",
                android.system.OsConstants.O_RDWR or android.system.OsConstants.O_NOCTTY,
                0,
            )
            val isTty = android.system.Os.isatty(fd)
            android.system.Os.close(fd)
            "open 成功 isatty=$isTty"
        }.getOrElse { "✗ ${it.javaClass.simpleName}: ${it.message}" }
        say("P2. Os.open(/dev/ptmx) → $r")

        // P3：`android.system.Os` 到底有没有 ioctl —— 决定 openpty 能不能**纯 JVM** 做。
        // （TIOCGPTN 要一个 int* 输出参数；Os 里没有 ioctl 的话就必须 JNI。）
        val ioctlMethods = android.system.Os::class.java.methods
            .filter { it.name.startsWith("ioctl") }
            .map { m -> "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" }
        say("P3. Os 的 ioctl 家族 = ${if (ioctlMethods.isEmpty()) "（一个都没有）" else ioctlMethods}")

        val ttyMethods = android.system.Os::class.java.methods
            .filter { it.name.contains("tty") || it.name == "setsid" }
            .map { it.name }.distinct()
        say("P3. Os 的 tty/setsid 家族 = $ttyMethods")
    }

    /** 逐个 exec 系统工具，看哪些**真的能跑** —— 标签不同的工具不一定放行。 */
    @Test
    fun probeToolExec() {
        say("═══ 工具 exec 探针 ═══")
        val tools = listOf(
            "toybox", "ls", "grep", "sed", "awk", "find", "ps", "df", "du", "tar",
            "gzip", "sqlite3", "md5sum", "sha256sum", "base64", "xxd", "tty", "stty",
            "timeout", "nohup", "setsid", "uname", "id", "whoami", "env", "tr", "cut",
            "getprop", "dumpsys", "settings", "pm", "am", "wm", "input", "logcat",
            "ip", "ss", "ifconfig", "netstat", "ping",
        )
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        tools.forEach { t ->
            val r = runCatching {
                val f = File.createTempFile("probe", ".txt", cacheDir)
                val p = ProcessBuilder("/system/bin/$t", "--help")
                    .redirectErrorStream(true)
                    .redirectOutput(f)
                    .start()
                val done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                if (!done) {
                    p.destroyForcibly()
                    "超时（可能是交互式命令）"
                } else {
                    val first = f.readText().lineSequence().firstOrNull { it.isNotBlank() } ?: ""
                    "exit=${p.exitValue()} ${first.take(70)}"
                }
            }.getOrElse { "✗ ${it.javaClass.simpleName}: ${it.message}" }
            say("T. $t → $r")
        }
    }

    /**
     * 真实操作探针。
     *
     * `probeToolExec` 用 `--help` 测，只能证明**进程起得来**（很多工具打印 usage 就退出，
     * 根本没碰它要操作的东西）。这个测的是**真的去读**。
     */
    @Test
    fun probeRealWork() {
        say("═══ 真实操作探针 ═══")
        val cases = listOf(
            "getprop ro.build.version.release" to "属性读取",
            "ps -A | wc -l" to "进程数",
            "df -h /data | tail -1" to "磁盘",
            "ip addr show 2>&1 | head -3" to "网络接口",
            "ifconfig 2>&1 | head -3" to "网络接口(旧)",
            "netstat -an 2>&1 | head -3" to "网络连接",
            "ss -tln 2>&1 | head -3" to "监听端口",
            "logcat -d -t 2 2>&1 | head -2" to "读日志",
            "dumpsys battery 2>&1 | head -3" to "电池",
            "pm list packages 2>&1 | head -2" to "包列表",
            "settings get system screen_brightness 2>&1" to "系统设置",
            "date" to "日期",
            "cat /proc/meminfo 2>&1 | head -2" to "内存",
            "ls -l /data/data/io.github.jiunian503.patchbay/files 2>&1 | head -2" to "自己的目录",
            "echo hi > /data/data/io.github.jiunian503.patchbay/files/w.txt && cat /data/data/io.github.jiunian503.patchbay/files/w.txt" to "写自己的目录",
        )
        cases.forEach { (cmd, label) ->
            val r = runCatching {
                val p = ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                val done = p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)
                if (!done) {
                    p.destroyForcibly()
                    "超时"
                } else {
                    "exit=${p.exitValue()} | ${out.replace("\n", " / ").take(90)}"
                }
            }.getOrElse { "✗ ${it.javaClass.simpleName}" }
            say("W. $label → $r")
        }
    }

    private fun readText(path: String): String =
        runCatching { File(path).readText().trim() }.getOrElse { "✗ ${it.message}" }

    private companion object {
        const val TAG = "PB_PROBE"
    }
}
