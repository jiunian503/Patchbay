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

    /**
     * 全部**有值**的键值对。
     *
     * 给脚本沙箱用：它要跨进程，所以配置得序列化过去；而**空串不能过去** ——
     * 「用户填了空」和「用户没填」在插件里必须是同一件事，否则脚本会看到一个
     * 空字符串并当成真值（`Authorization: Bearer ` 就是这么来的，见 [value]）。
     * 这里和 [value] 用同一条规则，就是为了让两处不会漂移。
     */
    fun asMap(): Map<String, String> = values.filterValues { it.isNotBlank() }

    companion object {
        val Empty = PluginSettings()
    }
}
