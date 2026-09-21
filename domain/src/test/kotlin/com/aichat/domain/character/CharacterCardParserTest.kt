package com.aichat.domain.character

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CharacterCardParser] 的行为。
 *
 * ## 为什么这个解析器值得写这么细的测试
 *
 * 它的输入是**别人写的文件**，而它的失败方式几乎全是「静默地把东西导错」：
 * 少一层 `data`、`keys` 里混进空串、条目顺序被打乱 —— 这些都不抛异常，
 * 只会在用户的角色卡里留下一份错的设定。而它们又全是纯字节 + 纯字符串的活，
 * 放在 `:domain` 就能秒级穷举。**这正是把它放在 `:domain` 的全部意义。**
 *
 * ## 夹具是**自己造的 PNG**，不是仓库里的真卡
 *
 * 不塞一张真卡当夹具：那样夹具是个二进制黑盒，看不出它到底测了哪个块；
 * 而真卡往往 `chara` 和 `ccv3` 两个块都有，想单独验「只有一个块」时反而没法用。
 * 这里的 [png] 只有十来行，每个用例都能指名道姓地说「我造的是只有 `chara` 块的 PNG」。
 *
 * ⚠️ 块末尾的 CRC **填的是 0**：解析器不校验它（理由见 [Png] 的注释），
 * 所以这里造的文件是「不合规但内容正确」的 —— 而这恰好也是它要能处理的情况之一。
 */
class CharacterCardParserTest {

    // ---- 夹具 ----

    private val SIGNATURE =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private val V1_CARD = """
        {
          "name": "老周",
          "description": "镇上的铁匠",
          "personality": "话少",
          "scenario": "打铁铺",
          "mes_example": "",
          "first_mes": ""
        }
    """.trimIndent()

    private val V2_CARD = """
        {
          "spec": "chara_card_v2",
          "spec_version": "2.0",
          "data": {
            "name": "苏晚",
            "description": "住在茶山的少女",
            "personality": "安静",
            "scenario": "上山问路",
            "mes_example": "{{user}}: 你好",
            "creator_notes": "照着小说捏的",
            "first_mes": "你来啦。"
          }
        }
    """.trimIndent()

