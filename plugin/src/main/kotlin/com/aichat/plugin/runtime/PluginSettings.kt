package com.aichat.plugin.runtime

/**
 * 用户为某个插件填的配置值。
 *
 * ## 为什么「没填」和「填了空」要分开
 *
 * [value] 把空串也当成没填。这不是洁癖：`Authorization: Bearer ` 和
 * 「根本没有 Authorization 头」对服务端来说是两种不同的请求，
 * 而前者报回来的是一个 401 —— 用户会以为密钥错了，实际原因是他没填。
 *
 * 所以这里统一归一成「没有值」，让调用方去报「去设置里填一下 X」，
 * 而不是把一个空串拼进请求里。
 *
 * secret 项的值由 `:app` 从 Keystore 取出来之后填进这个 map，
 * 所以本模块（纯 JVM）不需要知道 Keystore 的存在 —— 单测里直接给字符串。
 */
class PluginSettings(private val values: Map<String, String> = emptyMap()) {

    fun value(key: String): String? = values[key]?.takeIf { it.isNotBlank() }

    fun isEmpty(): Boolean = values.values.all { it.isBlank() }

    /** 有哪些键是有值的。用于「还缺哪些配置」的提示。 */
    fun presentKeys(): Set<String> = values.filterValues { it.isNotBlank() }.keys

    companion object {
        val Empty = PluginSettings()
    }
}
