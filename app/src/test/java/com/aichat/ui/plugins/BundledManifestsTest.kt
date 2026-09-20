package com.aichat.ui.plugins

import com.aichat.plugin.manifest.ManifestParser
import com.aichat.plugin.manifest.PluginManifest
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 随包发的示例插件必须**装得上**。
 *
 * ## 为什么值得一个专门的测试
 *
 * 内置示例是用户第一眼看到的插件长什么样 —— 它装不上的话，用户得到的结论是
 * 「这个 App 的插件功能是坏的」，而不是「示例文件有问题」。
 * 而它坏掉的方式特别隐蔽：示例是 JSON 文件，改了 `ManifestParser`
 * 的校验规则之后，那份 JSON 可能就不再合法了，但**编译和别的测试都不会报错**。
 *
 * 所以这里把每一个示例都过一遍真正的解析器。测试本身不验证语义，
 * 只断言「没有 Error 级问题」—— 那就是「能不能装」的定义。
 *
 * ## 路径怎么来的
 *
 * `app/build.gradle.kts` 的 `testOptions` 里把 assets 目录通过系统属性
 * 传给测试 JVM（单元测试拿不到 Android 的 AssetManager）。
 * 属性缺失时**直接失败**而不是跳过：那说明构建配置被改坏了，
 * 而这个测试正是为了拦住「配置悄悄坏掉」。
 */
class BundledManifestsTest {

    private val assetsDir: File = run {
        val raw = System.getProperty("patchbay.assetsDir")
        assertNotNull(
            "构建配置没把 patchbay.assetsDir 传给测试 JVM —— " +
                "检查 app/build.gradle.kts 的 testOptions.unitTests.all",
            raw,
        )
        File(raw!!).also {
            assertTrue("assets 目录不存在：${it.absolutePath}", it.isDirectory)
        }
    }

    private fun exampleFile(assetPath: String) = File(assetsDir, assetPath)

    /**
     * 读出示例并解析成清单。
     *
     * 解析不过时**直接抛出带报告的消息**，而不是让调用方去 `!!` ——
     * 那样失败会显示成 `NullPointerException`，看不出是哪个示例、
     * 哪一条校验没过。
     */
    private fun manifestOf(example: BundledExample): PluginManifest {
        val check = ManifestParser.parse(exampleFile(example.assetPath).readText())
        return check.manifest ?: error(
            "示例「${example.label}」的清单不合法，用户在安装页上会看到一片红字：\n${check.report()}",
        )
    }

    @Test
    fun `示例清单文件都存在`() {
        assertTrue("一个示例都没打包进来，安装页上会是空的", PluginInstallViewModel.EXAMPLES.isNotEmpty())

        for (example in PluginInstallViewModel.EXAMPLES) {
            assertTrue(
                "清单里登记的示例不存在：${example.assetPath}",
                exampleFile(example.assetPath).isFile,
            )
        }
    }

    @Test
    fun `每个示例清单都能通过校验`() {
        for (example in PluginInstallViewModel.EXAMPLES) {
            val json = exampleFile(example.assetPath).readText()
            val check = ManifestParser.parse(json)

            assertNotNull(
                "示例「${example.label}」装不上，用户在安装页上会看到一片红字：\n${check.report()}",
                check.manifest,
            )
        }
    }

    @Test
    fun `示例清单的插件 id 不重复`() {
        val ids = PluginInstallViewModel.EXAMPLES.map { manifestOf(it).id }

        // 重复的话第二个会覆盖第一个 —— 用户装了两个「不同的」示例，
        // 实际只有一个
        assertTrue("示例的插件 id 有重复：$ids", ids.size == ids.distinct().size)
    }

    @Test
    fun `示例不能申请任意主机权限`() {
        // 内置示例是我们自己写的，不该成为「随手就声明 `*`」的坏示范
        for (example in PluginInstallViewModel.EXAMPLES) {
            assertFalse(
                "示例「${example.label}」声明了任意主机 —— 它应该示范怎么把权限写到最小",
                manifestOf(example).permissions.network.contains("*"),
            )
        }
    }

    @Test
    fun `示例的工具说明写足了触发场景`() {
        // `description` 是提示词，直接决定模型会不会用对。
        // 示例是作者照着抄的模板，写「查询天气」和写清「用户只说城市名时
        // 先用 geocode_city」的示范效果差很远
        for (example in PluginInstallViewModel.EXAMPLES) {
            for (tool in manifestOf(example).tools) {
                assertTrue(
                    "示例工具 ${tool.name} 的说明太短，它会被作者照抄：${tool.description}",
                    tool.description.length >= 30,
                )
            }
        }
    }

