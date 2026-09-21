package com.aichat.ui.characters

import com.aichat.domain.prompt.WorldBookMatcher

/**
 * 世界书说明里那句「**一次注入有上限**」。
 *
 * ## 为什么非说不可
 *
 * [WorldBookMatcher.match] 按顺序累加内容长度，**超过预算就停**（只有第一条
 * 命中是无条件保留的）。而这个截断是**静默的**：界面上没有任何地方显示本轮
 * 注入了哪几条，也没有任何地方显示被丢掉了哪几条。用户看到的现象是
 * 「我明明配了这条设定，模型却不知道」—— 而线索（顺序 + 上限）一条都没露在外面。
 *
 * 更要紧的是另外两处文案在往反方向引：README 说「设定集写多长都行」，
 * 角色列表的说明卡说「写多长都不占日常对话的上下文」。后者**字面没错**
 * （不命中确实不占），但它们会让人**推出**「长度不要钱」——
 * 于是在「同时命中很多条」这个场景下踩空。
 *
 * ## 为什么数字不写成字面量
 *
 * 文案里的数字是**断言**（README 的体积数字就是这么过期的，§107.8）。
 * 这里直接从 [WorldBookMatcher.DEFAULT_BUDGET_CHARS] 取，改上限时文案跟着走，
 * 漂不动 —— `WorldBookHintUiTest` 里有一条守着这个等式。
 *
 * ## 为什么末尾要给「出路」
 *
 * 用户看到「排在后面的不生效」之后，下一步动作只有一个：**调顺序**（词条卡片上
 * 就有「上移 / 下移」）。只说限制不给动作，等于把问题丢回给他。
 */
internal fun worldBookBudgetHint(): String {
    val budget = WorldBookMatcher.DEFAULT_BUDGET_CHARS
    return "一次注入的内容有上限，约 $budget 字 —— 同时命中很多条时，" +
        "排在后面的那几条这一轮不会发出去。把更要紧的往前排。"
}
