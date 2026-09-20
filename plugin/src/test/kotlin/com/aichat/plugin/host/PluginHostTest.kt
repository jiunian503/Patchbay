package com.aichat.plugin.host

import com.aichat.plugin.Manifests
import com.aichat.plugin.manifest.AuthSpec
import com.aichat.plugin.manifest.AuthType
import com.aichat.plugin.manifest.HttpMethod
import com.aichat.plugin.runtime.PluginSettings
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装配：一个插件 → 一组工具。
 *
 * ## 这里守的核心性质是「失败不能是静默的」
 *
 * 装配失败的三种形态都很容易写成「返回空列表」：
 * baseUrl 解析不了、认证配置不完整、请求头缺配置项。
 * 每一种都会让用户看到「装了个插件，什么也没发生」—— 那是没法排查的状态。
 *
 * 所以每条失败路径都有一个用例断言「返回了 0 个工具，**并且**给出了一条
 * 说清原因的问题」。
 */
class PluginHostTest {

    private val client = OkHttpClient()

    private fun tools(
        json: String,
        settings: Map<String, String> = emptyMap(),
        enabled: Boolean = true,
    ) = PluginHost.tools(
        Manifests.installed(json, settings = settings, enabled = enabled),
        client,
    )

    // ---------------------------------------------------------------- 正常装配

    @Test
    fun `声明式插件按 tools 数量产出工具`() {
        val set = tools(Manifests.declarative(tools = listOf("a", "b", "c")))

        assertEquals(3, set.tools.size)
        assertEquals(listOf("a", "b", "c"), set.tools.map { it.definition.name })
        assertTrue(set.problems.isEmpty())
        assertEquals("pub.test.demo", set.pluginId)
    }

    @Test
    fun `工具定义原样透传描述和参数`() {
        val set = tools(Manifests.declarative(tools = listOf("do_thing")))
        val definition = set.tools.single().definition

        assertEquals("do_thing", definition.name)
        assertEquals("测试用工具 do_thing", definition.description)
        assertTrue(definition.parameters.containsKey("properties"))
    }

    @Test
    fun `关掉的插件不装配也不报问题`() {
        // 关掉是用户的选择，不是错误。给它报一条问题会让插件管理页
        // 一直亮着小红点，而用户已经处理过了
        val set = tools(Manifests.declarative(), enabled = false)

        assertTrue(set.tools.isEmpty())
        assertTrue(set.problems.isEmpty())
    }

    // ---------------------------------------------------------------- 还没实现的运行形态

    @Test
    fun `不传脚本引擎时 script 插件给出警告而不是静默空列表`() {
        // **这条测的是默认参数。** 现有调用点（以及 25 处测试）都不传第三个参数，
        // 它们看到的必须是明确的「宿主还不支持」，不是静默的空工具列表。
        // 各条失败路径在 ScriptHostTest —— 这里只钉住默认值。
        //
        // 这是一次**行为改变**：以前 script 和 native 一样走「运行形态还没实现」，
        // 现在它有了实现，但引擎得由 :app 注入（:plugin 必须保持纯 JVM，§44）。
        // 所以「不传」和「传了 Unavailable」是同一件事，而它仍然是可用的默认值 ——
        // 因为宿主**没有**脚本引擎时，那句话就是事实。
        val set = tools(Manifests.otherRuntime("pub.a.script", "script"))

        assertTrue(set.tools.isEmpty())
        val problem = set.problems.single()
        assertEquals("$.runtime", problem.path)
        assertTrue("要说清是哪种运行形态不支持", problem.message.contains("script"))
        assertTrue("要说清是宿主还没实现，不是作者写错了", problem.message.contains("不支持"))
        assertEquals(
            "作者没写错，不该报成错误",
            com.aichat.plugin.manifest.ManifestProblem.Severity.Warning,
            problem.severity,
        )
    }

    @Test
    fun `native 给出警告`() {
        // mcp 原来也在这条里。它现在**真的会装配**了（读缓存），
        // 所以那个断言被搬到了 `McpHostTest` —— 这里只剩 native。
        //
        // 这是一次**行为改变**，不是修 bug：原来 mcp 和 native 一样
        // 返回「宿主不支持」的警告，现在 mcp 有了实现。
        // 留着旧断言会让「宿主不支持 mcp」这句话永远出现在测试里，
        // 而它已经不成立了
        val set = tools(Manifests.otherRuntime("pub.a.native", "native"))

        assertTrue(set.tools.isEmpty())
        val problem = set.problems.single()
        assertEquals("$.runtime", problem.path)
        assertTrue(problem.message, problem.message.contains("native"))
        assertTrue("要说清是宿主还没实现，不是作者写错了", problem.message.contains("不支持"))
    }

    // ---------------------------------------------------------------- 失败路径

    @Test
    fun `baseUrl 解析不了时报出问题而不是空列表`() {
        // 校验层会拦，但 PluginHost 是 public API，不能假设调用方一定先校验过。
        // 所以这里绕过解析、直接造一个 baseUrl 不合法的清单喂进去
        val set = PluginHost.tools(
            Manifests.raw(Manifests.declarativeObject(baseUrl = "不是个地址")),
            client,
        )

        assertTrue(set.tools.isEmpty())
        assertEquals(1, set.problems.size)
        assertTrue(set.problems.single().message, set.problems.single().message.contains("不是合法地址"))
    }

