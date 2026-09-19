package com.aichat.plugin.manifest

import com.aichat.plugin.Manifests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单解析与校验。
 *
 * ## 严格模式是这个文件里最重要的一条
 *
 * 宽松解析下，把 `requiresConfirmation` 拼成 `requireConfirmation` 会被当成
 * 「没写」→ 取默认值。对确认开关来说，那意味着**本该弹窗的写操作直接执行**。
 *
 * 所以「未知键必须报错」这条有一个专门的用例。
 */
class ManifestParserTest {

    private fun parse(json: String) = ManifestParser.parse(json)

    /** 断言「有 Error 级问题，且消息里出现某个关键词」。 */
    private fun assertRejected(json: String, keyword: String) {
        val check = parse(json)
        assertNull("有 Error 时不该给出清单：\n${check.report()}", check.manifest)
        assertTrue(
            "错误里该提到「$keyword」，实际是：\n${check.report()}",
            check.errors.any { it.message.contains(keyword) },
        )
    }

    /**
     * 断言「有一条问题挂在某个路径上」。
     *
     * 关键词断言管不了路径，而有一类 bug 只在路径上体现：`auth` 在两段里
     * 各有一份（declarative / mcp），共用一个校验函数却写死路径的话，
     * 作者会在一个自己没写过的地方看到报错。消息内容完全正常，只有路径是错的。
     */
    private fun assertProblemAt(check: ManifestCheck, path: String) {
        assertTrue(
            "问题里该有一条挂在 $path 上，实际是：\n${check.report()}",
            check.problems.any { it.path == path },
        )
    }

    // ---------------------------------------------------------------- 严格模式

    @Test
    fun `未知字段一律报错`() {
        // 拼错了确认开关的名字 —— 这是最危险的一种拼写错误
        val json = Manifests.declarative().replace(
            "\"tools\":[",
            "\"tools\":[",
        ).replace(
            Manifests.toolSpec("do_thing"),
            Manifests.toolSpec("do_thing").replaceFirst("{", "{\"requireConfirmation\":false,"),
        )

        val check = parse(json)

        assertNull(check.manifest)
        assertTrue(
            "要说清为什么严格：宽松处理会把它当成「没写」\n${check.report()}",
            check.errors.any { it.message.contains("不认识字段") },
        )
    }

    @Test
    fun `合法清单直接通过`() {
        val check = parse(Manifests.declarative())

        assertNotNull(check.report(), check.manifest)
        assertTrue(check.errors.isEmpty())
        assertEquals("pub.test.demo", check.manifest?.id)
        assertTrue(check.isUsable)
    }

    @Test
    fun `不是 JSON 时给出可读的错误`() {
        val check = parse("{ 这不是 JSON")

        assertNull(check.manifest)
        assertTrue(check.errors.single().message, check.errors.single().message.contains("不是合法的 JSON"))
    }

    @Test
    fun `query 里写数字时给出中文的可操作报错`() {
        // 作者最自然的手写方式就是这个，而 kotlinx 的原话是
        // "String literal for value of key 'forecast_days' should be quoted" ——
        // 既没说清是哪个字段，也没说该怎么改
        val json = Manifests.declarative(
            tools = listOf("t"),
            toolBody = { Manifests.toolSpec(it, query = """{"forecast_days":3}""") },
        )

        val check = parse(json)

        assertNull(check.manifest)
        val message = check.errors.single().message
        assertTrue("要指出是哪个字段：$message", message.contains("forecast_days"))
        assertTrue("要说清怎么改：$message", message.contains("\"3\""))
        assertFalse("不要把 kotlinx 的英文原话漏出去：$message", message.contains("should be quoted"))
    }

    @Test
    fun `query 里写字符串时通过`() {
        val json = Manifests.declarative(
            tools = listOf("t"),
            toolBody = { Manifests.toolSpec(it, query = """{"forecast_days":"3"}""") },
        )
        assertNotNull(parse(json).manifest)
    }

