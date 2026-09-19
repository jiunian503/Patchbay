package com.aichat.chat

import com.aichat.domain.llm.AssistantToolCall
import com.aichat.domain.llm.ChatStreamEvent
import com.aichat.domain.llm.FinishReason
import com.aichat.domain.tool.SimpleToolRegistry
import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolApprover
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolResult
import com.aichat.network.ChatApiException
import com.aichat.network.ChatCompletionClient
import com.aichat.network.ChatCompletionRequest
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按脚本回放的假客户端。
 *
 * 每次 [stream] 调用消费一步预设 —— 这样就能精确控制「模型第一轮请求工具、
 * 第二轮给出答案」这类多轮场景，也能让**指定的某一轮**失败，
 * 而不用碰网络。
 */
private class FakeChatClient : ChatCompletionClient {

    private sealed interface Step {
        data class Emit(val events: List<ChatStreamEvent>) : Step
        data class Fail(val error: Throwable) : Step
    }

    private val steps = ArrayDeque<Step>()

    val requests = mutableListOf<ChatCompletionRequest>()

    /** 预设一轮的响应。 */
    fun enqueueRound(vararg events: ChatStreamEvent) {
        steps += Step.Emit(events.toList())
    }

    /** 预设某一轮直接失败。 */
    fun enqueueFailure(error: Throwable) {
        steps += Step.Fail(error)
    }

    override fun stream(request: ChatCompletionRequest): Flow<ChatStreamEvent> {
        requests += request
        return when (val step = steps.removeFirstOrNull()) {
            is Step.Emit -> flow { step.events.forEach { emit(it) } }
            is Step.Fail -> flow { throw step.error }
            // 没有预设 = 服务端直接断流，什么都不发
            null -> flow { }
        }
    }
}

private fun toolDef(name: String = "get_weather") = ToolDefinition(
    name = name,
    description = "测试用工具",
    parameters = buildJsonObject { put("type", JsonPrimitive("object")) },
)

private class StubTool(
    override val definition: ToolDefinition = toolDef(),
    override val requiresConfirmation: Boolean = false,
    private val body: suspend (JsonObject) -> ToolResult = { ToolResult.ok("晴 25°C") },
) : Tool {
    override suspend fun execute(arguments: JsonObject): ToolResult = body(arguments)
}

private fun delta(
    index: Int = 0,
    id: String? = null,
    name: String? = null,
    args: String? = null,
) = ChatStreamEvent.ToolCallDelta(index, id, name, args)

private fun history() = listOf(ChatMessage.user("北京今天天气怎么样"))

private fun config(maxToolRounds: Int = 8) = ChatConfig(model = "test-model", maxToolRounds = maxToolRounds)

class ConversationEngineTest {

    // ---------- 无工具调用 ----------

    @Test
    fun `纯文本回复产生文本增量与完成事件`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(
            ChatStreamEvent.TextDelta("你好"),
            ChatStreamEvent.TextDelta("，世界"),
            ChatStreamEvent.Finished(FinishReason.Stop),
        )

        val events = ConversationEngine(client).send(history(), config()).toList()

