package com.aichat.di

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 插件发起网络请求用的客户端。
 *
 * ## 为什么是一个工厂函数，而不是两处各写一遍 Builder
 *
 * 两个进程都要它：**宿主进程**里声明式工具和 MCP 客户端用它，
 * **沙箱进程**里 `host.http` 用它。而两个进程不可能共用一个实例 ——
 * 所以看起来只能各建一个，但**配置必须是同一份**。
 * 各写一遍 Builder 的话，改了超时只改一处，另一处静默地用着旧值。
 *
 * ## 和对话用的客户端分开
 *
 * 插件请求的读超时该短得多（一个天气查询等 5 分钟没有意义），
 * 而对话的流式响应可能要等好几分钟。合成一个的话，
 * 调短会掐断长回答，调长会让插件把工具循环卡住。
 *
 * ## `followRedirects` 必须关掉
 *
 * [com.aichat.plugin.permission.NetworkGuard] 自己一跳一跳地跟重定向，
 * 每一跳都要过白名单。开了自动重定向等于把白名单交给对端决定 ——
 * 而且那是**静默**失效：白名单里的域名回一个 302，请求就去了别处。
 */
fun pluginHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
