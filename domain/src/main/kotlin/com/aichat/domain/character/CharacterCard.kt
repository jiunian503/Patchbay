package com.aichat.domain.character

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 角色卡解析失败。
 *
 * [message] 是**给用户看的一句话** —— 界面直接展示，不再包装。
 * 「导入失败」这种话帮不了他：他需要知道是「选错了文件」还是「这张卡坏了」，
 * 因为前者他换个文件就好，后者他得去找卡的原作者。
 */
class CharacterCardException(message: String) : Exception(message)

/**
 * 从一张角色卡文件里读出来的东西。
 *
 * 字段刻意和 `:data` 的 `CharacterDraft` **同形**（名字 / 简介 / 人设 / 词条），
 * 但**不依赖它** —— `:domain` 不认识 `:data`，依赖方向是反的。所以 `:app`
 * 拿到它之后做一次逐字段搬运，那一步没有判断、也就不会出错。
 */
data class ImportedCharacter(
    val name: String,
    /** 界面上的简介。**不进提示词**。来自卡里的 `creator_notes`。 */
    val description: String,
    /** 拼好的人设正文，见 [CharacterCardParser] 的字段映射表。 */
    val persona: String,
    /**
     * 开场白：新会话里角色先说的那一句。空串 = 卡里没写。
     *
     * 和 [persona] **分开放**是有意的：人设每轮都进提示词，开场白只在
     * 会话的第一句出现一次。并进人设的话，模型会把那句开场白当成设定，
     * 于是每轮都想再说一遍。
     */
    val firstMessage: String,
    /**
     * 备用开场白：和 [firstMessage] 一起当候选，新会话里随机挑一条说。
     *
     * 保原卡顺序，但**没有 `insertion_order` 那层语义** —— 这一列只被
     * 「挑一条」消费，不存在「先说什么后说什么」。空列表 = 卡里没写。
     */
    val alternateGreetings: List<String>,
    /** 世界书词条，**已按原卡的 `insertion_order` 排好序**。 */
    val entries: List<ImportedEntry>,
    /**
     * 这张卡里有、但这个 App 装不下的东西。**每条都是一句给人看的话。**
     *
     * 空列表 = 全导进来了。非空时界面要把它们显示出来 —— 静默丢掉
     * 「全局提示词替换」这类东西，用户会觉得「导入的东西不全」，却不知道
     * 缺了什么、更不知道是自己点错了还是这张卡本来就没有。
     */
    val warnings: List<String>,
)

/**
 * 一条世界书词条。
 *
 * **没有 `orderIndex`**：顺序由它在 [ImportedCharacter.entries] 里的位置决定，
 * 而 `:data` 的保存路径本来也是按列表下标重编号的（见 `CharacterRepository.save`）。
 * 这里再带一个序号字段，下游不用它，读代码的人却会以为要用它。
 */
data class ImportedEntry(
    val keys: List<String>,
    val content: String,
    val enabled: Boolean = true,
    val caseSensitive: Boolean = false,
)

