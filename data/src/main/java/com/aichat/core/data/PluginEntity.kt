package com.aichat.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一个已安装的插件。
 *
 * ## 存的是清单**原文**，不是解析后的对象
 *
 * [manifestJson] 是用户装进来时那份 JSON 的原文。这样做有两个理由：
 *
 * 1. **宿主的解析规则会演进。** 今天不认识某个字段就报错，明天支持了 ——
 *    如果只存解析结果，已装插件永远拿不到新能力，用户得重新装一遍。
 *    存原文则下次启动重新解析，自动跟上。
 * 2. **报错要能指回原文。** 清单校验给出的路径（`$.tools[0].request.path`）
 *    只有对着原文才有意义。存解析结果的话，出问题时报的路径没有东西可以对照。
 *
 * 代价是每次装配都要重新解析一遍 JSON。清单只有几 KB，解析在毫秒级，
 * 而这个代价换来的是「宿主升级不需要用户重装插件」。
 *
 * ## 敏感配置项**不在这一行里**
 *
 * [settingsJson] 只存非敏感项。`SettingSpec.secret = true` 的那些
 * （API Key、token）走 [com.aichat.domain.secret.SecretStore]，
 * 别名规则见 [PluginRepository.secretAlias]。
 *
 * 和 `provider` 表是同一个理由：数据库文件被拷走不该等于密钥泄漏。
 * 差别在于 provider 只有**一个**密钥所以存了 `key_alias`，
 * 而插件可以有任意多个敏感项 —— 别名由 id + 键名**推导**出来，
 * 不需要额外存一列（存了反而会和 KeyStore 的真实状态漂移）。
 */
@Entity(tableName = "plugin")
data class PluginEntity(
    /** 清单里的 `id`。安装后不可变更 —— 它是权限授权的键。 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** 清单 JSON 原文。 */
    @ColumnInfo(name = "manifest_json")
    val manifestJson: String,

    /** 非敏感配置项的取值，JSON object 字符串。null / 空对象都表示没配。 */
    @ColumnInfo(name = "settings_json")
    val settingsJson: String? = null,

    /**
     * 用户的总开关。
     *
     * 关掉之后插件**不进注册表** —— 不只是不执行，连工具定义都不会出现在
     * 发给模型的请求里。一个被关掉的插件如果还留在工具列表里，模型会去调它，
     * 然后拿到「插件已停用」，白白多跑一轮。
     */
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    /** 从哪装进来的：`bundled` / `paste` / `file` / `url`。仅用于界面显示。 */
    @ColumnInfo(name = "source")
    val source: String = PluginRepository.SOURCE_PASTE,

    @ColumnInfo(name = "installed_at")
    val installedAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    /**
     * MCP 工具清单的缓存（`McpToolCache` 的 JSON）。
     *
     * ## 为什么必须落库，而不是只放在内存里
     *
     * 因为 MCP 的工具清单只能**联网**拿到，而装配是同步的（见 `PluginHost.tools`）。
     * 不缓存的话每次冷启动都要为每个 MCP 插件打一次网络请求 —— 那既慢
     * （用户会看到工具列表在几秒内逐步出现），又把「用户装了什么插件」
     * 这件事持续广播给第三方服务。
     *
     * ## 它和 [manifestJson] 的地位完全不同
     *
     * 清单是**用户授权过的契约**，丢了就得让用户重新装一次；
     * 这份缓存只是**省一次网络往返**，丢了刷新一下就好。所以：
     * 解不出来时当它不存在（见 `McpCacheCodec.decode`），
     * 而不是像清单那样把插件判成坏的。
     *
     * 它里面**不含密钥** —— 指纹刻意只用「哪些头、认证方式」，不用值。
     * 理由见 `mcpCacheFingerprint`。
     */
    @ColumnInfo(name = "mcp_cache_json")
    val mcpCacheJson: String? = null,
)
