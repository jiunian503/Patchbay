package com.aichat.plugin.manifest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 插件清单 —— 所有集成的公共契约。
 *
 * 声明式、脚本、MCP、原生四种运行形态共用同一份清单。宿主只读这一份东西
 * 就能回答四个问题：**怎么加载、要什么权限、暴露哪些工具、缺哪些配置**。
 *
 * 字段与 `plugin/manifest.schema.json` 一一对应。改这里必须同时改那份 schema，
 * 它是给插件作者看的，而这里是给宿主看的 —— 两边漂移的话，
 * 作者会按文档写出宿主读不懂的清单。
 */
@Serializable
data class PluginManifest(
    /** 全局唯一标识，反域名风格。安装后不可变更 —— 它是权限授权的键。 */
    val id: String,
    val name: String,
    val version: String,
    val description: String? = null,
    val author: String? = null,
    val homepage: String? = null,
    val license: String? = null,

    /** 运行形态。决定 [entry] 里哪一段是必填的。 */
    val runtime: PluginRuntimeKind,

    val permissions: PluginPermissions = PluginPermissions(),
    val settings: Map<String, SettingSpec> = emptyMap(),
    val entry: PluginEntry = PluginEntry(),
    val tools: List<ToolSpec> = emptyList(),
)

/**
 * 运行形态。
 *
 * 用 `@SerialName` 显式写字符串而不是靠 `enum.name` 的小写 —— 后者一旦有人
 * 改了枚举常量名就会让**所有已装插件**解析失败。这和 `MessageStatus.wire`
 * 是同一个理由：落盘/落文件的标识不能和代码里的标识符绑在一起。
 */
@Serializable
enum class PluginRuntimeKind {
    /** 纯 HTTP 声明，零代码。宿主按 [ToolSpec.request] 拼请求。 */
    @SerialName("declarative")
    Declarative,

    /** 内置 JS 解释器跑插件自带的脚本。 */
    @SerialName("script")
    Script,

    /** 接入 MCP 生态，工具列表由对端动态提供。 */
    @SerialName("mcp")
    Mcp,

    /** 随 APK 打包的二进制。 */
    @SerialName("native")
    Native,
}

/**
 * 权限声明。
 *
 * ## 默认值的取向
 *
 * 每一项的默认值都是**最小权限**：不给网络、不给文件、不给 shell。
 * 插件不写 `permissions` 也能装，但它什么也做不了 —— 这个方向是对的：
 * 漏写权限的后果是「插件不工作」，而如果默认放开，后果是「用户不知道
 * 它做了什么」。前者用户会立刻发现并去查，后者不会。
 */
@Serializable
data class PluginPermissions(
    /**
     * 允许访问的主机白名单。
     *
     * 只写主机名（`api.open-meteo.com`），不写协议和路径 ——
     * 写成 `https://api.open-meteo.com/v1` 一律按非法处理，
     * 因为那会让人以为「路径也被限制了」，而实际匹配只看到主机名。
     *
     * `"*"` 表示任意主机，安装时按高危提示。
     *
     * **不支持 `*.example.com` 这种子域通配。** 它看起来很自然，但它是
     * 一次**静默的权限扩张**：`*.example.com` 会连 `evil.example.com` 一起放行，
     * 而用户看到的是一行很窄的声明。真需要覆盖多个子域就逐个列出来 ——
     * 多写几行的成本，比「以为限住了其实没限住」低得多。
     */
    val network: List<String> = emptyList(),

    /** 只能作用于插件自己的工作区目录，不给全盘访问。 */
    val filesystem: FilesystemScope = FilesystemScope.None,

    /** 是否允许执行 shell 命令。高危，默认拒绝。 */
    val shell: Boolean = false,

    val device: List<DeviceCapability> = emptyList(),

    /** 是否需要 Ubuntu 工作区。为 true 时首次调用要等环境启动。 */
    val linuxEnv: Boolean = false,
)

@Serializable
enum class FilesystemScope {
    @SerialName("none")
    None,

    @SerialName("read")
    Read,

    @SerialName("readwrite")
    ReadWrite,
}

