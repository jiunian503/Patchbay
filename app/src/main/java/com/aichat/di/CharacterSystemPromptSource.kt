package com.aichat.di

import com.aichat.chat.ChatMessage
import com.aichat.chat.SystemPromptSource
import com.aichat.core.data.CharacterRepository
import com.aichat.domain.prompt.PromptComposer
import com.aichat.domain.prompt.WorldBookMatcher

/**
 * [SystemPromptSource] 的实现：会话 → 角色卡 → 人设 + 世界书命中条目。
 *
 * ## 为什么它在 `:app` 而不在 `:chat`
 *
 * 它要读会话表和角色库，而 `:chat` 不认识 Room。`:chat` 只声明了
 * 「我需要一个能按上下文给我提示词的东西」（那个 `fun interface`），
 * 谁来给、从哪儿拿，是宿主的事 —— 和 `ConversationStore` 是同一套做法。
 *
 * ## 每轮都要走一遍，所以这里有三步查询
 *
 * 会话 → 角色的两步（`characterIdOf` 里的 `conversations.get` + `characters.get`）
 * 都是按主键查，走的是索引，本地 SQLite 上是微秒级。世界书条目那一步才可能
 * 多一点，但一个角色的词条通常是个位数量级。
 *
 * **刻意不做跨轮缓存**：世界书是按当前上下文命中的，缓存就等于把
 * 「换了个话题就该换一批条目」这件事做坏了 —— 而那个错误的表现是
 * 「聊到第三章还带着第一章的设定」，用户根本不会想到是缓存的问题。
 *
 * ## 查不到角色时静默返回 null
 *
 * 会话指向一个已经被删掉的角色（正常路径下不会发生 —— `CharacterRepository.delete`
 * 会清掉引用），或者用户压根没选角色。两种情况下正确行为都是**不注入**：
 * 和升级前的老会话一样。抛异常的话，用户会看到一个和「聊天」毫无关系的报错。
 */
class CharacterSystemPromptSource(
    /**
     * 「这个会话用哪个角色」—— 直接传 [AppContainer] 上那个同名方法的引用，
     * 而不是把 `ConversationDao` 交进来。
     *
     * 差别不只是少一个依赖：会话行还没建出来时，选择是暂存在设置里的，
     * 而「要不要把暂存值提升进库」的判断只有那边有。这里自己去查 DAO 的话，
     * **新建对话里选的角色会在发第一条消息时被漏掉** —— 那条路径恰好是
     * 首次使用最可能走的一条。
     */
    private val characterIdOf: suspend (String) -> String?,
    private val characters: CharacterRepository,
) : SystemPromptSource {

    override suspend fun resolve(
        conversationId: String,
        history: List<ChatMessage>,
    ): String? {
        val characterId = characterIdOf(conversationId) ?: return null
        val character = characters.get(characterId) ?: return null

        val entries = characters.entriesFor(characterId)
        val hits = WorldBookMatcher.match(entries, history.scannableTexts())

        return PromptComposer.compose(persona = character.persona, entries = hits)
    }
}

/**
 * 世界书要扫的文本：用户和助手说过的话。
 *
 * ## 为什么跳过 `system` 和 `tool`
 *
 * - `system`：那条消息**就是我们自己拼出来的**，扫它等于让世界书
 *   自己触发自己 —— 命中的词条会被拼进下一条 system，再被扫到、
 *   再命中一次。这是个自我放大的回路，而且它不会报错，只会让上下文
 *   莫名其妙地变长。
 * - `tool`：工具结果是机器产生的（一段 JSON、一个网页摘要），里面的词
 *   不该激活用户手写的世界观设定。而且它动辄几千字，拿它去扫等于把
 *   世界书变成随机触发器 —— 用户搜一次网页，「北京」这个词条就被激活了。
 *
 * ## 不用 `reasoning`
 *
 * 思维链是模型的草稿纸，用户看不到它（默认折叠）。让它参与命中判定的话，
 * 用户会遇到「我什么都没说，为什么这个词条生效了」—— 而线索藏在
 * 一条他没展开的消息里。
 */
private fun List<ChatMessage>.scannableTexts(): List<String> =
    mapNotNull { message ->
        when (message.role) {
            ChatMessage.Role.User, ChatMessage.Role.Assistant ->
                message.content.takeIf { it.isNotBlank() }

            ChatMessage.Role.System, ChatMessage.Role.Tool -> null
        }
    }