    // ---------------------------------------------------------------- 确认开关的三态

    @Test
    fun `不写确认开关时解析成 null`() {
        val manifest = parse(Manifests.declarative(tools = listOf("t"))).manifest!!
        assertNull(
            "null 表示「作者没说」，宿主据此按 HTTP 方法兜底 —— " +
                "不能和「作者明确说不用确认」混成同一个值",
            manifest.tools.single().requiresConfirmation,
        )
    }

    @Test
    fun `显式写 false 解析成 false`() {
        val json = Manifests.declarative(
            tools = listOf("t"),
            toolBody = { Manifests.toolSpec(it, method = "GET", confirmation = "false") },
        )
        assertEquals(false, parse(json).manifest!!.tools.single().requiresConfirmation)
    }

    @Test
    fun `显式写 true 解析成 true`() {
        val json = Manifests.declarative(
            tools = listOf("t"),
            toolBody = { Manifests.toolSpec(it, confirmation = "true") },
        )
        assertEquals(true, parse(json).manifest!!.tools.single().requiresConfirmation)
    }

    @Test
    fun `标了 dangerous 却没写确认开关时报错`() {
        val json = Manifests.declarative(
            tools = listOf("t"),
            toolBody = { Manifests.toolSpec(it, dangerous = "true") },
        )
        assertRejected(json, "dangerous")
    }

    @Test
    fun `标了 dangerous 且开了确认时通过`() {
        val json = Manifests.declarative(
            tools = listOf("t"),
            toolBody = { Manifests.toolSpec(it, dangerous = "true", confirmation = "true") },
        )
        assertNotNull(parse(json).manifest)
    }

    @Test
    fun `作者明确关掉确认但方法是 POST 时给警告`() {
        val json = Manifests.declarative(
            tools = listOf("t"),
            toolBody = { Manifests.toolSpec(it, method = "POST", confirmation = "false") },
        )

        val check = parse(json)

        assertNotNull("这是警告不是错误 —— 有些接口确实用 POST 做只读查询", check.manifest)
        assertTrue(check.warnings.any { it.message.contains("POST") })
    }

    @Test
    fun `作者明确关掉确认且方法是 GET 时没有警告`() {
        val json = Manifests.declarative(
            tools = listOf("t"),
            toolBody = { Manifests.toolSpec(it, method = "GET", confirmation = "false") },
        )
        assertTrue(parse(json).warnings.isEmpty())
    }

    // ---------------------------------------------------------------- 权限

    @Test
    fun `声明式插件没有网络白名单时报错`() {
        // 装了也完全不工作，作者不看日志很难发现
        assertRejected(Manifests.declarative(network = emptyList()), "白名单")
    }

    @Test
    fun `baseUrl 的主机必须在白名单里`() {
        assertRejected(
            Manifests.declarative(baseUrl = "https://other.example.org", network = listOf("api.example.com")),
            "不在网络白名单里",
        )
    }

    @Test
    fun `声明任意主机时给警告`() {
        val check = parse(Manifests.declarative(network = listOf("*")))

        assertNotNull(check.manifest)
        assertTrue(check.warnings.any { it.message.contains("任意主机") })
    }

    @Test
    fun `子域通配被拒绝`() {
        // `*.example.com` 是一次静默的权限扩张：它会把 evil.example.com
        // 一起放行，而用户看到的是一行很窄的声明
        assertRejected(Manifests.declarative(network = listOf("*.example.com")), "不是合法的主机名")
    }

    @Test
    fun `带协议的白名单项被拒绝`() {
        assertRejected(
            Manifests.declarative(network = listOf("https://api.example.com")),
            "不是合法的主机名",
        )
    }

    @Test
    fun `shell 权限给警告`() {
        val check = parse(Manifests.declarative(extraPermissions = ""","shell":true"""))
        assertTrue(check.warnings.any { it.message.contains("shell") })
    }