    /**
     * 同一个示例存在两处，它们**必须是同一份文件**。
     *
     * - `app/src/main/assets/plugins/<名字>.json` —— 用户点「装示例」时真正读的那份
     * - `plugin/examples/<插件名>/manifest.json` —— 插件作者照着抄的参考版
     *
     * （这两行刻意不写通配符：**Kotlin 的块注释可以嵌套** —— 注释里出现
     * 一个「斜杠紧跟星号」就会再开一层，后面那个「星号紧跟斜杠」只关掉一层，
     * 于是整个文件都成了注释。报错位置在文件末尾的「Unclosed comment」，
     * 离真正写错的地方有两百行。）
     *
     * ## 为什么值得一条测试
     *
     * 这两份是**手工同步**的副本，而手工同步的东西一定会漂移。这不是假设：
     * 第六轮就发生过 —— 只改了示例里的对端点、忘了改随包那份，编译和所有
     * 测试都不报错，直到真机上装示例跑出 404 才暴露。症状离原因太远：
     * 404 看起来像「服务端挂了」，而真正的问题是「有两份文件，只改了一份」。
     *
     * ## 为什么按字节比，而不是比解析后的语义
     *
     * 语义相同、文本不同的两份，正是漂移的**中间状态**：下一次有人改其中一份时，
     * 他已经看不出哪边是当前生效的。逐字节相同让「只改一份」这个动作立刻失败，
     * 而两份文件就在同一个仓库里，同时改的成本几乎为零。
     *
     * 代价是参考版不能加 JSON 里本来没有的东西（注释、示例字段）。这个代价
     * 划算：格式说明在 `plugin/manifest.schema.json`（它和 Kotlin 模型的一致性
     * 由 `ManifestModelTest` 守着），不该靠某一份示例文件兼职。
     *
     * ## 方向是单向的：只要求 assets ⊆ examples
     *
     * 随包发的每一份，参考版里必须有一份逐字节相同的。反过来**不要求** ——
     * `csvstat` 是脚本式示例，它依赖的运行时还没实现，所以**故意**不打包。
     * 为它报错等于逼着把还不能用的东西塞进安装页。
     */
    @Test
    fun `随包发的示例清单与作者参考版逐字节相同`() {
        val examplesDir = run {
            val raw = System.getProperty("patchbay.examplesDir")
            assertNotNull(
                "构建配置没把 patchbay.examplesDir 传给测试 JVM —— " +
                    "检查 app/build.gradle.kts 的 testOptions.unitTests.all",
                raw,
            )
            File(raw!!).also {
                assertTrue("参考版示例目录不存在：${it.absolutePath}", it.isDirectory)
            }
        }

        val references = examplesDir.listFiles { f -> f.isDirectory }
            .orEmpty()
            .map { File(it, "manifest.json") }
            .filter { it.isFile }
        assertTrue(
            "一份参考版示例都没找到，先确认 patchbay.examplesDir 指对了：${examplesDir.absolutePath}",
            references.isNotEmpty(),
        )

        // 按**插件 id** 建索引，不按文件名 —— 文件名可以随便改，
        // id 是清单自己声明的身份，也是「这是同一个插件」的唯一依据
        val ids = references.map { idOf(it) }
        assertTrue(
            "plugin/examples/ 里有重复的插件 id，后面的会盖掉前面的：$ids",
            ids.size == ids.distinct().size,
        )
        val byId = references.associateBy { idOf(it) }

        for (example in PluginInstallViewModel.EXAMPLES) {
            val bundled = exampleFile(example.assetPath)
            val id = idOf(bundled)
            val reference = byId[id] ?: error(
                "随包发的示例「${example.label}」（id=$id）在 plugin/examples/ 里没有参考版。" +
                    "作者是照着参考版写的 —— 少一份等于让他照着一份不存在的文件抄。",
            )
            assertTrue(
                "示例「${example.label}」（id=$id）的两份副本已经不一致：\n" +
                    "  随包发：${bundled.absolutePath}\n" +
                    "  参考版：${reference.absolutePath}\n" +
                    "改一份必须同时改另一份 —— 第六轮就是只改了一份，真机上跑出 404。",
                bundled.readBytes().contentEquals(reference.readBytes()),
            )
        }
    }

    /**
     * 读一份清单的插件 id。
     *
     * 解析不过就直接失败：一份坏掉的参考版同样是 bug —— 作者会照着它抄，
     * 抄出来的是同样装不上的清单。
     */
    private fun idOf(file: File): String {
        val check = ManifestParser.parse(file.readText())
        return check.manifest?.id ?: error(
            "清单不合法：${file.absolutePath}\n${check.report()}",
        )
    }
}