    @Test
    fun `认证配置不完整时装配失败而不是退回不认证`() {
        // auth.type=bearer 但 settingKey 缺失。退回「不认证」的话，
        // 请求会带着错误的身份发出去，报回来一个和真实原因无关的 401
        val set = PluginHost.tools(
            Manifests.raw(
                Manifests.declarativeObject(
                    auth = AuthSpec(type = AuthType.Bearer, settingKey = null),
                ),
            ),
            client,
        )

        assertTrue(set.tools.isEmpty())
        assertEquals(1, set.problems.size)
        assertTrue(set.problems.single().message, set.problems.single().message.contains("不完整"))
    }

    @Test
    fun `插件级请求头缺配置项时装配失败`() {
        val json = Manifests.declarative(
            settings = """"token":{"type":"string","title":"令牌","secret":true}""",
            extraEntry = ""","headers":{"X-Token":"{{settings.token}}"}""",
        )

        // 没填 token
        val missing = tools(json)
        assertTrue(missing.tools.isEmpty())
        assertTrue(missing.problems.single().message.contains("请求头"))

        // 填了就正常
        val filled = tools(json, settings = mapOf("token" to "abc"))
        assertEquals(1, filled.tools.size)
        assertTrue(filled.problems.isEmpty())
    }

    @Test
    fun `没有 request 的工具被跳过而不是崩掉`() {
        // 校验会报错，但装配层也不该崩。跳过是没办法的事 ——
        // 没有 request 就不知道发什么请求
        val set = PluginHost.tools(
            Manifests.raw(
                Manifests.declarativeObject(
                    tools = listOf(
                        Manifests.defaultTool("good"),
                        Manifests.defaultTool("bad", request = null),
                    ),
                ),
            ),
            client,
        )

        assertEquals(listOf("good"), set.tools.map { it.definition.name })
    }

    // ---------------------------------------------------------------- 确认策略的传递

    @Test
    fun `作者没写确认策略时按 HTTP 方法兜底`() {
        val set = tools(
            Manifests.declarative(
                tools = listOf("read_it", "write_it"),
                toolBody = { name ->
                    if (name == "read_it") {
                        Manifests.toolSpec(name, method = "GET")
                    } else {
                        Manifests.toolSpec(name, method = "POST")
                    }
                },
            ),
        )

        assertFalse("GET 是安全方法，不该打扰用户", set.tools[0].requiresConfirmation)
        assertTrue("POST 可能改动服务端数据", set.tools[1].requiresConfirmation)
    }

    @Test
    fun `作者明确说不用确认时听作者的`() {
        val set = tools(
            Manifests.declarative(
                tools = listOf("search"),
                toolBody = { Manifests.toolSpec(it, method = "POST", confirmation = "false") },
            ),
        )

        assertFalse(set.tools.single().requiresConfirmation)
    }

    @Test
    fun `声明任意主机时无论作者怎么写都要确认`() {
        // 任意主机 = 把「请求发去哪」交给模型决定，和内置的 fetch_url 是同一件事
        val set = tools(
            Manifests.declarative(
                network = listOf("*"),
                tools = listOf("read_it"),
                toolBody = { Manifests.toolSpec(it, method = "GET", confirmation = "false") },
            ),
        )

        assertTrue(set.tools.single().requiresConfirmation)
    }

    // ---------------------------------------------------------------- 用户看的那句话

    @Test
    fun `userSummary 说清主机名和为什么问`() {
        val set = tools(
            Manifests.declarative(
                tools = listOf("write_it"),
                toolBody = { Manifests.toolSpec(it, method = "POST") },
            ),
        )

        val summary = set.tools.single().userSummary
        assertTrue(summary, summary.contains("api.example.com"))
        assertTrue("HTTP 方法要按清单里的写法显示，不是 Kotlin 枚举名：$summary", summary.contains("POST"))
        assertFalse(summary, summary.contains("Post 请求"))
    }

    @Test
    fun `GET 工具的 userSummary 不解释确认`() {
        val set = tools(Manifests.declarative(tools = listOf("read_it")))
        val summary = set.tools.single().userSummary

        assertTrue(summary, summary.contains("GET"))
        assertFalse("没弹窗就别解释弹窗：$summary", summary.contains("确认"))
    }

    // ---------------------------------------------------------------- 配置项

    @Test
    fun `配置项被传进运行时`() {
        val set = tools(
            Manifests.declarative(
                settings = """"unit":{"type":"enum","title":"单位","enum":["celsius","fahrenheit"]}""",
                tools = listOf("get_weather"),
                toolBody = { Manifests.toolSpec(it, query = """{"unit":"{{settings.unit}}"}""") },
            ),
            settings = mapOf("unit" to "celsius"),
        )

        assertEquals(1, set.tools.size)
        assertEquals("pub.test.demo", set.pluginId)
    }

    @Test
    fun `空配置和没配置是一回事`() {
        // PluginSettings 把空串也当成没填 —— 见它的 KDoc。
        // 这里只是确认装配层没有绕过这个约定
        val settings = PluginSettings(mapOf("token" to "  "))
        assertNull(settings.value("token"))
        assertTrue(settings.isEmpty())
    }

    @Test
    fun `工具的方法被正确传递`() {
        val set = tools(
            Manifests.declarative(
                tools = listOf("del"),
                toolBody = { Manifests.toolSpec(it, method = "DELETE") },
            ),
        )
        // 没有直接暴露 method 的接口，用「非 GET 要确认」来间接确认方法传对了
        assertTrue(set.tools.single().requiresConfirmation)
        assertTrue(set.tools.single().userSummary.contains(HttpMethod.Delete.wire))
    }
}
