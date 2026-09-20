package com.aichat.network

import com.aichat.domain.io.CappedRead
import com.aichat.domain.io.readCapped
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** 查「最新版本是多少」的结果。 */
sealed interface ReleaseLookup {

    /**
     * 查到了。[tag] 是**原样**的 `tag_name`（形如 `v1.2`）—— 界面要显示它，
     * 因为用户去 Release 页面看到的也是这个名字。
     *
     * [pageUrl] 是那一版的网页地址，用于「去下载」。
     */
    data class Found(val tag: String, val pageUrl: String) : ReleaseLookup

    data class Failed(val error: ReleaseError) : ReleaseLookup
}

/**
 * 查最新版本会失败在哪一步。
 *
 * ## 为什么要分类，而不是一句话
 *
 * 因为**用户该做的事完全不同**：
 *
 * - 限流 → 「过一会儿再试」（他自己能解决，等一下就行）
 * - 还没有发布 → 「仓库里还没有发布过版本」（等多久都没用，不用再试）
 * - 连不上 → 「检查你的网络」
 * - 返回看不懂 → 「稍后再试」或者「去 Release 页面自己看」
 *
 * 拼成一句「检查更新失败」的话，用户唯一能做的就是反复点同一个按钮。
 * 这和 `FetchError` 是同一套理由（见那个接口的 KDoc）。
 */
sealed interface ReleaseError {

    /**
     * 404：这个仓库**没有任何已发布的 release**。
     *
     * 也包含「仓库地址写错了 / 仓库变成私有」—— 从响应上区分不了这两件事，
     * 而它们的表现完全一样（都拿不到），所以不假装能分清。
     */
    data object NoRelease : ReleaseError

    /**
     * 被 GitHub 限流了（未认证的接口是每 IP 每小时 60 次）。
     *
     * 单独一类是因为它**可以等一会儿再试**，而其它几类等多久都一样。
     */
    data object RateLimited : ReleaseError

    /** 响应体超过了我们愿意读的上限，或者读出来不是一个完整的 JSON。 */
    data object Malformed : ReleaseError

    data class HttpStatus(val code: Int) : ReleaseError

    /** 连不上、超时、TLS 失败之类。`detail` 是底层异常的信息，可能为空串。 */
    data class Network(val detail: String) : ReleaseError
}

/**
 * 查一个 GitHub 仓库的最新 release。
 *
 * ## 为什么是「查 release」而不是「查 tag」
 *
 * tag 可以随便打（一次调试就多一个），而 release 是**发布动作**。用户要的是
 * 「有没有一个值得我更新的新版本」，那就该问 release。
 *
 * 用的端点是 `/releases/latest`，它有个关键性质：**不返回草稿和预发布版**。
 * 所以「查到的就是最新的正式版」，这里不需要自己过滤。
 *
 * ## 为什么不用「列出全部 release 再自己挑最新的」
 *
 * 那要自己实现一遍版本号排序，而 `:domain` 里已经有一份（`compareVersions`）。
 * 两份排序迟早会不一致 —— 而这个端点已经把这件事做对了。
 *
 * ## 为什么 `pageUrl` 取 `html_url` 而不是自己拼
 *
 * `html_url` 是 GitHub 给的、指向那一版页面的地址。自己拼
 * `https://github.com/<owner>/<repo>/releases/tag/<tag>` 也能work，但 tag 里
 * 可能有需要转义的字符，而那个拼法一旦错了就是一个 404 —— 不如用对方给的。
 *
 * ## 为什么不要 release 正文
 *
 * 同一份响应里就有 `body`（Release 说明），拿它不要额外的请求。但**这里刻意
 * 不取**：正文是 Markdown，要在界面上显示就得再接一层渲染，那是另一件事
 * （`MarkdownParser` 在 `:domain/render`，但把它接进一个对话框是独立的活）。
 * 而「去下载」那一步本来就会把用户带到 Release 页面，说明就在那儿。
 * 先不取，等真有需要再说。
 */
