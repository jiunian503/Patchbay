package com.aichat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航图的三处说法必须**互相一致**。
 *
 * ## 它守的是哪三处
 *
 * | # | 在哪 | 是什么 |
 * |---|---|---|
 * | 1 | `NavigationKeys.kt` | 声明了几个 `: NavKey` 的类型 |
 * | 2 | `Navigation.kt` 的 `entryProvider { … }` | 注册了几个 `entry<X>` |
 * | 3 | `Navigation.kt` 的 `MainNavigation` KDoc | 写的是「**N 个目的地**」 |
 *
 * ## 为什么值得一条测试
 *
 * **1 和 2 不一致是真的会出事的**：有键没注册，`backStack.add(X)` 那一刻
 * `NavDisplay` 找不到对应的 entry —— 页面要么空白要么崩，而且崩在
 * recomposition 里（和 `NavigationBackStackTest` 那条同一个形状）。
 * 反过来「有 entry 没键」不会崩，但它是一段永远走不到的死代码。
 *
 * **3 漂过两次，而且两次都是「差一个」**：
 *
 * - 加 `CrashLogs` 的时候只加了键和 entry，没回来改 KDoc —— 于是它写着
 *   「十个目的地」而实际是十一个，名单里也没有崩溃记录。
 * - 加 `Browser` 的时候我照抄了那份残缺名单，把「十个」改成「十一个」——
 *   **还是差一个**。写完这条测试才发现。
 *
 * 这正是「枚举式断言每加一个成员就要回来改，改漏了不会有任何测试红」——
 * 现在会红了。
 *
 * ## 为什么不反射
 *
 * 反射能数出 1，数不出 3（**注释里的数字反射看不到**）。而 3 恰恰是
 * 唯一真的错过的那一处。所以这里读源码文本。
 *
 * 代价是「读文本」这件事 Gradle 不知道，得在 `app/build.gradle.kts` 里
 * 把这两个文件声明成测试任务的输入 —— 否则只改导航、不改测试源码时，
 * 任务会被判 UP-TO-DATE **整个跳过**，于是它会以「通过」的形式安静地失效。
 * 那是这个文件里最容易被漏掉的一步，也写在那边的注释里了。
 *
 * ## 空集合恒过
 *
 * 两个正则如果哪天一个都匹配不到（改了声明写法、把 `entry<X>` 挪进
 * 另一层函数），下面所有断言都会「通过」得毫无意义。所以先断言两边都
 * 不是空的 —— 而且匹配不到时**宁可红**：那说明这个文件要跟着改。
 */
class NavigationGraphTest {

    private val keysFile: File = fileFromProperty("patchbay.navigationKeysFile")
    private val navigationFile: File = fileFromProperty("patchbay.navigationFile")

    private fun fileFromProperty(name: String): File {
        val raw = System.getProperty(name)
        assertNotNull(
            "构建配置没把 $name 传给测试 JVM —— 检查 app/build.gradle.kts 的 testOptions",
            raw,
        )
        return File(raw!!).also {
            assertTrue("源文件不存在：${it.absolutePath}", it.isFile)
        }
    }

    /** `NavigationKeys.kt` 里声明的每一个导航键类型名。 */
    private fun declaredKeys(): Set<String> =
        NAV_KEY.findAll(keysFile.readText()).map { it.groupValues[1] }.toSet()

    /**
     * `Navigation.kt` 里注册的每一个目的地。
     *
     * 只认**整行就是** `entry<X> …` 的行。这样注释里提到 `entry<Chat>`
     * （那处注释是解释 `LocalUriHandler` 作用域的，非提到不可）不会被算进来。
     * 代价是换一种写法就会漏 —— 但漏了会变成「1 和 2 不一致」而红，
     * 红得明白，比悄悄放过强。
     */
    private fun registeredEntries(): Set<String> =
        navigationFile.readText().lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .mapNotNull { ENTRY.find(it)?.groupValues?.get(1) }
            .toSet()

