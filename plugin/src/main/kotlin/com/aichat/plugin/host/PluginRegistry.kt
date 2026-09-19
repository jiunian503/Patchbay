package com.aichat.plugin.host

import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolRegistry
import com.aichat.plugin.manifest.ManifestProblem
import okhttp3.OkHttpClient

/**
 * 插件工具和内置工具重名了。
 *
 * 这是一个**必须让用户看见**的事件，所以它不是一个日志行、不是一个内部集合，
 * 而是一个会被插件管理页读出来展示的对象。原因见 [Reason]。
 */
data class ToolConflict(
    /** 撞车的工具名。 */
    val toolName: String,

    /** 被丢弃的那个插件。 */
    val pluginId: String,
    val pluginName: String,

    val reason: Reason,

    /** [Reason.Duplicate] 时是谁赢了。 */
    val winnerPluginId: String? = null,
) {
    enum class Reason {
        /**
         * 插件想用内置工具的名字。
         *
         * 这**不是**「命名冲突」这种小事，而是冒充：一个叫 `fetch_url` 的插件工具，
         * 在模型和用户眼里就是那个内置的抓网页工具。它可以把自己的域名写进
         * 描述里，让模型以为「抓取网页」只能走它 —— 于是每次抓取都经过它，
         * 而用户看到的确认弹窗上写的是「将访问 xxx」这种看起来很正常的话。
         *
         * 所以：**丢弃，不遮蔽，不重命名。**
         */
        ShadowsBuiltin,

        /**
         * 两个插件用了同一个工具名。
         *
         * 也丢弃而不是自动加前缀（`weather__search`）。加前缀看起来很聪明，
         * 但它会让工具名变成「宿主拼出来的东西」—— 作者在清单里写 `search`，
         * 模型看到的是 `weather__search`，两者对不上；而作者为了让模型选对工具
         * 精心写的那段 description 是围绕 `search` 这个词组织的。
         *
         * 更重要的是：**重名说明这两个插件在解决同一个问题**，用户应该知道
         * 并自己决定留哪个。悄悄把两个都塞给模型，只会看到它随机挑一个。
         */
        Duplicate,
    }

    /** 直接显示给用户的一句话。 */
    fun describe(): String = when (reason) {
        Reason.ShadowsBuiltin ->
            "插件「$pluginName」的工具 `$toolName` 和内置工具重名，已被忽略。" +
                "插件不能占用内置工具的名字 —— 那会让它看起来像系统自带的功能。"

        Reason.Duplicate ->
            "插件「$pluginName」的工具 `$toolName` 和插件「${winnerPluginId ?: "另一个插件"}」重名，" +
                "已被忽略。同一个名字只能有一个工具，否则模型无法确定该调哪个。" +
                "如果两个插件功能重复，建议只保留一个。"
    }
}

/**
 * 内置工具 + 已装插件的工具，合成一个注册表。
 *
 * ## 为什么要有这一层，而不是把插件工具塞进 `SimpleToolRegistry`
 *
 * 因为 `SimpleToolRegistry` 的规则是「重名时后者覆盖前者」—— 那对一组
 * 写死的内置工具是够用的（重名是编译期就能看出来的错误），但对插件是
 * **危险**的：它会安静地让一个插件顶掉内置工具。合成必须是一个显式的、
 * 有规则、有输出的过程。
 *
 * ## 合成规则（三条，按优先级）
 *
 * 1. **内置工具的名字是保留的。** 插件工具撞名 → 丢弃 + 记录 [ToolConflict]
 * 2. **同一个名字只属于一个插件。** 第二个撞名的 → 丢弃 + 记录
 * 3. **其余按「内置在前、插件按 id 排序」的稳定顺序排列**
 *
 * ## 为什么按 id 排序而不是按安装顺序
 *
 * 因为结果必须**只由「装了哪些插件」决定，不由「先装哪个」决定**。
 * 按安装顺序的话，同一组插件在两台设备上会得到不同的工具列表 ——
 * 那是一种极难复现的 bug，而它本来可以不存在。
 *
 * 按 id 排序还有个副作用是好的：冲突的「赢家」是确定的，于是插件管理页
 * 上那句「和插件 X 重名」在两台设备上说的是同一个插件。
 */
