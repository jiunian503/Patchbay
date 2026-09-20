package com.aichat.sandbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.plugin.manifest.FilesystemScope
import com.aichat.plugin.runtime.script.ScriptOutcome
import com.aichat.plugin.runtime.script.ScriptRequest
import com.aichat.plugin.workspace.PluginWorkspace
import com.aichat.plugin.workspace.PluginWorkspaces
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 插件运行时工作区，走**真的沙箱**（`:sandbox` 进程 + QuickJS）验一遍。
 *
 * ## 为什么这些不能只靠单测
 *
 * `PluginWorkspaceTest` 已经把边界（路径穿越、权限、四条上限）钉住了，
 * 而且跑得飞快。这里补的是**只有跨进程才成立的那件事**：
 *
 * > 沙箱每次调用都是一个新进程，但工作区里的东西**下一次调用还在**。
 *
 * 这一条是工作区存在的全部理由，也是它最容易做错的地方 —— 只要
 * `PluginWorkspaces.under()` 算出来的路径在沙箱进程里和主进程里不一样，
 * 单测永远发现不了：单测里两边拿到的是同一个 `File` 对象。
 *
 * ## 一个用例 = 一个插件 id
 *
 * **同一个用例里的多次调用必须用同一个 id**，否则每次调用都是一块新工作区，
 * 「跨调用」那条就悄悄变成了「每次都是空的」—— 用例还是绿的，但它什么都没验。
 * 所以 id 由用例自己 [newId] 拿一次，之后所有 `request` 都传它。
 *
 * ## 顺带
 *
 * 这里也是**唯一**会真跑「软链接越界」的地方 —— Windows 上 JDK 建不了
 * 软链接（见 `PluginWorkspaceTest` 里那条的跳过说明），而 Android 是 Linux。
 */