class GitHubReleaseClient(
    private val client: OkHttpClient,
    private val apiUrl: String,
) {

    /**
     * **阻塞调用**，调用方负责切线程（[latest] 已经切好了）。
     *
     * 单独拆出来是因为 `withContext` 里只该有那一句切换 —— 这个函数里
     * 有多个 return 出口，混在 lambda 里读起来会分不清哪层是哪个。
     */
    private fun fetch(): ReleaseLookup {
        val request = Request.Builder()
            .url(apiUrl)
            // GitHub 的接口**要求**带 User-Agent，不带会直接 403。
            // OkHttp 默认会发一个 `okhttp/x.y.z`，够用；但这里写明确的，
            // 免得将来有人把默认 UA 关掉之后莫名其妙开始 403。
            .header("User-Agent", USER_AGENT)
            // 固定 API 版本：不写的话走的是「当前默认版本」，而那个东西
            // 将来会变 —— 变了之后字段名可能跟着变，而这是静默的
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            return ReleaseLookup.Failed(ReleaseError.Network(e.describe()))
        }

        try {
            if (!response.isSuccessful) {
                return ReleaseLookup.Failed(classify(response.code, response.header(RATE_LIMIT_REMAINING)))
            }

            // 用 source().inputStream() 而不是 body.string()：后者会把整个响应
            // 读进内存，那正是下面这条上限要防的事
            val text = response.body.source().inputStream().use { readCapped(it, MAX_CHARS) }
            return when (text) {
                is CappedRead.TooLarge -> ReleaseLookup.Failed(ReleaseError.Malformed)
                is CappedRead.Ok -> parse(text.text)
            }
        } finally {
            response.close()
        }
    }

    private fun parse(json: String): ReleaseLookup {
        val dto = try {
            ReleaseJson.decodeFromString(ReleaseDto.serializer(), json)
        } catch (e: Exception) {
            // 解析失败的类型不重要，结果都一样：这份响应我们读不懂。
            // 捕获宽一点是因为 kotlinx.serialization 在「缺字段」和「类型不对」
            // 时抛的是不同的异常，而这里不打算对它们分别处理
            return ReleaseLookup.Failed(ReleaseError.Malformed)
        }

        val tag = dto.tagName?.takeIf { it.isNotBlank() }
        val url = dto.htmlUrl?.takeIf { it.isNotBlank() }
        if (tag == null || url == null) {
            // 少任何一个都没法给用户一个可用的答案：没有 tag 就比不了版本，
            // 没有 url 就没有「去下载」。宁可说读不懂
            return ReleaseLookup.Failed(ReleaseError.Malformed)
        }
        return ReleaseLookup.Found(tag = tag, pageUrl = url)
    }

    suspend fun latest(): ReleaseLookup = withContext(Dispatchers.IO) { fetch() }

    private companion object {

        /**
         * 响应上限。Release 的 JSON 正常只有几 KB（我们只取三个字段），
         * 给 64 KB 是为了**在对方出问题时有个闸** —— 而不是因为预期它会大。
         *
         * 超过就报「读不懂」而不是截断了硬解析：截断的 JSON 一定解析失败，
         * 那不如在读到上限的那一刻就说清楚。
         */
        const val MAX_CHARS = 64 * 1024

        const val USER_AGENT = "Patchbay-UpdateCheck"

        /** 见请求头那处注释：固定版本，免得默认版本变了之后字段名跟着变。 */
        const val API_VERSION = "2022-11-28"

        const val RATE_LIMIT_REMAINING = "X-RateLimit-Remaining"

        /**
         * **必须显式配，不能用默认的 `Json`。**
         *
         * 默认实例的 `ignoreUnknownKeys = false`，而 GitHub 的响应里有几十个
         * 我们不要的字段（`name` / `body` / `assets` / `author`…）——
         * 用默认实例的话**每一个正常响应都会解析失败**，然后被报成「读不懂」。
         *
         * 这个坑真踩了一次：写这段时的注释里已经写着「+ `ignoreUnknownKeys`」，
         * 但代码用的是 `Json.decodeFromString`（默认实例），两者不一致 ——
         * 而注释不会报错，是两条正常路径的用例把它抓出来的。
         */
        val ReleaseJson = Json {
            ignoreUnknownKeys = true
        }

        /**
         * 把失败的状态码分到能采取行动的那一类里。
         *
         * **429 也算限流**：GitHub 文档写的是 403 + `X-RateLimit-Remaining: 0`，
         * 但实际也见过 429。两者对用户的含义一样（等一会儿），所以合成一类。
         */
        fun classify(code: Int, remaining: String?): ReleaseError = when {
            code == 404 -> ReleaseError.NoRelease
            code == 429 -> ReleaseError.RateLimited
            code == 403 && remaining == "0" -> ReleaseError.RateLimited
            else -> ReleaseError.HttpStatus(code)
        }
    }
}

/**
 * `/releases/latest` 的响应里我们关心的字段。
 *
 * 全部可空 + `ignoreUnknownKeys`：GitHub 的响应有几十个字段，而且会**加字段**
 * （那不算破坏性变更）。写成非空的话，任何一次字段调整都会让「检查更新」
 * 开始报「读不懂」—— 而那是静默的、我们这边不会收到任何信号。
 */
@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

private fun IOException.describe(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
