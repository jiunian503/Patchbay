package com.aichat.domain.tool

import kotlinx.serialization.json.JsonObject

/**
 * 暴露给模型的工具定义。
 *
 * [parameters] 是 JSON Schema 片段，会被原样透传给模型 —— 它直接决定模型
 * 填不填得对参数，写得含糊就会看到模型把城市名塞进 `latitude` 这种字段。
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * 工具执行结果。
 *
 * [isError] 为 true 时，内容会被当作**错误信息**回灌给模型 ——
 * 这不是「调用失败就中断」，而是让模型知道发生了什么并自行调整
 * （换参数重试、换工具、或者直接告诉用户做不到）。
 * 所以错误内容要写成人能看懂的话，模型读的是它。
 */
data class ToolResult(
    val content: String,
    val isError: Boolean = false,
) {
    companion object {
        fun ok(content: String) = ToolResult(content, isError = false)

        fun error(message: String) = ToolResult(message, isError = true)
    }
}

/**
 * 一个可被模型调用的工具。
 *
 * 实现方（将来的插件宿主）可以直接抛异常 —— 引擎会捕获并转成
 * [ToolResult.error] 回灌，不需要自己包 try/catch。
 */
interface Tool {

    val definition: ToolDefinition

    /**
     * 给**用户**看的一句话说明，用在「允许执行这个工具吗」的弹框里。
     *
     * ## 为什么不直接复用 [definition] 的 description
     *
     * 那个是写给模型的提示词，里面是「回答『今天几号』前必须先调用它，
     * 不要凭记忆回答」这种**对模型说的话**。原样展示给用户会很怪，
     * 而且它往往很长，把弹框撑满之后用户反而不看参数了 ——
     * 而参数才是他真正需要判断的东西。
     *
     * 默认值回退到工具名，而不是回退到 description：宁可只显示一个名字，
     * 也不要弹出一段明显不是给用户看的文字。内置工具都覆盖了它，
     * `BuiltinToolsTest` 会守住这一点。
     */
    val userSummary: String get() = definition.name

    /**
     * 调用前是否需要用户确认。
     *
     * 写操作、花钱、对外发消息一律返回 true。默认 false 是给纯查询类工具
     * 用的（查天气、算统计），那些确认一次反而烦人。
     */
    val requiresConfirmation: Boolean get() = false

    /**
     * 执行一次调用。
     *
     * ## 执行线程
     *
     * 引擎会在**后台调度器**上调用它，所以实现里可以直接做阻塞操作
     * （网络请求、读文件、查系统信息），不需要自己 `withContext`。
     *
     * 这条保证由宿主（[ConversationEngine]）提供，不在这个接口里 ——
     * 因为接口没法强制线程，而「每个工具都记得切线程」这件事一定会有人忘。
     */
    suspend fun execute(arguments: JsonObject): ToolResult
}

/** 工具集合。按名字查找必须是 O(1) —— 每轮都要按模型给的名字找工具。 */
interface ToolRegistry {

    fun definitions(): List<ToolDefinition>

    fun find(name: String): Tool?

    companion object {
        val Empty: ToolRegistry = object : ToolRegistry {
            override fun definitions(): List<ToolDefinition> = emptyList()
            override fun find(name: String): Tool? = null
        }
    }
}

/** 默认实现。名字重复时后者覆盖前者 —— 安装插件时应该更早拦住重名。 */
class SimpleToolRegistry(tools: List<Tool>) : ToolRegistry {

    private val byName = tools.associateBy { it.definition.name }

    override fun definitions(): List<ToolDefinition> = byName.values.map { it.definition }

    override fun find(name: String): Tool? = byName[name]
}

/**
 * 工具执行前的审批。
 *
 * 做成 `fun interface` 是为了让调用方（UI 层）能直接用 lambda 接一个弹窗，
 * 而引擎不需要知道弹窗长什么样。
 */
fun interface ToolApprover {

    suspend fun approve(tool: Tool, arguments: JsonObject): Boolean

    companion object {
        val AllowAll = ToolApprover { _, _ -> true }
    }
}
