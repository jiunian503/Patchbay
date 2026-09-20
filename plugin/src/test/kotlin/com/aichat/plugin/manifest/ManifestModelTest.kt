package com.aichat.plugin.manifest

import java.io.File
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单模型自己的不变量 —— 钉住那些「改了不会编译错、只会静默失效」的东西。
 *
 * ## 这个文件存在的理由
 *
 * `PluginManifest.kt` 里有两句**承诺**，在写下它们的时候都没有东西守着：
 *
 * 1. 「字段与 `plugin/manifest.schema.json` 一一对应。改这里必须同时改那份 schema……
 *    **两边漂移的话，作者会按文档写出宿主读不懂的清单**」
 * 2. 「[HttpMethod.wire] 显式写死，并由 `ManifestModelTest` 钉住每个值」
 *
 * 第 2 条里点名的测试**当时并不存在**（`plugin/src/test` 下没有这个文件），
 * 而 `wire` 是**给用户看的字符串** —— 确认弹窗里那句「将发起一次 POST 请求」
 * 就是它。所以这个类同时补上了那两句承诺。
 *
 * ## 为什么漂移是往「作者」那个方向最危险
 *
 * 清单是**给第三方写的东西**，作者手上只有 `manifest.schema.json`。宿主是严格
 * 解析的（`ManifestParser` 里 `ignoreUnknownKeys = false`，而且那是安全性质、
 * 不能为兼容性关掉）。于是任何「schema 允许、宿主不认」的差异，都会变成
 * 作者照着文档写、被宿主一句「不认识字段」打回 —— 而他没有任何办法自己看出来
 * 是文档错了。
 *
 * 反过来「宿主有、schema 没有」温和一些，但也会让作者根本不知道有那个字段。
 *
 * ## 为什么用描述符而不是手写一张字段表
 *
 * 手写一张「模型有哪些字段」的清单，等于把要守的东西抄了一遍 —— 它自己就会漂移，
 * 而且漂移时测试还是绿的。这里两边都是**机械取到的**：宿主侧走
 * `serializer().descriptor`，schema 侧走 `properties`。
 * 唯一手写的是 [SCHEMA_TO_CLASS] 那份路径映射（两边的命名本来就不同），
 * 而它漏了会有明确信号（见 `schema 和 Kotlin 模型的字段名一一对应`）。
 *
 * ## 读文件这件事有个坑
 *
 * 这个类读的是文件系统里的 `plugin/manifest.schema.json`，不是 classpath 资源。
 * Gradle 只看测试任务的**声明输入**，所以 `plugin/build.gradle.kts` 里必须
 * 把它声明成 `inputs.file(...)` —— 否则「schema 被改了」这个唯一要抓的场景，
 * 会让任务判 UP-TO-DATE 而**整个跳过**，测试以「通过」的形式安静地失效。
 * 那比没有测试更糟，因为它是绿的。
 */
class ManifestModelTest {

    // ------------------------------------------------------------ 枚举的序列化名字

    /**
     * `HttpMethod.wire` 和 `@SerialName` 必须一致，而且字面值也要钉住。
     *
     * ## 为什么是两条断言而不是一条
     *
     * 只断言「两处一样」是不够的：**两处同时写错同一个字**它抓不到。
     * 所以第一条保证一致性（解析认 `@SerialName`、显示用 `wire`，
     * 不一致就会出现「清单里写 POST、弹窗里说 Post」），
     * 第二条把五个字面值钉死。两条一起，「两处都写错」才会漏网 ——
     * 而那正是 KDoc 里那句「两处都写错同一个字才会漏网」的意思。
     */
    @Test
    fun `HttpMethod 的 wire 和序列化名字一致，字面值也被钉住`() {
        val descriptor = HttpMethod.serializer().descriptor
        assertEquals(
            "描述符里的元素个数和枚举常量个数对不上，下面的按下标比对就没有意义了",
            HttpMethod.entries.size,
            descriptor.elementsCount,
        )

        HttpMethod.entries.forEachIndexed { index, method ->
            assertEquals(
                "${method.name} 的 wire 和 @SerialName 不一致 —— " +
                    "解析认的是 @SerialName、显示用的是 wire，不一致就会出现" +
                    "「清单里写 POST、确认弹窗里说 Post」这种半截英文",
                descriptor.getElementName(index),
                method.wire,
            )
        }

        assertEquals(
            "字面值变了。它们会出现在确认弹窗和报错信息里，是用户看到的原文",
            listOf("GET", "POST", "PUT", "DELETE", "PATCH"),
            HttpMethod.entries.map { it.wire },
        )
    }

