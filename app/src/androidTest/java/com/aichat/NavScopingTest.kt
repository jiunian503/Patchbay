package com.aichat

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Nav3 的 entry 作用域。
 *
 * ## 为什么需要这个测试
 *
 * Nav3 的 `NavDisplay` **默认不带 ViewModelStore 装饰器**（默认只有
 * `SaveableStateHolder` 和 `SceneSetup` 两个，翻 `navigation3-ui` 的字节码
 * 对 `ViewModelStoreOwner` 的引用是零）。不显式加上
 * `rememberViewModelStoreNavEntryDecorator()` 的话，每个 entry 里的
 * `LocalViewModelStoreOwner` 一路向上找到的是 **Activity**。
 *
 * 这个错误**不会报任何错**，只会让状态串页。实测踩到的样子是：
 *
 * > 在插件列表点「安装插件」→ 出现的是某个插件的详情页。
 *
 * 原因是安装页的 ViewModel 活过了它自己的页面：上次装完留下的
 * `result` 还在，新页面一组合就被 `LaunchedEffect` 拿去再触发一次导航。
 * 当时第一反应是「按钮绑错了回调」，绕了一圈才发现是作用域。
 *
 * ## 测什么
 *
 * 两条断言，分别对应「装饰器装了」和「出栈会清」：
 *
 * 1. entry 里的 `ViewModelStoreOwner` 和 Activity 的**不是同一个**。
 * 2. 同一个目的地出栈后再进来，拿到的 ViewModel **是新实例**。
 *
 * 两者都不依赖具体页面，所以即使以后导航图大改，这个测试也不用跟着改。
 *
 * ## 方法名里为什么没有空格
 *
 * **instrumented 测试会被 dex，而 DEX 版本低于 040（API 30）时方法名的
 * SimpleName 里不允许有空格。** minSdk 28 → `dexBuilderDebugAndroidTest`
 * 报 `Space characters in SimpleName '...' are not allowed prior to
 * DEX version 040`。JVM 单测不走 dex，所以那边中文带空格随便写，
 * 这个约束只对 `src/androidTest` 成立。
 */
@RunWith(AndroidJUnit4::class)
class NavScopingTest {

    @get:Rule
    val rule = createComposeRule()

    /** 只为拿一个「有没有被复用」可比较的身份，不承载任何逻辑。 */
    class Marker : ViewModel()

    /** 两个假目的地。用不同的类型，`entry<...>` 才分得开谁是谁。 */
    private data object Home
    private data object Install
    private data object Detail

    @Test
    fun `entry里的ViewModelStoreOwner不是Activity那一个`() {
        var outer: ViewModelStoreOwner? = null
        var inner: ViewModelStoreOwner? = null

        rule.setContent {
            // NavDisplay 之外拿到的就是宿主 Activity —— 也就是漏装装饰器时
            // entry 里会拿到的那个
            val outerOwner = LocalViewModelStoreOwner.current
            SideEffect { outer = outerOwner }

            val backStack = remember { mutableStateListOf<Any>(Home) }
            NavDisplay(
                backStack = backStack,
                entryDecorators = navEntryDecorators(),
                entryProvider = entryProvider {
                    entry<Home> {
                        val owner = LocalViewModelStoreOwner.current
                        SideEffect { inner = owner }
                        Text("home")
                    }
                },
            )
        }

        rule.waitForIdle()

        val outerOwner = outer
        val innerOwner = inner
        assertNotNull("NavDisplay 外面应该能拿到宿主的 ViewModelStoreOwner", outerOwner)
        assertNotNull("entry 里应该能拿到 ViewModelStoreOwner", innerOwner)
        assertNotSame(
            "entry 里的 ViewModelStoreOwner 和宿主是同一个 —— " +
                "说明 navEntryDecorators() 里少了 rememberViewModelStoreNavEntryDecorator()，" +
                "所有页面的 ViewModel 都会变成 Activity 作用域（状态会串页，且不报错）",
            outerOwner,
            innerOwner,
        )
        assertNotSame(
            "两个 owner 虽然不是一个对象，但共用同一个 ViewModelStore —— 等于没隔离",
            outerOwner!!.viewModelStore,
            innerOwner!!.viewModelStore,
        )
    }

    @Test
    fun `同一个目的地出栈后再进来会拿到新的ViewModel`() {
        val backStack: SnapshotStateList<Any> = mutableStateListOf(Install)
        val seen = mutableListOf<Marker>()

        rule.setContent {
            NavDisplay(
                backStack = backStack,
                entryDecorators = navEntryDecorators(),
                entryProvider = entryProvider {
                    // 只有 Install 记录 —— Detail 用另一个类型，免得它
                    // 也往 seen 里塞一个，把「访问了几次」数乱
                    entry<Install> {
                        val marker: Marker = viewModel()
                        SideEffect { seen += marker }
                        Text("install")
                    }
                    entry<Detail> { Text("detail") }
                },
            )
        }
        rule.waitForIdle()

        // 走一趟真实路径：装完跳到详情页（安装页被从返回栈里去掉），
        // 再从详情页返回列表、重新点「安装插件」
        rule.runOnIdle {
            backStack.removeLastOrNull()
            backStack.add(Detail)
        }
        rule.waitForIdle()
        rule.runOnIdle {
            backStack.removeLastOrNull()
            backStack.add(Install)
        }
        rule.waitForIdle()

        assertEquals(
            "安装页被访问了两次，却只记录到 ${seen.size} 个 ViewModel，导航没走通",
            2,
            seen.size,
        )
        assertNotSame(
            "出栈后重新进入同一个目的地，拿到的还是上次那个 ViewModel —— " +
                "它里面的状态（比如「刚刚装好了」这个结果）会再触发一次导航， " +
                "用户就会看到「点安装插件却进了详情页」",
            seen.first(),
            seen.last(),
        )
    }
}
