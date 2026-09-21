package com.aichat.core.data

import androidx.room.migration.AutoMigrationSpec
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 迁移测试：**在设备上**把老版本的库一路升到当前版本，验证老数据无损。
 *
 * 现在覆盖 v1→v2→v3→v4→v5→v6→v7→v8→v9，每一跳单独测一次，另外再加一条「v1 直迁到最新」——
 * 分段全过不代表连起来能过（某一步可能依赖了上一步没建立的列）。
 *
 * ## 为什么必须有这个测试
 *
 * 迁移错了有两种表现，都很糟：
 *
 * - 校验不过 → 用户升级后 App 直接打不开（Room 抛
 *   `Migration didn't properly handle`）
 * - 校验过了但数据错了 → 更糟。聊天记录悄悄丢失或串味，用户过几天才发现
 *
 * 这两件事都**只能**在真实 SQLite 上验证 —— JVM 单测里那些假 DAO 根本
 * 不知道 `ALTER TABLE` 长什么样。
 *
 * ## 它靠什么工作
 *
 * `MigrationTestHelper` 会从 androidTest 的 assets 里读导出的 schema JSON
 * （`schemas/com.aichat.core.data.AppDatabase/` 下的 `1.json` … `9.json`，
 * 由 `data/build.gradle.kts` 里那句 `assets.srcDir` 挂进来），据此建出真正的
 * 老版本库、跑迁移、再拿 `PRAGMA table_info` 跟当前版本的期望逐列比对。
 *
 * **所以：改了实体但忘了改迁移，这个测试会直接失败。** 这正是它存在的意义。
 *
 * ## 已知边界：`5.json` 的 identityHash 不可信（但无害）
 *
 * 每个 schema JSON 里有个 `identityHash`，是 Room 对结构算的哈希 —— 结构不同就
 * 必然不同。实测 `5.json` 和 `6.json` 的 identityHash **一模一样**，说明
 * `5.json` 那份是手工反推的：某轮把 `version = 5 → 6` 的改动被工具静默丢弃，
 * 于是 Room 按「版本号还是 5、实体已是新形状」导出了 `5.json`（也就是把当前
 * 版本号对应的那份**重写成了 v6 形状**），后来手工把结构改回来，hash 忘了改。
 *
 * **为什么无害**：Room 决定「要不要迁移」看的是**版本号**，不是 hash。hash 只在
 * `onOpen` 时做一次「文件里存的 vs 代码里的」一致性检查，而那是相等/不等的比较，
 * 具体值是什么不影响判断。所以真实用户从 v5 升 v6 照常走 `MIGRATION_5_6`。
 * 测试这一侧，`runMigrationsAndValidate` 的结构校验是硬的。
 *
 * **怎么确认它不是假绿**（实测过，别只信这段注释）：把 `MIGRATION_5_6` 的三条
 * `ALTER TABLE` 删掉一条再跑本测试 —— 14 条里有 3 条立刻红，报
 * `Migration didn't properly handle: provider`。也就是校验确实在看结构，
 * 没有因为 hash 相同而跳过。
 *
 * 真 v5 的 hash 已经**无法恢复**（生成它的那份代码不存在了），所以这里不修 ——
 * 改了只会更假。记住这条即可。
 *
 * ## 方法名里为什么没有空格
 *
 * **instrumented 测试会被 dex，而 DEX 版本低于 040（对应 API 30）时
 * 方法名的 SimpleName 里不允许有空格。** minSdk 是 28，所以
 * `fun \`v1 升到 v2 后老消息一条不丢\`()` 会让 `dexBuilderDebugAndroidTest`
 * 直接失败：
 *
 * > Space characters in SimpleName '...' are not allowed prior to DEX version 040
 *
 * JVM 单测不走 dex，所以那边几百个中文带空格的方法名都没事 ——
 * 这个约束**只对 `src/androidTest` 成立**，很容易照抄过来踩坑。
 * 这里的写法是「中文照写、空格去掉」。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList<AutoMigrationSpec>(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `v1升到v2后老消息一条不丢`() {
        // ---- 造一个真正的 v1 库 ----
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO message (id, conversation_id, content, created_at) " +
                    "VALUES (1, 'c1', '帮我把会议纪要整理成表格', 100)"
            )
            db.execSQL(
                "INSERT INTO message (id, conversation_id, content, created_at) " +
                    "VALUES (2, 'c1', '会议纪要的模板放在哪里', 200)"
            )
            db.execSQL(
                "INSERT INTO message_fts (rowid, body) VALUES (1, '帮 我 把 会 议 纪 要 整 理 成 表 格')"
            )
        }

        // ---- 跑迁移，并让 Room 校验 schema ----
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        db.query("SELECT id, content, role, status, deleted, updated_at FROM message ORDER BY id").use { c ->
            assertEquals("两条老消息都该还在", 2, c.count)

            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("帮我把会议纪要整理成表格", c.getString(1))
            // 新列的默认值
            assertEquals("user", c.getString(2))
            assertEquals("complete", c.getString(3))
            assertEquals(0, c.getInt(4))
            // updated_at 用 created_at 回填，不能是 0
            assertEquals(100L, c.getLong(5))

            assertTrue(c.moveToNext())
            assertEquals(2L, c.getLong(0))
            assertEquals("会议纪要的模板放在哪里", c.getString(1))
            assertEquals(200L, c.getLong(5))
        }

        // 老数据的索引行必须还在 —— 迁移不该把检索能力弄丢
        db.query("SELECT COUNT(*) FROM message_fts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }

        db.close()
    }

    @Test
    fun `v1的conversation_id会被补成会话行`() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO message (id, conversation_id, content, created_at) VALUES (1, 'c1', '甲', 100)"
            )
            db.execSQL(
                "INSERT INTO message (id, conversation_id, content, created_at) VALUES (2, 'c1', '乙', 300)"
            )
            db.execSQL(
                "INSERT INTO message (id, conversation_id, content, created_at) VALUES (3, 'c2', '丙', 500)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        // 不补会话行的话，v1 的消息在会话列表里会「无家可归」——
        // 界面上一条都看不到，但数据库里明明有
        db.query("SELECT id, title, created_at, updated_at FROM conversation ORDER BY id").use { c ->
            assertEquals(2, c.count)

            assertTrue(c.moveToFirst())
            assertEquals("c1", c.getString(0))
            assertEquals("标题留空，界面显示成「新对话」", "", c.getString(1))
            assertEquals(100L, c.getLong(2))
            assertEquals(300L, c.getLong(3))

            assertTrue(c.moveToNext())
            assertEquals("c2", c.getString(0))
            assertEquals(500L, c.getLong(2))
        }

        db.close()
    }

    @Test
    fun `迁移后新表可用且默认值正确`() {
        helper.createDatabase(TEST_DB, 1).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        db.execSQL(
            "INSERT INTO provider (id, name, base_url, model, key_alias, key_hint, extra_headers, " +
                "connect_timeout_seconds, read_timeout_seconds, include_usage, is_default, created_at, updated_at) " +
                "VALUES ('p1', '深度求索', 'api.deepseek.com', 'deepseek-chat', 'provider.p1', '…1234', NULL, 30, 300, 0, 1, 1, 1)"
        )
        db.query("SELECT name, model, key_hint, is_default FROM provider").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("深度求索", c.getString(0))
            assertEquals("deepseek-chat", c.getString(1))
            assertEquals("…1234", c.getString(2))
            assertEquals(1, c.getInt(3))
        }

        // conversation 的 provider_id / model 允许为空（用户可以先建会话再配 Key）
        db.execSQL(
            "INSERT INTO conversation (id, title, provider_id, model, created_at, updated_at) " +
                "VALUES ('c9', '', NULL, NULL, 1, 1)"
        )
        db.query("SELECT provider_id, model FROM conversation WHERE id = 'c9'").use { c ->
            assertTrue(c.moveToFirst())
            assertNotNull(c)
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
        }

        db.close()
    }

    @Test
    fun `空库迁移不会报错`() {
        // 全新安装走的是「直接建 v2」，但用户可能在 v1 里一条消息都没发就升级了。
        // 空表跑 GROUP BY 补会话的语句不能炸。
        helper.createDatabase(TEST_DB, 1).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        db.query("SELECT COUNT(*) FROM conversation").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        db.close()
    }

    // ---------------------------------------------------------------- v2 → v3

    @Test
    fun `v2升到v3后老数据一条不丢且多了plugin表`() {
        // v3 只加表、不动已有列。但「只加表」也需要验证：
        // CREATE TABLE 的 SQL 和 Room 生成的差一个字，runMigrationsAndValidate
        // 就会报 `Migration didn't properly handle: plugin`
        helper.createDatabase(TEST_DB, 2).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        db.query("SELECT COUNT(*) FROM plugin").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("新表该是空的", 0, c.getInt(0))
        }

        // 老表还在（迁移没有用「重建整库」这种粗暴做法）
        db.query("SELECT COUNT(*) FROM message").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM provider").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        db.close()
    }

    @Test
    fun `从v1一路升到v3`() {
        // 用户可能是从最早的版本一路升上来的，中间每一跳都要能过。
        // 分段测（v1→v2、v2→v3）都通过不代表连起来能过 ——
        // 比如某一步依赖了上一步没建立的列。
        helper.createDatabase(TEST_DB, 1).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_1_2, MIGRATION_2_3)

        db.query("SELECT COUNT(*) FROM plugin").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        db.close()
    }

    // ---------------------------------------------------------------- v3 → v4

    @Test
    fun `v3升到v4后老插件还在且多了缓存列`() {
        // v4 给 plugin 加一列可空列。加列也要验证：
        // 列名、类型亲和性、可空性任何一处不同都会报
        // `Migration didn't properly handle: plugin`
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO plugin (id, manifest_json, settings_json, enabled, source, installed_at, updated_at) " +
                    "VALUES ('pub.a.one', '{\"id\":\"pub.a.one\"}', NULL, 1, 'paste', 100, 100)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        // 老插件行必须还在 —— 迁移只加列，不该碰数据
        db.query("SELECT id, enabled, mcp_cache_json FROM plugin").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("pub.a.one", c.getString(0))
            assertEquals(1, c.getInt(1))
            // 没有缓存的老插件，这一列是 NULL —— 不是空串。
            // 空串会让 McpCacheCodec.decode 多一个要处理的形状
            assertTrue("老行的新列该是 NULL", c.isNull(2))
        }

        db.close()
    }

    @Test
    fun `v4的缓存列可以写读`() {
        helper.createDatabase(TEST_DB, 3).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        db.execSQL(
            "INSERT INTO plugin (id, manifest_json, settings_json, enabled, source, installed_at, updated_at, mcp_cache_json) " +
                "VALUES ('pub.a.mcp', '{}', NULL, 1, 'paste', 1, 1, '{\"fingerprint\":\"x\"}')"
        )
        db.query("SELECT mcp_cache_json FROM plugin WHERE id = 'pub.a.mcp'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("{\"fingerprint\":\"x\"}", c.getString(0))
        }

        db.close()
    }

    @Test
    fun `从v1一路升到v4`() {
        // 用户可能是从最早的版本一路升上来的，中间每一跳都要能过。
        // 分段测都通过不代表连起来能过 —— 比如某一步依赖了上一步没建立的列
        helper.createDatabase(TEST_DB, 1).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
        )

        db.query("SELECT COUNT(*) FROM plugin").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        db.close()
    }

    // ---------------------------------------------------------------- v4 → v5

    @Test
    fun `v4升到v5后老会话还在且多了置顶列`() {
        // v5 给 conversation 加一个 NOT NULL 列。**加 NOT NULL 列**是最容易
        // 写错的一种：SQLite 要求带 DEFAULT，而 DEFAULT 的取值决定了老数据的
        // 语义 —— 这里 0 = 不置顶，正是老用户该有的取值（他没表达过置顶意图）
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO conversation (id, title, provider_id, model, created_at, updated_at) " +
                    "VALUES ('c1', '会议纪要', 'p1', 'deepseek-chat', 100, 300)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        db.query("SELECT id, title, provider_id, model, created_at, updated_at, pinned FROM conversation")
            .use { c ->
                assertEquals(1, c.count)
                assertTrue(c.moveToFirst())
                assertEquals("c1", c.getString(0))
                assertEquals("会议纪要", c.getString(1))
                assertEquals("p1", c.getString(2))
                assertEquals("deepseek-chat", c.getString(3))
                assertEquals(100L, c.getLong(4))
                // updated_at 不能被迁移动过 —— 它是「最后活跃时间」，
                // 迁移顺手刷一遍会让所有会话都变成「刚刚」
                assertEquals(300L, c.getLong(5))
                assertEquals("老数据默认不置顶", 0, c.getInt(6))
            }

        db.close()
    }

    @Test
    fun `v5的置顶列可以写读`() {
        helper.createDatabase(TEST_DB, 4).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        db.execSQL(
            "INSERT INTO conversation (id, title, created_at, updated_at, pinned) " +
                "VALUES ('c9', '', 1, 1, 1)"
        )
        db.query("SELECT pinned FROM conversation WHERE id = 'c9'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }

        db.close()
    }

    @Test
    fun `v5升到v6后老服务商还在且多了三个参数列`() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO provider (id, name, base_url, model, key_alias, key_hint, " +
                    "extra_headers, connect_timeout_seconds, read_timeout_seconds, " +
                    "include_usage, is_default, created_at, updated_at) " +
                    "VALUES ('p1', '深度求索', 'api.deepseek.com', 'deepseek-chat', " +
                    "'provider.p1', '…1234', NULL, 30, 300, 0, 1, 100, 200)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)

        db.query(
            "SELECT name, model, key_hint, created_at, updated_at, " +
                "temperature, max_tokens, system_prompt FROM provider WHERE id = 'p1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("深度求索", c.getString(0))
            assertEquals("deepseek-chat", c.getString(1))
            assertEquals("…1234", c.getString(2))
            assertEquals(100L, c.getLong(3))
            // updated_at 不能被迁移动过 —— 同 v4→v5 的理由
            assertEquals(200L, c.getLong(4))

            // 三个新列必须是 NULL，**不能是 0 / 0.0 / 空串**。
            // 见 MIGRATION_5_6：null 表达「请求里不发这个字段」，
            // 而 0.0 是「显式要求确定性输出」—— 语义正好相反，
            // 老用户会莫名其妙发现回答不再有变化
            assertTrue("temperature 应该是 NULL", c.isNull(5))
            assertTrue("max_tokens 应该是 NULL", c.isNull(6))
            assertTrue("system_prompt 应该是 NULL", c.isNull(7))
        }

        db.close()
    }

    @Test
    fun `v6的三个参数列可以写读`() {
        helper.createDatabase(TEST_DB, 5).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)

        db.execSQL(
            "INSERT INTO provider (id, name, base_url, model, extra_headers, " +
                "connect_timeout_seconds, read_timeout_seconds, include_usage, " +
                "is_default, created_at, updated_at, temperature, max_tokens, system_prompt) " +
                "VALUES ('p2', '本地', '127.0.0.1:8080', 'qwen', NULL, 30, 300, 0, 0, 1, 1, " +
                "0.7, 4096, '只回答一个字。')"
        )
        db.query(
            "SELECT temperature, max_tokens, system_prompt FROM provider WHERE id = 'p2'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0.7, c.getDouble(0), 1e-9)
            assertEquals(4096, c.getInt(1))
            assertEquals("只回答一个字。", c.getString(2))
        }

        db.close()
    }

    // ---------------------------------------------------------------- v6 → v7

    @Test
    fun `v6升到v7后老数据还在且多了角色与世界书表`() {
        // v7 加两张表 + 一列。加表和加列都要验证：`CREATE TABLE` 的 SQL、
        // 列的顺序、索引名，任何一处和 Room 生成的差一个字，
        // runMigrationsAndValidate 都会报 `Migration didn't properly handle`
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO conversation (id, title, provider_id, model, created_at, updated_at, pinned) " +
                    "VALUES ('c1', '会议纪要', 'p1', 'deepseek-chat', 100, 300, 1)"
            )
            db.execSQL(
                "INSERT INTO provider (id, name, base_url, model, extra_headers, " +
                    "connect_timeout_seconds, read_timeout_seconds, include_usage, " +
                    "is_default, created_at, updated_at, temperature, max_tokens, system_prompt) " +
                    "VALUES ('p1', '深度求索', 'api.deepseek.com', 'deepseek-chat', NULL, 30, 300, 0, 1, " +
                    "100, 200, NULL, NULL, '回答不超过三句话。')"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        // 老会话还在，而且 character_id 必须是 NULL ——
        // NULL = 「这个会话还没选角色」，正是升级后所有老会话的真实状态。
        // 给一个 `DEFAULT ''` 会造出「指向空字符串角色」的会话，
        // 而那是个不存在的形状，下游还得为它多写一个分支
        db.query(
            "SELECT title, updated_at, pinned, character_id FROM conversation WHERE id = 'c1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("会议纪要", c.getString(0))
            // updated_at 不能被迁移动过 —— 它是「最后活跃时间」，
            // 迁移顺手刷一遍会让所有会话都变成「刚刚」
            assertEquals(300L, c.getLong(1))
            assertEquals(1, c.getInt(2))
            assertTrue("老会话的 character_id 该是 NULL", c.isNull(3))
        }

        // 服务商那条提示词不能丢 —— 它这一版换了个定位（「附加提示词」），
        // 但**没有换列**，老用户填过的内容要原样还在
        db.query("SELECT system_prompt FROM provider WHERE id = 'p1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("回答不超过三句话。", c.getString(0))
        }

        // 两张新表该是空的
        db.query("SELECT COUNT(*) FROM `character`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM world_book_entry").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        db.close()
    }

    @Test
    fun `v7的角色与世界书可以写读`() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO conversation (id, title, created_at, updated_at, pinned) " +
                    "VALUES ('c1', '', 1, 1, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        db.execSQL(
            "INSERT INTO `character` (id, name, description, persona, created_at, updated_at) " +
                "VALUES ('ch1', '诗人', '照着古诗捏的', '你是一位诗人。', 1, 1)"
        )
        db.execSQL(
            "INSERT INTO world_book_entry " +
                "(id, character_id, keys_json, content, enabled, order_index, case_sensitive) " +
                "VALUES ('e1', 'ch1', '[\"老王\",\"王叔\"]', '老王是镇上的铁匠。', 1, 0, 0)"
        )
        db.execSQL("UPDATE conversation SET character_id = 'ch1' WHERE id = 'c1'")

        db.query(
            "SELECT c.name, c.persona, e.keys_json, e.enabled, e.order_index, e.case_sensitive " +
                "FROM `character` c JOIN world_book_entry e ON e.character_id = c.id WHERE c.id = 'ch1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("诗人", c.getString(0))
            assertEquals("你是一位诗人。", c.getString(1))
            // 触发词存的是 JSON 数组原文。写坏了由 Repository 兜底成空列表，
            // 但那不代表这里可以随便写 —— 它是**用户数据的投影**
            assertEquals("[\"老王\",\"王叔\"]", c.getString(2))
            assertEquals(1, c.getInt(3))
            assertEquals(0, c.getInt(4))
            assertEquals(0, c.getInt(5))
        }

        // 会话确实挂上了角色
        db.query("SELECT character_id FROM conversation WHERE id = 'c1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("ch1", c.getString(0))
        }

        // 复合索引必须真的建出来了，而且名字和 Room 生成的一致 ——
        // 名字对不上，迁移之后 Room 校验 schema 时会报
        // 「实体期望一个不存在的索引」
        db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                "AND name = 'index_world_book_entry_character_id_order_index'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("复合索引必须存在", 1, c.getInt(0))
        }

        db.close()
    }

    // ---------------------------------------------------------------- v7 → v8

    @Test
    fun `v7升到v8后老角色还在且多了开场白列`() {
        // 只加一列。列名、类型亲和性、非空性任何一处和 Room 期望的不同，
        // runMigrationsAndValidate 都会报 `Migration didn't properly handle`
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO `character` (id, name, description, persona, created_at, updated_at) " +
                    "VALUES ('ch1', '诗人', '照着古诗捏的', '你是一位诗人。', 1, 1)"
            )
            db.execSQL(
                "INSERT INTO world_book_entry " +
                    "(id, character_id, keys_json, content, enabled, order_index, case_sensitive) " +
                    "VALUES ('e1', 'ch1', '[\"老王\"]', '老王是镇上的铁匠。', 1, 0, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)

        // 老角色一个字都不能少，开场白必须是**空串**而不是 NULL ——
        // 空串 = 「这个角色没有开场白」，正是 v7 里所有角色的真实状态；
        // 而 NULL 会让实体侧那个非空的 `String` 直接对不上。
        // （`getString` 在列是 NULL 时返回 null，所以这一条断言同时管住了两件事）
        db.query(
            "SELECT name, persona, first_message FROM `character` WHERE id = 'ch1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("诗人", c.getString(0))
            assertEquals("你是一位诗人。", c.getString(1))
            assertEquals("", c.getString(2))
        }

        // 世界书这张表这一版没动，老词条要原样还在
        db.query("SELECT content FROM world_book_entry WHERE id = 'e1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("老王是镇上的铁匠。", c.getString(0))
        }

        db.close()
    }

    @Test
    fun `v8的开场白可以写读`() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO `character` (id, name, description, persona, created_at, updated_at) " +
                    "VALUES ('ch1', '苏晚', '', '', 1, 1)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)

        // 开场白里一定有换行、括号、中文标点 —— 存的是**原样文本**，
        // 谁都不许在这一层做转义或截断
        val greeting = "（茶山脚下，苏晚提着灯站在门口）\n……你来啦。"
        db.execSQL("UPDATE `character` SET first_message = ? WHERE id = 'ch1'", arrayOf(greeting))

        db.query("SELECT first_message FROM `character` WHERE id = 'ch1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(greeting, c.getString(0))
        }

        db.close()
    }

    // ---------------------------------------------------------------- v8 → v9

    @Test
    fun `v8升到v9后老角色还在且多了备用开场白列`() {
        // 只加一列，和 v7→v8 是同一形状的改动。列名、类型亲和性、非空性
        // 任何一处和 Room 期望的不同，runMigrationsAndValidate 都会报
        // `Migration didn't properly handle`
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO `character` (id, name, description, persona, first_message, " +
                    "created_at, updated_at) " +
                    "VALUES ('ch1', '苏晚', '照着小说捏的', '你是苏晚。', '你来啦。', 1, 1)"
            )
            db.execSQL(
                "INSERT INTO world_book_entry " +
                    "(id, character_id, keys_json, content, enabled, order_index, case_sensitive) " +
                    "VALUES ('e1', 'ch1', '[\"老王\"]', '老王是镇上的铁匠。', 1, 0, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)

        // 老角色的每一列都要原样还在，新列必须是**空串**而不是 NULL ——
        // 空串 = 「这个角色没有备用开场白」，正是 v8 里所有角色的真实状态；
        // 而 NULL 会让实体侧那个非空的 `String` 直接对不上。
        // （`getString` 在列是 NULL 时返回 null，所以这一条断言同时管住了两件事）
        db.query(
            "SELECT name, persona, first_message, alternate_greetings_json " +
                "FROM `character` WHERE id = 'ch1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("苏晚", c.getString(0))
            assertEquals("你是苏晚。", c.getString(1))
            assertEquals("你来啦。", c.getString(2))
            assertEquals("", c.getString(3))
        }

        // 世界书这张表这一版没动，老词条要原样还在
        db.query("SELECT content FROM world_book_entry WHERE id = 'e1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("老王是镇上的铁匠。", c.getString(0))
        }

        db.close()
    }

    @Test
    fun `v9的备用开场白可以写读`() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO `character` (id, name, description, persona, first_message, " +
                    "created_at, updated_at) " +
                    "VALUES ('ch1', '苏晚', '', '', '', 1, 1)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)

        // 存的是 JSON 数组原文。开场白里一定有换行、括号、中文标点，
        // 而 JSON 会把换行转义成 `\n` —— 谁都不许在这一层再做一次转义
        val json = """["早。","（她放下手里的灯）\n……又见面了。"]"""
        db.execSQL(
            "UPDATE `character` SET alternate_greetings_json = ? WHERE id = 'ch1'",
            arrayOf(json),
        )

        db.query("SELECT alternate_greetings_json FROM `character` WHERE id = 'ch1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(json, c.getString(0))
        }

        db.close()
    }

    @Test
    fun `从v1一路升到v9`() {
        // 用户可能是从最早的版本一路升上来的，中间每一跳都要能过。
        // 分段测都通过不代表连起来能过 —— 比如某一步依赖了上一步没建立的列
        helper.createDatabase(TEST_DB, 1).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            9,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
        )

        // v1 建的消息会补出会话行，那一行的 pinned 必须是 0 而不是 NULL
        db.query("SELECT COUNT(*) FROM conversation WHERE pinned IS NULL").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("不能有 pinned 为空的行", 0, c.getInt(0))
        }
        // 一路升上来的会话，character_id 全是 NULL（老用户没选过角色）——
        // 这正是「升级后行为不变」的判据
        db.query("SELECT COUNT(*) FROM conversation WHERE character_id IS NOT NULL").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("老会话不该凭空多出一个角色", 0, c.getInt(0))
        }
        // v7 / v8 / v9 三次加列都必须落到表上（那几列是升级上来的库独有的形状）
        db.query("SELECT COUNT(*) FROM `character` WHERE first_message IS NULL").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("开场白列不能有 NULL", 0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM `character` WHERE alternate_greetings_json IS NULL")
            .use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("备用开场白列不能有 NULL", 0, c.getInt(0))
            }

        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
