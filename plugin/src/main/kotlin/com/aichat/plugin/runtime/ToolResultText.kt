package com.aichat.plugin.runtime

/**
 * 插件工具的结果文本，在交给模型之前要过的那道闸。
 *
 * ## 为什么非要有
 *
 * `ToolResult.content` 会被原样放进上下文（`ChatMessage.tool`），而且**每一轮都会
 * 重发**，直到它滑出窗口（`ConversationStore.DEFAULT_HISTORY_LIMIT` 条）。所以一个
 * 几 MB 的工具结果不是「多占一点内存」，是**把后面几十轮的每一次请求都撑大** ——
 * 而它本来只是一次工具调用的返回值。
 *
 * ## 为什么三个消费者共用一份
 *
 * 声明式（[DeclarativeTool]）、MCP（`McpClient`）、脚本（`ScriptTool`）是**同一个
 * 语义位置**：插件工具的结果 → 模型。以前前两个各写了一份 `truncate`，数字都是
 * 20 000、文案只差一个字；**第三个根本没有**。
 *
 * 于是新增一种插件形态时，那道闸就漏了 —— 这正是「同一个位置的上限只该有一处
 * 实现」（§111.9）的由来。
 *
 * ⚠️ 这是**同一个位置**共用，不是「同一个数字照抄」：`FetchUrlTool` 的 8 000、
 * `SearchWebTool` 的 4 000、`ShellSource` 的 32 768 量的是别的东西，
 * 别拿这一份去替（§111.19）。
 */
internal object ToolResultText {

    /**
     * 一条工具结果最多给模型多少字。
     *
     * 这个数字**只在这个位置有约束力**：它要挡的是「插件把一大坨东西倒给模型」，
     * 不是「这个 App 能处理多少数据」。所以别拿它去和上面那几个上限对齐。
     */
    const val MAX_CHARS = 20_000

    /**
     * 超了就截断，并且**在结果里说明白**。
     *
     * 「说明白」不能省：模型看到「已截断」才会在需要细节时换个更精确的查询，
     * 否则它会拿前半截当成全部（`SearchWebTool` 的「超出上限时必须说出来」
     * 是同一条规矩）。而且这句话是**给模型看的** —— 它得知道该怎么自救。
     */
    fun clip(text: String): String =
        if (text.length <= MAX_CHARS) {
            text
        } else {
            text.take(MAX_CHARS) +
                "\n\n（内容过长已截断：原文 ${text.length} 字，只保留前 $MAX_CHARS 字。" +
                "需要完整内容请让用户换个更精确的查询。）"
        }
}
