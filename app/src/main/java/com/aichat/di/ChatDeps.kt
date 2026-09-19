package com.aichat.di

import com.aichat.chat.ChatSession
import com.aichat.chat.ConversationStore
import com.aichat.chat.MessageCursor
import com.aichat.chat.StoredMessage
import com.aichat.chat.TranscriptPage
import com.aichat.core.data.ResolvedProvider
import com.aichat.domain.tool.ToolApprover

/**
 * [com.aichat.ui.chat.ChatViewModel] 需要的那部分容器能力。
 *
 * ## 为什么不让 ViewModel 直接依赖 AppContainer
 *
 * `AppContainer` 会一路拖出 Room、AndroidKeyStore、OkHttp —— 也就是说
 * ViewModel 的单测必须在模拟器上跑（分钟级），而它里面装的恰恰是
 * **最容易写错的纯逻辑**：流式分段拼装、乐观追加用户消息、
 * 结束后从库里刷新。抽成接口之后这些逻辑在毫秒级就能验证。
 *
 * 接口只有三个方法，也顺便说明了 ViewModel 的真实依赖面有多窄 ——
 * 它不需要知道数据库、不需要知道密钥存在哪。
 */
interface ChatDeps {

    /**
     * **这个会话**该用的服务商。
     *
     * 注意不是「当前默认服务商」—— 会话是**黏住**的：第一次发消息时把当时的
     * 默认服务商钉在它身上，之后用户改了默认也不影响它。
     * 语义与两条退化路径见 `ConversationRouter`。
     *
     * `config` 为 null 表示还没填 API Key，界面据此引导用户去设置页，
     * 而不是发一个注定 401 的请求。
     */
    suspend fun providerForConversation(conversationId: String): ResolvedProvider?

    /**
     * 可以选的服务商（不含密钥），给「本会话用哪个」选择器用。
     *
     * 只带 id / 名称 / 模型 / 有没有 Key —— 选择器不需要密钥，
     * 也不该有机会碰到它。
     */
    suspend fun selectableProviders(): List<ProviderChoice>

    /**
     * 用户主动把这个会话换到另一个服务商。
     *
     * 这是「黏住」唯一的出口：没有它，会话一旦钉上就再也换不掉，
     * 用户唯一的办法是删掉整个会话（消息会一起没）。
     *
     * @return 换成功了没有（服务商不存在时 false）。
     */
    suspend fun repinConversation(conversationId: String, providerId: String): Boolean

    /**
     * 展示用的消息列表（含未完成的消息），**取最近一页**。
     *
     * [before] 为 null 表示最新一页；非 null 表示「再往上一页」，
     * 传上一次返回的 [TranscriptPage.cursor]。返回值里带
     * [TranscriptPage.hasMore]，界面据此决定要不要显示「加载更早的消息」。
     */
    suspend fun transcript(
        conversationId: String,
        limit: Int = ConversationStore.DEFAULT_TRANSCRIPT_LIMIT,
        before: MessageCursor? = null,
    ): TranscriptPage

    /**
     * 整个会话的全部消息，**按时间正序**。给导出用。
     *
     * 不复用 [transcript] 翻页循环：导出要的是「全部」，而那个循环的
     * 终止条件很容易写错（漏最后一页、或多读一页重复），错法是静默的 ——
     * 导出的文件少一段，用户不会逐行核对。
     */
    suspend fun allMessages(conversationId: String): List<StoredMessage>

    /**
     * 按服务商建一次会话。
     *
     * 每次都新建，不缓存 —— 用户中途改了 baseUrl 时，缓存下来的客户端
     * 会继续往旧地址发请求，表现为「改了配置没生效」且毫无报错。
     *
     * [approver] 由界面提供：需要确认的工具在真正执行前会挂在这里等用户点。
     * 做成参数而不是容器里的字段，是因为它必须**跟当前这个对话页绑定** ——
     * 弹框要弹在这个屏幕上。
     */
    fun newSession(provider: ResolvedProvider, approver: ToolApprover): ChatSession
}

/**
 * 选择器里的一行。
 *
 * [hasApiKey] 为 false 的也要列出来 —— 用户可能正想去填 Key，
 * 把它藏起来的话他会以为「这个服务商不存在」。
 */
data class ProviderChoice(
    val id: String,
    val name: String,
    val model: String,
    val hasApiKey: Boolean,
)
