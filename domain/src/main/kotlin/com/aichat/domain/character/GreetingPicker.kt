package com.aichat.domain.character

import kotlin.random.Random

/**
 * 从一张角色卡的全部开场白里挑一条，作为新会话的第一句。
 *
 * ## 为什么是随机
 *
 * 卡里可以带好几条开场白（SillyTavern 的 `first_mes` + `alternate_greetings`），
 * 那是作者**故意**给的开局变化 —— 每次新会话都撞同一句，那几条备用就白写了。
 * （原版是靠手势「划」到下一句，本版没有那个界面，随机是最接近的等价物。）
 *
 * ## 判据
 *
 * - **空白条目当没有**：写卡工具会留空字段，`["", "  "]` 不该被算成两条候选，
 *   更不该有概率抽出一条空的开场白（那会让新会话里角色一言不发）
 * - **去重**：卡里偶尔把主开场白又抄进备用里。不去重的话它被抽中的概率是
 *   别人的两倍 —— 而「同一句话明显更容易出现」是用户能感觉到的
 * - **一条都没有 → `null`**，不是空串：调用方（`:chat`）用 `null` 表示
 *   「这次不落开场白」，返回空串会让它以为有一条空消息要写
 *
 * 抽签用传进来的 [random]（默认全局那个），测试里换成固定种子即可复现。
 */
fun pickGreeting(greetings: List<String>, random: Random = Random.Default): String? {
    val candidates = greetings.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (candidates.isEmpty()) return null
    return candidates[random.nextInt(candidates.size)]
}
