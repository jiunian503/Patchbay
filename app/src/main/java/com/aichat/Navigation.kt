package com.aichat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.aichat.di.AppContainer
import com.aichat.ui.browser.BrowserScreen
import com.aichat.ui.browser.isWebUrl
import com.aichat.ui.characters.CharacterEditScreen
import com.aichat.ui.characters.CharacterListScreen
import com.aichat.ui.chat.ChatScaffold
import com.aichat.ui.conversations.ConversationSearchScreen
import com.aichat.ui.crash.CrashLogScreen
import com.aichat.ui.plugins.PluginDetailScreen
import com.aichat.ui.plugins.PluginInstallScreen
import com.aichat.ui.plugins.PluginListScreen
import com.aichat.ui.settings.ProviderEditScreen
import com.aichat.ui.settings.ProviderListScreen
import com.aichat.ui.settings.WebSearchSettingsScreen
import com.aichat.ui.terminal.TerminalScreen

/**
 * `NavDisplay` 的 entry 装饰器。
 *
 * ## 为什么是「默认值 + 一项」
 *
 * `NavDisplay(backStack = ...)` 这个重载的默认值是
 * **`listOf(rememberSaveableStateHolderNavEntryDecorator())`，只有一项**。
 * （看 `navigation3-ui` 的字节码：默认分支里只调用了
 * `rememberSaveableStateHolderNavEntryDecorator`。
 * 那个 `SceneSetupNavEntryDecorator` 属于另一套基于 `SceneState` 的重载，
 * 跟这个重载无关 —— 别照着「默认有两个」去补，补不进来也补不对。）
 *
 * 而 `entryDecorators` 是**整体替换**，不是追加。所以这里把默认的那一项
 * 原样带上，再补上缺的那一项：
 *
 * - [rememberSaveableStateHolderNavEntryDecorator]：默认值，照抄。
 * - [rememberViewModelStoreNavEntryDecorator]：**缺的就是它**。
 *   它提供 per-entry 的 `LocalViewModelStoreOwner`，ViewModel 才会
 *   随 entry 出栈而销毁。
 *
 * 顺序上最后一个离内容最近，只有 VM 那个提供 `LocalViewModelStoreOwner`，
 * 所以放哪都行 —— 放最后是为了让「哪个 owner 生效」一眼可见。
 *
 * 单独抽成函数是为了可测：`NavScopingTest` 会拿它渲染一个最小
 * `NavDisplay`，断言 entry 里的 `LocalViewModelStoreOwner`
 * **不是** Activity 那一个。
 */
@Composable
fun navEntryDecorators(): List<NavEntryDecorator<Any>> = listOf(
    rememberSaveableStateHolderNavEntryDecorator(),
    rememberViewModelStoreNavEntryDecorator(),
)

/**
 * 返回栈「能不能弹」—— 抽成纯函数是为了能直接单测（`NavigationBackStackTest`）。
 *
 * 判据是 **`size > 1`**，不是 `isNotEmpty()`：`NavDisplay(backStack = …)` 的第一行
 * 就是 `require(backStack.isNotEmpty())`，空栈它会直接抛
 * `IllegalArgumentException: NavDisplay backstack cannot be empty`。
 * 来龙去脉见 [MainNavigation] 上面「返回栈：永远不弹空」那一段。
 */
internal fun canPopBackStack(size: Int): Boolean = size > 1

/**
 * 唯一的出栈入口。**栈里只剩一项时什么都不做** —— 那一项就是首页，
 * 这时按返回该由系统把 Activity 结束掉（Nav3 的 `isBackEnabled` 在栈深 1 时是
 * false，它不拦，事件会走到系统默认处理），而不是把这个栈弹空。
 *
 * 为什么收成一个函数、而不是在十二处 `onBack` 里各写一遍：漏掉任何一处都是
 * **同一个崩溃**，而且崩在 recomposition 里，堆栈上看不出是谁弹的。
 *
 * ⚠️ **不含**「先弹再压」那种换栈顶的写法（`onSwitchConversation` /
 * `onNewConversation` / `onInstalled`）—— 那三处是 `removeLastOrNull()` 紧跟
 * `add(...)`，**在同一段同步代码里**；Compose 的重组按帧调度，中间那个瞬时空栈
 * 没有观察者看得到，所以照旧不动。
 */
internal fun <T> popBackStack(backStack: MutableList<T>) {
    if (canPopBackStack(backStack.size)) backStack.removeLastOrNull()
}

