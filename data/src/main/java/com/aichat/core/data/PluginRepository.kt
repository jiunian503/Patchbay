package com.aichat.core.data

import com.aichat.domain.secret.SecretStore
import com.aichat.plugin.host.InstalledPlugin
import com.aichat.plugin.manifest.FilesystemScope
import com.aichat.plugin.manifest.ManifestCheck
import com.aichat.plugin.manifest.ManifestParser
import com.aichat.plugin.manifest.ManifestProblem
import com.aichat.plugin.manifest.PluginManifest
import com.aichat.plugin.manifest.PluginRuntimeKind
import com.aichat.plugin.runtime.PluginSettings
import com.aichat.plugin.runtime.mcp.McpCacheCodec
import com.aichat.plugin.runtime.mcp.McpToolCache
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 安装一个插件的结果。
 *
 * ## 为什么不是一个 `Boolean`
 *
 * 因为「装上了」和「装上了但有问题」和「没装上，原因是第 3 个工具的参数写错了」
 * 是三件不同的事，而用户在装的时候需要看到第三种的具体内容。
 * 返回 [check] 而不是一个错误字符串，是为了让界面能**按路径逐条展示**
 * 校验问题，而不是把一整段文字糊在屏幕上。
 */
data class InstallResult(
    /** 装成功的插件 id。失败时为 null。 */
    val pluginId: String?,

    /** 清单校验结果（含警告）。失败时 [ManifestCheck.manifest] 为 null。 */
    val check: ManifestCheck,

    /** 之前已经有同 id 的插件，这次是**升级**。 */
    val isUpgrade: Boolean = false,

    /**
     * 升级时权限发生的变化。
     *
     * 单独列出来是因为这是升级里**唯一危险的部分**：插件可以借一次
     * 「小版本更新」把 `network` 从 `["api.example.com"]` 改成 `["*"]`，
     * 而用户以为自己只是更新了个修 bug 的版本。
     *
     * ## 界面**不要**拿它来弹确认框
     *
     * 因为这是**装完之后**才知道的，而界面拿到 [InstallResult] 的第一件事
     * 是跳到插件详情页 —— 用户根本没有机会看到这一段。
     * 真正要让用户确认，得在装之前问：[PluginRepository.preview]。
     *
     * 留着这个字段是因为它是「这次更新实际改了什么」的**记录**，
     * 不是征询同意的入口。两件事分开之后，才不会出现
     * 「以为弹过框了，其实框在跳转前一帧就被扔掉了」。
     */
    val permissionChanges: List<String> = emptyList(),
) {
    val ok: Boolean get() = pluginId != null
    val warnings: List<ManifestProblem> get() = check.warnings
}

/**
 * 「如果现在装这份清单，会发生什么」—— 在真正写库**之前**就能问出来的那部分。
 *
 * ## 为什么必须能在装之前问
 *
 * 因为「升级扩大了权限」这件事，只有在用户点确认**之前**告诉他才有意义。
 * 它原来跟着 [InstallResult] 一起返回，而界面拿到结果的第一件事是跳走 ——
 * 等于把「插件这次多要了 shell 权限」写进了一份用户永远读不到的日志里。
 *
 * 装之前问还有一个附带好处：用户看到扩张之后可以直接放弃，
 * 而不是「已经装上了，再去卸载」——后者要求他先意识到发生了什么。
 */
data class InstallPreview(
    /** 已存在同 id 的插件，这次会是**升级**而不是新装。 */
    val isUpgrade: Boolean,

    /**
     * 权限的**扩张**，逐条写给用户看。
     *
     * 空表示这次升级没有多要任何东西（收紧权限也算空，见 [permissionDiff]）。
     */
    val permissionChanges: List<String> = emptyList(),
) {
    /**
     * 必须先让用户明确点一次确认，才能写库。
     *
     * 只有权限**扩张**才需要。新装不需要 —— 那份权限清单本来就在安装页上
     * 展示着，用户是在看过的前提下点的「安装」。
     */
    val needsConfirmation: Boolean get() = permissionChanges.isNotEmpty()

    companion object {
        /** 没有同 id 的插件，也没有任何变化。 */
        val Fresh = InstallPreview(isUpgrade = false)
    }
}

/** 配置项回显。敏感项**只回报「有没有填过」**，不回报值。 */
data class PluginSettingsView(
    /** 非敏感项的当前值。 */
    val values: Map<String, String>,

    /** 敏感项里哪些已经填过了。界面据此显示「已配置」而不是空白。 */
    val configuredSecrets: Set<String>,
)

