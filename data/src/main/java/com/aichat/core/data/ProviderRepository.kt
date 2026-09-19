package com.aichat.core.data

import com.aichat.domain.secret.SecretStore
import com.aichat.network.ProviderConfig
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 新建 / 编辑服务商时的输入。
 *
 * 从 UI 表单直接映射过来，所以字段都是「用户填得出来的形态」，
 * 而不是 [ProviderEntity] 那种「库里存的形态」（比如这里给 Map，
 * 实体里存 JSON 字符串）。
 */
data class ProviderDraft(
    /** null = 新建。 */
    val id: String? = null,

    val name: String = "",
    val baseUrl: String,
    val model: String,

    /**
     * 三个采样参数，**三态里的「两态」**：`null` = 不发这个字段（跟随服务端默认），
     * 有值 = 发出去。这里没有「清除」这个独立状态 —— 把它改回 null 就是清除。
     *
     * 表单里三个框留空就等于 null，所以界面上「清空输入框」和「从没填过」
     * 是同一个结果，不需要额外处理。
     */
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val systemPrompt: String? = null,

    /**
     * API Key，**三态语义**：
     *
     * - `null`：不动。编辑表单里密钥框永远是空的（不回显密钥），
     *   用户没改就应该是这个值 —— 否则一保存就把 Key 抹掉了。
     * - `""`（空串）：清除。用户主动点了「删除密钥」。
     * - 非空：写入（覆盖）。
     *
     * 这三态是这个类里**唯一容易用错的地方**。把 null 当空串处理，
     * 用户改个超时时间就会丢掉 Key。
     */
    val apiKey: String? = null,

    /** 整体替换。表单持有完整的 headers 映射，所以没有「只改某一项」的需求。 */
    val extraHeaders: Map<String, String> = emptyMap(),

    val connectTimeoutSeconds: Long = 30,
    val readTimeoutSeconds: Long = 300,
    val includeUsage: Boolean = false,

    /** 保存后是否把它设为默认服务商。 */
    val makeDefault: Boolean = false,
)

/**
 * 一个可以直接用来发请求的服务商。
 *
 * [config] 为 null 表示**这个服务商还没填 API Key** —— 这不是错误，
 * 是用户建到一半的正常状态。UI 据此显示「去填 Key」而不是报错。
 */
data class ResolvedProvider(
    val id: String,
    val name: String,
    val model: String,
    val keyHint: String?,
    val config: ProviderConfig?,
    /** 采样参数，直接来自 `provider` 表。null = 请求里不发。 */
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val systemPrompt: String? = null,
) {
    val hasApiKey: Boolean get() = config != null
}

/** 服务商的显示名：用户起的名字优先，没起就用域名。 */
val ProviderEntity.displayName: String
    get() = name.ifBlank { baseUrl.substringAfter("://").substringBefore("/").ifBlank { baseUrl } }

/**
 * BYOK 配置的读写。把两张存储拼成一样东西：
 *
 * ```
 *   Room  (provider 表)          SecretStore (AndroidKeyStore)
 *   ├─ baseUrl / model / 超时     └─ API Key
 *   ├─ keyAlias ────────────────────┘ 别名指向
 *   └─ keyHint（尾部 4 位，仅显示）
 * ```
 *
 * ## 为什么密钥和配置要分两处存
 *
 * 合在一起最省事，但那样数据库文件（`/data/data/<pkg>/databases/aichat.db`）
 * 被拷走就等于 Key 泄漏 —— 备份、root、调试桥都能拿到它。
 * 分开之后，攻击者还需要拿到设备的 KeyStore（不可导出）。
 *
 * ## 写入顺序
 *
 * 两张存储没法放进同一个事务（一个是 SQLite，一个是 KeyStore），
 * 所以顺序是**刻意排的**：先写密钥，再写数据库行；数据库写失败就把刚写的
 * 密钥删掉。反过来先写数据库的话，中途失败会留下一个指向不存在密钥的行，
 * 用户看到「Key 已配置」但请求一直 401。
 */
