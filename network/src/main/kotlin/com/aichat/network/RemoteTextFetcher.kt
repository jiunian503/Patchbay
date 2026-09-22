package com.aichat.network

import com.aichat.domain.io.CappedRead
import com.aichat.domain.text.errorDetail
import com.aichat.domain.io.readCapped
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** [RemoteTextFetcher.fetch] 的结果。 */
sealed interface FetchResult {
    data class Ok(val text: String, val finalUrl: HttpUrl) : FetchResult
    data class Failed(val error: FetchError) : FetchResult
}

/**
 * 抓取失败的原因。
 *
 * ## 为什么是结构化类型而不是一句话
 *
 * 因为**同一句话在不同场景下要说清不同的东西**。「拉取失败」对用户没有用；
 * 他需要知道是网址写错了、还是对方返回了 404、还是自己网络断了。
 * 而拼这句话需要上下文（主机名、状态码、上限）—— 那些只有界面层才有，
 * 这里只负责把「是哪一类」分类清楚。
 *
 * 字符串断言也更容易在改文案时误伤测试。
 */
sealed interface FetchError {
    /** 原始 URL 不是一个合法网址（也包含「没有 scheme 且补成 https 后仍不合法」）。 */
    data object BadUrl : FetchError

    /** scheme 不是 http/https（`ftp://`、`file://`、`content://` 之类）。 */
    data object UnsupportedScheme : FetchError

    /** 重定向的 `Location` 解析不出来。 */
    data object BadRedirect : FetchError

    /** 用户给的是 https，但某一跳把它降级成了明文 http。 */
    data object InsecureDowngrade : FetchError

    data object TooManyRedirects : FetchError

    data object TooLarge : FetchError

    data class HttpStatus(val code: Int) : FetchError

    /** 连不上、超时、TLS 失败之类。`detail` 是底层异常的信息，可能为空串。 */
    data class Network(val detail: String) : FetchError
}

/**
 * 按 URL 取一段**有大小上限**的文本。
 *
 * ## 它是干什么的
 *
 * 目前唯一的用途是「从 URL 装插件」：用户给一个地址，把清单 JSON 拉回来。
 * 但实现里没有任何「插件」或「清单」的概念 —— 它就是「按 URL 取一段文本」。
 * 这样 `:network` 不必依赖 `:plugin`，将来别的地方要取一段文本也能用。
 *
 * ## 为什么不走插件那套 `NetworkGuard` 白名单
 *
 * 白名单约束的是**插件运行时的网络请求** —— 那是插件自己的行为，所以要在
 * 装之前就把它的活动范围钉死。而这里发起请求的是**宿主**，触发者是**用户
 * 主动点的一个按钮**，此时那个插件根本还不存在，谈不上它的权限。
 *
 * 换个角度更好理解：这和「用户在浏览器里打开一个链接」是同一级别的操作，
 * 而不是「某个已安装的程序在后台偷偷联网」。
 *
 * 但正因为少了白名单这层，**这里必须自己把好关** —— 见下面三条。
 *
 * ## 三条自定的规则
 *
 * **一、只认 http / https。** 用 `HttpUrl` 解析，它天然拒绝 `ftp://`、
 * `file://`、`content://`、`javascript:` 这些。`file://` 尤其要挡住：
 * 已经有「从文件」入口了（走 SAF，有系统的权限模型），
 * 而 `file://` 会绕过它直接读应用能碰到的任何文件。
 *
 * **二、没写 scheme 时补 `https://`。** 用户输 `example.com/x.json` 是常态，
 * 报「这不像一个网址」太苛刻。补 https 而不是 http —— 默认落在安全的那一边。
 *
 * **三、https 绝不接受降级到 http。** 用户明确写了 `https://`，就说明他要求
 * 走加密；中途某一跳把它转成明文是**服务器单方面**改的，他没有同意过。
 * （用户自己写 `http://` 则照办 —— 那是他的选择，界面会如实标注这是明文连接。）
 *
 * ## 为什么要自己跟重定向
 *
 * 因为规则三。开着自动重定向就没法在每一跳上检查 scheme，
 * 一个 `https://` 的地址被 302 到 `http://` 会**静默**地降级成功。
 * 自己跟还能拿到最终 URL，界面可以如实显示「实际是从哪个主机取到的」。
 *
 * [maxChars] 刻意**没有默认值**：上限该设多少取决于场景，
 * 给默认值等于让调用方不必想这件事。清单场景传的是 256 KB。
 */
