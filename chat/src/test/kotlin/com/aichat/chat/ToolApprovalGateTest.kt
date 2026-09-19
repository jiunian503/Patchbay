package com.aichat.chat

import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批闸门。
 *
 * 这里最重要的不是「允许/拒绝能正确返回」，而是**任何路径都不能挂死**。
 * 挂死的表现是「模型转圈等一个永远不来的回复」—— 没有崩溃日志、
 * 没有报错，比崩溃难查得多。所以下面一半的用例都在验证这件事。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolApprovalGateTest {

    private val tool = object : Tool {
        override val definition = ToolDefinition(
            name = "fetch_url",
            description = "抓取一个网页。这是写给模型的提示词，不该出现在弹框里。",
            parameters = buildJsonObject { put("type", "object") },
        )
        override val userSummary = "向指定地址发起一次网络请求"
        override val requiresConfirmation = true
        override suspend fun execute(arguments: JsonObject) = ToolResult.ok("done")
    }

    private val args = buildJsonObject {
        put("url", "https://example.com")
        put("limit", 3)
    }

    // ---------- 基本流程 ----------

    @Test
    fun `请求会出现在 pending 里等待用户决定`() = runTest {
        val gate = ToolApprovalGate()

        val job = launch { gate.approve(tool, args) }
        advanceUntilIdle()

        val request = gate.pending.value
        assertNotNull("应该有一个待确认请求", request)
        assertEquals("fetch_url", request!!.toolName)
        // 必须是给用户看的那句话，而不是写给模型的 description
        assertEquals("向指定地址发起一次网络请求", request.toolSummary)
        assertFalse(
            "弹框里不该出现写给模型的提示词",
            request.toolSummary.contains("提示词"),
        )

        job.cancel()
    }

    @Test
    fun `点允许后返回 true 并收起弹框`() = runTest {
        val gate = ToolApprovalGate()
        var result: Boolean? = null

        launch { result = gate.approve(tool, args) }
        advanceUntilIdle()
        gate.decide(gate.pending.value!!.id, approved = true)
        advanceUntilIdle()

        assertEquals(true, result)
        assertNull("决定之后不该再有待确认请求", gate.pending.value)
    }

    @Test
    fun `点拒绝后返回 false`() = runTest {
        val gate = ToolApprovalGate()
        var result: Boolean? = null

        launch { result = gate.approve(tool, args) }
        advanceUntilIdle()
        gate.decide(gate.pending.value!!.id, approved = false)
        advanceUntilIdle()

        assertEquals(false, result)
        assertNull(gate.pending.value)
    }

    // ---------- 参数要能让人读懂 ----------

    /**
     * 弹框里给用户看的参数必须是**格式化过的**。
     *
     * 原始参数是一行挤在一起的 JSON，用户没法读 —— 读不了就没法判断该不该放行，
     * 确认机制就退化成了「闭眼点允许」。
     */
    @Test
    fun `参数被格式化成多行`() = runTest {
        val gate = ToolApprovalGate()

        val job = launch { gate.approve(tool, args) }
        advanceUntilIdle()

        val shown = gate.pending.value!!.argumentsJson
        assertTrue("应该换行显示，实际是：$shown", shown.contains("\n"))
        assertTrue(shown.contains("https://example.com"))
        assertTrue(shown.contains("limit"))

        job.cancel()
    }

    // ---------- 不能挂死的三条路径 ----------

    /** 路径一：用户点了「停止」，整个协程被取消。 */
    @Test
    fun `协程被取消时弹框会收起且不挂死`() = runTest {
        val gate = ToolApprovalGate()

        val job = launch { gate.approve(tool, args) }
        advanceUntilIdle()
        assertNotNull(gate.pending.value)

        job.cancel()
        advanceUntilIdle()

        assertNull("协程取消后弹框必须收掉，否则会留一个点不动的框", gate.pending.value)
    }

    /** 路径二：用户点了停止，界面还没来得及收框，此时点了框上的按钮。 */
    @Test
    fun `迟到的决定不会误判到下一个请求上`() = runTest {
        val gate = ToolApprovalGate()

        launch { gate.approve(tool, args) }
        advanceUntilIdle()
        val staleId = gate.pending.value!!.id

        // 用户点了停止 → 请求被清掉
        gate.cancelPending()
        advanceUntilIdle()

        // 之后界面才把「允许」回调送过来，带的是旧 id
        gate.decide(staleId, approved = true)
        advanceUntilIdle()

        // 不该有任何残留状态
        assertNull(gate.pending.value)
    }

    /**
     * 路径三：同时出现两个待确认请求。
     *
     * 引擎是顺序执行工具的，理论上不会发生。但真发生时，
     * **绝不能**让第一个 await 一直悬着 —— 那是一个永久挂起，
     * 表现是模型永远不再回复。所以新请求到来时先把旧的判为拒绝。
     */
    @Test
    fun `新请求到来时旧请求会被判为拒绝而不是永久挂起`() = runTest {
        val gate = ToolApprovalGate()
        var first: Boolean? = null

        // 用前台 launch 而不是 backgroundScope。
        //
        // backgroundScope 看起来更贴切（这个用例确实「不管」第二个请求），
        // 但 `advanceUntilIdle()` 的语义是**推进到前台任务清空为止**
        // （内部是 `advanceUntilIdleOr { events.none { it.isForeground } }`），
        // 队列里只剩后台任务时它会立刻返回。结果两个协程都没跑，
        // `first` 始终是 null —— 用例会以一个看着莫名其妙的
        // 「expected:<false> but was:<null>」失败。
        launch { first = gate.approve(tool, args) }
        advanceUntilIdle()

        launch { gate.approve(tool, args) }
        advanceUntilIdle()

        assertEquals("旧请求必须有结果，不能悬着", false, first)
        assertNotNull("新请求应该接管弹框", gate.pending.value)

        // 收尾：把第二个请求决定掉。不决定的话它会一直挂在 await 上，
        // runTest 结束时会报「有活跃子协程」—— 那是测试写法的问题，不是闸门的问题。
        gate.decide(gate.pending.value!!.id, approved = true)
        advanceUntilIdle()
    }

    // ---------- 边界 ----------

    @Test
    fun `没有待确认请求时 decide 是安全的空操作`() = runTest {
        val gate = ToolApprovalGate()
        gate.decide(1L, approved = true)
        assertNull(gate.pending.value)
    }

    @Test
    fun `没有待确认请求时 cancelPending 是安全的空操作`() = runTest {
        val gate = ToolApprovalGate()
        gate.cancelPending()
        assertNull(gate.pending.value)
    }

    @Test
    fun `cancelPending 等价于拒绝`() = runTest {
        val gate = ToolApprovalGate()
        var result: Boolean? = null

        launch { result = gate.approve(tool, args) }
        advanceUntilIdle()
        gate.cancelPending()
        advanceUntilIdle()

        assertEquals(false, result)
        assertNull(gate.pending.value)
    }

    @Test
    fun `每次请求的 id 都不同`() = runTest {
        val gate = ToolApprovalGate()
        val ids = mutableListOf<Long>()

        repeat(3) {
            launch { gate.approve(tool, args) }
            advanceUntilIdle()
            ids += gate.pending.value!!.id
            gate.decide(gate.pending.value!!.id, approved = true)
            advanceUntilIdle()
        }

        assertEquals(3, ids.toSet().size)
    }

    @Test
    fun `空参数也能正常显示`() = runTest {
        val gate = ToolApprovalGate()

        val job = launch { gate.approve(tool, buildJsonObject { }) }
        advanceUntilIdle()

        assertNotNull(gate.pending.value)
        assertFalse(gate.pending.value!!.argumentsJson.isBlank())

        job.cancel()
    }
}
