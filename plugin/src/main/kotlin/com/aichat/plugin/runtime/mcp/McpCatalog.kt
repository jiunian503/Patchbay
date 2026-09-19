package com.aichat.plugin.runtime.mcp

import com.aichat.plugin.manifest.McpTransport
import com.aichat.plugin.runtime.AuthPlan
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl

/**
 * 一次 `tools/list` 的结果。
 *
 * [offered] 是**对端报出来的条目数**，[tools] 是其中能用的那些。两者不相等
 * 只有一个原因：有条目没有 `name`（没有名字的工具没法调用）。把这个差值
 * 显式地带出来，是因为「服务端说有 5 个、App 里只有 4 个」这类问题
 * 光看 [tools] 是查不出来的。
 */
data class McpDiscovery(val tools: List<McpToolDescriptor>, val offered: Int)

/**
 * 一份**缓存下来的**工具清单。
 *
 * ## 为什么必须有这个东西
 *
 * 因为 `PluginHost.tools()` 是**同步**的（它从 `ToolRegistryHolder.refresh()`
 * 里被调，而那一层被 ViewModel 直接调用），而 MCP 的工具清单只能**联网**
 * 拿到。在同步路径上做网络请求的后果是把主线程卡住 —— 而 `refresh()` 的
 * 触发点包括「用户点了安装」，也就是用户正盯着屏幕的时候。
 *
 * 所以装配读缓存、联网拉取是**另一条路**（`PluginHost.connect`）。这份东西
 * 就是两者之间传递的那个值，它会落进 `plugin` 表的一列。
 *
 * ## 为什么存的是快照而不是「工具对象」
 *
 * 因为 [McpToolDescriptor] 有一个**派生字段** `headerParams`（从 `inputSchema`
 * 里的 `x-mcp-header` 扫出来的）。派生字段不能存 —— 存了之后
 * 「schema 改了但派生字段没重算」就是一个只有在对端更新 schema 时才发作的
 * bug。存输入、算派生，和 `McpToolDescriptor` 自己的做法一致。
 */
@Serializable
data class McpToolCache(
    /**
     * 这份清单是**对着哪个配置**拉下来的。
     *
     * 装配时拿当前配置算一遍，不一致就当没有缓存 —— 否则用户把地址从
     * A 服务改成 B 服务之后，界面上还挂着 A 的工具清单，而他会照着
     * 那些名字去问模型，然后收到一串「工具不存在」。
     *
     * 算法见 [mcpCacheFingerprint]。**注意它不含任何密钥**。
     */
    val fingerprint: String,

    /** 拉取时刻（毫秒）。界面上要显示出来，让用户知道这份清单有多旧。 */
    val fetchedAt: Long,

    /** 对端属于哪一代。诊断用，会显示在插件详情页。 */
    val era: McpEra,

    /** 实际谈成的协议版本。 */
    val protocolVersion: String,

    /** 对端报出来的条目数（含被丢掉的那些）。 */
    val offered: Int,

    val tools: List<McpToolSnapshot>,
) {
    /** 因为没名字而被丢掉的工具数量。非 0 时界面必须解释一句。 */
    val dropped: Int get() = (offered - tools.size).coerceAtLeast(0)
}

/** 一个工具的**可序列化**快照。字段是 [McpToolDescriptor] 的输入部分。 */
@Serializable
data class McpToolSnapshot(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean = false,
)

/** 快照 → 运行时描述符。`headerParams` 在这里重新扫出来，不存。 */
fun McpToolSnapshot.toDescriptor(): McpToolDescriptor = McpToolDescriptor(
    name = name,
    description = description,
    inputSchema = inputSchema,
    readOnly = readOnly,
)

/** 描述符 → 快照。 */
fun McpToolDescriptor.toSnapshot(): McpToolSnapshot = McpToolSnapshot(
    name = name,
    description = description,
    inputSchema = inputSchema,
    readOnly = readOnly,
)

