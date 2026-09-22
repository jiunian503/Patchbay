package com.aichat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 沙箱进程名在**三个文件**里各写一遍 —— 这个测试把它们钉在一起。
 *
 * ## 为什么值得一条测试
 *
 * `PatchbayApp.SANDBOX_PROCESS_SUFFIX`（Kotlin 常量）、`AndroidManifest.xml` 里
 * `<service>` 的 `android:process`、以及 `README.md` 里那句说明，必须说的是同一个名字：
 *
 * - `PatchbayApp` 靠它判断「我在沙箱进程里，什么初始化都不做」
 * - 清单靠它把 `ScriptSandboxService` 放进独立进程
 * - README 告诉用户「沙箱是独立进程」
 *
 * XML 里引用不了 Kotlin 常量，所以**两边都写了注释说「这只能靠人保持同步」**。
 * 但三处都是**文本文件** —— 那句话是**可以证伪的**，这个测试就是证伪它。
 *
 * 对不上的后果不是「报个错」：`isSandboxProcess()` 会永远返回 false ⇒
 * **沙箱进程当成主进程启动**（建容器、读数据库、装崩溃留痕全都跑一遍）。
 * 详见 `PatchbayApp` 里 `SANDBOX_PROCESS_SUFFIX` 的 KDoc。
 *
 * ## 它守着什么
 *
 * 1. 代码常量 == 清单里的 `android:process` == README 里的那一处
 * 2. 名字以 `:` 开头（相对进程名）
 * 3. 清单里 `android:process` **只有一处** —— 多一处就回来判断新的那处是不是
 *    也该单独开进程（`:sandbox` 现在是 manifest 里唯一一处 `android:process`）
 *
 * ## 为什么读文本而不是反射
 *
 * 要钉住的是「**这几个文件里的字面量一致**」，而清单和 README 根本不是 Kotlin ——
 * 反射只能拿到其中一边。
 *
 * ⚠️ 两侧都**先剥掉注释**再匹配：`PatchbayApp.kt` 的 KDoc 里就写着
 * `android:process=":sandbox"` 当例子 —— 不剥的话守卫会对着**解释它自己的注释**
 * 报警（这条规则是 `SourceScan` 那边先立下的）。
 *
 * ⚠️ **这几个文件都必须在测试任务的输入里**，否则「有人只改了一边」时任务会被判
 * UP-TO-DATE 整个跳过，守卫读到旧报告里的绿：
 *
 * - `AndroidManifest.xml` 由 `app/build.gradle.kts` 显式 `inputs.file(...)` 声明
 * - 源码树由 `testOptions` 里那棵 `fileTree` 覆盖（各模块 `src/main` 下的 `.kt`）
 * - `README.md` 也早就显式声明了（`NetworkEgressTest` 要读它核出网种类）
 */
class SandboxProcessNameTest {

    @Test
    fun `清单里的沙箱进程名和代码里的常量一致`() {
        val fromKotlin = kotlinConst()
        val fromManifest = onlyOne(manifestText(), PROCESS_ATTR, "清单里的 `android:process`")

        assertEquals(
            "沙箱进程名两边对不上：`PatchbayApp.SANDBOX_PROCESS_SUFFIX` = `$fromKotlin`，" +
                "而清单里 `android:process` = `$fromManifest`。\n" +
                "对不上的后果是**沙箱进程当成主进程启动**（建容器、读数据库、装崩溃留痕），" +
                "见 `PatchbayApp` 里那段 KDoc。改的时候两边一起改。",
            fromKotlin,
            fromManifest,
        )
    }

    @Test
    fun `README 里写的沙箱进程名也是这一个`() {
        val fromKotlin = kotlinConst()
        val fromReadme = onlyOne(readmeText(), PROCESS_ATTR, "README 里的 `android:process`")

        assertEquals(
            "README 那句「`:sandbox` 是一个**独立进程**」里写的进程名是 `$fromReadme`，" +
                "和常量 `$fromKotlin` 对不上。\n" +
                "README 是用户读的那份，进程名改了它也得改（它已经在测试任务的输入里，" +
                "所以只改它这条也会跑起来）。",
            fromKotlin,
            fromReadme,
        )
    }

    @Test
    fun `进程名是相对名，而且清单里只开了一个进程`() {
        val name = kotlinConst()
        assertTrue(
            "沙箱进程名应当以 `:` 开头（相对名，形如 `:sandbox`），现在是 `$name`。",
            name.startsWith(":"),
        )

        val all = PROCESS_ATTR.findAll(manifestText()).map { it.groupValues[1] }.toList()
        assertEquals(
            "清单里 `android:process` 现在有 ${all.size} 处：$all —— 只允许一处（`:sandbox`）。\n" +
                "多出来的那处要回来判断：它该单独开进程，还是写错了？",
            listOf(name),
            all,
        )
    }

    private fun kotlinConst(): String =
        onlyOne(
            kotlinText(),
            KOTLIN_CONST,
            "`PatchbayApp.kt` 里的 `SANDBOX_PROCESS_SUFFIX`",
        )

    private fun onlyOne(text: String, pattern: Regex, where: String): String {
        val all = pattern.findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(
            "$where 应该恰好命中 1 处，实际 ${all.size} 处：$all —— 守卫自己在看空气？",
            1,
            all.size,
        )
        return all.first()
    }

    private companion object {
        /** 块注释，也就是 KDoc 那种。`DOT_MATCHES_ALL` 让 `.` 跨行。 */
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

        /** `// ...` 到行尾。 */
        val LINE_COMMENT = Regex("//[^\n]*")

        /** `<!-- ... -->`。 */
        val XML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

        val KOTLIN_CONST = Regex("const val SANDBOX_PROCESS_SUFFIX\\s*=\\s*\"([^\"]+)\"")

        /** 清单和 README 里都用这个形式写进程名。 */
        val PROCESS_ATTR = Regex("android:process\\s*=\\s*\"([^\"]+)\"")

        fun kotlinText(): String =
            read(File(SourceScan.root, "app/src/main/java/com/aichat/PatchbayApp.kt"))
                .replace(BLOCK_COMMENT, "")
                .replace(LINE_COMMENT, "")

        fun manifestText(): String =
            read(File(SourceScan.root, "app/src/main/AndroidManifest.xml"))
                .replace(XML_COMMENT, "")

        fun readmeText(): String = read(SourceScan.fileFromProperty("patchbay.readmeFile"))

        fun read(file: File): String {
            assertTrue(
                "找不到文件：${file.absolutePath} —— `patchbay.sourceRoot` / `patchbay.readmeFile` 没注入？",
                file.isFile,
            )
            return file.readText()
        }
    }
}
