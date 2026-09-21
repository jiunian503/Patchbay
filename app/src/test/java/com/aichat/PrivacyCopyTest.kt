package com.aichat

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 讲「数据去哪了」的文案也是**断言** —— 而且是用户据以做隐私决定的那一种。
 *
 * ## 它守的是一句已经说错的话
 *
 * 首启引导（还没有服务商时那张提示卡）原本写着：
 *
 * > Key 只存在这台设备上，**不会经过任何服务器**。
 *
 * 前半句是真的（AndroidKeyStore 本地加密，见 `AndroidKeystoreSecretStore`）。
 * 后半句**和事实相反**：Key 会作为 `Authorization` 头，随每一个模型请求
 * 发往**用户自己填的那个地址**（`OpenAiChatClient` 里那一处）。
 *
 * 写的人想说的是「没有中转服务器」；读者读到的是「我的 Key 从不出这台手机」。
 * 而这句话出现的位置，恰恰是**用户第一次被要求粘贴 Key 的那一刻** ——
 * 他会拿这句话去决定「要不要把 Key 交给这个 App」。
 *
 * 说得准的说法只有一个：**把目的地写出来**（「请求直接从这台设备发往你填的地址」）。
 *
 * ## 还有一句是替模型打包票
 *
 * 联网搜索设置页里「关闭」那一项的说明原本是「模型只能靠自己的知识回答，
 * **遇到不知道的会说不知道**」。那是**替模型做承诺** —— 模型对不知道的事经常
 * 直接编一个。这一页其他四项都主动交代了缺点（Bing「随时可能失效」、SearXNG
 * 「通常意味着自己部署」），只有这一项只讲好处，而那个好处它保证不了。
 *
 * ## 为什么是「读源码文本」
 *
 * 这些句子活在 `@Composable` 里，单测拿不到；而它们的**真值**来自别处的代码
 * （Key 怎么发、发去哪）。所以扫文本 —— 和 [NetworkEgressTest] 同一个理由，
 * 也共用同一份构建配置（`patchbay.sourceRoot` / `patchbay.readmeFile`，以及
 * `app/build.gradle.kts` 里那两行 `inputs`：**不声明输入的话，只改文案不会
 * 让测试重跑，它会以「通过」的形式安静失效**）。
 *
 * ## ⚠️ 它守的是「我们想过这件事」，不是完备性
 *
 * 换个说法（「密钥从不离开手机」）就漏了。这条测试的价值在于：**下一次有人写
 * 这类句子时，[FORBIDDEN] 和 [NEEDS_DESTINATION] 会把「想一下」这件事摆到他
 * 面前**。真要抓全，得让文案从结构化数据生成 —— 那不是一个测试的活。
 *
 * 范围也刻意不含 `RELEASING.md` 与 `dist/<版本>/NOTES.md`：那些是**发版说明**，
 * 改已发布的说明是**对外动作**，不该被一条单测逼着改（见 `RELEASING.md`）。
 *
 * ## 空集合恒过
 *
 * 「某句话不该出现」这类断言，扫描一旦失效（属性没传进来、目录树变了）会
 * **通过得毫无意义**。所以每条都先 [assertScanWorks]。
 *
 * ## 扫描范围和 [NetworkEgressTest] 不同，是故意的
 *
 * 那边从 `settings.gradle.kts` 里解析模块名单（它要证明「出网的口子只有这几个」，
 * 漏一个模块就没说服力）。这里**整棵仓库树按 `src/main` 过滤** —— 比模块名单
 * **更宽**，而「禁语」这类守卫里宽一点没有代价：多扫到一份文件最多多一条误报，
 * 少扫一个模块却是静默放过。
 */
class PrivacyCopyTest {

    // ---- 判据 --------------------------------------------------------

    /**
     * **和事实相反、或者替别人打包票的句子，一处都不许有。**
     *
     * 每一条都是**具体发生过的那句话**，不是「一类写法」—— 换个说法就漏了
     * （见类注释）。加一条的时候把理由写在这儿，别只写句子。
     */
    private val FORBIDDEN = listOf(
        // Key 会发到用户填的服务商，所以「不经过任何服务器」是反话。
        // 想表达「没有中转」就写目的地。
        "不会经过任何服务器",
        // 替模型承诺它保证不了的行为。
        "遇到不知道的会说不知道",
    )

    /**
     * 这半句**单独看**是读得反的（Key 确实会发到用户填的那个服务商），
     * 但补上目的地之后就是准确的 —— 所以不禁它，改成要求**同一份文件里**
     * 必须出现 [MUST_NAME_DESTINATION]。
     */
    private val NEEDS_DESTINATION = "不会上传到任何服务器"

