package com.aichat.settings

import android.content.Context
import androidx.core.content.edit
import com.aichat.tools.WebSearchBackend

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
 * 换来的只是「更现代」，没有实际收益。这个文件里存的全是**标量**
 * （几个开关、几段 id 字符串），不存在 DataStore 要解决的那些问题
 * （多写者、一致性、类型安全）。
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
        prefs.edit { putBoolean(KEY_LONG_TERM_MEMORY, enabled) }
    }

    /**
     * 模型能不能在这台设备上跑系统命令（`run_command` 工具）。
     *
     * ## 默认值：**关** —— 和 [longTermMemory] 相反，而且是刻意的
     *
     * 类 KDoc 里那条「默认值的争论，两边说的其实是不同的事」在这儿答案翻了个面：
     *
     * - [longTermMemory] 默认开，因为它默认关的代价是「功能等于不存在」，
     *   而它带来的隐私面可以随时关掉、后果也写在设置页上
     * - 这个默认关，因为默认开的代价是**模型可以改你的文件** ——
     *   一个从没进过设置页的用户会在不知情的情况下把设备交出去
     *
     * 而且它的补救路径和长期记忆**不对称**：长期记忆用户随时能关、关掉就完事；
     * 这个一旦跑了一条 `rm -rf`，没有任何东西能撤销。所以「用户没选择时的安全」
     * 这次必须赢。
     *
     * ## 它唯一的安全边界是「弹框」，所以两层缺一不可
     *
     * `RunCommandTool.requiresConfirmation` 是 true —— 每次执行前用户都会
     * 看到那条命令。但**开关关着的时候连弹框都不会有**：工具根本不注册给模型
     * （见 `BuiltinTools.all` 的 `if (shell.enabled())`）。
     *
     * 两层管的是两件事：开关决定「模型知不知道有这个能力」，弹框决定
     * 「这一次跑不跑」。少了第一层，用户会被一堆弹框淹没然后学会闭眼点「允许」；
     * 少了第二层，一条写错的命令直接就跑了。
     *
     * ## 关掉只影响**模型**
     *
     * 设置页里的终端不受影响 —— 那是用户自己敲的，和这个开关是两件事。
     * 所以终端页**不看这个开关**（`SystemShell` 的 `enabled()` 只被装配路径问）。
     */
    fun shellEnabled(): Boolean = prefs.getBoolean(KEY_SHELL_ENABLED, DEFAULT_SHELL_ENABLED)

    fun setShellEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SHELL_ENABLED, enabled) }
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
        prefs.edit {
            if (id == null) remove(KEY_LAST_CONVERSATION) else putString(KEY_LAST_CONVERSATION, id)
        }
    }

    /**
     * 联网搜索用的后端 id。**空串表示「用户主动关过」**。
     *
     * 存的是后端自己的 id 字符串（`bing` / `searxng` / `brave` / `tavily`），
     * 不是枚举名 —— 枚举改名不该让老设置失效，而且这里存的东西本来就要能
     * 被未来的版本读回来。
     *
     * ## 「从没碰过」和「主动关闭」是两种状态
     *
     * 默认值是**内置后端**，所以全新安装打开 App 就能直接搜 —— 这是那个
     * 后端存在的全部理由（见 `WebSearchBackend.BING_HTML` 的 KDoc）。
     *
     * 代价是这个字段有了两种「空」的语义，而 `SharedPreferences` 只有
     * 「key 不存在」和「key = 某值」两种。于是：
     *
     * - **key 不存在** → 默认值（Bing）→ 工具注册，装上就能用
     * - **key 存在且是空串** → 用户主动关过 → 工具不注册，别自作主张打开
     *
     * 因此 [setWebSearchBackend] 的调用方**必须继续写空串**，不能改成
     * `remove()` —— 那会把两种状态合并成一种，用户关掉的搜索会在下次启动时
     * 自己打开（`AppContainer.saveWebSearch` 里写的是 `backend?.id.orEmpty()`，
     * 那个 `orEmpty()` 是有意的）。
     *
     * 地址和密钥为什么不一起放这儿：地址是「用户填的一个字符串」，放这儿没问题；
     * **密钥必须走 [com.aichat.domain.secret.SecretStore]**，和对话服务商的 Key
     * 同一套规矩（见 `AndroidKeystoreSecretStore` 的 KDoc）。
     */
    fun webSearchBackend(): String =
        prefs.getString(KEY_WEB_SEARCH_BACKEND, WebSearchBackend.BING_HTML.id).orEmpty()

    fun setWebSearchBackend(id: String) {
        prefs.edit { putString(KEY_WEB_SEARCH_BACKEND, id) }
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
        prefs.edit { putString(KEY_WEB_SEARCH_ENDPOINT, url) }
    }

    /**
     * 「会话行还没建出来时先选好的角色」。没选过（或者已经提升进库了）返回 null。
     *
     * ## 为什么这个状态只能放在设置里
     *
     * 角色本身存在 `conversation` 表的 `character_id` 列上。但会话行是
     * **第一条消息发出去时**才建的，而用户可以在那之前就点角色选择器 ——
     * 那时 `UPDATE ... WHERE id = ?` 影响 0 行，选择会被静默丢掉。
     *
     * 另外两种存法都不行：选角色时顺手建会话行会在列表里留一个没说过话的
     * 幽灵会话；让 `ensureConversation` 接受角色 id 则等于把「角色」这个概念
     * 塞进 `:chat`，破坏模块边界。所以先存在这儿，等会话行一出现就搬过去
     * （搬运的判断在 `resolveCharacterChoice`，是个纯函数，有单测）。
     *
     * ## 为什么连会话 id 一起存
     *
     * 待定的是「**那个**还没落库的会话该用谁」。只存角色 id 的话，用户选完
     * 又去别的会话里发消息，那个角色会被误用到别人身上。
     */
    fun pendingCharacterConversation(): String? =
        prefs.getString(KEY_PENDING_CHARACTER_CONV, null)

    fun pendingCharacter(): String? = prefs.getString(KEY_PENDING_CHARACTER, null)

    /** 两个参数都传 null 就是清空。 */
    fun setPendingCharacter(conversationId: String?, characterId: String?) {
        prefs.edit {
            if (conversationId == null) {
                remove(KEY_PENDING_CHARACTER_CONV)
            } else {
                putString(KEY_PENDING_CHARACTER_CONV, conversationId)
            }
            if (characterId == null) {
                remove(KEY_PENDING_CHARACTER)
            } else {
                putString(KEY_PENDING_CHARACTER, characterId)
            }
        }
    }

    /**
     * 「会话行还没建出来时先换好的服务商」。没换过（或者已经提升进库了）返回 null。
     *
     * 和 [pendingCharacterConversation] 是同一个坑、同一套解法：会话行要等第一条
     * 消息发出去才建，而顶栏的服务商入口在那之前就能点到，于是
     * `UPDATE ... WHERE id = ?` 影响 0 行、选择被静默丢掉（用户看到顶栏弹回默认）。
     * 完整的推导在 [resolveProviderPin] 的 KDoc 里。
     *
     * 和角色那对键**分开存**，不共用：两者是独立的用户动作（换了服务商不等于
     * 换了角色），合成一对的话会出现「清了服务商的待定值顺手把角色的也清了」。
     */
    fun pendingProviderConversation(): String? =
        prefs.getString(KEY_PENDING_PROVIDER_CONV, null)

    fun pendingProvider(): String? = prefs.getString(KEY_PENDING_PROVIDER, null)

    /** 两个参数都传 null 就是清空。 */
    fun setPendingProvider(conversationId: String?, providerId: String?) {
        prefs.edit {
            if (conversationId == null) {
                remove(KEY_PENDING_PROVIDER_CONV)
            } else {
                putString(KEY_PENDING_PROVIDER_CONV, conversationId)
            }
            if (providerId == null) {
                remove(KEY_PENDING_PROVIDER)
            } else {
                putString(KEY_PENDING_PROVIDER, providerId)
            }
        }
    }

    companion object {
        /**
         * 偏好文件名。
         *
         * 和密钥的 `patchbay_secrets` 分开：这个文件里没有秘密，别让清理密钥的逻辑
         * 误伤它。
         *
         * 五十四轮跟着 `applicationId` 一起从 `aichat_settings` 改成这个名字。
         * **顺序不能反**，理由写在 `AppDatabase.DB_NAME` 的 KDoc 里：换包名之后
         * 数据目录是新的，老偏好本来就带不过来，这时改名才是免费的。
         */
        private const val FILE = "patchbay_settings"

        private const val KEY_LONG_TERM_MEMORY = "long_term_memory"

        const val DEFAULT_LONG_TERM_MEMORY = true

        /**
         * 「让 AI 跑命令」。
         *
         * 默认**关**，理由见 [shellEnabled] 的 KDoc —— 一句话：打开它的代价是
         * 「模型能改你的文件」，而这个代价不可撤销，所以「用户没选择时的安全」赢。
         *
         * 键名写 `shell_enabled` 而不是 `run_command_enabled`：开关管的是
         * **能力**（这台设备上能不能跑命令），工具名是那件能力的当前实现，
         * 将来换名字不该让老设置失效。
         */
        private const val KEY_SHELL_ENABLED = "shell_enabled"

        const val DEFAULT_SHELL_ENABLED = false

        private const val KEY_LAST_CONVERSATION = "last_conversation_id"

        private const val KEY_WEB_SEARCH_BACKEND = "web_search_backend"

        private const val KEY_WEB_SEARCH_ENDPOINT = "web_search_endpoint"

        /**
         * 「会话行还没建出来时先选好的角色」。
         *
         * 两个键配对使用：一个记住**是哪个会话**，一个记住**选了谁**。
         * 只存角色 id 的话，用户选完又跑去别的会话发消息，那个角色会被
         * 误用到别人身上 —— 详见 [resolveCharacterChoice]。
         */
        private const val KEY_PENDING_CHARACTER_CONV = "pending_character_conversation"

        private const val KEY_PENDING_CHARACTER = "pending_character_id"

        /**
         * 「会话行还没建出来时先换好的服务商」。
         *
         * 和角色那对**分开存**：两者是独立的用户动作（换了服务商不等于换了角色），
         * 合成一对的话会出现「清服务商的待定值顺手把角色的也清了」。
         * 推导见 [resolveProviderPin]。
         */
        private const val KEY_PENDING_PROVIDER_CONV = "pending_provider_conversation"

        private const val KEY_PENDING_PROVIDER = "pending_provider_id"
    }
}
