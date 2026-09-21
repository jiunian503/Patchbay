package com.aichat.di

import android.content.Context
import com.aichat.chat.ChatSession
import com.aichat.chat.ConversationEngine
import com.aichat.chat.ConversationStore
import com.aichat.chat.GreetingSource
import com.aichat.chat.MessageCursor
import com.aichat.chat.MonotonicIds
import com.aichat.chat.StoredMessage
import com.aichat.chat.SystemPromptSource
import com.aichat.chat.TranscriptPage
import com.aichat.core.data.AndroidKeystoreSecretStore
import com.aichat.core.data.AppDatabase
import com.aichat.core.data.CharacterRepository
import com.aichat.core.data.ConversationRouter
import com.aichat.core.data.ProviderRepository
import com.aichat.core.data.PluginRepository
import com.aichat.core.data.ResolvedProvider
import com.aichat.core.data.RoomConversationSearch
import com.aichat.core.data.RoomConversationStore
import com.aichat.core.data.RoomTransactionRunner
import com.aichat.core.data.displayName
import com.aichat.crash.CrashStore
import com.aichat.domain.search.ConversationSearch
import com.aichat.domain.secret.SecretStore
import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolApprover
import com.aichat.domain.tool.ToolResult
import com.aichat.network.OpenAiChatClient
import com.aichat.network.GitHubReleaseClient
import com.aichat.network.RemoteTextFetcher
import com.aichat.plugin.host.InstalledPlugin
import com.aichat.plugin.host.McpRefresh
import com.aichat.plugin.host.PluginHost
import com.aichat.plugin.runtime.script.ScriptRuntime
import com.aichat.plugin.workspace.PluginWorkspaces
import com.aichat.sandbox.QuickJsSandboxRuntime
import com.aichat.settings.AppSettings
import com.aichat.settings.SettingsWebSearchSource
import com.aichat.tools.BuiltinTools
import com.aichat.tools.SearchWebTool
import com.aichat.tools.WebSearchBackend
import com.aichat.tools.WebSearchConfig
import com.aichat.ui.plugins.MAX_MANIFEST_CHARS
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 手写的依赖容器。整个 App 的装配只有这一处。
 *
 * ## 全部 `by lazy`
 *
 * [AppContainer] 是在 `Application.onCreate` 里造的，那时主线程正忙。
 * Room 的 `databaseBuilder().build()` 虽然不真正打开文件，但也不是零成本；
 * AndroidKeyStore 的取用更可能触发一次磁盘 I/O。全部推迟到第一次真正用到，
 * 冷启动就只剩一次对象构造。
 *
 * ## 关于 [newSession]
 *
 * **每次发送都新建一个 [ChatSession]**，不缓存。原因：session 里的
 * `ConversationEngine` 绑定了某个具体服务商的网络客户端，用户中途改了
 * baseUrl 或换了服务商，缓存下来的那个就会继续往旧地址发请求 ——
 * 表现为「改了配置没生效」，而且没有任何报错。
 *
 * 新建的代价只有一次对象构造，[MonotonicIds] 是共享的，所以 id 仍然全局递增。
 */
class AppContainer(context: Context) : ChatDeps {

    /**
     * 一定是 application context —— 构造函数里过了一道 `applicationContext`。
     *
     * 公开它，是为了给「活得比界面久」的对象一个**类型正确**的上下文来源
     * （[AndroidDeviceInfo] 就是这么拿的）。这样就不存在「顺手把 Activity
     * 传进去」这条路 —— 而那正是 lint 的 StaticFieldLeak 在 ViewModel 上
     * 反复报的那件事。
     */
    val appContext: Context = context.applicationContext

    private val db: AppDatabase by lazy { AppDatabase.create(appContext) }

    private val tx: RoomTransactionRunner by lazy { RoomTransactionRunner(db) }

    /** 密钥存储。**不要**把它的内容写进日志或界面。 */
    val secrets: SecretStore by lazy { AndroidKeystoreSecretStore(appContext) }

