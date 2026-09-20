package com.aichat.crash

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次崩溃发生时能拿到的「环境」信息。
 *
 * ## 为什么每个字段都可空
 *
 * 它们全部来自 `Build` 和 `PackageManager`，而这两处在不同 ROM 上都有
 * 拿不到的时候（理由见 `AndroidDeviceInfo` 的 KDoc）。宁可让报告里写
 * 「未知」，也不要编一个值 —— 一份写着错误机型或版本号的崩溃报告比没有
 * 报告更坏：它会让人照着错误的前提去查。
 *
 * ## 为什么不是一个 Context
 *
 * 因为这个包要能跑 JVM 单测。崩溃报告里最容易写错、又最难在真机上发现的
 * 部分是**格式**（空行在哪、元信息怎么压成一行、截断标记长什么样），
 * 把 Android 那一侧摘出去，格式才测得动。
 */
data class CrashContext(
    val appVersion: String?,
    val deviceModel: String?,
    val osVersion: String?,
)

/**
 * 一条已经落盘的崩溃记录。
 *
 * [text] 是**完整报告原文**，与文件里的字节一模一样；[headLine] 是列表上
 * 折叠时显示的那一行（异常类 + 消息）。界面要的就是这两样：折叠看头部、
 * 展开看全文，所以在这里一次给全，省得界面自己去切字符串。
 */
data class CrashRecord(
    val epochMillis: Long,
    val headLine: String,
    val text: String,
)

/**
 * 崩溃记录的落盘仓库。
 *
 * ## 为什么需要它
 *
 * 在它之前，全仓库只有**一处** `Log.*`，也没有装
 * `setDefaultUncaughtExceptionHandler` —— 也就是说，无论 R8 的 `mapping.txt`
 * 把堆栈还原得多好（见 §73），**一条真实崩溃的堆栈都拿不到**。用户说
 * 「它闪退了」，我们手里是空的。
 *
 * ## 三个刻意的取舍
 *
 * 1. **不联网。** 报告只写在本机 `filesDir/crash/` 下。这个 App 的立场是
 *    「数据不出设备」（BYOK、密钥在 KeyStore 里），把堆栈悄悄发给第三方
 *    上报服务是同一件事的反面。要看，用户自己复制出去。
 * 2. **不记任何应用状态。** 报告里只有时间、版本、系统、机型、线程和堆栈。
 *    没有会话内容、没有 API Key、没有 baseUrl、没有任何设备标识符 ——
 *    堆栈里可能出现的 URL 之类是异常自己带出来的，不是我们挑的。
 * 3. **写失败就是写失败。** 目录建不出来、磁盘满、文件被占，一律返回 null，
 *    **绝不往外抛**。这个类是在「进程已经要死了」的路径上被调用的，它再抛
 *    一次只会让事情更糟，而且那个二次异常没人接得住。**这是硬契约**，
 *    `crashHandler` 就是靠它才敢不包 try。
 *
 * ## 为什么是「最多 5 份」而不是无上限
 *
 * 崩溃会成循环：启动即崩、崩了被系统重启、又崩。无上限的话磁盘会被几十份
 * 一模一样的堆栈填满，而且用户永远只会看第一份。留 5 份，按时间倒序，
 * 新的挤掉旧的。
 *
 * ## 为什么单份截断到 64KB
 *
 * 堆栈本身很少有几十 KB，但 `Caused by` 链加上超长异常消息（比如某个服务端
 * 把一整页 HTML 塞进了 message）能撑得很大。上限是给磁盘用的。
 */
