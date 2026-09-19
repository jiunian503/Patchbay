package com.aichat.settings

import android.content.Context

/**
 * App 级的偏好设置。
 *
 * ## 为什么不用 Room
 *
 * 这些值是「一个开关」，不是「一张表的一行」。为它建一张单行表、写一次迁移、
 * 每次读都走一次挂起查询，成本远大于收益 —— 而且它必须在**构造工具注册表时
 * 同步读到**（见下），Room 的挂起接口在那里用不上。
 *
 * ## 为什么 `SharedPreferences` 而不是 `DataStore`
 *
 * DataStore 是挂起 API，读一个布尔值要起协程。这里的调用点
 * （`AppContainer.activeBuiltins()`）在**冷启动的装配路径上**，
 * 而工具注册表必须在第一次对话之前就绪 —— 为它引入一条异步链路，
 * 换来的只是「更现代」，没有实际收益。这个文件只有一个布尔值，
 * 不存在 DataStore 要解决的那些问题（多写者、一致性、类型安全）。
 *
 * ## 默认值：**开**
 *
 * 长期记忆是这个产品的核心（参照 Hermes 的三层记忆）。默认关的话，
 * 绝大多数用户不会去设置页把它打开 —— 那等于功能不存在。而它带来的
 * 隐私面（模型检索到的历史会随下一次请求发给服务商）是**可以随时关掉的**，
 * 所以设置页把这个开关放在最显眼的位置、并把后果写清楚。
 *
 * 这个取舍值得记住：**默认值的争论，两边说的其实是不同的事** ——
 * 「默认关」保护的是「用户没选择时的安全」，「默认开」保护的是
 * 「用户不知道自己错过了什么」。这里选后者，因为前者有明确的补救
 * （一个开关 + 一句说明），后者没有（用户根本不会去翻设置页）。
 */
class AppSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 模型能不能主动检索历史对话。
     *
     * 关掉只影响**模型**。用户自己在搜索页里翻历史不受影响 ——
     * 那是用户主动的动作，和这个开关是两件事。
     */
    fun longTermMemory(): Boolean = prefs.getBoolean(KEY_LONG_TERM_MEMORY, DEFAULT_LONG_TERM_MEMORY)

    fun setLongTermMemory(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LONG_TERM_MEMORY, enabled).apply()
    }

    /**
     * 上次退出时人在哪个会话里。冷启动直接进这里。
     *
     * ## 为什么要单独存，而不是「取列表里最近的那条」
     *
     * 会话的 `updated_at` 只在**发消息时**变。用户完全可能打开一个老会话读一读、
     * 一个字都没发就退出 —— 按 `updated_at` 排的话下次启动会进到另一个会话，
     * 而用户的预期是「我退出去的时候就在这儿」。这是**导航状态**，不是业务数据。
     *
     * ## 存的是一个可能不存在的 id
     *
     * `newConversationId()` 只生成 id、**不建会话行**（会话行要等第一条消息发出去
     * 才由 `ChatSession.ensureConversation` 创建）。所以这里存下来的 id 有两种情况：
     *
     * - 一个真的会话
     * - 一个「刚点了新建、还没说话」的 id，库里根本没有这一行
     *
     * 两者对对话页是同一件事（空会话），所以不需要在这里区分。
     * **唯一要处理的是删除**：删掉的正好是它时把它清掉，否则下次启动会恢复出一个
     * 已经不存在的会话 —— 表现是一个空的幽灵对话，用户会以为自己的数据丢了。
     * 那件事在 `ConversationListViewModel.delete` 里做。
     */
    fun lastConversationId(): String? = prefs.getString(KEY_LAST_CONVERSATION, null)

    fun setLastConversationId(id: String?) {
        prefs
            .edit()
            .apply {
                if (id == null) remove(KEY_LAST_CONVERSATION) else putString(KEY_LAST_CONVERSATION, id)
            }
            .apply()
    }

    /**
     * 联网搜索用的后端 id。**空串表示关闭**。
     *
     * 存的是后端自己的 id 字符串（`searxng` / `brave` / `tavily`），不是枚举名 ——
     * 枚举改名不该让老设置失效，而且这里存的东西本来就要能被未来的版本读回来。
     *
     * 地址和密钥为什么不一起放这儿：地址是「用户填的一个字符串」，放这儿没问题；
     * **密钥必须走 [com.aichat.domain.secret.SecretStore]**，和对话服务商的 Key
     * 同一套规矩（见 `AndroidKeystoreSecretStore` 的 KDoc）。
     */
    fun webSearchBackend(): String = prefs.getString(KEY_WEB_SEARCH_BACKEND, "").orEmpty()

    fun setWebSearchBackend(id: String) {
        prefs.edit().putString(KEY_WEB_SEARCH_BACKEND, id).apply()
    }

    /**
     * 搜索后端的地址。
     *
     * 语义**随后端而变**：SearXNG 填的是实例地址（`https://searx.be`，
     * 路径由 `resolveEndpoint` 补），Brave / Tavily 填的是接口地址。
     * 这个差别写在设置页的提示里，不在这里 —— 这里只存字符串。
     */
    fun webSearchEndpoint(): String = prefs.getString(KEY_WEB_SEARCH_ENDPOINT, "").orEmpty()

    fun setWebSearchEndpoint(url: String) {
        prefs.edit().putString(KEY_WEB_SEARCH_ENDPOINT, url).apply()
    }

    companion object {
        /** 和密钥的 `aichat_secrets` 分开：这个文件里没有秘密，别让清理密钥的逻辑误伤它。 */
        private const val FILE = "aichat_settings"

        private const val KEY_LONG_TERM_MEMORY = "long_term_memory"

        const val DEFAULT_LONG_TERM_MEMORY = true

        private const val KEY_LAST_CONVERSATION = "last_conversation_id"

        private const val KEY_WEB_SEARCH_BACKEND = "web_search_backend"

        private const val KEY_WEB_SEARCH_ENDPOINT = "web_search_endpoint"
    }
}