/**
 * 给用户看的中文名。
 *
 * ## 为什么放在这里而不是各自的界面层
 *
 * 因为**同一份东西要在两个地方显示**：安装时的权限清单，和插件详情页。
 * 两边各写一份的话，迟早会出现「装的时候说『无障碍』、详情页说『辅助功能』」
 * 这种不一致 —— 用户会以为它们不是同一个权限。
 *
 * 也刻意**不复用 `enum.name`**：`Accessibility` 直接显示出来是英文，
 * 而这个列表是给用户判断「要不要给它这个权限」用的，必须一眼看懂。
 */
val DeviceCapability.displayName: String
    get() = when (this) {
        DeviceCapability.Accessibility -> "无障碍（读取屏幕内容并模拟操作）"
        DeviceCapability.Clipboard -> "剪贴板"
        DeviceCapability.Notification -> "通知"
        DeviceCapability.Contacts -> "联系人"
        DeviceCapability.Calendar -> "日历"
        DeviceCapability.Location -> "位置"
        DeviceCapability.Camera -> "相机"
        DeviceCapability.Sms -> "短信"
    }

val FilesystemScope.displayName: String
    get() = when (this) {
        FilesystemScope.None -> "无"
        FilesystemScope.Read -> "只读（限插件工作区）"
        FilesystemScope.ReadWrite -> "读写（限插件工作区）"
    }

val PluginRuntimeKind.displayName: String
    get() = when (this) {
        PluginRuntimeKind.Declarative -> "声明式（纯 HTTP 配置）"
        PluginRuntimeKind.Script -> "脚本"
        PluginRuntimeKind.Mcp -> "MCP"
        PluginRuntimeKind.Native -> "原生库"
    }

val SettingType.displayName: String
    get() = when (this) {
        SettingType.String -> "文本"
        SettingType.Number -> "数字"
        SettingType.Boolean -> "开关"
        SettingType.Enum -> "选项"
    }

val AuthType.displayName: String
    get() = when (this) {
        AuthType.None -> "不需要认证"
        AuthType.Bearer -> "Bearer 令牌（Authorization 头）"
        AuthType.Header -> "自定义请求头"
        AuthType.Query -> "查询参数"
    }

/**
 * 权限的人话清单，一行一条。
 *
 * ## 为什么返回列表而不是一段文字
 *
 * 因为界面要**逐条**展示（每条一行、高危的单独标红），而不是把一整段
 * 糊进一个 Text。安装时的权限确认是用户唯一一次真正做决定的机会，
 * 排版糊掉等于没给他看。
 *
 * ## 为什么这里不带 markdown
 *
 * 这些字符串会直接进 Compose 的 `Text`，而 `Text` 不渲染 markdown ——
 * 写 `**任意**` 的结果是屏幕上真的出现两个星号。要强调就用 `⚠️`，
 * 它在两边都能看懂。
 */
fun PluginPermissions.describe(): List<String> {
    val out = mutableListOf<String>()

    when {
        network.contains("*") -> out += "⚠️ 网络：可以访问任意地址（没有限制）"
        network.isEmpty() -> out += "网络：不访问网络"
        else -> out += "网络：只能访问 ${network.joinToString("、")}"
    }

    if (filesystem != FilesystemScope.None) {
        out += "文件：${filesystem.displayName}"
    }
    if (shell) out += "⚠️ 可以执行系统命令（shell）"
    if (linuxEnv) out += "⚠️ 需要 Ubuntu 环境"
    if (device.isNotEmpty()) {
        out += "⚠️ 设备能力：${device.joinToString("、") { it.displayName }}"
    }

    return out
}

/**
 * 这个插件有没有需要用户额外留意的权限。
 *
 * 安装界面据此决定要不要把权限区标红。**不看数量看性质**：
 * 一个只要 `api.example.com` 的插件和一个要 `*` 的插件，
 * 权限行数可能一样多。
 */
val PluginPermissions.isHighRisk: Boolean
    get() = network.contains("*") || shell || linuxEnv || device.isNotEmpty()

@Serializable
enum class DeviceCapability {
    @SerialName("accessibility")
    Accessibility,

    @SerialName("clipboard")
    Clipboard,

    @SerialName("notification")
    Notification,

    @SerialName("contacts")
    Contacts,

    @SerialName("calendar")
    Calendar,

    @SerialName("location")
    Location,

    @SerialName("camera")
    Camera,

    @SerialName("sms")
    Sms,
}

/**
 * 一个用户配置项。
 *
 * [default] 是 `JsonElement` 而不是 `String`：schema 里它可以是任意类型
 * （数字、布尔、枚举值），用字符串接会把 `"3"` 和 `3` 混起来，
 * 而下游拿去拼 URL 时这两者行为不同。
 */
