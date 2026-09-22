package com.aichat.plugin.runtime

import com.aichat.plugin.manifest.HttpMethod

/**
 * 「这个工具调用前要不要弹窗问你」—— **唯一一处**规则。
 *
 * ## 为什么必须只有一处
 *
 * 这条规则有**四个**地方要用：
 *
 * 1. [DeclarativeTool.requiresConfirmation] —— 真正拦下调用
 * 2. `McpTool.requiresConfirmation` —— 同上
 * 3. `ScriptTool.requiresConfirmation` —— 同上
 * 4. 插件详情页那句「调用前会问你 / 调用前不会打扰你」—— 用户照着它决定放不放行
 *
 * ⚠️ 第 3 项是**后来补上的**：脚本工具原来自己写了一遍 `spec.requiresConfirmation ?: true`，
 * 少了「声明了任意主机就一律确认」那一条 —— 于是 `network: ["*"]` 的脚本插件只要作者写
 * `false`，模型就能把请求发去任意主机而完全不问用户。这和第 20 行记的那次事故是同一个
 * 病根（把规则抄了一遍），只是漏掉的分支不同。
 *
 * 前三个写错的话工具根本跑不起来，很快就会有人发现。**第四个写错了没人会发现**：
 * 界面会安静地显示一个和实际相反的策略，而它恰恰是用户唯一能看到的地方 ——
 * 那句文案的注释里还写着「显示**实际生效**的策略，不是清单里写的那个值」。
 *
 * 实测就是这么错的：详情页自己写了一遍 `spec.requiresConfirmation == true`，
 * 于是
 *
 * - 「作者没写 + POST」（真实规则：确认）被显示成「不会打扰你」
 * - 声明了 `network: ["*"]` 的工具也一样漏（真实规则：一律确认）
 *
 * 两个方向都错在**同一个原因**上：把规则抄了一遍。
 *
 * ## 为什么是两个函数，而不是一个带布尔参数的
 *
 * 声明式看的是 **HTTP 方法**（RFC 9110 的「安全方法」），MCP 看的是**对端声明的
 * `readOnlyHint`** —— 两个依据的来源和可信度都不同（见 [mcp] 的注释）。
 * 合成一个函数只能靠一个布尔参数区分，那比两个函数更难用对。
 */
object ToolConfirmation {

    /**
     * 声明式工具。规则见 `ToolSpec.requiresConfirmation` 的 KDoc：
     *
     * 1. 作者写了值 → 听作者的
     * 2. 作者没写 → 按 HTTP 方法兜底，**只有 GET 免确认**
     * 3. **无论作者怎么写，声明了任意主机就一律确认** ——
     *    那等于把「请求发去哪」交给模型决定，和内置的 `fetch_url` 是同一件事，
     *    而 `fetch_url` 是要确认的
     *
     * 第 3 条压过前两条：它保护的是「地址由模型说了算」，而不是「这次请求危不危险」，
     * 所以作者那句 `false` 担保不了它。
     */
    fun declarative(anyHost: Boolean, declared: Boolean?, method: HttpMethod): Boolean =
        anyHost || (declared ?: (method != HttpMethod.Get))

    /**
     * MCP 工具。`readOnlyHint` 是每次连接时从线上下来的，对端随时可以改、
     * 用户看不到改动 —— 它比清单里那句 `requiresConfirmation`
     * （用户装的时候看过并授权）弱得多。
     *
     * 所以它只能用来**免掉**确认，不能在「请求发去哪由模型决定」的场景下免掉确认。
     * 那正是 `network: ["*"]` 的意思。
     */
    fun mcp(anyHost: Boolean, readOnly: Boolean): Boolean = anyHost || !readOnly

    /**
     * 脚本工具。**没有 HTTP 方法可依** —— 宿主看得到脚本「能碰到什么」
     * （白名单、工作区），看不到它「会做什么」，往一个白名单主机 POST 是完全可能的。
     * 所以 `null` 在这里落到 `true`：**看不到就确认**。
     *
     * 作者仍然可以写 `false` 明确担保（三态设计里「签字」那个位置）——
     * 但 `anyHost` 压过它，理由和 [declarative] 的第 3 条一样：
     * 声明了任意主机，等于把「请求发去哪」交给模型决定。
     */
    fun script(anyHost: Boolean, declared: Boolean?): Boolean = anyHost || (declared ?: true)
}
