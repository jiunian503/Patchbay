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
 * ## 密钥别名为什么要带上后端
 *
 * 一开始是一个固定的 `web_search_api_key`，理由是「搜索后端是全局的、
 * 只有一份密钥，固定别名让『密钥存在哪儿』一眼可见」。
 *
 * 那个理由把「界面上同时只有一个后端」当成了「只有一把密钥」。
 * 真机上撞到的是：先配 Brave 拿到 token，再切到 Tavily ——
 * KeyStore 里那把是 Brave 的，可设置页读的是同一个别名，于是
 *
 * 1. 界面显示「已保存一个密钥」（它确实是「一把」密钥，只是不是这一家的）
 * 2. 保存前的校验放行（`keyRequired` 只问有没有，不问是谁的）
 * 3. 搜索拿着 **Brave 的 token** 去请求 Tavily → **401**
 *
 * 而用户看到的只有「搜索失败」，完全指不到密钥头上。
 *
 * 所以别名按后端分开：`web_search.brave` / `web_search.tavily` / …
 * 换后端就是换一把密钥，界面从头开始问。代价是用户要为每个后端各配一次，
 * 但那本来就是事实 —— 两家的 key 在对方那边都不认。
 *
 * **没有做旧数据迁移**：App 还没发布、唯一的使用者是开发机，
 * 迁移代码的寿命会比它要保护的那点数据长得多。
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
            apiKey = secrets.get(alias(backend)),
        )
        return config.takeIf { it.resolveEndpoint() != null }
    }

    /**
     * 设置页在「用户没重新输入密钥」时用它把旧密钥取出来做测试。
     *
     * **必须带后端**：不带的话它返回的是「某一个」后端的密钥，
     * 而调用方以为拿到的是「当前这个」的 —— 那正是上面那个 401。
     */
    suspend fun storedKey(backend: WebSearchBackend?): String? =
        backend?.let { secrets.get(alias(it)) }

    companion object {
        /**
         * 密钥在 KeyStore 里的别名，**按后端分开**。
         *
         * 前缀 `web_search.` 是为了和服务商的别名（UUID）分开命名空间；
         * 后缀是后端的 [WebSearchBackend.id]，所以出问题时在设备上
         * （或日志里）能一眼看出这一把是谁的。
         */
        fun alias(backend: WebSearchBackend): String = "web_search.${backend.id}"
    }
}
