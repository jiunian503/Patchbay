package com.aichat.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.domain.search.ConversationSearch
import com.aichat.domain.search.MessageHit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationSearchUiState(
    /** 输入框里的当前文字。用户随时可以改，改完不一定提交。 */
    val query: String = "",
    val hits: List<MessageHit> = emptyList(),
    val searching: Boolean = false,

    /**
     * 用户至少提交过一次检索。
     *
     * **这个字段不能省**：没有它，「一进页面」和「搜完没结果」是同一个状态
     * （都是 `hits` 为空），界面只能显示「没有找到」。而用户刚进来什么都没搜，
     * 看到「没有找到」会以为库里的消息搜不出来。
     */
    val searched: Boolean = false,

    /**
     * 上一次检索**顶到了上限** —— 也就是后面还有没列出来的。
     *
     * ## 为什么非要有这个字段
     *
     * 一次检索最多要 [ConversationSearch.DEFAULT_LIMIT] 条，而结果区原来只写
     * 「找到 N 条」：匹配 200 条时那句话是**事实错误** —— 用户会以为库里
     * 只有 50 条，于是不再往下找。**上限没露在外面，就会把人引到错结论上**（§111）。
     *
     * ## 「还有没有更多」是**多要一条**问出来的
     *
     * 只查上限那么多的话，「刚好 50 条」和「还有更多」在返回值上**长得一样**。
     * 所以要 51 条：拿到 51 条就说明至少还有 51 条（取前 50 条显示、把这一位置真）；
     * 只拿到 50 条则说明库里的匹配到 50 为止 —— 那种情况**不算**截断，
     * 报「还有更多」同样是假话。
     */
    val truncated: Boolean = false,

    /**
     * **产生当前这批 [hits] 的那次查询**，不是输入框里的当前文字。
     *
     * 两者必须分开。用户搜完「北京」拿到结果，接着在输入框里改成「上海」
     * 但还没按搜索 —— 这时 [hits] 仍然是「北京」的结果。如果高亮用 [query]，
     * 卡片会在北京的结果里去标「上海」（标不到），点进去之后会话里也标不到，
     * 用户看到的是「搜到了却哪儿都没标」，而真正的原因只是他多打了一个字。
     *
     * 同理，点结果跳转时带的也必须是这个，而不是 [query]。
     */
    val submittedQuery: String = "",
)

/**
 * 历史消息检索（Hermes 三层记忆的第三层）。
 *
 * ## 为什么收 [ConversationSearch] 而不是 `AppContainer`
 *
 * 和 `ChatViewModel` 收 `ChatDeps` 同一个理由：这个页面**只做检索**，
 * 给它整个容器的话，测试里就得造出一个带数据库、密钥库、网络客户端的
 * 完整容器才能验证「空查询不该查库」这种一行逻辑。收窄接口之后，
 * 一个十几行的假实现就够，而且能顺手验证竞态。
 *
 * ## 为什么是「按搜索键才查」而不是边输边搜
 *
 * 中文输入法在拼拼音的过程中会不断产生中间态（`huiyi`、`会`、`会议`），
 * 边输边搜意味着这些中间态都会各查一次库，其中绝大多数是用户根本不想搜的。
 * 而且中文是逐字索引，前缀语义没有意义（见 `RoomConversationSearch` 的 KDoc）。
 *
 * 所以界面上是「输完按回车/搜索」。
 */
class ConversationSearchViewModel(private val search: ConversationSearch) : ViewModel() {

    private val _state = MutableStateFlow(ConversationSearchUiState())
    val state: StateFlow<ConversationSearchUiState> = _state.asStateFlow()

    /**
     * 正在跑的那次检索。
     *
     * 每次提交前先取消上一次。不取消的话，「先搜 A、再马上搜 B」时
     * A 的结果可能后到并覆盖 B 的 —— 表现是「搜出来的东西和输入框里对不上」，
     * 而用户只会以为搜索功能坏了。
     */
    private var running: Job? = null

    fun onQueryChange(text: String) {
        _state.update { it.copy(query = text) }
    }

    fun submit() {
        val query = _state.value.query
        running?.cancel()

        if (query.isBlank()) {
            // 清空查询是回到「还没搜」的状态，不是「搜了但没有」。
            // 直接复用 clear() 的语义，免得两条路径各写一遍。
            clear()
            return
        }

        _state.update { it.copy(searching = true) }
        running = viewModelScope.launch {
            // 多要一条：拿回来超过上限就说明后面还有（见 [pageOf]）。
            // 只查上限那么多的话，「刚好 50 条」和「还有更多」分不出来 ——
            // 见 [ConversationSearchUiState.truncated] 的 KDoc。
            val limit = ConversationSearch.DEFAULT_LIMIT
            val page = pageOf(search.search(query, limit = limit + 1), limit)

            _state.update {
                it.copy(
                    hits = page.items,
                    truncated = page.truncated,
                    searching = false,
                    searched = true,
                    // 连查询词一起记下来：后面标命中位置、跳转都用它，
                    // 而不是输入框里可能已经被改过的 `query`
                    submittedQuery = query,
                )
            }
        }
    }

    /** 清空输入和结果，回到刚进页面的样子。 */
    fun clear() {
        running?.cancel()
        _state.value = ConversationSearchUiState()
    }
}