/**
 * 把一张角色卡 PNG 读成 [ImportedCharacter]。
 *
 * ## 支持哪些格式
 *
 * | 格式 | 判别 | 说明 |
 * |---|---|---|
 * | V1 | 顶层直接有 `name` | 2023-05 之前生态里的事实标准 |
 * | V2 | 字段在 `data` 里，`spec == "chara_card_v2"` | 现在的主流 |
 * | V3 | 同 V2，`spec == "chara_card_v3"` | 只多了几个我们本来也不读的字段 |
 *
 * V3 走 V2 那条路：它的 `data` 是 V2 的超集，我们只读两边都有的字段。
 *
 * ## 字段映射
 *
 * | 卡里的字段 | 去哪儿 | 为什么 |
 * |---|---|---|
 * | `name` | 角色名 | |
 * | `creator_notes` | **界面简介** | 规格原文：`MUST NOT be used inside prompts` + `SHOULD be very discoverable` —— 正好就是「简介」这个位置 |
 * | `description` / `personality` / `scenario` / `mes_example` | **人设正文**（分节拼起来） | 这四段都是「发给模型看的设定」，和这个 App 的「人设」是同一个东西 |
 * | `first_mes` | **开场白** | 卡里那句「角色先说的话」。它和人设的**生命周期不同**（人设每轮都发，开场白只在会话第一句出现一次），所以是另一个字段而不是并进人设 |
 * | `alternate_greetings` | **备用开场白** | 「另外还能这么说」的那几条。和 `first_mes` 一起当候选，新会话里随机挑一条说 —— 挑哪条不在这里（是宿主层 `pickGreeting` 的事），这里只管读出来 |
 * | `character_book.entries[]` | 世界书词条 | `keys` / `content` / `enabled` / `insertion_order` / `case_sensitive` |
 *
 * 装不下的（全局提示词替换、条件触发、常驻条目、递归扫描）会进
 * [ImportedCharacter.warnings]，**不静默丢**。
 *
 * ## 为什么不用 `@Serializable` 定义 DTO
 *
 * 卡文件是**别人写的**：字段缺失、类型写错（`keys` 给了个字符串而不是数组）
 * 都很常见。`@Serializable` 遇到类型不符会抛异常，而那个异常信息
 * （`Unexpected JSON token at offset 412`）对用户毫无意义、对我们也定位不到字段。
 * 手读 [JsonObject] 能对每个字段**就地宽容**：类型不对就当没有，缺字段就用默认值。
 */
object CharacterCardParser {

    /** SillyTavern 用的两个关键字。**`ccv3` 优先**，见 [pickCardText]。 */
    private const val KEY_CHARA = "chara"
    private const val KEY_CCV3 = "ccv3"

    private val JSON = Json {
        // 卡文件里有重复键、无引号的键这类不规范写法（手写脚本生成的常见）
        isLenient = true
        ignoreUnknownKeys = true
    }

    /**
     * 卡里那四段「发给模型看的设定」，按这个顺序拼成人设。
     *
     * 顺序是照 V1 规范里字段的排列写的：先「是什么」，再「什么性格」，
     * 再「在什么场景」，最后才是「怎么说话」的示例。用户读到人设时
     * 这个顺序也最好理解。
     */
    private val SECTIONS = listOf(
        "角色设定" to "description",
        "性格" to "personality",
        "场景" to "scenario",
        "对话示例" to "mes_example",
    )

    /**
     * 解析一张角色卡 PNG。
     *
     * @throws CharacterCardException 任何一步失败，消息可直接展示给用户。
     */
    fun parse(bytes: ByteArray): ImportedCharacter {
        if (!Png.isPng(bytes)) {
            throw CharacterCardException("这不是 PNG 文件。角色卡要选一张 .png 图片。")
        }
        val encoded = pickCardText(Png.readTexts(bytes))
            ?: throw CharacterCardException(
                "这张 PNG 里没有角色卡数据。它可能只是一张普通图片 —— " +
                    "角色卡的设定存在图片的文本块里，普通图片没有。"
            )
        return mapCard(cardData(decodeJson(encoded)))
    }

    /**
     * 从所有文本块里挑出角色卡那一块。
     *
     * `ccv3` 优先于 `chara`：SillyTavern 导出的新卡会**两个都写**
     * （`ccv3` 放 V3、`chara` 放 V2），是同一张卡的两个版本。拿 `chara`
     * 那个也能用，但 `ccv3` 才是作者实际编辑的那份。
     *
     * 关键字比对**忽略大小写**：规范说它区分大小写，但生态里 `Chara`
     * 这样的写法确实存在，而它显然指的是同一个东西。
     *
     * 文本为空的那个不算数（否则一个空的 `ccv3` 会挡住有内容的 `chara`）。
     */
    private fun pickCardText(texts: List<PngText>): String? {
        val byKeyword = texts.associateBy { it.keyword.lowercase() }
        return byKeyword[KEY_CCV3]?.text?.takeIf { it.isNotBlank() }
            ?: byKeyword[KEY_CHARA]?.text?.takeIf { it.isNotBlank() }
    }

