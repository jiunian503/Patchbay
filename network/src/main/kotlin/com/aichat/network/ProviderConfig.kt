package com.aichat.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 一个 LLM 服务商的连接配置。本地 BYOK —— 用户自己填，不经过我们的服务器。
 *
 * [baseUrl] 的写法很宽松，几种常见形态都能直接用：
 *
 * | 用户填的 | 实际请求的 |
 * |---|---|
 * | `https://api.openai.com` | `https://api.openai.com/v1/chat/completions` |
 * | `https://api.openai.com/v1` | `https://api.openai.com/v1/chat/completions` |
 * | `https://open.bigmodel.cn/api/paas/v4` | `https://open.bigmodel.cn/api/paas/v4/chat/completions` |
 * | `api.deepseek.com` | `https://api.deepseek.com/v1/chat/completions` |
 */
data class ProviderConfig(
    val baseUrl: String,
    val apiKey: String,
    val extraHeaders: Map<String, String> = emptyMap(),
    val connectTimeoutSeconds: Long = 30,

    /**
     * 两次网络读取之间的最长等待。**不是**整场对话的上限。
     *
     * 给得比较宽，是因为推理模型在「思考」阶段可能几十秒不吐任何字节。
     * 真正需要设上限的场景请用 [OpenAiChatClient] 的取消能力，而不是靠超时。
     */
    val readTimeoutSeconds: Long = 300,

    /**
     * 是否请求 token 用量统计（`stream_options.include_usage`）。
     *
     * 默认关闭：这个字段是 OpenAI 后加的，部分自建/第三方网关不认，
     * 收到会直接 400。需要显示用量时再打开。
     */
    val includeUsage: Boolean = false,
) {

    /** 规范化后的 chat completions 地址。 */
    val chatCompletionsUrl: String get() = chatCompletionsUrl(baseUrl)

    /**
     * 按本配置造一个 OkHttpClient。
     *
     * 注意**不要**设置 `callTimeout` —— 它是整个请求（含读完响应体）的硬上限，
     * 会把长回答从中间掐断。流式对话的「停止」应该由用户主动取消，不是超时。
     */
    fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {

        /**
         * 把用户填的 baseUrl 规范化成完整的 chat completions 地址。
         *
         * 规则：域名后面**没有路径段**就补 `/v1`，有路径就原样用。
         * 这条规则覆盖了绝大多数服务商 —— 官方 API 都在 `/v1`，
         * 而自建网关/国内厂商通常带自己的路径前缀（`/api/paas/v4`）。
         */
        fun chatCompletionsUrl(rawBase: String): String {
            val trimmed = rawBase.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "baseUrl 不能为空" }

            // 用户常常只填域名，自动补 https
            val base = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }

            val afterScheme = base.substringAfter("://")
            return if (afterScheme.contains('/')) {
                "$base/chat/completions"
            } else {
                "$base/v1/chat/completions"
            }
        }
    }
}
