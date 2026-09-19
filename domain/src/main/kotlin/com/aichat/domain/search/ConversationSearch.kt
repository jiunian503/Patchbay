package com.aichat.domain.search

/**
 * 一条历史消息的检索命中。
 */
data class MessageHit(
    val messageId: Long,

    val conversationId: String,

    /**
     * 会话标题。
     *
     * **可能是空串** —— 那是「用户还没命名」，界面要显示成「新对话」
     * 而不是留一片空白。
     */
    val conversationTitle: String,

    /**
     * 消息正文**原文**。
     *
     * 必须来自主表，不能来自全文索引表 —— 索引里的正文是
     * [com.aichat.domain.text.CjkText.forIndex] 处理过的（汉字两侧补了空格），
     * 直接拿来显示会得到「会 议 记 录」这种东西。
     */
    val content: String,

    val createdAt: Long,
)

/**
 * 历史消息检索。
 *
 * ## 为什么输入是**用户原始查询串**，而不是 FTS 查询表达式
 *
 * 因为「怎么切词、怎么建索引」是实现的细节。哪天从 FTS4 换成别的
 * （FTS5、向量、远端检索），这个接口不该跟着变。
 * 调用方只知道一件事：我有一段用户输入，给我命中的消息。
 *
 * 把 [com.aichat.domain.text.CjkText] 的调用留在实现里，还有一条安全上的理由：
 * 用户输入里的 `"`、`-`、`*` 在 FTS 里是**语法**。让每个调用方自己记得
 * 转义一次，迟早有一个地方忘掉 —— 而忘了的后果是查询报错或者语义错乱，
 * 不是崩溃，所以不会有人发现。
 *
 * ## 为什么和 `ConversationStore` 分开
 *
 * 那个是**对话生命周期**的出口：`ChatSession` 用它落盘。所以它每加一个方法，
 * `ChatSessionTest` 里的假实现就得跟着长一个 —— 而那个假实现永远用不到检索。
 * 这个是**只读查询**。
 *
 * ## 两个消费者
 *
 * 一是搜索界面（用户自己找），二是 `:tools` 的 `SearchHistoryTool`
 * （模型自己找）。后者才是 Hermes 三层记忆里最上面那层的实际形态 ——
 * 检索要由模型在需要时**自己发起**，而不是等人来喂。
 *
 * 两者共用同一个实例（见 `AppContainer.search`），所以看到的索引必然一致；
 * 分成两份实现就会出现「用户搜得到、模型搜不到」这种最难解释的偏差。
 */
interface ConversationSearch {

    /**
     * 检索。
     *
     * [query] 为空或纯空白时**必须返回空列表**，不要往下传。
     *
     * 理由**不是**「空表达式会让 SQLite 抛异常」—— 实测（SQLite 3.53.1 / FTS4）
     * `MATCH ''` 只是安静地返回空结果。真正的理由有两条：
     *
     * - 空查询的结果在语义上就是空，为此跑一趟数据库没有意义；
     * - `''`、`'   '` 这些**畸形表达式的宽容度是实现细节，不是契约**。
     *   同一个 SQLite 上 `MATCH 'OR'` 就直接报 `malformed MATCH expression`，
     *   而 `MATCH ''` 不报 —— 把正确性建立在「它不会抛」上是不划算的。
     *
     * 顺带一提：这也意味着**不能靠「查询抛异常」来发现转义写漏了**。
     * 转义的防线只有一条，就是 `CjkText.forQuery` 本身，它由
     * `CjkSearchInstrumentedTest` 里的畸形输入用例守着。
     *
     * 结果按时间**倒序**，最近的在前。
     */
    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): List<MessageHit>

    companion object {
        /**
         * 一次最多取多少条。
         *
         * 50 条够用户判断「是不是要找这个」了，再多他也不看，
         * 而每一条都要回主表拿正文。
         */
        const val DEFAULT_LIMIT = 50
    }
}