    val providers: ProviderRepository by lazy {
        ProviderRepository(dao = db.providerDao(), secrets = secrets, tx = tx)
    }

    /**
     * 会话路由 —— 「这个会话该用哪个服务商」。
     *
     * 单独一个对象而不是塞进 [providers]：它要读**会话表**，
     * 而 [ProviderRepository] 的职责是服务商配置本身。混在一起的话，
     * 「换了个默认服务商，老会话为什么没变」这类问题就得去翻一个
     * 名字里写着 Provider 的类，很难想到。
     */
    private val conversationRouter: ConversationRouter by lazy {
        ConversationRouter(conversations = db.conversationDao(), providers = providers)
    }

    val conversations: ConversationStore by lazy {
        RoomConversationStore(
            messages = db.messageDao(),
            fts = db.messageFtsDao(),
            conversations = db.conversationDao(),
            tx = tx,
        )
    }

    /**
     * 角色卡与世界书。
     *
     * 和 [conversations] 分开：那个是「对话生命周期的出口」，每加一个方法
     * 都会逼着 `ChatSessionTest` 的假实现跟着长一个；这个是角色配置的读写，
     * 只有设置界面和注入侧用它。
     */
    val characters: CharacterRepository by lazy {
        CharacterRepository(
            characters = db.characterDao(),
            entries = db.worldBookEntryDao(),
            conversations = db.conversationDao(),
            tx = tx,
        )
    }

    /**
     * 会话选角色。`null` = 不注入任何角色提示词（行为和 v6 升上来的老会话一样）。
     *
     * 做成 [AppContainer] 上的一个方法而不是塞进 `ConversationStore`：
     * 那个接口是 `:chat` 的，而「角色」是宿主侧的概念 —— 让对话引擎
     * 认识角色卡，等于把 `:data` 的表结构泄漏进 `:chat`。
     *
     * ## 会话行还不存在时先存进设置
     *
     * 会话行是**第一条消息发出去时**才建的，而角色选择器在那之前就能点到。
     * 直接写库的话，`UPDATE ... WHERE id = ?` 影响 0 行、**不报错**，
     * 用户看到的是「人设选了却不生效」。所以行不在时先记在设置里，
     * 等行一出现再由 [effectiveCharacterId] 搬进库（判断逻辑在
     * `resolveCharacterChoice`，是纯函数、有单测）。
     */
    suspend fun selectCharacter(conversationId: String, characterId: String?) {
        if (db.conversationDao().get(conversationId) != null) {
            db.conversationDao().setCharacter(conversationId, characterId)
            // 行已经在了，之前可能留下的待定值就过期了 —— 不清的话
            // 下一次读会拿它去覆盖用户刚刚做的选择
            if (settings.pendingCharacterConversation() == conversationId) {
                settings.setPendingCharacter(null, null)
            }
            return
        }

        // 「不用角色」没什么可记的，顺手把可能残留的待定值清掉
        if (characterId == null) {
            settings.setPendingCharacter(null, null)
        } else {
            settings.setPendingCharacter(conversationId, characterId)
        }
    }

    /**
     * 这个会话最终该用哪个角色。null = 没选（或者会话还没建、也没先选过）。
     *
     * 和 [selectCharacter] 配对。**不做缓存**：会话的 `character_id` 可能在
     * 别处被改（比如用户刚在设置里删掉了这个角色，`CharacterRepository.delete`
     * 会把引用清空），缓存下来就会显示一个已经不存在的角色名。
     *
     * ## 这个方法会写库（一次，幂等）
     *
     * 名字从 `characterIdOf` 改过来的，因为它不只是「读」：如果用户在会话行
     * 建出来**之前**就选好了角色，那这里要把暂存的选择提升进会话行。
     * 没有别的时机能做这件事 —— 会话行的创建发生在 `ChatSession.send()` 里，
     * 而 `:chat` 不认识「角色」。
     *
     * 提升之后暂存值立刻清掉，所以同一个会话只会写这一次。**这是刻意的**：
     * 不提升的话，选择只活在设置里，用户重启 App 后选择器会显示「不用角色」，
     * 而人设其实还在生效 —— 界面和实际行为对不上，是最难查的一类问题。
     */
    suspend fun effectiveCharacterId(conversationId: String): String? {
        val row = db.conversationDao().get(conversationId)
        val choice = resolveCharacterChoice(
            conversationId = conversationId,
            rowExists = row != null,
            rowCharacterId = row?.characterId,
            pendingConversationId = settings.pendingCharacterConversation(),
            pendingCharacterId = settings.pendingCharacter(),
        )

        // 顺序不能反：先把值落到行上，再清暂存 —— 反过来的话中间崩一次
        // 就两边都没有了
        if (choice.promote) {
            db.conversationDao().setCharacter(conversationId, choice.characterId)
        }
        if (choice.clearPending) {
            settings.setPendingCharacter(null, null)
        }
        return choice.characterId
    }