    @Test
    fun `设备权限给警告`() {
        val check = parse(Manifests.declarative(extraPermissions = ""","device":["clipboard","sms"]"""))
        assertTrue(check.warnings.any { it.message.contains("剪贴板") && it.message.contains("短信") })
    }

    // ---------------------------------------------------------------- 认证

    @Test
    fun `auth 指向不存在的配置项时报错`() {
        // 指向不存在的项会让密钥静默地不发送，用户只看到一个 401
        assertRejected(
            Manifests.declarative(extraEntry = ""","auth":{"type":"bearer","settingKey":"nope"}"""),
            "指向",
        )
    }

    @Test
    fun `auth 指向存在的配置项时通过`() {
        val json = Manifests.declarative(
            settings = """"apiKey":{"type":"string","title":"密钥","secret":true}""",
            extraEntry = ""","auth":{"type":"bearer","settingKey":"apiKey"}""",
        )
        assertNotNull(parse(json).manifest)
    }

    @Test
    fun `auth type 是 header 但没给 headerName 时报错`() {
        val json = Manifests.declarative(
            settings = """"k":{"type":"string","title":"密钥","secret":true}""",
            extraEntry = ""","auth":{"type":"header","settingKey":"k"}""",
        )
        assertRejected(json, "headerName")
    }

    @Test
    fun `auth type 是 none 却给了 settingKey 时给警告`() {
        val json = Manifests.declarative(
            settings = """"k":{"type":"string","title":"密钥"}""",
            extraEntry = ""","auth":{"type":"none","settingKey":"k"}""",
        )

        val check = parse(json)
        assertNotNull(check.manifest)
        assertTrue(check.warnings.any { it.message.contains("不会被使用") })
    }

    @Test
    fun `auth 校验两段都跑 且各自报回自己那一段的路径`() {
        // 一份清单可以同时写 declarative 和 mcp 两段（宿主只读 runtime
        // 对应的那段），而作者改运行形态时很容易只改 runtime、留下一段
        // 过期的配置。两段都查，那些残留会当场暴露。
        //
        // 这里两段都故意指向不存在的配置项 —— 关键不是「报了几条」，
        // 而是**两条报错分别挂在作者写过的那两段路径上**。
        // 共用一个校验函数却把路径写死的话，作者会去一个自己没写过的地方找。
        val json = """
            {"id":"pub.test.both","name":"双段插件","version":"1.0.0","runtime":"declarative",
             "permissions":{"network":["api.example.com"]},
             "settings":{"k":{"type":"string","title":"密钥"}},
             "entry":{
               "declarative":{"baseUrl":"https://api.example.com",
                              "auth":{"type":"bearer","settingKey":"missing_a"}},
               "mcp":{"transport":"http","url":"https://api.example.com/mcp",
                      "auth":{"type":"bearer","settingKey":"missing_b"}}},
             "tools":[{"name":"t","description":"说明","parameters":{"type":"object","properties":{}},
                       "request":{"method":"GET","path":"/p"}}]}
        """.trimIndent()

        val check = parse(json)

        assertNull(check.manifest)
        assertProblemAt(check, "$.entry.declarative.auth.settingKey")
        assertProblemAt(check, "$.entry.mcp.auth.settingKey")
        // 两条报错各自点到自己的那个键名上 —— 路径对而内容串了也一样没用
        assertTrue(
            "declarative 那条该提到 missing_a：\n${check.report()}",
            check.problems.any { it.path.startsWith("$.entry.declarative") && it.message.contains("missing_a") },
        )
        assertTrue(
            "mcp 那条该提到 missing_b：\n${check.report()}",
            check.problems.any { it.path.startsWith("$.entry.mcp") && it.message.contains("missing_b") },
        )
    }

