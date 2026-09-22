package com.aichat.plugin.runtime

import com.aichat.domain.text.errorDetail
import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolResult
import com.aichat.plugin.manifest.HttpMethod
import com.aichat.plugin.manifest.RequestSpec
import com.aichat.plugin.manifest.ToolSpec
import com.aichat.plugin.permission.NetworkDeniedException
import com.aichat.plugin.permission.NetworkGuard
import com.aichat.plugin.template.Placeholder
import com.aichat.plugin.template.Placeholders
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 声明式工具：清单里的一段 JSON 变成模型能调用的一个工具。
 *
 * ## 这个类里唯一重要的原则：**永远不拼字符串**
 *
 * 把 `{{参数}}` 替换进 URL 的最直觉写法是 `baseUrl + path.replace("{{id}}", id)`，
 * 而那是这个系统里最容易被攻破的地方。模型给的参数是不可信输入 ——
 * 提示注入可以让它填出这样的值：
 *
 * | 模型给的值 | 字符串拼接的后果 |
 * |---|---|
 * | `../../admin` | 路径穿越，打到另一个接口 |
 * | `a?x=1` | 凭空多出一个查询参数 |
 * | `a&token=…` | 在已有的查询串里注入参数 |
 * | `a%2F..%2Fb` | 二次编码绕过 |
 * | `a\nHost: evil.com` | 请求头注入（如果进了 header） |
 *
 * 所以这里的做法是：**占位符只负责产出「值」，位置由 builder 决定。**
 * 路径段走 `addPathSegment`（它会把 `/` 编码成 `%2F`，模型没法多造一段），
 * 查询参数走 `addQueryParameter`（`&` `=` `#` 一律编码），
 * 请求体走 `JsonObject`（`"` 和 `\` 由序列化器转义）。
 *
 * 三处都不给「值」任何机会变成「结构」。
 *
 * ## 结果为什么要截断
 *
 * 不加限制的插件调用是自伤：一个返回 50MB 的接口会直接撑爆上下文。
 * 和 `FetchUrlTool` 是同一个理由，所以这里也用同一套上限。
 */
class DeclarativeTool(
    private val pluginName: String,
    private val baseUrl: HttpUrl,
    private val spec: ToolSpec,
    /**
     * 工具对应的请求模板。
     *
     * 是**构造参数**而不是从 [spec] 里取 —— 校验阶段已经保证了声明式工具的
     * `request` 非空，把这件事编码进类型之后，这个类里就不可能出现
     * 「拿一个 null 的 request 去拼 URL」的分支。
     */
    private val request: RequestSpec,
    private val settings: PluginSettings,
    private val guard: NetworkGuard,
    private val client: OkHttpClient,
    private val auth: AuthPlan = AuthPlan.None,
    /**
     * 插件级的静态请求头（`entry.declarative.headers`）。
     *
     * 传进来的是**渲染好的**结果，不是模板 —— 渲染发生在装配期
     * （见 `PluginHost`），于是「`{{settings.xxx}}` 拼错」这类问题在安装时
     * 就报得出来，而不是等到某一次调用才变成一个看不懂的 401。
     *
     * 只支持 `{{settings.*}}`，不支持参数占位符：这段头被这个插件的
     * 所有工具共用，而参数是每个工具各自的东西。校验层会拦住这种写法。
     */
    private val headers: Map<String, String> = emptyMap(),
) : Tool {

    override val definition = ToolDefinition(
        name = spec.name,
        description = spec.description,
        parameters = spec.parameters,
    )

    /**
     * 给用户看的一句话。
     *
     * 刻意**不复用** [definition] 的 description：那段是写给模型的提示词
     * （「用户只说城市名时先用 geocode_city」这种对模型说的话），
     * 原样展示给用户既奇怪又长，会把弹框撑满，用户反而不看参数了。
     *
     * 用户真正要判断的是「这次请求会发给谁、会不会改东西」，所以这里给的是
     * 主机名（白名单实际约束的东西）加上**为什么会被问**。第二点很重要：
     * 作者没开确认弹窗、是宿主按方法兜底问的，用户看到一句解释才不会觉得
     * 这个 App 在乱弹窗。
     */
    /**
     * 这次请求实际会打到哪个主机。
     *
     * 工具可以用 `request.host` 覆盖插件级的 `baseUrl` 主机名
     * （真实的 API 常常把端点分散在 `api.x.com` / `auth.x.com` 这样的
     * 不同子域上）。**凡是给用户或模型看主机名的地方都要走这里** ——
     * 直接用 `baseUrl.host` 会在覆盖时显示成另一个主机，
     * 而用户正是照着那句话决定要不要放行的。
     */
    private val targetHost: String get() = request.host ?: baseUrl.host

    override val userSummary: String
        get() = buildString {
            append("插件「$pluginName」将向 $targetHost 发起一次 ${request.method.wire} 请求")
            when {
                guard.allowsAnyHost ->
                    append("。这个插件声明可以访问任意主机，所以每次都要你确认")

                request.method != HttpMethod.Get ->
                    append("。非 GET 请求可能改动服务端数据，所以每次都要你确认")
            }
            append("。")
        }

    /**
     * 是否弹窗确认。规则见 [ToolSpec.requiresConfirmation] 的 KDoc：
     *
     * 1. 作者写了值 → 听作者的
     * 2. 作者没写 → 按 HTTP 方法兜底，只有 GET 免确认
     * 3. 无论作者怎么写，**声明了任意主机就一律确认** ——
     *    那等于把「请求发去哪」交给模型决定，和内置的 `fetch_url` 是同一件事，
     *    而 `fetch_url` 是要确认的
     */
    override val requiresConfirmation: Boolean
        get() = guard.allowsAnyHost || (spec.requiresConfirmation ?: (request.method != HttpMethod.Get))

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val url = try {
            buildUrl(arguments)
        } catch (e: TemplateFailure) {
            return ToolResult.error(e.message ?: "请求参数不完整")
        }

        guard.check(url)?.let { return ToolResult.error(it) }

        val httpRequest = try {
            buildRequest(url, arguments)
        } catch (e: TemplateFailure) {
            return ToolResult.error(e.message ?: "请求参数不完整")
        }

        val response = try {
            guard.call(client, httpRequest)
        } catch (e: NetworkDeniedException) {
            return ToolResult.error(e.message ?: "这个地址不在插件的网络白名单里")
        } catch (e: IOException) {
            // 网络故障和「被白名单拒绝」要分开报：前者可以重试，后者重试没有意义。
            // 混成一句话的话，模型会对着一个永远不会成功的地址反复试
            return ToolResult.error(
                "请求 ${url.host} 失败：${errorDetail(e)}。" +
                    "这是网络问题，可以稍后重试。",
            )
        }

        return response.use { readResult(it, url) }
    }

    // ------------------------------------------------------------------ 拼 URL

    /**
     * 按段拼出最终地址。
     *
     * ## baseUrl 的路径部分会被保留
     *
     * `baseUrl = https://api.example.com/v2` + `path = /users`
     * → `https://api.example.com/v2/users`。
     *
     * 这是刻意的：如果按 RFC 3986 的语义让开头的 `/` 回到根，
     * 那 `baseUrl` 里写的前缀就会被**静默丢掉** —— 作者写了一个看起来生效、
     * 实际不生效的前缀，然后对着 404 怀疑人生。保留前缀是更可预测的规则。
     */
    private fun buildUrl(arguments: JsonObject): HttpUrl {
        val builder = HttpUrl.Builder()
            .scheme(baseUrl.scheme)
            // scheme 和端口**只从 baseUrl 来**，工具只能改主机名。
            // 允许整段 URL 的话，作者可以写 http:// 把密钥明文发出去，
            // 而安装界面上那行权限声明完全不会变
            .host(targetHost)
            .port(baseUrl.port)

        baseUrl.pathSegments.filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }

        // 路径段逐个加：addPathSegment 会把值里的 `/` 编码成 %2F，
        // 所以模型给的参数没法凭空多造出路径层级
        for (segment in request.path.split('/')) {
            if (segment.isEmpty()) continue
            val value = renderSegment(segment, arguments, "$.request.path")
            if (value.isEmpty()) {
                throw TemplateFailure("路径里的 `$segment` 渲染成了空值，无法拼出有效地址。请检查对应参数是否给了值。")
            }
            builder.addPathSegment(value)
        }

        for ((name, template) in request.query) {
            val value = renderQueryValue(template, arguments) ?: continue
            builder.addQueryParameter(name, value)
        }

        applyAuth(builder)

        return builder.build()
    }

    /**
     * 把认证信息放进查询串。
     *
     * 放在这里而不是 [buildRequest]，是因为它要改的是 URL 而不是 header ——
     * 而 URL 已经 [build] 完就不能再动了。
     *
     * 用户没填密钥时**静默不加**：这个分支只负责 URL 的形状，
     * 「密钥没填」这件事由 [buildRequest] 统一报出来（那里能给出更完整的一句话）。
     * 两处都报会让模型收到两条措辞不同的提示。
     */
    private fun applyAuth(builder: HttpUrl.Builder) {
        val plan = auth as? AuthPlan.Query ?: return
        val value = settings.value(plan.settingKey) ?: return
        builder.addQueryParameter(plan.queryName, value)
    }

    // ------------------------------------------------------------------ 渲染

    /**
     * 渲染一段路径。
     *
     * 缺失的参数在这里是**错误**而不是省略：路径段少一段就是打到别的接口上，
     * 静默省略只会得到一个莫名其妙的 404。
     */
    private fun renderSegment(segment: String, arguments: JsonObject, where: String): String {
        val rendered = Placeholders.render(segment) { lookup(it, arguments) }
        if (rendered.missing.isNotEmpty()) {
            throw TemplateFailure(
                "$where 里的占位符 ${rendered.missing.joinToString("、") { "`{{$it}}`" }} 没有对应的值。" +
                    "路径参数必须由模型提供。",
            )
        }
        return rendered.text
    }

    /**
     * 渲染一个查询参数值。
     *
     * 缺失时返回 null 表示**省略这个参数**（而不是发一个空值）：
     * `?count=` 和「没有 count」对服务端常常是两种不同的行为，
     * 而作者写可选参数时的意图几乎一定是后者。
     */
    private fun renderQueryValue(template: String, arguments: JsonObject): String? {
        val rendered = Placeholders.render(template) { lookup(it, arguments) }
        if (rendered.missing.isNotEmpty()) return null
        // 整个值就是一个占位符且没值时，上面已经返回 null；
        // 这里再挡一次「模板里混了常量但占位符缺失」的情况
        return rendered.text.takeIf { it.isNotEmpty() }
    }

    /**
     * 占位符取值。
     *
     * 设置项优先于参数 —— 两者的名字空间其实不会冲突（参数用 `{{x}}`、
     * 设置用 `{{settings.x}}`），这里显式分开是为了让读代码的人一眼看清。
     */
    private fun lookup(placeholder: Placeholder, arguments: JsonObject): String? = when (placeholder) {
        is Placeholder.Argument -> arguments[placeholder.name].asDisplayString()
        is Placeholder.Setting -> settings.value(placeholder.name)
        is Placeholder.Malformed -> null
    }

    // ------------------------------------------------------------------ 发请求

    /**
     * 拼出最终请求。
     *
     * 认证在这里用 `when` 穷举 —— [AuthPlan] 是密封接口，少写一个分支编译器会拦下来。
     * 用旧的「可空字段 + type 判别」写法时，`type=header` 但 `headerName=null`
     * 这种情况要靠运行时的 `?:` 兜底，忘写一处就是一个静默发出去的裸请求。
     */
    private fun buildRequest(url: HttpUrl, arguments: JsonObject): Request {
        val builder = Request.Builder().url(url)

        // 插件级的头先加、工具自己的后加 —— 更具体的赢。
        // 用 header() 而不是 addHeader()：后者会保留同名的旧值，
        // 于是「插件声明一个 + 运行时再加一个」会变成两个头，
        // 而服务端对重复头的处理各不相同
        for ((name, value) in headers) builder.header(name, value)

        // 只在作者没声明 Accept 时才给默认值，否则会把作者的 xml/protobuf 覆盖掉
        if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
            builder.header("Accept", "application/json")
        }

        when (val plan = auth) {
            is AuthPlan.Bearer -> {
                builder.header("Authorization", "Bearer ${requireSetting(plan.settingKey)}")
            }

            is AuthPlan.Header -> {
                builder.header(plan.headerName, requireSetting(plan.settingKey))
            }

            // query 类型的认证在 buildUrl 里加了（它要改 URL，不能在这里做）
            is AuthPlan.Query, AuthPlan.None -> Unit
        }

        val body = buildBody(arguments)
        return when (request.method) {
            HttpMethod.Get -> builder.get()
            HttpMethod.Delete -> if (body == null) {
                builder.delete()
            } else {
                builder.method("DELETE", body)
            }

            HttpMethod.Post -> builder.post(body ?: EMPTY_JSON_BODY)
            HttpMethod.Put -> builder.put(body ?: EMPTY_JSON_BODY)
            HttpMethod.Patch -> builder.patch(body ?: EMPTY_JSON_BODY)
        }.build()
    }

    /**
     * 取一个「缺了就没法发请求」的配置项。
     *
     * 报错文案刻意指向**用户动作**而不是技术细节：模型收到这句话之后
     * 唯一正确的做法是转告用户去填，写「settingKey 为空」它只会照着念。
     */
    private fun requireSetting(key: String): String =
        settings.value(key) ?: throw TemplateFailure(
            "插件「$pluginName」需要先配置「$key」才能调用，但用户还没有填。" +
                "请让用户到插件设置里补上，不要重试。",
        )

    /**
     * 拼请求体。
     *
     * ## 「整段就是一个占位符」时会保留原始类型
     *
     * `{"count": "{{count}}"}` 里的 `{{count}}` 如果按字符串替换，
     * 一个整数参数会变成 `"5"`（带引号的字符串），而服务端 schema 要的是数字。
     * 所以当字符串**恰好等于**一个占位符时，直接把参数的原始 JSON 值放进去 ——
     * 类型得以保留。
     *
     * 这不会引入注入：值的类型和转义都由 `JsonElement` 序列化器负责，
     * 参数是对象或数组时也只是原样嵌进去，不可能越出它所在的位置。
     */
    private fun buildBody(arguments: JsonObject): okhttp3.RequestBody? {
        val template = request.body ?: return null
        val resolved = resolveElement(template, arguments)
        return json.encodeToString(JsonElement.serializer(), resolved)
            .toRequestBody(JSON_MEDIA_TYPE)
    }

    private fun resolveElement(element: JsonElement, arguments: JsonObject): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) -> put(key, resolveElement(value, arguments)) }
        }

        is JsonArray -> JsonArray(element.map { resolveElement(it, arguments) })

        is JsonPrimitive -> if (element.isString) {
            resolveString(element.content, arguments)
        } else {
            element
        }
    }

    private fun resolveString(template: String, arguments: JsonObject): JsonElement {
        val whole = WHOLE_PLACEHOLDER.matchEntire(template)
        if (whole != null) {
            val placeholder = Placeholders.parse(whole.groupValues[1])
            if (placeholder is Placeholder.Argument) {
                return arguments[placeholder.name] ?: JsonNull
            }
            if (placeholder is Placeholder.Setting) {
                val value = settings.value(placeholder.name)
                return if (value == null) JsonNull else JsonPrimitive(value)
            }
        }

        val rendered = Placeholders.render(template) { lookup(it, arguments) }
        // 缺失的占位符在请求体里按 null 处理，而不是留一串花括号发给服务端
        return if (rendered.missing.isEmpty()) {
            JsonPrimitive(rendered.text)
        } else {
            JsonNull
        }
    }

    // ------------------------------------------------------------------ 读结果

    /**
     * 读响应体失败时给模型的那句话。
     *
     * 和上面 `execute` 里那条「请求 X 失败」是**同一类瞬时故障**：连接已经建起来、
     * 响应头也收到了，只是读 body 时断了或超时。那条说了「可以稍后重试」，
     * 这条也必须说 —— 否则模型会以为是自己参数的问题，方向就错了。
     *
     * ## 为什么抽成一个函数
     *
     * 这条分支（响应头已回、读 body 时才断）在 JVM 测试栈上**造不出来**：
     * `mockwebserver3` 5.x 移除了 `SocketPolicy`，谎报 `Content-Length` 无效
     * （MockWebServer 自己算长度），而自定义 `ResponseBody` 要过 `Okio.buffer(...)`，
     * 那个 Java 入口已废弃、换成扩展函数又得改测试文件的 import 区。
     *
     * 所以退一步：把**那句话**抽出来直接测。判据是「这句话里有没有那个动作」，
     * 而这正是这条改动唯一要保证的事 —— 见 `DeclarativeToolTest` 里那条用例。
     *
     * [detail] 由调用点传 `errorDetail(e)` —— 「message 为 null / 只有空白时说什么」
     * 这条语义只有一份实现（在 `:domain` 的 `ErrorText` 里，那里测得到），
     * 这里不再自己判一遍。
     */
    internal fun readFailedMessage(detail: String): String =
        "读取响应失败：$detail。这是网络问题，可以稍后重试。"

    private fun readResult(response: okhttp3.Response, url: HttpUrl): ToolResult {
        val body = try {
            response.peekBody(MAX_BYTES.toLong()).string()
        } catch (e: IOException) {
            // 兜底交给 `errorDetail`：`e.message` 可能为 null，而 `e::class.simpleName`
            // 对**匿名类**也是 null —— 两个都不能直接摆给模型看（§111.15 / §111.16）
            return ToolResult.error(readFailedMessage(errorDetail(e)))
        }

        if (!response.isSuccessful) {
            // 把服务端返回的内容带上一小段：API 的 4xx 通常带一句人能看懂的原因
            // （"invalid api key"、"latitude must be between -90 and 90"），
            // 只回一个状态码的话，模型只能瞎猜
            val hint = body.trim().take(400).ifEmpty { "（响应体为空）" }
            return ToolResult.error(
                "请求 ${url.host} 返回 HTTP ${response.code}。服务端说：$hint",
            )
        }

        val extracted = extract(body) ?: return ToolResult.ok(truncate(body))

        return ToolResult.ok(truncate(extracted))
    }

    /**
     * 按 [RequestSpec.responsePath] 从响应里取一段。
     *
     * 解析失败时**返回 null 让调用方回退到原始文本**，而不是报错：
     * 服务端返回了非 JSON（HTML 错误页、纯文本）时，把原文给模型看
     * 比报「responsePath 取不到」有用得多。
     */
    private fun extract(body: String): String? {
        val path = request.responsePath?.trim().orEmpty()
        if (path.isEmpty()) return null

        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null

        var current: JsonElement = root
        for (rawPart in path.split('.')) {
            val part = rawPart.trim()
            if (part.isEmpty()) return null

            val index = INDEX_PATTERN.find(part)
            val key = if (index == null) part else part.substring(0, index.range.first)

            if (key.isNotEmpty()) {
                current = (current as? JsonObject)?.get(key) ?: return null
            }
            index?.groupValues?.get(1)?.toIntOrNull()?.let { i ->
                current = (current as? JsonArray)?.getOrNull(i) ?: return null
            }
        }

        return render(current)
    }

    /** 把抽出来的那一段转成给模型看的文本。对象/数组给 JSON，标量给裸内容。 */
    private fun render(element: JsonElement): String = when (element) {
        is JsonObject, is JsonArray -> json.encodeToString(JsonElement.serializer(), element)
        is JsonPrimitive -> element.content
    }

    private fun truncate(text: String): String =
        if (text.length <= MAX_CHARS) {
            text
        } else {
            text.take(MAX_CHARS) +
                "\n\n（内容过长已截断：原文 ${text.length} 字，只保留前 $MAX_CHARS 字。" +
                "需要完整内容请让用户换个更精确的查询。）"
        }

    private companion object {
        val json = Json { prettyPrint = false }

        /** 上限和 `FetchUrlTool` 保持一致，理由相同：不加限制的抓取会撑爆上下文。 */
        const val MAX_BYTES = 256 * 1024
        const val MAX_CHARS = 20_000

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val EMPTY_JSON_BODY = "{}".toRequestBody(JSON_MEDIA_TYPE)

        /** 字符串恰好等于一个占位符，例如 `"{{count}}"`。 */
        val WHOLE_PLACEHOLDER = Regex("""^\{\{([^{}]*)\}\}$""")

        /** `list[0]` 这种带下标的段。 */
        val INDEX_PATTERN = Regex("""\[(\d+)]$""")
    }
}