    /**
     * 每个枚举的序列化名字都被钉住。
     *
     * ## 为什么不能靠 `enum.name` 的小写
     *
     * 这些字符串是**落进清单文件里**的，作者会照着写、插件会被装到用户设备上。
     * 靠 `enum.name.lowercase()` 的话，有人把 `Stdio` 改成 `StdioTransport`
     * 就会让**所有已装插件**解析失败 —— 而且编译是过的。
     * 所以每个值都显式写 `@SerialName`，这里再把字面值钉一遍：
     * 改常量名不该动它，动了就该红。
     */
    @Test
    fun `枚举的序列化名字被钉住 —— 改常量名不该动它`() {
        assertEquals(
            listOf("declarative", "script", "mcp", "native"),
            serialNames(PluginRuntimeKind.serializer().descriptor),
        )
        assertEquals(
            listOf("none", "read", "readwrite"),
            serialNames(FilesystemScope.serializer().descriptor),
        )
        assertEquals(
            "设备能力这一组是给用户看权限清单用的，少一个就会「装了但授权页面没提」",
            listOf(
                "accessibility", "clipboard", "notification", "contacts",
                "calendar", "location", "camera", "sms",
            ),
            serialNames(DeviceCapability.serializer().descriptor),
        )
        assertEquals(
            listOf("string", "number", "boolean", "enum"),
            serialNames(SettingType.serializer().descriptor),
        )
        assertEquals(
            listOf("none", "bearer", "header", "query"),
            serialNames(AuthType.serializer().descriptor),
        )
        assertEquals(
            listOf("quickjs"),
            serialNames(ScriptRuntimeKind.serializer().descriptor),
        )
        assertEquals(
            listOf("stdio", "http", "sse"),
            serialNames(McpTransport.serializer().descriptor),
        )
    }

    /**
     * 同一个枚举里不能有两个常量共用一个序列化名字。
     *
     * 共用的话，「字符串 → 枚举」这一步是有歧义的 —— 解析出来是哪一个
     * 取决于实现细节，而清单文件里那个值到底代表什么就没有保证了。
     */
    @Test
    fun `枚举的序列化名字两两不同`() {
        val all = mapOf(
            "PluginRuntimeKind" to PluginRuntimeKind.serializer().descriptor,
            "FilesystemScope" to FilesystemScope.serializer().descriptor,
            "DeviceCapability" to DeviceCapability.serializer().descriptor,
            "SettingType" to SettingType.serializer().descriptor,
            "AuthType" to AuthType.serializer().descriptor,
            "ScriptRuntimeKind" to ScriptRuntimeKind.serializer().descriptor,
            "McpTransport" to McpTransport.serializer().descriptor,
            "HttpMethod" to HttpMethod.serializer().descriptor,
        )

        all.forEach { (name, descriptor) ->
            val names = serialNames(descriptor)
            assertEquals(
                "$name 里有两个常量共用了同一个序列化名字：$names",
                names.size,
                names.toSet().size,
            )
        }
    }

    // ------------------------------------------------------------ schema 与模型

