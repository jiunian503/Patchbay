package com.aichat.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldBookMatcher] 的行为。
 *
 * 全是纯字符串，不需要模拟器、不需要数据库 —— 这就是把匹配放在 `:domain`
 * 的全部意义：世界书最容易出错的地方（中文怎么算命中、拉丁缩写怎么不算命中）
 * 可以在秒级穷举。
 */
class WorldBookMatcherTest {

    private fun entry(
        id: String,
        keys: List<String>,
        content: String = "内容-$id",
        enabled: Boolean = true,
        order: Int = 0,
        caseSensitive: Boolean = false,
    ) = WorldBookEntry(
        id = id,
        keys = keys,
        content = content,
        enabled = enabled,
        orderIndex = order,
        caseSensitive = caseSensitive,
    )

    private fun ids(entries: List<WorldBookEntry>) = entries.map { it.id }

    @Test
    fun `没有触发词命中时返回空列表`() {
        val entries = listOf(entry("a", listOf("北京")), entry("b", listOf("上海")))
        assertTrue(WorldBookMatcher.match(entries, listOf("今天天气不错")).isEmpty())
    }

    @Test
    fun `要扫描的文本为空时返回空列表`() {
        val entries = listOf(entry("a", listOf("北京")))
        assertTrue(WorldBookMatcher.match(entries, emptyList()).isEmpty())
        assertTrue(WorldBookMatcher.match(entries, listOf("")).isEmpty())
    }

    @Test
    fun `中文触发词按子串匹配，不要求词边界`() {
        val entries = listOf(entry("a", listOf("北京")))

        // 「去北京玩」和「北京大学」都该命中 —— 中文没有词边界，
        // 硬加 \b 会让这两种最正常的输入都匹配不上
        assertEquals(listOf("a"), ids(WorldBookMatcher.match(entries, listOf("我明天去北京玩"))))
        assertEquals(listOf("a"), ids(WorldBookMatcher.match(entries, listOf("他在北京大学读书"))))
    }

    @Test
    fun `纯拉丁触发词按词边界匹配，短缩写不会乱命中`() {
        val entries = listOf(entry("ai", listOf("AI")))

        assertEquals(listOf("ai"), ids(WorldBookMatcher.match(entries, listOf("AI 是一个缩写"))))
        assertEquals(listOf("ai"), ids(WorldBookMatcher.match(entries, listOf("我喜欢 ai 这个领域"))))

        // 这三句里都有 "ai" 这个子串，但都不是独立的词。
        // 不加词边界的话世界书会在这些句子上莫名其妙地触发。
        assertTrue(WorldBookMatcher.match(entries, listOf("He said hello")).isEmpty())
        assertTrue(WorldBookMatcher.match(entries, listOf("Please wait a moment")).isEmpty())
        assertTrue(WorldBookMatcher.match(entries, listOf("try again")).isEmpty())
    }

    @Test
    fun `拉丁触发词带标点时仍能按词边界命中`() {
        // 正则里的 . 是元字符，必须被 escape，否则 "a.b" 会匹配 "axb"
        val entries = listOf(entry("dot", listOf("a.b")))
        assertEquals(listOf("dot"), ids(WorldBookMatcher.match(entries, listOf("看看 a.b 这段"))))
        assertTrue(WorldBookMatcher.match(entries, listOf("看看 axb 这段")).isEmpty())
    }

    @Test
    fun `中英混排的触发词走子串分支`() {
        // 只要含一个 CJK 字符就按子串匹配。混排词加词边界没有意义：
        // "北京AI" 里的 \b 位置在中文语境下不可预测
        val entries = listOf(entry("mix", listOf("北京AI")))
        assertEquals(listOf("mix"), ids(WorldBookMatcher.match(entries, listOf("北京AI园区"))))
    }

    @Test
    fun `一组触发词里任意一个命中即激活`() {
        val entries = listOf(entry("wang", listOf("老王", "王叔", "王建国")))

        assertEquals(listOf("wang"), ids(WorldBookMatcher.match(entries, listOf("王叔今天来了"))))
        assertEquals(listOf("wang"), ids(WorldBookMatcher.match(entries, listOf("王建国是谁"))))
        assertTrue(WorldBookMatcher.match(entries, listOf("老王没来")).isEmpty().not())
    }

