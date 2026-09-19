package com.aichat.tools

import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer

/**
 * 抓一个网页/接口的内容回来。
 *
 * ## 这个工具为什么需要用户确认
 *
 * 它是**由模型决定目标地址**的对外请求。风险不在于「读」，
 * 而在于地址是模型选的：
 *
 * - 提示注入可以让模型去请求 `http://内网地址/admin`，把结果带进上下文；
 * - URL 里的查询串会连同用户 IP 一起泄露给第三方。
 *
 * 所以 [requiresConfirmation] 为 true —— 写操作和对外动作一律要人点头。
 * （当前编排层用的是 `ToolApprover.AllowAll`，弹窗 UI 还没做；
 * 但分类必须现在就写对，否则等 UI 接上时就成了默认放行。）
 *
 * ## 硬限制
 *
 * 不加限制的抓取工具是自伤：一个 50MB 的页面会直接撑爆上下文和内存。
 * 所以协议白名单、类型白名单、字节上限、字符上限、超时，一个都不能少。
 */
class FetchUrlTool(
    private val client: OkHttpClient = defaultClient(),
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        description = "抓取一个网页或 JSON 接口的文本内容。" +
            "需要读某个具体网址、或者查一个你知道确切地址的资料时用它。" +
            "只能读公开可访问的 http/https 地址，不能提交表单、不能登录。" +
            "抓回来的内容只是**原始文本**，不是你的知识 —— 与已有认知冲突时以抓回来的为准。",
        parameters = schema(
            properties = mapOf(
                URL to stringParam(
                    "完整地址，必须带 http:// 或 https://，" +
                        "例如 https://example.com/api/status",
                ),
            ),
            required = listOf(URL),
        ),
    )

    override val userSummary: String
        get() = "向你下面指定的地址发起一次网络请求，并把返回的内容读进对话"

    override val requiresConfirmation: Boolean get() = true

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val raw = arguments.stringOrNull(URL)
            ?: return ToolResult.error("缺少参数 $URL。请给出完整地址，要带 http:// 或 https://。")

        // 协议检查必须在解析之前。
        //
        // `toHttpUrlOrNull()` 只认 http/https，`file:///etc/hosts` 会被它拒掉，
        // 于是报出来的是「需要带协议的完整地址」—— 而那个地址**明明带协议**。
        // 模型收到这种自相矛盾的提示只会换个说法重试同一种写法，
        // 所以要在这里先把它拦下来，并明确指出支持哪几种协议。
        val scheme = raw.substringBefore("://").lowercase().takeIf { raw.contains("://") }
        if (scheme != null && scheme != "http" && scheme != "https") {
            return ToolResult.error(
                "只支持 http 和 https，不支持「$scheme」。读本地文件不在这个工具的能力范围内。",
            )
        }

        // 用 toHttpUrlOrNull 而不是已废弃的 HttpUrl.get()：
        // 前者解析失败返回 null 而不是抛异常，正好符合「模型给的地址不可信」的前提。
        // 它本身也只返回 http/https，所以上面查过协议之后这里不需要再查一遍。
        val parsed = raw.toHttpUrlOrNull()
            ?: return ToolResult.error(
                "「$raw」不是一个合法的地址。需要带协议的完整地址，例如 https://example.com",
            )

        val request = Request.Builder()
            .url(parsed)
            // 有些站点对没有 UA 的请求直接 403
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/json,text/plain,*/*;q=0.8")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use ToolResult.error(
                        "抓取失败：HTTP ${response.code} ${response.message}。" +
                            "地址是 $raw。如果 404 就说明这个路径不对，换个地址或者放弃。",
                    )
                }

                val contentType = response.body.contentType()?.toString().orEmpty()
                if (contentType.isNotBlank() && !isTextLike(contentType)) {
                    return@use ToolResult.error(
                        "这个地址返回的是 $contentType，不是文本（可能是图片或文件），读不了内容。",
                    )
                }

                val charset = response.body.contentType()?.charset() ?: Charsets.UTF_8
                val (text, truncated) = readBounded(response.body.source(), charset)

                if (text.isBlank()) {
                    return@use ToolResult.error("抓到了，但内容为空（$raw）。")
                }

                val isHtml = contentType.contains("html", ignoreCase = true)
                val body = if (isHtml) stripHtml(text) else text
                val clipped = body.take(MAX_CHARS)

                val notes = buildList {
                    if (truncated) add("响应超过 ${MAX_BYTES / 1024}KB，已截断")
                    if (body.length > MAX_CHARS) add("内容过长，只保留前 $MAX_CHARS 字")
                }

                ToolResult.ok(
                    buildString {
                        appendLine("来源：$raw")
                        if (notes.isNotEmpty()) appendLine("注意：${notes.joinToString("；")}")
                        appendLine()
                        append(clipped)
                    },
                )
            }
        } catch (e: IOException) {
            ToolResult.error(
                "连不上 $raw：${e.message ?: e::class.simpleName}。" +
                    "检查地址拼写，或者这个站点在这台设备上访问不了。",
            )
        }
    }

    companion object {
        const val NAME = "fetch_url"
        const val URL = "url"

        internal const val MAX_BYTES = 256 * 1024
        internal const val MAX_CHARS = 8_000

        // 会发给被抓取的网站。改应用名时要一起改，否则对外自称的还是旧名字
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android) Patchbay/1.0 (+local BYOK client)"

        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // 不设 callTimeout：它是整个请求（含重定向与读流）的硬上限，
            // 慢站会被它误杀，而上面的读写超时已经能兜住卡死
            .followRedirects(true)
            .build()

        /** 只收文本类。用白名单而不是黑名单 —— 未知类型一律当二进制处理。 */
        internal fun isTextLike(contentType: String): Boolean {
            val type = contentType.substringBefore(';').trim().lowercase()
            return type.startsWith("text/") ||
                type == "application/json" ||
                type == "application/xml" ||
                type == "application/xhtml+xml" ||
                type.endsWith("+json") ||
                type.endsWith("+xml")
        }

        /**
         * 最多读 [MAX_BYTES] 字节。
         *
         * 不能用 `body.string()`：那是「全部读进内存」，一个 1GB 的响应
         * 会把 App 直接 OOM 掉 —— 而地址是模型给的，它可能根本不知道那是个大文件。
         */
        internal fun readBounded(
            source: okio.BufferedSource,
            charset: java.nio.charset.Charset,
        ): Pair<String, Boolean> {
            val sink = Buffer()
            var remaining = MAX_BYTES.toLong()
            var truncated = false
            while (remaining > 0) {
                val read = source.read(sink, remaining)
                if (read == -1L) break
                remaining -= read
                if (remaining == 0L) truncated = true
            }
            return sink.readString(charset) to truncated
        }

        /**
         * 把 HTML 变成能读的纯文本。
         *
         * 刻意用正则而不是引 HTML 解析器：这里的目的是**让模型读懂大意**，
         * 不是精确还原 DOM。多一个依赖、多一份攻击面，换不来实质收益。
         * 所以先整块丢掉 script/style（它们的正文对模型是噪声，还特别长），
         * 再去标签、解实体、压空白。
         */
        internal fun stripHtml(html: String): String {
            var text = html
            text = SCRIPT_STYLE.replace(text, " ")
            text = COMMENT.replace(text, " ")
            // 块级标签换成换行，避免「第一段最后一句第二段第一句」粘成一行
            text = BLOCK_TAG.replace(text, "\n")
            text = ANY_TAG.replace(text, " ")
            text = ENTITY.replace(text) { match ->
                when (match.groupValues[1]) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    else -> match.value
                }
            }
            text = NUMERIC_ENTITY.replace(text) { match ->
                match.groupValues[1].toIntOrNull()
                    ?.takeIf { it in 1..0x10FFFF }
                    ?.let { code -> String(Character.toChars(code)) }
                    ?: match.value
            }
            return text
                .lineSequence()
                .map { it.replace(WHITESPACE, " ").trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        }

        private val SCRIPT_STYLE =
            Regex("(?is)<(script|style|noscript|svg|head)\\b.*?</\\1\\s*>")
        private val COMMENT = Regex("(?s)<!--.*?-->")
        private val BLOCK_TAG =
            Regex("(?i)</?(p|div|br|li|tr|h[1-6]|section|article|header|footer|table)\\b[^>]*>")
        private val ANY_TAG = Regex("(?s)<[^>]*>")
        private val ENTITY = Regex("(?i)&(amp|lt|gt|quot|apos|nbsp);")
        private val NUMERIC_ENTITY = Regex("&#(\\d+);")
        private val WHITESPACE = Regex("\\s+")
    }
}
