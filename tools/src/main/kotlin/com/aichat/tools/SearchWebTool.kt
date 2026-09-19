package com.aichat.tools

import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 让模型在**公网**上搜一次。
 *
 * ## 这个工具补的是哪个洞
 *
 * 在它之前，模型能接触到的外部信息只有 `fetch_url` —— 而那个要求
 * **模型先知道确切地址**。现实里用户问的绝大多数是「你不知道、我也不知道
 * 该去哪查」的问题：某个库的最新版本、某家公司上个月发生了什么、
 * 一个刚听说的名词是什么意思。没有搜索时，模型的正确反应是「我无法联网」，
 * 错误反应是编一个 —— 而后者更常见。
 *
 * 所以这个工具的 `description` 里必须把**触发场景**写死（见 §48 的判据）：
 * 不是「可以搜索」，而是「遇到 X 类问题就用它，不要凭记忆回答」。
 *
 * ## description 里那三条是实测改出来的，不是想出来的
 *
 * 最初只写了「涉及你不知道、或可能已经变化的事实」。真机实测时问了一句
 * 「What is the Patchbay patchboard number?」，模型**没有搜索**，而是回答
 * 「我不认识这个，告诉我你在哪儿见过，我可以上网查」—— 也就是说它明明
 * 看见了工具，却选择了反问。
 *
 * 原因是那句话只覆盖了「我不知道这个**事实**」，没覆盖「我不知道这个**东西**」。
 * 后者在模型的判断里更像「用户在问一个内部术语，我该先澄清」。
 *
 * 所以加了第 ③ 条，并明确写「别急着反问，先搜；搜不到再问」。改完实测：
 * 同一个问题，模型直接调用了 `search_web`，query 是
 * `Patchbay patchboard number`，并把搜到的编号用进了回答。
 *
 * **工具描述改一个字就是改行为** —— 所以这条改动本身也是要跟着测的。
 *
 * ## 为什么不需要用户确认
 *
 * 和 `fetch_url` 的差别在这里，值得写清楚：
 *
 * - `fetch_url` 的**目标地址是模型选的**。提示注入可以让它去请求内网地址、
 *   或者把用户 IP 和查询串一起送给任意第三方 —— 那是实打实的越权路径，
 *   所以必须逐次点头。
 * - 这个工具的**目标地址是用户自己填的**。模型只能决定关键词，决定不了
 *   请求发到哪儿。而且用户能用到这个工具，前提是他已经主动进设置页
 *   选了后端、填了地址和密钥 —— **那一次配置就是同意**。
 *
 * 再加上搜索往往是成串的（模型先搜一遍，发现方向不对再搜一遍），
 * 每次弹窗的结果一定是用户闭眼点「允许」，确认机制退化成形式。
 * 这一点和 `search_history` 是同一个判断：确认要留给「模型能决定去向」的动作。
 *
 * 关键词会随请求发给用户选的那个搜索服务 —— 这句话写在设置页上，
 * 而不是藏在每次弹窗里。
 *
 * ## 结果的上限是**三层**
 *
 * 条数（[MAX_RESULTS]）、单条摘要（[MAX_SNIPPET_CHARS]）、总字符（[MAX_CHARS]）。
 * 只限总字符不够：一条 8000 字的摘要会把后面九条全挤掉，而那九条里可能
 * 才有答案。只限条数也不够：Tavily 的摘要天生就长。
 *
 * ## 两种响应格式，两条解析路径
 *
 * [WebSearchBackend.BING_HTML]（内置、零配置）返回的是**结果页 HTML**，
 * 另外三个返回 JSON。所以 [execute] 在解析处分叉：JSON 那条走
 * [parseRoot] + [parse]，HTML 那条走 [parseBingHtml]。
 *
 * 两条路径的「失败」语义**不一样**，不能共用一套提示：
 *
 * - JSON 路径「解析不出」= 配置问题（实例关了 json 输出、或者地址填错了）
 * - HTML 路径「解析不出」= **对方改版了、或者把我们当爬虫挡了** ——
 *   用户没有配置可查，他改不了对方
 *
 * 所以后者单独走 [structureChangedMessage]，第一句话就是「和你的配置无关」，
 * 免得用户去设置页反复检查一个根本不存在的地址 / 密钥。
 */
