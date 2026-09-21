package com.aichat

/**
 * 中文数字转整数，够这个项目的文档用（一到九十九）。
 *
 * ## 为什么单独一个文件
 *
 * 它是**两个测试共用的**：`NavigationGraphTest` 读导航图 KDoc 里那句
 * 「**N 个目的地**」，`NetworkEgressTest` 读 README 里那句「出网请求只有 N 种」。
 * 两处守的是同一个毛病 —— **文档里写死的数字漂了，而没有任何东西会红**。
 *
 * 各写一份的话，改了一处忘了另一处**不会有任何症状**：两边都还是绿的，
 * 直到某天有一边算错。这正是这个项目一贯要避免的形状。
 *
 * ## 边界
 *
 * 只认 `一`…`九十九`。文档里的数量到不了三位数，真到了也该改那句话的写法
 * （写阿拉伯数字更好认），而不是把这个函数写复杂。
 *
 * 认不出来返回 `null`，**调用方要以「找不到那句话」红掉** —— 不能静默放过。
 * 返回 0 也不行：`0` 和「解析失败」长得太像。
 */
internal object ChineseNumbers {

    private val DIGITS = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    /** `"七"` → 7，`"十二"` → 12，`"二十"` → 20，`"二十一"` → 21；认不出来 → `null`。 */
    fun parse(text: String): Int? {
        if (text.isEmpty()) return null
        if (text == "十") return 10

        val ten = text.indexOf('十')
        if (ten < 0) return DIGITS[text.singleOrNull()]

        // `十X` 的十位是 1（中文不写「一十」）
        val tens = if (ten == 0) 1 else DIGITS[text[0]] ?: return null
        val onesText = text.substring(ten + 1)
        val ones = if (onesText.isEmpty()) 0 else DIGITS[onesText.singleOrNull()] ?: return null
        return tens * 10 + ones
    }

    /**
     * 文档里能出现的数字串（一到九十九）—— 给正则用。
     *
     * 写成常量而不是让每个调用方自己拼 `[一二三四五六七八九十]+`：
     * 那样两处的正则会长得不一样，而「哪一处更松」是没人会去比的东西。
     */
    const val PATTERN: String = "[一二三四五六七八九十]+"
}
