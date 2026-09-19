package com.aichat.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.chat.ConversationSummary
import com.aichat.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 侧边栏要的状态。
 *
 * 只有「有哪些会话」和「读完了没有」两件事 —— 服务商的名字和模型名不在这里：
 * 侧边栏不显示它们（对话页的标题栏已经写着「当前用哪个服务商 · 哪个模型」，
 * 在抽屉里再放一遍是同一句话出现两处）。之前那两个字段是给独立的列表页用的，
 * 列表页改成侧边栏之后就没有消费者了。
 */
data class ConversationListUiState(
    val items: List<ConversationSummary> = emptyList(),
    val loading: Boolean = true,
)

class ConversationListViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ConversationListUiState())
    val state: StateFlow<ConversationListUiState> = _state.asStateFlow()

    /**
     * 待落盘的一次导出。**单独一条流**，不放进 [ConversationListUiState]。
     *
     * 放进主状态会让「列表变了」和「要弹保存框了」共用一个通知 ——
     * 界面在重组时会反复看到同一个待办值，于是弹两次保存框。
     * 分开之后，界面读它、处理完调 [consumeExport]，语义是「一次性事件」。
     */
    private val _export = MutableStateFlow<ExportRequest?>(null)
    val export: StateFlow<ExportRequest?> = _export.asStateFlow()

    init {
        refresh()
    }

    /**
     * 重新读列表。
     *
     * 不用 `observeAll()` 那种响应式流：会话的 `updated_at` 是在**发送消息时**
     * 才变的，而用户刚聊完就拉开侧边栏，需要看到新的排序和时间。
     * 每次拉开抽屉拉一次，比维护一条常驻的数据库订阅更简单，也不会漏刷。
     */
    fun refresh() {
        viewModelScope.launch {
            val items = container.conversations.listConversations()
            _state.update { it.copy(items = items, loading = false) }
        }
    }

    fun delete(conversationId: String) {
        viewModelScope.launch {
            container.conversations.deleteConversation(conversationId)

            // 删掉的正好是「下次启动要恢复的那一个」→ 一起清掉。
            // 不清的话下次冷启动会恢复出一个库里已经不存在的会话，
            // 表现是一个空的幽灵对话 —— 用户会以为数据丢了，
            // 而这跟「删掉的那条真的没了」是完全不同的两件事。
            if (container.settings.lastConversationId() == conversationId) {
                container.settings.setLastConversationId(null)
            }

            refresh()
        }
    }

    /**
     * 用户手动改名。
     *
     * 空白标题**照写**（库里也是无条件覆盖）：用户可能就是想清掉标题，
     * 让它回到「新对话」的显示。在这里拦一道的话，那个动作就做不出来了 ——
     * 而界面上没有任何东西告诉用户「空标题不被接受」。
     *
     * 改完刷新：`rename` 会更新 `updated_at`，会话因此跳到列表最上面，
     * 不刷新的话用户会以为改名没生效（顺序和名字都没变）。
     */
    fun rename(conversationId: String, title: String) {
        viewModelScope.launch {
            container.conversations.renameConversation(
                conversationId,
                title,
                System.currentTimeMillis(),
            )
            refresh()
        }
    }

    /** 置顶 / 取消置顶。刷新之后排序由库里的 `pinned DESC, updated_at DESC` 决定。 */
    fun setPinned(conversationId: String, pinned: Boolean) {
        viewModelScope.launch {
            container.conversations.setPinned(conversationId, pinned)
            refresh()
        }
    }

    /**
     * 导出这个会话。
     *
     * 读的是 `allMessages`（**全部**）而不是 `transcript`（最近一页）：
     * 导出一份「只有最近 200 条」的文件比不导出更糟 —— 用户不会逐行核对，
     * 只会以为文件是完整的。
     *
     * 结果放进 [export] 交给界面落盘。ViewModel 不碰文件系统：
     * 写文件需要一个 `Uri`，而那个只能由 SAF 的 Activity 结果回调给出。
     */
    fun exportMarkdown(conversationId: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val title = _state.value.items.firstOrNull { it.id == conversationId }?.title.orEmpty()
            val messages = container.conversations.allMessages(conversationId)
            _export.value = ExportRequest(
                fileName = exportFileName(title, now),
                markdown = buildMarkdown(title, messages, exportedAt = now),
            )
        }
    }

    /**
     * 界面已经把文件写完了（或用户取消了保存框）—— 清掉待办。
     *
     * 不清的话它会一直挂在这个 StateFlow 上：用户下次点导出时，
     * 界面会先看到「上一份还没落盘」的那个值，再看到新的 ——
     * 表现是连弹两次保存框。
     */
    fun consumeExport() {
        _export.value = null
    }

    /**
     * 只生成一个 id 就跳走，**不建会话行**。
     *
     * 会话行由 `ChatSession.ensureConversation` 在第一条消息发出去时创建。
     * 这样「点了新建但没说话」不会在列表里留下一条空会话 ——
     * 空会话在列表里既没法命名也没法区分，纯属垃圾。
     */
    fun newConversationId(): String = container.newConversationId()
}
