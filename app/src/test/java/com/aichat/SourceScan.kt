package com.aichat

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 扫**生产源码文本**的公共设施 —— [NetworkEgressTest] 与 `DeviceInfoSourceTest` 共用一份。
 *
 * ## 为什么要读文本而不是反射
 *
 * 这两条测试要证明的都是「**某个东西只有这几处**」（出网的口子、读设备信息的地方），
 * 而那个「东西」不是某个类型 —— 是几处互不相干的代码各自做的事。反射看不到这个，
 * 也数不出「同一处有几种」。
 *
 * ## 代价：Gradle 不知道这层依赖
 *
 * 得在 `app/build.gradle.kts` 里把源码树声明成测试任务的输入，否则只改注释时任务会被判
 * UP-TO-DATE **整个跳过**，于是守卫以「通过」的形式安静地失效。那是这类测试最容易漏的
 * 一步，也写在那边的注释里了。
 *
 * ## 模块名单从 `settings.gradle.kts` 读，不写死
 *
 * 写死的话，新加一个模块而它里面有出网点时扫描看不见 —— 而这类测试要证明的恰恰是
 * 「口子只有这几个」，它自己先漏掉一个模块就没有说服力了。
 *
 * ## 空集合恒过
 *
 * 扫描要是哪天一个都匹配不到（改了写法、模块列表解析失败），调用方的断言会「通过」得
 * 毫无意义。所以调用方**必须**先断「不是空的」—— [scan] 不替它们做这件事，因为
 * 「命中几处才算正常」只有调用方知道。
 */
object SourceScan {

    /** 仓库根。`patchbay.sourceRoot` 由 `app/build.gradle.kts` 的 `testOptions` 注入。 */
    val root: File = dirFromProperty("patchbay.sourceRoot")

    /**
     * 各模块 `src/main` 下所有 `.kt`。
     *
     * 只看 `src/main` —— 测试代码里造一个 HTTP 客户端、读一次 `Build.MODEL`
     * 都不算「生产代码又多了一处」。
     */
    fun productionSources(): List<File> {
        val settings = File(root, "settings.gradle.kts")
        assertTrue(
            "找不到 ${settings.path} —— `patchbay.sourceRoot` 指的应该是仓库根",
            settings.isFile,
        )

        val modules = INCLUDE.findAll(settings.readText()).map { it.groupValues[1] }.toList()
        assertTrue(
            "从 settings.gradle.kts 里一个 `include(\":模块\")` 都没解析出来 —— " +
                "声明写法变了，扫描会变成空的",
            modules.isNotEmpty(),
        )

        return modules.flatMap { module ->
            File(root, "$module/src/main").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        }
    }

    /**
     * 生产代码里 [marker] 的每一处出现：相对仓库根的路径 → 出现次数。
     *
     * **整行注释不算**（`//` 开头的、`*` 开头的续行、以及块注释的起始行）。这条规则
     * 是被自己逼出来的：要守的那几处代码，恰恰**必须在注释里提到那个标记**
     * （比如「没有 WebView 时 `WebView(context)` 会抛」）。不跳注释的话，守卫会对着
     * 解释它自己的注释报警 —— 那是最没用的那种红，而且会逼着人把注释写得含糊。
     *
     * 代价：标记写在**行尾**注释里、或者换个构造写法，就漏了。前者很罕见；后者会以
     * 「和声明对不上」红掉，红得明白。
     *
     * ⚠️ 别在这个文件里写出「斜杠加星号」那两个字符连在一起的写法 —— Kotlin 的块
     * 注释是**可嵌套**的，它会在注释里再开一层，报「Unclosed comment」。
     * 上面那些话是刻意绕开这个序列写的（实测踩过）。
     */
    fun scan(marker: String): Map<String, Int> =
        productionSources()
            .mapNotNull { file ->
                val count = file.readLines()
                    .map { it.trim() }
                    .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                    .sumOf { countOccurrences(it, marker) }
                if (count == 0) null
                else file.relativeTo(root).invariantSeparatorsPath to count
            }
            .toMap()

    /** [scan] 的便捷形态：命中的**文件名**（相对仓库根），不关心次数。 */
    fun scanFiles(marker: String): Set<String> = scan(marker).keys

    private fun countOccurrences(text: String, marker: String): Int {
        var from = 0
        var count = 0
        while (true) {
            val at = text.indexOf(marker, from)
            if (at < 0) return count
            count++
            from = at + marker.length
        }
    }

    fun fileFromProperty(name: String): File =
        File(pathFromProperty(name)).also {
            assertTrue("文件不存在：${it.absolutePath}", it.isFile)
        }

    private fun dirFromProperty(name: String): File =
        File(pathFromProperty(name)).also {
            assertTrue("目录不存在：${it.absolutePath}", it.isDirectory)
        }

    private fun pathFromProperty(name: String): String {
        val raw = System.getProperty(name)
        assertNotNull(
            "构建配置没把 $name 传给测试 JVM —— 检查 app/build.gradle.kts 的 testOptions",
            raw,
        )
        return raw!!
    }

    /** `settings.gradle.kts` 里的 `include(":模块")`。 */
    private val INCLUDE = Regex("""include\(":(\w+)"\)""")
}
