package com.aichat.tools

import com.aichat.domain.search.ConversationSearch
import com.aichat.domain.search.MessageHit
import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注册表与工具定义的完整性。
 *
 * ## 为什么要专门测「定义长得对不对」
 *
 * 工具定义写错时的失败模式特别隐蔽：schema 里少一个字段、`required` 给了空数组、
 * 名字里有大写字母 —— **服务端不会报错**，它只会把整个 `tools` 字段忽略掉。
 * 表现是「模型永远不调工具，但聊天一切正常」。
 *
 * 这类问题在真机上一眼看不出（模型本来就有权不调工具），
 * 只能靠把「合法定义长什么样」写成断言来守。
 */
class BuiltinToolsTest {

    private val deviceInfo = object : DeviceInfoSource {
        override fun deviceModel() = "Test Device"
        override fun osVersion() = "Android 15"
        override fun batteryPercent() = 50
        override fun isCharging() = false
        override fun locale() = "zh-CN"
        override fun appVersion() = "1.0"
    }

    /** 这个类只关心「定义长得对不对」，检索行为由 `SearchHistoryToolTest` 覆盖。 */
    private val search = object : ConversationSearch {
        override suspend fun search(query: String, limit: Int): List<MessageHit> = emptyList()
    }

    /** 没配搜索后端。这是出厂状态，也是绝大多数用户的状态。 */
    private val noWebSearch = object : WebSearchSource {
        override fun configured() = false
        override suspend fun config(): WebSearchConfig? = null
    }

    /** 配好了一个 SearXNG 实例。 */
    private val webSearch = object : WebSearchSource {
        override fun configured() = true
        override suspend fun config(): WebSearchConfig? =
            WebSearchConfig(WebSearchBackend.SEARXNG, "https://searx.example.com")
    }

    private val registry: ToolRegistry = BuiltinTools.registry(deviceInfo, search, webSearch)

    private val tools: List<Tool> = BuiltinTools.all(deviceInfo, search, webSearch)

    // ---------- 注册表 ----------

    @Test
    fun `注册表非空`() {
        // 这条断言看起来废话，但它挡的是「装配漏了」这个真实事故：
        // AppContainer 曾经传的是 ToolRegistry.Empty，模型看到 0 个工具，
        // 而且没有任何报错
        assertTrue("内置工具不能为空", registry.definitions().isNotEmpty())
    }

    @Test
    fun `定义数量与工具数量一致`() {
        assertEquals(tools.size, registry.definitions().size)
    }

    @Test
    fun `按名字能找到每一个工具`() {
        tools.forEach { tool ->
            val found = registry.find(tool.definition.name)
            assertNotNull("找不到 ${tool.definition.name}", found)
            assertEquals(tool.definition.name, found!!.definition.name)
        }
    }

    @Test
    fun `找不存在的名字返回 null 而不是抛异常`() {
        assertNull(registry.find("no_such_tool"))
    }

    @Test
    fun `工具名不重复`() {
        val names = tools.map { it.definition.name }
        // 重复时 SimpleToolRegistry 会静默覆盖，等于少了一个工具
        assertEquals(names.size, names.toSet().size)
    }

    // ---------- 每个定义都得合法 ----------

    @Test
    fun `名字符合各家服务端的命名约束`() {
        tools.forEach { tool ->
            val name = tool.definition.name
            // OpenAI / DeepSeek 等都要求 ^[a-zA-Z0-9_-]{1,64}$
            assertTrue(
                "工具名「$name」不合法，只能是字母数字下划线短横",
                Regex("^[a-zA-Z0-9_-]{1,64}$").matches(name),
            )
        }
    }

    @Test
    fun `描述足够长且说清了使用场景`() {
        tools.forEach { tool ->
            val description = tool.definition.description
            assertTrue("${tool.definition.name} 的描述太短，模型不会用", description.length >= 20)
        }
    }

    @Test
    fun `参数 schema 是合法的 JSON Schema 对象`() {
        tools.forEach { tool ->
            val parameters = tool.definition.parameters
            val type = parameters["type"]
            assertNotNull("${tool.definition.name} 的 schema 缺 type", type)
            assertEquals("object", type!!.jsonPrimitive.content)

            // 无参工具也必须给一个空的 properties。
            // 缺这个字段时部分网关会直接 400，整场请求失败。
            assertNotNull(
                "${tool.definition.name} 的 schema 缺 properties",
                parameters["properties"],
            )
            assertTrue(
                "${tool.definition.name} 的 properties 必须是对象",
                parameters["properties"] is JsonObject,
            )
        }
    }

