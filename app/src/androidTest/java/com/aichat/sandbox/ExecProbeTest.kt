package com.aichat.sandbox

import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 「App 自己能不能跑外部程序」探针 —— **这不是测试，是测量**。
 *
 * ## 为什么必须让 App 自己跑
 *
 * `adb shell run-as` 给的是 `u:r:runas_app:s0`，**它不是 `untrusted_app`**。
 * 实测（2026-09-21，Redmi / Android 15）：`runas_app` 域 exec app 数据目录里的文件
 * 时，audit 打的是
 * `avc: granted { execute } ... tcontext=u:object_r:app_data_file:s0`
 * —— 是 **granted**，策略明确放行。
 *
 * 而 Android 10 那条 W^X 规则（「以 API 29 为目标平台的应用无法再直接针对应用主目录
 * 中的文件调用 `execve()`」）针对的是**按 targetSdk 分配的 `untrusted_app_29+` 域**。
 * 所以 `run-as` 测出来的任何结论都不能外推 —— 只有 App 自己的进程算数。
 *
 * ## 判据怎么读：靠**错误类型**，不靠成败
 *
 * | 看到什么 | 说明什么 |
 * |---|---|
 * | `Permission denied`（EACCES） | SELinux 拦了 —— 这条路不通 |
 * | `Exec format error`（ENOEXEC） | SELinux **放行**了，只是这个文件不是可执行程序 |
 *
 * 所以 C 组拿一个 `.so` 去 exec 是**故意的**：它本来就不是可执行程序，
 * 只要报的是「格式不对」而不是「没权限」，就证明**位置本身**是可执行的。
 *
 * ## 五组
 *
 * - **A 对照**：exec `/system/bin/toybox` —— 证明「在这个域里 exec」本身是通的。
 * - **B 实验**：把 toybox 拷进 `filesDir` 再 exec —— 这就是「解压一个 rootfs 然后跑」的机制。
 * - **C 缝隙**：exec `nativeLibraryDir` 里的 `.so` —— 那条已知的 jniLibs 缝隙。
 * - **D 缝隙**：`System.load()` 一个 `filesDir` 里的 `.so` —— dlopen 走的是
 *   只读 fd + `PROT_EXEC` 的 mmap；proot / qemu 那条路全靠它。
 * - **F 直测**：`Os.mmap(PROT_READ or PROT_EXEC)` 一个普通文件 —— 比 D 更直接，
 *   不掺「这个 .so 依赖全不全」的干扰。
 *
 * 输出走 logcat，tag `PB_PROBE`。
 *
 * ## 实测结论（2026-09-21，模拟器 x86_64 / Android 15 / **Permissive** / targetSdk 36）
 *
 * | 组 | 结果 | 怎么读 |
 * |---|---|---|
 * | A 对照 | `exit=0 out=hello_from_system` | 这个域里 exec 本身是通的 |
 * | B 实验 | `exit=0 out=hello_from_appdata` | **⚠️ 假阳性** —— 设备是 Permissive，别采信 |
 * | C 缝隙 | 跳过 —— `nativeLibraryDir` 里 **0 个文件**（`extractNativeLibs=false`） | 缝隙要另开那个开关 |
 * | F 直测 | `mmap(PROT_EXEC) 成功` | 同上，Permissive 下不算数 |
 * | G | `/data/local/tmp` 不可读、不可写、列不出来 | |
 *
 * **算数的是 avc 日志**（同一次 audit 的两条，**不矛盾**，查的是不同权限）：
 *
 * ```
 * avc:  granted { execute }          … scontext=u:r:untrusted_app:s0  tcontext=u:object_r:app_data_file:s0
 * avc:  denied  { execute_no_trans } … scontext=u:r:untrusted_app:s0  tcontext=u:object_r:app_data_file:s0  permissive=1
 * ```
 *
 * `execute`（mmap `PROT_EXEC` / `dlopen` 走它）**放行**；
 * `execute_no_trans`（`execve` 且不转换域）**拒绝** ⇒
 * **Enforcing 设备上 execve 数据目录的文件必然 `Permission denied`**。
 */
@RunWith(AndroidJUnit4::class)
class ExecProbeTest {

    private fun say(msg: String) {
        Log.i(TAG, msg)
        println("[PROBE] $msg")
    }