    @Test
    fun `停用的词条不参与匹配`() {
        val entries = listOf(
            entry("on", listOf("北京"), enabled = true),
            entry("off", listOf("北京"), enabled = false),
        )
        assertEquals(listOf("on"), ids(WorldBookMatcher.match(entries, listOf("北京"))))
    }

    @Test
    fun `触发词为空或内容为空的词条被跳过`() {
        val entries = listOf(
            entry("noKeys", emptyList()),
            entry("blankKey", listOf("  ")),
            entry("noContent", listOf("北京"), content = ""),
            entry("blankContent", listOf("北京"), content = "   "),
            entry("good", listOf("北京")),
        )
        // 用户新加一条还没填完时不该报错，但也不该在填上之前就生效
        assertEquals(listOf("good"), ids(WorldBookMatcher.match(entries, listOf("北京"))))
    }

    @Test
    fun `结果按 orderIndex 排序，相同时按 id 兜底`() {
        val entries = listOf(
            entry("c", listOf("北京"), order = 2),
            entry("b", listOf("北京"), order = 1),
            entry("a", listOf("北京"), order = 1),
        )
        // 顺序必须完全确定：两次发送得到不同的提示词是最难排查的一类差异
        assertEquals(listOf("a", "b", "c"), ids(WorldBookMatcher.match(entries, listOf("北京"))))
        // 传进来的顺序不影响结果
        assertEquals(
            listOf("a", "b", "c"),
            ids(WorldBookMatcher.match(entries.reversed(), listOf("北京"))),
        )
    }

    @Test
    fun `超出预算时按顺序截断`() {
        val entries = listOf(
            entry("first", listOf("北京"), content = "0123456789", order = 0),
            entry("second", listOf("北京"), content = "0123456789", order = 1),
            entry("third", listOf("北京"), content = "0123456789", order = 2),
        )
        // 预算 25：第一条 10、第二条 20 都能进，第三条会到 30 超限
        assertEquals(listOf("first", "second"), ids(WorldBookMatcher.match(entries, listOf("北京"), 25)))
    }

    @Test
    fun `第一条命中永远保留，哪怕它自己就超预算`() {
        val entries = listOf(entry("big", listOf("北京"), content = "x".repeat(100)))
        // 预算小到装不下唯一命中的条目时也必须保留 ——
        // 否则用户看到的是「我明明配了却没生效」，而界面上没有任何解释
        assertEquals(listOf("big"), ids(WorldBookMatcher.match(entries, listOf("北京"), 10)))
    }

    @Test
    fun `截断按整条进行，不切内容`() {
        val entries = listOf(
            entry("a", listOf("北京"), content = "A".repeat(10), order = 0),
            entry("b", listOf("北京"), content = "B".repeat(10), order = 1),
        )
        val hits = WorldBookMatcher.match(entries, listOf("北京"), 15)
        // 第二条要么整条进来要么整条不进，绝不能出现半截内容 ——
        // 模型会照着半句话编下去
        assertEquals(1, hits.size)
        assertEquals("A".repeat(10), hits[0].content)
    }

    @Test
    fun `大小写敏感开关生效`() {
        val strict = listOf(entry("s", listOf("AI"), caseSensitive = true))
        assertTrue(WorldBookMatcher.match(strict, listOf("ai 这个领域")).isEmpty())
        assertEquals(listOf("s"), ids(WorldBookMatcher.match(strict, listOf("AI 这个领域"))))

        val loose = listOf(entry("l", listOf("AI"), caseSensitive = false))
        assertEquals(listOf("l"), ids(WorldBookMatcher.match(loose, listOf("ai 这个领域"))))
    }

    @Test
    fun `跨多条文本扫描，任一条命中即可`() {
        val entries = listOf(entry("a", listOf("北京")))
        assertEquals(
            listOf("a"),
            ids(WorldBookMatcher.match(entries, listOf("你好", "他在北京", "再见"))),
        )
    }
}
