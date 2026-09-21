package com.aichat.di

/**
 * 一次「这个会话用哪个角色」的解析结果。
 *
 * @property characterId 最终该用的角色 id，`null` = 不用角色。
 * @property promote 要不要把 [characterId] 写进会话行 —— 只在
 *   「会话行刚被建出来、而待定值还没落库」时为 true。
 * @property clearPending 要不要把待定值清掉（已经用上了，或者它已经过期）。
 */
internal data class CharacterChoice(
    val characterId: String?,
    val promote: Boolean,
    val clearPending: Boolean,
)

/**
 * 算出这次该用哪个角色。
 *
 * ## 为什么需要「待定值」这个东西
 *
 * 会话行是**第一条消息发出去时**才由 `ChatSession.ensureConversation` 建的
 * （`newConversationId()` 只生成 id，不建行 —— 见 `AppSettings.lastConversationId`
 * 的 KDoc）。而角色选择器在**说话之前**就能点到。
 *
 * 于是「新建对话 → 先选角色 → 再说第一句话」这条**最自然的首次使用路径**上，
 * `UPDATE conversation SET character_id = ? WHERE id = ?` 会更新 **0 行** ——
 * 不报错，选择被静默丢掉。用户会看到人设明明选了却不生效。
 *
 * 三种可能的修法，选了第三种：
 *
 * 1. **选角色时顺手把会话行建出来** —— 会在会话列表里留下一个没说过话的
 *    幽灵会话。代码库把幽灵会话当成要避免的东西（`AppSettings.lastConversationId`
 *    的 KDoc 里写了为什么）。
 * 2. **让 `ensureConversation` 也接受一个 characterId** —— 那等于让 `:chat`
 *    认识「角色」这个概念，破坏模块边界。
 * 3. **把选择先存在设置里，等会话行一出现就提升进库**（就是这里）——
 *    不动任何现有行为，纯加法。
 *
 * ## 待定值为什么要连会话 id 一起存
 *
 * 待定的是一句「**那个还没落库的会话**该用谁」。只存角色 id 的话，
 * 用户选完又去别的会话里发消息，那个角色就会被误用到别人身上。
 * 所以配对存储，只在会话 id 对上时才认。
 *
 * ## 纯函数
 *
 * 这里不碰 SharedPreferences 也不碰 Room —— 调用方把已经读出来的值传进来，
 * 拿结果去决定要不要写。这样这段最容易出错的判断逻辑可以在 JVM 上直接测。
 */
internal fun resolveCharacterChoice(
    conversationId: String,
    rowExists: Boolean,
    rowCharacterId: String?,
    pendingConversationId: String?,
    pendingCharacterId: String?,
): CharacterChoice {
    // 待定值只在会话 id 对得上时才算数
    val pendingMatches = pendingCharacterId != null && pendingConversationId == conversationId

    if (!rowExists) {
        // 会话行还没建出来：只能靠待定值。没有就什么也不注入 ——
        // 和升级前的老会话一样
        return CharacterChoice(
            characterId = if (pendingMatches) pendingCharacterId else null,
            promote = false,
            clearPending = false,
        )
    }

    if (!pendingMatches) {
        // 行在，没有待定值：直接用行上的值
        return CharacterChoice(characterId = rowCharacterId, promote = false, clearPending = false)
    }

    // 行在，待定值也对得上。这时有两种情况，区别是「行上有没有值」：
    if (rowCharacterId == null) {
        // 行刚被 ensureConversation 建出来（character_id 是 NULL），
        // 而用户在它存在之前就选好了角色 —— 把待定值提升进库，
        // 否则它只在内存/设置里活着，重启之后选择器会显示「不用角色」
        return CharacterChoice(characterId = pendingCharacterId, promote = true, clearPending = true)
    }

    // 行上已经有值了。那只可能是用户在行建出来**之后**又选过一次，
    // 而 `selectCharacter` 那时会直接写库、不会留待定值 ——
    // 所以这个待定值是上一轮留下的过期数据，清掉，以行上的值为准
    return CharacterChoice(characterId = rowCharacterId, promote = false, clearPending = true)
}