    @Test
    fun `MCP 的认证不能走查询参数`() {
        // 对 MCP 来说 query 不是「不推荐」而是**没法表达**：地址是一个固定
        // 端点，没有「往哪个查询参数上挂」的语义。而且密钥放进 URL 会出现在
        // 服务端的访问日志和任何中间代理上 —— 一个只在抓包时才发现的问题
        val json = Manifests.mcp(
            settings = """"k":{"type":"string","title":"密钥","secret":true}""",
            auth = """{"type":"query","settingKey":"k","queryName":"token"}""",
        )

        val check = parse(json)

        assertNull(check.manifest)
        assertProblemAt(check, "$.entry.mcp.auth.type")
        assertTrue(check.errors.any { it.message.contains("不能走查询参数") })
    }

    @Test
    fun `声明式的认证仍然可以走查询参数`() {
        // **对照组**：上一条禁掉的是「MCP 用 query」，不是「query 这个类型
        // 有问题」。少了这一条，一个把 query 从 AuthType 里整个删掉的改动
        // 也能让上面那条通过 —— 而那会让一批能用的声明式插件装不上
        val json = Manifests.declarative(
            settings = """"k":{"type":"string","title":"密钥","secret":true}""",
            extraEntry = ""","auth":{"type":"query","settingKey":"k","queryName":"token"}""",
        )
        assertNotNull(parse(json).manifest)
    }

    @Test
    fun `MCP 的 auth 指向不存在的配置项时报错`() {
        // 和声明式同一个校验函数，但路径必须是 mcp 那一段
        val json = Manifests.mcp(auth = """{"type":"bearer","settingKey":"nope"}""")

        val check = parse(json)

        assertNull(check.manifest)
        assertProblemAt(check, "$.entry.mcp.auth.settingKey")
    }

    @Test
    fun `MCP 用 bearer 认证且配置项存在时通过`() {
        val json = Manifests.mcp(
            settings = """"token":{"type":"string","title":"访问令牌","secret":true}""",
            auth = """{"type":"bearer","settingKey":"token"}""",
        )
        assertNotNull(parse(json).manifest)
    }

    // ---------------------------------------------------------------- 占位符

    @Test
    fun `占位符指向不存在的参数时报错`() {
        val json = Manifests.declarative(
            tools = listOf("t"),
            toolBody = { Manifests.toolSpec(it, path = "/p/{{latitide}}") },
        )
        assertRejected(json, "对应的参数不存在")
    }

    @Test
    fun `占位符指向不存在的配置项时报错且报出完整写法`() {
        val json = Manifests.declarative(
            tools = listOf("t"),
            toolBody = { Manifests.toolSpec(it, query = """{"u":"{{settings.nope}}"}""") },
        )

        val check = parse(json)
        assertNull(check.manifest)
        val message = check.errors.single().message
        // 报出来的写法必须和作者写的一致 —— 报成 {{nope}} 的话，
        // 他回清单里找不到这一串
        assertTrue(message, message.contains("{{settings.nope}}"))
        assertFalse(message, message.contains("`{{nope}}`"))
    }

    @Test
    fun `畸形占位符被报出来`() {
        val json = Manifests.declarative(
            tools = listOf("t"),
            toolBody = { Manifests.toolSpec(it, path = "/p/{{}}") },
        )
        assertRejected(json, "写法不合法")
    }

    @Test
    fun `path 写成完整地址时报错`() {
        val json = Manifests.declarative(
            tools = listOf("t"),
            toolBody = { Manifests.toolSpec(it, path = "https://evil.example.org/x") },
        )
        assertRejected(json, "相对")
    }

    // ---------------------------------------------------------------- 工具级主机名覆盖

    /** 一个「工具打到另一台主机上」的清单，白名单由调用方给。 */
    private fun withToolHost(host: String, network: List<String>) = Manifests.declarative(
        network = network,
        tools = listOf("t"),
        toolBody = { Manifests.toolSpec(it, host = host) },
    )

