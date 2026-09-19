package com.aichat.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.domain.text.CjkText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机验证：这是「验证一」的最终关卡。
 *
 * 单测只能证明表达式拼对了，证明不了设备上的 SQLite 行为符合预期。
 * 各厂商 ROM 内置的 SQLite 版本、编译选项都不一样，
 * **必须在目标机型上跑通这个测试**再往下写业务代码。
 *
 * 跑法：./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class CjkSearchInstrumentedTest {

    private lateinit var db: AppDatabase

    private val corpus = listOf(
        1L to "帮我把会议纪要整理成表格",
        2L to "会议纪要的模板放在哪里",
        3L to "今天天气不错，出去走走",
        4L to "我想做一个AI对话软件，类似RikkaHub",
        5L to "把上周的会议录音转成文字",
    )

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        db.conversationDao().insertIfAbsent(
            ConversationEntity(id = "c1", title = "会议相关", createdAt = 1, updatedAt = 1)
        )
        corpus.forEach { (id, text) ->
            db.messageDao().insert(
                MessageEntity(
                    id = id,
                    conversationId = "c1",
                    role = "user",
                    content = text,
                    status = "complete",
                    createdAt = id,
                    updatedAt = id,
                )
            )
            db.messageFtsDao().insert(
                MessageFtsEntity(rowId = id, body = CjkText.forIndex(text))
            )
        }
    }

    @After
    fun tearDown() = db.close()

    /**
     * prefixLast 必须显式传：它只影响**拉丁段**（整词 token 需要 `*` 才能前缀匹配）。
     * 中文段是逐字索引 + 短语查询，本身就是子串语义，加不加 `*` 都一样。
     * 默认 false，即「搜什么就是什么」，避免把用户的短输入误当成前缀放大召回。
     */
    private fun search(q: String, prefixLast: Boolean = false): List<Long> = runBlocking {
        val expr = CjkText.forQuery(q, prefixLast = prefixLast)
        if (expr.isEmpty()) return@runBlocking emptyList()
        db.messageFtsDao().search(expr).map { it.rowId }.sorted()
    }

    @Test
    fun 中文两字词可召回() {
        assertEquals(listOf(1L, 2L, 5L), search("会议"))
        assertEquals(listOf(1L, 2L), search("纪要"))
        assertEquals(listOf(5L), search("录音"))
        assertEquals(listOf(3L), search("天气"))
    }

    @Test
    fun 中文长词按字序匹配() {
        assertEquals(listOf(1L, 2L), search("会议纪要"))
    }

    @Test
    fun 紧邻中文的拉丁词可召回() {
        assertEquals(listOf(4L), search("RikkaHub"))
        assertEquals(listOf(4L), search("rikkahub"))
    }

    @Test
    fun 拉丁前缀可召回() {
        // 对照：默认（精确）语义下，`rikka` 匹配不到 token `rikkahub`。
        // 这不是 bug —— 逐字索引只对汉字生效，拉丁词始终是完整 token。
        assertTrue(search("Rikka").isEmpty())

        // 开启前缀后才召回。搜索框「边输边搜」应显式开这个开关。
        assertEquals(listOf(4L), search("Rikka", prefixLast = true))
        assertEquals(listOf(4L), search("rikka", prefixLast = true))
        assertEquals(listOf(4L), search("rikkaH", prefixLast = true))
    }

    @Test
    fun 中英混排可召回() {
        assertEquals(listOf(4L), search("对话软件"))
        assertEquals(listOf(4L), search("AI"))
    }

    @Test
    fun 无关词不产生误召回() {
        assertTrue(search("不存在的词").isEmpty())
    }

    @Test
    fun 多段查询是AND语义而非子串语义() {
        // 语料里并不存在连续子串「对话AI」（原文是「AI对话软件」），
        // 所以 LIKE '%对话AI%' 返回空。而 FTS 把查询拆成 `"对 话" ai`，
        // 两个片段各自命中即算匹配，于是召回 doc 4。
        //
        // 这是**有意的行为差异**，不是 bug：对搜索框来说召回更全。
        // 但它意味着 FTS 召回是 LIKE 的超集。若某处业务必须严格子串匹配，
        // 需在 Kotlin 侧对 hit.content 再做一次 contains 过滤。
        assertEquals(listOf(4L), search("对话AI"))
    }

    /**
     * 带空格的多段查询必须命中。
     *
     * 这条守的是一个**只在真机上才暴露**的坑：Android 内置的 SQLite 编译时
     * 没有开 `SQLITE_ENABLE_FTS3_PARENTHESIS`，用的是 **Standard Query
     * Syntax** —— 在那套语法里 `AND` 不是运算符，而是被当成**普通 token**。
     *
     * 于是 `"会 议" AND "纪 要"` 被解析成「三个条件都满足」：短语「会 议」、
     * 单词 `and`、短语「纪 要」。索引里永远不会有 `and` 这个 token，
     * 所以**带空格的多段查询在真机上永远返回空**。
     *
     * 桌面版 SQLite 默认是 Enhanced 语法，`AND` 正常工作 —— 所以
     * 单测（只检查字符串拼得对不对）和本地跑 SQL（语法不同）**都发现不了**，
     * 只有这个 instrumented test 能把它钉住。
     *
     * 实测（MuMu / SQLite 3.44.3）：
     * ```
     * MATCH '"北 京" AND "适 合"'   -> 空
     * MATCH '"北 京" "适 合"'       -> 命中
     * ```
     */
    @Test
    fun 多段查询能命中() {
        assertEquals(listOf(1L, 2L), search("会议 纪要"))
        assertEquals(listOf(5L), search("会议 录音"))
    }

    /** 全角空格和半角空格必须等价 —— 中文输入法下全角空格很容易打出来。 */
    @Test
    fun 全角空格分隔的多段查询与半角等价() {
        assertEquals(search("会议 纪要"), search("会议\u3000纪要"))
        assertEquals(listOf(1L, 2L), search("会议\u3000纪要"))
    }

    /**
     * 下划线在 FTS 查询里是**分隔符**，一个只含下划线的段会解析成「空」，
     * 空出现在 `AND` 右边时 SQLite 直接报 `malformed MATCH expression`。
     *
     * `CjkText.forQuery` 因此把非 ASCII 字母数字的段一律加引号包住。
     * 这里验证包住之后查询能正常执行 —— 不抛异常就算过。
     */
    @Test
    fun 含下划线的查询不会让数据库报错() {
        assertTrue(search("会议 _").isEmpty())
        assertTrue(search("_").isEmpty())
        assertTrue(search("my_file").isEmpty())
    }

    @Test
    fun 返回正文是原始文本而非索引文本() {
        val hit = runBlocking {
            db.messageFtsDao().search(CjkText.forQuery("会议")).first { it.rowId == 1L }
        }
        assertEquals("帮我把会议纪要整理成表格", hit.content)
    }

    @Test
    fun 检索结果带会话标题() = runBlocking {
        val hit = db.messageFtsDao().search(CjkText.forQuery("录音")).first()
        assertEquals("会议相关", hit.conversationTitle)
    }

    /**
     * 消息还在、会话行没了。
     *
     * 这条同时验证两件事：
     * - `LEFT JOIN`（而不是 `JOIN`）：结果**不能少**。少一条比多一条难查得多，
     *   用户只会觉得「明明说过这句话」。
     * - `COALESCE(c.title, '')`：没匹配上时 title 是 NULL，而 Kotlin 侧是
     *   非空 `String`，不做兜底 Room 会在映射时直接抛。
     */
    @Test
    fun 会话行缺失时不会丢掉检索结果() = runBlocking {
        db.conversationDao().delete("c1")

        val hits = db.messageFtsDao().search(CjkText.forQuery("会议"))
        assertEquals(3, hits.size)
        assertTrue(hits.all { it.conversationTitle == "" })
    }

    /**
     * **故意不清 FTS 索引**。
     *
     * 正常路径下 `RoomConversationStore.delete` 会在同一个事务里清索引，
     * 所以这里模拟的是「索引残留」——历史数据，或者将来某次改动漏了一半。
     * 没有 SQL 里那句 `m.deleted = 0` 的话，已经删掉的消息会重新出现在
     * 搜索结果里，而从界面上看那像是「删除没生效」。
     */
    @Test
    fun 索引残留时软删的消息仍不出现在结果里() = runBlocking {
        db.messageDao().softDelete(1L, "c1", 99)

        val ids = db.messageFtsDao().search(CjkText.forQuery("会议")).map { it.rowId }.sorted()
        assertEquals(listOf(2L, 5L), ids)
    }

    /**
     * 工具循环里 user → assistant → tool 常常落在同一毫秒，`created_at` 不唯一。
     * 排序不带 `m.id` 的话每次查询顺序可能不同，结果列表会自己跳动。
     */
    @Test
    fun 同一毫秒的消息顺序稳定() = runBlocking {
        listOf(6L, 7L, 8L).forEach { id ->
            db.messageDao().insert(
                MessageEntity(
                    id = id,
                    conversationId = "c1",
                    role = "user",
                    content = "顺序测试$id",
                    status = "complete",
                    createdAt = 100,
                    updatedAt = 100,
                )
            )
            db.messageFtsDao().insert(
                MessageFtsEntity(rowId = id, body = CjkText.forIndex("顺序测试$id"))
            )
        }

        repeat(3) {
            val ids = db.messageFtsDao().search(CjkText.forQuery("顺序")).map { it.rowId }
            assertEquals(listOf(8L, 7L, 6L), ids)
        }
    }
}
