package com.aichat.settings

import com.aichat.domain.secret.SecretStore
import com.aichat.tools.WebSearchBackend
import com.aichat.tools.WebSearchConfig
import com.aichat.tools.WebSearchSource
import com.aichat.tools.resolveEndpoint

/**
 * [WebSearchSource] 的 Android 实现：后端与地址读 [AppSettings]，密钥读 [SecretStore]。
 *
 * 命名沿用 `AndroidDeviceInfo` 那套约定 —— 窄接口在 `:tools`，
 * 具体实现放在宿主侧，一眼能看出「这是宿主提供的那一份」。
 *
 * ## 两个方法为什么差别这么大
 *
 * [configured] 是**同步**的：它被 `BuiltinTools.all()` 在装配路径上调用，
 * 而装配路径不能挂起。所以它只读 SharedPreferences（同步 API），
 * **不碰密钥**。
 *
 * [config] 是**挂起**的：真到执行时才去 KeyStore 取密钥。
 *
 * 于是「后端配好了，但设备恢复出厂之后 KeyStore 里的密钥没了」这种情况，
 * 表现是**执行时报一句能看懂的错误**，而不是工具凭空从列表里消失 ——
 * 后者用户完全无从判断发生了什么。
 *
 * ## 密钥别名
 *
 * 刻意用一个大白话的固定字符串，而不是 UUID：这个密钥只有一份
 * （搜索后端是全局的，不像服务商那样可以有很多个），固定别名让
 * 「密钥到底存在哪儿」这件事在代码里一眼可见。
 */
class SettingsWebSearchSource(
    private val settings: AppSettings,
    private val secrets: SecretStore,
) : WebSearchSource {

    override fun configured(): Boolean {
        val backend = WebSearchBackend.fromId(settings.webSearchBackend()) ?: return false
        // 地址能解析就算配好了。**这里查不了密钥**（挂起），
        // 所以 Brave / Tavily 缺密钥的情况由 config() 在执行时兜住 ——
        // 设置页会在保存前就拦住它，正常路径走不到
        return WebSearchConfig(backend, settings.webSearchEndpoint()).resolveEndpoint() != null
    }

    override suspend fun config(): WebSearchConfig? {
        val backend = WebSearchBackend.fromId(settings.webSearchBackend()) ?: return null
        val config = WebSearchConfig(
            backend = backend,
            endpoint = settings.webSearchEndpoint(),
            apiKey = secrets.get(API_KEY_ALIAS),
        )
        return config.takeIf { it.resolveEndpoint() != null }
    }

    /** 设置页在「用户没重新输入密钥」时用它把旧密钥取出来做测试。 */
    suspend fun storedKey(): String? = secrets.get(API_KEY_ALIAS)

    companion object {
        /**
         * 密钥在 KeyStore 里的别名。
         *
         * 和服务商的别名（UUID）不同命名空间，不会撞上。
         */
        const val API_KEY_ALIAS = "web_search_api_key"
    }
}