/** 模板渲染出的问题。内部异常，会被翻成 `ToolResult.error` 回灌给模型。 */
private class TemplateFailure(message: String) : Exception(message)

/**
 * 认证在运行时的形态。
 *
 * 和清单里的 `manifest.AuthSpec` 刻意分开：清单那份是**外部输入**
 * （字段可空、需要校验），这一份是**校验通过之后**的结论 ——
 * 用密封类表达「要么不认证、要么以某种确定的方式认证」，
 * 于是每个使用点都不必再处理「type 说是 header 但 headerName 是 null」
 * 这种已经被校验排除掉的情况。
 */
sealed interface AuthPlan {
    /**
     * 密钥取自哪个配置项。[None] 没有密钥，所以是可空的。
     *
     * ## 为什么把它提到接口上
     *
     * 因为「这次认证用的是哪个配置项」是**所有**使用点都要问的问题，
     * 而不只是发请求的那一处：装配期要检查用户填了没有（没填要在界面上
     * 说一句）、缓存指纹要能描述认证方式、诊断信息要能说出「缺的是哪一项」。
     * 每个使用点各写一遍 `when` 的话，将来加一种认证方式就得改四处 ——
     * 而漏掉一处不会编译报错，只会让那个功能静默失效。
     */
    val settingKey: String?

    data object None : AuthPlan {
        override val settingKey: String? get() = null
    }

    data class Bearer(override val settingKey: String) : AuthPlan

    data class Header(override val settingKey: String, val headerName: String) : AuthPlan

    data class Query(override val settingKey: String, val queryName: String) : AuthPlan
}

/** 模型给的参数转成可以放进模板的字符串。 */
private fun JsonElement?.asDisplayString(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content.takeIf { it.isNotEmpty() }
    // 对象/数组塞进标量位置时不报错，给它的 JSON 文本 ——
    // 有些接口确实接受 JSON 编码过的参数，而报错会让模型无从调整
    is JsonObject, is JsonArray -> toString()
}