    @Test
    fun `required 要么不写要么非空`() {
        tools.forEach { tool ->
            val required = tool.definition.parameters["required"] ?: return@forEach
            // `"required": []` 会让部分网关 400，所以无必填参数时应该整个省略
            assertTrue(
                "${tool.definition.name} 的 required 是空数组，应该省略这个字段",
                required.toString().length > 2,
            )
        }
    }

    @Test
    fun `required 里的名字都在 properties 里`() {
        tools.forEach { tool ->
            val schema = tool.definition.parameters
            val properties = schema["properties"]!!.jsonObject.keys
            val required = schema["required"] ?: return@forEach
            required.toString().removeSurrounding("[", "]")
                .split(',')
                .map { it.trim().trim('"') }
                .filter { it.isNotEmpty() }
                .forEach { name ->
                    assertTrue(
                        "${tool.definition.name} 声明了必填「$name」但 properties 里没有",
                        name in properties,
                    )
                }
        }
    }

    @Test
    fun `每个属性都写了 description`() {
        tools.forEach { tool ->
            tool.definition.parameters["properties"]!!.jsonObject.forEach { (name, spec) ->
                val description = spec.jsonObject["description"]
                assertNotNull(
                    "${tool.definition.name}.$name 缺 description —— 模型靠它决定填什么",
                    description,
                )
                assertTrue(
                    "${tool.definition.name}.$name 的 description 太短",
                    (description as JsonPrimitive).content.length >= 5,
                )
            }
        }
    }

    @Test
    fun `每个属性都有 type`() {
        tools.forEach { tool ->
            tool.definition.parameters["properties"]!!.jsonObject.forEach { (name, spec) ->
                assertNotNull(
                    "${tool.definition.name}.$name 缺 type",
                    spec.jsonObject["type"],
                )
            }
        }
    }

    // ---------- 审批分类 ----------

    /**
     * 每个内置工具都必须提供面向用户的说明。
     *
     * 不提供时 `Tool.userSummary` 会回退到工具名，审批弹框里就只剩一个
     * `fetch_url` —— 用户看不懂这是在干什么，只能闭眼点「允许」，
     * 确认机制就退化成了形式。
     */
    @Test
    fun `每个内置工具都有面向用户的说明`() {
        tools.forEach { tool ->
            assertNotEquals(
                "${tool.definition.name} 没覆盖 userSummary，弹框里只会显示工具名",
                tool.definition.name,
                tool.userSummary,
            )
            assertTrue(
                "${tool.definition.name} 的 userSummary 太短：${tool.userSummary}",
                tool.userSummary.length >= 8,
            )
        }
    }

    /**
     * 面向用户的说明不能直接拿模型描述顶替。
     *
     * 两者受众不同：一个给用户做决定用，一个给模型判断何时调用用。
     * 后者里面有「不要凭记忆回答」这类对模型说的话，展示给用户很怪。
     */
    @Test
    fun `面向用户的说明与模型描述是两份不同的文本`() {
        tools.forEach { tool ->
            assertNotEquals(
                "${tool.definition.name} 直接把模型描述当用户说明了",
                tool.definition.description,
                tool.userSummary,
            )
        }
    }

    /**
     * 对外发请求的工具必须走确认。
     *
     * 这条断言守的是「新增工具时忘了想清楚风险等级」——
     * 默认值是 false，忘了写就等于默认放行。
     */
    @Test
    fun `抓取类工具需要用户确认`() {
        val fetch = tools.first { it.definition.name == FetchUrlTool.NAME }
        assertTrue(fetch.requiresConfirmation)
    }

    @Test
    fun `纯本地计算与查询类工具不需要确认`() {
        // 这些工具只读本地状态、不产生副作用，每次都弹窗反而让人关掉审批
        listOf(
            CalculateTool.NAME,
            CurrentTimeTool.NAME,
            DeviceInfoTool.NAME,
            SearchHistoryTool.NAME,
        ).forEach { name ->
            val tool = tools.first { it.definition.name == name }
            assertFalse("$name 不该要求确认", tool.requiresConfirmation)
        }
    }

