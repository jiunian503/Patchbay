package com.aichat.chat

import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolApprover
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 把「引擎要执行一个需要确认的工具」变成「界面上弹一个框，等人点」。
 *
 * ## 为什么单独一个类，而不是在 ViewModel 里写个 lambda
 *
 * 这段代码有一个**必须靠测试才能确认**的性质：**任何情况下都不能挂死**。
 *
 * 挂死的后果不是报错，而是「模型卡在那里转圈，用户等一个永远不来的回复」——
 * 比崩溃更难排查。而会挂死的路径不止一条：
 *
 * 1. 用户点了「停止」→ 整个协程被取消 → `await()` 抛 `CancellationException`，
 *    必须让异常正常传出去（而不是被吞掉后继续等）。
 * 2. 用户直接返回上一页 → ViewModel 被清理 → 同上。
 * 3. 界面上弹框的时机和 await 的时机错开（重组、旋转）→ 状态必须能被重新观察到。
 *
 * 抽成纯逻辑类之后，这三条都能在毫秒级单测里验证。
 *
 * ## 一个刻意的取舍：不做「本次对话不再询问」
 *
 * 加了它确实能少点几次（`fetch_url` 连抓几个页面时会烦），但那个开关会把
 * 整个确认机制变成一次性的形式 —— 用户点过一次之后，模型就能在剩余对话里
 * 任意发请求，而用户以为自己在盯着。真嫌烦的话，正确的做法是让插件声明
 * 更细的权限范围（比如只允许某几个域名），而不是「不再询问」。
 */
class ToolApprovalGate : ToolApprover {

    /**
     * 待确认的请求。
     *
     * [argumentsJson] 是**格式化过的**参数，直接给用户看 —— 原始参数是一行
     * 挤在一起的 JSON，用户没法读，也就无从判断该不该放行。
     *
     * [toolSummary] 取的是 [Tool.userSummary] 而不是 `definition.description`：
     * 后者是写给模型的提示词，展示给用户会是一段奇怪的长文。
     */
    data class Request(
        val id: Long,
        val toolName: String,
        val toolSummary: String,
        val argumentsJson: String,
    )

    private val _pending = MutableStateFlow<Request?>(null)

    /** 界面观察它来弹框。为 null 表示没有待确认的请求。 */
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private var nextId = 1L

    /** 当前正在等待的那个。引擎是顺序执行工具的，所以同一时刻最多一个。 */
    private var waiting: Pair<Long, CompletableDeferred<Boolean>>? = null

    override suspend fun approve(tool: Tool, arguments: JsonObject): Boolean {
        val id = nextId++

        // 引擎顺序执行工具，理论上不会出现两个待确认请求。
        // 但真出现时**绝不能**让前一个 await 一直悬着 —— 那是一个永久挂起。
        // 所以先把旧的判为拒绝（它的调用方会收到「用户拒绝」并继续跑），再接管。
        waiting?.let { (_, previous) -> previous.complete(false) }

        val deferred = CompletableDeferred<Boolean>()
        waiting = id to deferred
        _pending.value = Request(
            id = id,
            toolName = tool.definition.name,
            toolSummary = tool.userSummary,
            argumentsJson = prettyPrint(arguments),
        )

        try {
            return deferred.await()
        } finally {
            // 必须放 finally：无论是用户点了、被拒绝、还是整个协程被取消
            // （CancellationException 会从 await 抛出来），都要把弹框收掉。
            // 否则用户会看到一个点不动的残留对话框。
            if (waiting?.first == id) {
                waiting = null
                _pending.value = null
            }
        }
    }

    /**
     * 用户点了「允许」或「拒绝」。
     *
     * 带 [requestId] 而不是无条件放行，是为了挡住「旧对话框的迟到回调」：
     * 用户点了停止之后，界面上那个框可能还在，此时点它会误判到下一个请求上。
     */
    fun decide(requestId: Long, approved: Boolean) {
        val (id, deferred) = waiting ?: return
        if (id != requestId) return
        deferred.complete(approved)
    }

    /** 用户点了「停止」时立刻收掉弹框，不用等协程取消传导过来。 */
    fun cancelPending() {
        waiting?.second?.complete(false)
    }

    private companion object {
        private val pretty = Json { prettyPrint = true }

        /**
         * 格式化参数。
         *
         * 解析失败时退回原始字符串 —— 这里的目标是「让人看懂」，
         * 而不是校验合法性（合法性由引擎负责）。
         */
        fun prettyPrint(arguments: JsonObject): String =
            runCatching { pretty.encodeToString(JsonObject.serializer(), arguments) }
                .getOrDefault(arguments.toString())
    }
}
