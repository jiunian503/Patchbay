package com.aichat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * README 那句「出网请求只有 **N 种**」是**断言**，不是描述。
 *
 * 它写在「本地优先」一节里 —— 用户读那一段是为了判断「这个 App 会不会偷偷把我的
 * 东西发出去」。所以那句话错了，代价不是「文档不好看」，而是**用户基于错的信息
 * 做了一个关于隐私的决定**。
 *
 * ## 它守的是三处必须一致
 *
 * | # | 在哪 | 是什么 |
 * |---|---|---|
 * | 1 | 本文件的 [DECLARED] | 这个 App 有几种出网，各自实现在哪 |
 * | 2 | 生产代码 | 扫出来的实现处（[OKHTTP] / [WEBVIEW] 两个标记） |
 * | 3 | README 那一句 | 汉字数字 + 每一种都得写到 |
 *
 * ## 为什么值得一条测试
 *
 * **它已经错过一次了**（2026-09-21）：README 写着「六种」并列了六项，而代码里
 * 有**七个**出网点 —— 漏掉的是「从网址装插件」（`AppContainer.manifestHttpClient`）。
 * 那一处是**用户自己粘一个地址、我们去取**，去向由用户决定，恰恰是隐私声明里
 * 最该写出来的一种。它没被写出来，不是因为谁隐瞒，是因为**加那个功能时
 * 没人想到还要回来改这段话** —— 而当时没有任何东西会红。
 *
 * 这正是「枚举式断言每加一个成员就要回来改，改漏了不会有任何测试红」。
 * 现在会红了：**加一个 `OkHttpClient` 就会红**。
 *
 * ## 为什么是「读源码文本」而不是反射
 *
 * 反射看不到 README，也数不出「同一处有几种出网」——「出网」不是某个类型，
 * 是**几处互不相干的代码各自做的事**。所以这里扫文本。
 *
 * 代价是 Gradle 不知道这层依赖，得在 `app/build.gradle.kts` 里把
 * README 和所有模块的 `src/main` 声明成测试任务的输入 —— 否则只改文档
 * （或只改注释）时，任务会被判 UP-TO-DATE **整个跳过**，于是它会以
 * 「通过」的形式安静地失效。那是这个文件最容易漏掉的一步，也写在那边的注释里了。
 *
 * ## 空集合恒过
 *
 * 扫描要是哪天一个都匹配不到（改了写法、模块列表解析失败），下面所有断言都会
 * 「通过」得毫无意义。所以每一处都先断**不是空的** —— 而且匹配不到时**宁可红**：
 * 那说明这个文件要跟着改。
 *
 * ## 注释不算，代码算
 *
 * 扫描**按行**做，整行注释跳过 —— 规则和代价写在 [SourceScan.scan] 的 KDoc 里。
 * 那份实现是本文件和 `DeviceInfoOriginTest` 共用的一处，不是各写一遍。
 */
class NetworkEgressTest {

    private val readme: File = SourceScan.fileFromProperty("patchbay.readmeFile")

    // ---- 两个扫描标记 ------------------------------------------------

    /**
     * 「造一个 HTTP 客户端」的写法。
     *
     * 为什么认 `OkHttpClient.Builder()` 而不是 `OkHttpClient`：后者是个**类型名**，
     * 满地都是（参数类型、属性类型）；前者才是**真的在造一个客户端**。
     * 而「没走 Builder 直接 `OkHttpClient()`」这种绕开的写法由
     * [BYPASS_MARKERS] 兜着。
     */
    private val OKHTTP = "OkHttpClient.Builder()"

    /**
     * 「造一个 WebView」的写法。
     *
     * 带上 `(context)` 是为了避开 `WebView?>`、`WebView` 这类**只是提到类型**的地方
     * （`BrowserScreen` 的注释里就有几处）。代价是换个写法（`WebView(requireContext())`）
     * 就匹配不到 —— 但那时扫描会变空而红，红得明白。
     */
    private val WEBVIEW = "WebView(context)"

    // ---- 声明 --------------------------------------------------------

