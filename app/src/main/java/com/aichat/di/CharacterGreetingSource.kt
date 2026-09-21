package com.aichat.di

import com.aichat.chat.GreetingSource
import com.aichat.core.data.CharacterRepository
import com.aichat.domain.character.pickGreeting
import kotlin.random.Random

/**
 * [GreetingSource] 的实现：会话 → 角色卡 → 从候选里挑一条开场白。
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
 * ## 候选是**一列不是一句**：主开场白 + 备用，抽一条
 *
 * 角色卡里的开场白本来就有好几条（`first_mes` + `alternate_greetings`），
 * 那是作者给的开局变化 —— 每次都撞同一句，那几条备用就白写了。
 * 拼接候选（[CharacterRepository.greetingsFor]）与抽签（[pickGreeting]）
 * 都在别处，这里只负责把两者接起来。
 *
 * **抽签放在宿主层而不是 `:chat`**：`:chat` 的 `GreetingSource` 契约
 * 表达的是「这个会话该说哪句」这个最小问题，它不需要知道「有几条候选」
 * 这回事 —— 一旦让它挑，它就得知道随机数从哪来、什么时候该重新抽，
 * 而那两件事都只和「角色卡长什么样」有关。
 *
 * ## 空白当没有
 *
 * 卡里写了 `first_mes: ""` 或者只有空格的情况是存在的（写卡工具会留空字段）。
 * 在这里收口比在 `ChatSession` 里判断好 —— 那边只管「有没有东西要落」。
 * 备用那几条的空白与重复由 [pickGreeting] 一并滤掉。
 */
class CharacterGreetingSource(
    private val characterIdOf: suspend (String) -> String?,
    private val characters: CharacterRepository,
    /** 抽签用的随机源。默认全局那个；测试里换成固定种子即可复现。 */
    private val random: Random = Random.Default,
) : GreetingSource {

    override suspend fun greeting(conversationId: String): String? {
        val characterId = characterIdOf(conversationId) ?: return null
        return pickGreeting(characters.greetingsFor(characterId), random)
    }
}
