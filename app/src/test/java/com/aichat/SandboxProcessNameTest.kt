package com.aichat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 沙箱进程名在**两个文件**里各写一遍 —— 这个测试把它们钉在一起。
 *
 * ## 为什么值得一条测试
 *
 * `PatchbayApp.SANDBOX_PROCESS_SUFFIX`（Kotlin 常量）和 `AndroidManifest.xml` 里
 * `<service>` 的 `android:process` 必须一模一样：
 *
 * - `PatchbayApp` 靠它判断「我在沙箱进程里，什么初始化都不做」
 * - 清单靠它把 `ScriptSandboxService` 放进独立进程
 *
 * XML 里引用不了 Kotlin 常量，所以**两边都写了注释说「这只能靠人保持同步」**。
 * 但两边都是文本文件 —— 那句话是**可以证伪的**，这个测试就是证伪它。
 *
 * 对不上的后果不是「报个错」：`isSandboxProcess()` 会永远返回 false ⇒
 * **沙箱进程当成主进程启动**（建容器、读数据库、装崩溃留痕全都跑一遍）。
 * 详见 `PatchbayApp` 里 `SANDBOX_PROCESS_SUFFIX` 的 KDoc。
 *
 * ## 它守着什么
 *
 * 1. 两边取出来的名字相等
 * 2. 名字以 `:` 开头（相对进程名）
 * 3. 清单里 `android:process` **只有一处** —— 多一处就回来判断新的那处是不是
 *    也该单独开进程（`:sandbox` 现在是 manifest 里唯一一处 `android:process`）
 *
 * ## 为什么读文本而不是反射
 *
 * 要钉住的是「**这两个文件里的字面量一致**」，而清单根本不是 Kotlin ——
 * 反射只能拿到其中一边。
 *
 * ⚠️ 两侧都**先剥掉注释**再匹配：`PatchbayApp.kt` 的 KDoc 里就写着
 * `android:process=":sandbox"` 当例子 —— 不剥的话守卫会对着**解释它自己的注释**
 * 报警（这条规则是 `SourceScan` 那边先立下的）。
 *
 * ⚠️ `AndroidManifest.xml` **不在 `:app` 的编译输入里**，`app/build.gradle.kts`
 * 把它显式声明成了测试任务的输入 —— 和 README 同一个理由：只改它时任务会被判
 * UP-TO-DATE 整个跳过，而「有人只改了一边」恰恰是这里唯一要抓的场景。
 */
class SandboxProcessNameTest {

    @Test
    fun `清单里的沙箱进程名和代码里的常量一致`() {
        val fromKotlin = onlyOne(
            kotlinText(),
            KOTLIN_CONST,
            "`PatchbayApp.kt` 里的 `SANDBOX_PROCESS_SUFFIX`",
        )
        val fromManifest = onlyOne(manifestText(), MANIFEST_ATTR, "清单里的 `android:process`")

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
    fun `进程名是相对名，而且清单里只开了一个进程`() {
        val name = onlyOne(
            kotlinText(),
            KOTLIN_CONST,
            "`PatchbayApp.kt` 里的 `SANDBOX_PROCESS_SUFFIX`",
        )
        assertTrue(
            "沙箱进程名应当以 `:` 开头（相对名，形如 `:sandbox`），现在是 `$name`。",
            name.startsWith(":"),
        )

        val all = MANIFEST_ATTR.findAll(manifestText()).map { it.groupValues[1] }.toList()
        assertEquals(
            "清单里 `android:process` 现在有 ${all.size} 处：$all —— 只允许一处（`:sandbox`）。\n" +
                "多出来的那处要回来判断：它该单独开进程，还是写错了？",
            listOf(name),
            all,
        )
    }

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
        /** `/* ... */`（KDoc 也是它）。`DOT_MATCHES_ALL` 让 `.` 跨行。 */
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

        /** `// ...` 到行尾。 */
        val LINE_COMMENT = Regex("//[^\n]*")

        /** `<!-- ... -->`。 */
        val XML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

        val KOTLIN_CONST = Regex("const val SANDBOX_PROCESS_SUFFIX\\s*=\\s*\"([^\"]+)\"")
        val MANIFEST_ATTR = Regex("android:process\\s*=\\s*\"([^\"]+)\"")

        fun kotlinText(): String =
            read(File(SourceScan.root, "app/src/main/java/com/aichat/PatchbayApp.kt"))
                .replace(BLOCK_COMMENT, "")
                .replace(LINE_COMMENT, "")

        fun manifestText(): String =
            read(File(SourceScan.root, "app/src/main/AndroidManifest.xml"))
                .replace(XML_COMMENT, "")

        fun read(file: File): String {
            assertTrue(
                "找不到文件：${file.absolutePath} —— `patchbay.sourceRoot` 没注入？",
                file.isFile,
            )
            return file.readText()
        }
    }
}