    /**
     * 每轮对话前解析要注入的系统提示词（角色人设 + 世界书命中条目）。
     *
     * 它读**会话表**（这个会话选了哪个角色）和角色库，所以只能活在宿主
     * 这一层 —— `:chat` 连 Room 都不认识。
     *
     * 传的是 [effectiveCharacterId] 的引用而不是 `ConversationDao`：
     * 「行还没建出来时选择暂存在哪」这件事只有 [AppContainer] 知道。
     */
    private val promptSource: SystemPromptSource by lazy {
        CharacterSystemPromptSource(
            characterIdOf = ::effectiveCharacterId,
            characters = characters,
        )
    }

    /**
     * 新会话里角色先说的那一句（角色卡上的「开场白」）。
     *
     * 和 [promptSource] 分开，是因为两者的**消费方式不同**：那个每轮解析
     * 一次、拼进请求；这个只在会话还是空的时候问一次，拿到之后
     * `ChatSession` 会把它**落成一条消息**。
     *
     * 同样传 [effectiveCharacterId] 的引用 —— 「会话行还没建出来时选择
     * 暂存在设置里」这件事只有这里知道，自己去查 DAO 的话，
     * 新建对话里选的角色会连开场白一起被漏掉。
     */
    private val greetingSource: GreetingSource by lazy {
        CharacterGreetingSource(
            characterIdOf = ::effectiveCharacterId,
            characters = characters,
        )
    }

    /**
     * 历史消息检索（Hermes 三层记忆的第三层）。
     *
     * **刻意和 [conversations] 分开**，即使它俩读的是同一张索引表。
     * 理由在 `ConversationSearch` 的 KDoc 里：那个是对话生命周期的出口，
     * 每加一个方法 `ChatSessionTest` 的假实现就得跟着长一个；这个是只读查询。
     * 绑在一起只会让「对话引擎」被迫认识「搜索」。
     *
     * ## 两个消费者，共用一个实例
     *
     * 一是搜索界面（用户自己找），二是 `search_history` 工具（模型自己找）。
     * 两者共用同一个实例是刻意的 —— 它们必须看到**同一份**索引，
     * 否则会出现「用户搜得到、模型搜不到」这种最难解释的偏差。
     *
     * 不缓存成 `ConversationSearch` 之外的任何东西 —— 索引表是随对话写入
     * 实时更新的，检索本身没有需要维护的状态。
     */
    val search: ConversationSearch by lazy {
        RoomConversationSearch(fts = db.messageFtsDao())
    }

    /** 全进程共享的 id 生成器。分实例的话不同会话可能撞 id。 */
    private val ids = MonotonicIds()

    /** App 级偏好设置（目前只有「长期记忆」和「联网搜索」两组）。 */
    val settings: AppSettings by lazy { AppSettings(appContext) }

    /**
     * 联网搜索的配置来源。
     *
     * 后端与地址在 [settings] 里，密钥在 KeyStore 里 —— 这个类把两处拼成一个
     * `:tools` 认识的 [WebSearchSource]。`:tools` 完全不认识 Android。
     */
    val webSearch: SettingsWebSearchSource by lazy {
        SettingsWebSearchSource(settings = settings, secrets = secrets)
    }

