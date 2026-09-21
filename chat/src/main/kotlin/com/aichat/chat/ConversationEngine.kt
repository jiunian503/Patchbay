package com.aichat.chat

import com.aichat.domain.llm.AssistantToolCall
import com.aichat.domain.llm.ChatStreamEvent
import com.aichat.domain.llm.ToolArguments
import com.aichat.domain.llm.ToolCallAccumulator
import com.aichat.domain.tool.ToolApprover
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolRegistry
import com.aichat.domain.tool.ToolResult
import com.aichat.network.ChatCompletionClient
import com.aichat.network.ChatCompletionRequest
import com.aichat.network.ToolDefinitionDto
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** 一次对话的模型参数。 */
data class ChatConfig(
    val model: String,

    /** 服务商上配的「附加提示词」。与 [injectedPrompt] 的关系见那边的注释。 */
    val systemPrompt: String? = null,

    /**
     * 角色卡注入的提示词（人设 + 世界书命中条目），由宿主在每轮开始前解析。
     *
     * ## 为什么要和 [systemPrompt] 分开
     *
     * 两者的**来源和生命周期都不同**：这个每轮重算（世界书是按当前上下文
     * 命中的，换个话题就该换一批条目），那个是服务商上的固定配置。
     * 合成一个字段的话，「这轮为什么多了一段」就查不出来了。
     *
     * 发送时按「先这个、后 [systemPrompt]」合成一条 system 消息 ——
     * 人设在前，对所有人都生效的补充要求在后。见 [systemMessage]。
     *
     * 解析放在 `:app` 而不是引擎里：`:chat` 不认识 Room，而角色卡和世界书
     * 都存在库里（`:chat` 不依赖 `:data` 的模块边界）。
     */
    val injectedPrompt: String? = null,

    val temperature: Double? = null,
    val maxTokens: Int? = null,

    /**
     * 工具循环的最大轮数。
     *
     * 8 是个经验值：正常任务 1–3 轮就够了，留 8 轮给「查天气 → 查不到 →
     * 换个城市名再查」这类需要试错的场景。再多基本就是模型卡住了。
     */
    val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
) {
    companion object {
        const val DEFAULT_MAX_TOOL_ROUNDS = 8
    }
}

/**
 * 对话核心：把「一次用户输入」变成一串 [ChatEvent]。
 *
 * ## 工具循环是什么
 *
 * 模型不能直接调工具，它只能**请求**调用。所以一次完整的对话可能是这样的：
 *
 * ```
 * 轮 0：模型说「我来查一下」+ 请求调用 get_weather(39.9, 116.4)
 *       → 我们执行工具 → 把结果作为 tool 消息追加进上下文
 * 轮 1：模型看到结果，给出「北京今天 25°C，晴」
 * ```
 *
 * 这个「执行 → 回灌 → 再问」的循环就是本类的全部价值。上限由
 * [ChatConfig.maxToolRounds] 兜底，防止模型反复调同一个工具把 token 烧光。
 *
 * ## 为什么每轮都要把 assistant 消息记回上下文
 *
 * 回灌工具结果时，上下文里必须**先有那条带 `tool_calls` 的 assistant 消息**。
 * 否则模型看到一条孤零零的 tool 结果，会认为上下文损坏而拒绝继续 ——
 * 报错信息通常是「tool_call_id 找不到对应的调用」。
 */