class CrashStore(
    private val dir: File,
    private val maxFiles: Int = MAX_FILES,
    private val maxBytes: Int = MAX_BYTES,
) {

    /**
     * 记一份崩溃报告，返回落盘的文件；**任何一步失败都返回 null，不抛异常**。
     *
     * [context] 是个 lambda 而不是 [CrashContext]，因为它要在崩溃那一刻才
     * 求值（版本号要读 `PackageManager`，机型要读 `Build`），而且**它自己
     * 抛异常不能连累整份报告** —— 没有版本号的堆栈仍然比没有堆栈强。
     *
     * [at] 是写入哪个时刻，默认当前时间。测试用它把两份报告钉在同一毫秒上
     * （真实场景里「同一毫秒连崩两次」只在崩溃循环里出现）。
     *
     * 调用方是 `setDefaultUncaughtExceptionHandler` 里那个处理器，因此这个
     * 方法跑在**正在崩溃的那条线程**上。写盘是同步的：那条线程马上就要被
     * 系统杀掉，异步写等于永远写不完。
     */
    fun record(
        context: () -> CrashContext,
        thread: Thread,
        error: Throwable,
        at: Long = System.currentTimeMillis(),
    ): File? {
        // 先确认目标位置可用再拼正文 —— 目录建不出来时不必白拼一份
        val target = writableTarget(at) ?: return null
        val meta = runCatching { context() }.getOrDefault(UNKNOWN_CONTEXT)
        return try {
            target.writeText(truncate(render(meta, thread, error, at), maxBytes), Charsets.UTF_8)
            prune()
            target
        } catch (e: Exception) {
            // 磁盘满 / 目录被删 / 权限变了。半截文件留着只会让 list() 读出
            // 一份缺尾巴的报告，删掉更干净
            target.delete()
            null
        }
    }

    /**
     * 已落盘的记录，**按时间倒序**（最新的在前）。
     *
     * 目录里认不出的文件（名字对不上、是子目录、大到不可能是我们写的）一律
     * 跳过，读不出来的也跳过 —— 诊断页面本身绝不能因为一份坏文件而打不开，
     * 而那正是用户最需要它的时候。
     *
     * 一次性把全文读回来：份数与单份大小都有硬上限（默认 5 × 64KB），所以
     * 这个代价是有界的，不值得为它引一套懒加载状态。
     */
    fun list(): List<CrashRecord> = files()
        .sortedByDescending { it.first }
        .mapNotNull { (stamp, file) ->
            // 我们自己写的文件一定不超过 maxBytes 加几十字节的截断标记
            if (file.length() > maxBytes + 1024) return@mapNotNull null
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                ?: return@mapNotNull null
            CrashRecord(epochMillis = stamp, headLine = headLineOf(text), text = text)
        }

    /**
     * 删掉目录里所有**文件**，返回删掉的份数。
     *
     * 不区分「是不是我们写的」：这个目录只为崩溃记录而存在，留着认不出的
     * 文件只会让用户点完「清除全部」、再用文件管理器一看还有东西，以为
     * 按钮没生效。
     *
     * 子目录不动 —— 那更不像我们的东西，而且 `delete()` 对非空目录本来也
     * 只会失败。目录本身也不删：下次崩溃还要用它。
     */
    fun clear(): Int = dir.listFiles().orEmpty().count { it.isFile && it.delete() }

    // ------------------------------------------------------------ 内部

    /** 目录里「像是我们写的」文件，配对的第一个是文件名里的时间戳。 */
    private fun files(): List<Pair<Long, File>> =
        dir.listFiles().orEmpty().mapNotNull { file -> stampOf(file)?.let { it to file } }

    /** 超出上限的旧记录，从最旧的开始删。 */
    private fun prune() {
        files()
            .sortedByDescending { it.first }
            .drop(maxFiles)
            .forEach { (_, file) -> file.delete() }
    }

    private fun writableTarget(at: Long): File? {
        if (!dir.isDirectory && !dir.mkdirs()) return null
        // 同一毫秒里崩两次（崩溃循环）时不能让后一份覆盖前一份 —— 被盖掉的
        // 那份往往才是根因（第一次崩溃），而循环里后续的堆栈长得都差不多。
        // 往后挪一毫秒换个空名字，名字各不相同所以循环必然终止
        var stamp = at
        while (File(dir, fileName(stamp)).exists()) stamp++
        return File(dir, fileName(stamp))
    }

    /**
     * 从文件名里取出时间戳；不是我们生成的名字就返回 null。
     *
     * 时间戳放在**文件名**而不是正文里，是为了让 [list] 不必解析正文就能
     * 排序，也让 `adb shell ls` 一眼看出哪份最新。
     */
    private fun stampOf(file: File): Long? =
        if (file.isFile) {
            NAME.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
        } else {
            null
        }

    /**
     * 报告正文。
     *
     * 前五行是元信息、一个空行、然后是堆栈原文。**那个空行是格式的一部分**：
     * [headLineOf] 就靠它把「元信息」和「异常」切开，所以元信息的每个值都
     * 必须先压成一行（见 [oneLine]）—— 否则一个带换行的机型名就能让列表上
     * 显示的那一行变成半句元信息。
     */
    private fun render(meta: CrashContext, thread: Thread, error: Throwable, at: Long): String =
        buildString {
            appendLine("时间：${formatTimestamp(at)}")
            appendLine("版本：${oneLine(meta.appVersion) ?: UNKNOWN}")
            appendLine("系统：${oneLine(meta.osVersion) ?: UNKNOWN}")
            appendLine("机型：${oneLine(meta.deviceModel) ?: UNKNOWN}")
            appendLine("线程：${oneLine(thread.name) ?: UNKNOWN} (id=${threadId(thread)})")
            appendLine()
            // printStackTrace 会把 Caused by 链、Suppressed 和
            // "N common frames omitted" 一起写出来，比自己遍历 getCause()
            // 再拼一遍可靠得多
            val out = StringWriter()
            PrintWriter(out).use { error.printStackTrace(it) }
            append(out.toString())
        }

    /**
     * 线程 id。
     *
     * 光有线程名不够用：OkHttp、Room、协程调度器都会起一串同名线程
     * （「OkHttp Dispatcher」可能有七八条），不带上 id 就分不清是哪一条。
     *
     * `Thread.id` 在新版 JDK 与 Android 上被标了废弃（替代品是 `threadId()`），
     * 但**那个新 API 在 minSdk 28 上根本不存在** —— 按提示改会直接编译不过。
     * 为了一个数字抬 minSdk 不划算，就在这一处把警告压掉。这个替代判断
     * 来自 SDK 的 `android.jar`，不是猜的：`Thread.threadId()` 要 API 36。
     */
    @Suppress("DEPRECATION")
    private fun threadId(thread: Thread): Long = thread.id

    /**
     * 列表上折叠显示的那一行 —— 异常类 + 消息。
     *
     * 取的是空行之后的第一个非空行，也就是 `printStackTrace` 的第一行。
     * 长度掐到 [HEAD_LINE_CHARS]：异常消息本身可能很长（比如整页 HTML），
     * 而它在界面上只占一行。
     */
    private fun headLineOf(text: String): String {
        val sep = text.indexOf("\n\n")
        val body = if (sep >= 0) text.substring(sep + 2) else text
        val line = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: return EMPTY_BODY
        return if (line.length <= HEAD_LINE_CHARS) line else line.take(HEAD_LINE_CHARS) + "…"
    }

    /** 压成单行并掐长。元信息的每个值都要过这一道，理由见 [render]。 */
    private fun oneLine(value: String?): String? = value
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { if (it.length <= META_CHARS) it else it.take(META_CHARS) + "…" }

    /**
     * 绝对时间，带时区偏移。
     *
     * 刻意**不用** `ConversationDrawer.formatTime`：那个是相对时间
     * （「3 小时前」），适合会话列表，不适合诊断 —— 一份三天前的崩溃报告
     * 写着「3 天前」，等于让人自己去算。这里要的是能拿去比对日志的时刻。
     *
     * 每次都新建 `SimpleDateFormat`：它**不是线程安全的**，而崩溃可能来自
     * 任意线程、甚至几条线程同时崩，共享一个实例迟早输出一串乱码时间。
     * `Locale.US` 也是刻意的：数字模式看着与 locale 无关，但 `ar` 之类的
     * 默认 locale 会把数字渲染成阿拉伯-印度数字，时间就没法读了。
     */
    private fun formatTimestamp(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(epochMillis))

    /**
     * 按**字节**截断，并退到字符边界。
     *
     * 上限是给磁盘用的，而一个汉字占 3 字节 —— 按字符算的话「64KB」能变成
     * 192KB。退到字符边界，是因为从中间切开一个多字节字符之后，落盘的末尾
     * 会是个替换符（U+FFFD），看着像文件坏了。
     */
    private fun truncate(text: String, limit: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= limit) return text
        // bytes[limit] 是第一个被丢弃的字节。只要它还是「续字节」
        // （10xxxxxx），就说明它前面那个字符没写全，得继续往前退
        var end = limit
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) +
            "\n…（已截断：原文 ${bytes.size} 字节，上限 $limit）\n"
    }

    companion object {
        /** 记录所在目录名，拼在 `filesDir` 下面。 */
        const val DIR_NAME = "crash"

        /** 最多留几份。崩溃循环时旧报告会被新的挤掉。 */
        const val MAX_FILES = 5

        /** 单份上限（字节）。 */
        const val MAX_BYTES = 64 * 1024

        /** 折叠那一行的最大字符数。 */
        private const val HEAD_LINE_CHARS = 160

        /** 元信息每个值的最大字符数。 */
        private const val META_CHARS = 120

        private const val UNKNOWN = "未知"
        private const val EMPTY_BODY = "（空报告）"

        private val UNKNOWN_CONTEXT = CrashContext(null, null, null)

        /** `crash-<epochMillis>.txt`。 */
        private val NAME = Regex("""crash-(\d+)\.txt""")

        private fun fileName(stamp: Long): String = "crash-$stamp.txt"
    }
}