    /** 唯一的准确说法：**目的地**，而不是「不经过谁」。 */
    private val MUST_NAME_DESTINATION = "你填的地址"

    /** 扫描**没在看东西**的证据 —— 这几个词必须能在扫到的文本里找到。 */
    private val SANITY = listOf("API Key", "模型")

    // ---- 测试 --------------------------------------------------------

    @Test
    fun `没有和事实相反的说法`() {
        val files = scanned()
        assertScanWorks(files)

        for (phrase in FORBIDDEN) {
            val hits = files.filter { file -> file.code().any { line -> line.contains(phrase) } }
            assertTrue(
                "生产代码 / README 里出现了「$phrase」。\n" +
                    "这不是文风问题，它**和事实相反**：\n" +
                    "  · Key 会作为 `Authorization` 头发往用户填的那个地址" +
                    "（`OpenAiChatClient` 里那一处）；\n" +
                    "  · 模型对不知道的事经常直接编一个，不是「会说不知道」。\n" +
                    "要表达「没有中转」，就写**目的地**：「发往$MUST_NAME_DESTINATION」。\n" +
                    "命中的文件：${hits.joinToString { it.name }}",
                hits.isEmpty(),
            )
        }
    }

    @Test
    fun `说不会上传到任何服务器的地方都交代了真正的去向`() {
        val files = scanned()
        assertScanWorks(files)

        val claiming = files.filter { file -> file.code().any { it.contains(NEEDS_DESTINATION) } }
        assertTrue(
            "一处都没扫到「$NEEDS_DESTINATION」—— 要么这句被删了（那这条测试也该一起删），" +
                "要么扫描失效了。空集合会让下面的循环一句都不跑。",
            claiming.isNotEmpty(),
        )

        for (file in claiming) {
            assertTrue(
                "${file.name} 里说了「$NEEDS_DESTINATION」，却没交代**发去哪**。\n" +
                    "这半句单独看是读得反的：Key 确实会发到用户填的那个服务商。\n" +
                    "同一段里必须写出「$MUST_NAME_DESTINATION」—— " +
                    "说「不经过谁」不如说「去哪」准，而用户要判断的正是「去哪」。",
                file.code().any { line -> line.contains(MUST_NAME_DESTINATION) },
            )
        }
    }

    // ---- 扫描 --------------------------------------------------------

    private val sourceRoot: File = pathOf("patchbay.sourceRoot")
    private val readme: File = pathOf("patchbay.readmeFile")

    /**
     * 要扫的文本：各模块 `src/main` 下的 `.kt` + `README.md`。
     *
     * 整棵仓库树按 `/src/main/` 过滤，而不是先解析模块名单 —— 理由写在类注释里
     * （这里宁可宽）。
     */
    private fun scanned(): List<File> {
        assertTrue("`patchbay.sourceRoot` 指的不是目录：${sourceRoot.path}", sourceRoot.isDirectory)
        assertTrue("找不到 ${readme.path} —— `patchbay.readmeFile` 指错了", readme.isFile)

        val sources = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.invariantPath().contains("/src/main/") }
            .toList()
        assertTrue(
            "一份 `src/main` 下的 .kt 都没扫到 —— 目录结构变了，扫描会变成一句空话。",
            sources.isNotEmpty(),
        )
        return sources + readme
    }

    /**
     * 代码行（README 就是全文）。
     *
     * **整行注释不算** —— 这条规则是被自己逼出来的：要守的那几句话，恰恰
     * **必须**在注释里被提到（「这里不能说『不会经过任何服务器』」）。不跳注释
     * 的话，守卫会对着解释它自己的注释报警 —— 那会逼着人把注释写得含糊。
     * 代价和 [NetworkEgressTest] 那边一样：写在**行尾**注释里就漏了。
     */
    private fun File.code(): List<String> =
        readLines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /**
     * 扫描**真的在看东西**。
     *
     * 没有这一步，上面几条「不许出现」的断言在扫描失效时会全绿 —— 那是最坏的一种绿。
     */
    private fun assertScanWorks(files: List<File>) {
        for (word in SANITY) {
            assertTrue(
                "扫到的文本里连「$word」都找不到 —— 扫描没在看东西，" +
                    "下面那些「不许出现」的断言全是空话。先看本文件的 [scanned] 和 " +
                    "`app/build.gradle.kts` 的 `testOptions`。",
                files.any { file -> file.code().any { line -> line.contains(word) } },
            )
        }
    }

    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

    private fun pathOf(name: String): File {
        val raw = System.getProperty(name)
        assertNotNull(
            "构建配置没把 $name 传给测试 JVM —— 检查 app/build.gradle.kts 的 testOptions",
            raw,
        )
        return File(raw!!)
    }
}