/**
 * 用户提交的配置。
 *
 * ## 敏感项的三态，和 `ProviderDraft.apiKey` 是同一套
 *
 * | 写法 | 含义 |
 * |---|---|
 * | key 不在 map 里 | 不动。表单里密钥框永远不回显，用户没碰就是这一种 |
 * | `null` 或空白 | 清除 |
 * | 非空 | 写入 |
 *
 * 这套语义容易写错的地方只有一个：**把「不在 map 里」当成「清除」**。
 * 那样用户改个温度单位就会把 API Key 抹掉，而且不会有任何提示。
 */
data class PluginSettingsDraft(
    /** 非敏感项，整体替换。空白值会被丢弃（不存空串）。 */
    val values: Map<String, String> = emptyMap(),

    /** 敏感项的三态，见类注释。 */
    val secrets: Map<String, String?> = emptyMap(),
)

/** 插件在管理界面上的完整状态。 */
data class PluginStatus(
    val entity: PluginEntity,

    /**
     * 清单校验结果。
     *
     * 为 null 表示**清单现在解析不了** —— 可能是装进来之后宿主收紧了校验规则，
     * 也可能是数据库被改坏了。这种插件在管理界面里必须显式列出来并说明原因：
     * 它已经不在工具列表里了，如果界面上也看不到，用户就只剩
     * 「这个插件好像没生效」这一个信息。
     */
    val check: ManifestCheck?,

    /**
     * MCP 工具清单缓存。**解不出来时为 null**（当作没连接过）。
     *
     * ## 为什么解析放在这里而不是界面层
     *
     * 因为「缓存格式」的知识属于 `:plugin`（见 `McpCacheCodec`），
     * 而界面层拿到一列 JSON 字符串时没有理由知道该怎么解它。
     * 在这里解一次，界面就只需要处理 `McpToolCache?` 这个类型 ——
     * 于是「格式改了」这件事的影响范围只有这一个文件。
     */
    val mcpCache: McpToolCache? = null,
) {
    val manifest: PluginManifest? get() = check?.manifest
    val id: String get() = entity.id
    val isBroken: Boolean get() = manifest == null

    /** 这个插件是 MCP 形态（工具清单来自对端，不在清单里）。 */
    val isMcp: Boolean get() = manifest?.runtime == PluginRuntimeKind.Mcp
}

/**
 * 已装插件的读写。把两张存储拼成一样东西：
 *
 * ```
 *   Room (plugin 表)                SecretStore (AndroidKeyStore)
 *   ├─ manifest_json（清单原文）      └─ 敏感配置项
 *   ├─ settings_json（非敏感配置）       别名 = plugin.<插件id>.<配置键>
 *   └─ enabled
 * ```
 *
 * ## 为什么存清单原文
 *
 * 见 [PluginEntity.manifestJson]。一句话：宿主的解析规则会演进，
 * 存原文能让已装插件跟着演进，不用用户重装。
 *
 * ## 升级为什么必须保留配置
 *
 * 用户填的 API Key 是他自己的东西。一次「修了个 bug」的版本更新把 Key 抹掉，
 * 用户下次对话时只会看到插件报 401，而且完全想不到是更新导致的。
 * 所以 [install] 走的是 upsert + 保留 `settingsJson` / 密钥，
 * 只把**新清单里已经没有的配置项**清掉。
 */