    /**
     * 历史检索工具必须在装配结果里。
     *
     * 它和别的工具不一样：**它的缺失完全看不出来**。少一个 `calculate`
     * 用户很快会发现，而少一个「搜历史」只会表现为「模型好像不记得以前
     * 聊过什么」—— 用户会归因于模型能力，而不是我们的装配。
     *
     * 而 `BuiltinTools.all` 的签名要求显式传入检索实现，恰好就是防这个的；
     * 这条断言守的是更蠢的一层：有人把 listOf 里的那一行删了。
     */
    @Test
    fun `注册表里有历史检索工具`() {
        val tool = registry.find(SearchHistoryTool.NAME)
        assertNotNull("search_history 不在内置工具里，模型将无法检索历史对话", tool)
    }

    /**
     * 长期记忆关掉时，`search_history` **不该出现在工具列表里**。
     *
     * 刻意选「不注册」而不是「注册了、调用时返回『用户关掉了』」：
     * 工具定义本身就会让模型产生「我有这个能力」的预期，它可能先想好要查、
     * 再被拒，然后换个说法再试一次 —— 空结果返回 `error` 会引发反复重试，
     * 是同一个坑。
     */
    @Test
    fun `长期记忆关掉时模型看不到历史检索工具`() {
        val off = BuiltinTools.all(deviceInfo, search, webSearch, longTermMemory = false)
        val names = off.map { it.definition.name }

        assertFalse("关掉长期记忆后模型仍能看到 search_history", SearchHistoryTool.NAME in names)

        // 这一半不能省：只断言「没有 search_history」的话，
        // 一个把工具列表整个清空的 bug 也会让上面那条通过
        assertTrue(CurrentTimeTool.NAME in names)
        assertTrue(CalculateTool.NAME in names)
        assertTrue(DeviceInfoTool.NAME in names)
        assertTrue(FetchUrlTool.NAME in names)
    }

    @Test
    fun `长期记忆开着时历史检索工具在列表里`() {
        val on = BuiltinTools.all(deviceInfo, search, webSearch, longTermMemory = true)
        assertTrue(SearchHistoryTool.NAME in on.map { it.definition.name })
    }

    // ---------- 联网搜索的注册条件 ----------

    @Test
    fun `没配搜索后端时模型看不到搜索工具`() {
        val names = BuiltinTools.all(deviceInfo, search, noWebSearch).map { it.definition.name }

        assertFalse("没配后端却注册了 search_web", SearchWebTool.NAME in names)
        // 同上：不能只断言「没有」，否则「列表整个空了」也会通过
        assertTrue(FetchUrlTool.NAME in names)
    }

    @Test
    fun `配好搜索后端时模型能看到搜索工具`() {
        val names = BuiltinTools.all(deviceInfo, search, webSearch).map { it.definition.name }
        assertTrue(SearchWebTool.NAME in names)
    }

    /**
     * 搜索工具排在抓网页工具**前面**。
     *
     * 两者都是对外请求，但 `search_web` 的去向是用户定的、`fetch_url` 的去向
     * 是模型定的。模型倾向于先试排在前面的工具，所以顺序本身就是一道
     * 软性的风险护栏 —— 它先搜、拿到真实网址、再决定要不要抓，
     * 比它直接编一个地址去抓要好。
     */
    @Test
    fun `搜索工具排在抓网页工具前面`() {
        val names = BuiltinTools.all(deviceInfo, search, webSearch).map { it.definition.name }
        assertTrue(
            "search_web 应该排在 fetch_url 前面，实际顺序：$names",
            names.indexOf(SearchWebTool.NAME) < names.indexOf(FetchUrlTool.NAME),
        )
    }

    /**
     * 搜索的关键词是模型定的，但**去向是用户定的** —— 所以不需要逐次确认。
     *
     * 这条断言和 `抓取类工具需要用户确认` 是一对：两者都是对外请求，
     * 分类却不同，差别只在「谁决定目标地址」。写下来是为了防止后来的人
     * 按「对外请求一律确认」的粗规则把它改掉。
     */
    @Test
    fun `搜索工具不需要逐次确认`() {
        val tool = tools.first { it.definition.name == SearchWebTool.NAME }
        assertFalse(tool.requiresConfirmation)
    }

    @Test
    fun `注册表可以安全地重复构建`() {
        val again = BuiltinTools.registry(deviceInfo, search, webSearch)
        assertEquals(
            registry.definitions().map { it.name }.sorted(),
            again.definitions().map { it.name }.sorted(),
        )
    }
}
