package com.aichat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用 `LocalUriHandler` 的地方就是这几个」是**断言**，不是描述。
 *
 * ## 它守的是一个「两类待遇」的横切设计
 *
 * `Navigation.kt` 用**换掉 `LocalUriHandler` 的提供值**来把聊天页里的链接关进
 * 内置浏览器，而不是弹出去。那个 `CompositionLocalProvider` **只包 `entry<Chat>`** ——
 * 于是「用 `LocalUriHandler.current` 的地方」被分成两类：
 *
 * | 在哪 | 拿到的是 | 该去哪 |
 * |---|---|---|
 * | 聊天页里的 Markdown 链接 | 被换过的那个 | **内置浏览器** |
 * | 其余（设置页「去下载」、内置浏览器页顶栏） | Activity 提供的那个 | **系统浏览器** |
 *
 * 这个分类是**有意**的：「去下载」的目的是装 APK，WebView 既下不了也装不了。
 *
 * ## 为什么值得一条测试
 *
 * 原来那句话写的是「全项目用 `LocalUriHandler` 的**还有一处**」—— 实际是**两处**。
 * 写那句话时还没有内置浏览器页，后来加了，**没有人回来改它**，也没有任何东西会红。
 * 这正是「注释里的数量会漂，而漂了不会有东西说话」那个形状
 * （同类的还有 `NavigationGraphTest` 守的「N 个目的地」，那边**已经漂过两次**）。
 *
 * ## 红的时候要做什么
 *
 * 不是「把新路径加进表里」就完事 —— 要回答**新那处在不在 `entry<Chat>` 作用域里**：
 *
 * - 在（聊天页内部）⇒ 它会走内置浏览器，符合预期
 * - 不在 ⇒ 它走系统浏览器；若那不是想要的（希望它在 App 内打开），得改作用域，
 *   而那会**一起**影响别的页
 *
 * ## 它按**文件**去重，不数「用了几次」
 *
 * 待遇由**文件所在的作用域**决定，不由出现次数决定 —— 所以同一个文件里再写一处
 * `LocalUriHandler.current` **不会红**（那是无害的）。只有**换一个文件**才会。
 *
 * ## 空集合恒过
 *
 * 扫描要是匹配不到（改了 API），下面的断言会「通过」得毫无意义 ⇒ 先断不是空的。
 * 扫描设施在 [SourceScan]（和 `NetworkEgressTest` / `DeviceInfoOriginTest` 共用一份）。
 */
class UriHandlerScopeTest {

    @Test
    fun `用 LocalUriHandler 的地方就是表里这几个`() {
        val actual = SourceScan.scanFiles(MARKER)

        assertTrue(
            "生产代码里一个 `$MARKER` 都没扫到 —— 写法变了（比如改用了别的 API），" +
                "这条断言会变成空转。要改写法就一起改本文件的标记。",
            actual.isNotEmpty(),
        )

        assertEquals(
            "用 `$MARKER` 的地方和表里对不上。\n" +
                "多出来的 = 有人新加了一处 —— 回来判断它在不在 `entry<Chat>` 作用域里" +
                "（见 Navigation.kt 那段注释），再更新本表；\n" +
                "少了 = 表里记着一处早就不存在的用法。",
            EXPECTED,
            actual,
        )
    }

    private companion object {
        const val MARKER = "LocalUriHandler.current"

        /**
         * 生产代码里用 `LocalUriHandler.current` 的全部地方。
         *
         * ⚠️ 这四处的**待遇不一样**（两类，见类 KDoc 那张表）——
         * 加新的一处时，先回答「它在不在 `entry<Chat>` 里」。
         */
        val EXPECTED = setOf(
            // 包装处：取系统那个，再包一层只在 entry<Chat> 里生效的
            "app/src/main/java/com/aichat/Navigation.kt",
            // 消费者：在 entry<Chat> 里 ⇒ 拿到的是被换过的（内置浏览器）
            "app/src/main/java/com/aichat/ui/chat/MarkdownText.kt",
            // 消费者：不在作用域里 ⇒ 系统浏览器（「去下载」要装 APK）
            "app/src/main/java/com/aichat/ui/settings/ProviderListScreen.kt",
            // 消费者：不在作用域里 ⇒ 系统浏览器（顶栏「用浏览器打开」）
            "app/src/main/java/com/aichat/ui/browser/BrowserScreen.kt",
        )
    }
}