class PluginRepository(
    private val dao: PluginDao,
    private val secrets: SecretStore,
    private val tx: TransactionRunner,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observe(): Flow<List<PluginEntity>> = dao.observeAll()

    suspend fun list(): List<PluginEntity> = dao.list()

    suspend fun count(): Int = dao.count()

    // ------------------------------------------------------------------ 安装

    /**
     * 问一句「如果现在装这份清单，会发生什么」。
     *
     * **纯读**：不写库、不动密钥、不碰全文索引。调用方拿到结果之后
     * 可以放心地不装。
     *
     * ## 为什么单独一个方法，而不是让 [install] 返回两份东西
     *
     * 因为这两件事发生在**两个不同的时刻**：preview 在用户点「安装」之前，
     * install 在他点确认之后。挤进一个返回值里的话，界面就只能
     * 「先装、再看到扩权提示」—— 那时候插件已经拿到权限了。
     *
     * 清单解析不了时返回 [InstallPreview.Fresh]：调用方本来就已经
     * 拿着校验错误在展示了（界面上的实时校验），这里再报一次是重复的。
     */
    suspend fun preview(manifestJson: String): InstallPreview {
        val manifest = ManifestParser.parse(manifestJson).manifest
            ?: return InstallPreview.Fresh
        val existing = dao.get(manifest.id) ?: return InstallPreview.Fresh
        return InstallPreview(
            isUpgrade = true,
            permissionChanges = permissionDiff(existing.manifestJson, manifest),
        )
    }

    /**
     * 安装或升级。
     *
     * 校验不通过时**什么都不写** —— 半个插件比没有插件更难查。
     *
     * ## 调用方的责任：扩权必须先问过 [preview]
     *
     * 这个方法**不会**拦下「扩权但没有确认」的调用 —— 它没法弹框。
     * 所以任何安装入口都必须先 `preview()`，在
     * [InstallPreview.needsConfirmation] 为真时停下来问用户。
     * 目前唯一的入口是 `PluginInstallViewModel`，它就是这么做的。
     */
    suspend fun install(
        manifestJson: String,
        source: String = SOURCE_PASTE,
    ): InstallResult {
        val check = ManifestParser.parse(manifestJson)
        val manifest = check.manifest
            ?: return InstallResult(pluginId = null, check = check)

        val existing = dao.get(manifest.id)
        val now = clock()

        val entity = PluginEntity(
            id = manifest.id,
            manifestJson = manifestJson,
            // 升级时保留旧配置，但把新清单里已经没有的项删掉
            settingsJson = pruneSettings(existing?.settingsJson, manifest),
            // 升级不改变用户的启停选择 —— 用户手动关掉的插件不该因为
            // 一次更新就自己打开
            enabled = existing?.enabled ?: true,
            source = source,
            installedAt = existing?.installedAt ?: now,
            updatedAt = now,
            // MCP 的工具清单缓存**要保留**：一次「修了个 bug」的版本更新
            // 不该让用户重新连一次（而那个连接是发往第三方的网络请求）。
            // 如果新清单换了地址或认证方式，装配侧会因为指纹对不上而
            // 忽略这份缓存 —— 那是同一个问题的另一个答案，不需要在这里再判一次
            mcpCacheJson = existing?.mcpCacheJson,
        )

        tx.run { dao.upsert(entity) }

        // 密钥的清理放在数据库写成功之后：反过来的话，数据库写失败会留下
        // 「配置项还在、值已经没了」的插件，用户看到的是一堆空配置框
        removeOrphanSecrets(existing?.manifestJson, manifest)

        return InstallResult(
            pluginId = manifest.id,
            check = check,
            isUpgrade = existing != null,
            permissionChanges = existing
                ?.let { permissionDiff(it.manifestJson, manifest) }
                .orEmpty(),
        )
    }

    /** 卸载。连带清掉这个插件的全部密钥。 */
    suspend fun uninstall(id: String) {
        val existing = dao.get(id)
        tx.run { dao.delete(id) }
        existing?.manifestJson?.let { json ->
            val manifest = ManifestParser.parse(json).manifest ?: return@let
            for (key in manifest.settings.filterValues { it.secret }.keys) {
                secrets.remove(secretAlias(id, key))
            }
        }
    }

    /** 启停。关掉的插件不进工具列表，也不显示在模型看到的工具定义里。 */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled, clock())
    }

    // ------------------------------------------------------------------ 装配

    /**
     * 给 `PluginRegistry.build` 用的列表。
     *
     * 清单解析不了的插件**直接跳过**：拿不到 [PluginManifest] 就没法装配，
     * 这是没办法的事。但跳过不能是静默的 —— 界面侧要用 [statuses]
     * 把这类插件显式列出来（见 [PluginStatus.isBroken]）。
     */
    suspend fun installed(): List<InstalledPlugin> =
        dao.list().mapNotNull { toInstalled(it) }

    /** 单个插件的装配输入。MCP 的「连接并刷新」要用它。 */
    suspend fun installed(id: String): InstalledPlugin? =
        dao.get(id)?.let { toInstalled(it) }

    private suspend fun toInstalled(entity: PluginEntity): InstalledPlugin? {
        val manifest = ManifestParser.parse(entity.manifestJson).manifest ?: return null
        return InstalledPlugin(
            manifest = manifest,
            settings = settingsFor(entity, manifest),
            enabled = entity.enabled,
            // 解不出来当没有 —— 缓存是可以丢的，清单不是。见 `McpCacheCodec`
            mcpCache = McpCacheCodec.decode(entity.mcpCacheJson),
        )
    }

    /** 管理界面用的完整状态，包含那些解析不了的。 */
    suspend fun statuses(): List<PluginStatus> = dao.list().map { statusOf(it) }

    suspend fun status(id: String): PluginStatus? = dao.get(id)?.let { statusOf(it) }

    private fun statusOf(entity: PluginEntity): PluginStatus = PluginStatus(
        entity = entity,
        check = ManifestParser.parse(entity.manifestJson),
        mcpCache = McpCacheCodec.decode(entity.mcpCacheJson),
    )

    // ------------------------------------------------------------------ MCP 工具清单

    /**
     * 存下一次成功的 MCP 连接结果。
     *
     * ## 调用方的责任：**失败时不要调用它**
     *
     * 这个方法没有「清空」的用法 —— 刷新失败时正确的做法是什么都不做，
     * 让上一次成功拉到的清单继续生效（见 `McpRefresh` 的注释）。
     * 把旧缓存清掉的话，用户只是断了个网，插件就从「能用」变成
     * 「工具列表空空如也」。
     */
    suspend fun saveMcpCache(id: String, cache: McpToolCache) {
        dao.setMcpCache(id, McpCacheCodec.encode(cache), clock())
    }

    // ------------------------------------------------------------------ 配置

    /** 表单回显。敏感项只回报「填过没有」，**不回报值**。 */
    suspend fun settingsView(id: String): PluginSettingsView {
        val entity = dao.get(id) ?: return PluginSettingsView(emptyMap(), emptySet())
        val manifest = ManifestParser.parse(entity.manifestJson).manifest
            ?: return PluginSettingsView(decodeSettings(entity.settingsJson), emptySet())

        val secretsFilled = manifest.settings
            .filterValues { it.secret }
            .keys
            .filterTo(mutableSetOf()) { secrets.contains(secretAlias(id, it)) }

        return PluginSettingsView(
            values = decodeSettings(entity.settingsJson),
            configuredSecrets = secretsFilled,
        )
    }

    /**
     * 保存配置。
     *
     * 写入顺序和 `ProviderRepository` 一样是**刻意排的**：先写密钥，再写数据库行，
     * 数据库失败就把刚写的密钥回滚。反过来的话，中途失败会留下一个
     * 「说配置好了、实际取不到密钥」的插件。
     */
    suspend fun saveSettings(id: String, draft: PluginSettingsDraft) {
        val entity = dao.get(id) ?: return
        val manifest = ManifestParser.parse(entity.manifestJson).manifest ?: return

        val secretKeys = manifest.settings.filterValues { it.secret }.keys

        // 只认清单里真实存在的项：界面传上来的可能是过期的表单
        val written = mutableListOf<String>()
        for ((key, value) in draft.secrets) {
            if (key !in secretKeys) continue
            val alias = secretAlias(id, key)
            if (value.isNullOrBlank()) {
                secrets.remove(alias)
            } else {
                secrets.put(alias, value)
                written += key
            }
        }

        val nonSecret = draft.values
            // 只认清单里真实存在的项：界面传上来的可能是过期的表单
            // （插件升级后删掉了某一项，而用户手上那份表单还是旧的）
            .filterKeys { it in manifest.settings && it !in secretKeys }
            .filterValues { it.isNotBlank() }

        try {
            dao.setSettings(id, encodeSettings(nonSecret), clock())
        } catch (t: Throwable) {
            // 数据库没写成，把刚写进去的密钥撤掉，避免留下孤儿密钥
            for (key in written) runCatching { secrets.remove(secretAlias(id, key)) }
            throw t
        }
    }

    // ------------------------------------------------------------------ 内部

    private suspend fun settingsFor(entity: PluginEntity, manifest: PluginManifest): PluginSettings {
        val values = LinkedHashMap(decodeSettings(entity.settingsJson))
        for ((key, spec) in manifest.settings) {
            if (!spec.secret) continue
            secrets.get(secretAlias(entity.id, key))?.takeIf { it.isNotBlank() }
                ?.let { values[key] = it }
        }
        return PluginSettings(values)
    }

    /** 删掉新清单里已经不存在的非敏感项。null 表示没有可保留的。 */
    private fun pruneSettings(raw: String?, manifest: PluginManifest): String? {
        val kept = decodeSettings(raw).filterKeys { it in manifest.settings }
        return encodeSettings(kept)
    }

    /**
     * 删掉新清单里已经不存在的**密钥**。
     *
     * 不做的话，插件把 `apiKey` 改名成 `token` 之后，旧别名会永远留在
     * KeyStore 里 —— 用户以为「我卸载过那个插件了」，实际上他的密钥
     * 还躺在设备上。
     */
    private suspend fun removeOrphanSecrets(oldManifestJson: String?, newManifest: PluginManifest) {
        if (oldManifestJson == null) return
        val old = ManifestParser.parse(oldManifestJson).manifest ?: return
        for (key in old.settings.filterValues { it.secret }.keys) {
            if (key !in newManifest.settings) {
                secrets.remove(secretAlias(newManifest.id, key))
            }
        }
    }

    /**
     * 权限差异，只报**扩张**。
     *
     * 收紧权限（把 `*` 改成具体域名）不需要打扰用户 —— 那是变安全了，
     * 而每次更新都弹一堆「权限变了」会让人养成无脑点确认的习惯，
     * 真正危险的那次也就跟着被点掉了。
     */
    private fun permissionDiff(oldManifestJson: String, newManifest: PluginManifest): List<String> {
        val old = ManifestParser.parse(oldManifestJson).manifest ?: return emptyList()
        val before = old.permissions
        val after = newManifest.permissions
        val changes = mutableListOf<String>()

        // 网络权限单独判：`*` 和具体域名之间是**包含关系**，不是集合差集。
        // 从 `*` 收紧成具体域名时，集合差集算出来是「新增了 api.example.com」，
        // 而实际上是变安全了 —— 报出来只会让用户以为插件在扩权
        when {
            after.network.contains("*") && !before.network.contains("*") ->
                changes += "网络权限被扩大到任意主机（原来只能访问 ${before.network.joinToString("、").ifEmpty { "无" }}）"

            before.network.contains("*") -> Unit

            else -> {
                val added = after.network - before.network.toSet()
                if (added.isNotEmpty()) changes += "新增可访问的主机：${added.joinToString("、")}"
            }
        }

        if (!before.shell && after.shell) changes += "新增 shell 权限（可以执行系统命令）"
        if (!before.linuxEnv && after.linuxEnv) changes += "新增 Ubuntu 环境权限"
        if (before.filesystem == FilesystemScope.None && after.filesystem != FilesystemScope.None) {
            changes += "新增文件系统权限：${after.filesystem}"
        }
        val newDevice = after.device - before.device.toSet()
        if (newDevice.isNotEmpty()) changes += "新增设备能力：${newDevice.joinToString("、")}"

        if (old.runtime != newManifest.runtime) {
            changes += "运行形态从 ${old.runtime.name.lowercase()} 变成了 ${newManifest.runtime.name.lowercase()}"
        }
        if (old.entry.declarative?.baseUrl != newManifest.entry.declarative?.baseUrl) {
            newManifest.entry.declarative?.baseUrl?.let { changes += "接口地址变成了 $it" }
        }

        return changes
    }

    companion object {
        /**
         * 清单是从哪来的，写进 `plugin.source` 列。
         *
         * 这一列不是装饰：装了十几个插件之后想不起来「这个是从哪弄来的」时，
         * 它是唯一的线索。所以**每个入口都要老实报自己的来源** ——
         * 一律写成 `paste` 的话，这一列就等于不存在。
         *
         * 用字符串而不是枚举：这是个只读的诊断信息，将来加一个入口
         * （应用商店、二维码）不该逼着老版本 App 去认识一个新枚举值。
         */
        const val SOURCE_PASTE = "paste"

        /** 用户从剪贴板一键粘进来的。和 [SOURCE_PASTE] 分开 —— 两者的区别说明了很多事。 */
        const val SOURCE_CLIPBOARD = "clipboard"

        /** 随 APK 打包的示例。 */
        const val SOURCE_BUNDLED = "bundled"

        /** 用户用文件选择器挑的。 */
        const val SOURCE_FILE = "file"

        /** 从网络地址取的。还没实现。 */
        const val SOURCE_URL = "url"

        /**
         * 密钥在 SecretStore 里的别名。
         *
         * 由 id + 键名**推导**，不存进数据库 —— 存了就会和 KeyStore 的真实状态
         * 漂移（用户在别处清过 KeyStore、换机恢复等等），而漂移的后果是
         * 「界面说配好了、实际发出去的请求没有密钥」。
         */
        fun secretAlias(pluginId: String, settingKey: String): String = "plugin.$pluginId.$settingKey"

        private val settingsSerializer = MapSerializer(String.serializer(), String.serializer())

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        internal fun encodeSettings(values: Map<String, String>): String? =
            values.takeIf { it.isNotEmpty() }?.let { json.encodeToString(settingsSerializer, it) }

        /**
         * 解析配置。坏 JSON 一律当空 —— 这一列是宿主自己写的，
         * 但万一被改坏了，也不该让整个插件读不出来（用户会连「去改回来」的入口都没有）。
         */
        internal fun decodeSettings(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return runCatching { json.decodeFromString(settingsSerializer, raw) }
                .getOrDefault(emptyMap())
        }
    }
}