class SearchWebTool(
    private val source: WebSearchSource,
    private val client: OkHttpClient = defaultClient(),
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        description = "用搜索引擎在公网上查资料，返回若干条结果（标题 + 网址 + 摘要）。" +
            "遇到下面任何一类问题都**先搜一下**，不要凭记忆回答，也不要说「我无法联网」：" +
            "① 新闻、价格、版本号、某个人/公司/产品的近况；" +
            "② 任何你有把握「我的训练数据里没有」的事实；" +
            "③ **你根本没听说过的名词、产品、人名、编号** —— 这一类最容易被漏掉，" +
            "因为它读起来像是用户在问你一个内部术语。别急着反问「这是什么」，" +
            "先搜；搜不到再问。用户提出一个问题，默认就是希望你去找答案。" +
            "返回的是**搜索摘要片段，不是网页全文**；要读正文就再用 fetch_url 抓结果里的网址。" +
            "query 要写成搜索引擎会用的关键词（空格分隔多个词），" +
            "不要写完整问句，也不要带「请帮我」「我想知道」这类话。",
        parameters = schema(
            properties = mapOf(
                QUERY to stringParam(
                    "搜索关键词，用空格分隔多个词。" +
                        "例如「Android 16 release date」「Patagonia 退货政策」。" +
                        "不要写完整问句。",
                ),
            ),
            required = listOf(QUERY),
        ),
    )

    override val userSummary: String
        get() = "把这句话作为关键词，通过你设置里填的那个搜索服务在公网上查一次"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments.stringOrNull(QUERY)
            ?: return ToolResult.error(
                "缺少 query 参数。请给出搜索关键词，例如「Android 16 release date」。",
            )

        // 到执行这一刻才去 KeyStore 取密钥（挂起）。设置页的装配判断是同步的，
        // 查不了密钥 —— 所以「后端选好了但密钥没了」会走到这里。
        val config = source.config()
            ?: return ToolResult.error(
                "联网搜索现在用不了：后端没配置完整，或者密钥读不出来" +
                    "（设备恢复出厂、系统清过应用数据都会这样）。" +
                    "不要再尝试搜索，直接用你已有的知识回答，并说明这部分可能不是最新的。" +
                    "如果用户需要联网查，告诉他去「设置 → 联网搜索」检查一下。",
            )

        val endpoint = config.resolveEndpoint()
            ?: return ToolResult.error(
                "搜索后端的地址「${config.endpoint}」不是合法的 http/https 地址。" +
                    "这是用户在设置里填的，直接告诉用户去「设置 → 联网搜索」改，" +
                    "不要自己换一个地址重试。",
            )

        val request = buildRequest(config, endpoint, query)
            ?: return ToolResult.error(
                "${config.backend.label} 需要 API Key，但设置里没有填。" +
                    "不要再尝试搜索，告诉用户去「设置 → 联网搜索」填上。",
            )

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use httpError(config, response.code, response.message)
                }

                // 复用抓取工具那套「边读边计数」：搜索接口理论上不会返回大文件，
                // 但地址是用户填的，万一填成了别的接口，也不能让 App OOM
                val (body, truncated) = FetchUrlTool.readBounded(response.body.source(), Charsets.UTF_8)

                // 「200 但不是 JSON」必须和「200 但没有结果」分开报。
                //
                // 前者是配置问题（实例关掉了 json 输出、或者前面挡了一层人机验证），
                // 后者是「网上确实没搜到」。都报「没有结果」的话，用户会去改关键词，
                // 而真正该做的是换一个实例 —— 方向完全错了。
                //
                // 内置的 BING_HTML 走另一条路：它返回的**本来就是 HTML**，
                // 所以那里「解不出结果」意味着别的东西（见 parseBingHtml）
                val hits = if (config.backend == WebSearchBackend.BING_HTML) {
                    parseBingHtml(body)
                        ?: return@use ToolResult.error(structureChangedMessage(config))
                } else {
                    val root = parseRoot(body)
                        ?: return@use ToolResult.error(notJsonMessage(config, body))
                    parse(config.backend, root)
                }
                if (hits.isEmpty()) {
                    return@use ToolResult.ok(
                        "搜索「$query」没有返回任何结果（${config.backend.label}）。" +
                            "换一组更常见或更具体的关键词再试一次；" +
                            "如果还是没有，就直接告诉用户没查到，不要编。",
                    )
                }

                val notes = buildList {
                    if (truncated) add("响应超过 ${FetchUrlTool.MAX_BYTES / 1024}KB，已截断")
                }
                ToolResult.ok(render(config.backend, query, hits, notes))
            }
        } catch (e: IOException) {
            ToolResult.error(
                "连不上搜索服务（${endpoint.host}）：${e.message ?: e::class.simpleName}。" +
                    "可能是设备没网，也可能是这个地址在这台设备上访问不了。" +
                    "告诉用户这个情况，不要反复重试。",
            )
        }
    }

    // ---------- 请求构造 ----------

    /**
     * 返回 null 表示「这个后端需要密钥但没配」—— 调用方据此给出可操作的提示。
     *
     * ## 关于 `Accept-Encoding`
     *
     * Brave 在没有 `Accept-Encoding` 时会直接 422。这里**刻意不手动加**它：
     * OkHttp 在自己加这个头的时候，会顺带把响应解压好；一旦我们自己写死，
     * 它就认为「调用方自己会处理」，于是返回给我们的是 gzip 字节流。
     */
    private fun buildRequest(config: WebSearchConfig, endpoint: HttpUrl, query: String): Request? {
        val base = Request.Builder().header("User-Agent", USER_AGENT).header("Accept", "application/json")

        return when (config.backend) {
            // 内置后端抓的是**结果页 HTML**，所以刻意不继承上面那个
            // `Accept: application/json` —— 那等于告诉对方「给我 JSON」，
            // 而这里要的恰恰是 HTML。也不需要任何密钥
            WebSearchBackend.BING_HTML ->
                Request.Builder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .url(endpoint.newBuilder().addQueryParameter("q", query).build())
                    .get()
                    .build()

            WebSearchBackend.SEARXNG ->
                base.url(
                    endpoint.newBuilder()
                        .addQueryParameter("q", query)
                        .addQueryParameter("format", "json")
                        .build(),
                ).get().build()

            WebSearchBackend.BRAVE -> {
                val key = config.apiKey?.takeIf { it.isNotBlank() } ?: return null
                base.url(
                    endpoint.newBuilder()
                        .addQueryParameter("q", query)
                        .addQueryParameter("count", MAX_RESULTS.toString())
                        .build(),
                )
                    .header("X-Subscription-Token", key)
                    .get()
                    .build()
            }

            WebSearchBackend.TAVILY -> {
                val key = config.apiKey?.takeIf { it.isNotBlank() } ?: return null
                val payload =
                    buildJsonObject {
                        put("query", query)
                        put("max_results", MAX_RESULTS)
                        // basic 足够：advanced 会显著变慢，而模型的等待是有代价的
                        put("search_depth", "basic")
                    }.toString()
                base.url(endpoint)
                    .header("Authorization", "Bearer $key")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            }
        }
    }

    /**
     * 把状态码翻译成**下一步该做什么**。
     *
     * 光说「HTTP 403」模型只会换个说法重试同一个请求，而这三类失败
     * 重试一万次结果都一样 —— 所以每一条都要指明「这不是重试能解决的」。
     *
     * 内置后端（[WebSearchBackend.BING_HTML]）整个跳过这张表：它没有密钥、
     * 没有配额，失败只可能是「对方那边变了」（见下）。
     */
    private fun httpError(config: WebSearchConfig, code: Int, message: String): ToolResult {
        val what = "搜索失败：HTTP $code ${message.ifBlank { "" }}".trim()

        // 内置后端没有密钥、也没有配额 —— 它的失败**一定是**「对方那边变了」
        // （被反爬挡了、或者接口改了），不是用户的配置问题。
        // 所以建议只有一条：换后端。说「去检查密钥」会把用户引到错的地方
        if (config.backend == WebSearchBackend.BING_HTML) {
            return ToolResult.error(
                "$what。这是内置的免费后端，没有密钥或配额可查 —— " +
                    "多半是必应把这次请求当成爬虫挡掉了。重试和换关键词都没有用，" +
                    "告诉用户去「设置 → 联网搜索」换一个后端（Brave / Tavily 要自己注册 Key）。",
            )
        }

        val advice = when (code) {
            401 -> "${config.backend.label} 拒绝了密钥。这是配置问题，重试没有用 —— " +
                "告诉用户去「设置 → 联网搜索」检查 API Key。"

            403 -> if (config.backend == WebSearchBackend.SEARXNG) {
                // SearXNG 最常见的坑：默认不输出 JSON
                "SearXNG 默认只返回 HTML，不返回 JSON。需要在实例的 settings.yml 里" +
                    "把 search.formats 加上 json，或者换一个允许 JSON 输出的实例。" +
                    "这是配置问题，重试没有用，告诉用户去「设置 → 联网搜索」换地址。"
            } else {
                "${config.backend.label} 拒绝了这次请求（密钥无效、或者这个 Key 没有搜索权限）。" +
                    "重试没有用，告诉用户去「设置 → 联网搜索」检查。"
            }

            429 -> "${config.backend.label} 说请求太频繁或者配额用完了。" +
                "不要再连续重试，过一会儿再说，或者让用户去检查套餐额度。"

            in 500..599 -> "${config.backend.label} 那边出错了（服务端故障），" +
                "和配置无关。可以告诉用户稍后再试，不要在这个回合里反复重试。"

            else -> "告诉用户这次搜索没成功，不要反复重试。"
        }
        return ToolResult.error("$what。$advice")
    }

    // ---------- 响应解析 ----------

    /** 解析失败（不是 JSON 对象）返回 null，由调用方给「这不是 JSON」的提示。 */
    private fun parseRoot(body: String): JsonObject? =
        runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull()

    /**
     * 「HTTP 200 但返回的不是 JSON」。
     *
     * 这不是理论上的情况 —— 实测公共 SearXNG 实例里，大多数要么关掉了 json
     * 输出格式（返回 HTML 搜索页），要么挡在一层人机验证后面（返回一个
     * 「Making sure you're not a bot」页面）。两者都是 200。
     *
     * 所以这条提示必须说清「重试和换关键词都没用」，否则模型会一直换说法重试。
     */
    private fun notJsonMessage(config: WebSearchConfig, body: String): String {
        val looksHtml = body.trimStart().startsWith("<")
        val detail = if (looksHtml) "返回的是一段网页 HTML" else "返回的内容无法解析"
        val advice = when (config.backend) {
            WebSearchBackend.SEARXNG ->
                "常见原因：这个实例关掉了 json 输出格式，或者前面挡了一层人机验证。" +
                    "这是配置问题，换关键词重试没有用 —— 告诉用户去「设置 → 联网搜索」换一个实例" +
                    "（能稳定返回 JSON 的公共实例很少，通常得自己搭一个）。"
            WebSearchBackend.BRAVE, WebSearchBackend.TAVILY ->
                "多半是地址填错了（指到了别的接口）。重试没有用，" +
                    "告诉用户去「设置 → 联网搜索」检查地址。"
            // 内置后端到不了这里：它的响应本来就是 HTML，由 parseBingHtml 处理。
            // 留着这一支只是因为 when 要穷尽
            WebSearchBackend.BING_HTML ->
                "这是内置后端的问题，告诉用户去「设置 → 联网搜索」换一个后端。"
        }
        return "搜索失败：HTTP 200，但${detail}（${config.backend.label}）。$advice"
    }

    /**
     * 「内置后端拿到的不是结果页」。
     *
     * 和 [notJsonMessage] 是同一类问题的两个版本：都表示**对方那边变了**，
     * 重试和换关键词都没有用。区别只在于内置后端没有配置项可查 ——
     * 用户能做的只有换一个后端。
     *
     * 必须说清「和你的配置无关」：不然用户会去设置页反复检查一个根本不存在的
     * 地址 / 密钥（这个后端不需要它们）。
     */
    private fun structureChangedMessage(config: WebSearchConfig): String =
        "搜索失败：${config.backend.label} 返回的页面里找不到搜索结果" +
            "（多半是被当成爬虫挡掉了，或者对方改版了）。" +
            "这是内置后端的问题，和用户的配置无关，换关键词重试也没有用 —— " +
            "告诉用户去「设置 → 联网搜索」换一个后端（Brave / Tavily 要自己注册 Key）。"

    /**
     * 三种后端的响应结构不一样，这里归一成同一种。
     *
     * 全程防御式取值：响应体的形状**没有任何保证**（自建实例可以改模板、
     * 网关可以塞一层包装）。解析不出条目就返回空列表，由调用方给出
     * 「没搜到」的提示 —— 那比抛异常让整场对话断掉好得多。
     */
    private fun parse(backend: WebSearchBackend, root: JsonObject): List<WebHit> {
        val raw = when (backend) {
            // {"results":[{"title","url","content"}]}
            WebSearchBackend.SEARXNG -> root.objects("results")

            // {"web":{"results":[{"title","url","description"}]}}
            WebSearchBackend.BRAVE -> root.obj("web")?.objects("results").orEmpty()

            // {"results":[{"title","url","content"}]}
            WebSearchBackend.TAVILY -> root.objects("results")

            // 内置后端返回的是 HTML，走 parseBingHtml —— 到不了这里
            WebSearchBackend.BING_HTML -> emptyList()
        }

        return raw
            .map { item ->
                WebHit(
                    title = item.text("title").clean(),
                    url = item.text("url"),
                    // Brave 用 description，另外两个用 content
                    snippet = item.text("content").ifBlank { item.text("description") }.clean(),
                )
            }
            .filter { it.url.isNotBlank() }
            .take(MAX_RESULTS)
    }

    // ---------- 内置后端：从必应结果页的 HTML 里抠结果 ----------

    /**
     * 从必应结果页的 HTML 里抠出结果。**返回 null 表示「拿到的不是结果页」。**
     *
     * ## 为什么会有这条路径
     *
     * [WebSearchBackend.BING_HTML] 是唯一的零配置后端 —— 另外三个都要用户先填
     * 地址、注册 Key。没有它，「刚装好 App 的用户问一句『今天有什么新闻』」
     * 的结果是工具**根本不在列表里**（见 `WebSearchBackend.BING_HTML` 的 KDoc）。
     *
     * 代价是它**随时可能因为对方改版而失效**，所以这个函数的返回值必须让
     * 「页面变了」和「真的没搜到」分开：
     *
     * | 返回 | 含义 | 该告诉模型做什么 |
     * |---|---|---|
     * | `null` | 拿到的**不是结果页** —— 被反爬挡了、或者对方改版了 | 换后端，**不要重试** |
     * | 空列表 | 是结果页，但这次查询确实没结果 | 换关键词 |
     * | 非空 | 正常 | —— |
     *
     * 把前两者混成一个「没搜到」，是这类解析最典型的错：用户会一直换关键词，
     * 而真正该做的是换个后端。
     *
     * ## 为什么是必应中国版
     *
     * 两条：**国内可达**（DuckDuckGo 在国内通常打不开 —— Operit 也因此选了
     * Bing / 百度 / 搜狗 / 夸克），以及**结果链接是直接的**：`href` 就是落地
     * 网址，不是 `/link?url=` 那种跳转包装。后者很关键 —— 跳转包装要真的访问
     * 一次才能拿到落地地址，6 条结果就是 6 个额外请求。
     *
     * 对照：Operit 抓百度 / 搜狗时必须靠它自己的浏览器会话逐条「点」链接
     * （`Tools.Net.visit({visit_key, link_number})`），那套基建我们没有。
     *
     * ## 用的是字符串切分，不是 HTML 解析器
     *
     * `:tools` 必须保持纯 JVM（不能 `import android.text.Html`），而引入一个
     * HTML 解析库只为这一个后端不划算。正则在这里够用，因为必应的结果块
     * 结构是稳定的（`<li class="b_algo">` + `<h2>` + `b_caption`）——
     * 不稳定的话本来就是「结构变了」，那时返回 null 交给用户换后端。
     */
    private fun parseBingHtml(body: String): List<WebHit>? {
        // 结果列表的容器都不在 → 拿到的不是结果页。
        // 这一步**先做**，是为了把「页面结构变了」和「真的没搜到」分开：
        // 后者仍然带着 b_results 这个容器（只是里面没有 b_algo）
        if (!body.contains(RESULTS_MARKER)) return null

        return BING_ALGO.split(body)
            .drop(1)  // 第 0 段是 <ol id="b_results"> 之前的页面头
            .mapNotNull { parseBingBlock(it.substringBefore("</li>").take(BLOCK_LIMIT)) }
            .take(MAX_RESULTS)
    }

    /**
     * 解析单条结果。**标题或网址缺一个就丢弃** —— 缺网址的条目给模型也没用
     * （它没法把网址交给 `fetch_url`）。
     *
     * 网址有三级回退，因为必应把标题链接放在 `<h2>` 里面还是外面**变过**：
     * 实测中国版是 `<a href="..."><h2>标题</h2></a>`，但历史上出现过
     * `<h2><a href="...">标题</a></h2>`。最后一级退到「块里第一个外部链接」——
     * 那通常是来源站的图标链接，`href` 也是真实网址。
     */
    private fun parseBingBlock(block: String): WebHit? {
        val url = BING_LINK_AROUND_H2.find(block)?.groupValues?.get(1)
            ?: BING_LINK_IN_H2.find(block)?.groupValues?.get(1)
            ?: ANY_EXTERNAL_HREF.findAll(block)
                .map { it.groupValues[1] }
                .firstOrNull { !BING_OWN_HOST.containsMatchIn(it) }
            ?: return null

        val title = BING_TITLE.find(block)?.groupValues?.get(1).orEmpty()
        val snippet = BING_CAPTION.find(block)?.groupValues?.get(1).orEmpty()

        return WebHit(
            // 标题里必有 `<strong>`（必应把命中的词包起来），要过 stripHtml
            title = FetchUrlTool.stripHtml(title).clean(),
            // 网址也要过一遍：结果里的 `&` 是以 `&amp;` 写着的，
            // 不解码的话模型拿到的网址是坏的（交给 fetch_url 会 404）。
            // `stripHtml` 顺带做实体解码，复用它而不是再写一份
            url = FetchUrlTool.stripHtml(url).trim(),
            snippet = FetchUrlTool.stripHtml(snippet).clean(),
        )
    }

    /**
     * 给模型看的正文。
     *
     * 每条都给**标题 + 完整网址 + 摘要**：网址不能省 —— 模型要靠它
     * 判断来源可信度，而且需要正文时得把网址交给 `fetch_url`。
     * 只给标题的话，模型会把摘要当成全文来引用。
     */
    private fun render(
        backend: WebSearchBackend,
        query: String,
        hits: List<WebHit>,
        notes: List<String>,
    ): String {
        val head = buildString {
            append("搜索「$query」找到 ${hits.size} 条结果（${backend.label}）")
            if (notes.isNotEmpty()) append("。注意：${notes.joinToString("；")}")
            append("：")
        }

        val body = buildString {
            hits.forEachIndexed { index, hit ->
                appendLine()
                appendLine()
                appendLine("[${index + 1}] ${hit.title.take(MAX_TITLE_CHARS).ifBlank { "(无标题)" }}")
                appendLine("    ${hit.url}")
                append(hit.snippet.take(MAX_SNIPPET_CHARS))
            }
        }.trimEnd()

        // 超出总上限时**必须说出来**。不说的话模型会以为「网上就只有这些」，
        // 然后拿一个残缺的结果当成全部事实
        if (body.length <= MAX_CHARS) return "$head$body"

        return "$head${body.take(MAX_CHARS)}\n\n（结果过长，已截断。需要更多就用更具体的关键词再搜一次。）"
    }

    /** 把外部文本压成模型能读的一行：去 HTML、压空白。 */
    private fun String.clean(): String =
        FetchUrlTool.stripHtml(this).replace(WHITESPACE, " ").trim()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    /** 只认标量。模型/服务端可能把对象塞进本该是字符串的字段。 */
    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** 一条搜索结果。`url` 一定非空 —— 空网址的条目在 [parse] 里已经被滤掉。 */
    private data class WebHit(val title: String, val url: String, val snippet: String)

    companion object {
        const val NAME = "search_web"
        const val QUERY = "query"

        /**
         * 一次返回几条。
         *
         * 6 是权衡出来的：三条往往不够回答一个比较型问题（模型得再搜一次，
         * 而每次搜索都是几百毫秒的等待 + 一份配额），十条又会把上下文里
         * 一大块位置占给未必相关的内容。6 条 × 400 字 ≈ 1000 token。
         */
        const val MAX_RESULTS = 6

        /** 单条摘要上限。见类 KDoc 里「三层上限」的说明。 */
        const val MAX_SNIPPET_CHARS = 400

        /**
         * 单条标题上限。
         *
         * 标题本该是一行字，但**垃圾站会往 title 里塞整段 SEO 文本**，
         * 而那个字段的长度是服务端说了算的。不设上限的话，
         * 六条「标题」就能把整个结果预算吃掉。
         */
        const val MAX_TITLE_CHARS = 120

        /**
         * 整个结果的字符上限。
         *
         * 条数和单条摘要都封顶之后，正常响应是够不到这个数的 ——
         * 它兜的是**字段长度不受我们控制**的那部分：标题和网址。
         * 网址刻意不截断（模型要靠它交给 `fetch_url`），所以一条
         * 带超长查询串的网址就可能很长。
         */
        const val MAX_CHARS = 4_000

        // ---------- 内置后端（必应结果页）用的标记与正则 ----------

        /**
         * 结果列表的容器 id。**它的有无 = 「这是不是结果页」** ——
         * 见 [parseBingHtml] 为什么要先查它
         */
        private const val RESULTS_MARKER = "id=\"b_results\""

        /**
         * 单条结果最多看多少字符。
         *
         * 必应把样式表内联在结果块里（实测一个块能到几 KB），不封顶的话
         * 正则要在大段 CSS 上跑 —— 白花时间，回溯风险也高
         */
        private const val BLOCK_LIMIT = 20_000

        /**
         * 每条结果的开始。
         *
         * 刻意**不写死** `class="b_algo"`：class 里可能还有别的
         * （`b_algo b_algoBigWiki`），写死的话必应加一个修饰类就全军覆没。
         * 用词边界匹配、且一直吃到 `>`，中间不管有几个属性
         */
        private val BING_ALGO = Regex("""<li\s[^>]*\bb_algo\b[^>]*>""")

        /** `<a href="..."><h2>` —— 实测必应中国版是这个形态。 */
        private val BING_LINK_AROUND_H2 = Regex("""<a[^>]*href="([^"]+)"[^>]*>\s*<h2""")

        /** `<h2><a href="...">` —— 历史形态，留作保险。 */
        private val BING_LINK_IN_H2 = Regex("""<h2[^>]*>\s*<a[^>]*href="([^"]+)"""")

        /** 标题。必应会把命中的词包在 `<strong>` 里，所以拿到后还要过 `stripHtml`。 */
        private val BING_TITLE = Regex("""<h2[^>]*>(.*?)</h2>""", RegexOption.DOT_MATCHES_ALL)

        /** 摘要。 */
        private val BING_CAPTION =
            Regex("""class="b_caption"[^>]*>\s*<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)

        /** 任意绝对链接，网址回退的第三级用。 */
        private val ANY_EXTERNAL_HREF = Regex("""href="(https?://[^"]+)"""")

        /**
         * 必应自己的域名。结果块里混着指向它们自己的链接（图标代理、站内跳转、
         * 分享），回退那一级要把这些滤掉
         */
        private val BING_OWN_HOST =
            Regex("""https?://([^/]*\.)?(bing|microsoft|msn|live)\.com""")

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android) Patchbay/1.0 (+local BYOK client)"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val JSON = Json { ignoreUnknownKeys = true }

        private val WHITESPACE = Regex("\\s+")

        /**
         * 搜索比抓网页该快得多：用户在等一个回答，不是在等一个文件。
         * 15 秒还没结果就该报错让他重问，而不是继续挂着。
         */
        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        /**
         * 用一份**临时配置**跑一次真实搜索。给设置页的「测试」按钮用。
         *
         * ## 这个函数在扮演「宿主」
         *
         * 刻意复用 [execute] 而不是另写一条探测逻辑：设置页要验证的就是
         * 「模型真的调这个工具时会发生什么」。另写一条的话，测试能过、
         * 真跑不通（或者反过来）—— 那这个按钮就成了误导。
         *
         * 但 [execute] 是**在宿主提供的线程上**跑的：`Tool.execute` 的约定
         * 是「调用方已经切到后台调度器」（`ConversationEngine` 用
         * `withContext(Dispatchers.IO)` 保证这一点）。对话路径有这个保证，
         * 设置页没有 —— 它是从 `viewModelScope`（`Dispatchers.Main.immediate`）
         * 直接调的。**少了这一句就是 `NetworkOnMainThreadException`，
         * 而且整个 App 当场挂掉**（实测踩到过：点「测试」直接闪退）。
         *
         * 所以 [probe] 必须自己把宿主该做的两件事都做掉：
         *
         * 1. 切到后台调度器；
         * 2. 把异常转成 [ToolResult.error]（引擎也这么做）。
         *
         * 换句话说：**凡是不经过 `ConversationEngine` 的工具入口，
         * 都得自己把这套保证补上。** 这是这个接口的约定留下的口子。
         *
         * [query] 默认给一个中性词：探测只是要确认「地址通、密钥认」，
         * 不该把用户关心的东西当测试数据发出去。
         */
        suspend fun probe(
            config: WebSearchConfig,
            query: String = PROBE_QUERY,
            client: OkHttpClient = defaultClient(),
        ): ToolResult = withContext(Dispatchers.IO) {
            try {
                SearchWebTool(FixedWebSearchSource(config), client).execute(
                    buildJsonObject { put(QUERY, query) },
                )
            } catch (e: CancellationException) {
                // 用户离开了设置页，不是工具出错 —— 原样抛出，
                // 否则会被下面吞掉当成「测试失败」
                throw e
            } catch (t: Throwable) {
                ToolResult.error("测试失败：${t.message ?: t::class.simpleName}")
            }
        }

        internal const val PROBE_QUERY = "hello"
    }
}
