package com.aichat.plugin.runtime.script

import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolResult
import com.aichat.plugin.manifest.FilesystemScope
import com.aichat.plugin.manifest.ToolSpec
import com.aichat.plugin.manifest.networkDeclaresAnyHost
import com.aichat.plugin.runtime.ToolConfirmation
import com.aichat.plugin.runtime.ToolResultText
import kotlinx.serialization.json.JsonObject

/**
 * 脚本工具：清单里的一段 `tools` 声明 + 插件自带的 JS 变成一个模型能调用的工具。
 *
 * 装配期只做两件事：把 [ToolSpec] 变成工具定义，把 [template] 备好（插件自带的
 * 源码已经在 `PluginHost` 里从清单的 `files` 取好、放进了 `template.files`）。
 * 引擎要到 [execute] 才用。
 *
 * ## 确认弹窗的默认值是 `true`（和声明式不同）
 *
 * `ToolSpec.requiresConfirmation` 是三态：`null` 表示「作者没说」，由宿主判断。
 * 声明式工具的判断依据是 **HTTP 方法**（只有 GET/HEAD 算安全方法，见
 * [ToolSpec.requiresConfirmation] 的 KDoc）—— 因为宿主能看到完整的请求。
 *
 * 脚本工具**没有这个依据**：宿主看得到它「能碰到什么」（白名单、工作区），
 * 看不到它「会做什么」。往一个白名单主机 POST 是完全可能的，而那可能改服务端数据。
 * 所以 `null` 在这里落到 `true`：**看不到就确认**。
 *
 * 作者可以写 `false` 明确担保 —— 那正是三态设计里「签字」那个位置。
 * 这个方向也是项目一贯的取向：漏写的后果应该是「多弹一次窗」，
 * 而不是「本该问的没问」。
 *
 * ⚠️ **「声明了任意主机就一律确认」那条同样适用**，而且作者那句 `false` 压不过它 ——
 * 它保护的是「地址由模型说了算」，不是「这次请求危不危险」（理由见
 * [ToolConfirmation.declarative] 的第 3 条）。
 *
 * 这一条**曾经漏过**：原来这里只写了 `spec.requiresConfirmation ?: true`，于是
 * `network: ["*"]` 的脚本插件只要作者写 `false`，模型就能把请求发去任意主机而完全
 * 不问用户。现在整条规则都走 [ToolConfirmation.script]。
 *
 * ## 一个插件的多个工具共用一个 `run`
 *
 * 清单里可以有多个 `tools`，它们都能落到同一份脚本上。[template.toolName]
 * 会把这次要调的名字传进去，脚本自己分派（`host.tool`）。这样作者不必为每个工具
 * 拆一个文件，也不必写 `if` 之外的胶水。
 */
class ScriptTool(
    private val pluginName: String,
    private val spec: ToolSpec,

    /**
     * 除了 [ScriptRequest.inputJson] 之外的字段都备好了。
     *
     * 入参是每次调用才有的，所以这里留空、在 [execute] 里 `copy` ——
     * 让「装配期确定的东西」和「调用期才有的东西」在类型上就分开。
     */
    private val template: ScriptRequest,
    private val runtime: ScriptRuntime,
) : Tool {

    override val definition = ToolDefinition(
        name = spec.name,
        description = spec.description,
        parameters = spec.parameters,
    )

    /**
     * 给用户看的一句话。
     *
     * 用户要判断的是「这个插件会碰到什么」，所以这里说的是**可达范围**
     * （哪些主机、能不能读自己的目录），不是「它会运行脚本」这种等于没说的话。
     */
    override val userSummary: String
        get() = buildString {
            append("插件「").append(pluginName).append("」将运行它自带的脚本 ")
            append(template.entryFile).append("。")

            val reach = buildList {
                if (template.network.isNotEmpty()) {
                    // 声明了 `*` 时说「任意主机」，而不是把清单里的星号原样拼进句子 ——
                    // 同一个声明在另外两种形态里说的就是「任意主机」
                    add(
                        if (networkDeclaresAnyHost(template.network)) "访问任意主机"
                        else "访问主机 " + template.network.joinToString("、"),
                    )
                }
                when (template.filesystem) {
                    FilesystemScope.None -> Unit
                    FilesystemScope.Read -> add("读它自己目录里的文件")
                    FilesystemScope.ReadWrite -> add("读写它自己目录里的文件")
                }
            }

            if (reach.isEmpty()) {
                append("它没有声明网络或文件权限，只能处理你给它的数据。")
            } else {
                append("它能").append(reach.joinToString("，也能")).append("。")
            }

            if (requiresConfirmation) {
                append("脚本内部具体做了什么宿主看不到，所以每次都要你确认。")
            }
        }

    /** 见类 KDoc：规则整体在 [ToolConfirmation.script]（包括「任意主机一律确认」）。 */
    override val requiresConfirmation: Boolean
        get() = ToolConfirmation.script(
            anyHost = networkDeclaresAnyHost(template.network),
            declared = spec.requiresConfirmation,
        )

    override suspend fun execute(arguments: JsonObject): ToolResult =
        when (val outcome = runtime.execute(template.copy(inputJson = arguments.toString()))) {
            is ScriptOutcome.Ok -> ToolResult.ok(ToolResultText.clip(outcome.json))

            // 失败原因由沙箱写成一句给人看的话。这里**不加工** ——
            // 「超时别重试」和「脚本报错可以改参数重试」的区别必须原样传下去，
            // 而这个类没有能力判断哪句该怎么说。
            is ScriptOutcome.Failed -> ToolResult.error(outcome.message)
        }
}