@Serializable
data class SettingSpec(
    val type: SettingType,
    val title: String,
    val description: String? = null,
    val enum: List<JsonElement> = emptyList(),
    val default: JsonElement? = null,

    /**
     * 为 true 时值存入 Keystore，不落明文、不随配置导出。
     *
     * 注意它**不是**「校验」意义上的必填项：一个 secret 项没填值，
     * 插件该拿到的是「没有这个值」，而不是空字符串 —— 后者会被原样
     * 拼进 `Authorization: Bearer ` 里，报回来一个 401，
     * 而真正的原因是「用户没填」。
     */
    val secret: Boolean = false,
)

@Serializable
enum class SettingType {
    @SerialName("string")
    String,

    @SerialName("number")
    Number,

    @SerialName("boolean")
    Boolean,

    @SerialName("enum")
    Enum,
}

/**
 * 各运行形态的入口配置。
 *
 * 四段都存在、只有一段与 [PluginManifest.runtime] 匹配的那段是必填的。
 * 校验放在 [ManifestValidator] 里而不是用密封类表达，是因为清单是**外部输入** ——
 * 它可能同时写了 `declarative` 和 `mcp` 两段，也可能一段都没写。
 * 用密封类的话这些情况在解析阶段就崩了，而它们本该报成「清单有问题，
 * 第几行哪里不对」给插件作者看。
 */
@Serializable
data class PluginEntry(
    val declarative: DeclarativeEntry? = null,
    val script: ScriptEntry? = null,
    val mcp: McpEntry? = null,
    val native: NativeEntry? = null,
)

@Serializable
data class DeclarativeEntry(
    val baseUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val auth: AuthSpec = AuthSpec(),
)

/**
 * 认证方式。声明式（[DeclarativeEntry]）和 MCP（[McpEntry]）共用。
 *
 * [settingKey] 必须指向 [PluginManifest.settings] 里真实存在的项 ——
 * 写错的话密钥会**静默地不发送**，用户看到的是一个 401，
 * 而真正的原因是清单里少写了一个字母。所以这一条是校验错误，不是警告。
 *
 * 注意 [AuthType.Query] 对 MCP 没有意义（MCP 只有一个固定端点，
 * 认证走 `Authorization` 头是生态里的通行做法）。这里**不在类型上禁掉**它 ——
 * 那会让「一份清单同时声明 declarative 和 mcp 两段」这种合法情况没法写。
 * 真正的约束在 `ManifestParser` 里按运行形态分别校验。
 */
@Serializable
data class AuthSpec(
    val type: AuthType = AuthType.None,
    val settingKey: String? = null,
    val headerName: String? = null,
    val queryName: String? = null,
)

@Serializable
enum class AuthType {
    @SerialName("none")
    None,

    @SerialName("bearer")
    Bearer,

    @SerialName("header")
    Header,

    @SerialName("query")
    Query,
}

@Serializable
data class ScriptEntry(
    /** 入口 JS 文件相对路径。 */
    val main: String,
    val runtime: ScriptRuntimeKind = ScriptRuntimeKind.Node,
    val memoryLimitMb: Int = 128,
    val timeoutMs: Long = 30_000,
)

@Serializable
enum class ScriptRuntimeKind {
    @SerialName("node")
    Node,
}

/**
 * MCP 运行形态的入口配置。
 *
 * ## 为什么 `auth` 和声明式共用同一个 [AuthSpec]
 *
 * 因为**要解决的问题是同一个**：密钥放在哪、怎么放进请求。
 * 分开定义两份的话，`PluginHost` 里就会出现两个几乎一样的渲染函数，
 * 而「两处渲染」迟早会漂移 —— 比如一边处理了 `Bearer ` 后面那个空格、
 * 另一边忘了。共用一份，将来加一种认证方式（比如 mTLS）只改一处。
 *
 * 差别只在**参数校验的路径前缀**（`$.entry.declarative.auth` 还是
 * `$.entry.mcp.auth`），那是 `ManifestParser` 的事。
 *
 * ## `transport` 的三种取值在 Android 上的实际含义
 *
 * | 值 | 能不能跑 | 为什么 |
 * |---|---|---|
 * | `http` | 能 | 现代版 Streamable HTTP，单一端点、纯 POST |
 * | `sse` | 能 | 走**同一条**路径 —— 现代版把旧的「HTTP+SSE」合并进来了，响应是不是事件流由 `Content-Type` 决定，不需要配置 |
 * | `stdio` | **不能** | 它要求宿主启动一个本地子进程。Android 上既没有可用的运行时（§已定方向：不用 Node），也没有「插件自带二进制」这条路 |
 *
 * 所以 `stdio` 是一个**用户看得见的问题**，不是静默失败：清单作者按桌面端
 * 的习惯写 `stdio` 是很自然的事，而他要的答案是「换一个远程 MCP 服务」。
 */
