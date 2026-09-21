package com.aichat.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v7 → v8：给 `character` 加「开场白」。
 *
 * ## 为什么是角色卡上的一列，而不是消息表里的一条
 *
 * 开场白是**作者写在卡里的**东西（SillyTavern 的 `first_mes`），属于角色、
 * 跨会话复用。存成消息的话，每开一个新会话都得从别处抄一份进来，而
 * 「抄进来的那份和原卡对不上」就成了一个新的、没人测试过的坏状态。
 * 理由同 [CharacterEntity.firstMessage]。
 *
 * ## NOT NULL 列必须给 DEFAULT
 *
 * 同 [MIGRATION_4_5]：SQLite 的 `ALTER TABLE ADD COLUMN` 对非空列要求
 * 默认值，否则直接报错。`''` = 没有开场白，这正是老数据的正确取值 ——
 * v7 里的角色卡压根没有这个字段，用户也没表达过任何开场白意图。
 *
 * 实体侧**没有**声明 `@ColumnInfo(defaultValue = ...)`，所以 Room 校验
 * 迁移结果时不会比对这一列的默认值，于是「升级来的库有 `DEFAULT ''`、
 * 全新装的库没有 DEFAULT」这种差异不会导致 `Migration didn't properly
 * handle`。理由见 [MIGRATION_1_2] 的第 2 点。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `character` ADD COLUMN `first_message` TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v6 → v7：加角色卡与世界书，给 `conversation` 挂上角色。
 *
 * ## 这一版推翻了一条 v6 的判断，而且是故意的
 *
 * [MIGRATION_5_6] 的注释里有一节「为什么放在 `provider` 表而不是 `conversation` 表」，
 * 理由原文是：
 *
 * > 放进 `conversation` 的话还要再给会话加一个编辑入口，而收益
 * > （**同一个服务商下用不同人设**）并不确定。
 *
 * 那个收益现在确定了。所以提示词从「服务商的属性」变成「可复用的实体」——
 * 不是给 `provider` 再加一列，而是新开 [CharacterEntity] 那张表。
 * `provider.system_prompt` 保留但换了定位（见 [ProviderEntity.systemPrompt]）。
 *
 * **两处注释看起来矛盾是故意的**，别去「统一」它们：v6 的理由在当时成立，
 * v7 把它推翻了，留着原文比删掉它更有用 —— 否则半年后会有人照着
 * 「prompt 属于配置」这条把提示词又挪回 `provider`。
 *
 * ## `character` 表名必须带反引号
 *
 * `CHARACTER` 是 SQLite 的类型名关键字。手写 SQL 时一律加反引号是零成本的
 * 保险，而且 Room 自己生成的建表 SQL 也带反引号 —— 迁移校验比对
 * `createSql` 时两边写法一致（同 [CharacterDao] 的注释）。
 *
 * ## `character_id` 可空，且不加 DEFAULT
 *
 * `ALTER TABLE ADD COLUMN` 只在加 **NOT NULL** 列时才要求默认值
 * （同 [MIGRATION_5_6]）。这里 `null` 本身就是有意义的取值 ——
 * 「这个会话还没选角色」，而升级上来的老会话**全是** null。
 * 加 `DEFAULT ''` 会造出一批「指向空字符串角色」的会话，
 * 于是 `CharacterRepository` 还要为这个不存在的形状多写一个分支。
 *
 * ## 索引名必须和 Room 生成的完全一致
 *
 * `world_book_entry` 上的复合索引 `(character_id, order_index)` 正好覆盖
 * `WorldBookEntryDao` 三个查询的 `WHERE` + `ORDER BY`。但索引名不能随便起：
 * 迁移之后 Room 会拿 `PRAGMA index_list` 的结果跟实体期望的比对，
 * 名字对不上就报 `Migration didn't properly handle`。规则是
 * `index_<表名>_<列名>_<列名>`，**必须逐字相同**。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // ---- character：角色卡 ----
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `character` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`description` TEXT NOT NULL, " +
                "`persona` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )

        // ---- world_book_entry：世界书条目 ----
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `world_book_entry` (" +
                "`id` TEXT NOT NULL, " +
                "`character_id` TEXT NOT NULL, " +
                "`keys_json` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, " +
                "`enabled` INTEGER NOT NULL, " +
                "`order_index` INTEGER NOT NULL, " +
                "`case_sensitive` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_world_book_entry_character_id_order_index` " +
                "ON `world_book_entry` (`character_id`, `order_index`)"
        )

        // ---- conversation：挂上角色 ----
        db.execSQL("ALTER TABLE `conversation` ADD COLUMN `character_id` TEXT")
    }
}

