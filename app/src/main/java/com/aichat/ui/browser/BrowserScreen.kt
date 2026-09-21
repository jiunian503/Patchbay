package com.aichat.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import com.aichat.ui.common.PbIcons
import com.aichat.ui.common.PbTopBar

/**
 * 内置浏览器。
 *
 * ## 它解决的是什么
 *
 * 在这之前，消息正文里的链接一律交给系统浏览器 —— 用户点一下就**被弹出 App**。
 * 回来时对话还在，但中间那一步的上下文断了：他本来在读模型给的资料，
 * 现在人在浏览器里，要接着读得先切回来。
 *
 * ## 它**不是**「让 AI 上网」那个能力
 *
 * 「让 AI 读网页」是 `fetch_url` 工具（`:tools`）—— 那条路不发 WebView、
 * 不执行 JS、只取回正文。两者的用途和风险都不一样，别混。
 * 这一页解决的是**人点的链接不要跳出 App**。
 *
 * ## 为什么**不用** `PbScaffold`
 *
 * 它是二级页面的标准骨架（顶栏毛玻璃 + 内容滚到顶栏背后）。这里故意不用：
 * 毛玻璃靠 `hazeSource` 模糊**它背后的内容**，而 `WebView` 是**独立渲染层**
 * （不是 Compose 画布上的一层），haze 取不到它的像素 —— 用了只会得到一条
 * 背后空无一物的顶栏，也就是「毛玻璃白做」（§62 那条判据的反面）。
 * 所以这里是**实心顶栏**，内容也不往顶栏背后滚。
 *
 * ## 返回的两套语义（**故意不一样**）
 *
 * | 从哪按 | 行为 | 为什么 |
 * |---|---|---|
 * | 顶栏左箭头 | **退出这一页**（回聊天） | 它和 App 里其它二级页面的箭头是同一个动作，长相和行为都不该在这里变（`PbTopBar` 的 KDoc 就是为这件事写的） |
 * | 系统返回键 | **先网页后退，退不动了再退出** | Android 上 WebView 的惯例（Chrome 也这样）。用户翻了三层之后按返回，要回的是上一页 |
 *
 * 顶栏那个箭头**永远给得出出口**，所以即使网页里绕成了环也不会把人困住。
 *
 * ## 安全上的四个开关
 *
 * - `javaScriptEnabled = true` —— 现代网页没有 JS 基本不可用（GitHub 也是）。
 *   代价是网页能在 WebView 里跑脚本；但 WebView 自己的沙箱、同源策略，
 *   加上下面三条限制仍然在。lint 会为这一行报警告，这里显式抑制并记档。
 * - `allowFileAccess = false` / `allowContentAccess = false` —— 不让网页经
 *   `file://` 读本机文件、也不让它读 `content://`。这两个在 API 30+ 默认就是
 *   false，**显式写出来是为了不依赖「默认值恰好对」**。
 * - `mixedContentMode = MIXED_CONTENT_NEVER_ALLOW` —— HTTPS 页面里不许夹明文
 *   子资源。这条**不会**妨碍用户直接打开一个 `http://` 页面：那是顶层导航，
 *   走的是 `network_security_config` 里那条明文许可（那个开关为什么开着，
 *   见那个文件的注释）。
 *
 * 非 http/https 的链接（`mailto:` / `tel:` / `intent:` …）**不在 WebView 里加载**，
 * 一律交回系统 —— 那些是「另一个 App 的活」，硬塞进 WebView 只会白屏。
 *
 * @param url 要打开的地址。它是导航键的一部分，**进页面之后就不再变** ——
 *   页内的跳转由 WebView 自己处理，不会往返回栈里压新的一页。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    url: String,
    onBack: () -> Unit,
) {
    // 顶栏「用浏览器打开」要的是**系统**那个 handler。
    // 这一页不在聊天页那层替换范围内（见 `Navigation.kt` 里 `entry<Chat>` 的注释），
    // 所以这里拿到的就是 Activity 提供的那个
    val systemHandler = LocalUriHandler.current
    val currentSystemHandler by rememberUpdatedState(systemHandler)

    // WebView 不是 Compose 状态，下面这几个都得自己从回调里同步过来
    var currentUrl by remember { mutableStateOf(url) }
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    // WebView 的引用。**故意不是 Compose 状态** —— 它只在事件回调里被读，
    // 写它不需要触发重组。
    //
    // ⚠️ 这里踩过一次（2026-09-21）：一开始用的是
    // `var webView by remember { mutableStateOf<WebView?>(null) }`，在
    // `AndroidView.factory` 里 `webView = this`。后果很隐蔽 ——
    // **返回键拦到了**（`canGoBack` 确实为 true，`BackHandler` 生效），
    // 但 `goBack()` 没执行：按返回既不后退、也不退出，像按了个死键。
    // 因为那个赋值发生在组合期间，没落进状态里。
    // 数组当 holder 是为了不额外定义一个类；`[0]` 只在上面两个回调里出现。
    val webViewRef = remember { arrayOfNulls<WebView>(1) }

    // 只在「网页里还能后退」时拦截系统返回；退不动了就放行，
    // 事件会走到 Nav3 的 onBack（也就是 popBackStack，判据 size > 1，见 §98）
    BackHandler(enabled = canGoBack) { webViewRef[0]?.goBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PbTopBar(
                // 网页标题优先；拿不到就退化成域名 —— 顶栏总得写点什么，
                // 空着会让人以为页面没加载出来
                title = pageTitle?.takeIf { it.isNotBlank() } ?: hostOf(currentUrl),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { webViewRef[0]?.reload() }) {
                        Icon(PbIcons.Refresh, contentDescription = "刷新")
                    }
                    // ⚠️ 必须包 `runCatching`。`AndroidUriHandler.openUri` **不吞异常** ——
                    // 它把 `ActivityNotFoundException` 包成 `IllegalArgumentException`
                    // 再抛（反汇编 `ui-android-1.12.0` 的 `AndroidUriHandler.openUri` 看到
                    // 异常表里 catch 的就是 `ActivityNotFoundException`）。实测过：
                    // 没包的时候，目标 URI 无人处理 ⇒ **直接崩在点击回调里**
                    // （`FATAL EXCEPTION: java.lang.IllegalArgumentException: Can't open …`）。
                    // 设备上没装浏览器、或那个 scheme 没有应用认领，都会走到这里。
                    IconButton(
                        onClick = { runCatching { currentSystemHandler.openUri(currentUrl) } },
                    ) {
                        Icon(PbIcons.OpenInNew, contentDescription = "用浏览器打开")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        // 双指缩放要开着（网页不是按手机宽度设计的很多），
                        // 但**不要**那对悬浮的 +/- 按钮 —— 它们会盖住正文
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val target = request.url.toString()
                                if (isWebUrl(target)) return false // 交回 WebView 自己加载

                                // mailto: / tel: / intent: … 交给系统。
                                // 打不开就算了（没有能处理它的应用）—— 和消息里
                                // 点链接同一个处理，理由见 `MarkdownText.InlineText`
                                runCatching { currentSystemHandler.openUri(target) }
                                return true
                            }

                            override fun doUpdateVisitedHistory(
                                view: WebView,
                                url: String?,
                                isReload: Boolean,
                            ) {
                                canGoBack = view.canGoBack()
                                if (url != null) currentUrl = url
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                                if (url != null) currentUrl = url
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                canGoBack = view.canGoBack()
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView, title: String?) {
                                pageTitle = title
                            }
                        }

                        loadUrl(url)
                        // ⚠️ 这里是**非状态** holder 才成立。以前写 `webView = this`
                        // （一个 `mutableStateOf`），赋值发生在组合期间被丢掉，
                        // 结果返回键成了死键 —— 上面 `webViewRef` 那段注释有全过程
                        webViewRef[0] = this
                    }
                },
                // 不 destroy 的话 WebView 会连着它的渲染进程一起漏掉 ——
                // 这是这一页唯一需要手动收尾的资源
                onRelease = {
                    it.stopLoading()
                    it.destroy()
                },
            )

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * 是不是「网页」地址。
 *
 * 只看 scheme，不做更严的判断 —— 调用方（`MarkdownText` 那条路）的链接
 * 已经在 `:domain` 的解析里过了一遍白名单，这里只负责分流
 * 「WebView 能加载的」和「该交回系统的」。
 */
internal fun isWebUrl(url: String): Boolean {
    val lower = url.lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://")
}

/**
 * 顶栏标题的兜底：域名。
 *
 * 去掉 `www.` —— 它在顶栏里只是噪音，而顶栏宽度很宝贵（长标题会截断）。
 * 解析失败就原样返回，至少让人看到点东西。
 */
private fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host }
        .getOrNull()
        ?.removePrefix("www.")
        ?: url
