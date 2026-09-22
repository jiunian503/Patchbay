package com.aichat.plugin.permission

import java.net.URI
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 网络白名单守卫。
 *
 * ## 为什么这是一个单独的类，而不是插件运行时里的几行 if
 *
 * 「插件能不能访问某个地址」是这个系统里最需要**被穷举验证**的一条规则。
 * 它有三个容易漏的角落，每一个都能让白名单形同虚设：
 *
 * 1. **重定向。** 白名单里的域名可以 302 到任意域名。只检查第一个请求的
 *    host，等于把白名单交给了对端决定。所以这里**关掉自动重定向**，自己一跳一跳跟。
 * 2. **端口。** `api.example.com` 在白名单里，不代表 `api.example.com:22` 也该放行。
 *    这里放行任意端口（本机调试要用 `127.0.0.1:8765`），但把 host 的匹配
 *    收紧到大小写不敏感的全等 —— 不做后缀匹配。
 * 3. **后缀匹配。** 这是最经典的漏洞：用 `host.endsWith("example.com")`
 *    会连 `evilexample.com` 一起放行。所以这里只做全等（外加显式的 `*`）。
 *
 * ## 为什么子域通配也刻意不支持
 *
 * `*.example.com` 看起来很自然，但它是一次**静默的权限扩张**：
 * 它会把 `evil.example.com` 一起放行，而用户在安装界面上看到的只是一行
 * 很窄的声明。需要多个子域就逐个列出来 —— 多写几行的成本，
 * 远低于「以为限住了其实没限住」。
 */
class NetworkGuard(declared: List<String>) {

    private val wildcard: Boolean = declaresAnyHost(declared)

    /** 归一化成小写后做全等匹配。主机名大小写不敏感。 */
    private val hosts: Set<String> = declared
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && it != "*" }
        .toSet()

    /** 给人看的声明内容，用在拒绝原因里。 */
    private val declaredForDisplay: List<String> =
        if (wildcard) listOf("*（任意主机）") else hosts.sorted()

    val allowsAnyHost: Boolean get() = wildcard

    fun allows(host: String): Boolean = wildcard || host.lowercase() in hosts

    /**
     * 检查一个地址能不能访问。
     *
     * @return null 表示放行；否则是**给模型看**的拒绝原因。
     *
     * 为什么把原因写给模型：模型收到「这个主机不在白名单里，插件只被允许访问
     * api.example.com」之后，可以换个参数重试或者直接告诉用户。只回一句
     * 「被拒绝」的话，它多半会换个写法重试同一个地址，白白多跑几轮。
     */
    fun check(url: HttpUrl): String? {
        if (allows(url.host)) return null
        return "插件没有被授权访问 ${url.host}。" +
            "它声明可访问的主机是：${declaredForDisplay.ifEmpty { listOf("（没有声明任何主机）") }.joinToString("、")}。" +
            "请换用被允许的地址，或让用户去插件设置里确认权限。"
    }

    /**
     * 发请求，自己处理重定向，每一跳都过白名单。
     *
     * ## 为什么不用 OkHttp 的自动重定向
     *
     * 自动重定向只在最终拿到响应时才回到调用方，中间跳过了哪些主机
     * 调用方根本看不到。而「白名单」要约束的恰恰是**实际连过的每一台机器**。
     * 所以这里用 `followRedirects(false)` 的派生客户端，手动一跳一跳走。
     *
     * @throws NetworkDeniedException 任何一跳被白名单拒绝，或者跳数超限
     */
    fun call(client: OkHttpClient, request: Request): Response {
        val noRedirect = manualRedirectClient(client)

        var current = request
        var hops = 0
        while (true) {
            check(current.url)?.let { throw NetworkDeniedException(it) }

            // 网络层的 IOException 直接往外抛：调用方会把它和
            // NetworkDeniedException 分开处理（前者可重试，后者不该重试）
            val response = noRedirect.newCall(current).execute()

            val next = nextHop(current, response) ?: return response

            response.close()
            hops++
            if (hops > MAX_REDIRECTS) {
                throw NetworkDeniedException(
                    "重定向次数超过 $MAX_REDIRECTS 次，已停止。这可能是一个重定向环。",
                )
            }
            current = next
        }
    }

    /**
     * 算出下一跳；不是重定向时返回 null。
     *
     * 方法改写规则按 RFC 9110：303 一律变 GET；301/302 对 POST 变 GET
     * （历史遗留行为，浏览器都这么做）；307/308 保持方法和请求体。
     */
    private fun nextHop(current: Request, response: Response): Request? {
        if (response.code !in REDIRECT_CODES) return null

        val location = response.header("Location")?.trim().orEmpty()
        if (location.isEmpty()) return null

        val target = resolve(current.url, location) ?: throw NetworkDeniedException(
            "重定向目标「$location」不是合法地址，已停止。",
        )

        val method = current.method
        val toGet = response.code == 303 ||
            (response.code in setOf(301, 302) && method == "POST")

        return if (toGet) {
            Request.Builder().url(target).get()
        } else {
            Request.Builder().url(target).method(method, current.body)
        }.apply {
            // 认证头**不跟着重定向走**。
            //
            // 这是最容易被忽略的泄漏路径：白名单里的域名被攻陷或本身就是恶意的，
            // 它只要回一个 302，用户的 API Key 就会跟着 Authorization 头
            // 一起发到它指定的地方。跨主机时一律剥掉，同主机才保留。
            if (target.host == current.url.host) {
                current.headers.forEach { (name, value) ->
                    if (!name.equals("Host", ignoreCase = true)) header(name, value)
                }
            } else {
                current.headers.forEach { (name, value) ->
                    if (!name.equals("Host", ignoreCase = true) &&
                        !name.equals("Authorization", ignoreCase = true) &&
                        !name.startsWith("X-Api", ignoreCase = true)
                    ) {
                        header(name, value)
                    }
                }
            }
        }.build()
    }

    /**
     * 相对地址解析。
     *
     * 用 `java.net.URI` 而不是 OkHttp 的 `HttpUrl.resolve`：
     * 前者是 JDK 标准实现、RFC 3986 语义，且不依赖 OkHttp 的 API 版本
     * （`resolve` 在 OkHttp 各版本间的去留不稳定）。
     */
    private fun resolve(base: HttpUrl, location: String): HttpUrl? = runCatching {
        URI(base.toString()).resolve(location).toString()
    }.getOrNull()?.toHttpUrlOrNull()

    private fun manualRedirectClient(client: OkHttpClient): OkHttpClient =
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    companion object {
        /**
         * 「声明里有 `*`」这个判据**只有这一处**。
         *
         * [allowsAnyHost] 用它；`ScriptTool` 也用它 —— 后者拿不到 guard 实例
         * （guard 是沙箱那边建的），手里只有 `ScriptRequest.network`，
         * 而两边对「任意主机」的判断必须一致：不一致的后果是确认弹窗在一处生效、
         * 另一处静默失效。
         */
        fun declaresAnyHost(declared: List<String>): Boolean = declared.any { it.trim() == "*" }

        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/**
 * 白名单拦下了一次请求。
 *
 * 单独一个异常类型是为了让运行时能把它和网络故障区分开：
 * 前者要告诉模型「换个地址或让用户改权限」，后者要告诉它「网络不通，可以重试」。
 * 混成一个 `IOException` 的话，模型会对一个永远不会成功的地址反复重试。
 */
class NetworkDeniedException(message: String) : Exception(message)