class PluginRegistry private constructor(
    /** 内置在前，插件在后。顺序即展示顺序，也是发给模型的顺序。 */
    val all: List<Tool>,

    /** 每个插件的装配结果（含它自己的问题列表），供插件管理页使用。 */
    val plugins: List<PluginTools>,

    /** 被丢弃的插件工具。非空时插件管理页必须显示出来。 */
    val conflicts: List<ToolConflict>,

    /**
     * 工具名 → 提供它的插件 id。
     *
     * **必须由合成过程产出，不能事后从 [plugins] 推。**
     * 从 [plugins] 推的话，被丢弃的工具（撞内置名、撞别的插件）也会算进去 ——
     * 于是 `ownerOf("fetch_url")` 会回答「某个插件」，而实际上那个工具
     * 已经不在工具列表里了。这种「界面上说它是插件提供的、模型却看不到它」
     * 的不一致，比不显示更糟。
     */
    private val owners: Map<String, String>,
) : ToolRegistry {

    /** 名字 → 工具。合成阶段已经去重，所以这里的键是唯一的。 */
    private val byName: Map<String, Tool> = all.associateBy { it.definition.name }

    override fun definitions(): List<ToolDefinition> = all.map { it.definition }

    override fun find(name: String): Tool? = byName[name]

    /** 这个工具是哪个插件提供的；内置工具返回 null。 */
    fun ownerOf(toolName: String): String? = owners[toolName]

    /** 某个插件的装配问题（不含重名，那个在 [conflicts] 里）。 */
    fun problemsOf(pluginId: String): List<ManifestProblem> =
        plugins.firstOrNull { it.pluginId == pluginId }?.problems.orEmpty()

    /** 有没有需要用户处理的事情。插件管理页据此显示小红点。 */
    val needsAttention: Boolean
        get() = conflicts.isNotEmpty() || plugins.any { it.problems.isNotEmpty() }

    companion object {

        /** 只有内置工具时的注册表。 */
        fun builtinsOnly(builtins: List<Tool>): PluginRegistry =
            PluginRegistry(
                all = builtins,
                plugins = emptyList(),
                conflicts = emptyList(),
                owners = emptyMap(),
            )

        /**
         * 合成。
         *
         * [plugins] 里同一个 id 出现多次时只取第一个 —— 那是调用方（安装记录表）
         * 的问题，但让它在这里变成一个确定的行为，比让工具列表出现两份要好。
         */
        fun build(
            builtins: List<Tool>,
            plugins: List<InstalledPlugin>,
            client: OkHttpClient,
        ): PluginRegistry {
            val assembled = plugins
                .distinctBy { it.manifest.id }
                .sortedBy { it.manifest.id }
                .map { PluginHost.tools(it, client) }

            val builtinNames = builtins.map { it.definition.name }.toSet()

            val accepted = mutableListOf<Tool>()
            val conflicts = mutableListOf<ToolConflict>()
            val owner = mutableMapOf<String, String>()

            for (set in assembled) {
                for (tool in set.tools) {
                    val name = tool.definition.name
                    when {
                        name in builtinNames -> conflicts += ToolConflict(
                            toolName = name,
                            pluginId = set.pluginId,
                            pluginName = set.pluginName,
                            reason = ToolConflict.Reason.ShadowsBuiltin,
                        )

                        name in owner -> conflicts += ToolConflict(
                            toolName = name,
                            pluginId = set.pluginId,
                            pluginName = set.pluginName,
                            reason = ToolConflict.Reason.Duplicate,
                            winnerPluginId = owner[name],
                        )

                        else -> {
                            owner[name] = set.pluginId
                            accepted += tool
                        }
                    }
                }
            }

            return PluginRegistry(
                all = builtins + accepted,
                plugins = assembled,
                conflicts = conflicts,
                owners = owner,
            )
        }
    }
}
