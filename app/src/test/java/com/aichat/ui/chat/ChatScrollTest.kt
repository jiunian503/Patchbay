package com.aichat.ui.chat

import com.aichat.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话页消息列表的**定位逻辑**。两块：
 *
 * 1. 「要不要自动跟随到底部」（[isAtBottom]）
 * 2. 「从搜索结果点进来时定位到哪一条」（[highlightIndex]）
 *
 * 都是纯数值 / 纯列表判断，抽出来就能在这里钉住，不需要跑一个真的
 * `LazyColumn`，也不需要模拟器。
 *
 * ## 关于自动跟随
 *
 * 这个判断出过一次只在真机上、只对**长回复**可见的 bug：
 * 最早写的是按**条数**判断（`firstVisibleItemIndex >= totalItemsCount - 2`），
 * 而一条消息的高度可以差几十倍。长回复比整屏还高时，可见窗口只装得下
 * 两三条，第一个可见条目的序号离总数差好几个 → 判成「用户没贴着底部」→
 * 自动滚动整个失效。
 *
 * 症状极具迷惑性：回复**已经落库、也已经在 Compose 语义树里**
 * （`uiautomator dump` 能看到它的文字），只是停在屏幕外面。
 * 看起来像「模型没回」，实际是滚动问题。短回复恰好塞得进剩余空间，
 * 所以手测很容易漏过去。
 */
class ChatScrollTest {

    /** 1080x1920、密度 3 的机器上，消息区视口高度约 1700px，底部留白 12dp=36px。 */
    private val viewportEnd = 1700
    private val padding = 36

    private fun atBottom(
        totalItems: Int,
        lastIndex: Int,
        lastBottom: Int,
    ) = isAtBottom(
        totalItems = totalItems,
        lastVisibleIndex = lastIndex,
        lastVisibleBottom = lastBottom,
        viewportEndOffset = viewportEnd,
        afterContentPadding = padding,
    )

    @Test
    fun `空列表算贴着底部`() {
        assertTrue(
            "一条消息都没有时不该把「跟随」判成关",
            isAtBottom(
                totalItems = 0,
                lastVisibleIndex = 0,
                lastVisibleBottom = 0,
                viewportEndOffset = viewportEnd,
                afterContentPadding = padding,
            ),
        )
    }

    @Test
    fun `最后一条完整可见时算贴着底部`() {
        assertTrue(atBottom(totalItems = 5, lastIndex = 4, lastBottom = 1700 - padding))
    }

    @Test
    fun `最后一条的底边被截掉时不算贴着底部`() {
        assertFalse(
            "底边还在视口外面，用户看不到最新内容，不能算贴着底部",
            atBottom(totalItems = 5, lastIndex = 4, lastBottom = 1900),
        )
    }

    @Test
    fun `最后一个条目还没出现在视口里时不算贴着底部`() {
        assertFalse(atBottom(totalItems = 5, lastIndex = 3, lastBottom = 1600))
    }

    /**
     * 关键回归用例。
     *
     * 最后一条比视口还高（一段长 Markdown 回复就是这样）：滚到底时它的
     * 顶边已经在视口上方（`offset` 为负），底边正好落在视口底部。
     *
     * 旧的「按条数」判断在这种布局下会得出 false —— 因为可见窗口里
     * 装不下 3 个条目。这里用一个「负 offset + 高度超出视口」的真实数值
     * 把新判断钉住。
     */
    @Test
    fun `比视口还高的最后一条滚到底时算贴着底部`() {
        // 条目高 2464px，视口 1700px → 顶边在视口上方 800px
        val size = 2464
        val offset = -(size - (viewportEnd - padding))
        assertTrue(
            "offset=$offset size=$size：滚到底时底边贴在视口下沿，必须判成贴着底部",
            atBottom(totalItems = 2, lastIndex = 1, lastBottom = offset + size),
        )
    }

    @Test
    fun `比视口还高的最后一条只滚到一半时不算贴着底部`() {
        // 同一条回复，但只滚到了它的前半部分 —— 底边还在视口下面
        assertFalse(
            "底边还在视口下面，最新内容看不到，不能算贴着底部",
            atBottom(totalItems = 2, lastIndex = 1, lastBottom = 2000),
        )
    }

    /**
     * 底部留白必须算进去。
     *
     * 真正滚到底时，最后一个条目的底边停在「视口下沿减去留白」处
     * （留白本身也在视口外面）。
     *
     * 反证：把留白当成 0，那么「条目底边刚进视口、但留白还露在外面」
     * 这个位置就会被误判成「已经到底」—— 明明还能再往下滚几十像素。
     */
    @Test
    fun `底部内容留白必须算进到底的判断`() {
        val trueBottom = viewportEnd - padding
        assertTrue("真正滚到底的位置必须判成到底", atBottom(3, 2, trueBottom))

        // 1690：条目底边已经进了视口，但底部那 36px 留白还在视口外，
        // 也就是还能再往下滚 26px。这里不该算「到底」。
        assertFalse("条目底边进了视口、但底部留白还露在外面，不算到底", atBottom(3, 2, 1690))

        assertTrue(
            "不算留白的话同一个位置会被误判成到底，跟随会提前关掉 —— 这正是要防的",
            isAtBottom(
                totalItems = 3,
                lastVisibleIndex = 2,
                lastVisibleBottom = 1690,
                viewportEndOffset = viewportEnd,
                afterContentPadding = 0,
            ),
        )
    }

    @Test
    fun `亚像素误差不会让跟随反复开关`() {
        val exact = viewportEnd - padding
        assertTrue("超出 1px 属于布局抖动", atBottom(3, 2, exact + 1))
        assertTrue("超出 4px 仍在容差内", atBottom(3, 2, exact + 4))
        assertFalse("超出 5px 就该判成没到底", atBottom(3, 2, exact + 5))
    }

    // ---- 从搜索结果点进来时的定位 ----

    private fun messages(vararg ids: Long): List<MessageUi> =
        ids.map { MessageUi(id = it, role = ChatMessage.Role.User, content = "m$it") }

    @Test
    fun `带定位意图时返回那条消息的下标`() {
        val list = messages(11L, 22L, 33L)

        assertEquals(0, highlightIndex(list, 11L))
        assertEquals(1, highlightIndex(list, 22L))
        assertEquals(2, highlightIndex(list, 33L))
    }

    @Test
    fun `没有定位意图时返回 null`() {
        assertNull(highlightIndex(messages(11L, 22L), null))
    }

    /**
     * 消息已经不在这个会话里了（会话被清空过、消息被删了、或者导航参数
     * 是伪造的）。这时必须退回「落到底部」。
     *
     * 返回一个越界下标会让 `scrollToItem` 抛；而如果随便返回 0，
     * 用户会莫名其妙停在第一条 —— 两种都比「落到底部」糟。
     */
    @Test
    fun `定位目标不在列表里时返回 null`() {
        assertNull(highlightIndex(messages(11L, 22L), 99L))
        assertNull(highlightIndex(emptyList(), 11L))
    }

    /** 下标必须是第一个匹配项，`indexOfFirst` 和 `lastIndexOf` 在这里结果不同。 */
    @Test
    fun `id 重复时取第一个匹配项`() {
        assertEquals(0, highlightIndex(messages(7L, 7L, 7L), 7L))
    }
}