@Serializable
data class McpEntry(
    val transport: McpTransport = McpTransport.Stdio,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val url: String? = null,

    /** 远程 MCP 服务基本都要 Bearer，所以这一项不是可选的装饰。 */
    val auth: AuthSpec = AuthSpec(),

    /**
     * 插件级静态请求头，所有 MCP 请求共用。
     *
     * 和 `entry.declarative.headers` 同一套规则（只支持 `{{settings.配置项名}}`）。
     * 有些 MCP 服务除了认证还要一个租户/路由头，没有这个字段的话作者只能
     * 放弃 —— 而那种服务用 declarative 又表达不了（工具清单在对端手里）。
     */
    val headers: Map<String, String> = emptyMap(),

    /** 白名单：只暴露这些 MCP 工具。留空表示全部暴露。 */
    val tools: List<String> = emptyList(),
)

@Serializable
enum class McpTransport {
    @SerialName("stdio")
    Stdio,

    @SerialName("http")
    Http,

    @SerialName("sse")
    Sse,
}

@Serializable
data class NativeEntry(
    /** `nativeLibraryDir` 下的文件名。必须是 `lib*.so` 命名，否则不会被打进 release 包。 */
    val library: String,
)

/**
 * 一个对模型暴露的工具。
 *
 * [description] 是**提示词**，不是注释：它直接决定模型会不会选对工具、
 * 参数填不填得对。写「查询天气」和写「查询指定经纬度的当前天气与未来三天
 * 预报。用户只说城市名时，先用 geocode_city 拿到经纬度再调用本工具，
 * 不要凭记忆猜坐标」的调用准确率差好几倍 —— 后者把**触发场景**写进去了。
 */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,

    /** JSON Schema 片段，原样透传给模型的 function calling。 */
    val parameters: JsonObject,

    /**
     * 调用前弹窗让用户确认。**三态，不是布尔。**
     *
     * | 值 | 含义 |
     * |---|---|
     * | 不写（null） | 作者没说，**宿主按 HTTP 方法判断**：GET 免确认，其余方法确认 |
     * | `true` | 一律确认 |
     * | `false` | 作者**明确担保**这个工具不改动服务端数据，免确认 |
     *
     * ## 为什么必须是三态
     *
     * 用 `Boolean = false` 时，「作者没说」和「作者明确说不用确认」是同一个值，
     * 于是宿主没法给一个安全的默认：要么对大量只读的 POST 接口（翻译、搜索、
     * embeddings —— 这些用 POST 是很常见的）弹一堆没必要的窗，要么对一个
     * 忘了声明的 DELETE 静默放行。
     *
     * 三态之后这两件事分开了：**没声明就按 RFC 9110 的「安全方法」判断**
     * （只有 GET/HEAD 是安全的），声明了 `false` 就是作者签字。
     *
     * 顺带修掉一个拼写陷阱：把字段名写成 `requireConfirmation` 时，
     * 严格解析会报未知键（见 [ManifestParser]）；就算哪天宽松处理了，
     * 结果也只是回到「宿主按方法判断」，而不是「本该弹窗的直接执行」。
     */
    val requiresConfirmation: Boolean? = null,

    /** 标记为高危。宿主可据此在自动模式下拒绝执行。 */
    val dangerous: Boolean = false,

    /** declarative 运行时专用：该工具对应的 HTTP 请求模板。 */
    val request: RequestSpec? = null,
)

/**
 * HTTP 请求模板。
 *
 * 所有字符串字段里的 `{{名字}}` 占位符都会在调用时替换：
 * `{{参数名}}` 取模型给的参数，`{{settings.键}}` 取用户配置。
 *
 * **替换不是字符串拼接。** 路径按段加、查询按参数加、请求体走 JSON 序列化，
 * 一律经过对应的 builder —— 否则模型给的一个 `/` 或 `&` 就能改变请求的
 * 目标路径或注入额外参数。详见 `DeclarativeTool`。
 */
