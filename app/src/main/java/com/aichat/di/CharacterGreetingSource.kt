package com.aichat.di

import com.aichat.chat.GreetingSource
import com.aichat.core.data.CharacterRepository

/**
 * [GreetingSource] 的实现：会话 → 角色卡 → 开场白。
 *
 * ## 为什么只有三行也要单开一个类
 *
 * 关键不在那三行，在**依赖从哪儿来**：`characterIdOf` 传的是
 * [AppContainer.effectiveCharacterId] 的引用，而不是 `ConversationDao`。
 * 「会话行还没建出来时选择暂存在设置里」这件事只有 [AppContainer] 知道 ——
 * 自己去查 DAO 的话，**新建对话里选的角色会在发第一条消息时被漏掉**，
 * 而那恰好是首次使用最可能走的一条路径（同 [CharacterSystemPromptSource]）。
 *
 * ## 查不到角色时返回 null，不抛
 *
 * 两种情况：会话指向一个已经被删掉的角色（正常路径下不会发生 ——
 * `CharacterRepository.delete` 会清掉引用），或者用户压根没选角色。
 * 两种情况下正确行为都是「这次不落开场白」，和升级前的老会话一样。
 * 抛异常的话，用户会看到一条和「聊天」毫无关系的报错。
 *
 * ## 空白当没有
 *
 * 卡里写了 `first_mes: ""` 或者只有空格的情况是存在的（写卡工具会留空字段）。
 * 在这里收口比在 [ChatSession] 里判断好 —— 那边只管「有没有东西要落」。
 */
class CharacterGreetingSource(
    private val characterIdOf: suspend (String) -> String?,
    private val characters: CharacterRepository,
) : GreetingSource {

    override suspend fun greeting(conversationId: String): String? {
        val characterId = characterIdOf(conversationId) ?: return null
        return characters.get(characterId)?.firstMessage?.takeIf { it.isNotBlank() }
    }
}