    private fun run(cmd: List<String>): String = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        "exit=${p.waitFor()} out=$out"
    }.getOrElse { "✗ ${it.javaClass.simpleName}: ${it.message}" }

    @Test
    @org.junit.Ignore(
        "**这不是测试，是测量** —— 它只往 logcat 打结果、不做断言，留在跑批里只是多占一条。" +
            "结论已经记在类注释和 SKILL.md §100 里。要重新测量时单独跑它，" +
            "跑之前先 `adb shell getenforce`：Permissive 的话 B/F 两组不算数，只有 avc 日志算数。",
    )
    fun probeExec() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val ai = ctx.applicationInfo

        say("═══ exec 探针 ═══")
        say("0. 进程 = ${processName()}   uid = ${Process.myUid()}")
        say("0. SELinux 域 = ${readText("/proc/self/attr/current")}")
        say("0. targetSdk = ${ai.targetSdkVersion}   minSdk = ${ai.minSdkVersion}")
        say("0. filesDir = ${ctx.filesDir}")
        say("0. nativeLibraryDir = ${ai.nativeLibraryDir}")

        // ── A 对照组：证明这个域里 exec 本身是通的 ──────────────────────────────
        say("A. [对照] exec /system/bin/toybox echo hello")
        say("A.   → ${run(listOf("/system/bin/toybox", "echo", "hello_from_system"))}")

        // ── B 实验组：拷进 filesDir 再 exec（= 解压 rootfs 的机制）─────────────
        val dst = File(ctx.filesDir, "echo")
        runCatching { File("/system/bin/toybox").copyTo(dst, overwrite = true) }
        dst.setExecutable(true, false)
        say("B. [实验] filesDir 里的可执行文件 ${dst.absolutePath}")
        say("B.   （${dst.length()} B, exists=${dst.exists()}, canExecute=${dst.canExecute()}）")
        say("B.   → ${run(listOf(dst.absolutePath, "hello_from_appdata"))}")

        // ── C 缝隙：nativeLibraryDir ─────────────────────────────────────────
        val libs = File(ai.nativeLibraryDir).listFiles()?.sortedBy { it.name } ?: emptyList()
        say("C. nativeLibraryDir 里 ${libs.size} 个：${libs.map { it.name }}")
        libs.firstOrNull { it.name.startsWith("libquickjs.so") }?.let {
            say("C. [缝隙] exec ${it.name}（它本来就不是可执行程序，只看错误类型）")
            say("C.   → ${run(listOf(it.absolutePath))}")
        }

        // ── D 缝隙：dlopen 一个 filesDir 里的 .so ─────────────────────────────
        libs.firstOrNull { it.name.startsWith("libquickjs.so") }?.let { src ->
            val so = File(ctx.filesDir, src.name)
            runCatching { src.copyTo(so, overwrite = true) }
            say("D. [缝隙] System.load(filesDir 里的 ${so.name})")
            val r = runCatching { System.load(so.absolutePath); "成功" }
                .getOrElse { "✗ ${it.javaClass.simpleName}: ${it.message}" }
            say("D.   → $r")
        }

        // ── F 直测：只读 fd + PROT_EXEC 的 mmap ───────────────────────────────
        say("F. [直测] Os.mmap(PROT_READ or PROT_EXEC) 一个普通文件")
        val m = runCatching {
            val fd = Os.open(dst.absolutePath, OsConstants.O_RDONLY, 0)
            val size = Os.fstat(fd).st_size
            val addr = Os.mmap(
                0L, size,
                OsConstants.PROT_READ or OsConstants.PROT_EXEC,
                OsConstants.MAP_PRIVATE, fd, 0L,
            )
            Os.munmap(addr, size)
            Os.close(fd)
            "mmap(PROT_EXEC) 成功，${size} B"
        }.getOrElse { "✗ ${it.javaClass.simpleName}: ${it.message}" }
        say("F.   → $m")

        // ── 顺带：/data/local/tmp 摸不摸得到 ─────────────────────────────────
        val tmp = File("/data/local/tmp")
        say("G. /data/local/tmp 可读=${tmp.canRead()} 可写=${tmp.canWrite()} 可列举=${tmp.list()?.size}")
    }

    private fun readText(path: String): String =
        runCatching { File(path).readText().trim() }.getOrElse { "✗ ${it.message}" }

    /** 进程名。不用 `ActivityThread.currentProcessName()` —— 那是隐藏 API。 */
    private fun processName(): String =
        runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }
            .getOrElse { "pid=${Process.myPid()}" }

    private companion object {
        const val TAG = "PB_PROBE"
    }
}