    /**
     * 当前该注册哪些内置工具。
     *
     * 每次装配时重新求值 —— 用户切换长期记忆开关后调一次 [setLongTermMemory]，
     * 下一次对话的**第一轮**就拿到新的工具集（正在跑的那一轮不会中途换，
     * 因为引擎只在每轮开始时读一次 `definitions()`）。
     */
    private fun activeBuiltins(): List<Tool> = BuiltinTools.all(
        deviceInfo = AndroidDeviceInfo(appContext),
        search = search,
        webSearch = webSearch,
        longTermMemory = settings.longTermMemory(),
    )

    /**
     * 脚本插件的运行时：`:sandbox` 进程里的 QuickJS。
     *
     * ## 为什么是注入而不是 `:plugin` 自己引
     *
     * `:plugin` 必须保持纯 JVM（§44 铁律）—— QuickJS 是带 `.so` 的 AAR，
     * 引进去整个模块变 Android library，所有插件测试从秒级变分钟级。
     * 所以那边只有一个窄接口，实现由这里注入。`DeviceInfoSource` /
     * `WebSearchSource` 给 `:tools` 也是同一个模式，这是第二次用。
     *
     * ## 装配期就要读它
     *
     * `PluginRegistry.build` 会读 [ScriptRuntime.unavailableReason] 来决定
     * 「这台设备能不能跑 script 形态的插件」，并把那句原因**原样**带给用户
     * （见 `QuickJsSandboxRuntime.unavailableReason`）。读它不会加载原生库，
     * 所以放在这里没有冷启动代价。
     */
    private val scripts: ScriptRuntime by lazy {
        QuickJsSandboxRuntime(context = appContext, filesDir = appContext.filesDir)
    }

    /**
     * 模型能看到的工具集合。**内置工具 + 已装插件**。
     *
     * 内置工具里 `search_history` 依赖 [search]（历史检索）。两者都是
     * `by lazy`，而 [search] 只依赖数据库句柄，没有循环依赖 —— 但
     * **别在这里引用 [conversations]**：那会绕回对话引擎，形成环。
     *
     * ## 为什么是一个「可替换的持有者」而不是直接建一次
     *
     * 插件列表要读数据库和 AndroidKeyStore，都是挂起操作，而容器构造是同步的。
     * 更要紧的是装完插件必须**立刻生效** —— 只在进程启动时建一次的话，
     * 用户装完插件得杀进程重开，而他不会知道要这么做，只会觉得
     * 「装了这个插件但模型还是不调用它」。
     *
     * 所以这里给出去的是一个可替换的注册表：启动时先只有内置工具，
     * 数据库读完（见 [PatchbayApp]）就换成「内置 + 插件」。
     *
     * ## 为什么 `definitions()` 每轮都要重新读
     *
     * 引擎每轮都会调它，而它读的是当前那个 volatile 引用 —— 于是插件的变化
     * 会**从下一轮开始**生效，正在跑的那一轮不会中途换工具集。
     */
    val tools: ToolRegistryHolder by lazy {
        ToolRegistryHolder(
            builtins = ::activeBuiltins,
            plugins = plugins,
            client = pluginHttpClient,
            scripts = scripts,
        )
    }

    /**
     * 切换长期记忆，并**立刻**重新装配工具。
     *
     * 两步的顺序不能反：先写设置、再 refresh —— refresh 里会重新求值
     * [activeBuiltins]，读的就是刚写进去的那个值。反过来会让这次切换
     * 直到下次 refresh 才生效，而界面上开关已经动了，看起来像「没生效」。
     *
     * refresh 是挂起的（要读插件表），所以这个方法是 suspend。
     */
    suspend fun setLongTermMemory(enabled: Boolean) {
        settings.setLongTermMemory(enabled)
        tools.refresh()
    }