    /**
     * schema 和 Kotlin 模型认识的字段名必须**完全一样**。
     *
     * 两个方向都要报出来，因为它们的后果不同：
     *
     * - **schema 多了** → 作者按 schema 写，宿主报「不认识字段」。这是最坏的一种，
     *   因为作者会去怀疑自己的 JSON 语法，而不是怀疑文档
     * - **schema 少了** → 作者根本不知道有那个字段，那个能力等于没发布
     */
    @Test
    fun `schema 和 Kotlin 模型的字段名一一对应`() {
        val schema = readSchema()
        val kotlinSide = kotlinClasses()

        // ① 先保证映射表本身没漏。Kotlin 那边是从描述符递归走出来的，
        //    所以「加了新类型忘了映射」会在这里暴露，而不是被静默跳过
        val mapped = SCHEMA_TO_CLASS.values.toSet()
        assertEquals(
            "Kotlin 模型里有类没被 SCHEMA_TO_CLASS 映射到，它们的一致性就没被检查",
            emptySet<String>(),
            kotlinSide.keys - mapped,
        )
        assertEquals(
            "SCHEMA_TO_CLASS 指向了不存在的类（改了类名没改映射？）",
            emptySet<String>(),
            mapped - kotlinSide.keys,
        )

        // ② 逐节点比对字段名集合
        SCHEMA_TO_CLASS.forEach { (path, className) ->
            val node = navigate(schema, path)
            val fromSchema = node["properties"]?.jsonObject?.keys.orEmpty()
            val fromKotlin = serialNames(kotlinSide.getValue(className)).toSet()

            assertEquals(
                "$path（$className）两边不一致 —— " +
                    "schema 多声明了 ${fromSchema - fromKotlin}，" +
                    "少声明了 ${fromKotlin - fromSchema}。" +
                    "多的那部分会让作者写出一份宿主读不懂的清单，" +
                    "少的那部分会让作者不知道有这个字段",
                fromKotlin,
                fromSchema,
            )
        }
    }

    /**
     * schema 里每个 `enum` 节点的取值，必须和 Kotlin 枚举的序列化名字**完全一样**。
     *
     * ## 为什么补这一条
     *
     * 五十六轮把 `ScriptRuntimeKind` 的 `Node` 换成 `QuickJs` 时发现：改一个枚举要同时动
     * **四处**（Kotlin 枚举 / schema 的 `enum` / schema 的 `default` / 示例清单），
     * 而其中 schema 那两处**一处都没有测试守** —— `枚举的序列化名字被钉住` 只钉 Kotlin
     * 那一侧，schema 里的取值是手工维护的。那次是靠人肉 grep 收的尾。
     *
     * 这个洞和 `schema 和 Kotlin 模型的字段名一一对应` 补的是同一类问题（§67：
     * 「schema 是一份没有守卫的承诺」），只是那次守的是**字段名**，这次守**取值**。
     * 后果也同构：
     *
     * - **schema 多一个值** → 作者照着写，`ManifestParser` 严格解析，直接失败
     * - **schema 少一个值** → 那个值过不了 schema 校验，等于对作者不存在
     *
     * ## 一个陷阱：这个 schema 里有个字段**就叫 `enum`**
     *
     * `SettingSpec` 有一个名为 `enum` 的字段（`type: "enum"` 时的取值列表），它的值是
     * `{"type":"array"}`。所以扫描时**只认「值是字符串数组」的 `enum`** ——
     * 不区分的话会把那个字段声明当成枚举约束，然后报一个看不懂的「多了一个节点」。
     */
    @Test
    fun `schema 里的 enum 和 Kotlin 枚举一一对应`() {
        val schema = readSchema()

        // ① 先保证映射表本身没漏 —— 两个方向都要报，理由同字段名那条
        val fromSchema = enumPaths(schema)
        assertEquals(
            "schema 里出现了没被 SCHEMA_TO_ENUM 映射的枚举节点，它们的取值一致性没被检查",
            emptySet<String>(),
            fromSchema - SCHEMA_TO_ENUM.keys,
        )
        assertEquals(
            "SCHEMA_TO_ENUM 指向了 schema 里不存在的路径（改了 schema 结构没改映射？）",
            emptySet<String>(),
            SCHEMA_TO_ENUM.keys - fromSchema,
        )

        // ② 逐节点比对取值
        SCHEMA_TO_ENUM.forEach { (path, descriptor) ->
            val fromKotlin = serialNames(descriptor).toSet()
            val declared = navigate(schema, path)["enum"]!!.jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()

            assertEquals(
                "$path 两边不一致 —— schema 多声明了 ${declared - fromKotlin}，" +
                    "少声明了 ${fromKotlin - declared}。" +
                    "多的那部分会让作者写出一份宿主直接解析失败的清单，" +
                    "少的那部分会让那个值对作者等于不存在",
                fromKotlin,
                declared,
            )
        }
    }