class RemoteTextFetcher(
    private val client: OkHttpClient,
    private val maxChars: Int,
) {

    init {
        // 开着自动重定向的话，`https://` 被 302 到 `http://` 会**静默**降级成功 ——
        // 下面那条「不接受降级」的检查根本没机会跑，而且没有任何报错。
        // 这个错误太安静了，所以宁可在这里直接失败。
        //
        // 用 `require` 而不是在文档里写一句「请传 followRedirects = false」：
        // 文档约束不了调用方，而这里的失败模式是**安全性静默失效**。
        require(!client.followRedirects) {
            "RemoteTextFetcher 需要 followRedirects = false 的 OkHttpClient —— " +
                "它自己逐跳跟重定向，每一跳都要检查协议有没有被降级"
        }
    }

    suspend fun fetch(rawUrl: String): FetchResult {
        val start = when (val parsed = parseFetchUrl(rawUrl)) {
            is FetchUrlParse.Ok -> parsed.url
            FetchUrlParse.Bad -> return FetchResult.Failed(FetchError.BadUrl)
            FetchUrlParse.UnsupportedScheme ->
                return FetchResult.Failed(FetchError.UnsupportedScheme)
        }
        // 阻塞的 IO 全在这里切线程；下面那个循环是普通函数
        return withContext(Dispatchers.IO) { follow(start) }
    }

    /**
     * 逐跳跟随重定向，直到拿到一个最终响应。
     *
     * **阻塞调用** —— 调用方负责切线程（见 [fetch]）。
     *
     * 单独拆成具名函数而不是直接写在 `withContext { }` 里：
     * `while (true)` 作为 lambda 的最后一个表达式时，Kotlin 会把整个 lambda
     * 推断成返回 `Unit`（所有出口都是 `return@withContext`），于是编译不过。
     * 具名函数走的是流分析，知道每条路径都 return 了。
     */
    private fun follow(start: HttpUrl): FetchResult {
        // 用户**自己**写的是不是 https。这个判断只做一次 ——
        // 中途每一跳都重新比的话，第二跳之后就没有「用户的意愿」可参照了
        val userAskedForTls = start.isHttps

        var current = start
        var hops = 0

        while (true) {
            val response = try {
                client.newCall(Request.Builder().url(current).build()).execute()
            } catch (e: IOException) {
                return FetchResult.Failed(FetchError.Network(errorDetail(e)))
            }

            try {
                if (response.isRedirect) {
                    if (hops >= MAX_REDIRECTS) {
                        return FetchResult.Failed(FetchError.TooManyRedirects)
                    }
                    val location = response.header("Location")
                        ?: return FetchResult.Failed(FetchError.HttpStatus(response.code))
                    // resolve 能处理相对地址（`/other.json`），这是重定向里最常见的形式
                    val next = current.resolve(location)
                        ?: return FetchResult.Failed(FetchError.BadRedirect)
                    if (isInsecureDowngrade(userAskedForTls, next)) {
                        return FetchResult.Failed(FetchError.InsecureDowngrade)
                    }
                    current = next
                    hops++
                    continue
                }

                if (!response.isSuccessful) {
                    return FetchResult.Failed(FetchError.HttpStatus(response.code))
                }

                // 用 source().inputStream() 而不是 body.string()：
                // 后者会把整个响应体读进内存，那正是下面这条上限要防的事
                val outcome = response.body.source().inputStream()
                    .use { readCapped(it, maxChars) }

                return when (outcome) {
                    is CappedRead.TooLarge -> FetchResult.Failed(FetchError.TooLarge)
                    is CappedRead.Ok -> FetchResult.Ok(outcome.text, current)
                }
            } finally {
                response.close()
            }
        }
    }

    companion object {
        /** 5 跳足够覆盖 CDN / 短链 / 尾斜杠规范化这些正常情况。 */
        const val MAX_REDIRECTS = 5
    }
}

/** [parseFetchUrl] 的结果。 */
internal sealed interface FetchUrlParse {
    data class Ok(val url: HttpUrl) : FetchUrlParse
    data object Bad : FetchUrlParse
    data object UnsupportedScheme : FetchUrlParse
}

/**
 * 把用户输入的一段文本变成一个可请求的 URL。
 *
 * 抽成顶层函数是为了能**脱离网络**单独测 —— 「没写 scheme 时补 https」
 * 这条用 MockWebServer 验不了（它是明文的 http 服务，补出来的 https 连不上）。
 */
internal fun parseFetchUrl(rawUrl: String): FetchUrlParse {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return FetchUrlParse.Bad

    val scheme = schemeOf(trimmed)
    if (scheme != null && scheme != "http" && scheme != "https") {
        return FetchUrlParse.UnsupportedScheme
    }
    val withScheme = if (scheme == null) "https://$trimmed" else trimmed
    // HttpUrl 会拒掉主机名非法、端口非法、含空格等等，
    // 所以这一句同时挡住了「javascript:alert(1)」这类东西
    val url = withScheme.toHttpUrlOrNull() ?: return FetchUrlParse.Bad
    return FetchUrlParse.Ok(url)
}

/** 取 `://` 前面那段。没有 `://` 就返回 null（视为「用户没写 scheme」）。 */
private fun schemeOf(raw: String): String? {
    val at = raw.indexOf("://")
    if (at <= 0) return null
    val candidate = raw.substring(0, at)
    // scheme 只能是字母数字加 `+ - .`（RFC 3986）。含别的字符说明
    // 这不是一个 scheme，而是 `javascript:alert(1)` 那种东西 ——
    // 当作「没写 scheme」，让后面补上 https 再解析失败
    val looksLikeScheme = candidate.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '+' || it == '-' || it == '.'
    }
    return if (looksLikeScheme) candidate.lowercase() else null
}

/**
 * 用户要的是 https，而下一跳是明文 —— 拒绝。
 *
 * 用户**自己**写 `http://` 时不算降级：那是他的选择（界面会如实标注这是明文连接）。
 * 但 `https://` 被服务器 302 到 `http://` 是**对方单方面**改的，他没有同意过。
 *
 * 抽成函数是为了能单独钉住这个语义 —— 起一个 https 的测试服务端太重了。
 */
internal fun isInsecureDowngrade(userAskedForTls: Boolean, next: HttpUrl): Boolean =
    userAskedForTls && !next.isHttps

// 和 `GitHubReleaseClient` 里那个一模一样的私有 `describe()` 一起删掉了 ——
// 两份实现漂移过的地方，现在只有 `:domain` 的 `errorDetail` 一份