    /**
     * 保存联网搜索配置，并**立刻**重新装配工具。
     *
     * ## 为什么必须 refresh
     *
     * 搜索工具**在不在**工具列表里，取决于用户有没有配好后端
     * （见 `BuiltinTools.all` 里的 `if (webSearch.configured())`）。
     * 不 refresh 的话，用户刚在设置页配好、回到对话里问一句需要联网的话，
     * 模型那边根本看不到这个工具 —— 而它会表现得像「这个 App 不能联网」，
     * 用户不可能想到要杀进程重开。
     *
     * ## 顺序
     *
     * 和 [setLongTermMemory] 一样：先写设置、再 refresh。refresh 里会重新
     * 求值 [activeBuiltins]，读的就是刚写进去的值。
     *
     * ## 参数
     *
     * [backend] 为 null 表示**关闭**联网搜索。关闭时**不清除密钥** ——
     * 用户可能只是临时关掉，把密钥一起删掉的话，他下次打开还得重填一遍
     * （而那个密钥在大多数服务上是不能再看第二眼的）。
     *
     * [apiKey] 为 null 表示「不动已存的密钥」。空串表示**显式清除**。
     * 这个三态和插件配置页的处理保持一致：密钥输入框永远不回显，
     * 所以「没碰」和「想删掉」在框里长得一样，必须靠调用方区分。
     */
    suspend fun saveWebSearch(
        backend: WebSearchBackend?,
        endpoint: String,
        apiKey: String? = null,
    ) {
        settings.setWebSearchBackend(backend?.id.orEmpty())
        settings.setWebSearchEndpoint(endpoint.trim())

        // 密钥按后端分开存（理由见 SettingsWebSearchSource 的 KDoc）。
        // backend 为 null（关闭）时不动任何密钥 —— 那把密钥还属于它的后端，
        // 用户下次开回来时应该还在
        if (backend != null) {
            val alias = SettingsWebSearchSource.alias(backend)
            when {
                apiKey == null -> Unit
                apiKey.isBlank() -> secrets.remove(alias)
                else -> secrets.put(alias, apiKey)
            }
        }

        tools.refresh()
    }

    /**
     * 设置页的「测试」按钮。
     *
     * 走的是 [SearchWebTool.probe]，也就是**模型调用这个工具时的同一条路径**。
     * 另写一条探测逻辑的话，会出现「测试通过、真跑不通」这种最难查的偏差。
     */
    suspend fun probeWebSearch(config: WebSearchConfig): ToolResult = SearchWebTool.probe(config)

    /**
     * 插件发起网络请求用的客户端。
     *
     * 配置搬到了 [pluginHttpClient] 那个工厂函数里 —— 因为**沙箱进程也要用
     * 同一份配置**（`host.http` 走的就是它），而两个进程不可能共用一个实例。
     * 各写一遍 Builder 的话，改了超时只改一处，另一处静默地用着旧值。
     *
     * 为什么和对话用的客户端分开、为什么必须关重定向，理由都在那个函数的 KDoc 里。
     */
    private val pluginHttpClient: OkHttpClient by lazy { pluginHttpClient() }

    /**
     * 拉取远端清单用的客户端。
     *
     * ## 为什么和 [pluginHttpClient] 分开
     *
     * 那个是「**插件**发起请求」用的，每一跳都要过白名单（`NetworkGuard`）。
     * 这个是「**用户**给一个地址，我们去取一段文本」用的 —— 触发者是用户
     * 主动点的按钮，而且那时候插件还不存在，谈不上它的权限。
     * 两者要遵守的规则完全不同，共用会让人以为它们是同一件事。
     *
     * ## `followRedirects` 必须关掉
     *
     * [RemoteTextFetcher] 自己逐跳跟重定向，**每一跳都要检查协议有没有被降级**
     * （用户写的是 https，就不能被 302 到 http）。开着自动重定向的话那个检查
     * 根本没机会跑，而且是**静默**失效。
     *
     * 这个约束由 [RemoteTextFetcher] 构造里的一条 `require` 守着 ——
     * 写在这里只是让改的人先看到理由。
     */
    private val manifestHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * 「从 URL 装插件」的取数层。
     *
     * 上限跟「从文件」用的是**同一个**常量 —— 两个入口面对的是同一类东西
     * （一份不知道多大的清单），上限不该各写各的。
     */
    val manifestFetcher: RemoteTextFetcher by lazy {
        RemoteTextFetcher(client = manifestHttpClient, maxChars = MAX_MANIFEST_CHARS)
    }

