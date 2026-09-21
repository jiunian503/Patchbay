package com.aichat.domain.character

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [pickGreeting] 的判据。
 *
 * ## 为什么钉的是边界而不是「抽出来的是哪一条」
 *
 * 抽签本身没有正确答案，把某次的结果写死就等于把随机数实现的细节焊进测试 ——
 * 换一个 `Random` 实现它就红，而那个红不说明任何东西坏了。所以这里只钉三件事：
 * **什么时候返回 `null`**、**哪些条目算候选**、**同一个候选会不会被重复计权**。
 *
 * ## 分布那条测试为什么不是「不稳定的统计测试」
 *
 * 种子写死了，所以区间是确定的 —— 它不靠运气，跑一万次结果一样。
 * 选这个判据是因为「重复条目被重复计权」这件事**只在分布上看得出来**：
 * 去重与不去重，单次抽签的行为完全一样。
 */
class GreetingPickerTest {

    @Test
    fun `一条都没有时返回 null`() {
        assertNull(pickGreeting(emptyList()))
    }

    @Test
    fun `全是空白时返回 null，不是空串`() {
        // 返回空串的话 `:chat` 会以为「有一条空消息要落」——
        // `null` 才是「这次不落开场白」，两者后果完全不同
        assertNull(pickGreeting(listOf("", "   ", "\n")))
    }

    @Test
    fun `只有一条时就是那一条`() {
        assertEquals("你来啦", pickGreeting(listOf("你来啦")))
    }

    @Test
    fun `两端空白被裁掉`() {
        assertEquals("你来啦", pickGreeting(listOf("  你来啦  ")))
    }

    @Test
    fun `空白条目不算候选`() {
        // 卡里写 ["", "早"] 时，抽中空的那一次角色就一言不发 ——
        // 而「候选里有一条空话」还会把概率摊薄
        val random = Random(0)
        repeat(50) { assertEquals("早", pickGreeting(listOf("", "早", "  "), random)) }
    }

    @Test
    fun `抽出来的总是候选之一`() {
        val candidates = listOf("早", "晚安", "又见面了")
        val random = Random(7)
        repeat(100) {
            assertTrue(pickGreeting(candidates, random) in candidates)
        }
    }

    @Test
    fun `每条候选都有机会被抽到`() {
        // 只抽 100 次、三条候选，全抽中的概率极高；固定种子所以这是确定的。
        // 这一条挡的是「实现成永远返回第一条」那种退化
        val candidates = listOf("早", "晚安", "又见面了")
        val random = Random(2026)
        val seen = (1..100).mapNotNull { pickGreeting(candidates, random) }.toSet()
        assertEquals(candidates.toSet(), seen)
    }

    @Test
    fun `重复的条目只算一次，不会被重复计权`() {
        // 卡里偶尔把主开场白又抄进备用里。不去重的话它被抽中的概率是
        // 别人的两倍 —— 而「同一句话明显更容易出现」是用户能感觉到的。
        //
        // 去重后两条各半（900 次里约 450），不去重的话「晚安」只有约 300 次。
        // 区间取 380..520：离两种情况的期望都足够远，而种子固定所以不会飘
        val random = Random(20260921)
        val draws = (1..900).map { pickGreeting(listOf("早", "早", "晚安"), random) }
        val nights = draws.count { it == "晚安" }
        assertTrue(
            "去重后两条应当各半，「晚安」实际出现 $nights 次（不去重的话约 300 次）",
            nights in 380..520,
        )
    }
}