    /**
     * Base64 解码 → JSON 解析。
     *
     * 每一步失败都给一句**具体的**错：用户拿着这张卡去找作者、或者换一张卡，
     * 都需要知道是哪一步不对。
     */
    private fun decodeJson(encoded: String): JsonObject {
        val raw = decodeBase64(encoded)
            ?: throw CharacterCardException("角色卡数据不是有效的 Base64，这张卡可能已经损坏。")
        val text = raw.toString(StandardCharsets.UTF_8)
        val element = runCatching { JSON.parseToJsonElement(text) }.getOrNull()
            ?: throw CharacterCardException("角色卡数据不是有效的 JSON，这张卡可能已经损坏。")
        return element as? JsonObject
            ?: throw CharacterCardException("角色卡数据不是 JSON 对象，这张卡可能已经损坏。")
    }

    /**
     * Base64 解码，对生态里的几种写法都宽容：
     *
     * - **中间有换行 / 空格**：有些工具会折行。规范不允许，但很常见
     * - **URL-safe 字母表**（用 `-` `_` 代替 `+` `/`）：少数工具这么写
     * - **没有 `=` 补位**：Java 的解码器本来就接受，不用自己补
     *
     * 全解不开返回 `null`，由调用方报错。
     */
    private fun decodeBase64(text: String): ByteArray? {
        val cleaned = text.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        val decoder =
            if (cleaned.contains('-') || cleaned.contains('_')) Base64.getUrlDecoder()
            else Base64.getDecoder()
        return runCatching { decoder.decode(cleaned) }.getOrNull()
    }

    /**
     * 拿到「真正装字段的那个对象」。
     *
     * V2 / V3 把它放在 `data` 里，V1 直接放顶层 —— 所以判据是「`data` 是不是
     * 一个对象」，**不是** `spec` 的值。理由：有些卡的 `spec` 写错或干脆没有，
     * 但 `data` 里的内容是对的。按内容走比按声明走更宽容，而这里没有需要
     * 严格的地方（读不出来自然会在下面报「没有名字」）。
     */
    private fun cardData(root: JsonObject): JsonObject = root["data"] as? JsonObject ?: root

    private fun mapCard(data: JsonObject): ImportedCharacter {
        val name = data.str("name").trim()
        if (name.isEmpty()) {
            throw CharacterCardException("这张卡没有名字，没法导入。")
        }

        val book = data["character_book"] as? JsonObject
        val rawEntries = (book?.get("entries") as? JsonArray).orEmpty()
        val parsed = rawEntries.mapNotNull { (it as? JsonObject)?.let(::mapEntry) }

        // 原卡的插入顺序要保住：我们的世界书是按列表下标注入的，
        // 打乱顺序会让「先说什么后说什么」变了，而那是作者写卡时定的
        val entries = parsed.sortedBy { it.order }

        return ImportedCharacter(
            name = name,
            description = data.str("creator_notes").trim(),
            persona = composePersona(data),
            // 空串也是合法的（卡里没写开场白）—— 这里不做「有没有」的判断，
            // 那是下游的事：`:chat` 只管「有东西就落一条消息」
            firstMessage = data.str("first_mes").trim(),
            // 空白的丢掉：写卡工具留空字段、或者用空串占位都很常见。
            // 留着的话下游还会再滤一次（见 `pickGreeting`），但那是一次
            // 本可以不发生的兜底 —— 而且「候选里有几条空话」会体现在
            // 概率上（抽中空的那次角色就一言不发）
            alternateGreetings =
                data.strList("alternate_greetings").map { it.trim() }.filter { it.isNotEmpty() },
            entries = entries.map { it.entry },
            warnings = buildWarnings(data, book, rawEntries, parsed.size),
        )
    }

    /** 带 `insertion_order` 的中间形态 —— 排完序就把这个字段丢掉，见 [ImportedEntry]。 */
    private data class ParsedEntry(val order: Int, val entry: ImportedEntry)