    /** 一种出网：README 里怎么指代它、代码里长什么样、实现在哪几个文件。 */
    private data class Egress(
        val readme: String,
        val marker: String,
        val sites: Map<String, Int>,
    )

    /**
     * **这个 App 的全部出网方式。** 加一种就在这里加一条 —— 同时去改 README。
     *
     * ⚠️ 这里只有**实现处**，没有「去向」：去向在运行时由用户配置决定
     * （服务商地址、搜索后端、`fetch_url` 的目标…），代码里查不到。
     * 所以这条测试证明的是「**出网的口子只有这几个**」，
     * 不是「数据只发到某几个域名」—— 后者是 README 用文字说清楚的。
     */
    private val DECLARED = listOf(
        // 对话：去用户填的那个服务商
        Egress(
            readme = "模型请求",
            marker = OKHTTP,
            sites = mapOf("network/src/main/kotlin/com/aichat/network/ProviderConfig.kt" to 1),
        ),
        // 联网搜索：内置 Bing 或用户自己配的后端
        Egress(
            readme = "联网搜索",
            marker = OKHTTP,
            sites = mapOf("tools/src/main/kotlin/com/aichat/tools/SearchWebTool.kt" to 1),
        ),
        // 抓网页正文：地址由模型挑，所以每次都弹框
        Egress(
            readme = "`fetch_url`",
            marker = OKHTTP,
            sites = mapOf("tools/src/main/kotlin/com/aichat/tools/FetchUrlTool.kt" to 1),
        ),
        // 插件自己发请求：声明式工具、MCP、脚本插件的 host.http —— 三条都过 NetworkGuard
        Egress(
            readme = "插件声明的白名单主机",
            marker = OKHTTP,
            sites = mapOf("app/src/main/java/com/aichat/di/PluginHttp.kt" to 1),
        ),
        // 「从网址装插件」：地址是用户粘进去的。⚠️ 就是这一条漂掉过
        Egress(
            readme = "从网址装插件",
            marker = OKHTTP,
            sites = mapOf("app/src/main/java/com/aichat/di/AppContainer.kt" to 1),
        ),
        // 「检查更新」：固定地址，只问 GitHub 的公开接口
        // （和上一条同在 AppContainer.kt —— 两个客户端各有各的理由，KDoc 写在那边）
        Egress(
            readme = "检查更新",
            marker = OKHTTP,
            sites = mapOf("app/src/main/java/com/aichat/di/AppContainer.kt" to 1),
        ),
        // 用户自己点开的链接：WebView，不经过 OkHttp，所以单列一个标记
        Egress(
            readme = "你自己点开的链接",
            marker = WEBVIEW,
            sites = mapOf("app/src/main/java/com/aichat/ui/browser/BrowserScreen.kt" to 1),
        ),
    )

    /**
     * **绕开上面两条路的写法，一个都不许有。**
     *
     * 上面那张表只证明「我认得的写法有这几种」；有人直接开 socket、或者不用
     * Builder 造客户端，表是看不见的 —— 这几条标记就是补那个缺口。
     *
     * 期望值恒为「一个都没有」。这条是**兜底**，不是证明：`import java.net.URL`
     * 之后再写 `URL(x)` 就绕过了 `java.net.URL(` 这个标记。够用就行 ——
     * 真有人要偷偷加一个出网点，绕不过「加了就该回来改这张表」这件事本身。
     */
    private val BYPASS_MARKERS = listOf(
        "HttpURLConnection(",
        "Socket(",
        "java.net.URL(",
        "OkHttpClient()",
    )

    // ---- 测试 --------------------------------------------------------

    @Test
    fun `声明的出网种类和代码里的实现处完全一致`() {
        for (marker in listOf(OKHTTP, WEBVIEW)) {
            val declared = expectedSites(marker)
            val actual = SourceScan.scan(marker)

            assertTrue(
                "扫 $marker 一个都没匹配到 —— 写法变了（或者模块列表解析失败），" +
                    "这条测试已经变成一句空话。先看本文件的扫描标记和 app/build.gradle.kts。",
                actual.isNotEmpty(),
            )

            assertEquals(
                "出网实现处对不上。左边是 [DECLARED] 声明的，右边是代码里扫出来的。\n" +
                    "多出来的 = 有人加了出网点却没回来改这张表（README 那句「只有 N 种」\n" +
                    "会因此变成一句错话，而用户读它就是为了判断隐私）；\n" +
                    "少了 = 表里记着一个早就不存在的出网点。\n" +
                    "两边都要一起改：本文件 + README 那段话。",
                declared,
                actual,
            )
        }
    }

