package com.aichat.plugin.manifest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `plugin/examples/` 下**每一份**清单都必须能通过宿主校验 —— 包括不随包发的那份。
 *
 * ## 为什么单独一个测试文件
 *
 * `:app` 的 `BundledManifestsTest` 已经有一条「每个示例清单都能通过校验」，
 * 但它遍历的是 `PluginInstallViewModel.EXAMPLES` —— **随包发的那几份**。
 * 而 `plugin/examples/csvstat` 是**故意不打包**的（它的 `script` 运行时还没实现），
 * 于是它从来没被任何测试解析过。
 *
 * 这不是小事：`csvstat` 是协议里 **`script` 这一支唯一的示例**，是插件作者照着抄的
 * 参考版。它坏掉的话，作者会照着抄出一份装不上的清单，而两边的测试都是绿的 ——
 * 五十六轮改它的 `runtime`（`node` → `quickjs`）时正是这个状态，改错也不会有人发现。
 *
 * ## 为什么放在 `:plugin` 而不是 `:app`
 *
 * 两个理由：`plugin/examples/` 就在本模块自己的目录下（不用跨模块猜路径）；
 * 而且它校验的是**协议**，不是某个界面上的示例列表。
 *
 * ## 空集合是恒过的
 *
 * 遍历一个空集合的测试**永远通过** —— 路径指错、目录被改名、文件被挪走，
 * 它都会以「通过」的形式安静地失效。所以先断言「找到了几份」。
 * 这是 §38 那条坑的另一副面孔：读文件系统的测试，先证明自己真的读到了东西。
 */
class ExampleManifestsTest {

    private val examplesDir: File = run {
        val raw = System.getProperty("patchbay.examplesDir")
        assertNotNull(
            "构建配置没把 patchbay.examplesDir 传给测试 JVM —— " +
                "检查 plugin/build.gradle.kts 的 tasks.test",
            raw,
        )
        File(raw!!).also {
            assertTrue("示例目录不存在：${it.absolutePath}", it.isDirectory)
        }
    }

    /** 每个示例目录下的 `manifest.json`，按路径排序（失败信息才稳定）。 */
    private fun manifests(): List<File> =
        examplesDir.listFiles { f -> f.isDirectory }
            .orEmpty()
            .map { File(it, "manifest.json") }
            .sortedBy { it.path }

    @Test
    fun `每一份示例清单都能通过校验`() {
        val files = manifests()

        assertTrue(
            "在 ${examplesDir.absolutePath} 下一份示例清单都没找到。" +
                "路径指错了、或者目录被挪走了，都会让这个测试变成一句空话",
            files.isNotEmpty(),
        )

        val problems = files.mapNotNull { file ->
            assertTrue(
                "示例目录里有子目录但缺 manifest.json：${file.parentFile.name}",
                file.isFile,
            )
            val check = ManifestParser.parse(file.readText())
            // 注意方向：要收的是**解析失败**的那些（`manifest == null`）。
            // 写成 `check.manifest ?: "…"` 是反的 —— 那样会把解析成功的清单
            // 收进「问题」列表，于是这条测试变成「必须一份都解析不通过」。
            if (check.manifest == null) "${file.parentFile.name}：\n${check.report()}" else null
        }

        assertEquals(
            "有示例清单通不过宿主校验。示例是插件作者照着抄的参考版，" +
                "它坏了会直接传染给第三方作者：",
            emptyList<String>(),
            problems,
        )
    }

    /**
     * 每种 `runtime` 形态都要有示例，除非明确列进 [NO_EXAMPLE_BY_DESIGN]。
     *
     * 这条守的是「**删掉一份示例**」—— 上面那条只检查「存在的文件能不能解析」，
     * 把 `csvstat` 整个删掉它照样绿。而 `csvstat` 恰恰是 `script` 这一支唯一的示例，
     * 且不随包发（`:app` 那边看不到它）。
     *
     * 例外必须**写下来**，不能靠「反正现在没有」。理由：不写的话，「我们决定不发
     * 这一类示例」和「有人删了示例没发现」在测试眼里长得一模一样。
     */
    @Test
    fun `每种 runtime 都有示例，例外必须写下来`() {
        val covered = manifests()
            .map { ManifestParser.parse(it.readText()).manifest }
            .mapNotNull { it?.runtime }
            .toSet()

        assertEquals(
            "有 runtime 形态既没有示例、也不在 NO_EXAMPLE_BY_DESIGN 里。" +
                "作者想写这一类插件时没有可抄的东西 —— " +
                "要么补一份示例，要么把它写进例外表并说明理由：",
            PluginRuntimeKind.entries.toSet() - NO_EXAMPLE_BY_DESIGN,
            covered,
        )
    }

    private companion object {
        /**
         * 明确「**不打算**给示例」的形态。往这里加一个值，等于声明
         * 「这一类我们不让作者写」—— 所以每一条都得有理由。
         */
        val NO_EXAMPLE_BY_DESIGN = setOf(
            // 一份连不上的 MCP 示例没有意义：它要指着一台真实服务器，
            // 而作者手上没有我们的服务器。协议怎么接由 MCP 自己的文档讲，
            // 这边只留 `McpHostTest` 里的测试夹具
            PluginRuntimeKind.Mcp,
            // 宿主还不支持「随 APK 打包的二进制」（PluginHost 明确报不支持），
            // 更不该发一份作者照着也做不出来的示例
            PluginRuntimeKind.Native,
        )
    }
}
