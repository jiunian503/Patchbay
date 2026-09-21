package com.aichat.domain.prompt

/**
 * 把「角色人设」和「世界书命中条目」拼成**一条**系统消息。
 *
 * ## 它只管角色这一侧
 *
 * 服务商上配的「附加提示词」**不在这里** —— 它由引擎拼在更外层
 * （`:chat` 的 `ChatConfig.systemMessage()`）。分两处拼看起来啰嗦，
 * 但那是唯一能让「这一段来自服务商配置」这件事有个明确归属的地方：
 * 注入侧（`:app`）只认识角色卡，不认识服务商。
 *
 * 最终发出去的顺序是 **人设 → 世界书 → 服务商附加**，见那边的 KDoc。
 *
 * ## 为什么拼成一条，而不是发多条 system
 *
 * OpenAI 兼容服务端对多条 `system` 的处理并不统一：有的只认第一条，
 * 有的把后面的丢掉，有的直接报错。拼成一条之后顺序完全一样，
 * 但没有这个兼容风险。而且**两层都空时返回 null** —— 此时发出去的消息列表
 * 和加这个能力之前逐字节相同，老会话的行为不会有一丝变化。
 *
 * ## 不加任何标题或分隔标签
 *
 * 试过给世界书加 `[世界设定]` 这样的头，但模型有时会把它当成待办事项
 * 或输出格式来回应（「好的，我会参考世界设定」这种废话会漏到回复里）。
 * 用户写什么就原样发什么，层与层之间只用空行分开。
 */
object PromptComposer {

    /**
     * @param persona 角色卡的人设正文。
     * @param entries 本轮命中的世界书条目，**按注入顺序**传进来
     *   （[WorldBookMatcher.match] 的返回值已经是排好序的）。
     * @return 拼好的系统消息；两层都为空时返回 `null`。
     */
    fun compose(
        persona: String?,
        entries: List<WorldBookEntry> = emptyList(),
    ): String? {
        val layers = ArrayList<String>(entries.size + 1)

        persona?.trim()?.takeIf { it.isNotEmpty() }?.let { layers += it }
        entries.forEach { entry ->
            entry.content.trim().takeIf { it.isNotEmpty() }?.let { layers += it }
        }

        return layers.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    }

    /** 层与层之间。用空行而不是单个换行 —— 用户写的人设常以列表结尾，
     *  单换行会让两段在视觉上黏成一条，模型也更容易把它们读成同一段。 */
    private const val SEPARATOR = "\n\n"
}