    @Test
    fun `工具可以指定另一台主机`() {
        // 真实的 API 常常把端点分散在不同子域上，所以这是必须支持的写法。
        // 内置的天气示例就撞上了：预报在 api.open-meteo.com，
        // 地理编码在 geocoding-api.open-meteo.com
        val check = parse(
            withToolHost("\"geo.example.com\"", listOf("api.example.com", "geo.example.com")),
        )

        assertNotNull("这是合法写法，不该报错：\n${check.report()}", check.manifest)
        assertEquals("geo.example.com", check.manifest!!.tools.single().request?.host)
    }

    @Test
    fun `工具指定的主机不在白名单里时报错`() {
        // 这条一定会在运行时被守卫拦下，而且提示是写给模型的 ——
        // 作者在安装界面上看不到。所以必须在这里报
        assertRejected(
            withToolHost("\"geo.example.com\"", listOf("api.example.com")),
            "permissions.network",
        )
    }

    @Test
    fun `声明了任意主机时不再单独校验工具主机`() {
        val check = parse(withToolHost("\"geo.example.com\"", listOf("*")))
        assertNotNull("白名单是 * 时任何主机都放行，不该报错：\n${check.report()}", check.manifest)
    }

    @Test
    fun `工具主机名带协议时报错`() {
        // 允许整段地址的话，作者可以写 http:// 把密钥明文发出去，
        // 而安装界面上那行权限声明完全不会变 —— 一次静默的降级
        assertRejected(
            withToolHost("\"https://geo.example.com\"", listOf("geo.example.com")),
            "不是一个具体的主机名",
        )
    }

    @Test
    fun `工具主机名带端口时报错`() {
        // 端口一律沿用 baseUrl：白名单不参与端口匹配，
        // 让作者在这里写端口会让他以为「限住了端口」
        assertRejected(
            withToolHost("\"geo.example.com:8080\"", listOf("geo.example.com")),
            "不是一个具体的主机名",
        )
    }

    @Test
    fun `工具主机名不能是星号`() {
        // `*` 在权限声明里有意义（任意主机），但作为一个「这次请求打去哪」
        // 的值没有意义 —— 请求总得有一个具体的目标
        assertRejected(withToolHost("\"*\"", listOf("*")), "不是一个具体的主机名")
    }

    @Test
    fun `工具主机名不能是空串`() {
        assertRejected(withToolHost("\"\"", listOf("api.example.com")), "不能是空字符串")
    }

    // ---------------------------------------------------------------- 插件级请求头

    @Test
    fun `请求头里不能用参数占位符`() {
        // 这段头被所有工具共用，而参数是每个工具各自的 ——
        // 在共享的头里引用某个工具的参数，换个工具就成了未定义行为
        val json = Manifests.declarative(
            tools = listOf("t"),
            extraEntry = ""","headers":{"X-Thing":"{{arg}}"}""",
        )
        assertRejected(json, "不能用")
    }

    @Test
    fun `请求头里设置 Host 被拒绝`() {
        val json = Manifests.declarative(
            extraEntry = ""","headers":{"Host":"evil.example.org"}""",
        )
        assertRejected(json, "Host")
    }

    @Test
    fun `请求头里带换行被拒绝`() {
        // 注意这里是**单个**反斜杠 + n：Kotlin 原始字符串不处理转义，
        // 所以 JSON 收到的是 `\n`，解出来才是真的换行。
        // 写成 `\\n` 的话 JSON 解出的是字面的「反斜杠 n」两个字符，
        // 用例会通过但什么都没测到
        val json = Manifests.declarative(
            extraEntry = ""","headers":{"X-Thing":"a\nb"}""",
        )
        assertRejected(json, "换行")
    }

    @Test
    fun `请求头里引用存在的配置项时通过`() {
        val json = Manifests.declarative(
            settings = """"k":{"type":"string","title":"令牌","secret":true}""",
            extraEntry = ""","headers":{"X-Token":"{{settings.k}}"}""",
        )
        assertNotNull(parse(json).manifest)
    }

    // ---------------------------------------------------------------- MCP 入口

