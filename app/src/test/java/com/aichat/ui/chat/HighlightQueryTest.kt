package com.aichat.ui.chat

import com.aichat.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「哪一条消息该标命中词」的判定。
 *
 * ## 为什么这个判定要单独抽出来测
 *
 * 判错的两种方式都是安静地错：
 *
 * - **判宽了**（整个会话都标）：同一个词出现几十次时满屏底色，
 *   用户反而找不到刚才点进来的那一条。
 * - **判窄了**（谁都没标）：用户从搜索结果跳进来，看到的是一条没有标记的
 *   长消息 —— 和没做这个功能一模一样，而且看不出是哪一步坏的。
 *
 * 判定依赖三个输入（消息 id、目标 id、查询词），组合起来有七八种情况，
 * 靠真机点一遍是覆盖不全的。
 *
 * 顺带钉住一条：**空白查询等于没有查询**。`highlightRanges` 那边本来就
 * 返回空，这里再挡一道，是为了不让下游每个渲染点都各自处理一遍空串 ——
 * 少一处漏写就少一处「标了整段」。
 */
class HighlightQueryTest {

    private fun message(id: Long) = MessageUi(
        id = id,
        role = ChatMessage.Role.Assistant,
        content = "北京适合秋天去",
    )

    @Test
    fun `被定位到的那一条拿到查询词`() {
        assertEquals("北京", highlightQueryFor(message(7), targetId = 7, query = "北京"))
    }

    @Test
    fun `同一个会话里其它消息拿到 null`() {
        assertNull(highlightQueryFor(message(8), targetId = 7, query = "北京"))
        assertNull(highlightQueryFor(message(6), targetId = 7, query = "北京"))
    }

    /** 普通打开会话（从列表点进来）：没有定位意图，谁都不标。 */
    @Test
    fun `没有目标 id 时谁都不标`() {
        assertNull(highlightQueryFor(message(7), targetId = null, query = "北京"))
    }

    @Test
    fun `没有查询词时谁都不标`() {
        assertNull(highlightQueryFor(message(7), targetId = 7, query = null))
    }

    @Test
    fun `空白查询等于没有查询`() {
        for (blank in listOf("", " ", "   ", "\t", "\n", "\u3000", "\u00A0")) {
            assertNull(
                "空白查询 ${blank.map { it.code }} 不该标任何东西",
                highlightQueryFor(message(7), targetId = 7, query = blank),
            )
        }
    }

    /**
     * 判定就是「id 相等」，不做别的。
     *
     * 这里刻意**不加**「负数 id 不标」这类守卫。还没落库的乐观消息确实用
     * 负数 id（`PENDING_ID = -1L`，见 `ChatViewModel`），但搜索读的是数据库，
     * 命中的 id 恒为正，所以「负数 id 恰好等于目标」在真机上不会发生。
     * 加一道守卫的话，将来真出现「调用方把 id 传错了」时它会被静默吞掉，
     * 而症状会变成「跳进来了但没标词」—— 正是这个判定要避免的那种安静错误。
     */
    @Test
    fun `判定只认 id 相等`() {
        assertEquals("北京", highlightQueryFor(message(7), targetId = 7, query = "北京"))
        assertNull(highlightQueryFor(message(7), targetId = 8, query = "北京"))
    }

    @Test
    fun `查询词不会被裁剪或改写`() {
        // 裁剪是 highlightRanges 的事，这里原样传递 ——
        // 两处都裁的话，将来改了其中一处的规则就会出现「卡片标了、消息没标」
        assertEquals("  北京  ", highlightQueryFor(message(7), targetId = 7, query = "  北京  "))
    }
}
