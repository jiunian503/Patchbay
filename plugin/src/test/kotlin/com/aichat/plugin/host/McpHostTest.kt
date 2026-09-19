package com.aichat.plugin.host

import com.aichat.plugin.Manifests
import com.aichat.plugin.manifest.AuthSpec
import com.aichat.plugin.manifest.AuthType
import com.aichat.plugin.manifest.ManifestProblem
import com.aichat.plugin.manifest.PluginManifest
import com.aichat.plugin.manifest.PluginRuntimeKind
import com.aichat.plugin.runtime.PluginSettings
import com.aichat.plugin.runtime.mcp.McpCacheCodec
import com.aichat.plugin.runtime.mcp.McpEra
import com.aichat.plugin.runtime.mcp.toDescriptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MCP 插件的装配与连接。
 *
 * ## 这个文件存在的理由：**同步装配 + 联网拉取**这两件事的接缝
 *
 * `PluginHost.tools()` 必须在装配路径上保持同步（它在主线程那条路上被调），
 * 而 MCP 的工具清单只能联网拿到。所以这里有一条硬要求：
 * `connect()` 算出来的指纹，`tools()` 必须算出一模一样的一个。
 *
 * 算得不一样的话，症状是「点连接显示成功、工具列表还是空的」——
 * 而两边的代码各自看起来都对。
 *
 * ## 所以凡是需要「一份能用的缓存」的用例，都必须走 [cached]
 *
 * [cached] 拿的是 `PluginHost.connect()` 的**真实返回值**。
 * 手写一个指纹（`Manifests.mcpCache(fingerprint = ...)`）只在**反例**里用
 * ——「指纹对不上就该忽略」。正例里手写指纹等于把被测算法抄一遍，
 * 两边一起改错也能通过。
 */
class McpHostTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    /** 请求实际发出去时所在的线程名。用来验「不在调用方线程上」这条契约。 */
    private val requestThreads = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestThreads += Thread.currentThread().name
                chain.proceed(chain.request())
            }
            .build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ------------------------------------------------------------------ 夹具

    private fun json(body: String) = MockResponse(
        code = 200,
        headers = headersOf("Content-Type", "application/json"),
        body = body,
    )

    private fun toolsResult(vararg tools: String) =
        """{"jsonrpc":"2.0","id":1,"result":{"tools":[${tools.joinToString(",")}]}}"""

    private fun tool(name: String, readOnly: Boolean = false) =
        """{"name":"$name","description":"对端工具 $name",""" +
            """"inputSchema":{"type":"object","properties":{"q":{"type":"string"}}},""" +
            """"annotations":{"readOnlyHint":$readOnly}}"""

    private fun callResult(text: String) =
        """{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"$text"}]}}"""

    private fun legacyInitialize() =
        json("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25"}}""")

    /** 请求体里的 `method`。`Mcp-Method` 头是从它镜像来的，所以要看真身。 */
    private fun RecordedRequest.methodName(): String =
        Json.parseToJsonElement(body?.utf8().orEmpty())
            .jsonObject["method"]!!.jsonPrimitive.content

    private fun mcpJson(
        transport: String = "http",
        whitelist: List<String> = emptyList(),
        settings: String = "",
        auth: String = "",
        headers: String = "",
        network: List<String>? = null,
    ) = Manifests.mcp(
        transport = transport,
        url = server.url("/mcp").toString(),
        // 白名单要放行 MockWebServer 的主机名，否则连不上 —— 而且那正是
        // `NetworkGuard` 该做的事，不是 bug
        network = network ?: listOf(server.url("/").host),
        whitelist = whitelist,
        settings = settings,
        auth = auth,
        headers = headers,
    )

    private fun plugin(
        transport: String = "http",
        whitelist: List<String> = emptyList(),
        settings: Map<String, String> = emptyMap(),
        settingsJson: String = "",
        auth: String = "",
        headers: String = "",
        enabled: Boolean = true,
    ) = Manifests.installed(
        mcpJson(
            transport = transport,
            whitelist = whitelist,
            settings = settingsJson,
            auth = auth,
            headers = headers,
        ),
        settings = settings,
        enabled = enabled,
    )

    /**
     * 走一次真实连接，拿到一份**指纹一定对得上**的缓存。
     *
     * 这一步不能省（见类注释）。[prime] 用来先把对端的回答排好队。
     */
    private suspend fun cached(p: InstalledPlugin, prime: () -> Unit): InstalledPlugin {
        prime()
        val refresh = PluginHost.connect(p, client)
        assertTrue("连接没成功，后面的断言就没有意义了：" + refresh.message, refresh.ok)
        return p.copy(mcpCache = refresh.cache)
    }

    private fun bearer(key: String = "token") = """{"type":"bearer","settingKey":"$key"}"""

    private fun secretSetting(key: String = "token") =
        """"$key":{"type":"string","title":"令牌","secret":true}"""

    // ------------------------------------------------------------------ 装配：只用缓存

    @Test
    fun `没有缓存时说清是还没拉取过 而不是静默空列表`() {
        val set = PluginHost.tools(plugin(), client)

        assertTrue(set.tools.isEmpty())
        val problem = set.problems.single()
        assertEquals("$.entry.mcp", problem.path)
        assertEquals(ManifestProblem.Severity.Warning, problem.severity)
        // 光说「没有工具」没用，要说清下一步点什么
        assertTrue(problem.message, problem.message.contains("连接并刷新"))
    }

    @Test
    fun `关掉的插件不装配也不报问题`() {
        // 关掉是用户的选择，不是错误。报一条问题会让插件管理页
        // 一直亮着小红点，而用户已经处理过了
        val set = PluginHost.tools(plugin(enabled = false), client)

        assertTrue(set.tools.isEmpty())
        assertTrue(set.problems.isEmpty())
    }

    @Test
    fun `指纹对不上时忽略缓存`() {
        // 用户把地址从 A 服务改成 B 服务，而缓存里还是 A 的工具。
        // 凑合用的症状是：界面上挂着一串 B 服务根本没有的工具名。
        //
        // 这条是**反例**，所以指纹故意给一个假的
        val fake = Manifests.mcpCache(tools = listOf(Manifests.snapshot("old_tool")))

        val set = PluginHost.tools(plugin().copy(mcpCache = fake), client)

        assertTrue("指纹是别的配置的，不该被采纳", set.tools.isEmpty())
        assertEquals("$.entry.mcp", set.problems.single().path)
    }

    @Test
    fun `stdio 给出的是用户能照着做的话`() {
        val set = PluginHost.tools(plugin(transport = "stdio"), client)

        assertTrue(set.tools.isEmpty())
        val problem = set.problems.single()
        assertEquals("$.entry.mcp.transport", problem.path)
        assertTrue(problem.message, problem.message.contains("Android"))
        // 「不支持」三个字没有用，要说清去找作者要一个远程地址
        assertTrue(problem.message, problem.message.contains("http"))
        assertEquals(ManifestProblem.Severity.Warning, problem.severity)
    }

    @Test
    fun `清单里的工具白名单只放行点名的那些`() = runBlocking {
        val p = cached(plugin(whitelist = listOf("alpha", "gamma"))) {
            server.enqueue(json(toolsResult(tool("alpha"), tool("beta"), tool("gamma"))))
        }

        val set = PluginHost.tools(p, client)

        assertEquals(listOf("alpha", "gamma"), set.tools.map { it.definition.name })
        assertTrue("点名都在，不该有警告：" + set.problems, set.problems.isEmpty())
    }

    @Test
    fun `白名单写了不存在的名字要说出来`() = runBlocking {
        // 它的效果是「什么都没发生」：作者以为限定了，实际那个名字没匹配上，
        // 而界面上看不出区别。对端提供哪些工具是运行时才知道的，
        // 所以清单校验阶段发现不了 —— 这里是最早能发现它的地方
        val p = cached(plugin(whitelist = listOf("alpha", "typo_tool"))) {
            server.enqueue(json(toolsResult(tool("alpha"), tool("beta"))))
        }

        val set = PluginHost.tools(p, client)

        assertEquals(listOf("alpha"), set.tools.map { it.definition.name })
        val problem = set.problems.single()
        assertEquals("$.entry.mcp.tools", problem.path)
        assertTrue(problem.message, problem.message.contains("typo_tool"))
        // 报错信息里要给出对端真实提供了什么，否则用户没法对照着改
        assertTrue(problem.message, problem.message.contains("beta"))
    }

    @Test
    fun `对端报了没名字的工具时解释一句`() = runBlocking {
        // 「服务端文档说有 3 个、App 里只有 2 个」这种不一致，
        // 光看工具列表是查不出来的
        val p = cached(plugin()) {
            server.enqueue(
                json(toolsResult(tool("alpha"), """{"description":"没有名字"}""", tool("beta"))),
            )
        }

        val set = PluginHost.tools(p, client)

        assertEquals(2, set.tools.size)
        val problem = set.problems.single()
        assertTrue(problem.message, problem.message.contains("3"))
        assertTrue(problem.message, problem.message.contains("1"))
    }

    @Test
    fun `密钥没填时工具照常装配 只是给一条警告`() = runBlocking {
        // 和声明式一致：把工具拿掉的话，用户填好密钥之前连
        // 「这个插件提供什么」都看不到。
        //
        // 顺带证明了**指纹里不含密钥**：缓存是带着 token 拉下来的，
        // 装配时 token 没了，指纹照样对得上
        val withKey = plugin(
            settingsJson = secretSetting(),
            settings = mapOf("token" to "s3cret"),
            auth = bearer(),
        )
        val p = cached(withKey) { server.enqueue(json(toolsResult(tool("alpha")))) }

        val set = PluginHost.tools(p.copy(settings = PluginSettings.Empty), client)

        assertEquals(listOf("alpha"), set.tools.map { it.definition.name })
        val problem = set.problems.single()
        assertEquals("$.entry.mcp.auth", problem.path)
        assertEquals(ManifestProblem.Severity.Warning, problem.severity)
        assertTrue("要说清缺的是哪一项", problem.message.contains("token"))
    }

    @Test
    fun `认证配置不完整时装配失败而不是退回不认证`() {
        // 退回「不认证」的话，请求会带着错误的身份发出去，
        // 报回来一个和真实原因无关的 401
        val p = Manifests.raw(
            Manifests.mcpObject(
                url = server.url("/mcp").toString(),
                auth = AuthSpec(type = AuthType.Bearer),
            ),
        )

        val set = PluginHost.tools(p, client)

        assertTrue(set.tools.isEmpty())
        assertEquals("$.entry.mcp.auth", set.problems.single().path)
        assertTrue(set.problems.single().message.contains("401"))
    }

    @Test
    fun `MCP 的认证不能走查询参数`() {
        val p = Manifests.raw(
            Manifests.mcpObject(
                url = server.url("/mcp").toString(),
                auth = AuthSpec(type = AuthType.Query, settingKey = "token", queryName = "key"),
            ),
        )

        val set = PluginHost.tools(p, client)

        assertTrue(set.tools.isEmpty())
        assertEquals("$.entry.mcp.auth.type", set.problems.single().path)
    }

    @Test
    fun `mcp 段缺失时装配报出来`() {
        val p = Manifests.raw(
            PluginManifest(
                id = "pub.a.broken",
                name = "缺 mcp 段",
                version = "1.0.0",
                runtime = PluginRuntimeKind.Mcp,
            ),
        )

        val set = PluginHost.tools(p, client)

        assertTrue(set.tools.isEmpty())
        assertEquals("$.entry.mcp", set.problems.single().path)
        assertTrue(set.problems.single().message.contains("没有 mcp 段"))
    }

    // ------------------------------------------------------------------ 连接：真的联网

    @Test
    fun `连接成功后装配就能看到工具`() = runBlocking {
        // **这个文件里最值钱的一条。** 它拿 connect() 的真实返回值去装配，
        // 所以两边算出来的指纹必须一模一样
        server.enqueue(json(toolsResult(tool("alpha", readOnly = true), tool("beta"))))
        val p = plugin()
        assertTrue("先确认装配前确实是空的", PluginHost.tools(p, client).tools.isEmpty())

        val refresh = PluginHost.connect(p, client)

        assertTrue(refresh.problems.toString(), refresh.ok)
        assertEquals("对端协议版本要记下来", "2026-07-28", refresh.cache!!.protocolVersion)
        assertEquals(McpEra.Modern, refresh.cache.era)

        val after = PluginHost.tools(p.copy(mcpCache = refresh.cache), client)

        assertEquals(listOf("alpha", "beta"), after.tools.map { it.definition.name })
        assertTrue("指纹对上了就不该再有警告：" + after.problems, after.problems.isEmpty())
        assertEquals("对端工具的描述要原样透传", "对端工具 alpha", after.tools.first().definition.description)
    }

    @Test
    fun `连接的请求不在调用方线程上执行`() = runBlocking {
        // `connect` 是挂起的，而调用方是 `viewModelScope.launch`（主线程）。
        // 自己切调度器这件事不能省 —— 不切的话真机上会
        // NetworkOnMainThreadException 闪退，而**纯 JVM 单测测不出来**
        // （那是 Android 运行时的检查）。能写的最接近的断言就是这个：
        // 请求不在调用方线程上发出
        server.enqueue(json(toolsResult(tool("alpha"))))
        val caller = Thread.currentThread().name

        PluginHost.connect(plugin(), client)

        assertEquals(1, requestThreads.size)
        assertFalse(
            "请求在调用方线程（$caller）上发出了 —— 真机上会闪退",
            requestThreads.contains(caller),
        )
    }

    @Test
    fun `连接失败时返回 null 缓存而不是抛异常`() = runBlocking {
        // 调用方是界面，它需要一句能显示的话，不是一个要自己分类的异常树
        server.enqueue(MockResponse(code = 500, body = "boom"))

        val refresh = PluginHost.connect(plugin(), client)

        assertNull(refresh.cache)
        assertFalse(refresh.ok)
        val problem = refresh.problems.single()
        assertEquals("$.entry.mcp.url", problem.path)
        assertTrue(problem.message, problem.message.contains("500"))
    }

    @Test
    fun `被白名单拦下时问题指向权限而不是地址`() = runBlocking {
        // 用 `raw` 绕过清单校验：新加的校验层会在安装时就拦下
        // 「地址不在白名单里」的清单，但 `PluginHost` 是 public API，
        // 不能假设调用方一定先跑过校验
        val p = Manifests.raw(
            Manifests.mcpObject(
                url = server.url("/mcp").toString(),
                network = listOf("api.example.com"),
            ),
        )

        val refresh = PluginHost.connect(p, client)

        assertNull(refresh.cache)
        assertEquals("$.permissions.network", refresh.problems.single().path)
        assertEquals("白名单拦下时一个请求都不该发出去", 0, server.requestCount)
    }

    @Test
    fun `密钥没填时不去连 直接说清缺哪一项`() = runBlocking {
        val p = plugin(settingsJson = secretSetting(), auth = bearer())

        val refresh = PluginHost.connect(p, client)

        assertNull(refresh.cache)
        assertTrue(refresh.message, refresh.message.contains("token"))
        // 负面证据：连都没连。发出去换一个 401 回来的话，
        // 排查方向会变成「密钥错了」，而真实原因是「密钥没填」
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `stdio 不去连`() = runBlocking {
        val refresh = PluginHost.connect(plugin(transport = "stdio"), client)

        assertNull(refresh.cache)
        assertEquals("$.entry.mcp.transport", refresh.problems.single().path)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `停用的插件不去连`() = runBlocking {
        val refresh = PluginHost.connect(plugin(enabled = false), client)

        assertNull(refresh.cache)
        assertTrue(refresh.message, refresh.message.contains("停用"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `连接时把插件级请求头和认证头都发出去`() = runBlocking {
        server.enqueue(json(toolsResult(tool("alpha"))))
        val p = plugin(
            settingsJson = secretSetting() + ""","tenant":{"type":"string","title":"租户"}""",
            settings = mapOf("token" to "s3cret", "tenant" to "acme"),
            auth = bearer(),
            headers = """{"X-Tenant":"{{settings.tenant}}"}""",
        )

        PluginHost.connect(p, client)

        val request = server.takeRequest()
        assertEquals("Bearer s3cret", request.headers["Authorization"])
        assertEquals("模板要渲染成真实值", "acme", request.headers["X-Tenant"])
    }

    @Test
    fun `对端报出旧版协议时缓存里记的是旧版`() = runBlocking {
        // 先按现代版探一次，被拒（404 + 认不出来的 body）→ 回退到握手
        server.enqueue(MockResponse(code = 404))
        server.enqueue(legacyInitialize())
        server.enqueue(MockResponse(code = 202))
        server.enqueue(json(toolsResult(tool("alpha"))))

        val refresh = PluginHost.connect(plugin(), client)

        assertTrue(refresh.problems.toString(), refresh.ok)
        assertEquals(McpEra.Legacy, refresh.cache!!.era)
        assertEquals("2025-11-25", refresh.cache.protocolVersion)
    }

    @Test
    fun `旧版对端通过缓存装配之后仍然能调用`() = runBlocking {
        // ## 这条用例守的是一个真实存在过的 bug
        //
        // `callTool` 走的 `rpc()` **不触发年代判定**（只有 `list()` 会）。
        // 所以如果装配时不把缓存里的年代带回给 `McpClient`，一个旧版对端
        // 会被当成现代版发出去 —— 而且是在用户**第一次调用工具**的时候才炸，
        // 那时候离「连接成功」已经过了很久，根本想不到是那里
        server.enqueue(MockResponse(code = 404))
        server.enqueue(legacyInitialize())
        server.enqueue(MockResponse(code = 202))
        server.enqueue(json(toolsResult(tool("alpha", readOnly = true))))

        val p = plugin()
        val refresh = PluginHost.connect(p, client)
        val tool = PluginHost.tools(p.copy(mcpCache = refresh.cache), client).tools.single()

        // 把连接那 4 个请求取走，后面的断言才看得清
        repeat(4) { server.takeRequest() }

        server.enqueue(legacyInitialize())
        server.enqueue(MockResponse(code = 202))
        server.enqueue(json(callResult("pong")))

        val result = tool.execute(buildJsonObject { put("q", "hi") })

        assertFalse("调用不该失败：" + result.content, result.isError)
        assertEquals("pong", result.content)
        val first = server.takeRequest()
        assertEquals("装配时把年代带回来了，所以调用前会先握手", "initialize", first.methodName())
    }

    @Test
    fun `旧版对端装配出来的工具不做多余的探测`() = runBlocking {
        // 上一条的正面对照。缓存里写着旧版，装配出来的工具就该直接握手 ——
        // 不带年代的话它会先按现代版发一次，白白多一轮
        server.enqueue(MockResponse(code = 404))
        server.enqueue(legacyInitialize())
        server.enqueue(MockResponse(code = 202))
        server.enqueue(json(toolsResult(tool("alpha", readOnly = true))))

        val p = plugin()
        val refresh = PluginHost.connect(p, client)
        val tool = PluginHost.tools(p.copy(mcpCache = refresh.cache), client).tools.single()
        repeat(4) { server.takeRequest() }

        server.enqueue(legacyInitialize())
        server.enqueue(MockResponse(code = 202))
        server.enqueue(json(callResult("pong")))

        tool.execute(buildJsonObject { put("q", "hi") })

        val first = server.takeRequest()
        assertEquals("不该先探一次现代版", "initialize", first.methodName())
        assertNull("旧版请求不带协议版本头", first.headers["MCP-Protocol-Version"])
    }

    @Test
    fun `连接成功后缓存里带的是对端报的条目数`() = runBlocking {
        server.enqueue(json(toolsResult(tool("alpha"), tool("beta"))))

        val refresh = PluginHost.connect(plugin(), client)

        assertEquals(2, refresh.cache!!.offered)
        assertEquals(2, refresh.cache.tools.size)
        assertEquals(0, refresh.cache.dropped)
    }

    @Test
    fun `换了密钥但地址没变时缓存仍然有效`() = runBlocking {
        // 指纹刻意**不含密钥**（连哈希也不含）：这个 App 的安全姿态是
        // 「数据库被拷走不该等于密钥泄漏」，而一个短口令的 SHA-256
        // 是可以暴力反推的。代价是「换 token 指向同一地址上的另一个账号」
        // 时缓存不会自动失效 —— 那靠界面上的拉取时间和刷新按钮兜
        val p = cached(
            plugin(settingsJson = secretSetting(), settings = mapOf("token" to "old"), auth = bearer()),
        ) { server.enqueue(json(toolsResult(tool("alpha")))) }

        val rotated = p.copy(settings = PluginSettings(mapOf("token" to "new")))
        val set = PluginHost.tools(rotated, client)

        assertEquals(listOf("alpha"), set.tools.map { it.definition.name })
    }

    @Test
    fun `换了地址时缓存失效`() = runBlocking {
        // 上一条的对照组。只写「密钥变了缓存还有效」的话，
        // 一个把指纹写死成常量的实现也能通过
        val p = cached(plugin()) { server.enqueue(json(toolsResult(tool("alpha")))) }

        val moved = Manifests.installed(
            Manifests.mcp(url = "https://other.example.com/mcp", network = listOf("other.example.com")),
        ).copy(mcpCache = p.mcpCache)

        val set = PluginHost.tools(moved, client)

        assertTrue("地址变了，缓存说的就是另一台服务器了", set.tools.isEmpty())
        assertEquals("$.entry.mcp", set.problems.single().path)
    }

    @Test
    fun `换了认证方式时缓存失效`() = runBlocking {
        // 第二条对照组。指纹要覆盖「请求的形状」而不只是地址 ——
        // 从「不认证」改成「Bearer」意味着对端看到的身份变了，
        // 它完全可能因此给一份不同的工具清单
        val p = cached(plugin()) { server.enqueue(json(toolsResult(tool("alpha")))) }

        val withAuth = Manifests.installed(
            mcpJson(settings = secretSetting(), auth = bearer()),
            settings = mapOf("token" to "s3cret"),
        ).copy(mcpCache = p.mcpCache)

        val set = PluginHost.tools(withAuth, client)

        assertTrue(set.tools.isEmpty())
        assertEquals("$.entry.mcp", set.problems.single().path)
    }

    @Test
    fun `对端返回的不是对象时报错而不是崩`() = runBlocking {
        server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":"这是个字符串"}"""))

        val refresh = PluginHost.connect(plugin(), client)

        assertNull(refresh.cache)
        assertTrue(refresh.message, refresh.message.contains("tools/list"))
    }

    @Test
    fun `连接结果的消息是可显示的一句话`() = runBlocking {
        server.enqueue(json(toolsResult(tool("alpha"), tool("beta"))))

        val ok = PluginHost.connect(plugin(), client)
        assertTrue(ok.message, ok.message.contains("2"))

        val bad = McpRefresh(cache = null, problems = listOf(ManifestProblem("$.x", "地址写错了")))
        assertEquals("地址写错了", bad.message)
    }

    @Test
    fun `空清单也是成功 不是失败`() = runBlocking {
        // 对端把工具全关了是**它**的状态，不是连接失败。
        // 报成失败会让用户以为配置有问题，然后去改一个没写错的地址
        server.enqueue(json(toolsResult()))

        val refresh = PluginHost.connect(plugin(), client)

        assertTrue(refresh.ok)
        assertEquals(0, refresh.cache!!.tools.size)
        assertTrue(refresh.problems.isEmpty())
    }

    @Test
    fun `JSON 里没提 tools 字段时按空处理`() = runBlocking {
        server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":{}}"""))

        val refresh = PluginHost.connect(plugin(), client)

        assertTrue(refresh.ok)
        assertEquals(0, refresh.cache!!.tools.size)
        assertEquals(0, refresh.cache.offered)
    }

    @Test
    fun `装配出来的 MCP 工具不需要确认时看的是对端的只读声明`() = runBlocking {
        val p = cached(plugin()) {
            server.enqueue(json(toolsResult(tool("ro", readOnly = true), tool("rw", readOnly = false))))
        }

        val byName = PluginHost.tools(p, client).tools.associateBy { it.definition.name }

        assertFalse("对端声明只读 → 免确认", byName.getValue("ro").requiresConfirmation)
        assertTrue("没声明只读 → 要确认", byName.getValue("rw").requiresConfirmation)
    }

    @Test
    fun `声明了任意主机时只读也要确认`() = runBlocking {
        // readOnlyHint 是**每次连接时从线上下来的**，对端随时能改，
        // 用户看不到改动 —— 它比清单里那句声明弱得多，所以只能用来免掉确认，
        // 不能在「请求发去哪由模型决定」的场景下免掉确认
        val p = cached(Manifests.installed(mcpJson(network = listOf("*")))) {
            server.enqueue(json(toolsResult(tool("ro", readOnly = true))))
        }

        val tool = PluginHost.tools(p, client).tools.single()

        assertTrue(tool.requiresConfirmation)
        assertTrue("给用户看的那句话要说出「可以访问任意主机」", tool.userSummary.contains("任意主机"))
    }

    @Test
    fun `工具定义直接透传对端的 schema`() = runBlocking {
        val schema =
            """{"type":"object","properties":{"q":{"type":"string","description":"关键词"}},"required":["q"]}"""
        val p = cached(plugin()) {
            server.enqueue(json(toolsResult("""{"name":"search","description":"搜索","inputSchema":$schema}""")))
        }

        val definition = PluginHost.tools(p, client).tools.single().definition

        assertEquals("search", definition.name)
        assertEquals("搜索", definition.description)
        assertEquals(Json.parseToJsonElement(schema), definition.parameters)
    }

    @Test
    fun `同一个插件装配两次得到等价的结果`() = runBlocking {
        // 装配路径上有几个地方会建 `McpClient`、算指纹。它们必须
        // **不依赖任何跨调用的状态** —— 依赖了的话，第二次装配
        // （用户改完设置、或从别的页面回来触发的那次 refresh）
        // 会得到和第一次不同的结果，而那是最难复现的一类 bug
        val p = cached(plugin()) { server.enqueue(json(toolsResult(tool("alpha")))) }

        val first = PluginHost.tools(p, client)
        val second = PluginHost.tools(p, client)

        assertEquals(first.tools.map { it.definition.name }, second.tools.map { it.definition.name })
        assertEquals(first.problems.map { it.path }, second.problems.map { it.path })
        assertEquals("装配不该发任何请求", 1, server.requestCount)
    }

    @Test
    fun `插件级请求头缺配置项时连接失败而不是少发一个头`() = runBlocking {
        // 少一个头的结果通常是 401 或 400 —— 一个和真实原因无关的错误，
        // 而「作者声明的头没生效」是最难查的一类问题
        val p = plugin(
            settingsJson = """"tenant":{"type":"string","title":"租户"}""",
            headers = """{"X-Tenant":"{{settings.tenant}}"}""",
        )

        val refresh = PluginHost.connect(p, client)

        assertNull(refresh.cache)
        assertEquals("$.entry.mcp.headers", refresh.problems.single().path)
        assertEquals(0, server.requestCount)
    }

    // ------------------------------------------------------------------ 缓存的编解码

    @Test
    fun `缓存解不出来时当作没连接过`() {
        // 宿主升级之后老缓存的字段可能对不上。那时候把整个插件判成坏的
        // 是不对的 —— 缓存只是省一次网络往返，丢了刷新一下就好，
        // 而插件的清单（manifest_json）不可以丢。两者的容错策略因此不同
        assertNull("不是 JSON", McpCacheCodec.decode("这不是 JSON"))
        assertNull("是 JSON 但没有必需字段", McpCacheCodec.decode("{}"))
        assertNull("类型对不上", McpCacheCodec.decode("""{"fingerprint":1}"""))
        assertNull("空串", McpCacheCodec.decode(""))
        assertNull("null", McpCacheCodec.decode(null))
    }

    @Test
    fun `缓存能原样编解码`() {
        val cache = Manifests.mcpCache(
            fingerprint = "http|https://x/mcp|auth=bearer|headers=authorization",
            offered = 3,
            tools = listOf(Manifests.snapshot("alpha", readOnly = true)),
        )

        assertEquals(cache, McpCacheCodec.decode(McpCacheCodec.encode(cache)))
    }

    @Test
    fun `缓存里多出来的字段不影响解码`() {
        // 这条是「将来给 McpToolCache 加字段」的保险：老缓存里没有新字段时
        // 必须还能读出来（读不出来就等于用户白连一次）
        val raw = McpCacheCodec.encode(Manifests.mcpCache(fingerprint = "fp"))
        val withExtra = raw.dropLast(1) + ""","future_field":{"a":1}}"""

        val decoded = McpCacheCodec.decode(withExtra)

        assertNotNull(decoded)
        assertEquals("fp", decoded!!.fingerprint)
    }

    @Test
    fun `派生字段在读出来的时候重算`() {
        // headerParams 是从 inputSchema 的 `x-mcp-header` 扫出来的**派生**字段，
        // 不落库。存了的话，「schema 改了但派生字段没重算」就是一个
        // 只在对端更新 schema 时才发作的 bug
        val schema =
            """{"type":"object","properties":{"r":{"type":"string","x-mcp-header":"Region"}}}"""
        val cache = Manifests.mcpCache(tools = listOf(Manifests.snapshot("region", schema = schema)))

        val restored = McpCacheCodec.decode(McpCacheCodec.encode(cache))!!
            .tools.single().toDescriptor()

        assertEquals(mapOf("r" to "Region"), restored.headerParams)
    }

    @Test
    fun `inputSchema 编解码之后还是对象`() {
        // 变成字符串的话，模型拿到的参数定义就是一段没法用的文本
        val cache = Manifests.mcpCache(tools = listOf(Manifests.snapshot("alpha")))

        val restored = McpCacheCodec.decode(McpCacheCodec.encode(cache))!!
            .tools.single().inputSchema

        assertTrue(restored.containsKey("properties"))
        val properties = restored["properties"] as JsonObject
        assertEquals("q", properties.keys.first())
        assertEquals("string", (properties["q"] as JsonObject)["type"]!!.jsonPrimitive.content)
    }
}
