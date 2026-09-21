package com.aichat.chat

/**
 * 新会话里**角色先说的那一句话**（角色卡上的「开场白」）。
 *
 * ## 为什么单独一个接口，而不是塞进 [SystemPromptSource]
 *
 * 两者回答的是不同的问题：那个问「这一轮要注入什么提示词」，这个问
 * 「这个会话有没有一句开场白要先落成消息」。合成一个的话，那个 `fun interface`
 * 的返回值就得同时表示两件事，而它们的**消费方式完全不同** ——
 * 一个拼进请求，一个要写库（见 [ChatSession.send]）。
 *
 * ## 为什么实现只能活在宿主层
 *
 * 和 [SystemPromptSource] 同理：开场白存在角色卡里，而角色卡在 Room 里，
 * `:chat` 不认识 Room。所以这里只声明「我需要一句开场白」，
 * 从哪儿拿是宿主的事（`CharacterGreetingSource`）。
 *
 * ## 为什么参数里有 conversationId
 *
 * 开场白是**角色**的属性，而「这个会话用哪个角色」只有宿主知道
 * （同一个服务商下，A 会话用这个角色、B 会话用那个）。从 [ChatMessage]
 * 列表里推不出「这是哪个会话」，更推不出「它选了谁」。
 */
fun interface GreetingSource {

    /**
     * @param conversationId 当前会话。用来查它选了哪个角色。
     * @return 角色卡上的开场白；`null` 或空白表示这个会话没有开场白可落。
     */
    suspend fun greeting(conversationId: String): String?
}
