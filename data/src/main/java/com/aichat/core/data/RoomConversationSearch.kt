package com.aichat.core.data

import com.aichat.domain.search.ConversationSearch
import com.aichat.domain.search.MessageHit
import com.aichat.domain.text.CjkText

/**
 * [ConversationSearch] 的 Room 实现。
 *
 * ## 为什么只依赖一个 DAO
 *
 * 检索是**只读**的，不参与任何写入编排，所以这里不需要 `TransactionRunner`，
 * 也不需要 `ConversationDao` —— 会话标题由 [MessageFtsDao.search] 的
 * `LEFT JOIN` 一次带出来，不额外查一遍。
 *
 * ## 为什么不实现 `ConversationStore`
 *
 * 那是**对话生命周期**的出口，`ChatSession` 用它落盘。把检索挂上去的话，
 * `ChatSessionTest` 里的假实现就得跟着长一个 `search` —— 而那个假实现
 * 永远用不到检索。两个接口的消费者完全不同（一个是对话引擎，
 * 一个是搜索界面），所以分开。
 *
 * ## 切词为什么留在这里
 *
 * [CjkText.forQuery] 的调用**必须**留在实现里，不能让调用方自己做：
 * 用户输入里的 `"`、`-`、`*` 在 FTS 里是语法，每个调用方自己转义的话
 * 迟早有一个地方忘掉 —— 而忘了的后果是查询报错或语义错乱，不是崩溃，
 * 所以不会有人发现。
 *
 * ## `prefixLast` 为什么是 false
 *
 * 中文是**逐字索引**，短语查询（`"会 议"`）本身就已经是子串语义，
 * 加 `*` 不会多召回任何东西；而拉丁词加 `*` 会把 `rikka` 放大成
 * `rikkahub` 等一整片，对「我明确知道自己要找什么」的搜索框是噪声。
 *
 * 将来若要做**边输边搜**，那时的语义是「我还没打完」，才应该传 true。
 * 当前的界面是「输完按搜索」，所以用精确语义。
 */
class RoomConversationSearch(
    private val fts: MessageFtsDao,
) : ConversationSearch {

    override suspend fun search(query: String, limit: Int): List<MessageHit> {
        // 空查询在这里就返回，不到达数据库。
        // 注意别把这条短路理解成「防崩溃」—— 实测 `MATCH ''` 并不会抛异常，
        // 只是返回空结果。短路的理由是语义（空查询结果就是空）和省一次往返。
        // 纯空白输入经过 forQuery 的 trim 之后也是空串。
        val expr = CjkText.forQuery(query)
        if (expr.isEmpty()) return emptyList()

        return fts.search(expr, limit).map { row ->
            MessageHit(
                messageId = row.rowId,
                conversationId = row.conversationId,
                conversationTitle = row.conversationTitle,
                content = row.content,
                createdAt = row.createdAt,
            )
        }
    }
}
