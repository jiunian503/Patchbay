package com.aichat.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一个 LLM 服务商配置（BYOK）。
 *
 * ## 这里**没有** API Key
 *
 * 表里只有 [keyAlias] 和 [keyHint]：
 *
 * - [keyAlias] 是 [com.aichat.domain.secret.SecretStore] 里的别名，
 *   真正的密钥由 AndroidKeyStore 加密后存在别处（见 [AndroidKeystoreSecretStore]）。
 *   为 null 表示这个服务商还没填过 Key。
 * - [keyHint] 是密钥尾部几位（`…a1b2`），**只用于界面显示**，
 *   让用户能认出「我填的是哪一个 Key」，同时不泄漏完整值。
 *
 * 这么拆的代价是「读配置」要跨两张存储（Room + KeyStore），
 * 换来的是**数据库文件被拷走也拿不到 Key**。值得。
 *
 * ## 为什么 baseUrl / model 存在这一行里，而不是拆成两张表
 *
 * 一个服务商 = 一套连接参数 + 一个默认模型，用户不会给同一个 baseUrl
 * 配两套超时。拆表只会让「新建服务商」变成两次写入，徒增不一致的可能。
 * 模型列表将来如果需要（拉 /v1/models），再单开一张缓存表。
 */
@Entity(tableName = "provider")
data class ProviderEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** 用户起的名字，如「DeepSeek 官转」。空的话界面退回显示域名。 */
    @ColumnInfo(name = "name")
    val name: String,

    /** 用户填的原始地址，规范化逻辑在 `ProviderConfig.chatCompletionsUrl`。 */
    @ColumnInfo(name = "base_url")
    val baseUrl: String,

    /** 这个服务商默认用哪个模型。 */
    @ColumnInfo(name = "model")
    val model: String,

    /**
     * 采样温度。**null = 请求里根本不发这个字段**，用服务端自己的默认值。
     *
     * 见 [MIGRATION_5_6]：不给它填一个「1.0」再发出去，因为各家默认值不同，
     * 填了等于悄悄改掉服务端行为，而用户在界面上看不出来。
     */
    @ColumnInfo(name = "temperature")
    val temperature: Double? = null,

    /** 单次回复的最大 token 数。null = 不发，同上。 */
    @ColumnInfo(name = "max_tokens")
    val maxTokens: Int? = null,

    /**
     * 附加提示词。null / 全空白 = 不追加。
     *
     * ## v7 之后它的定位变了，但这一列留着
     *
     * 原来叫「系统提示词」，现在叫「附加提示词」—— 因为人设改由角色卡
     * （[CharacterEntity]）提供，这一列的位置变成「对所有人都生效的补充」：
     * 输出格式、语言要求、语气约束这一类。发送时的顺序是
     * **角色人设 → 世界书命中条目 → 这里**（见 `:domain` 的 `PromptComposer`）。
     *
     * 保留而不是废弃，有两个理由：已经填过的用户不该因为升级就丢掉自己的
     * 配置；而且「所有会话都要遵守的约束」确实需要一个地方放 ——
     * 每条都塞进角色卡里等于让用户重复维护十几遍。
     *
     * **跟着服务商走，不跟着会话走** —— 会话已经「黏住服务商」，所以
     * 「一个会话 = 一套配置」这个语义仍然成立。UI 上必须写明
     * 「用这个服务商的所有会话都生效」，否则用户会以为只影响当前会话。
     */
    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String? = null,

    /** SecretStore 里的别名。null = 还没填 Key。 */
    @ColumnInfo(name = "key_alias")
    val keyAlias: String? = null,

    /** 尾部几位，仅用于显示。 */
    @ColumnInfo(name = "key_hint")
    val keyHint: String? = null,

    /** 额外请求头（JSON object 字符串）。国内网关常要 `X-Api-Version` 之类。 */
    @ColumnInfo(name = "extra_headers")
    val extraHeadersJson: String? = null,

    @ColumnInfo(name = "connect_timeout_seconds")
    val connectTimeoutSeconds: Long = 30,

    @ColumnInfo(name = "read_timeout_seconds")
    val readTimeoutSeconds: Long = 300,

    @ColumnInfo(name = "include_usage")
    val includeUsage: Boolean = false,

    /**
     * 是否默认服务商。
     *
     * 用「表里最多一行为 1」而不是「另一张表存当前选中项」，是因为
     * 前者在一次事务里就能保证唯一性（先全部清零再置一），
     * 后者需要维护两处的一致性。
     */
    @ColumnInfo(name = "is_default")
    val isDefault: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