    @Test
    fun `README 里写的种类数量就是声明的数量`() {
        val actual = DECLARED.size
        val stated = statedCount()

        assertNotNull(
            "在 README 里找不到「$SENTENCE N 种」那句话。\n" +
                "它可能被改写了措辞或挪走了 —— 那是**有意**要守的判据，别删；" +
                "要改措辞就一起改本文件的 STATED。",
            stated,
        )

        assertEquals(
            "README 说「出网请求只有 $stated 种」，[DECLARED] 里是 $actual 种。\n" +
                "这条断言存在的理由：它已经错过一次了 —— 2026-09-21 发现少了\n" +
                "「从网址装插件」那一条（`AppContainer.manifestHttpClient`）。\n" +
                "改的时候**数字和名单都要改**。",
            actual,
            stated,
        )
    }

    @Test
    fun `README 里每一种出网都写到了`() {
        val paragraph = readmeParagraph()

        for (egress in DECLARED) {
            assertTrue(
                "README 那一句里找不到「${egress.readme}」——\n" +
                    "种类数对得上不代表名单是对的：少写一种、多写一种都能凑出同样的数字。\n" +
                    "那一句现在是：\n$paragraph",
                paragraph.contains(egress.readme),
            )
        }
    }

    @Test
    fun `没有绕开 OkHttp 和 WebView 的出网写法`() {
        for (marker in BYPASS_MARKERS) {
            assertEquals(
                "生产代码里出现了 `$marker` —— 这是**绕开**了本文件认得的那两条出网路。\n" +
                    "要么改回 OkHttp / WebView，要么把它当成新的一种出网：\n" +
                    "在 [DECLARED] 里加一条，并去改 README 那句话。",
                emptyMap<String, Int>(),
                SourceScan.scan(marker),
            )
        }
    }

    // ---- 声明与实扫的对账 --------------------------------------------

    /** 把 [DECLARED] 里同一个标记的几处合成一张表（同一文件出现两次要相加）。 */
    private fun expectedSites(marker: String): Map<String, Int> =
        DECLARED.filter { it.marker == marker }
            .flatMap { it.sites.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, counts) -> counts.sum() }

    // ---- README ------------------------------------------------------

    /**
     * README 里那一整段话（从「出网请求只有」那行开始，到第一个空行为止）。
     *
     * 只取这一段而不是整份 README：整份里到处都是 `fetch_url`、`检查更新`
     * 这些词（工具表、功能列表里都有），拿整份去断言等于没断言。
     */
    private fun readmeParagraph(): String {
        val lines = readme.readText().split("\n")
        val start = lines.indexOfFirst { it.contains(SENTENCE) }

        assertTrue(
            "README 里找不到「$SENTENCE」那句话。它可能被改写了措辞或挪走了 —— " +
                "那是**有意**要守的判据，别删；要改措辞就一起改本文件的 SENTENCE。",
            start >= 0,
        )

        return lines.drop(start).takeWhile { it.isNotBlank() }.joinToString("\n")
    }

    /** 那一句里的 N。认不出来返回 `null`，调用方会红掉。 */
    private fun statedCount(): Int? {
        val raw = STATED.find(readmeParagraph())?.groupValues?.get(1) ?: return null
        return ChineseNumbers.parse(raw)
    }

    private companion object {
        /** 那一句话的开头 —— README 和本文件都靠它定位。 */
        const val SENTENCE = "出网请求只有"

        /** 那一句里的「N 种」。数字串的写法由 [ChineseNumbers.PATTERN] 统一。 */
        val STATED = Regex("""$SENTENCE(${ChineseNumbers.PATTERN})种""")
    }
}
