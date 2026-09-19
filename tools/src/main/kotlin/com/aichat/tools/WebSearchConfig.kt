package com.aichat.tools

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 联网搜索的后端。
 *
 * ## 为什么是「让用户自己选后端」，而不是我们接一个搜索 API
 *
 * 这个 App 的整个前提是**本地 BYOK** —— 请求从这台设备直接发往用户填的地址，
 * 中间没有我们的服务器。搜索如果走我们自己的中转，那就同时出现三个问题：
 *
 * 1. **隐私破了**。用户搜什么，我们全都看得见。而搜索词往往比对话本身更露骨。
 * 2. **配额是谁的**。用户量一上来，搜索 API 的钱是我们出；一收费，这个 App
 *    就不再是「你填 Key 就能用」。
 * 3. **和 BYOK 自相矛盾**。用户为对话自备了 Key，却在搜索这一步被强制经过我们。
 *
 * 所以这里只提供**协议适配**：把三种常见后端的响应归一成同一种结果。
 * 地址和密钥都是用户填的，和填对话服务商是同一件事。
 *
 * ## 三种后端的取舍
 *
 * - [SEARXNG]：**自托管 / 公共实例**。不需要密钥（或实例自己定），没有配额，
 *   但需要实例在 `settings.yml` 里打开 json 格式 —— 实测公共实例基本都关掉了它
 *   或者挡在人机验证后面，所以这条路实际上等于「自己部署一个」。声明顺序排在
 *   第一位只是历史原因，**界面上它排在最后**（见 `WebSearchSettingsScreen`）。
 * - [BRAVE]：有免费额度，结果质量稳定，接口最简单（一个 GET）。
 * - [TAVILY]：为 LLM 设计的搜索，摘要更长更整齐，但是 POST + Bearer。
 */
enum class WebSearchBackend(val id: String, val label: String) {

    SEARXNG("searxng", "SearXNG"),
    BRAVE("brave", "Brave Search"),
    TAVILY("tavily", "Tavily");

    companion object {

        /** 存进设置里的是 [id]（字符串），不是枚举名 —— 改名不影响老设置。 */
        fun fromId(id: String?): WebSearchBackend? {
            val trimmed = id?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            return entries.firstOrNull { it.id.equals(trimmed, ignoreCase = true) }
        }
    }
}

/**
 * 一次搜索要用的全部信息。由 `:app` 从「设置 + KeyStore」拼出来。
 *
 * [apiKey] 为 null 表示没配。SearXNG 通常不需要；Brave 和 Tavily 必需。
 */
data class WebSearchConfig(
    val backend: WebSearchBackend,
    val endpoint: String,
    val apiKey: String? = null,
)

/**
 * 搜索配置的来源。**由 `:app` 实现，`:tools` 只认识这个接口。**
 *
 * ## 为什么是两个方法，而不是一个
 *
 * 因为**装配路径不能挂起**。`BuiltinTools.all()` 是在
 * `ToolRegistryHolder.refresh()` 里同步调用的，而 API Key 存在
 * AndroidKeyStore 里、只能挂起读。一个 `suspend fun config()` 满足不了
 * 「这个工具到底要不要注册」这个同步的判断。
 *
 * 拆成两个之后各自的职责很清楚：
 *
 * - [configured] 是**同步**的，只读 SharedPreferences 里的后端 id 与地址，
 *   用来回答「该不该注册这个工具」。它**不查密钥**。
 * - [config] 是**挂起**的，真到执行时再去 KeyStore 取密钥。
 *
 * 于是「后端选好了但密钥读不出来」这种情况会在执行时报错，而不是让工具
 * 悄悄消失 —— 那是设备恢复出厂、KeyStore 被清掉之后的正确表现，
 * 而且错误信息能直接告诉用户去重填。
 */
interface WebSearchSource {

    /**
     * 后端选好了、而且地址是能解析的 http/https 地址。
     *
     * **刻意不检查密钥**：那需要挂起读 KeyStore，而调用方在同步的装配路径上。
     * 密钥的缺失由 [config] 在执行时兜住。
     */
    fun configured(): Boolean

    /** 当前完整配置（含密钥）。没配好、或密钥读不出来时返回 null。 */
    suspend fun config(): WebSearchConfig?
}

/**
 * 后端的默认接口地址。
 *
 * SearXNG **刻意留空**：它没有公共默认实例。给一个「示例地址」当默认值的话，
 * 用户很可能直接用它 —— 而公共实例随时会挂、会限流、会关掉 json 格式，
 * 到时候表现为「搜索时好时坏」，极难归因。宁可让用户自己去挑一个。
 */
fun defaultEndpoint(backend: WebSearchBackend): String = when (backend) {
    WebSearchBackend.SEARXNG -> ""
    WebSearchBackend.BRAVE -> "https://api.search.brave.com/res/v1/web/search"
    WebSearchBackend.TAVILY -> "https://api.tavily.com/search"
}

/**
 * 把用户填的地址解析成真正要请求的 URL。
 *
 * ## SearXNG 的路径要补 `/search`
 *
 * 用户在设置里填的是**实例地址**（`https://searx.be`），不是接口地址。
 * 让他自己写 `/search?format=json` 是没必要的负担 —— 那是这个后端的固定约定。
 *
 * 补的规则是「路径不以 `/search` 结尾就补一段」，所以下面三种写法都对：
 *
 * - `https://searx.be` → `https://searx.be/search`
 * - `https://searx.be/` → `https://searx.be/search`
 * - `https://example.com/searx`（子路径部署）→ `https://example.com/searx/search`
 * - `https://searx.be/search`（用户自己写全了）→ 原样
 *
 * 地址为空时回退到 [defaultEndpoint]；仍然为空或解析不了则返回 null。
 */
fun WebSearchConfig.resolveEndpoint(): HttpUrl? {
    val raw = endpoint.trim().ifBlank { defaultEndpoint(backend) }
    if (raw.isBlank()) return null

    val parsed = raw.toHttpUrlOrNull() ?: return null

    if (backend != WebSearchBackend.SEARXNG) return parsed

    if (parsed.encodedPath.trimEnd('/').endsWith("/search")) return parsed
    return parsed.newBuilder().addPathSegment("search").build()
}

/**
 * 这个配置能不能真的发出去。
 *
 * 设置页用它给一句「可以了 / 还差什么」。**它不决定注册** —— 注册看的是
 * [WebSearchSource.configured]（同步、不查密钥），所以「后端选好了但密钥
 * 读不出来」不会让工具消失，而是在执行时报错（见 [WebSearchSource] 的 KDoc）。
 */
fun WebSearchConfig.isComplete(): Boolean {
    if (resolveEndpoint() == null) return false
    return when (backend) {
        // SearXNG 的实例可能要求 Basic Auth，但绝大多数不要求，
        // 所以密钥是可选的 —— 不能因为它为空就判定「没配好」
        WebSearchBackend.SEARXNG -> true
        WebSearchBackend.BRAVE, WebSearchBackend.TAVILY -> !apiKey.isNullOrBlank()
    }
}

/** 一份写死的配置。设置页的「测试」按钮用它走一遍真实路径。 */
internal class FixedWebSearchSource(private val config: WebSearchConfig) : WebSearchSource {

    override fun configured(): Boolean = true

    override suspend fun config(): WebSearchConfig = config
}