/**
 * v5 → v6：给 `provider` 加三个采样参数（系统提示词 / 温度 / 最大回复长度）。
 *
 * ## 三列全部可空，而且 `null` 是**有意义的取值**
 *
 * 见 [ProviderEntity.systemPrompt]：`null` = **请求里根本不发这个字段**，
 * 让服务端用它自己的默认值。
 *
 * 这一条不是洁癖，是正确性问题：DeepSeek 与 OpenAI 的默认 temperature 并不相同，
 * 我们若替用户填一个「1.0」再发出去，等于**悄悄改掉了服务端行为**，
 * 而用户在界面上看不出来 —— 他会以为是「这个 App 答得和别处不一样」。
 *
 * 所以三列都不加 `DEFAULT`：`ALTER TABLE ADD COLUMN` 只在加 **NOT NULL** 列时
 * 才要求默认值（同 [MIGRATION_3_4]）。加了 `DEFAULT 0` 反而会造出一批
 * 「显式要求 temperature=0」的老服务商 —— 那是**确定性输出**，和老数据的
 * 真实语义（跟随服务端默认）正好相反。
 *
 * ## 为什么放在 `provider` 表而不是 `conversation` 表
 *
 * 跟着服务商走。理由见 `DECISIONS.md`：会话已经「黏住服务商」，
 * 所以「一个会话 = 一套配置」这个语义已经成立了，prompt 属于配置。
 * 放进 `conversation` 的话还要再给会话加一个编辑入口，而收益
 * （同一个服务商下用不同人设）并不确定。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `provider` ADD COLUMN `temperature` REAL")
        db.execSQL("ALTER TABLE `provider` ADD COLUMN `max_tokens` INTEGER")
        db.execSQL("ALTER TABLE `provider` ADD COLUMN `system_prompt` TEXT")
    }
}

/**
 * v4 → v5：给 `conversation` 加「置顶」标记。
 *
 * ## 为什么单独一列，不复用 `updated_at`
 *
 * 见 [ConversationEntity.pinned]。一句话：让排序键兼职「用户意图」的话，
 * 置顶这个动作会顺手篡改「最后活跃时间」，而那一列是要显示给用户看的。
 *
 * ## NOT NULL 列必须给 DEFAULT
 *
 * SQLite 的 `ALTER TABLE ADD COLUMN` 对非空列要求默认值，否则直接报错
 * （同 [MIGRATION_1_2]）。`0` = 不置顶，这正是老数据的正确取值 ——
 * 老用户没有表达过任何置顶意图。
 *
 * 实体侧**没有**声明 `@ColumnInfo(defaultValue = ...)`，所以 Room 校验迁移
 * 结果时不会比对这一列的默认值，于是「升级来的库有 `DEFAULT 0`、全新装的库
 * 没有 DEFAULT」这种差异不会导致 `Migration didn't properly handle`。
 * 理由见 [MIGRATION_1_2] 的第 2 点。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `conversation` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v3 → v4：给 `plugin` 加 MCP 工具清单缓存。
 *
 * ## 为什么是一列 JSON 而不是一张 `mcp_tool` 表
 *
 * 因为这份数据的读写形状是**整体**的：一次连接拉回整份清单、一次装配
 * 读整份清单、配置一变整份作废。拆成一行一个工具的话，就需要一个
 * 「这份快照是什么时候拉的、对着哪个配置拉的」的外层记录 —— 那是
 * 又一张表加一次 join，换来一个我们不需要的能力（按工具名跨插件查询）。
 *
 * 先例是 `settings_json` 和 `tool_calls`：这个库里凡是「整体读写的结构化数据」
 * 都是 JSON 列。
 *
 * ## 可空列，所以不需要 DEFAULT
 *
 * `ALTER TABLE ADD COLUMN` 只在加 **NOT NULL** 列时才要求默认值。
 * 这里 `null` 本身就是有意义的取值（= 没连接过），所以不加默认值 ——
 * 加了 `DEFAULT ''` 反而会造出一堆「空串缓存」，让
 * `McpCacheCodec.decode` 多一个要处理的形状。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `plugin` ADD COLUMN `mcp_cache_json` TEXT")
    }
}

/**
 * v2 → v3：加 `plugin` 表（已装插件）。
 *
 * 纯新增，不动任何已有数据 —— 用户可能已经攒了聊天记录和服务商配置。
 *
 * 表里的 `manifest_json` 存的是**清单原文**而不是解析后的结果，
 * 理由见 [PluginEntity]：宿主的解析规则会演进，存原文能让已装插件
 * 跟着演进，不用用户重装。敏感配置项（API Key）不在这一行里，
 * 它们在 AndroidKeyStore，别名由 `plugin.<插件id>.<配置键>` 推导。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `plugin` (" +
                "`id` TEXT NOT NULL, " +
                "`manifest_json` TEXT NOT NULL, " +
                "`settings_json` TEXT, " +
                "`enabled` INTEGER NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`installed_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
    }
}

/**
 * v1 → v2。
 *
 * v1 已经发布过一次（虽然只在开发机上装过），用户可能已经有聊天记录，
 * 所以这是**无损迁移**：只加列加表，不删不改任何已有数据。
 *
 * ## 三个必须注意的点
 *
 * 1. **加 `NOT NULL` 列必须带 `DEFAULT`。**
 *    SQLite 的 `ALTER TABLE ADD COLUMN` 要求非空列有默认值，否则直接报错。
 *    默认值选得对不对也很重要：
 *    - `role` 给 `'user'`：v1 里存的全是用户和助手混在一起的内容，
 *      而 v1 的写入方（检索链路验证代码）主要写的是用户输入。
 *      退一步说，猜错了也只是角色显示错，不会丢内容。
 *    - `status` 给 `'complete'`：v1 没有「正在写」的概念，已存在的都是写完了的。
 *    - `deleted` 给 `0`、`updated_at` 用 `created_at` 回填 —— 这两个是显然正确的。
 *
 * 2. **不能靠 `TableInfo` 的默认值校验偷懒。**
 *    Room 校验迁移结果时，只在**实体侧声明了 defaultValue** 的情况下才比对默认值。
 *    我们所有列都没写 `@ColumnInfo(defaultValue = ...)`，所以这里的 DEFAULT
 *    不会造成「迁移后 schema 不匹配」。反过来说，**一旦有人给实体加了
 *    `defaultValue`，这个迁移就会失败** —— 那时候要重写整张表的迁移方式。
 *
 * 3. **新表用 `CREATE TABLE IF NOT EXISTS`，且 SQL 必须和 Room 生成的一字不差。**
 *    Room 迁移后的校验是拿 `PRAGMA table_info` 的结果跟实体期望的比对，
 *    列名、类型亲和性、非空性、主键、索引任何一处不同都会报
 *    `Migration didn't properly handle`。对照物是导出的
 *    `schemas/com.aichat.core.data.AppDatabase/2.json` 里的 `createSql`。
 *    改实体之后如果忘了同步这里，`AppDatabaseMigrationTest` 会拦下来。
 *
 * `message_fts` 不需要动 —— FTS4 表结构没变，而且它的 rowid 是跟着主表 id 走的，
 * 老数据的索引仍然有效。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // ---- message：补齐对话必需字段 ----
        db.execSQL("ALTER TABLE `message` ADD COLUMN `role` TEXT NOT NULL DEFAULT 'user'")
        db.execSQL("ALTER TABLE `message` ADD COLUMN `reasoning` TEXT")
        db.execSQL("ALTER TABLE `message` ADD COLUMN `tool_calls` TEXT")
        db.execSQL("ALTER TABLE `message` ADD COLUMN `tool_call_id` TEXT")
        db.execSQL("ALTER TABLE `message` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'complete'")
        db.execSQL("ALTER TABLE `message` ADD COLUMN `model` TEXT")
        db.execSQL("ALTER TABLE `message` ADD COLUMN `error` TEXT")
        db.execSQL("ALTER TABLE `message` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `message` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0")

        // updated_at 用 created_at 回填，别留一堆 0 —— 排序和「最后活跃时间」
        // 都会用到它，0 会让老消息排到最前面
        db.execSQL("UPDATE `message` SET `updated_at` = `created_at` WHERE `updated_at` = 0")

        // ---- conversation：会话列表 ----
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `conversation` (" +
                "`id` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`provider_id` TEXT, " +
                "`model` TEXT, " +
                "`created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )

        // ---- provider：BYOK 配置（不含密钥，密钥在 AndroidKeyStore） ----
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `provider` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`base_url` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, " +
                "`key_alias` TEXT, " +
                "`key_hint` TEXT, " +
                "`extra_headers` TEXT, " +
                "`connect_timeout_seconds` INTEGER NOT NULL, " +
                "`read_timeout_seconds` INTEGER NOT NULL, " +
                "`include_usage` INTEGER NOT NULL, " +
                "`is_default` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )

        // ---- 老数据补一个会话，否则 v1 的消息在界面上会「无家可归」 ----
        // v1 只有 conversation_id 这个字符串，没有任何会话元信息。
        // 按它去重生成会话行，标题留空（界面上显示成「新对话」，用户可改）。
        db.execSQL(
            "INSERT OR IGNORE INTO `conversation` (`id`, `title`, `provider_id`, `model`, `created_at`, `updated_at`) " +
                "SELECT `conversation_id`, '', NULL, NULL, MIN(`created_at`), MAX(`created_at`) " +
                "FROM `message` GROUP BY `conversation_id`"
        )
    }
}