/**
 * 缓存指纹。
 *
 * ## 它回答的问题只有一个：**这还是同一台服务器吗？**
 *
 * 所以它覆盖的是**请求的声明形状**：传输方式、端点、插件级请求头的名字、
 * 认证方式、认证头名。这些全都来自清单 —— 都是「改了就意味着换了服务」
 * 的东西。
 *
 * ## [headerNames] 必须是**声明**的名字，不是渲染结果里的键
 *
 * 这一条很容易写错，而且错法很隐蔽：`authHeaders` 只在密钥**填了之后**
 * 才产出 `Authorization`，所以拿「渲染出来的头的键集合」当指纹的一部分，
 * 会让「用户把密钥补上」这件事**让一份本来有效的缓存失效**。
 * 症状是：用户填完密钥点保存，工具列表反而变空了 ——
 * 而他刚做的是一个补全配置的动作。
 *
 * 所以传进来的应当是 `entry.headers.keys` 加上认证计划会用的头名，
 * 两者都与密钥有没有填无关。
 *
 * ## 为什么**不含密钥**（连哈希也不含）
 *
 * 两条理由，第二条更重要：
 *
 * 1. 这个 App 的安全姿态是「数据库文件被拷走不该等于密钥泄漏」。往库里写
 *    任何密钥的函数值都是在削弱它 —— 而一个短口令的 SHA-256 是可以暴力反推的。
 * 2. 真正要防的那个 bug 是「地址改了、清单没变」。**换个 token 指向同一地址上的
 *    另一个账号**确实可能拿到不同的工具，但那是个罕见得多的场景，而且用户
 *    刚改完配置，本来就该刷新一次。
 *
 * 所以这里的取舍是：**宁可多刷一次，也不往库里写密钥的衍生物。**
 * 兜底手段是「界面上显示拉取时间 + 一个显眼的刷新按钮」（见 `PluginDetailScreen`）。
 *
 * ## 为什么返回可读字符串而不是哈希
 *
 * 因为它本来就不含密钥，哈希没有安全收益，却让「为什么缓存失效了」变得
 * 只能靠猜。可读指纹在日志和测试失败信息里能直接看出是哪一项变了。
 */
fun mcpCacheFingerprint(
    transport: McpTransport,
    endpoint: HttpUrl,
    headerNames: Collection<String>,
    auth: AuthPlan,
): String {
    val authPart = when (auth) {
        AuthPlan.None -> "auth=none"
        is AuthPlan.Bearer -> "auth=bearer"
        is AuthPlan.Header -> "auth=header:${auth.headerName}"
        is AuthPlan.Query -> "auth=query:${auth.queryName}"
    }
    val headers = headerNames.map { it.lowercase() }.sorted().joinToString(",")
    return "$transport|$endpoint|$authPart|headers=$headers"
}

/**
 * 缓存列的编解码。
 *
 * ## 解不出来时**当没有缓存**，而不是报错
 *
 * 这一列是宿主自己写的，但宿主会升级：将来 `McpToolSnapshot` 加一个必填字段，
 * 老缓存就解不出来了。那时候把整个插件判成坏的（用户会看到「清单无法解析」）
 * 是不对的 —— 该做的是**当作没连过**，让用户刷新一次。缓存是**可以丢的**，
 * 它只是省一次网络往返；而插件的清单（`manifest_json`）不可以丢。
 *
 * 和 `PluginRepository.decodeSettings` 同一个取向。
 *
 * ## 为什么是 public
 *
 * 因为**编解码的位置在 `:data`，而格式的知识在这里**。让 `:data` 自己
 * 持一份 `Json { }` 配置的话，两边的 `ignoreUnknownKeys` / `encodeDefaults`
 * 就可能不一致 —— 而那种不一致的症状是「写进去读不出来」，
 * 只在真正发生时才暴露。所以把格式收在一处，让持久化层只负责搬运字符串。
 */
object McpCacheCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(cache: McpToolCache): String = json.encodeToString(McpToolCache.serializer(), cache)

    fun decode(raw: String?): McpToolCache? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(McpToolCache.serializer(), raw) }.getOrNull()
    }
}
