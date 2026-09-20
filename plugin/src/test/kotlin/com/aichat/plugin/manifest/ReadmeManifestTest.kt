package com.aichat.plugin.manifest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `README.md` 里的示例清单必须**真能装**。
 *
 * ## 为什么值得一条测试
 *
 * README 是插件作者看到的第一份文档 —— 他多半不会先去读 `manifest.schema.json`，
 * 而是照着 README 里那段 JSON 抄一份、改改 `baseUrl` 就装上。
 *
 * 于是「README 里的例子漂移了」的代价和别的文档不一样：**它会直接传染**。
 * 作者抄出一份装不上的清单，报错指向的是他自己的字段名，他不会想到是文档错了。
 *
 * 这不是假设。写这份 README 的时候那段示例就漏了 `request` ——
 * 而声明式插件的每个工具都必须给 `request`（`ManifestParser.checkToolRequest`），
 * 漏了是 **Error**，装不上。当时它看着完全正常：字段名对、格式对、
 * 拿去问任何一个人都挑不出毛病，只有真的解析一遍才知道。
 *
 * ## 为什么不写成一份「校验规则」的副本
 *
 * 最容易想到的做法是写个 Python 脚本按 schema 逐字段对一遍。**那是复制品**：
 * 规则改了它不会跟着改，于是它会和 README 一起漂移，而且漂移的方向是
 * 「脚本说没问题」—— 比没有测试更糟，因为它是绿的（§38）。
 *
 * 所以这里直接把示例喂给**真的** `ManifestParser` —— 和宿主安装插件时走的是
 * 同一个函数、同一套规则。README 要么和宿主一致，要么测试红，没有第三种状态。
 *
 * ## 空集合恒过
 *
 * 遍历一个空集合的测试永远通过。README 被改名、代码块的语言标注从 `json`
 * 换成 `JSON`、或者有人把示例删了 —— 都会让这条测试以「通过」的形式安静地失效。
 * 所以先断言「找到了几段」，再断言它们能装。
 */
class ReadmeManifestTest {

    private val readme: File = run {
        val raw = System.getProperty("patchbay.readmeFile")
        assertNotNull(
            "构建配置没把 patchbay.readmeFile 传给测试 JVM —— " +
                "检查 plugin/build.gradle.kts 的 tasks.test",
            raw,
        )
        File(raw!!).also {
            assertTrue("README 不存在：${it.absolutePath}", it.isFile)
        }
    }

    /**
     * README 里所有以 ` ```json ` 开头的代码块内容。
     *
     * 收**全部**而不是只收第一段：以后 README 里多出第二份示例（比如脚本形态那份），
     * 它会自动被这条测试覆盖，不用记得回来改这里。
     */
    private fun jsonBlocks(): List<String> {
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        var inside = false

        readme.readText().lineSequence().forEach { line ->
            val isFence = line.trimStart().startsWith("```")
            when {
                !inside && isFence && line.trim().removePrefix("```").trim() == "json" -> {
                    inside = true
                    current.clear()
                }

                inside && isFence -> {
                    inside = false
                    blocks += current.toString()
                }

                inside -> current.appendLine(line)
            }
        }
        return blocks
    }

    @Test
    fun `README 里的每一段 json 示例都能通过宿主校验`() {
        val blocks = jsonBlocks()

        assertTrue(
            "在 ${readme.absolutePath} 里一段 ```json 代码块都没找到。" +
                "README 被改名了、或者代码块的语言标注换了写法（`JSON` / `jsonc`），" +
                "都会让这条测试变成一句空话",
            blocks.isNotEmpty(),
        )

        val bad = blocks.mapIndexedNotNull { index, json ->
            val check = ManifestParser.parse(json)
            // Error 是「装不上」，Warning 是「装得上但文档在示范一件会被提醒的事」。
            // 两种都收：README 里的例子该是范例，不该只是勉强能跑。
            if (check.problems.isEmpty()) {
                null
            } else {
                "第 ${index + 1} 段 json 示例：\n${check.report()}"
            }
        }

        assertEquals(
            "README 里有示例清单通不过宿主校验。它是插件作者照着抄的参考版，" +
                "坏掉会直接传染给第三方作者 —— 而报错会指向他自己抄下来的字段名，" +
                "他不会想到是文档错了。\n" +
                "（Error = 装不上；Warning = 装得上，但文档在示范一件会被提醒的事，同样该修。）\n",
            emptyList<String>(),
            bad,
        )
    }

    /**
     * README 里所有相对链接都指向真实存在的路径。
     *
     * 守的是我刚犯过的那一类错：示例里写 `plugin/src/main/resources/manifest.schema.json`，
     * 而 schema 其实在 `plugin/manifest.schema.json`。**路径写错不会报错**，
     * 读者点进去是 404，或者照着这个路径去仓库里找、找不到，然后放弃。
     *
     * 只查仓库内的相对链接；`http(s)` 和 `#锚点` 跳过（外链会烂，但那不是本地能判的）。
     */
    @Test
    fun `README 里的相对链接都指向真实存在的路径`() {
        val links = MARKDOWN_LINK.findAll(readme.readText())
            .map { it.groupValues[1].trim() }
            .filterNot { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("#") }
            .map { it.substringBefore('#') }
            .filter { it.isNotBlank() }
            .toList()

        assertTrue(
            "README 里一个仓库内相对链接都没有 —— 空集合恒过，" +
                "这条断言就是为了不让它变成一句空话",
            links.isNotEmpty(),
        )

        val missing = links.filterNot { File(readme.parentFile, it).exists() }

        assertEquals(
            "README 里有指向不存在路径的相对链接。改了文件名、挪了目录之后没人会发现，" +
                "读者点进去是 404：",
            emptyList<String>(),
            missing,
        )
    }

    private companion object {
        /** `[文字](目标)`。README 里的链接不嵌套、不含转义括号，够用。 */
        val MARKDOWN_LINK = Regex("""\[[^\]]*]\(([^)]+)\)""")
    }
}
