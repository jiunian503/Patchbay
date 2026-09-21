package com.aichat.tools

import com.aichat.domain.search.ConversationSearch
import com.aichat.domain.tool.SimpleToolRegistry
import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolRegistry

/**
 * 内置工具的装配点。
 *
 * ## 这个类是将来插件宿主的接缝
 *
 * 现在它返回一个写死的列表。将来第三方插件装进来时，改动只发生在这里：
 * 把 `SimpleToolRegistry` 换成一个「内置 + 已装插件」的合成注册表，
 * 上面的 [Tool] 接口和 [com.aichat.domain.tool.ToolDefinition] 完全不用动。
 *
 * 换句话说，**内置工具和插件工具在类型上是同一种东西** —— 这是刻意的：
 * 如果内置工具走了什么特权路径，插件生态就永远只能是二等公民。
 *
 * ## 为什么工具描述写得这么啰嗦
 *
 * `ToolDefinition.description` 直接决定模型会不会用、用对。
 * 「获取当前时间」和「获取当前日期和时间。回答『今天几号』这类问题前必须先调用它，
 * 不要凭记忆回答」的调用率差好几倍 —— 后者把**触发场景**写进去了。
 * 这不是注释，是提示词，改它等于改行为，所以也要跟着一起测。
 *
 * ## 工具需要外部能力时怎么办
 *
 * **不直接** import Android API、Room、OkHttp。需要什么就在构造函数里声明
 * 一个**窄接口**（[DeviceInfoSource]、[ConversationSearch]），由 `:app` 注入
 * 真实实现、单测注入假实现。
 *
 * 这样 `:tools` 才能保持纯 JVM：所有工具逻辑（参数怎么解析、结果怎么拼、
 * 边界怎么降级）都在秒级的 `:tools:test` 里覆盖，不用上模拟器。
 * 反过来，一旦某个工具 `import android.os.Build`，整个模块就得变成
 * Android library，**所有**工具的测试都跟着变成分钟级。
 */
object BuiltinTools {

    /**
     * 装好内置工具。
     *
     * [deviceInfo] 由 `:app` 注入 Android 实现；单测里传假实现即可。
     *
     * [search] 是历史消息检索（Hermes 三层记忆的第三层）。**刻意不给默认值**：
     * 给一个「空实现」当默认的话，忘了传的后果是模型少一个工具 ——
     * 而工具缺失在真机上完全看不出来（模型本来就有权不调工具），
     * 只会表现为「模型好像不记得以前聊过什么」。宁可编译不过。
     *
     * [webSearch] 同理不给默认值。它的「没配」是一个**正常状态**
     * （绝大多数用户不会去配搜索后端），但那个判断必须由调用方显式回答 ——
     * 默认给一个「永远返回未配置」的实现，等于允许调用方**忘记传**，
     * 而忘记传和「用户没配」在真机上长得一模一样。
     *
     * [shell] 也不给默认值，理由比上面两条更硬：忘了传的话，用户把设置里的
     * 开关打开、模型那边**仍然**看不到 `run_command` —— 而那个症状和
     * 「用户没打开开关」一模一样，同样没有任何报错。
     */
    fun registry(
        deviceInfo: DeviceInfoSource,
        search: ConversationSearch,
        webSearch: WebSearchSource,
        longTermMemory: Boolean = true,
        shell: ShellSource,
    ): ToolRegistry =
        SimpleToolRegistry(all(deviceInfo, search, webSearch, longTermMemory, shell))

    /**
     * 全部内置工具。**顺序即展示顺序，按「风险从低到高」排。**
     *
     * ## 三个「不注册」的分支
     *
     * [longTermMemory] 关掉时不注册 `search_history`；[webSearch] 没配好时
     * 不注册 `search_web`；[shell] 没打开时不注册 `run_command`。都是**不给**
     * 而不是「给了再在调用时返回拒绝」。
     *
     * 理由：工具定义本身就会让模型产生「我有这个能力」的预期，它可能先想好要查、
     * 再被拒，然后换个说法再试一次 —— 空结果返回 `error` 会引发反复重试，
     * 是同一个坑（见 `SearchHistoryTool` 的 KDoc）。
     *
     * 「没配好」的判断走的是 [WebSearchSource.configured]（同步、不查密钥）。
     * 密钥读不出来（设备恢复出厂）的情况由 `SearchWebTool` 在执行时报错兜住 ——
     * 那时工具在列表里、报错也说得清该去哪儿改，比工具凭空消失好。
     * 「开没开」走的是 [ShellSource.enabled]，同样同步、同样不碰进程。
     *
     * ## 顺序
     *
     * 越靠前的越安全，因为模型倾向于先试排在前面的工具：
     *
     * 1. 纯本地计算（时间、算术、设备信息）—— 无副作用
     * 2. `search_history` —— 只读本地库，不外发
     * 3. `search_web` —— 对外请求，但**去向是用户定的**（只决定关键词）
     * 4. `fetch_url` —— 对外请求，**去向是模型定的**
     * 5. `run_command` —— **唯一能改用户数据的**（命令由模型写、用户逐次确认）
     */
    fun all(
        deviceInfo: DeviceInfoSource,
        search: ConversationSearch,
        webSearch: WebSearchSource,
        longTermMemory: Boolean = true,
        shell: ShellSource,
    ): List<Tool> = buildList {
        add(CurrentTimeTool())
        add(CalculateTool())
        add(DeviceInfoTool(deviceInfo))
        if (longTermMemory) add(SearchHistoryTool(search))
        if (webSearch.configured()) add(SearchWebTool(webSearch))
        add(FetchUrlTool())
        // 排在最后 —— 它是唯一一个能改用户数据的工具，别的都只读
        if (shell.enabled()) add(RunCommandTool(shell))
    }
}