/**
 * 导航图。**十三个目的地**，没有嵌套。
 *
 * （对话 / 内置浏览器 / 搜索 / 服务商列表 / 服务商编辑 / 联网搜索 / 插件列表 /
 * 插件安装 / 插件详情 / 角色列表 / 角色编辑 / 崩溃记录 / 终端）
 *
 * ⚠️ 上面那两行**不是装饰**：它漂过两次 —— 加 `CrashLogs` 时只加了键和 entry、
 * 没回来改这里（于是「十个」其实一直是十一个，名单里也没有崩溃记录）；
 * 加 `Browser` 时我照抄了那份残缺名单，把它从「十个」改成「十一个」——
 * **还是差一个**。现在由 `NavigationGraphTest` 机械地守着（见那个文件）。
 * 改目的地就跟着改这里，测试会告诉你改漏了。
 *
 * ## 起始目的地是「上次那个会话」，不是列表
 *
 * 对话页就是首页。冷启动落在 `Chat(上次退出的会话 id)` —— 见下面
 * [startConversationId] 的注释。会话列表不再是一个目的地，
 * 它是对话页左边的抽屉（`ConversationDrawer`）。
 *
 * ## 换会话是替换栈顶，不是入栈
 *
 * `onSwitchConversation` 会先 `removeLastOrNull()` 再 `add`。
 * 入栈的话用户逛过五个会话之后要按五次返回才能退出 App，
 * 而每一次返回看到的都是一个他已经不关心的旧会话。
 *
 * 搜索页是**压**在对话页上面的（不是替换）—— 从搜索结果点进去之后，
 * 返回要能回到结果列表，那是用户会反复用的一条路径。
 * 所以对话页顶栏左边会按返回栈深度决定给汉堡还是返回箭头。
 *
 * ## 依赖怎么传
 *
 * [AppContainer] 直接作为参数往下传，不用 CompositionLocal。
 * 原因：它只在 Activity 这一层出现一次，传一层就能到每个屏幕；
 * 用 CompositionLocal 反而会把「谁依赖什么」藏起来，
 * 屏幕的可测性也会变差（测试时要记得提供局部值）。
 *
 * ## 返回栈：**永远不弹空**
 *
 * 出栈一律走 [popBackStack]，它只在栈深 > 1 时才弹。
 *
 * ⚠️ 这里原来写的是「用 `removeLastOrNull()` 而不是 `navigateUp()`：
 * 越界时返回 null 而不是抛异常 —— 在『连按两次返回』这种竞态下更稳」。
 * **那句话瞄错了异常。** `removeLastOrNull()` 防的是 `NoSuchElementException`，
 * 而 Nav3 真正会抛的是 `IllegalArgumentException: NavDisplay backstack cannot be empty`
 * —— `NavDisplay(backStack = …)` 的第一行就是 `require(backStack.isNotEmpty())`。
 * 也就是说：**空栈它照样崩**，而且崩在 recomposition 里（堆栈上是
 * `Recomposer.performRecompose` → `NavDisplay`），看不出是谁弹的。
 *
 * 线上撞到过一次（v1.2）。两条路都能把栈弹空，[popBackStack] 一次堵住两条：
 *
 * 1. **屏幕自己的返回箭头**（下面那些 `onBack = …`）**不受 Nav3 的
 *    `isBackEnabled` 管** —— 那个门控只覆盖系统返回。出栈转场期间旧页面
 *    仍然可点，连点两下就是连弹两次。
 * 2. Nav3 内部 `onBackCompleted` 里的
 *    `repeat(entries.size - scene.previousEntries.size) { onBack() }` ——
 *    它自己在源码注释里承认 `enabled` 可能「在同一帧里过期」，
 *    那时它按**旧的** entries 数去弹**新的**栈。
 *
 * 判据与还原过程见 SKILL.md **§98**。
 *
 * 注意没有 `safeDrawingPadding()`：每个屏幕都有自己的 Scaffold，
 * 而 Scaffold 会自己处理系统栏内边距。外面再包一层会导致顶栏被推下去
 * 两次，出现一条莫名其妙的空白。
 *
 * ## `entryDecorators` 为什么必须显式写
 *
 * **因为 Nav3 的默认值里没有 ViewModelStore 装饰器。**
 *
 * `NavDisplay(backStack = ...)` 的 `entryDecorators` 默认只有
 * `listOf(rememberSaveableStateHolderNavEntryDecorator())` ——
 * 翻遍 `navigation3-ui` 的字节码，它对 `ViewModelStoreOwner` 的引用是**零**。
 * 也就是说，每个 entry 里的 `LocalViewModelStoreOwner` 一路向上找到的
 * 是 **Activity**，所有屏幕的 ViewModel 都是 Activity 作用域。
 *
 * 这不是「多占一点内存」，它会直接产生错的行为：
 *
 * - 安装页的 ViewModel 活过了它自己的页面。用户装完插件被跳到详情页，
 *   返回列表、再点「安装插件」，新页面拿到的是**上次那个实例** ——
 *   `state.result` 还在，`LaunchedEffect` 立刻又触发一次导航，
 *   于是「点安装插件」的结果是**被弹到某个插件的详情页**。
 * - 详情页、编辑页要是没写显式 key，打开第二个条目会看到第一个的数据。
 *
 * 实测踩到过第一个：`tap 安装插件` → 出现的是详情页，而且不报任何错。
 *
 * 加上这个装饰器之后，每个 entry 有自己的 ViewModelStore，
 * 出栈时随 entry 一起清掉（`removeViewModelStoreOnPop` 默认在
 * 非配置变更时返回 true）。**这是 Nav3 的设计意图，不是可选项** ——
 * 不写就等于默认所有页面共用一个 ViewModel 作用域。
 *
 * 这份列表抽成 [navEntryDecorators] 是为了能被 `NavScopingTest` 直接引用：
 * 漏掉一项不会编译失败、也不会报错，只会让所有页面的 ViewModel
 * 悄悄变成 Activity 作用域。
 */
