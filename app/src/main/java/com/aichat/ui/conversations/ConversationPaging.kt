package com.aichat.ui.conversations

/**
 * 一页结果 + 后面还有没有。
 *
 * 用泛型而不是各写一遍：这个「多要一条再拆」的手法本身很微妙 ——
 * 漏了 `take` 就会把**探针那一条**显示出去，而它只是用来「问」的、不是内容。
 * 两处的元素类型不同（`MessageHit` / `ConversationSummary`），
 * 所以泛型是这里最省事的共享方式。
 */
internal data class Page<T>(val items: List<T>, val truncated: Boolean)

/**
 * 「后面还有没有」是**多要一条**问出来的。
 *
 * ## 为什么不能只查 limit 条
 *
 * 查 `limit` 条时，「库里刚好有 limit 条」和「库里还有两百条」在返回值上
 * **长得一模一样** —— 界面只能瞎猜，而猜错的那一边说的就是假话（§111）：
 *
 * - **侧边栏**原来直接渲染 `listConversations()`（默认 100 条），没有分页也没有提示 ⇒
 *   第 101 个往后的会话**静默消失**，用户看到的是「我的会话没了」。
 * - **搜索结果区**原来写「找到 N 条」，而检索一次最多 50 条 ⇒ 匹配 200 条时
 *   那句话是**事实错误**：用户会以为库里只有 50 条。
 *
 * 所以两边都改成查 `limit + 1`：拿回来超过 `limit` 就说明后面还有。
 * 刚好 `limit` 条则说明**到顶了** —— 那种情况不算截断，
 * 报「还有更多」是方向相反的同一种假话。
 *
 * ⚠️ 假实现也必须按上限裁剪，否则这条判据在测试里根本不成立
 * （`ConversationSearchViewModelTest` 里的 `FakeSearch` 就踩过这一脚）。
 *
 * @param found 查询返回的全部结果。**调用方必须按 `limit + 1` 去查**，
 *   否则这里永远算不出 `truncated`。
 * @param limit 界面上打算显示多少条。
 */
internal fun <T> pageOf(found: List<T>, limit: Int): Page<T> =
    if (found.size > limit) {
        // 探针那一条不能显示出去
        Page(items = found.take(limit), truncated = true)
    } else {
        Page(items = found, truncated = false)
    }