    /**
     * 枚举节点的 `default`（如果有）必须是它自己 `enum` 里的一个值。
     *
     * ## 守的是同一处改动里**最容易漏的那一半**
     *
     * §79 三 把 `ScriptRuntimeKind` 从 `Node` 换成 `QuickJs` 时，schema 里要改的是
     * `enum` **和** `default` 两处。只改 `enum` 的话，schema 会变成自相矛盾的 ——
     * 声明 `enum: ["quickjs"]` 而 `default: "node"`，一个不在取值范围内的默认值。
     *
     * 而它**不会以任何形式报错**：`default` 只是给作者看的文档，宿主根本不读它
     * （Kotlin 侧的默认值来自属性声明本身）。所以它坏了就是文档坏了，谁也发现不了 ——
     * 直到有作者照着那个 `default` 写了个 `node`，然后被宿主一句「解析失败」打回。
     *
     * ## 这条和上面那条是互补的
     *
     * 上面那条守「Kotlin 枚举 ↔ schema 的 `enum`」，这条守「schema 自己的
     * `enum` ↔ `default`」。两条一起，才把「改一个枚举要动四处」里的前两处封住
     * （剩下两处是 Kotlin 属性的默认值和示例清单，仍然靠人）。
     */
    @Test
    fun `枚举节点的 default 必须在 enum 里`() {
        val schema = readSchema()

        val bad = enumPaths(schema).mapNotNull { path ->
            val node = navigate(schema, path)
            val allowed = node["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            // 不用 `jsonPrimitive`（它在值不是基本类型时会抛）——
            // 这里只是「顺便看看有没有 default」，没有就不管
            val default = (node["default"] as? JsonPrimitive)?.content
            if (default != null && default !in allowed) {
                "$path：default 是 `$default`，但 enum 只允许 $allowed"
            } else {
                null
            }
        }

        assertEquals(
            "schema 里有枚举节点的 default 不在自己的取值范围内 —— 这份 schema 自相矛盾，" +
                "而 `default` 只是给作者看的文档（宿主不读它，Kotlin 侧的默认值来自属性声明），" +
                "所以它坏了不会有任何东西报错：",
            emptyList<String>(),
            bad,
        )
    }

    /**
     * schema 里每个对象节点都必须是**封闭**的（`additionalProperties: false`）。
     *
     * ## 为什么这条是必须的
     *
     * `ManifestParser` 的 KDoc 写着「清单格式是**封闭**的（`manifest.schema.json`
     * 里也是 `additionalProperties: false`）」，而宿主侧确实是处处严格的
     * （`ignoreUnknownKeys = false`，且那是安全性质）。
     *
     * 但 schema 一开始只有根、`permissions`、`tool` 三处写了这一行 ——
     * `entry`、四个 entry 段、`request`、`settings.*`、两个 `auth` 都没有。
     * 后果很具体：作者在 `entry.mcp` 里把 `headers` 拼成 `headerss`，
     * **schema 校验会通过**，然后被宿主一句「不认识字段」打回，
     * 而作者手上没有任何线索说明是 schema 漏写了。
     *
     * 所以这条断言守的是「作者手上的校验器和宿主的判断标准一致」。
     */
    @Test
    fun `schema 里每个对象节点都是封闭的`() {
        val schema = readSchema()

        SCHEMA_TO_CLASS.forEach { (path, className) ->
            val node = navigate(schema, path)
            assertEquals(
                "$path（$className）没有声明 additionalProperties: false。" +
                    "宿主是严格解析的，schema 不封闭的话，作者在这一层拼错字段名" +
                    "会通过 schema 校验、然后被宿主打回，而他没法知道是文档错了",
                "false",
                node["additionalProperties"].toString(),
            )
        }
    }

    /**
     * schema 的 `required` 必须等于 Kotlin 里**没有默认值**的那些字段。
     *
     * 这也是一种「作者手上的校验器和宿主不一致」：
     *
     * - schema 的 required **少了** → 作者能写出一份过 schema 校验、
     *   却被宿主判「缺字段」的清单
     * - schema 的 required **多了** → 作者被要求写一个本来有默认值的字段，
     *   等于把一个可选字段变成了必填
     *
     * `SerialDescriptor.isElementOptional(i)` 正好就是「这个属性有没有默认值」，
     * 所以两边都不用靠人维护。
     */
    @Test
    fun `schema 的 required 等于 Kotlin 里没有默认值的字段`() {
        val schema = readSchema()
        val kotlinSide = kotlinClasses()

        SCHEMA_TO_CLASS.forEach { (path, className) ->
            val node = navigate(schema, path)
            val fromSchema = node["required"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.toSet()
                .orEmpty()

            val descriptor = kotlinSide.getValue(className)
            val fromKotlin = (0 until descriptor.elementsCount)
                .filterNot { descriptor.isElementOptional(it) }
                .map { descriptor.getElementName(it) }
                .toSet()

            assertEquals(
                "$className：schema 的 required 是 $fromSchema，" +
                    "而 Kotlin 里没有默认值的是 $fromKotlin。" +
                    "少了 → 作者能写出过 schema 校验、却被宿主判「缺字段」的清单；" +
                    "多了 → 作者被要求写一个本来有默认值的字段",
                fromKotlin,
                fromSchema,
            )
        }
    }

    // ------------------------------------------------------------ 辅助

    private fun serialNames(descriptor: SerialDescriptor): List<String> =
        (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }

    /**
     * 递归找出 schema 里所有「值是字符串数组」的 `enum` 节点，返回它们的 `/` 路径。
     *
     * 只认字符串数组 —— 这个 schema 里有一个**字段名叫 `enum`**
     * （`SettingSpec` 的取值列表，值是 `{"type":"array"}`）。不区分的话会把它当成
     * 枚举约束，然后报一个「多了个节点」的错，而那个错会指向一个完全无关的地方。
     */
    private fun enumPaths(
        node: JsonElement,
        path: String = "",
        out: MutableSet<String> = mutableSetOf(),
    ): Set<String> {
        if (node !is JsonObject) return out

        val declared = node["enum"]
        if (declared is JsonArray && declared.all { it is JsonPrimitive && it.isString }) {
            out += path
        }
        node.forEach { (key, child) ->
            enumPaths(child, if (path.isEmpty()) key else "$path/$key", out)
        }
        return out
    }

    /**
     * 从 [PluginManifest] 的描述符出发，收集**模型里所有类**（简单名 → 描述符）。
     *
     * 走集合和映射的**值**一侧（`List<ToolSpec>` 里的 `ToolSpec`、
     * `Map<String, SettingSpec>` 里的 `SettingSpec`），但不进
     * `kotlinx.serialization.json.*` —— 那些是原样透传给模型的自由结构，
     * 没有「字段」可言（`ToolSpec.parameters`、`SettingSpec.default`、
     * `RequestSpec.body` 都是）。
     */
    private fun kotlinClasses(): Map<String, SerialDescriptor> {
        val out = LinkedHashMap<String, SerialDescriptor>()

        fun walk(descriptor: SerialDescriptor) {
            if (descriptor.serialName.startsWith("kotlinx.serialization.json.")) return

            when (descriptor.kind) {
                StructureKind.CLASS -> {
                    // 可空属性的描述符 serialName 带一个尾随 `?`
                    // （`com.aichat.plugin.manifest.DeclarativeEntry?`），
                    // 所以取简单名之前要先去掉它 —— 否则映射表里写的
                    // `DeclarativeEntry` 会和 `DeclarativeEntry?` 对不上，
                    // 表现为「Kotlin 里有类没被映射到」
                    val simple = descriptor.serialName.removeSuffix("?").substringAfterLast('.')
                    // 同一个类可能从两条路径到达（`AuthSpec` 就在声明式和 MCP
                    // 各出现一次）。放进去了就别再往下走，否则会重复遍历
                    if (out.putIfAbsent(simple, descriptor) == null) {
                        for (i in 0 until descriptor.elementsCount) {
                            walk(descriptor.getElementDescriptor(i))
                        }
                    }
                }
                // 集合看值、映射看值 —— 键一律是字符串，没有模型
                StructureKind.MAP -> walk(descriptor.getElementDescriptor(1))
                StructureKind.LIST -> walk(descriptor.getElementDescriptor(0))
                // 枚举和基本类型：没有「字段」这一层，上面的字面值测试管它们
                else -> Unit
            }
        }

        walk(PluginManifest.serializer().descriptor)
        return out
    }

    /**
     * 按 `$defs/a/b` 这样的路径取 schema 里的一个节点。
     *
     * 不解析 `$ref`：映射表里写的是**被引用的那个节点本身的路径**，
     * 所以 `entry.declarative` 这种 `$ref` 只在确认「引用关系还在」时才有意义 ——
     * 而字段名比对不需要经过引用。
     */
    private fun navigate(schema: JsonObject, path: String): JsonObject =
        path.split('/')
            .filter { it.isNotEmpty() }
            .fold(schema as kotlinx.serialization.json.JsonElement) { node, segment ->
                node.jsonObject[segment]
                    ?: error("schema 里没有路径 `$path` 的 `$segment` 这一段")
            }
            .jsonObject

    private fun readSchema(): JsonObject {
        val path = System.getProperty("patchbay.schemaFile")
        assertNotNull(
            "没拿到 schema 的路径。它由 plugin/build.gradle.kts 里的 " +
                "`systemProperty(\"patchbay.schemaFile\", …)` 传进来 —— " +
                "少了它这条测试会以「文件不存在」的形式失败，" +
                "看起来像 schema 丢了，实际是配置漏了",
            path,
        )

        val file = File(path!!)
        assertTrue("schema 文件不存在：${file.absolutePath}", file.isFile)
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private companion object {
        /**
         * schema 里的节点路径 → Kotlin 类的简单名。
         *
         * ## 为什么这份映射是手写的
         *
         * 因为两边的命名**本来就不同**：schema 那边是 `$defs/entryDeclarative`，
         * Kotlin 这边是 `DeclarativeEntry`；`settings` 是个 `Map<String, SettingSpec>`，
         * 所以它对应的节点是那个映射的**值** schema。
         *
         * 手写的东西会漂移，所以这里只留了「命名差异」这一层，
         * 字段名一个都没抄（那些从描述符里取）。漏映射会有明确信号：
         * `schema 和 Kotlin 模型的字段名一一对应` 里那两条集合断言会红。
         */
        val SCHEMA_TO_CLASS = mapOf(
            "" to "PluginManifest",
            "\$defs/permissions" to "PluginPermissions",
            "\$defs/entry" to "PluginEntry",
            "\$defs/entryDeclarative" to "DeclarativeEntry",
            "\$defs/entryScript" to "ScriptEntry",
            "\$defs/entryMcp" to "McpEntry",
            "\$defs/entryNative" to "NativeEntry",
            "\$defs/tool" to "ToolSpec",
            "\$defs/request" to "RequestSpec",
            "properties/settings/additionalProperties" to "SettingSpec",
            // AuthSpec 在清单里出现两次（声明式和 MCP 各一份），schema 也照着写了两份
            "\$defs/entryDeclarative/properties/auth" to "AuthSpec",
            "\$defs/entryMcp/properties/auth" to "AuthSpec",
        )

        /**
         * schema 里的枚举节点路径 → 对应的 Kotlin 枚举描述符。
         *
         * 和 [SCHEMA_TO_CLASS] 同构：只留「两边命名/位置不同」这一层，取值一个都不抄
         * （那些从描述符里取）。漏映射会有明确信号 —— `schema 里的 enum 和 Kotlin
         * 枚举一一对应` 里那两条集合断言会红。
         *
         * **`AuthType` 出现两次**（声明式和 MCP 各一个 auth 段），和 [SCHEMA_TO_CLASS]
         * 里 `AuthSpec` 出现两次是同一个原因。
         *
         * `HttpMethod` 也在这里 —— 它的 `@SerialName` 和 `wire` 的一致性由
         * `HttpMethod 的 wire 和序列化名字一致，字面值也被钉住` 那条测试管，
         * 这里管的是「schema 认不认这些值」。
         */
        val SCHEMA_TO_ENUM: Map<String, SerialDescriptor> = mapOf(
            "properties/runtime" to PluginRuntimeKind.serializer().descriptor,
            "properties/settings/additionalProperties/properties/type" to
                SettingType.serializer().descriptor,
            "\$defs/permissions/properties/filesystem" to FilesystemScope.serializer().descriptor,
            "\$defs/permissions/properties/device/items" to DeviceCapability.serializer().descriptor,
            "\$defs/entryDeclarative/properties/auth/properties/type" to
                AuthType.serializer().descriptor,
            "\$defs/entryMcp/properties/auth/properties/type" to AuthType.serializer().descriptor,
            "\$defs/entryScript/properties/runtime" to ScriptRuntimeKind.serializer().descriptor,
            "\$defs/entryMcp/properties/transport" to McpTransport.serializer().descriptor,
            "\$defs/request/properties/method" to HttpMethod.serializer().descriptor,
        )
    }
}
