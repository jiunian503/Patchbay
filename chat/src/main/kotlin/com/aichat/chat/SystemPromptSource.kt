package com.aichat.chat

/**
 * 每轮开始前，按**当前上下文**解析出要注入的系统提示词。
 *
 * ## 为什么是一个接口，而不是让引擎自己去查
 *
 * 角色卡和世界书都存在数据库里，而 `:chat` 不认识 Room，也不该认识
 * （`:chat` 不依赖 `:data`，这是模块边界的地基）。所以由宿主（`:app`）
 * 实现这个接口、把拼好的结果交进来 —— 和 [ConversationStore] 是同一个套路：
 * `:chat` 只声明它需要什么，怎么拿是宿主的事。
 *
 * ## 为什么是 suspend，而且每轮都要重新解析
 *
 * 它要读库，所以是 suspend。而**每轮都重新解析**不是浪费，是必须的：
 * 世界书是按当前上下文命中的，用户换了个话题，命中的条目就该换一批。
 * 只在会话开始时解析一次的话，聊到第三章时还带着第一章的设定。
 *
 * 代价是每次发送多一到两次查询。实现方该缓存就缓存 —— 但**不要缓存到
 * 跨轮**，那等于把上面这件事又做坏了。
 *
 * ## 为什么参数里有 conversationId
 *
 * 角色是**会话的属性**（同一个服务商下，A 会话用这个角色、B 会话用那个），
 * 所以只给 [history] 是不够的 —— 从消息内容反推不出「这是哪个会话」。
 */
fun interface SystemPromptSource {

    /**
     * @param conversationId 当前会话。用来查这个会话选了哪个角色。
     * @param history 这一轮真正要发出去的历史，**已包含本次用户输入**
     *   （`ChatSession` 是先落库再读历史的）。世界书就是扫它来命中的。
     * @return 要注入的提示词；返回 `null` 或空白表示这一轮不注入任何东西。
     */
    suspend fun resolve(conversationId: String, history: List<ChatMessage>): String?
}