@Composable
fun MainNavigation(container: AppContainer) {
    // 起始目的地：**上次退出时人在的那个会话**。
    //
    // 读的是 SharedPreferences（同步 API），所以这里直接拿得到，不需要先渲染
    // 一屏「加载中」再跳过去 —— 那会闪一下，而且进程被回收后重建还会闪第二次。
    //
    // 没有记录（首次安装，或者上次那个刚被删）就现生成一个 id：
    // `newConversationId()` 只生成 id、**不建会话行**（会话行要等第一条消息
    // 发出去才由 `ensureConversation` 创建），所以这里不会在库里留下空会话。
    val startConversationId =
        remember { container.settings.lastConversationId() ?: container.newConversationId() }
    val backStack = rememberNavBackStack(Chat(startConversationId))

    // ── 消息里的链接：在 App 内打开，不弹出去 ────────────────────────────────
    //
    // 做法是**换掉 `LocalUriHandler` 的提供值**，而不是给 `MarkdownText` 加一个
    // 回调参数。理由：那条路上取 URL 的地方只有一处 —— `MarkdownText.InlineText`
    // 里的 `LocalUriHandler.current` —— 而它到 `ChatScaffold` 之间隔着
    // `ChatScreen` / 消息列表 / 气泡好几层。为一个回调穿这么多层，每层都要多一个
    // 参数，而且**漏传一层不报错**，只会让链接又悄悄弹出去。
    //
    // ⚠️ **作用域只包 `entry<Chat>`**，这是有意的：全项目用 `LocalUriHandler` 的
    // 还有一处 —— 设置页「检查更新」里的「去下载」—— 它**必须**留在系统浏览器里。
    // 那个动作的目的是下载 APK 再安装，WebView 既下不了也装不了
    // （见 `ProviderListScreen.UpdateAvailableDialog` 的 KDoc）。包到
    // `NavDisplay` 外面就会把它一起改掉。
    val systemUriHandler = LocalUriHandler.current
    val inAppUriHandler = remember(systemUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                // 网页 → 内置页；其余 scheme（`mailto:` / `tel:` …）交回系统 ——
                // 那些是「另一个 App 的活」，硬塞进 WebView 只会白屏
                if (isWebUrl(uri)) {
                    backStack.add(Browser(uri))
                } else {
                    // ⚠️ 必须包 `runCatching`：`AndroidUriHandler.openUri` **不吞异常**，
                    // 它把 `ActivityNotFoundException` 包成 `IllegalArgumentException`
                    // 再抛。设备上没有能处理 `mailto:` / `tel:` 的应用时，
                    // 不包就是**直接崩在点击回调里**（实测见 §103）。
                    // 和 `MarkdownText.InlineText` 那边同一个处理：打不开就算了。
                    runCatching { systemUriHandler.openUri(uri) }
                }
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { popBackStack(backStack) },
        entryDecorators = navEntryDecorators(),
        entryProvider =
            entryProvider {
                entry<Chat> { key ->
                    CompositionLocalProvider(LocalUriHandler provides inAppUriHandler) {
                        // 记下「用户最后在哪个会话里」，下次冷启动直接进这里。
                        //
                        // 放在导航层而不是 ChatViewModel：这是「恢复上次位置」这件事，
                        // 属于导航的职责。ViewModel 不该关心 App 下次怎么启动。
                        LaunchedEffect(key.conversationId) {
                            container.settings.setLastConversationId(key.conversationId)
                        }

                        ChatScaffold(
                            container = container,
                            conversationId = key.conversationId,
                            highlightMessageId = key.highlightMessageId,
                            highlightQuery = key.highlightQuery,
                            // 返回栈里只有它自己 → 它就是首页 → 顶栏给汉堡；
                            // 否则是从搜索页压上来的 → 给返回箭头
                            showBack = backStack.size > 1,
                            onBack = { popBackStack(backStack) },
                            // 换会话是**替换**栈顶，不是压在它上面：压上去的话，
                            // 用户逛过五个会话之后要按五次返回才能退出 App，
                            // 而每一次返回看到的都是一个他已经不关心的旧会话
                            onSwitchConversation = { id ->
                                backStack.removeLastOrNull()
                                backStack.add(Chat(id))
                            },
                            onNewConversation = {
                                backStack.removeLastOrNull()
                                backStack.add(Chat(container.newConversationId()))
                            },
                            onOpenSearch = { backStack.add(ConversationSearch) },
                            onOpenSettings = { backStack.add(ProviderList) },
                            // 首屏那条「还没有可用的服务商」提示条直接进编辑页，
                            // 而不是先到设置页 —— 理由见 SetupHint 的 KDoc
                            onOpenProviderEdit = { providerId ->
                                backStack.add(ProviderEdit(providerId))
                            },
                        )
                    }
                }

                entry<Browser> { key ->
                    BrowserScreen(
                        url = key.url,
                        onBack = { popBackStack(backStack) },
                    )
                }

                entry<ConversationSearch> {
                    ConversationSearchScreen(
                        container = container,
                        onBack = { popBackStack(backStack) },
                        // 带上 messageId，对话页会把列表滚到命中的那一条。
                        // 只跳到会话底部是不够的 —— 长会话里还得自己翻。
                        // query 一起带过去：定位到了但正文里不标出那个词，
                        // 用户还是得自己找，等于只做了半件事。
                        onOpenConversation = { conversationId, messageId, query ->
                            backStack.add(Chat(conversationId, messageId, query))
                        },
                    )
                }

                entry<ProviderList> {
                    ProviderListScreen(
                        container = container,
                        onBack = { popBackStack(backStack) },
                        onEdit = { providerId -> backStack.add(ProviderEdit(providerId)) },
                        onOpenPlugins = { backStack.add(PluginList) },
                        onOpenWebSearch = { backStack.add(WebSearchSettings) },
                        onOpenCrashLogs = { backStack.add(CrashLogs) },
                        onOpenCharacters = { backStack.add(CharacterList) },
                        onOpenTerminal = { backStack.add(Terminal) },
                    )
                }

                entry<CharacterList> {
                    CharacterListScreen(
                        container = container,
                        onBack = { popBackStack(backStack) },
                        onEdit = { characterId -> backStack.add(CharacterEdit(characterId)) },
                    )
                }

                entry<CharacterEdit> { key ->
                    CharacterEditScreen(
                        container = container,
                        characterId = key.characterId,
                        onBack = { popBackStack(backStack) },
                    )
                }

                entry<CrashLogs> {
                    CrashLogScreen(
                        container = container,
                        onBack = { popBackStack(backStack) },
                    )
                }

                entry<Terminal> {
                    // 这一页**不碰 `container`** —— 它跑的是系统自带的 `sh`，
                    // 不需要数据库、不需要设置、不需要任何注入的依赖。
                    // 后面要是给它加了「让 AI 也用它」，那才需要在这里接上工具系统。
                    TerminalScreen(onBack = { popBackStack(backStack) })
                }

                entry<WebSearchSettings> {
                    WebSearchSettingsScreen(
                        container = container,
                        onBack = { popBackStack(backStack) },
                    )
                }

                entry<ProviderEdit> { key ->
                    ProviderEditScreen(
                        container = container,
                        providerId = key.providerId,
                        onBack = { popBackStack(backStack) },
                    )
                }

                entry<PluginList> {
                    PluginListScreen(
                        container = container,
                        onBack = { popBackStack(backStack) },
                        onOpenPlugin = { pluginId -> backStack.add(PluginDetail(pluginId)) },
                        onInstall = { backStack.add(PluginInstall) },
                    )
                }

                entry<PluginInstall> {
                    PluginInstallScreen(
                        container = container,
                        onBack = { popBackStack(backStack) },
                        // 装完直接进详情页：用户下一步多半是去填配置，
                        // 而不是回列表再点一次。同时把安装页从返回栈里去掉 ——
                        // 从详情页返回时回到列表，比回到一个已经装过的表单合理
                        onInstalled = { pluginId ->
                            backStack.removeLastOrNull()
                            backStack.add(PluginDetail(pluginId))
                        },
                    )
                }

                entry<PluginDetail> { key ->
                    PluginDetailScreen(
                        container = container,
                        pluginId = key.pluginId,
                        onBack = { popBackStack(backStack) },
                    )
                }
            },
    )
}
