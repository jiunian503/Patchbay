package com.aichat.core.data

import com.aichat.domain.secret.InMemorySecretStore
import com.aichat.plugin.Manifests
import com.aichat.plugin.manifest.FilesystemScope
import com.aichat.plugin.runtime.mcp.McpEra
import com.aichat.plugin.runtime.mcp.McpToolCache
import com.aichat.plugin.runtime.mcp.toDescriptor
import com.aichat.plugin.workspace.PluginWorkspaces
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [PluginRepository] 的行为测试。
 *
 * 全部在 JVM 上跑，因为 Repository 只依赖 [PluginDao] 与
 * [com.aichat.domain.secret.SecretStore] 两个接口。
 *
 * ## 这里守的三件事
 *
 * 1. **密钥绝不进数据库。** 和 `provider` 表同一个理由：数据库文件被拷走
 *    不该等于用户的 API Key 泄漏。
 * 2. **升级不丢配置。** 用户填的 Key 是他自己的东西，一次「修了个 bug」
 *    的更新把它抹掉，用户只会看到 401，完全想不到是更新导致的。
 * 3. **敏感项的三态。** 「不在 map 里 = 不动」这一条写错的话，
 *    用户改个温度单位就会掉 Key。
 */
class PluginRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * 一个临时的工作区根。
     *
     * 大部分用例都不碰它 —— 但 [PluginRepository] 的构造要求一个，
     * 因为**卸载时把工作区删掉是它的职责**（见 [PluginRepository.uninstall]）。
     * 用一个真的临时目录而不是空实现，是为了让「卸载顺手删了工作区」这条
     * 能在 `卸载会连工作区一起删掉` 里被真的验到。
     */
    private fun workspaces() = PluginWorkspaces(File(tmp.root, "plugin-workspaces"))

    /**
     * `inner` 是为了能直接用 [tmp]。它本来是普通嵌套类，而 53 个调用点
     * 都写的是 `Env()` —— 改签名会让这个文件里到处是噪声，
     * 而这里要加的只是一个测试专用的临时目录。
     */
    private inner class Env {
        val dao = FakePluginDao()
        val secrets = InMemorySecretStore()
        var now = 1_000L
        val workspaces = workspaces()
        val repo = PluginRepository(
            dao = dao,
            secrets = secrets,
            tx = NoTransactionRunner,
            workspaces = workspaces,
            clock = { now },
        )
    }

    /** 一个带敏感项和非敏感项的清单。 */
    private fun withSettings(
        id: String = "pub.test.demo",
        version: String = "1.0.0",
    ): String = Manifests.declarative(
        id = id,
        version = version,
        settings = """
            "apiKey":{"type":"string","title":"密钥","secret":true},
            "unit":{"type":"enum","title":"单位","enum":["celsius","fahrenheit"]}
        """.trimIndent(),
        extraEntry = ""","auth":{"type":"bearer","settingKey":"apiKey"}""",
    )

    // ---------------------------------------------------------------- 安装

    @Test
    fun `安装后能读回插件`() = runTest {
        val env = Env()

        val result = env.repo.install(Manifests.declarative())

        assertTrue(result.check.report(), result.ok)
        assertEquals("pub.test.demo", result.pluginId)
        assertFalse(result.isUpgrade)
        assertEquals(1, env.dao.count())
        assertEquals("pub.test.demo", env.dao.rows.keys.single())
    }

    @Test
    fun `校验不通过时什么都不写`() = runTest {
        val env = Env()

        val result = env.repo.install("""{"id":"bad"}""")

        assertFalse(result.ok)
        assertNull(result.pluginId)
        assertEquals("半个插件比没有插件更难查", 0, env.dao.count())
        assertTrue(result.check.errors.isNotEmpty())
    }

    @Test
    fun `存的是清单原文`() = runTest {
        val env = Env()
        val json = Manifests.declarative()

        env.repo.install(json)

        // 存原文而不是解析结果：宿主的解析规则会演进，
        // 存原文能让已装插件跟着演进，不用用户重装
        assertEquals(json, env.dao.rows.getValue("pub.test.demo").manifestJson)
    }

    @Test
    fun `安装时记下来源`() = runTest {
        val env = Env()

        env.repo.install(Manifests.declarative(), PluginRepository.SOURCE_FILE)

        // 这一列是「装了十几个插件之后想不起来这个从哪弄来的」时唯一的线索。
        // 它一旦被写死成 paste，就等于不存在 —— 所以每个入口都要有自己的值
        assertEquals("file", env.dao.rows.getValue("pub.test.demo").source)
    }

    @Test
    fun `升级不会把来源改回默认值`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative(), PluginRepository.SOURCE_CLIPBOARD)

        env.repo.install(Manifests.declarative(version = "1.1.0"), PluginRepository.SOURCE_FILE)

        // 这次是「从文件更新」，来源跟着变成 file 才是对的。
        // 这条用例真正钉的是「升级路径也把 source 写进去了」——
        // 漏掉的话，升级过的插件来源会停在第一次装的时候
        assertEquals("file", env.dao.rows.getValue("pub.test.demo").source)
    }

    @Test
    fun `四个来源常量互不相同`() {
        // 拼成同一个字符串的话，这一列记下来的东西就没有区分度了
        val all = listOf(
            PluginRepository.SOURCE_PASTE,
            PluginRepository.SOURCE_CLIPBOARD,
            PluginRepository.SOURCE_BUNDLED,
            PluginRepository.SOURCE_FILE,
            PluginRepository.SOURCE_URL,
        )
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `新装的插件默认启用`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative())

        assertTrue(env.dao.rows.getValue("pub.test.demo").enabled)
    }

    // ---------------------------------------------------------------- 密钥不进库

    @Test
    fun `密钥绝不出现在数据库行里`() = runTest {
        val env = Env()
        env.repo.install(withSettings())
        env.repo.saveSettings(
            "pub.test.demo",
            PluginSettingsDraft(values = mapOf("unit" to "celsius"), secrets = mapOf("apiKey" to "sk-SECRET-1234")),
        )

        // data class 的 toString 会把每个字段都印出来 —— 用它当探针
        val row = env.dao.rows.getValue("pub.test.demo").toString()
        assertFalse("数据库行里出现了密钥：$row", row.contains("sk-SECRET"))
        assertTrue(row, row.contains("celsius"))

        assertEquals("sk-SECRET-1234", env.secrets.get("plugin.pub.test.demo.apiKey"))
    }

    @Test
    fun `装配时把密钥拼回配置里`() = runTest {
        val env = Env()
        env.repo.install(withSettings())
        env.repo.saveSettings(
            "pub.test.demo",
            PluginSettingsDraft(values = mapOf("unit" to "celsius"), secrets = mapOf("apiKey" to "sk-1")),
        )

        val installed = env.repo.installed().single()

        assertEquals("sk-1", installed.settings.value("apiKey"))
        assertEquals("celsius", installed.settings.value("unit"))
    }

    @Test
    fun `卸载会清掉密钥`() = runTest {
        val env = Env()
        env.repo.install(withSettings())
        env.repo.saveSettings(
            "pub.test.demo",
            PluginSettingsDraft(secrets = mapOf("apiKey" to "sk-1")),
        )

        env.repo.uninstall("pub.test.demo")

        assertEquals(0, env.dao.count())
        assertNull(
            "不删的话用户以为卸载干净了，密钥还躺在设备上",
            env.secrets.get("plugin.pub.test.demo.apiKey"),
        )
    }

    // ---------------------------------------------------------------- 三态

    @Test
    fun `不传的敏感项保持不动`() = runTest {
        val env = Env()
        env.repo.install(withSettings())
        env.repo.saveSettings("pub.test.demo", PluginSettingsDraft(secrets = mapOf("apiKey" to "sk-old")))

        // 用户只改了单位，密钥框是空的（表单不回显），所以整条不传
        env.repo.saveSettings("pub.test.demo", PluginSettingsDraft(values = mapOf("unit" to "fahrenheit")))

        assertEquals(
            "传空串的话，用户改个温度单位就会掉 Key",
            "sk-old",
            env.secrets.get("plugin.pub.test.demo.apiKey"),
        )
        assertEquals("fahrenheit", PluginRepository.decodeSettings(env.dao.rows.getValue("pub.test.demo").settingsJson)["unit"])
    }

    @Test
    fun `显式传 null 是清除`() = runTest {
        val env = Env()
        env.repo.install(withSettings())
        env.repo.saveSettings("pub.test.demo", PluginSettingsDraft(secrets = mapOf("apiKey" to "sk-old")))

        env.repo.saveSettings("pub.test.demo", PluginSettingsDraft(secrets = mapOf("apiKey" to null)))

        assertNull(env.secrets.get("plugin.pub.test.demo.apiKey"))
    }

    @Test
    fun `传空白也是清除`() = runTest {
        val env = Env()
        env.repo.install(withSettings())
        env.repo.saveSettings("pub.test.demo", PluginSettingsDraft(secrets = mapOf("apiKey" to "sk-old")))

        env.repo.saveSettings("pub.test.demo", PluginSettingsDraft(secrets = mapOf("apiKey" to "   ")))

        assertNull(env.secrets.get("plugin.pub.test.demo.apiKey"))
    }

    @Test
    fun `非敏感项的空值被丢弃而不是存空串`() = runTest {
        val env = Env()
        env.repo.install(withSettings())

        env.repo.saveSettings("pub.test.demo", PluginSettingsDraft(values = mapOf("unit" to "")))

        assertNull(env.dao.rows.getValue("pub.test.demo").settingsJson)
    }

    @Test
    fun `界面传上来的过期字段被忽略`() = runTest {
        val env = Env()
        env.repo.install(withSettings())

        // 清单里没有 "gone" 这一项 —— 可能是插件升级后删掉的，
        // 而用户手上那份表单还是旧的
        env.repo.saveSettings(
            "pub.test.demo",
            PluginSettingsDraft(values = mapOf("unit" to "celsius", "gone" to "x")),
        )

        val stored = PluginRepository.decodeSettings(env.dao.rows.getValue("pub.test.demo").settingsJson)
        assertEquals(setOf("unit"), stored.keys)
    }

    // ---------------------------------------------------------------- 回显

    @Test
    fun `回显不返回密钥的值只回报填过没有`() = runTest {
        val env = Env()
        env.repo.install(withSettings())
        env.repo.saveSettings(
            "pub.test.demo",
            PluginSettingsDraft(values = mapOf("unit" to "celsius"), secrets = mapOf("apiKey" to "sk-1")),
        )

        val view = env.repo.settingsView("pub.test.demo")

        assertEquals("celsius", view.values["unit"])
        assertNull("密钥的值不该出现在回显里", view.values["apiKey"])
        assertEquals(setOf("apiKey"), view.configuredSecrets)
    }

    @Test
    fun `没填过的敏感项不在已配置集合里`() = runTest {
        val env = Env()
        env.repo.install(withSettings())

        assertTrue(env.repo.settingsView("pub.test.demo").configuredSecrets.isEmpty())
    }

    // ---------------------------------------------------------------- 默认值

    /**
     * 一份带默认值的清单：`unit` 声明了默认值，`apiKey` 是敏感项。
     *
     * [secretDefault] 为 true 时给敏感项也写一个默认值 —— 它**不该**被采用，
     * 校验层会为此报一条警告（见 `ManifestParserTest`）。
     * [default] 用来演「作者在新版本里改了默认值」。
     */
    private fun withDefault(
        secretDefault: Boolean = false,
        version: String = "1.0.0",
        default: String = "celsius",
    ): String {
        val secretPart = if (secretDefault) ",\"default\":\"sk-shipped\"" else ""
        return Manifests.declarative(
            version = version,
            settings = "\"apiKey\":{\"type\":\"string\",\"title\":\"密钥\",\"secret\":true$secretPart}," +
                "\"unit\":{\"type\":\"enum\",\"title\":\"单位\"," +
                "\"enum\":[\"celsius\",\"fahrenheit\"],\"default\":\"$default\"}",
            extraEntry = ""","auth":{"type":"bearer","settingKey":"apiKey"}""",
        )
    }

    @Test
    fun `装配时并进清单声明的默认值`() = runTest {
        val env = Env()
        env.repo.install(withDefault())

        assertEquals(
            "不并的话 {{settings.unit}} 会解析成空串 —— 而界面上看不出任何异常",
            "celsius",
            env.repo.installed("pub.test.demo")!!.settings.value("unit"),
        )
    }

    @Test
    fun `用户存下来的值优先于默认值`() = runTest {
        val env = Env()
        env.repo.install(withDefault())
        env.repo.saveSettings("pub.test.demo", PluginSettingsDraft(values = mapOf("unit" to "fahrenheit")))

        assertEquals("fahrenheit", env.repo.installed("pub.test.demo")!!.settings.value("unit"))
    }

    @Test
    fun `回显里也带着默认值`() = runTest {
        val env = Env()
        env.repo.install(withDefault())

        assertEquals(
            "界面显示的值必须就是实际会用到的值：各写一遍合并逻辑的话，" +
                "会出现「界面上选中了、请求里是空的」，而用户没法自己看出来",
            "celsius",
            env.repo.settingsView("pub.test.demo").values["unit"],
        )
    }

    @Test
    fun `清空一个非敏感项等于回到默认值`() = runTest {
        val env = Env()
        env.repo.install(withDefault())
        env.repo.saveSettings("pub.test.demo", PluginSettingsDraft(values = mapOf("unit" to "fahrenheit")))

        // 空值不落库（saveSettings 里的 filterValues），于是这一项回到「没有值」
        env.repo.saveSettings("pub.test.demo", PluginSettingsDraft(values = mapOf("unit" to "")))

        assertNull(env.dao.rows.getValue("pub.test.demo").settingsJson)
        assertEquals("celsius", env.repo.installed("pub.test.demo")!!.settings.value("unit"))
    }

    @Test
    fun `敏感项的默认值不参与`() = runTest {
        val env = Env()
        env.repo.install(withDefault(secretDefault = true))

        val plugin = env.repo.installed("pub.test.demo")!!

        assertNull(
            "清单是明文，而且会从网址下载、会被粘贴和分享 —— 凭据只能由用户自己填",
            plugin.settings.value("apiKey"),
        )
        assertTrue(
            "回显里也不能冒出清单里的那个默认凭据",
            "apiKey" !in env.repo.settingsView("pub.test.demo").values,
        )
    }

    @Test
    fun `保存过之后就不再跟着默认值走`() = runTest {
        val env = Env()
        env.repo.install(withDefault())
        // 用户顺手点了「保存设置」—— 存下的就是他当时看到的那个值
        env.repo.saveSettings("pub.test.demo", PluginSettingsDraft(values = mapOf("unit" to "celsius")))

        // 作者在新版本里把默认值改成了华氏
        env.now = 2_000L
        env.repo.install(withDefault(version = "2.0.0", default = "fahrenheit"))

        assertEquals(
            "保存过的值属于用户 —— 作者改默认值不该把它顶掉",
            "celsius",
            env.repo.installed("pub.test.demo")!!.settings.value("unit"),
        )
    }

    @Test
    fun `没保存过就一直跟着默认值走`() = runTest {
        val env = Env()
        env.repo.install(withDefault())

        env.now = 2_000L
        env.repo.install(withDefault(version = "2.0.0", default = "fahrenheit"))

        assertEquals(
            "用户没动过这一项，作者改默认值他就该跟着变 —— " +
                "所以「保存」这个动作必须真的有含义，不能在写入时把值悄悄裁掉",
            "fahrenheit",
            env.repo.installed("pub.test.demo")!!.settings.value("unit"),
        )
    }

    // ---------------------------------------------------------------- 升级

    @Test
    fun `升级保留用户配置`() = runTest {
        val env = Env()
        env.repo.install(withSettings(version = "1.0.0"))
        env.repo.saveSettings(
            "pub.test.demo",
            PluginSettingsDraft(values = mapOf("unit" to "celsius"), secrets = mapOf("apiKey" to "sk-mine")),
        )

        env.now = 2_000L
        val result = env.repo.install(withSettings(version = "1.1.0"))

        assertTrue(result.isUpgrade)
        assertEquals(
            "一次「修了个 bug」的更新把 Key 抹掉，用户只会看到 401，完全想不到是更新导致的",
            "sk-mine",
            env.secrets.get("plugin.pub.test.demo.apiKey"),
        )
        assertEquals(
            "celsius",
            PluginRepository.decodeSettings(env.dao.rows.getValue("pub.test.demo").settingsJson)["unit"],
        )
        assertEquals("1.1.0", env.repo.installed().single().manifest.version)
    }

    @Test
    fun `升级保留用户的启停选择`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative())
        env.repo.setEnabled("pub.test.demo", false)

        env.now = 2_000L
        env.repo.install(Manifests.declarative(version = "1.1.0"))

        assertFalse(
            "用户手动关掉的插件不该因为一次更新就自己打开",
            env.dao.rows.getValue("pub.test.demo").enabled,
        )
    }

    @Test
    fun `升级保留原来的安装时间`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative())

        env.now = 9_999L
        env.repo.install(Manifests.declarative(version = "2.0.0"))

        assertEquals(1_000L, env.dao.rows.getValue("pub.test.demo").installedAt)
        assertEquals(9_999L, env.dao.rows.getValue("pub.test.demo").updatedAt)
    }

    @Test
    fun `升级时清掉新清单里已经没有的密钥`() = runTest {
        val env = Env()
        env.repo.install(withSettings())
        env.repo.saveSettings("pub.test.demo", PluginSettingsDraft(secrets = mapOf("apiKey" to "sk-old")))

        // 新版本把 apiKey 改名成 token
        val renamed = Manifests.declarative(
            id = "pub.test.demo",
            version = "2.0.0",
            settings = """"token":{"type":"string","title":"令牌","secret":true}""",
        )
        env.repo.install(renamed)

        assertNull(
            "不删的话，旧别名会永远留在 KeyStore 里 —— 用户以为卸载干净了",
            env.secrets.get("plugin.pub.test.demo.apiKey"),
        )
    }

    @Test
    fun `升级时清掉新清单里已经没有的普通配置项`() = runTest {
        val env = Env()
        env.repo.install(withSettings())
        env.repo.saveSettings("pub.test.demo", PluginSettingsDraft(values = mapOf("unit" to "celsius")))

        env.repo.install(
            Manifests.declarative(id = "pub.test.demo", version = "2.0.0", settings = """"other":{"type":"string","title":"别的"}"""),
        )

        assertNull(env.dao.rows.getValue("pub.test.demo").settingsJson)
    }

    // ---------------------------------------------------------------- 权限变化

    @Test
    fun `升级扩大网络权限时单独报出来`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative(network = listOf("api.example.com")))

        val result = env.repo.install(Manifests.declarative(version = "1.1.0", network = listOf("*")))

        assertTrue(
            "插件可以借一次「小版本更新」把权限悄悄扩大，而用户以为只是修了个 bug",
            result.permissionChanges.any { it.contains("任意主机") },
        )
    }

    @Test
    fun `新增主机也算权限扩张`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative(network = listOf("api.example.com")))

        val result = env.repo.install(
            Manifests.declarative(
                version = "1.1.0",
                network = listOf("api.example.com", "evil.example.org"),
                baseUrl = "https://api.example.com",
            ),
        )

        assertTrue(result.permissionChanges.any { it.contains("evil.example.org") })
    }

    @Test
    fun `收紧权限不打扰用户`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative(network = listOf("*")))

        val result = env.repo.install(Manifests.declarative(version = "1.1.0", network = listOf("api.example.com")))

        // 每次更新都弹一堆「权限变了」会让人养成无脑点确认的习惯，
        // 真正危险的那次也就跟着被点掉了
        assertTrue(result.permissionChanges.isEmpty())
    }

    @Test
    fun `首次安装不报权限变化`() = runTest {
        val env = Env()
        val result = env.repo.install(Manifests.declarative(network = listOf("*")))

        assertTrue("第一次装的时候权限清单本来就会展示给用户", result.permissionChanges.isEmpty())
    }

    // ---------------------------------------------------------------- 摘要要说人话

    /**
     * 会出现在权限摘要里的英文枚举名 —— 它们**只该**以 `displayName` 的形式出现。
     *
     * 见 `FilesystemScope.displayName` 的 KDoc：**刻意不复用 `enum.name`**，
     * 「`Accessibility` 直接显示出来是英文，而这个列表是给用户判断
     * 『要不要给它这个权限』用的，必须一眼看懂」。
     *
     * 这条清单只写一份：两个测试各写一份的话，改一处忘另一处**没有任何症状**。
     */
    private val englishEnumNames = listOf(
        "ReadWrite", "Read", "None", // FilesystemScope
        "Clipboard", "Accessibility", "Sms", // DeviceCapability
        "Declarative", "Script", "Native", // PluginRuntimeKind
    )

    /**
     * 摘要会渲染到升级确认页上（`UpgradeWarning`），所以必须是中文。
     *
     * 这里曾经直接插值了枚举本身 —— 用户看到的是
     * `新增文件系统权限：ReadWrite`、`新增设备能力声明：Clipboard`。
     */
    @Test
    fun `权限摘要里不许出现英文枚举名`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative())

        val result = env.repo.install(
            Manifests.declarative(
                version = "1.1.0",
                extraPermissions = ""","filesystem":"readwrite","device":["clipboard"]""",
            ),
        )

        val text = result.permissionChanges.joinToString("\n")
        assertTrue("文件系统权限得说人话：\n$text", text.contains("读写"))
        assertTrue("设备能力得说人话：\n$text", text.contains("剪贴板"))
        englishEnumNames.forEach {
            assertFalse("权限摘要里出现了英文枚举名「$it」：\n$text", text.contains(it))
        }
    }

    /** 同上，这条管的是「运行形态变了」那一行（原来是 `declarative → native`）。 */
    @Test
    fun `运行形态变了也说中文`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative())

        val result = env.repo.install(Manifests.otherRuntime("pub.test.demo", "native"))

        val text = result.permissionChanges.joinToString("\n")
        assertTrue("运行形态得说人话：\n$text", text.contains("声明式"))
        assertTrue("运行形态得说人话：\n$text", text.contains("原生库"))
        englishEnumNames.forEach {
            assertFalse("权限摘要里出现了英文枚举名「$it」：\n$text", text.contains(it))
        }
    }

    // ---------------------------------------------------------------- 装之前问一句

    @Test
    fun `预览一份全新的清单 不需要确认`() = runTest {
        val env = Env()

        val preview = env.repo.preview(Manifests.declarative(network = listOf("*")))

        assertFalse(preview.isUpgrade)
        assertFalse("新装的权限清单本来就展示在安装页上，用户是在看过的前提下点的", preview.needsConfirmation)
    }

    @Test
    fun `预览升级扩权时要求确认`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative(network = listOf("api.example.com")))

        val preview = env.repo.preview(Manifests.declarative(version = "1.1.0", network = listOf("*")))

        assertTrue(preview.isUpgrade)
        assertTrue("这一步必须发生在写库之前", preview.needsConfirmation)
        assertTrue(preview.permissionChanges.any { it.contains("任意主机") })
    }

    @Test
    fun `预览升级但权限没变时不要求确认`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative(network = listOf("api.example.com")))

        val preview = env.repo.preview(
            Manifests.declarative(version = "1.1.0", network = listOf("api.example.com")),
        )

        // 是升级，但没多要东西。这里要是也停下来问，
        // 用户很快就会养成「看到这个框就点确认」的习惯
        assertTrue(preview.isUpgrade)
        assertFalse(preview.needsConfirmation)
    }

    @Test
    fun `预览收紧权限时不要求确认`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative(network = listOf("*")))

        val preview = env.repo.preview(
            Manifests.declarative(version = "1.1.0", network = listOf("api.example.com")),
        )

        assertTrue(preview.isUpgrade)
        assertFalse(preview.needsConfirmation)
    }

    @Test
    fun `预览不写任何东西`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative(version = "1.0.0", network = listOf("api.example.com")))
        val before = env.dao.rows.getValue("pub.test.demo")

        env.repo.preview(Manifests.declarative(version = "9.9.9", network = listOf("*")))

        // 「问一句」不能有副作用。有的话，用户只是把清单粘进来
        // 看了一眼就被升级了 —— 比不确认还糟
        assertEquals("预览把插件给升了", before, env.dao.rows.getValue("pub.test.demo"))
    }

    @Test
    fun `预览一份解析不了的清单时当作新装`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative(network = listOf("api.example.com")))

        val preview = env.repo.preview("{ 这不是 JSON")

        // 解析不了就没法比对。界面这时候正显示着校验错误，
        // 这里再报一次是重复的
        assertFalse(preview.isUpgrade)
        assertFalse(preview.needsConfirmation)
    }

    @Test
    fun `预览看到的扩张和真正装上时报的完全一致`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative(network = listOf("api.example.com")))
        val next = Manifests.declarative(
            version = "1.1.0",
            network = listOf("*"),
            extraPermissions = ""","shell":true""",
        )

        val preview = env.repo.preview(next)
        val result = env.repo.install(next)

        // 这两份东西由两个方法分别算出来，但它们描述的是同一件事。
        // 一旦分叉，用户确认的是一份、装进去的是另一份 ——
        // 那正是这套确认机制存在的意义所在
        assertEquals(result.permissionChanges, preview.permissionChanges)
    }

    // ---------------------------------------------------------------- 状态与启停

    @Test
    fun `启停写入数据库`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative())

        env.repo.setEnabled("pub.test.demo", false)
        assertFalse(env.dao.rows.getValue("pub.test.demo").enabled)

        env.repo.setEnabled("pub.test.demo", true)
        assertTrue(env.dao.rows.getValue("pub.test.demo").enabled)
    }

    @Test
    fun `关掉的插件仍然出现在状态列表里`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative())
        env.repo.setEnabled("pub.test.demo", false)

        val statuses = env.repo.statuses()

        assertEquals("关掉不等于消失，用户要能把它再打开", 1, statuses.size)
        assertFalse(statuses.single().entity.enabled)
    }

    @Test
    fun `清单坏掉的插件不出现在装配列表但出现在状态列表里`() = runTest {
        val env = Env()
        // 直接往表里塞一份坏清单，模拟「装进来之后宿主收紧了校验规则」
        env.dao.upsert(
            PluginEntity(
                id = "pub.bad.one",
                manifestJson = """{"id":"pub.bad.one"}""",
                installedAt = 1L,
                updatedAt = 1L,
            ),
        )

        assertTrue("拿不到 PluginManifest 就没法装配，跳过是没办法的事", env.repo.installed().isEmpty())

        val status = env.repo.statuses().single()
        assertTrue(
            "但必须在界面上显式列出来，否则用户看到的只是「它不见了」",
            status.isBroken,
        )
        assertNotNull(status.check)
    }

    @Test
    fun `装配列表按安装时间倒序`() = runTest {
        val env = Env()
        env.repo.install(Manifests.declarative(id = "pub.a.first"))
        env.now = 2_000L
        env.repo.install(Manifests.declarative(id = "pub.b.second"))

        // 列表页上用户想看到的是「我最近装了什么」。
        // 注意注册表合成时另按 id 排序 —— 两个顺序服务于不同的目的
        assertEquals(
            listOf("pub.b.second", "pub.a.first"),
            env.repo.statuses().map { it.id },
        )
    }

    // ---------------------------------------------------------------- MCP 工具清单缓存

    /** 一份成功的连接结果，写进库里。 */
    private suspend fun Env.connected(
        id: String = "pub.test.mcp",
        fingerprint: String = "http|https://api.example.com/mcp|auth=none|headers=",
    ): McpToolCache {
        val cache = Manifests.mcpCache(
            fingerprint = fingerprint,
            offered = 3,
            tools = listOf(Manifests.snapshot("echo", readOnly = true), Manifests.snapshot("write_note")),
        )
        repo.saveMcpCache(id, cache)
        return cache
    }

    @Test
    fun `MCP 插件的缓存存下来之后装配能读到`() = runTest {
        val env = Env()
        env.repo.install(Manifests.mcp())
        env.connected()

        val plugin = env.repo.installed("pub.test.mcp")!!

        assertEquals(
            "装配是同步的，工具清单只能从这一列读 —— 读不到的话工具列表就是空的",
            listOf("echo", "write_note"),
            plugin.mcpCache?.tools?.map { it.name },
        )
    }

    @Test
    fun `没连接过的 MCP 插件没有缓存`() = runTest {
        // **对照组**：上一条要守的是「存进去能读出来」，不是「mcpCache 永远非空」。
        // 少了这一条，一个「解码失败时返回一个空缓存」的实现也能通过上面那条 ——
        // 而那会让界面永远显示「已连接」，只是工具一个都没有
        val env = Env()
        env.repo.install(Manifests.mcp())

        assertNull(env.repo.installed("pub.test.mcp")!!.mcpCache)
    }

    @Test
    fun `缓存的每个字段都能原样往返`() = runTest {
        val env = Env()
        env.repo.install(Manifests.mcp())
        val saved = Manifests.mcpCache(
            fingerprint = "fp-x",
            fetchedAt = 1_700_000_000_000L,
            era = McpEra.Legacy,
            protocolVersion = "2024-11-05",
            offered = 5,
            tools = listOf(Manifests.snapshot("region_lookup")),
        )
        env.repo.saveMcpCache("pub.test.mcp", saved)

        val back = env.repo.installed("pub.test.mcp")!!.mcpCache!!

        // 整对象比对：漏一个字段（比如 era）都会在这里露出来，
        // 而单独断言每个字段的话，新加的字段会悄悄地不被覆盖
        assertEquals(saved, back)
        assertEquals("旧版年代要能存下来 —— 丢了的话，下次调用工具会先撞一次握手失败", McpEra.Legacy, back.era)
        assertEquals("对端报了 5 个但只认出 1 个，这个差要能算出来", 4, back.dropped)
    }

    @Test
    fun `派生字段在解码之后重新算出来`() = runTest {
        // headerParams 是**派生**的（从 inputSchema 的 x-mcp-header 扫出来），
        // 所以不落库。这条守的是「解码之后仍然扫得出来」—— 真把它存下来的话，
        // 对端更新 schema 之后这里会静默地停在旧值上，而且只在
        // 「对端改了 schema」时才发作
        val env = Env()
        env.repo.install(Manifests.mcp())
        env.repo.saveMcpCache(
            "pub.test.mcp",
            Manifests.mcpCache(
                tools = listOf(
                    Manifests.snapshot(
                        "region_lookup",
                        schema = """{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"}}}""",
                    ),
                ),
            ),
        )

        val descriptor = env.repo.installed("pub.test.mcp")!!.mcpCache!!.tools.single().toDescriptor()

        assertEquals(mapOf("region" to "Region"), descriptor.headerParams)
    }

    @Test
    fun `升级保留 MCP 缓存`() = runTest {
        // 一次「修了个 bug」的更新不该让用户重新连一次 ——
        // 而那个连接是发往第三方的网络请求
        val env = Env()
        env.repo.install(Manifests.mcp())
        env.connected()

        env.now = 2_000L
        env.repo.install(Manifests.mcp(version = "1.1.0"))

        assertEquals(
            listOf("echo", "write_note"),
            env.repo.installed("pub.test.mcp")!!.mcpCache?.tools?.map { it.name },
        )
    }

    @Test
    fun `第二次连接的结果会覆盖第一次`() = runTest {
        // 对端下线了一个工具之后，界面上不能还挂着它 ——
        // 用户会照着旧名字去问模型，然后收到一串「工具不存在」
        val env = Env()
        env.repo.install(Manifests.mcp())
        env.repo.saveMcpCache("pub.test.mcp", Manifests.mcpCache(tools = listOf(Manifests.snapshot("old_tool"))))
        env.repo.saveMcpCache("pub.test.mcp", Manifests.mcpCache(tools = listOf(Manifests.snapshot("new_tool"))))

        assertEquals(
            listOf("new_tool"),
            env.repo.installed("pub.test.mcp")!!.mcpCache?.tools?.map { it.name },
        )
    }

    @Test
    fun `缓存坏掉时当作没连接过 插件本身还是好的`() = runTest {
        // 两种容错策略**刻意不同**：缓存解不出来当「没连接过」（丢了刷新一下就好），
        // 清单解不出来是「插件坏了」（那是用户授权过的契约）。
        // 把缓存也当成「坏了」的话，一个字节的损坏就会让插件从列表里消失
        val env = Env()
        env.dao.upsert(
            PluginEntity(
                id = "pub.test.mcp",
                manifestJson = Manifests.mcp(),
                installedAt = 1L,
                updatedAt = 1L,
                mcpCacheJson = "{这不是 JSON",
            ),
        )

        val status = env.repo.statuses().single()

        assertNull("解不出来就当没连接过", status.mcpCache)
        assertFalse("插件本身是好的 —— 它只是需要重新连一次", status.isBroken)
        assertTrue(status.isMcp)
    }

    @Test
    fun `状态列表里也带着缓存`() = runTest {
        // 详情页从状态里读「上次拉取时间」和工具清单 —— 读不到的话，
        // 用户看到的是一个「显示连接过、列表却空着」的界面
        val env = Env()
        env.repo.install(Manifests.mcp())
        env.connected()

        val status = env.repo.status("pub.test.mcp")!!

        assertEquals(3, status.mcpCache?.offered)
        assertTrue(status.isMcp)
    }

    @Test
    fun `声明式插件的状态不认自己是 MCP`() = runTest {
        // **对照组**：isMcp 判错的话，详情页会给一个声明式插件显示
        // 「连接并刷新」按钮，而那个按钮对它毫无意义
        val env = Env()
        env.repo.install(Manifests.declarative())

        val status = env.repo.status("pub.test.demo")!!

        assertFalse(status.isMcp)
        assertNull(status.mcpCache)
    }

    @Test
    fun `保存缓存会更新修改时间`() = runTest {
        // 详情页上那个「上次拉取时间」来自缓存自己的 fetchedAt，
        // 而这一列是「这个插件最后一次被改动是什么时候」——
        // 两者含义不同，但都不能不动
        val env = Env()
        env.repo.install(Manifests.mcp())
        env.now = 5_000L

        env.connected()

        assertEquals(5_000L, env.dao.rows.getValue("pub.test.mcp").updatedAt)
    }

    // ---------------------------------------------------------------- 事务失败

    @Test
    fun `数据库写失败时回滚刚写的密钥`() = runTest {
        val dao = FakePluginDao()
        val secrets = InMemorySecretStore()
        val repo = PluginRepository(
            dao = dao,
            secrets = secrets,
            tx = NoTransactionRunner,
            workspaces = workspaces(),
            clock = { 1_000L },
        )
        repo.install(withSettings())

        val failing = PluginRepository(
            dao = object : PluginDao by dao {
                override suspend fun setSettings(id: String, settingsJson: String?, now: Long) =
                    throw IllegalStateException("磁盘满了")
            },
            secrets = secrets,
            tx = NoTransactionRunner,
            workspaces = workspaces(),
            clock = { 1_000L },
        )

        val error = runCatching {
            failing.saveSettings("pub.test.demo", PluginSettingsDraft(secrets = mapOf("apiKey" to "sk-1")))
        }.exceptionOrNull()

        assertNotNull("异常要往外抛，不能吞掉", error)
        assertNull(
            "数据库没写成却留下了密钥，用户会看到「配置好了但请求没带密钥」",
            secrets.get("plugin.pub.test.demo.apiKey"),
        )
    }

    // ---------------------------------------------------------------- 卸载

    /**
     * 卸载要清的是**三样**：数据库行、密钥、工作区。
     *
     * 前两样本来就有用例守着，这里补的是第三样。它漏掉的症状最难发现：
     * 界面上的插件没了，它留下的文件还在占存储，而且**再也没有入口能清它**
     * —— 插件记录已经没了，连详情页都进不去。
     */
    @Test
    fun `卸载会连工作区一起删掉`() = runTest {
        val env = Env()
        env.repo.install(withSettings(id = "pub.test.demo"))
        // 权限由调用方决定，这里直接给读写 —— 这条用例测的是「删干净」，
        // 不是权限（权限在 PluginWorkspaceTest 里）
        env.workspaces.open("pub.test.demo", FilesystemScope.ReadWrite)
            .write("cache/a.txt", "x")

        env.repo.uninstall("pub.test.demo")

        assertFalse(
            "插件记录删了、工作区还在：用户的存储被一份再也看不到的数据占着",
            File(tmp.root, "plugin-workspaces/pub.test.demo").exists(),
        )
    }

    @Test
    fun `卸载不碰别的工作区`() = runTest {
        val env = Env()
        env.repo.install(withSettings(id = "pub.test.a"))
        env.repo.install(withSettings(id = "pub.test.b"))
        env.workspaces.open("pub.test.b", FilesystemScope.ReadWrite).write("keep.txt", "b")

        env.repo.uninstall("pub.test.a")

        assertEquals(
            "删 a 的时候把 b 的工作区也删了",
            "b",
            env.workspaces.open("pub.test.b", FilesystemScope.ReadWrite).read("keep.txt"),
        )
    }
}