@RunWith(AndroidJUnit4::class)
class SandboxWorkspaceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val runtime = QuickJsSandboxRuntime(context, context.filesDir)

    private val workspaces = PluginWorkspaces.under(context.filesDir)

    /** 这次跑批用过的 id，`@After` 逐个删干净 —— 它们落在同一个 App 里。 */
    private val used = mutableListOf<String>()

    /** 只增不减：`used` 会清，这个不会，保证跨用例也不重号。 */
    private var seq = 0

    private fun newId(): String = "pub.test.ws${seq++}".also { used += it }

    @After
    fun cleanUp() {
        used.forEach { workspaces.delete(it) }
        used.clear()
    }

    private fun request(
        id: String,
        source: String,
        filesystem: FilesystemScope = FilesystemScope.ReadWrite,
    ) = ScriptRequest(
        pluginId = id,
        pluginName = "工作区测试插件",
        entryFile = "index.js",
        files = mapOf("index.js" to source),
        toolName = "probe",
        inputJson = "{}",
        settings = emptyMap(),
        network = emptyList(),
        filesystem = filesystem,
        timeoutMs = 20_000,
        memoryLimitMb = 128,
    )

    private fun run(request: ScriptRequest): ScriptOutcome = runBlocking { runtime.execute(request) }

    private fun okJson(outcome: ScriptOutcome): String {
        assertTrue("期望成功，实际是：$outcome", outcome is ScriptOutcome.Ok)
        return (outcome as ScriptOutcome.Ok).json
    }

    private fun failure(outcome: ScriptOutcome): ScriptOutcome.Failed {
        assertTrue("期望失败，实际是：$outcome", outcome is ScriptOutcome.Failed)
        return outcome as ScriptOutcome.Failed
    }

    // ── 存在意义：跨调用 ──────────────────────────────────────────────────────

    @Test
    fun 上一次调用写的东西_下一次调用还读得到() {
        // 三次调用 = 三个新进程。中间那次的「读-改-写」只有工作区真的落在
        // 磁盘上才做得到 —— 任何形式的进程内缓存都撑不过第二次调用
        val id = newId()

        assertEquals(
            """{"wrote":1}""",
            okJson(
                run(
                    request(
                        id = id,
                        source = """
                            module.exports = { run: function (i, host) {
                              host.fs.writeText("n.txt", "1");
                              return { wrote: 1 };
                            } };
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        assertEquals(
            """{"n":2}""",
            okJson(
                run(
                    request(
                        id = id,
                        source = """
                            module.exports = { run: function (i, host) {
                              var n = Number(host.fs.readText("n.txt"));
                              host.fs.writeText("n.txt", String(n + 1));
                              return { n: n + 1 };
                            } };
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        assertEquals(
            """{"n":2}""",
            okJson(
                run(
                    request(
                        id = id,
                        source = """
                            module.exports = { run: function (i, host) {
                              return { n: Number(host.fs.readText("n.txt")) };
                            } };
                        """.trimIndent(),
                    ),
                ),
            ),
        )
    }

    @Test
    fun 两个插件的工作区互不相干() {
        val a = newId()
        val b = newId()
        // 同一个文件名，两个插件各写各的
        okJson(run(request(id = a, source = write("a 的"))))
        okJson(run(request(id = b, source = write("b 的"))))

        // 读的时候各自拿到自己的那份 —— 若路径算错，两边会是同一个文件
        assertEquals("""{"v":"a 的"}""", okJson(run(request(id = a, source = READ_BACK))))
        assertEquals("""{"v":"b 的"}""", okJson(run(request(id = b, source = READ_BACK))))
    }

    // ── 边界：走一遍真的 JNI + 进程边界 ───────────────────────────────────────

    @Test
    fun list_列出写过的东西() {
        val json = okJson(
            run(
                request(
                    id = newId(),
                    source = """
                        module.exports = { run: function (i, host) {
                          host.fs.writeText("b.txt", "1");
                          host.fs.writeText("a.txt", "1");
                          return { items: host.fs.list(".") };
                        } };
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals("""{"items":["a.txt","b.txt"]}""", json)
    }

    @Test
    fun 越界路径被拒_而且报错是给作者看的() {
        val failed = failure(
            run(
                request(
                    id = newId(),
                    source = """
                        module.exports = { run: function (i, host) {
                          return { v: host.fs.readText("../../databases/chat.db") };
                        } };
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(ScriptOutcome.Kind.ScriptError, failed.kind)
        assertTrue(failed.message, failed.message.contains(".."))
        // 说权限，不说设置页 —— 见 PluginWorkspace.resolve
        assertTrue(failed.message, failed.message.contains("权限"))
    }

    @Test
    fun 只读权限的插件写不了() {
        val failed = failure(
            run(
                request(
                    id = newId(),
                    filesystem = FilesystemScope.Read,
                    source = """
                        module.exports = { run: function (i, host) {
                          host.fs.writeText("x.txt", "x");
                          return {};
                        } };
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(ScriptOutcome.Kind.ScriptError, failed.kind)
        assertTrue(failed.message, failed.message.contains("read"))
    }

    @Test
    fun 没写过东西时读_给的是能照着做的提示() {
        // 这一条覆盖的是**最常见**的一次失败：作者第一次跑自己的插件，
        // 工作区还是空的。报错必须说清「这里本来就不会有什么」
        val failed = failure(
            run(
                request(
                    id = newId(),
                    source = """module.exports = { run: function (i, host) { return host.fs.readText("a.csv"); } };""",
                ),
            ),
        )
        assertEquals(ScriptOutcome.Kind.ScriptError, failed.kind)
        assertTrue(failed.message, failed.message.contains("list"))
    }

    @Test
    fun 路径不是字符串时当场拦下_不是静默变成_object_Object() {
        val failed = failure(
            run(
                request(
                    id = newId(),
                    source = """
                        module.exports = { run: function (i, host) {
                          host.fs.writeText({ 不是: "字符串" }, "x");
                          return {};
                        } };
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(ScriptOutcome.Kind.ScriptError, failed.kind)
        assertTrue(failed.message, failed.message.contains("路径"))
    }

    @Test
    fun 软链接指到工作区外面也拒() {
        // 这条是 PluginWorkspace.resolve 里第二道守卫（canonicalFile + 前缀比对）
        // 的**唯一**真实验证点。第一道（逐段拒 ..）在这条路径上看不出问题 ——
        // 路径里一个 .. 都没有
        val id = newId()
        val dir = File(context.filesDir, "${PluginWorkspaces.BASE_DIR_NAME}/$id")
        dir.mkdirs()

        val outside = File(context.filesDir, "outside-secret.txt")
        outside.writeText("不该被读到")
        val created = runCatching {
            Files.createSymbolicLink(File(dir, "escape").toPath(), outside.toPath())
        }.isSuccess
        assertTrue("Android 上应该建得出软链接，这条用例不能靠跳过混过去", created)

        val failed = failure(
            run(
                request(
                    id = id,
                    source = """module.exports = { run: function (i, host) { return { v: host.fs.readText("escape") }; } };""",
                ),
            ),
        )
        assertEquals(ScriptOutcome.Kind.ScriptError, failed.kind)
        outside.delete()
    }

    // ── 用户导入的文件：方向反过来 ────────────────────────────────────────────

    @Test
    fun 主进程放进去的文件_插件读得到() {
        // 这是「导入文件」这个功能的**真正验收点**，而且方向是反的：
        // 上面那些用例都是沙箱写、沙箱读，这条是**主进程写、沙箱读**。
        //
        // 两边算路径的方式必须完全一致（都走 PluginWorkspaces.under），
        // 否则用户在详情页导入的文件会躺在一个插件永远看不到的地方 ——
        // 而界面上它会好好地显示在文件列表里，谁也不觉得有问题
        val id = newId()

        // 走 adminOf，也就是详情页导入文件用的那条路；
        // 名字也走 cleanName，连「SAF 给的名字很脏」这件事一起验了
        val name = PluginWorkspace.cleanName("Download/月度报表.csv")
        workspaces.adminOf(id).writeBytes(name, "城市,销量\n上海,42\n".toByteArray())

        val json = okJson(
            run(
                request(
                    id = id,
                    source = """
                        module.exports = { run: function (i, host) {
                          return { csv: host.fs.readText("$name") };
                        } };
                    """.trimIndent(),
                ),
            ),
        )
        assertTrue(json, json.contains("上海"))
    }

    // ── 和权限模型的接缝 ──────────────────────────────────────────────────────

    @Test
    fun 没声明_filesystem_的插件连_host_fs_都看不到() {
        // 权限模型那条「插件拿不到清单里没声明的东西」，在 JS 侧的表现就是它。
        // 和 SandboxRuntimeTest 里那条**刻意重复**：那边守的是「装配期有没有
        // 按声明注入」，这边守的是「工作区真的接进来之后这条还成不成立」
        val json = okJson(
            run(
                request(
                    id = newId(),
                    filesystem = FilesystemScope.None,
                    source = """module.exports = { run: function (i, host) { return { fs: typeof host.fs }; } };""",
                ),
            ),
        )
        assertEquals("""{"fs":"undefined"}""", json)
    }

    private companion object {
        /** 读回 `k.txt` 的内容。两个插件那条用例用它比对。 */
        val READ_BACK =
            """module.exports = { run: function (i, h) { return { v: h.fs.readText("k.txt") }; } };"""

        /**
         * 把 `v` 写进 `k.txt`。
         *
         * 走源码而不是 `inputJson`：这样两个插件可以**共用同一份 `inputJson`**
         * （都是 `{}`），用例里要盯的东西就只剩「各自读回了什么」。
         */
        fun write(v: String) =
            """module.exports = { run: function (i, h) { h.fs.writeText("k.txt", "$v"); return {}; } };"""
    }
}
