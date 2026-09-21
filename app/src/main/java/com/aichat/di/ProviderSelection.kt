package com.aichat.di

/**
 * 一次「这个会话该钉哪个服务商」的解析结果。
 *
 * @property providerId 要**优先使用**的服务商 id。`null` 表示「没有待定值要插手，
 *   交给 `ConversationRouter` 按它自己的规则解析」（钉着的 → 用它；没钉过 →
 *   用默认并顺手钉上；钉着的被删了 → 退回默认并改钉）。
 *   **注意这和 [CharacterChoice] 的 `null` 语义不同** —— 那边 `null` 是
 *   「不用角色」，这边 `null` 是「我没意见，让别人决定」。服务商没有「不用」
 *   这个状态，会话总要落到某一个服务商上。
 * @property promote 要不要把 [providerId] 写进会话行。
 * @property clearPending 要不要把待定值清掉（已经用上了，或者它已经过期）。
 */
internal data class ProviderPin(
    val providerId: String?,
    val promote: Boolean,
    val clearPending: Boolean,
)

/**
 * 算出这次该优先用哪个服务商。
 *
 * ## 为什么服务商也需要一份「待定值」
 *
 * 和角色是**同一个坑**（详见 [resolveCharacterChoice] 的 KDoc）：会话行要等
 * 第一条消息发出去才由 `ChatSession.ensureConversation` 建，而顶栏那个服务商
 * 入口在**说话之前**就能点到（`ChatScaffold` 的标题一直可点，没有「有消息才
 * 出现」的门）。
 *
 * 于是「新建对话 → 点顶栏 → 换一个服务商 → 再说第一句话」这条路径上，
 * `ConversationRouter.repin` 里的 `UPDATE ... WHERE id = ?` 会更新 **0 行**，
 * 而它**还返回 true**（那个返回值只看服务商存不存在，不看行在不在）。
 * 用户看到的是顶栏弹回默认那个 —— 选择被静默丢掉。
 *
 * ## 为什么不能让 `ConversationRouter` 自己兜住
 *
 * 它没有「待定值」这个概念该待的地方：`ConversationRouter` 活在 `:data`，
 * 不认识设置（`:app`）。而且它不能顺手建行 —— 那会在会话列表里留下一个
 * 没说过话的幽灵会话，代码库把幽灵会话当成要避免的东西
 * （`AppSettings.lastConversationId` 的 KDoc 里写了为什么）。
 *
 * ## 纯函数
 *
 * 不碰 SharedPreferences 也不碰 Room：调用方把已经读出来的值传进来，
 * 拿结果决定要不要写。这段判断最容易出错（四个输入的组合），所以在 JVM 上直接测。
 */
internal fun resolveProviderPin(
    conversationId: String,
    rowExists: Boolean,
    rowProviderId: String?,
    pendingConversationId: String?,
    pendingProviderId: String?,
): ProviderPin {
    // 待定值只在会话 id 对得上时才算数 —— 否则用户选完又去别的会话里发消息，
    // 那个服务商会把别人的归属盖掉
    val pendingMatches = pendingProviderId != null && pendingConversationId == conversationId

    if (!rowExists) {
        // 行还没建。待定值必须在这里生效：交给 router 的话它只会给默认服务商，
        // 用户刚做的选择立刻被盖掉（顶栏「弹回去」就是这么来的）。
        // 但**不写库** —— 没有行可写，而且我们不该为了这个建行
        return ProviderPin(
            providerId = if (pendingMatches) pendingProviderId else null,
            promote = false,
            clearPending = false,
        )
    }

    if (!pendingMatches) {
        // 行在、没有待定值：交给 router 全权处理
        return ProviderPin(providerId = null, promote = false, clearPending = false)
    }

    if (rowProviderId == null) {
        // 行刚被 ensureConversation 建出来（`provider_id` 是 NULL），
        // 而用户在它存在之前就选好了 —— 把待定值落进行里。
        // 不落的话选择只活在设置里，重启后顶栏会显示默认那个，
        // 而实际用的却是另一个 —— 界面和行为对不上是最难查的一类问题
        return ProviderPin(providerId = pendingProviderId, promote = true, clearPending = true)
    }

    // 行上已经有归属了。那只可能是用户在行建出来**之后**又换过一次 ——
    // 而 `repinConversation` 那时会直接写库、不会留待定值。所以这个待定值
    // 是上一轮留下的过期数据：清掉，以行上的为准
    return ProviderPin(providerId = null, promote = false, clearPending = true)
}
