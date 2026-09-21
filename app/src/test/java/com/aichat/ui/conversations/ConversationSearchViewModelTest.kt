package com.aichat.ui.conversations

import com.aichat.domain.search.ConversationSearch
import com.aichat.domain.search.MessageHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ConversationSearchViewModel] 的状态机。
 *
 * 两件事值得单独钉住：
 *
 * 1. **「还没搜」和「搜了没结果」是两种状态**。合成一种的话，用户刚进页面
 *    就会看到「没有找到」—— 他会以为库里的消息搜不出来，而不是「我还没搜」。
 * 2. **连续搜索只有最后一次的结果生效**。不取消前一次的话，「先搜 A 再搜 B」
 *    时 A 的结果可能后到并覆盖 B 的，用户看到的是「搜出来的东西和输入对不上」。
 *
 * 用 [StandardTestDispatcher] 而不是 `UnconfinedTestDispatcher`：后者会让协程
 * 立刻跑完，压根构造不出「前一次还在跑」的时刻，也就测不了第 2 条。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSearchViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * 可控的检索实现。
     *
     * [delays] 用来制造「慢查询」——竞态测试必须让第一次检索在
     * 第二次提交时还没结束，否则根本没有覆盖的机会。
     */
    private class FakeSearch(
        private val delays: Map<String, Long> = emptyMap(),
        private val results: Map<String, List<MessageHit>> = emptyMap(),
    ) : ConversationSearch {

        val queries = mutableListOf<String>()

        /** 每次被要了多少条。「多要一条」那条判据靠它守。 */
        val limits = mutableListOf<Int>()

        override suspend fun search(query: String, limit: Int): List<MessageHit> {
            queries += query
            limits += limit
            delays[query]?.let { delay(it) }
            // 按上限裁剪 —— 真实实现就是这么做的（SQL 的 LIMIT）。
            // 不裁的话，「多要一条」在假实现上根本不成立，那条判据就成了空话。
            return results[query].orEmpty().take(limit)
        }
    }

    private fun hit(content: String, conversationId: String = "c1") = MessageHit(
        messageId = content.hashCode().toLong(),
        conversationId = conversationId,
        conversationTitle = "会议相关",
        content = content,
        createdAt = 1000L,
    )

    @Test
    fun `初始状态是还没搜过`() = runTest(dispatcher) {
        val vm = ConversationSearchViewModel(FakeSearch())

        assertFalse(vm.state.value.searched)
        assertTrue(vm.state.value.hits.isEmpty())
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun `提交后带上结果并标记已搜过`() = runTest(dispatcher) {
        val search = FakeSearch(results = mapOf("会议" to listOf(hit("会议纪要"))))
        val vm = ConversationSearchViewModel(search)

        vm.onQueryChange("会议")
        vm.submit()
        advanceUntilIdle()

        assertEquals(listOf("会议"), search.queries)
        assertEquals(listOf("会议纪要"), vm.state.value.hits.map { it.content })
        assertTrue(vm.state.value.searched)
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun `搜过但没结果也是已搜过`() = runTest(dispatcher) {
        val search = FakeSearch(results = emptyMap())
        val vm = ConversationSearchViewModel(search)

        vm.onQueryChange("不存在的词")
        vm.submit()
        advanceUntilIdle()

        assertTrue("搜完了才算搜过，跟有没有结果无关", vm.state.value.searched)
        assertTrue(vm.state.value.hits.isEmpty())
    }

    /** 空查询不该打到数据库，而且要回到「还没搜」而不是「没有找到」。 */
    @Test
    fun `空查询不检索并回到初始状态`() = runTest(dispatcher) {
        val search = FakeSearch(results = mapOf("会议" to listOf(hit("会议纪要"))))
        val vm = ConversationSearchViewModel(search)

        vm.onQueryChange("会议")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.state.value.searched)

        // 把输入清空再提交 —— 用户按了清除，或者删光之后按了搜索
        vm.onQueryChange("   ")
        vm.submit()
        advanceUntilIdle()

        assertEquals("空查询不该产生第二次检索", listOf("会议"), search.queries)
        assertFalse("要回到「还没搜」，而不是显示「没有找到」", vm.state.value.searched)
        assertTrue(vm.state.value.hits.isEmpty())
    }

    /**
     * 竞态：慢的那次先提交，快的那次后提交。
     *
     * 如果不取消前一次，慢查询后到会把快查询的结果覆盖掉，
     * 界面显示的内容和输入框里写的对不上 —— 用户只会以为搜索坏了。
     */
    @Test
    fun `连续搜索只有最后一次的结果生效`() = runTest(dispatcher) {
        val search = FakeSearch(
            delays = mapOf("慢" to 1000L, "快" to 0L),
            results = mapOf(
                "慢" to listOf(hit("慢查询的结果")),
                "快" to listOf(hit("快查询的结果")),
            ),
        )
        val vm = ConversationSearchViewModel(search)

        vm.onQueryChange("慢")
        vm.submit()
        advanceTimeBy(10) // 让慢查询跑起来，但停在 delay 里

        vm.onQueryChange("快")
        vm.submit()
        advanceUntilIdle()

        assertEquals(listOf("快查询的结果"), vm.state.value.hits.map { it.content })
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun `清除会重置查询和结果`() = runTest(dispatcher) {
        val search = FakeSearch(results = mapOf("会议" to listOf(hit("会议纪要"))))
        val vm = ConversationSearchViewModel(search)

        vm.onQueryChange("会议")
        vm.submit()
        advanceUntilIdle()

        vm.clear()

        assertEquals("", vm.state.value.query)
        assertEquals("", vm.state.value.submittedQuery)
        assertTrue(vm.state.value.hits.isEmpty())
        assertFalse(vm.state.value.searched)
    }

    /**
     * 输入框里的文字和「产生当前结果的那次查询」是两回事。
     *
     * 用户搜完「北京」拿到结果，接着在输入框里改成「上海」但**还没按搜索** ——
     * 这时结果仍然是「北京」那批。点结果跳进会话时要带过去标命中位置的是
     * 「北京」；带「上海」的话，那条消息里根本不会出现「上海」，
     * 用户看到的是「跳过来了，可什么都没标」，而他明明什么都没做错。
     */
    @Test
    fun `改输入框不会改变产生结果的那次查询`() = runTest(dispatcher) {
        val search = FakeSearch(results = mapOf("北京" to listOf(hit("北京适合秋天"))))
        val vm = ConversationSearchViewModel(search)

        vm.onQueryChange("北京")
        vm.submit()
        advanceUntilIdle()

        vm.onQueryChange("上海")

        assertEquals("输入框里是新文字", "上海", vm.state.value.query)
        assertEquals(
            "结果还是「北京」那批，带出去标命中位置的也必须是「北京」",
            "北京",
            vm.state.value.submittedQuery,
        )
        assertEquals(listOf("北京适合秋天"), vm.state.value.hits.map { it.content })
        assertEquals("改输入框不该触发检索", listOf("北京"), search.queries)
    }

    @Test
    fun `重新搜索会把记下的查询一起更新`() = runTest(dispatcher) {
        val search = FakeSearch(
            results = mapOf(
                "北京" to listOf(hit("北京适合秋天")),
                "上海" to listOf(hit("上海适合春天")),
            ),
        )
        val vm = ConversationSearchViewModel(search)

        vm.onQueryChange("北京")
        vm.submit()
        advanceUntilIdle()

        vm.onQueryChange("上海")
        vm.submit()
        advanceUntilIdle()

        assertEquals("上海", vm.state.value.submittedQuery)
        assertEquals(listOf("上海适合春天"), vm.state.value.hits.map { it.content })
    }

    /** 清除之后，还在跑的那次检索不能把结果写回来。 */
    @Test
    fun `清除会取消正在跑的检索`() = runTest(dispatcher) {
        val search = FakeSearch(
            delays = mapOf("慢" to 1000L),
            results = mapOf("慢" to listOf(hit("慢查询的结果"))),
        )
        val vm = ConversationSearchViewModel(search)

        vm.onQueryChange("慢")
        vm.submit()
        advanceTimeBy(10)

        vm.clear()
        advanceUntilIdle()

        assertTrue("被取消的检索不该再写回结果", vm.state.value.hits.isEmpty())
        assertFalse(vm.state.value.searched)
    }

    /**
     * 「后面还有更多」是**多要一条**问出来的。
     *
     * 只查上限那么多的话，「刚好 50 条」和「后面还有 200 条」在返回值上**长得一样** ——
     * 而结果区原来写的「找到 N 条」正是把这句假话说了出去（§111）。
     */
    @Test
    fun `结果顶到上限时会标记后面还有`() = runTest(dispatcher) {
        val limit = ConversationSearch.DEFAULT_LIMIT
        val many = (1..limit + 1).map { hit("会议 $it") }
        val search = FakeSearch(results = mapOf("会议" to many))
        val vm = ConversationSearchViewModel(search)

        vm.onQueryChange("会议")
        vm.submit()
        advanceUntilIdle()

        assertEquals("必须多要一条，否则分不出「刚好」和「还有」", listOf(limit + 1), search.limits)
        assertTrue("拿回 ${limit + 1} 条 ⇒ 后面还有", vm.state.value.truncated)
        assertEquals("界面上只列 $limit 条", limit, vm.state.value.hits.size)
    }

    /**
     * 刚好顶到上限**不算**截断 —— 那说明库里的匹配到 50 为止。
     *
     * 这时候报「还有更多」是**方向相反的同一种假话**，所以两个方向都要断。
     */
    @Test
    fun `刚好等于上限时不标记后面还有`() = runTest(dispatcher) {
        val limit = ConversationSearch.DEFAULT_LIMIT
        val exact = (1..limit).map { hit("会议 $it") }
        val search = FakeSearch(results = mapOf("会议" to exact))
        val vm = ConversationSearchViewModel(search)

        vm.onQueryChange("会议")
        vm.submit()
        advanceUntilIdle()

        assertFalse("要 $limit 条拿到 $limit 条，说明到顶了", vm.state.value.truncated)
        assertEquals(limit, vm.state.value.hits.size)
    }
}