    private fun mapEntry(json: JsonObject): ParsedEntry? {
        val keys = json.strList("keys").map { it.trim() }.filter { it.isNotEmpty() }
        val content = json.str("content").trim()
        // 没触发词或没内容的条目留着没意义 —— 它永远不会生效。原卡里可能有
        // 这种占位条目，导进来用户只会在列表里看到一条空白
        if (keys.isEmpty() || content.isEmpty()) return null
        return ParsedEntry(
            // 缺 insertion_order 的排到最后（而不是排到最前）：写卡工具没给这个
            // 字段时，通常意味着它也不在意顺序，那就不该插到别人前面去
            order = json.int("insertion_order") ?: Int.MAX_VALUE,
            entry = ImportedEntry(
                keys = keys,
                content = content,
                enabled = json.bool("enabled") ?: true,
                caseSensitive = json.bool("case_sensitive") ?: false,
            ),
        )
    }

    /**
     * 把卡里那四段「发给模型看的设定」拼成一段人设。
     *
     * ## 为什么保留小节标题
     *
     * 直接首尾相接的话，`description` 的末字和 `personality` 的首字会连在一起
     * （「……住在山上性格温和……」），模型分不清哪句属于哪段。标题还有个副作用
     * 是**给用户看的**：导入之后他在编辑页里能一眼看出哪段来自卡的哪个字段，
     * 想删哪段就删哪段。
     *
     * ## 空的段直接不出现
     *
     * 一张只有 `description` 的卡，不该在 persona 里留下三个空标题。
     */
    private fun composePersona(data: JsonObject): String =
        SECTIONS
            .map { (title, field) -> title to data.str(field).trim() }
            .filter { (_, body) -> body.isNotEmpty() }
            .joinToString("\n\n") { (title, body) -> "【$title】\n$body" }

    /**
     * 列出「这张卡有、我们装不下」的东西。
     *
     * 措辞都刻意写成**用户视角的后果**，不是字段名。「本版不支持
     * `post_history_instructions`」对他没有任何意义；「本版不导入全局提示词
     * 替换」他才知道自己少了什么。
     */
    private fun buildWarnings(
        data: JsonObject,
        book: JsonObject?,
        rawEntries: List<JsonElement>,
        keptEntries: Int,
    ): List<String> = buildList {
        // 开场白不在这里提了：`first_mes` 和 `alternate_greetings` 现在**都**
        // 装进来了（见 [mapCard]）。这条曾经是这里唯一一句「东西没导全」的话，
        // 现在它不再成立 —— 留着会让用户以为丢了东西，然后去找卡的原作者
        if (data.str("system_prompt").isNotBlank() ||
            data.str("post_history_instructions").isNotBlank()
        ) {
            add(
                "这张卡带了「全局提示词替换」，本版不导入 —— 那两段是替换整个系统" +
                    "提示词的，和这里的「人设」不是一回事。"
            )
        }

        val objects = rawEntries.mapNotNull { it as? JsonObject }
        val selective = objects.count { it.bool("selective") == true }
        if (selective > 0) {
            add(
                "有 $selective 条世界书在原卡里要求「主词和副词都命中」才生效，" +
                    "本版只按普通触发词处理（任一命中即生效）—— 这几条的触发会比原卡更宽。"
            )
        }
        val constant = objects.count { it.bool("constant") == true }
        if (constant > 0) {
            add(
                "有 $constant 条世界书在原卡里是「常驻」的（不靠触发词，每次都在），" +
                    "本版会当成普通触发词处理。"
            )
        }
        if (book?.bool("recursive_scanning") == true) {
            add("原卡的世界书开了「递归扫描」，本版不递归 —— 词条内容不会再触发别的词条。")
        }

        val dropped = rawEntries.size - keptEntries
        if (dropped > 0) {
            add("有 $dropped 条世界书是空的（没有触发词或没有内容），已跳过。")
        }
    }

    // ---- JsonObject 上的宽容读取 ----
    //
    // 全部返回「缺了就当作没有」：类型不符（`keys` 给了字符串、`enabled` 给了
    // 数字）走的是同一条路 —— 当它没写。理由见类注释最后一段。

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.strList(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