    @Test
    fun `合法 MCP 清单直接通过`() {
        val check = parse(Manifests.mcp(whitelist = listOf("echo", "write_note")))

        assertNotNull(check.report(), check.manifest)
        assertTrue(check.errors.isEmpty())
        assertEquals(listOf("echo", "write_note"), check.manifest?.entry?.mcp?.tools)
    }

    @Test
    fun `MCP 的 http 传输必须给 url`() {
        assertRejected(Manifests.mcp(url = ""), "必须给 url")
    }

    @Test
    fun `MCP 地址不合法时报错`() {
        // 少了 https:// 是最常见的手写错误，而它在装配期的表现是
        // 「地址解析不出来」，报在离作者很远的地方
        assertRejected(Manifests.mcp(url = "api.example.com/mcp"), "不是合法的 http")
    }

    @Test
    fun `MCP 地址的主机必须在白名单里`() {
        // 和 baseUrl 同一个理由：不在白名单里的话第一次连接就会被守卫拦下，
        // 而拦下的那句话是写给模型的 —— 作者在安装界面上看不到
        val check = parse(
            Manifests.mcp(url = "https://mcp.example.org/mcp", network = listOf("api.example.com")),
        )

        assertNull(check.manifest)
        assertProblemAt(check, "$.entry.mcp.url")
    }

    @Test
    fun `白名单是任意主机时 MCP 地址不再单独校验`() {
        // **对照组**：上面那条要守的是「MCP 的地址也参与白名单校验」，
        // 不是「MCP 地址一律报错」
        val check = parse(
            Manifests.mcp(url = "https://mcp.example.org/mcp", network = listOf("*")),
        )
        assertNotNull("白名单是 * 时任何主机都放行：\n${check.report()}", check.manifest)
    }

    @Test
    fun `MCP 的 stdio 传输必须给 command`() {
        // 夹具在 transport 是 stdio 时会自动补上 command —— 那正是
        // 「校验层要检查的东西」被夹具替作者补掉了，所以这条必须手写 JSON。
        //
        // 顺带：stdio 在 Android 上永远跑不起来，但**这里不报错**。
        // 清单是跨宿主的，桌面宿主能跑；「这台设备上跑不起来」是装配期的问题
        val json = """
            {"id":"pub.test.stdio","name":"本地 MCP","version":"1.0.0","runtime":"mcp",
             "permissions":{"network":[]},
             "entry":{"mcp":{"transport":"stdio"}},
             "tools":[]}
        """.trimIndent()

        assertRejected(json, "必须给 command")
    }

    @Test
    fun `MCP 的请求头里不能用参数占位符`() {
        // 走的是和声明式同一个 checkEntryHeaders。这条守的是「MCP 段
        // 没被漏掉」—— 漏掉的话作者写了头却看不到任何提示，而那段头
        // 在运行时是会被真的发出去的
        val check = parse(Manifests.mcp(headers = """{"X-Thing":"{{arg}}"}"""))

        assertNull(check.manifest)
        assertProblemAt(check, "$.entry.mcp.headers.X-Thing")
    }

    @Test
    fun `MCP 的请求头里不能设置 Host`() {
        val check = parse(Manifests.mcp(headers = """{"Host":"evil.example.org"}"""))

        assertNull(check.manifest)
        assertProblemAt(check, "$.entry.mcp.headers.Host")
    }

    @Test
    fun `MCP 的请求头里带换行被拒绝`() {
        // 单个反斜杠 + n：Kotlin 原始字符串不处理转义，JSON 收到的是 `\n`，
        // 解出来才是真换行（写成 `\\n` 的话解出的是字面两个字符，用例
        // 会通过但什么都没测到）
        val check = parse(Manifests.mcp(headers = """{"X-Thing":"a\nb"}"""))

        assertNull(check.manifest)
        assertProblemAt(check, "$.entry.mcp.headers.X-Thing")
    }

