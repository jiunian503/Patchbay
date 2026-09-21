package com.aichat.core.data

/**
 * 「这个会话该用哪个服务商」—— 会话路由。
 *
 * ## 语义：黏住
 *
 * 会话第一次发消息时，把当时的默认服务商**钉在它身上**（写进
 * `conversation.provider_id` / `model`），之后一直用它。
 *
 * 用户之后改了默认服务商，老会话**不受影响**。理由是换服务商往往意味着换模型，
 * 而模型换了之后，同一段对话的上下文、风格、能力边界都对不上 ——
 * 用户会看到「我明明没动这个会话，它怎么变了」。
 *
 * 要换必须是**用户主动的动作**（对话页顶部的服务商入口，走 [repin]）。
 *
 * ## 模型为什么不单独钉
 *
 * 钉的是 `providerId`，模型取**那个服务商当前的** `model`。
 *
 * 差别在「用户改了服务商配置里的模型名」这个场景：跟着配置走的话，
 * 老会话立刻用上新模型；跟着会话钉的话，得逐个会话去换。
 * 而模型下线、改名是常事（服务商那边说了算），用户改配置时的期望就是
 * 「以后都用这个」。所以这里跟着配置走。
 *
 * 「这个会话正在用哪个模型」在对话页顶部一直显示着，用户看得见，
 * 不会出现「悄悄换了」的情况。
 *
 * `conversation.model` 照写，作为**记录**（这个会话上次用的模型名）。
 * 将来要支持「同一个服务商换模型」时，在这里读它做会话级覆盖即可。
 *
 * ## 两个退化情形
 *
 * **会话还没钉过**（刚新建的，或升级前建的老会话）→ 用默认服务商，顺手钉上。
 * 不钉的话每次发消息都要重新解析，而用户中途改了默认服务商时，
 * 同一个会话的前后两轮会用到不同的服务商 —— 那正是「黏住」要避免的。
 *
 * **钉着的服务商被删了** → 退回默认并**改钉**。不退回的话这个会话会永久卡在
 * 「找不到服务商」上，用户唯一的出路是删掉整个会话 —— 而删会话会连消息一起删。
 */
class ConversationRouter(
    private val conversations: ConversationDao,
    private val providers: ProviderRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 解析这个会话该用的服务商。
     *
     * 返回 null 表示**一个服务商都没配** —— 那是用户还没填 Key 的正常状态，
     * 界面据此引导去设置页，而不是发一个注定 401 的请求。
     */
    suspend fun resolve(conversationId: String): ResolvedProvider? {
        val pinned = conversations.get(conversationId)?.providerId
        if (pinned != null) {
            // 钉着的服务商还在 → 就是它，不碰数据库
            providers.resolve(pinned)?.let { return it }
            // 服务商被删了 → 往下走，退回默认并改钉
        }

        val fallback = providers.resolveDefault() ?: return null
        conversations.setRoute(conversationId, fallback.id, fallback.model, clock())
        return fallback
    }

    /**
     * 用户主动换：把这个会话改钉到另一个服务商。
     *
     * 服务商不存在（传了个过期的 id）时**什么都不做**。不报错是因为这个方法的
     * 调用方是界面 —— 服务商列表是异步刷新的，用户点下去的一瞬间它可能刚被
     * 在另一个页面删掉。静默失败在这里是对的：会话保持原来的归属，
     * 用户看到的是「没换过去」，而不是一个看不懂的异常。
     *
     * @return 真的换成功了没有。服务商不存在，**或者会话行还没建出来**，都返回 false。
     */
    suspend fun repin(conversationId: String, providerId: String): Boolean {
        val provider = providers.resolve(providerId) ?: return false
        // 行还没建出来（新会话的第一条消息还没发）→ 下面那条 UPDATE 影响 0 行，
        // 什么都不会发生。**如实返回 false**，别让调用方以为换成功了：
        // 它要拿这个 false 去把这个选择暂存起来，等会话行出现再写。
        // 以前这里无条件返回 true，于是「新建对话 → 换服务商 → 发第一句话」
        // 这条路径上用户的选择被静默丢掉（见 :app 的 resolveProviderPin）
        if (conversations.get(conversationId) == null) return false
        conversations.setRoute(conversationId, provider.id, provider.model, clock())
        return true
    }
}