    /** KDoc 里那句「**N 个目的地**」的 N。 */
    private fun statedCount(): Int? {
        val raw = STATED_COUNT.find(navigationFile.readText())?.groupValues?.get(1)
            ?: return null
        return chineseNumber(raw)
    }

    @Test
    fun `声明的导航键和注册的目的地完全一致`() {
        val keys = declaredKeys()
        val entries = registeredEntries()

        assertTrue(
            "在 ${keysFile.name} 里一个 `: NavKey` 都没匹配到 —— " +
                "声明写法变了（比如不再写 `: NavKey`），这条测试已经变成一句空话",
            keys.isNotEmpty(),
        )
        assertTrue(
            "在 ${navigationFile.name} 里一个 `entry<X>` 都没匹配到 —— " +
                "注册写法变了，这条测试已经变成一句空话",
            entries.isNotEmpty(),
        )

        assertEquals(
            "导航键和目的地对不上。左边是 NavigationKeys.kt 声明的，右边是 Navigation.kt 注册的。\n" +
                "多了键 = 走到那一页时 NavDisplay 找不到 entry（空白或崩，且崩在 recomposition 里）；\n" +
                "多了 entry = 一段永远走不到的死代码。",
            keys,
            entries,
        )
    }

    @Test
    fun `KDoc 里写的目的地数量就是实际数量`() {
        val actual = declaredKeys().size

        assertTrue(
            "NavigationKeys.kt 里一个 `: NavKey` 都没匹配到 —— 先看上面那条测试",
            actual > 0,
        )

        val stated = statedCount()
        assertNotNull(
            "在 ${navigationFile.name} 里找不到「**N 个目的地**」那句话。\n" +
                "它可能被改写了措辞或挪走了 —— 那是**有意**要守的判据，别删；" +
                "要改措辞就一起改这个文件里的 STATED_COUNT 正则。",
            stated,
        )

        assertEquals(
            "KDoc 说「$stated 个目的地」，实际声明了 $actual 个。\n" +
                "改目的地就回来改那句话（数字和名单都要改）。\n" +
                "这条断言存在的理由：它已经差一个漂过两次了 —— " +
                "一次是加 CrashLogs 时忘了改，一次是加 Browser 时照抄了残缺名单。",
            actual,
            stated,
        )
    }

    private companion object {
        /**
         * `@Serializable data object/class X … : NavKey`。
         *
         * 用非贪婪跨行匹配，是因为 `Chat` 那个声明带着三个参数的构造，
         * 换行写在 `) : NavKey` 上。
         */
        val NAV_KEY = Regex("""@Serializable\s+data\s+(?:object|class)\s+(\w+)[\s\S]*?:\s*NavKey""")

        /** 行首的 `entry<X>`。 */
        val ENTRY = Regex("""^entry<(\w+)>""")

        /** KDoc 里那句「**N 个目的地**」。 */
        val STATED_COUNT = Regex("""\*\*([一二三四五六七八九十]+)个目的地\*\*""")

        /**
         * 中文数字转整数，够这个项目用（一到九十九）。
         *
         * 只认 `一`…`九十九`：导航目的地到不了三位数，真到了也该改这句话的写法
         * （写阿拉伯数字更好认），而不是把这个函数写复杂。
         * 认不出来返回 `null`，调用方会以「找不到那句话」红掉 —— 不会静默放过。
         */
        fun chineseNumber(s: String): Int? {
            val digits = mapOf(
                '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
                '六' to 6, '七' to 7, '八' to 8, '九' to 9,
            )
            if (s == "十") return 10
            val ten = s.indexOf('十')
            if (ten < 0) return digits[s.singleOrNull()] ?: return null
            val tens = if (ten == 0) 1 else digits[s[0]] ?: return null
            val onesText = s.substring(ten + 1)
            val ones = if (onesText.isEmpty()) 0 else digits[onesText.single()] ?: return null
            return tens * 10 + ones
        }
    }
}