        assertEquals(
            listOf(
                ChatEvent.RoundStarted(0),
                ChatEvent.TextDelta("你好"),
                ChatEvent.TextDelta("，世界"),
                ChatEvent.Completed(ChatMessage.assistant("你好，世界")),
            ),
            events,
        )
    }

    @Test
    fun `只请求一轮`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(ChatStreamEvent.TextDelta("答案"))

        ConversationEngine(client).send(history(), config()).toList()

        assertEquals(1, client.requests.size)
    }

    @Test
    fun `系统提示词插在消息列表最前面`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(ChatStreamEvent.TextDelta("ok"))

        ConversationEngine(client)
            .send(history(), config().copy(systemPrompt = "你是一个助手"))
            .toList()

        val sent = client.requests[0].messages
        assertEquals("system", sent[0].role)
        assertEquals("你是一个助手", sent[0].content)
        assertEquals("user", sent[1].role)
    }

    @Test
    fun `空白的系统提示词不会被发出去`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(ChatStreamEvent.TextDelta("ok"))

        ConversationEngine(client).send(history(), config().copy(systemPrompt = "   ")).toList()

        assertEquals(1, client.requests[0].messages.size)
    }

    @Test
    fun `思维链被累积进最终消息`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(
            ChatStreamEvent.ReasoningDelta("先想"),
            ChatStreamEvent.ReasoningDelta("再答"),
            ChatStreamEvent.TextDelta("答案"),
        )

        val events = ConversationEngine(client).send(history(), config()).toList()

        val completed = events.last() as ChatEvent.Completed
        assertEquals("先想再答", completed.message.reasoning)
        assertEquals("答案", completed.message.content)
    }

    @Test
    fun `没有思维链时 reasoning 为 null`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(ChatStreamEvent.TextDelta("答案"))

        val events = ConversationEngine(client).send(history(), config()).toList()

        assertNull((events.last() as ChatEvent.Completed).message.reasoning)
    }

    // ---------- 工具循环 ----------

    @Test
    fun `一次工具调用后把结果回灌并继续`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(
            ChatStreamEvent.TextDelta("我来查一下"),
            delta(0, "call_a", "get_weather", ""),
            delta(0, null, null, """{"city":"北京"}"""),
            ChatStreamEvent.Finished(FinishReason.ToolCalls),
        )
        client.enqueueRound(
            ChatStreamEvent.TextDelta("北京今天晴，25°C"),
            ChatStreamEvent.Finished(FinishReason.Stop),
        )

        val engine = ConversationEngine(client, SimpleToolRegistry(listOf(StubTool())))
        val events = engine.send(history(), config()).toList()

        assertEquals(
            listOf(
                ChatEvent.RoundStarted(0),
                ChatEvent.TextDelta("我来查一下"),
                ChatEvent.ToolCallStarted(AssistantToolCall("call_a", "get_weather", """{"city":"北京"}""")),
                ChatEvent.ToolCallFinished("call_a", "get_weather", ToolResult.ok("晴 25°C")),
                ChatEvent.RoundStarted(1),
                ChatEvent.TextDelta("北京今天晴，25°C"),
                ChatEvent.Completed(
                    ChatMessage.assistant("我来查一下北京今天晴，25°C").copy(
                        toolCalls = listOf(
                            AssistantToolCall("call_a", "get_weather", """{"city":"北京"}""")
                        )
                    )
                ),
            ),
            events,
        )
    }

    @Test
    fun `第二轮请求的上下文带上 assistant 与 tool 消息`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(delta(0, "call_a", "get_weather", "{}"))
        client.enqueueRound(ChatStreamEvent.TextDelta("答案"))

        val engine = ConversationEngine(client, SimpleToolRegistry(listOf(StubTool())))
        engine.send(history(), config()).toList()

        val second = client.requests[1].messages
        // user -> assistant(带 tool_calls) -> tool(带 tool_call_id)
        assertEquals(3, second.size)
        assertEquals("user", second[0].role)
        assertEquals("assistant", second[1].role)
        assertEquals("call_a", second[1].toolCalls?.single()?.id)
        assertEquals("tool", second[2].role)
        assertEquals("call_a", second[2].toolCallId)
        assertEquals("晴 25°C", second[2].content)
    }

    @Test
    fun `只调工具不说话时 assistant 的 content 为 null`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(delta(0, "call_a", "get_weather", "{}"))
        client.enqueueRound(ChatStreamEvent.TextDelta("答案"))

        val engine = ConversationEngine(client, SimpleToolRegistry(listOf(StubTool())))
        engine.send(history(), config()).toList()

        // 空串会被部分服务端拒绝，必须是 null
        assertNull(client.requests[1].messages[1].content)
    }

    /**
     * 工具必须在**后台调度器**上执行，而不是引擎调用方的线程。
     *
     * 为什么这条值得单独守：引擎被 `viewModelScope`（`Dispatchers.Main.immediate`）
     * 驱动，不切线程就等于所有工具都跑在主线程上。而这件事**只炸一半** ——
     * `calculate`、`get_current_time` 是纯 CPU 的，在主线程上跑得又快又好；
     * 只有真正碰网络的那个会抛 `NetworkOnMainThreadException`。
     * 真机上就是这么发现的，而且报错完全看不出是线程问题。
     *
     * 这里用**默认调度器**而不是注入一个假的：默认值才是线上跑的那条路。
     */
    @Test
    fun `工具不在调用方线程上执行`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(delta(0, "call_a", "get_weather", "{}"))
        client.enqueueRound(ChatStreamEvent.TextDelta("答案"))

        val callerThread = Thread.currentThread().name
        var toolThread: String? = null
        val tool = StubTool(
            body = {
                toolThread = Thread.currentThread().name
                ToolResult.ok("晴 25°C")
            }
        )

        ConversationEngine(client, SimpleToolRegistry(listOf(tool)))
            .send(history(), config())
            .toList()

        assertNotNull("工具应该被执行过", toolThread)
        assertNotEquals(
            "工具不能跑在调用方线程上，否则主线程一碰网络就 NetworkOnMainThreadException",
            callerThread,
            toolThread,
        )
    }

    @Test
    fun `同一次回复里的多个工具调用都执行`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(
            delta(0, "call_a", "get_weather", "{}"),
            delta(1, "call_b", "get_time", "{}"),
        )
        client.enqueueRound(ChatStreamEvent.TextDelta("答案"))

        val tools = SimpleToolRegistry(
            listOf(
                StubTool(toolDef("get_weather"), body = { ToolResult.ok("晴") }),
                StubTool(toolDef("get_time"), body = { ToolResult.ok("12:00") }),
            )
        )
        val events = ConversationEngine(client, tools).send(history(), config()).toList()

        val finished = events.filterIsInstance<ChatEvent.ToolCallFinished>()
        assertEquals(2, finished.size)
        assertEquals(listOf("call_a", "call_b"), finished.map { it.callId })
        assertEquals("晴", finished[0].result.content)
        assertEquals("12:00", finished[1].result.content)
    }

    @Test
    fun `工具定义被传给模型`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(ChatStreamEvent.TextDelta("ok"))

        ConversationEngine(client, SimpleToolRegistry(listOf(StubTool()))).send(history(), config())
            .toList()

        val tools = client.requests[0].tools
        assertEquals(1, tools?.size)
        assertEquals("get_weather", tools?.single()?.function?.name)
    }

    @Test
    fun `没有工具时不传 tools 字段`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(ChatStreamEvent.TextDelta("ok"))

        ConversationEngine(client).send(history(), config()).toList()

        assertNull(client.requests[0].tools)
    }

    // ---------- 工具失败的四种形态（都必须回灌而不是中断） ----------

    @Test
    fun `工具不存在时把可用工具列表回灌给模型`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(delta(0, "call_x", "nonexistent", "{}"))
        client.enqueueRound(ChatStreamEvent.TextDelta("好的，我换个方式"))

        val engine = ConversationEngine(client, SimpleToolRegistry(listOf(StubTool())))
        val events = engine.send(history(), config()).toList()

        val finished = events.filterIsInstance<ChatEvent.ToolCallFinished>().single()
        assertTrue(finished.result.isError)
        assertTrue(finished.result.content.contains("没有名为 `nonexistent` 的工具"))
        assertTrue(finished.result.content.contains("get_weather"))

        // 关键：对话没有中断，模型拿到了错误并继续
        assertTrue(events.last() is ChatEvent.Completed)
    }

    @Test
    fun `工具抛异常时转成错误结果而不是中断对话`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(delta(0, "call_a", "get_weather", "{}"))
        client.enqueueRound(ChatStreamEvent.TextDelta("抱歉，查询失败了"))

        val boom = StubTool(body = { throw IllegalStateException("网络不可达") })
        val events = ConversationEngine(client, SimpleToolRegistry(listOf(boom)))
            .send(history(), config())
            .toList()

        val finished = events.filterIsInstance<ChatEvent.ToolCallFinished>().single()
        assertTrue(finished.result.isError)
        assertTrue(finished.result.content.contains("网络不可达"))
        assertTrue(events.last() is ChatEvent.Completed)
    }

    @Test
    fun `参数不是合法 JSON 时回灌错误`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(delta(0, "call_a", "get_weather", """{"city":"""))
        client.enqueueRound(ChatStreamEvent.TextDelta("我重新调用"))

        val events = ConversationEngine(client, SimpleToolRegistry(listOf(StubTool())))
            .send(history(), config())
            .toList()

        val finished = events.filterIsInstance<ChatEvent.ToolCallFinished>().single()
        assertTrue(finished.result.isError)
        assertTrue(finished.result.content.contains("不是合法的 JSON"))
    }

    @Test
    fun `工具名缺失时回灌错误`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(delta(0, "call_a", null, "{}"))
        client.enqueueRound(ChatStreamEvent.TextDelta("好的"))

        val events = ConversationEngine(client, SimpleToolRegistry(listOf(StubTool())))
            .send(history(), config())
            .toList()

        val finished = events.filterIsInstance<ChatEvent.ToolCallFinished>().single()
        assertTrue(finished.result.isError)
        assertTrue(finished.result.content.contains("没有给出函数名"))
    }

    // ---------- 审批 ----------

    @Test
    fun `需要确认的工具被拒绝时不执行`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(delta(0, "call_a", "dangerous", "{}"))
        client.enqueueRound(ChatStreamEvent.TextDelta("好的，我不执行了"))

        var executed = false
        val tool = StubTool(toolDef("dangerous"), requiresConfirmation = true) {
            executed = true
            ToolResult.ok("done")
        }
        val engine = ConversationEngine(
            client = client,
            tools = SimpleToolRegistry(listOf(tool)),
            approver = ToolApprover { _, _ -> false },
        )

        val events = engine.send(history(), config()).toList()

        assertFalse("被拒绝的工具不该真的执行", executed)
        val finished = events.filterIsInstance<ChatEvent.ToolCallFinished>().single()
        assertTrue(finished.result.isError)
        assertTrue(finished.result.content.contains("用户拒绝"))
    }

    @Test
    fun `需要确认的工具被允许时正常执行`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(delta(0, "call_a", "dangerous", "{}"))
        client.enqueueRound(ChatStreamEvent.TextDelta("完成"))

        val tool = StubTool(toolDef("dangerous"), requiresConfirmation = true) {
            ToolResult.ok("done")
        }
        val engine = ConversationEngine(
            client = client,
            tools = SimpleToolRegistry(listOf(tool)),
            approver = ToolApprover { _, _ -> true },
        )

        val events = engine.send(history(), config()).toList()

        val finished = events.filterIsInstance<ChatEvent.ToolCallFinished>().single()
        assertFalse(finished.result.isError)
        assertEquals("done", finished.result.content)
    }

    @Test
    fun `不需要确认的工具不触发审批`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(delta(0, "call_a", "get_weather", "{}"))
        client.enqueueRound(ChatStreamEvent.TextDelta("完成"))

        var asked = false
        val engine = ConversationEngine(
            client = client,
            tools = SimpleToolRegistry(listOf(StubTool())),
            approver = ToolApprover { _, _ ->
                asked = true
                true
            },
        )

        engine.send(history(), config()).toList()

        assertFalse("requiresConfirmation=false 的工具不该弹确认", asked)
    }

    // ---------- 边界 ----------

    @Test
    fun `工具循环超过上限时报错`() = runTest {
        val client = FakeChatClient()
        // 每一轮都请求工具，永远不给最终答案
        repeat(10) { round ->
            client.enqueueRound(delta(0, "call_$round", "get_weather", "{}"))
        }

        val engine = ConversationEngine(client, SimpleToolRegistry(listOf(StubTool())))
        val events = engine.send(history(), config(maxToolRounds = 3)).toList()

        val failed = events.last()
        assertTrue("期望 Failed，实际 $failed", failed is ChatEvent.Failed)
        assertTrue((failed as ChatEvent.Failed).error is ToolLoopExceededException)
        assertEquals(3, client.requests.size)
    }

    @Test
    fun `达到上限前给出答案仍然成功`() = runTest {
        val client = FakeChatClient()
        client.enqueueRound(delta(0, "call_a", "get_weather", "{}"))
        client.enqueueRound(delta(0, "call_b", "get_weather", "{}"))
        client.enqueueRound(ChatStreamEvent.TextDelta("终于好了"))

        val engine = ConversationEngine(client, SimpleToolRegistry(listOf(StubTool())))
        val events = engine.send(history(), config(maxToolRounds = 3)).toList()

        assertTrue(events.last() is ChatEvent.Completed)
    }

    @Test
    fun `网络异常被转成失败事件而不是抛出`() = runTest {
        val client = FakeChatClient()
        client.enqueueFailure(ChatApiException.Http(401, "", "HTTP 401：Invalid API key"))

        val events = ConversationEngine(client).send(history(), config()).toList()

        val failed = events.last()
        assertTrue(failed is ChatEvent.Failed)
        assertTrue((failed as ChatEvent.Failed).error is ChatApiException.Http)
    }

    @Test
    fun `某一轮失败时此前推送的内容仍然保留`() = runTest {
        val client = FakeChatClient()
        // 第一轮：模型说了一句就请求工具
        client.enqueueRound(
            ChatStreamEvent.TextDelta("我先查一下"),
            delta(0, "call_a", "get_weather", "{}"),
        )
        // 第二轮：网络断了
        client.enqueueFailure(ChatApiException.Network(IOException("连接被重置")))

        val engine = ConversationEngine(client, SimpleToolRegistry(listOf(StubTool())))
        val events = engine.send(history(), config()).toList()

        // 失败之前的文本与工具事件都还在 —— UI 不该把它们清掉，
        // 否则用户会觉得「明明刚才看到了回答，怎么突然没了」
        assertTrue(events.contains(ChatEvent.TextDelta("我先查一下")))
        assertTrue(events.any { it is ChatEvent.ToolCallFinished })
        assertTrue(events.last() is ChatEvent.Failed)
    }

    @Test
    fun `token 用量按轮推送`() = runTest {
        val client = FakeChatClient()
        // 第一轮得请求工具，才能走到第二轮
        client.enqueueRound(
            delta(0, "call_a", "get_weather", "{}"),
            ChatStreamEvent.Usage(10, 2),
        )
        client.enqueueRound(
            ChatStreamEvent.TextDelta("答案"),
            ChatStreamEvent.Usage(20, 3),
        )

        val engine = ConversationEngine(client, SimpleToolRegistry(listOf(StubTool())))
        val events = engine.send(history(), config()).toList()

        val usages = events.filterIsInstance<ChatEvent.Usage>()
        assertEquals(listOf(ChatEvent.Usage(10, 2), ChatEvent.Usage(20, 3)), usages)
    }
}