class ConversationEngine(
    private val client: ChatCompletionClient,
    private val tools: ToolRegistry = ToolRegistry.Empty,
    private val approver: ToolApprover = ToolApprover.AllowAll,

    /**
     * 工具在哪个调度器上执行。
     *
     * **默认绝不能是调用方的调度器。** 引擎被 `viewModelScope` 驱动，
     * 而那是 `Dispatchers.Main.immediate` —— 也就是说，不显式切走的话
     * 所有工具都跑在主线程上。
     *
     * 这个坑的可怕之处在于它**只炸一半**：`calculate`、`get_current_time`
     * 是纯 CPU 的，在主线程上跑得又快又好，看起来一切正常；
     * 只有真正碰网络/文件的那个工具会抛 `NetworkOnMainThreadException`，
     * 而报错长这样：`工具执行失败：NetworkOnMainThreadException` ——
     * 看不出是线程问题，很容易以为是网络库配错了。
     *
     * 放在引擎层而不是让每个工具自己 `withContext`：这是**宿主**该负的责任。
     * 要求每个工具（尤其是将来第三方写的插件工具）都记得切线程，
     * 等于把「会不会崩」交给插件作者的记性。
     */
    private val toolDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * 发起一次对话。
     *
     * [history] 是已有的消息（不含本次用户输入的话，需要调用方自己追加）。
     * 系统提示词由 [ChatConfig.injectedPrompt] 与 [ChatConfig.systemPrompt]
     * 合成一条，插在最前面（见 [systemMessage]）。
     *
     * Flow 正常完成时最后一个是 [ChatEvent.Completed] 或 [ChatEvent.Failed]；
     * 被取消时不发任何终止事件 —— 用户按了停止，UI 自己知道。
     */
    fun send(history: List<ChatMessage>, config: ChatConfig): Flow<ChatEvent> = flow {
        val working = mutableListOf<ChatMessage>()
        config.systemMessage()?.let { working += ChatMessage.system(it) }
        working += history

        // 跨轮累积：最终答案由多轮文本拼成，用户看到的是一条完整回复
        val allText = StringBuilder()
        val allReasoning = StringBuilder()
        val allCalls = mutableListOf<AssistantToolCall>()

        for (round in 0 until config.maxToolRounds) {
            emit(ChatEvent.RoundStarted(round))

            val accumulator = ToolCallAccumulator()
            val roundText = StringBuilder()

            try {
                client.stream(buildRequest(working, config)).collect { event ->
                    when (event) {
                        is ChatStreamEvent.TextDelta -> {
                            roundText.append(event.text)
                            allText.append(event.text)
                            emit(ChatEvent.TextDelta(event.text))
                        }

                        is ChatStreamEvent.ReasoningDelta -> {
                            allReasoning.append(event.text)
                            emit(ChatEvent.ReasoningDelta(event.text))
                        }

                        is ChatStreamEvent.ToolCallDelta -> accumulator.accept(event)

                        is ChatStreamEvent.Usage ->
                            emit(ChatEvent.Usage(event.promptTokens, event.completionTokens))

                        is ChatStreamEvent.Finished -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 统一在这里转成事件，UI 只需要处理 Failed 一种失败形态
                emit(ChatEvent.Failed(t))
                return@flow
            }

            val calls = accumulator.build()

            // 没有工具调用 = 模型给出最终答案，对话结束。
            // 用「有没有累积到 tool_calls」判断，而不是看 finish_reason ——
            // 部分服务端压根不发 finish_reason。
            if (calls.isEmpty()) {
                emit(
                    ChatEvent.Completed(
                        // 带上所有轮累积的 toolCalls —— UI 靠它渲染工具卡片，
                        // 持久化也靠它还原「这条回复用过哪些工具」
                        ChatMessage.assistant(allText.toString(), allCalls).copy(
                            reasoning = allReasoning.toString().ifBlank { null },
                        )
                    )
                )
                return@flow
            }

            // 把这条 assistant 消息（含 tool_calls）记回上下文，再追加工具结果。
            // 顺序不能反：tool 消息必须紧跟在发起它的 assistant 消息之后。
            working += ChatMessage.assistant(roundText.toString(), calls)
            allCalls += calls

            for (call in calls) {
                emit(ChatEvent.ToolCallStarted(call))
                val result = executeTool(call)
                emit(ChatEvent.ToolCallFinished(call.id, call.name, result))
                working += ChatMessage.tool(call.id, result.content)
            }
        }

        emit(ChatEvent.Failed(ToolLoopExceededException(config.maxToolRounds)))
    }

    /**
     * 执行一次工具调用。
     *
     * **这里不抛异常**（除了协程取消）—— 所有失败都变成 [ToolResult.error] 回灌给模型。
     * 让模型知道「工具不存在」「参数是坏的」「用户拒绝了」，它才有机会自救：
     * 换个工具、改参数、或者老老实实告诉用户做不到。
     * 直接抛异常打断整场对话，用户只会看到一个莫名其妙的错误。
     */
    private suspend fun executeTool(call: AssistantToolCall): ToolResult {
        if (!call.isExecutable) {
            return ToolResult.error("这次工具调用没有给出函数名，无法执行。请重新发起调用并带上函数名。")
        }

        val tool = tools.find(call.name)
            ?: return ToolResult.error(
                "没有名为 `${call.name}` 的工具。" +
                    "当前可用的工具：${tools.definitions().joinToString { it.name }.ifBlank { "（无）" }}"
            )

        val arguments = ToolArguments.parseOrNull(call.argumentsJson)
            ?: return ToolResult.error(
                "参数不是合法的 JSON 对象，无法执行。收到的原始参数：${call.argumentsJson.take(200)}"
            )

        if (tool.requiresConfirmation && !approver.approve(tool, arguments)) {
            return ToolResult.error(
                "用户拒绝执行 `${call.name}`。" +
                    "请换一种不需要该权限的方式；如果确实必须，请向用户说明为什么需要它。"
            )
        }

        return try {
            // 必须切到后台调度器。工具里干的是网络、文件、系统查询这类阻塞操作，
            // 留在主线程上会抛 NetworkOnMainThreadException（见构造函数注释）
            withContext(toolDispatcher) { tool.execute(arguments) }
        } catch (e: CancellationException) {
            // 用户取消整场对话，不是工具出错。原样抛出，
            // 否则会被下面的 catch 吞掉当成「工具失败」，然后继续跑下一轮
            throw e
        } catch (t: Throwable) {
            ToolResult.error("工具执行失败：${t.message ?: t::class.simpleName}")
        }
    }

    private fun buildRequest(
        messages: List<ChatMessage>,
        config: ChatConfig,
    ): ChatCompletionRequest {
        val definitions = tools.definitions()
        return ChatCompletionRequest(
            model = config.model,
            messages = messages.map { it.toDto() },
            tools = definitions.takeIf { it.isNotEmpty() }?.map { it.toDto() },
            temperature = config.temperature,
            maxTokens = config.maxTokens,
        )
    }
}

internal fun ToolDefinition.toDto() = ToolDefinitionDto.of(name, description, parameters)

/**
 * 把两段提示词合成**一条** system 消息。
 *
 * ## 为什么合成一条，而不是发两条 system
 *
 * OpenAI 兼容服务端对多条 `system` 的处理并不统一：有的只认第一条，
 * 有的把后面的丢掉。合成一条之后，「人设在前、服务商附加在后」的顺序
 * 完全一样，但没有这个兼容风险。
 *
 * ## 两段都空时返回 null
 *
 * 此时发出去的消息列表和加这个能力之前**逐字节相同** —— 没选角色的老会话
 * 行为不会有一丝变化。这是这次改动最重要的向后兼容保证：
 * 功能是加法，不是替换。
 */
internal fun ChatConfig.systemMessage(): String? =
    listOfNotNull(injectedPrompt, systemPrompt)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
        .ifBlank { null }