    /**
     * 「检查更新」用的客户端。
     *
     * ## 为什么又开一个 OkHttpClient
     *
     * [pluginHttpClient] 是「**插件**发请求」用的（每一跳过白名单），
     * [manifestHttpClient] 是「按**用户给的地址**取一段文本」用的（自己逐跳跟
     * 重定向、每一跳查降级）。这个是「去一个**固定地址**问一句话」——
     * 三条规则各不相同，共用会让人以为它们是同一件事。
     *
     * ## 两处刻意的设置
     *
     * - **`followSslRedirects = false`**：那个地址是 https，而且不该有重定向。
     *   开着的话，一次 `https → http` 的跳转会被**静默**跟过去 ——
     *   而它意味着「检查更新」这件事在明文里发生。真出现重定向时会拿到一个
     *   3xx，如实报出来比悄悄跟过去好。
     * - **超时短**：这是用户点完按钮在**等**的动作，不是后台任务。
     *   15 秒还没结果就该告诉他没成，而不是让他盯着转圈。
     */
    private val updateHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
    }

    /**
     * 「检查更新」的取数层。
     *
     * 地址**写死在这儿**：这是这个 App 自己的发布渠道，不是用户配置。
     * 让它可配等于开了一个「把检查更新指向别处」的口子 ——
     * 而那个口子没有任何用处（用户想手动看版本，直接开浏览器就行），
     * 却多了一个能悄悄改掉的地方。
     *
     * ## 这个字符串和仓库外面几件事绑在一起，而且**四种失败都是静默的**
     *
     * 1. **仓库必须保持 PUBLIC** —— 未认证的请求打私有仓库一律 404，
     *    用户看到的是「没找到发布版本」，像是这个仓库从没发布过
     * 2. **仓库不能改名 / 转移** —— 改了这里就 404，同一句话
     * 3. **发版时别加 `--prerelease`，也别用 `--draft`** —— `/releases/latest`
     *    只返回「最新的非草稿、非预发布」那一版。带了 `--prerelease` 的后果最隐蔽：
     *    不是「查不到」，而是**退回上一版**，于是用户看到的「有新版本」指向一个
     *    **更旧的** tag
     * 4. **tag 要能被解析**（`v1.2` / `1.2` 都行，`release-1.2` 不行）——
     *    解析不出来时界面说的是「比不了」而不是「已是最新」，这是有意的
     *    （宁可说不知道，不说一句确定的错话）
     *
     * 这四条在 `RELEASING.md` 第 8 步里对着写了一遍 —— 改这里时那边也要改。
     */
    val releases: GitHubReleaseClient by lazy {
        GitHubReleaseClient(
            client = updateHttpClient,
            apiUrl = "https://api.github.com/repos/jiunian503/Patchbay/releases/latest",
        )
    }

    /**
     * 当前安装的版本名，形如 `1.1`（**不带** `versionCode`）。
     *
     * 走 [AndroidDeviceInfo] 而不是自己读一遍 `PackageManager`：它已经是这个
     * 仓库里唯一读 `versionName` 的地方（见 `PatchbayApp.installCrashLogging`）。
     * 再写一份的话，两份迟早会给出不一样的版本号，而两份都「看着对」。
     *
     * 读不到时是 null —— 调用方据此说「读不到当前版本」，而不是编一个值去比。
     */
    val appVersionName: String? by lazy { AndroidDeviceInfo(appContext).versionName() }

    /**
     * 插件的运行时工作区（脚本插件存东西的地方）。
     *
     * 两个进程都要用**同一个路径**：主进程在卸载时删它，沙箱进程在跑脚本时
     * 读写它。所以路径只能有一个算法 —— 见 [PluginWorkspaces.under]。
     * `:sandbox` 进程里 `PatchbayApp` 会提前 return（不建容器），
     * 那边由 `ScriptSandboxService` 自己调同一个函数。
     */
    val workspaces: PluginWorkspaces by lazy { PluginWorkspaces.under(appContext.filesDir) }

    /** 已装插件。密钥（`secret = true` 的配置项）由它去 KeyStore 取。 */
    val plugins: PluginRepository by lazy {
        PluginRepository(dao = db.pluginDao(), secrets = secrets, tx = tx, workspaces = workspaces)
    }

    /**
     * 连一次 MCP 服务，把工具清单拉回来并落库。
     *
     * ## 为什么落库和刷新注册表要在这一个方法里做完
     *
     * 因为「拉到了清单」和「模型能看到新工具」是**同一件事的两半**：
     * 只写库不刷新的话，界面上显示连接成功、模型那边还是旧的一套；
     * 只刷新不写库的话，重开 App 又回到旧的一套。拆成两个方法让调用方
     * 自己拼，迟早有人只调一半 —— 而两种半吊子状态的症状都是
     * 「看起来成功了，但没生效」，最难查。
     *
     * ## 失败时**不写库、不刷新**
     *
     * 上一次成功拉到的清单继续生效（见 `McpRefresh` 的注释）。
     * 断个网不该让一个能用的插件变成「工具列表空空如也」。
     *
     * 用的是 [pluginHttpClient]：MCP 的每一跳都要过白名单，
     * 而那个客户端的 `followRedirects` 是关掉的（`NetworkGuard` 自己跟）。
     */
    suspend fun refreshMcpTools(plugin: InstalledPlugin): McpRefresh {
        val result = PluginHost.connect(plugin, pluginHttpClient)
        result.cache?.let { cache ->
            plugins.saveMcpCache(plugin.manifest.id, cache)
            // 工具集合变了，必须重新合成 —— 否则模型那边看不到刚拉回来的工具
            tools.refresh()
        }
        return result
    }

    /**
     * 崩溃记录。
     *
     * ## 为什么这里是**另一个实例**（和 `PatchbayApp` 装处理器时那个不是同一个对象）
     *
     * 因为装处理器必须**早于**容器构造 —— 容器构造要碰数据库、KeyStore、
     * OkHttp，那里炸掉正是最需要堆栈的一种崩溃，那时容器还不存在。
     *
     * 而 [CrashStore] 本身没有状态：它只是「某个目录」的一层操作。两个实例
     * 指向同一个 `filesDir/crash/`，就像同一个文件被打开两次。目录名只有
     * [CrashStore.DIR_NAME] 一处定义，所以两边必然指的是同一批文件。
     *
     * 用 `filesDir.resolve(...)` 而不是 `File(filesDir, ...)`：省一个
     * `java.io.File` 的 import，而且这里本来也不需要那个类型名。
     */
    val crashes: CrashStore by lazy {
        CrashStore(appContext.filesDir.resolve(CrashStore.DIR_NAME))
    }

    fun newConversationId(): String = UUID.randomUUID().toString()

    /**
     * 这个会话该用哪个服务商。
     *
     * ## 为什么不能直接转给 [conversationRouter]
     *
     * 会话行要等第一条消息发出去才建，而顶栏那个服务商入口在那之前就能点到。
     * 用户在那时换的服务商只能先存在设置里（见 [resolveProviderPin]）——
     * 不在这里读它的话，`ConversationRouter` 只会返回默认那个，用户刚做的选择
     * 会被静默盖掉，界面上表现为「顶栏弹回去了」。
     *
     * ## 这个方法会写库（一次，幂等）
     *
     * 行一旦出现就把待定值提升进库。顺序不能反：**先落库再清待定值** ——
     * 反过来的话中间崩一次就两边都没有了。
     */
    override suspend fun providerForConversation(conversationId: String): ResolvedProvider? {
        val row = db.conversationDao().get(conversationId)
        val pin = resolveProviderPin(
            conversationId = conversationId,
            rowExists = row != null,
            rowProviderId = row?.providerId,
            pendingConversationId = settings.pendingProviderConversation(),
            pendingProviderId = settings.pendingProvider(),
        )

        val pinned = pin.providerId
        if (pin.promote && pinned != null) {
            conversationRouter.repin(conversationId, pinned)
        }
        if (pin.clearPending) {
            settings.setPendingProvider(null, null)
        }

        // 待定值优先 —— `ConversationRouter` 不认识「用户还没说话就选好了」这件事。
        // 服务商被删了的话 resolve 给 null，那就退回路由自己的规则
        return pinned?.let { providers.resolve(it) } ?: conversationRouter.resolve(conversationId)
    }

    override suspend fun selectableProviders(): List<ProviderChoice> =
        providers.list().map { entity ->
            ProviderChoice(
                id = entity.id,
                name = entity.displayName,
                model = entity.model,
                // 有别名不代表 Key 还在（用户可能在系统设置里清了应用数据，
                // 而数据库行还在）。所以实际解一次，别用 keyAlias 猜
                hasApiKey = providers.resolve(entity.id)?.hasApiKey == true,
            )
        }

    /**
     * 把这个会话换到另一个服务商。
     *
     * ## 行还没建的时候不能直接写库
     *
     * 新会话的会话行要等第一条消息发出去才建，而顶栏的服务商入口在那之前就能点到。
     * 那时 `ConversationRouter.repin` 里的 UPDATE 影响 0 行、选择被静默丢掉
     * （用户看到顶栏弹回默认）。所以先记在设置里，等行一出现由
     * [providerForConversation] 提升进库 —— 和角色那条路同一个解法。
     *
     * @return 这次「换」的意图被接受了吗。行还没建时也返回 true：选择已经存下来了，
     *   界面该刷新去显示它（这正是修掉「顶栏弹回去」的关键）。
     *   服务商不存在才返回 false。
     */
    override suspend fun repinConversation(conversationId: String, providerId: String): Boolean {
        if (db.conversationDao().get(conversationId) == null) {
            if (providers.resolve(providerId) == null) return false
            settings.setPendingProvider(conversationId, providerId)
            return true
        }

        // 行在：和以前一样直接写。顺手清掉可能残留的待定值 —— 它已经过期，
        // 不清的话下一次读会拿它去覆盖用户刚刚做的选择
        if (settings.pendingProviderConversation() == conversationId) {
            settings.setPendingProvider(null, null)
        }
        return conversationRouter.repin(conversationId, providerId)
    }

    override suspend fun transcript(
        conversationId: String,
        limit: Int,
        before: MessageCursor?,
    ): TranscriptPage = conversations.transcript(conversationId, limit, before)

    override suspend fun allMessages(conversationId: String): List<StoredMessage> =
        conversations.allMessages(conversationId)

    /**
     * 按某个服务商建一次对话会话。
     *
     * [provider] 必须已经填过 API Key（[ResolvedProvider.config] 非 null），
     * 否则这里直接抛 —— UI 应该在此之前就拦住并引导用户去填。
     *
     * [approver] 由调用方（对话页）提供，用来在需要确认的工具执行前弹框。
     */
    override fun newSession(provider: ResolvedProvider, approver: ToolApprover): ChatSession {
        val config = requireNotNull(provider.config) {
            "服务商「${provider.name}」还没填 API Key"
        }
        return ChatSession(
            engine = ConversationEngine(
                client = OpenAiChatClient(config),
                tools = tools,
                approver = approver,
            ),
            store = conversations,
            ids = ids,
            // 角色人设 + 世界书命中条目。解析交给 ChatSession 做，
            // 因为世界书要扫历史，而历史只有那边才有
            promptSource = promptSource,
            // 开场白：会话还是空的时候，落成「角色说的第一句话」
            greetingSource = greetingSource,
        )
    }

    /** 启动清理：把上次异常退出留下的 `streaming` 消息标成失败。 */
    suspend fun recoverInterruptedMessages(): Int =
        conversations.failInterrupted(System.currentTimeMillis())
}