    /** 一张只带给定块的 PNG。 */
    private fun png(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        chunks.forEach(out::write)
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    /** 一个 `tEXt` 块：`关键字 \0 正文`。 */
    private fun textChunk(keyword: String, text: String): ByteArray =
        chunk("tEXt", (keyword + "\u0000" + text).toByteArray(StandardCharsets.UTF_8))

    private fun chunk(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val size = body.size
        out.write(
            byteArrayOf(
                (size ushr 24).toByte(), (size ushr 16).toByte(),
                (size ushr 8).toByte(), size.toByte(),
            )
        )
        out.write(type.toByteArray(StandardCharsets.US_ASCII))
        out.write(body)
        out.write(byteArrayOf(0, 0, 0, 0)) // CRC 填 0，解析器不校验
        return out.toByteArray()
    }

    private fun base64(json: String): String =
        Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))

    /** 一张标准卡文件：一个 `chara` 块，内容是 Base64 的 [json]。 */
    private fun cardPng(json: String, keyword: String = "chara"): ByteArray =
        png(textChunk(keyword, base64(json)))

    private fun parseCard(json: String): ImportedCharacter = CharacterCardParser.parse(cardPng(json))

    private fun zlib(text: String): ByteArray {
        val deflater = Deflater()
        val out = ByteArrayOutputStream()
        try {
            deflater.setInput(text.toByteArray(StandardCharsets.UTF_8))
            deflater.finish()
            val buf = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }

    // ---- PNG 这一层 ----

    @Test
    fun `不是 PNG 时直说是文件类型不对`() {
        val e = assertThrows(CharacterCardException::class.java) {
            CharacterCardParser.parse("这只是一段文本".toByteArray(StandardCharsets.UTF_8))
        }
        assertTrue("要指出是选错了文件类型：${e.message}", e.message.orEmpty().contains("PNG"))
    }

    @Test
    fun `PNG 里没有角色卡块时说没有，而不是说坏了`() {
        // 一张合法 PNG，只是没有任何文本块 —— 等于一张普通图片
        val e = assertThrows(CharacterCardException::class.java) {
            CharacterCardParser.parse(png())
        }
        assertTrue("要说清是「没有角色卡数据」：${e.message}", e.message.orEmpty().contains("没有角色卡数据"))
    }

    @Test
    fun `块长度大到溢出时停止遍历，而不是抛数组越界`() {
        val bad = ByteArrayOutputStream().apply {
            write(SIGNATURE)
            // 长度写成 2^31-1：偏移加上它会溢出成负数，边界检查会被绕过
            write(byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
            write("tEXt".toByteArray(StandardCharsets.US_ASCII))
            write("chara\u0000AAAA".toByteArray(StandardCharsets.UTF_8))
        }.toByteArray()

        assertEquals(emptyList<PngText>(), Png.readTexts(bad))
    }

    @Test
    fun `iTXt 块也能读`() {
        // iTXt：关键字 \0 压缩标志(0) 压缩方法(0) 语言 \0 译名 \0 正文
        val body = "chara\u0000\u0000\u0000\u0000\u0000" + base64(V1_CARD)
        val parsed = CharacterCardParser.parse(png(chunk("iTXt", body.toByteArray(StandardCharsets.UTF_8))))
        assertEquals("老周", parsed.name)
    }

    @Test
    fun `zTXt 压缩块也能读`() {
        val body = "chara\u0000\u0000".toByteArray(StandardCharsets.UTF_8) + zlib(base64(V1_CARD))
        val parsed = CharacterCardParser.parse(png(chunk("zTXt", body)))
        assertEquals("老周", parsed.name)
    }

    // ---- Base64 这一层 ----

    @Test
    fun `Base64 坏掉时说这张卡可能损坏`() {
        val e = assertThrows(CharacterCardException::class.java) {
            CharacterCardParser.parse(png(textChunk("chara", "这不是 base64!!!")))
        }
        assertTrue("要说是数据坏了：${e.message}", e.message.orEmpty().contains("Base64"))
    }

    @Test
    fun `JSON 坏掉时说这张卡可能损坏`() {
        val e = assertThrows(CharacterCardException::class.java) {
            CharacterCardParser.parse(cardPng("{\"name\": "))
        }
        assertTrue("要说是数据坏了：${e.message}", e.message.orEmpty().contains("JSON"))
    }

    @Test
    fun `Base64 里插了换行也能解`() {
        val folded = base64(V1_CARD).chunked(40).joinToString("\n")
        // 直接塞进块里，**不要再经过 cardPng** —— 那个 helper 会再编码一次，
        // 于是测的就成了「双层 Base64」，而这条要测的恰恰是解码器的宽容性
        assertEquals("老周", CharacterCardParser.parse(png(textChunk("chara", folded))).name)
    }

    @Test
    fun `Base64 没有等号补位也能解`() {
        val raw = Base64.getEncoder().withoutPadding()
            .encodeToString(V1_CARD.toByteArray(StandardCharsets.UTF_8))
        assertFalse("夹具本身要真的没有补位，否则这条用例什么也没测", raw.endsWith("="))
        assertEquals("老周", CharacterCardParser.parse(png(textChunk("chara", raw))).name)
    }

    @Test
    fun `URL-safe 字母表也能解`() {
        val raw = Base64.getUrlEncoder()
            .encodeToString(V1_CARD.toByteArray(StandardCharsets.UTF_8))
        assertTrue(
            "夹具的 Base64 要真的用到 URL-safe 特有的字符，否则这条用例只是重跑上面那条",
            raw.contains('-') || raw.contains('_'),
        )
        assertEquals("老周", CharacterCardParser.parse(png(textChunk("chara", raw))).name)
    }

    // ---- V1 / V2 的字段映射 ----

    @Test
    fun `V1 卡：顶层字段直接拼成人设，简介留空`() {
        val card = parseCard(V1_CARD)
        assertEquals("老周", card.name)
        assertEquals("V1 没有 creator_notes，简介就该是空的", "", card.description)
        assertTrue("设定要带小节标题：${card.persona}", card.persona.contains("【角色设定】\n镇上的铁匠"))
        assertTrue(card.persona.contains("【性格】\n话少"))
        assertTrue(card.persona.contains("【场景】\n打铁铺"))
        // 夹具里 first_mes 是空的。空串也是合法结果 —— 下游据此判断
        // 「这张卡没开场白」，而不是去判 null
        assertEquals("", card.firstMessage)
    }

    @Test
    fun `V2 卡：creator_notes 进简介，且不进人设`() {
        val card = parseCard(V2_CARD)
        assertEquals("苏晚", card.name)
        assertEquals("照着小说捏的", card.description)
        assertFalse(
            "creator_notes 是给人看的，规格明说不该进提示词：${card.persona}",
            card.persona.contains("照着小说捏的"),
        )
        // 开场白走**另一个字段**。并进人设的话，模型会把那句「你来啦。」
        // 当成设定，于是每轮都想再说一遍
        assertEquals("你来啦。", card.firstMessage)
        assertFalse("开场白不该进人设：${card.persona}", card.persona.contains("你来啦"))
    }

    @Test
    fun `四段按固定顺序拼，空的段不留下空标题`() {
        val card = parseCard(V1_CARD)
        // 夹具里 mes_example 是空的
        assertFalse("空的段不该留下一个空标题：${card.persona}", card.persona.contains("【对话示例】"))

        val positions = listOf("【角色设定】", "【性格】", "【场景】").map { card.persona.indexOf(it) }
        assertTrue("三段都该在：${card.persona}", positions.all { it >= 0 })
        assertEquals("三段的先后要按固定顺序", positions.sorted(), positions)
    }

    @Test
    fun `名字两端的空白会被去掉`() {
        assertEquals("苏晚", parseCard("""{"name":"  苏晚  ","description":"x"}""").name)
    }

    // ---- 世界书 ----

    @Test
    fun `词条按原卡的 insertion_order 排序`() {
        val card = parseCard(
            """
            {"name":"A","character_book":{"entries":[
              {"keys":["第三"],"content":"c3","insertion_order":30},
              {"keys":["第一"],"content":"c1","insertion_order":10},
              {"keys":["第二"],"content":"c2","insertion_order":20}
            ]}}
            """.trimIndent()
        )
        assertEquals(listOf("第一", "第二", "第三"), card.entries.map { it.keys.single() })
    }

    @Test
    fun `缺 insertion_order 的条目排在最后`() {
        val card = parseCard(
            """
            {"name":"A","character_book":{"entries":[
              {"keys":["没序号"],"content":"c"},
              {"keys":["有序号"],"content":"c","insertion_order":5}
            ]}}
            """.trimIndent()
        )
        assertEquals(listOf("有序号", "没序号"), card.entries.map { it.keys.single() })
    }

    @Test
    fun `没有触发词或没有内容的词条被跳过，并在提示里说明`() {
        val card = parseCard(
            """
            {"name":"A","character_book":{"entries":[
              {"keys":["好的"],"content":"有用"},
              {"keys":[],"content":"没触发词"},
              {"keys":["有词"],"content":"   "}
            ]}}
            """.trimIndent()
        )
        assertEquals(1, card.entries.size)
        assertTrue(
            "跳过了几条要说出来：${card.warnings}",
            card.warnings.any { it.contains("2 条") && it.contains("空") },
        )
    }

    @Test
    fun `enabled 和 case_sensitive 透传`() {
        val card = parseCard(
            """
            {"name":"A","character_book":{"entries":[
              {"keys":["k"],"content":"c","enabled":false,"case_sensitive":true}
            ]}}
            """.trimIndent()
        )
        val entry = card.entries.single()
        assertFalse(entry.enabled)
        assertTrue(entry.caseSensitive)
    }

    @Test
    fun `keys 是字符串而不是数组时当它没写`() {
        val card = parseCard(
            """
            {"name":"A","character_book":{"entries":[
              {"keys":"不是数组","content":"c"}
            ]}}
            """.trimIndent()
        )
        assertEquals("类型不对就当没写 ⇒ 这条没有触发词 ⇒ 被跳过", 0, card.entries.size)
    }

    // ---- ccv3 与 chara 的取舍 ----

    @Test
    fun `ccv3 优先于 chara`() {
        val bytes = png(
            textChunk("chara", base64("""{"name":"V2 那份"}""")),
            textChunk("ccv3", base64("""{"name":"V3 那份"}""")),
        )
        assertEquals("V3 那份", CharacterCardParser.parse(bytes).name)
    }

    @Test
    fun `ccv3 是空的时回退到 chara`() {
        val bytes = png(
            textChunk("ccv3", ""),
            textChunk("chara", base64("""{"name":"V2 那份"}""")),
        )
        assertEquals("V2 那份", CharacterCardParser.parse(bytes).name)
    }

    // ---- 「装不下」的提示 ----

    @Test
    fun `主开场白装进开场白字段，不再当成装不下的东西`() {
        val card = parseCard("""{"name":"A","first_mes":"你来啦"}""")
        assertEquals("你来啦", card.firstMessage)
        assertFalse(
            "开场白现在装得下了，不该再提示装不下：${card.warnings}",
            card.warnings.any { it.contains("开场白") },
        )
    }

    @Test
    fun `备用开场白会被提示没导进来`() {
        val card = parseCard(
            """{"name":"A","first_mes":"你来啦","alternate_greetings":["早","晚安"]}"""
        )
        assertEquals("你来啦", card.firstMessage)
        assertTrue(
            "备用的那两条要说清楚：${card.warnings}",
            card.warnings.any { it.contains("2 条") && it.contains("备用开场白") },
        )
    }

    @Test
    fun `只有备用开场白时主开场白是空的`() {
        // 卡里可以只写备用开场白（让用户自己挑一条开局）。
        // 本版没有「挑开场白」这个入口，所以主开场白是空的 ——
        // 但备用那几条必须提示，否则用户会以为导进来就能用
        val card = parseCard("""{"name":"A","alternate_greetings":["早"]}""")
        assertEquals("", card.firstMessage)
        assertTrue("备用的要提示：${card.warnings}", card.warnings.any { it.contains("1 条") })
    }

    @Test
    fun `条件触发、常驻条目、递归扫描都会被提示`() {
        val card = parseCard(
            """
            {"name":"A","character_book":{
              "recursive_scanning":true,
              "entries":[
                {"keys":["a"],"content":"c","selective":true,"secondary_keys":["b"]},
                {"keys":["d"],"content":"e","constant":true}
              ]}}
            """.trimIndent()
        )
        assertTrue("条件触发要说：${card.warnings}", card.warnings.any { it.contains("都命中") })
        assertTrue("常驻条目要说：${card.warnings}", card.warnings.any { it.contains("常驻") })
        assertTrue("递归扫描要说：${card.warnings}", card.warnings.any { it.contains("递归") })
    }

    @Test
    fun `全都装得下时没有任何提示`() {
        val card = parseCard(
            """
            {"name":"A","description":"人设","character_book":{"entries":[
              {"keys":["k"],"content":"c"}
            ]}}
            """.trimIndent()
        )
        assertEquals(emptyList<String>(), card.warnings)
    }

    // ---- 没法导入的情况 ----

    @Test
    fun `没有名字时报错`() {
        val e = assertThrows(CharacterCardException::class.java) {
            parseCard("""{"description":"只有人设"}""")
        }
        assertTrue("要说清是缺名字：${e.message}", e.message.orEmpty().contains("名字"))
    }
}