@Serializable
data class RequestSpec(
    val method: HttpMethod = HttpMethod.Get,

    /**
     * 覆盖插件级 `entry.declarative.baseUrl` 里的**主机名**。不写就用 `baseUrl` 的。
     *
     * ## 为什么需要它
     *
     * 因为真实的 API 经常把端点分散在不同子域上：Open-Meteo 的预报在
     * `api.open-meteo.com`、地理编码在 `geocoding-api.open-meteo.com`；
     * 大量服务是 `api.x.com` + `auth.x.com`。一个插件只能有一个
     * `baseUrl` 的话，这些插件就只能拆成两个装。
     *
     * 内置的天气示例正好撞上这件事 —— `geocode_city` 一开始写成
     * `api.open-meteo.com/v1/geocoding`，真机上一跑就是 404：
     * 那个路径在那个主机上不存在。
     *
     * ## 为什么只让改主机名，不让改整个 URL
     *
     * 只改主机名时，scheme 和端口沿用 `baseUrl`。这是一条**安全性质**：
     * 允许整段 URL 的话，作者可以写 `http://` —— 把 `Authorization`
     * 连同用户的密钥明文发出去，而**安装界面上看到的那行权限声明完全不会变**。
     * 一次静默的降级比一次显式的越权危险得多。
     *
     * 主机名本身也**必须**在 `permissions.network` 里，否则调用时会被
     * `NetworkGuard` 拦下。校验层会在安装时就把这件事查出来，
     * 而不是等用户第一次问天气才收到一个看不懂的 403。
     */
    val host: String? = null,

    /**
     * 相对 baseUrl 的路径，支持 `{{参数名}}` 占位。
     *
     * 开头的 `/` 不会「回到根」—— baseUrl 里的路径前缀会被保留
     * （`baseUrl = https://x/v2` + `path = /users` → `https://x/v2/users`）。
     * 见 `DeclarativeTool.buildUrl`。
     */
    val path: String,

    /**
     * 查询参数模板，值支持 `{{参数名}}`。
     *
     * ## 值**必须写成字符串**
     *
     * `"forecast_days": 3` 会被拒绝，要写 `"forecast_days": "3"`。
     * 这不是洁癖：查询参数在网络上本来就是字符串，而统一成字符串之后
     * 才有可能往里放占位符（`"{{days}}"`）。一个数字位置上永远不可能出现
     * 占位符，于是「有些值能模板化、有些不能」这种半吊子规则会一直
     * 让人猜错。规则简单一点比宽松一点更有用。
     *
     * 写错时的报错是中文的、会指出字段名（见 `ManifestParser.structuralProblem`）——
     * kotlinx 原话 "String literal ... should be quoted" 既没说清是哪个字段，
     * 也没说该怎么改。
     */
    val query: Map<String, String> = emptyMap(),

    /** 请求体模板，值支持 `{{参数名}}`。 */
    val body: JsonElement? = null,

    /** 从响应 JSON 中提取结果的点分路径，如 `data.list`。 */
    val responsePath: String? = null,
)

/**
 * HTTP 方法。
 *
 * ## 为什么每个常量都带一个 [wire]
 *
 * 因为**要显示给人看的字符串必须是清单里那个写法**。作者在清单里写
 * `"method": "POST"`，而 Kotlin 的 `HttpMethod.Post.toString()` 给的是 `Post` ——
 * 直接把枚举插进报错信息或用户看的弹窗里，就会出现
 * 「将发起一次 Post 请求」「method 是 Post」这种半截英文。
 *
 * 更麻烦的是它和 `@SerialName` 是两处独立的事实：改枚举常量名不会影响解析
 * （解析认的是 `@SerialName`），但会让显示跟着变。所以 [wire] 显式写死，
 * 并由 `ManifestModelTest` 钉住每个值 —— 两处都写错同一个字才会漏网。
 */
@Serializable
enum class HttpMethod(val wire: String) {
    @SerialName("GET")
    Get("GET"),

    @SerialName("POST")
    Post("POST"),

    @SerialName("PUT")
    Put("PUT"),

    @SerialName("DELETE")
    Delete("DELETE"),

    @SerialName("PATCH")
    Patch("PATCH"),
}