class ProviderRepository(
    private val dao: ProviderDao,
    private val secrets: SecretStore,
    private val tx: TransactionRunner,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observe(): Flow<List<ProviderEntity>> = dao.observeAll()

    suspend fun list(): List<ProviderEntity> = dao.list()

    suspend fun get(id: String): ProviderEntity? = dao.get(id)

    /**
     * 新建或更新一个服务商。
     *
     * @return 服务商 id（新建时是新生成的）。
     */
    suspend fun save(draft: ProviderDraft): String {
        val baseUrl = draft.baseUrl.trim()
        require(baseUrl.isNotEmpty()) { "接口地址不能为空" }

        val model = draft.model.trim()
        require(model.isNotEmpty()) { "模型名不能为空" }

        // 采样参数只拦「明显是打错了」的。**不给温度设上界** —— 各家范围不一样
        // （OpenAI 系 0–2，本地推理服务常允许更高），卡一个我们自己定的上限
        // 会把合法配置拦在门外。负数和小等于 0 的 max_tokens 则一定是错的
        require(draft.temperature == null || draft.temperature >= 0.0) { "温度不能是负数" }
        require(draft.maxTokens == null || draft.maxTokens > 0) { "最大回复长度必须大于 0" }

        val id = draft.id ?: newId()
        val existing = dao.get(id)
        val now = clock()
        val alias = secretAlias(id)

        // ---- 第一步：密钥（在数据库事务之外） ----
        // 先把三态拆成两个明确的局部量。直接对着 draft.apiKey 写 when 的话，
        // 编译器没法在分支里做智能转换（因为它是个可空属性），
        // 只能到处补 `!!` —— 那样「三态」这件事就藏在感叹号里了，更容易写错。
        val newApiKey = draft.apiKey?.takeIf { it.isNotBlank() }
        val clearingApiKey = draft.apiKey != null && newApiKey == null

        if (newApiKey != null) {
            secrets.put(alias, newApiKey)
        }

        val keyAlias = when {
            // 保持原样
            draft.apiKey == null -> existing?.keyAlias
            // 清除
            clearingApiKey -> null
            // 写入
            else -> alias
        }
        val keyHint = when {
            draft.apiKey == null -> existing?.keyHint
            clearingApiKey -> null
            else -> hintOf(newApiKey.orEmpty())
        }

        val entity = ProviderEntity(
            id = id,
            name = draft.name.trim(),
            baseUrl = baseUrl,
            model = model,
            temperature = draft.temperature,
            maxTokens = draft.maxTokens,
            // 全空白的提示词存成 null，而不是空串：库里只有「没有提示词」和
            // 「有提示词」两种状态，别多出第三种。引擎那边还有一道 isNotBlank 兜底
            systemPrompt = draft.systemPrompt?.trim()?.takeIf { it.isNotEmpty() },
            keyAlias = keyAlias,
            keyHint = keyHint,
            extraHeadersJson = draft.extraHeaders
                .filterValues { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
                ?.let { headersJson.encodeToString(headersSerializer, it) },
            connectTimeoutSeconds = draft.connectTimeoutSeconds,
            readTimeoutSeconds = draft.readTimeoutSeconds,
            includeUsage = draft.includeUsage,
            isDefault = existing?.isDefault ?: false,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )

        // ---- 第二步：配置行 ----
        try {
            tx.run {
                dao.upsert(entity)

                when {
                    draft.makeDefault -> {
                        dao.clearDefault()
                        dao.markDefault(id, now)
                    }
                    // 一个服务商都没有默认的（首次安装、或刚把默认的删了）时，
                    // 自动认领 —— 保证「只要有服务商，就一定有默认可用」
                    dao.getDefault() == null -> dao.markDefault(id, now)
                }
            }
        } catch (t: Throwable) {
            // 数据库没写成，把刚写进去的密钥回滚掉，避免留下孤儿密钥
            if (newApiKey != null) runCatching { secrets.remove(alias) }
            throw t
        }

        // ---- 第三步：清除密钥（必须在数据库行更新之后） ----
        if (clearingApiKey) {
            secrets.remove(alias)
        }

        return id
    }

    /** 删除服务商，连带清掉它的密钥。删的是默认服务商时自动改选另一个。 */
    suspend fun delete(id: String) {
        val now = clock()
        tx.run {
            val wasDefault = dao.get(id)?.isDefault == true
            dao.delete(id)
            if (wasDefault) {
                // list() 已按 is_default DESC, updated_at DESC 排序，
                // 所以第一个就是「最该接任的」
                dao.list().firstOrNull()?.let { dao.markDefault(it.id, now) }
            }
        }
        secrets.remove(secretAlias(id))
    }

    /**
     * 设为默认。
     *
     * 「先全部清零再置一」必须在同一个事务里 —— 分开做的话，
     * 中途被杀会留下**一个默认都没有**的库，用户下次发消息直接报
     * 「没有配置服务商」。
     */
    suspend fun setDefault(id: String) {
        val now = clock()
        tx.run {
            dao.clearDefault()
            dao.markDefault(id, now)
        }
    }

    /** 解析成可用的配置。id 不存在返回 null。 */
    suspend fun resolve(id: String): ResolvedProvider? {
        val entity = dao.get(id) ?: return null
        return resolve(entity)
    }

    /** 解析默认服务商。没配任何服务商时返回 null。 */
    suspend fun resolveDefault(): ResolvedProvider? {
        val entity = dao.getDefault() ?: dao.list().firstOrNull() ?: return null
        return resolve(entity)
    }

    private suspend fun resolve(entity: ProviderEntity): ResolvedProvider {
        // keyAlias 为 null 就直接跳过解密 —— 别拿 null 去查 KeyStore
        val apiKey = entity.keyAlias
            ?.let { secrets.get(it) }
            ?.takeIf { it.isNotBlank() }

        return ResolvedProvider(
            id = entity.id,
            name = entity.displayName,
            model = entity.model,
            keyHint = entity.keyHint,
            temperature = entity.temperature,
            maxTokens = entity.maxTokens,
            systemPrompt = entity.systemPrompt,
            config = apiKey?.let {
                ProviderConfig(
                    baseUrl = entity.baseUrl,
                    apiKey = it,
                    extraHeaders = decodeHeaders(entity.extraHeadersJson),
                    connectTimeoutSeconds = entity.connectTimeoutSeconds,
                    readTimeoutSeconds = entity.readTimeoutSeconds,
                    includeUsage = entity.includeUsage,
                )
            },
        )
    }

    companion object {
        /** 密钥在 SecretStore 里的别名。用 id 而不是名字 —— 名字可以重复、可以改。 */
        fun secretAlias(providerId: String): String = "provider.$providerId"

        private val headersSerializer = MapSerializer(String.serializer(), String.serializer())

        private val headersJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** 尾部 4 位。太短的 Key 干脆不显示尾巴，免得「提示」比 Key 本身还长。 */
        internal fun hintOf(apiKey: String): String =
            if (apiKey.length <= 8) "…" else "…" + apiKey.takeLast(4)

        /**
         * 解析 headers。坏 JSON 一律当空 —— 这一列是用户手填的，
         * 解析失败不该让整个配置读不出来（用户会连「去改回来」的入口都没有）。
         */
        internal fun decodeHeaders(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return runCatching {
                headersJson.decodeFromString(headersSerializer, raw)
            }.getOrDefault(emptyMap())
        }
    }
}
