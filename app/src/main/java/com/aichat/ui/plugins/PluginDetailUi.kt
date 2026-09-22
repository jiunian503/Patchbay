package com.aichat.ui.plugins

import com.aichat.plugin.manifest.HttpMethod
import com.aichat.plugin.manifest.PluginManifest
import com.aichat.plugin.runtime.ToolConfirmation
import com.aichat.plugin.runtime.mcp.McpToolSnapshot

/**
 * 插件详情页里那些**会说话**的决定：空状态说什么、工具行显示哪个策略。
 *
 * ## 为什么单独一个文件
 *
 * 和 [WorkspaceUi] 同一个理由：这些函数没有一行 Android / Compose API，
 * 所以能在 JVM 上跑。把「什么情况下说什么话」钉在这里，比在真机上跑一条
 * instrumented 用例便宜得多 —— 而这两句话恰恰是**说不出口的谎话**最容易
 * 藏身的地方（见下）。
 *
 * ## 三块内容的共同点：那个值还有别的来源
 *
 * - 权限区、工具区：清单解析不了时它们是**空的**，而界面原本会说「没有」
 *   （真相是「看不出」）
 * - 工具行的确认策略：注册表里查不到时得**自己算一遍**，算错就是和实际
 *   相反的策略（见 [toolRows]）
 *
 * 三件都不是「显示什么」的问题，是「**这句话真不真**」的问题。
 *
 * ## 为什么非要区分「空」和「读不出来」
 *
 * 详情页有两块内容是从清单里读出来的：它申请了什么权限、它提供了哪些工具。
 * 清单解析不了（`PluginStatus.isBroken`）时这两样都是**空的**，而界面拿到
 * 空列表原本会说：
 *
 * - 权限区：「没有申请任何权限」
 * - 工具区：「这个插件没有提供工具（清单里 tools 是空的，或者运行形态还没实现）」
 *
 * 两句都是**反话** —— 真相是「看不出」，不是「没有」。用户此刻正需要判断
 * 「要不要继续用它」，一句编出来的「它什么都没要」会把判断带反。
 *
 * 同一页的运行形态已经会说「清单无法解析」，列表页也说「这个插件的清单
 * 现在解析不了，它提供的工具已经不可用」—— 只有这两块在说反话。
 *
 * ## 权限那一边还有个额外的坑
 *
 * [com.aichat.plugin.manifest.describe] **永远不会返回空列表**（至少有一行
 * 「网络：不访问网络」）。所以权限区拿到空列表这件事**只可能**来自
 * `manifest == null` —— 也就是清单解析不了。换句话说，修之前那句
 * 「没有申请任何权限」**从来就不是真的**：它没有第二种触发方式。
 */

/**
 * 权限区要显示的行。
 *
 * [broken] 为 true 时返回一句实话，而不是空列表 —— 理由见文件头。
 * 返回 `List` 而不是让界面多带一个布尔，是为了让「空 = 真的没申请」
 * 这个前提**只在这里被打破一次**，而不是留给每个调用点自己记着。
 */
internal fun permissionLines(broken: Boolean, permissions: List<String>): List<String> =
    if (broken) listOf("清单现在解析不了，看不出它申请了什么权限。") else permissions

/**
 * 工具区在「一个工具都没有」时说的那句话。
 *
 * 空的原因决定了用户下一步该做什么，所以必须分开说：一句笼统的
 * 「没有提供工具」会让 MCP 用户去卸载重装 —— 而真正该做的是点上面那个
 * 「连接并刷新」。
 *
 * [broken] 排在最前面：清单读不出来时 `isMcp` 本来就是 false（运行形态
 * 也读不出来），但这条参数组合万一出现，也不能让「还没拉取过」之类的
 * 说法盖过真相。
 */
internal fun emptyToolsMessage(broken: Boolean, isMcp: Boolean, hasCache: Boolean): String = when {
    broken -> "清单现在解析不了，看不出它有哪些工具。它们现在都不可用。"

    isMcp && !hasCache ->
        "还没拉取过工具清单。点上面那个按钮连一次就能看到。"

    isMcp ->
        "对端这次一个工具都没提供。可能是它在服务端把工具关掉了，" +
            "也可能是这个地址指向的不是工具端点。"

    else ->
        "这个插件没有提供工具（清单里 tools 是空的，或者运行形态还没实现）"
}

/**
 * 工具列表的两条来源。
 *
 * ## 为什么 MCP 和声明式不能共用一条
 *
 * 声明式插件的工具是**清单里写死的**，任何时候都能显示，那是用户装它的时候
 * 看到的契约。MCP 插件的工具**在对端手里**，本地只有一份会过期的快照 ——
 * 没连过的时候一个都显示不出来，而那时候界面必须说清「还没拉取」而不是
 * 「这个插件没有工具」。
 *
 * ## 为什么是 [registered] 函数，而不是 `PluginRegistry`
 *
 * 这里刻意**不**从注册表反查工具（`registry.find(name)` 只能一个一个查）：
 * 注册表里没有的插件（被停用、清单坏了）也要能显示它「本来会提供什么」。
 *
 * 而「注册表里没有」正是这个函数**最容易出错**的地方 —— 那时得自己算一遍
 * 确认策略，算错就会安静地显示一个和实际相反的值。这个错真的发生过：
 * 原来这里写的是 `spec.requiresConfirmation == true`，于是
 *
 * - 「作者没写 + POST」（真实规则：会问你）被显示成「不会打扰你」
 * - 声明了 `network: ["*"]` 的工具也一样漏
 *
 * 现在两条兜底都走 [ToolConfirmation]，和真正拦下调用的那个值**是同一份实现**。
 *
 * 收一个查表函数而不是收注册表，是为了让这条路径**能被直接测**：
 * 测试传 `{ null }` 就等价于「插件没装配」，不用造一个注册表。
 */
internal fun toolRows(
    isMcp: Boolean,
    cache: List<McpToolSnapshot>?,
    manifest: PluginManifest?,
    registered: (String) -> Boolean?,
    anyHost: Boolean,
): List<PluginToolRow> {
    if (isMcp) {
        return cache.orEmpty().map { snapshot ->
            PluginToolRow(
                name = snapshot.name,
                description = snapshot.description,
                requiresConfirmation = registered(snapshot.name)
                    // 注册表里没有（插件被停用 / 还没装配完）时按同一套规则自己算
                    ?: ToolConfirmation.mcp(anyHost = anyHost, readOnly = snapshot.readOnly),
            )
        }
    }

    return manifest?.tools.orEmpty().map { spec ->
        PluginToolRow(
            name = spec.name,
            description = spec.description,
            requiresConfirmation = registered(spec.name)
                ?: ToolConfirmation.declarative(
                    anyHost = anyHost,
                    declared = spec.requiresConfirmation,
                    // `request` 为空是校验错误，运行时那个工具压根不会装配出来
                    // （见 `PluginHost`）。这里按 `RequestSpec` 的默认值取 GET，
                    // 和「没有这个工具」这件事不矛盾
                    method = spec.request?.method ?: HttpMethod.Get,
                ),
        )
    }
}