    @Test
    fun `MCP 的请求头可以引用配置项`() {
        // MCP 的密钥走 auth，但插件级的固定头（租户 ID、API 版本这类）
        // 走 headers —— 两者是不同的东西，都得出得去
        val json = Manifests.mcp(
            settings = """"tenant":{"type":"string","title":"租户"}""",
            headers = """{"X-Tenant":"{{settings.tenant}}"}""",
        )
        assertNotNull(parse(json).manifest)
    }

    // ---------------------------------------------------------------- 工具本身

    @Test
    fun `一个工具都没有时报错`() {
        assertRejected(Manifests.declarative(tools = emptyList()), "至少要暴露一个工具")
    }

    @Test
    fun `MCP 清单可以没有工具`() {
        // 工具清单由对端在装配时动态给出，作者在清单里写不出来。
        // 要求它非空的话，作者只能瞎编几个名字凑数 —— 而那些名字
        // 永远不会被用到，却会出现在「这个插件提供什么」的界面上
        val manifest = Manifests.parse(Manifests.mcp())
        assertTrue(manifest.tools.isEmpty())
        assertEquals(PluginRuntimeKind.Mcp, manifest.runtime)
    }

    @Test
    fun `script 清单仍然要求至少一个工具`() {
        // **对照组**：上一条放行的必须是「mcp 这个例外」，不是「空 tools 都不报错了」。
        // 少了这一条，一个把整个 checkTools 删掉的改动也能让上面那条通过
        assertRejected(
            """{"id":"pub.test.script","name":"脚本插件","version":"1.0.0","runtime":"script",
               "permissions":{"network":[],"filesystem":"read"},
               "entry":{"script":{"main":"index.js"}},
               "tools":[]}""".trimIndent(),
            "至少要暴露一个工具",
        )
    }

    @Test
    fun `工具名不合规时报错`() {
        assertRejected(Manifests.declarative(tools = listOf("BadName")), "不是合法的工具名")
    }

    @Test
    fun `同一插件里工具重名时报错`() {
        assertRejected(Manifests.declarative(tools = listOf("same", "same")), "重复")
    }

    @Test
    fun `工具说明为空时报错`() {
        val json = Manifests.declarative(
            tools = listOf("t"),
            toolBody = { Manifests.toolSpec(it).replace("测试用工具 t", "") },
        )
        assertRejected(json, "不能为空")
    }

    @Test
    fun `工具说明过长时报错`() {
        val json = Manifests.declarative(
            tools = listOf("t"),
            toolBody = { Manifests.toolSpec(it).replace("测试用工具 t", "长".repeat(301)) },
        )
        assertRejected(json, "超过")
    }

    @Test
    fun `GET 带请求体时给警告`() {
        val json = """
            {"id":"pub.a.one","name":"插件","version":"1.0.0","runtime":"declarative",
             "permissions":{"network":["api.example.com"]},
             "entry":{"declarative":{"baseUrl":"https://api.example.com"}},
             "tools":[{"name":"t","description":"说明","parameters":{"type":"object","properties":{}},
               "request":{"method":"GET","path":"/p","body":{"a":1}}}]}
        """.trimIndent()

        val check = parse(json)
        assertNotNull(check.manifest)
        assertTrue(check.warnings.any { it.message.contains("请求体") })
    }

    // ---------------------------------------------------------------- 身份

    @Test
    fun `插件 id 不合规时报错`() {
        assertRejected(Manifests.declarative(id = "BadID"), "不是合法的插件标识")
    }

    @Test
    fun `版本号不是三段式时报错`() {
        assertRejected(Manifests.declarative(version = "1.0"), "三段式")
    }

    @Test
    fun `报告文本包含全部问题`() {
        val check = parse(Manifests.declarative(network = listOf("*"), version = "1.0"))

        val report = check.report()
        assertTrue(report, report.contains("三段式"))
        assertTrue("警告也要出现在报告里：$report", report.contains("任意主机"))
    }
}
