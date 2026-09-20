package com.aichat.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 数据库装配。
 *
 * ## 必须遵守的三条
 *
 * 1. **消息写入与 FTS 写入必须同事务。**
 *    Room 不提供自动同步，FTS 表是独立的一张表。所有写路径都收敛在
 *    [RoomConversationStore] 里，不要在别处直接调 DAO 写主表。
 *
 * 2. **删除同理。** 软删除（`deleted = 1`）时记得同时从 FTS 表删掉，
 *    否则已删消息仍会被检索出来。
 *
 * 3. **千万不要用 Room 的 `fallbackToDestructiveMigration()`。**
 *    用户填的是自己的 API Key、攒的是自己的聊天记录，清库等于灾难。
 *    从第一版起就老老实实写 Migration（见 [MIGRATION_1_2]）。
 *
 * ## 版本历史
 *
 * - **v1**：只有 `message`（4 列）+ `message_fts`。目的是先把中文全文检索
 *   这条链路打通验证（见 `spike/RESULT.md`）。
 * - **v2**：`message` 补齐对话必需的 9 个字段；新增 `conversation`（会话列表）
 *   与 `provider`（BYOK 配置，**不含密钥**）。
 * - **v3**：新增 `plugin`（已装插件）。存清单**原文**，密钥同样不进库。
 * - **v4**：`plugin` 加 `mcp_cache_json`（MCP 工具清单缓存）。它**不含密钥**，
 *   而且丢了只是多一次网络往返 —— 和清单原文的地位完全不同，见 [PluginEntity]。
 * - **v5**：`conversation` 加 `pinned`（用户置顶）。单独一列而不是复用
 *   `updated_at`，因为后者要显示「最后活跃时间」，见 [ConversationEntity]。
 */
@Database(
    entities = [
        MessageEntity::class,
        MessageFtsEntity::class,
        ConversationEntity::class,
        ProviderEntity::class,
        PluginEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    abstract fun messageFtsDao(): MessageFtsDao

    abstract fun conversationDao(): ConversationDao

    abstract fun providerDao(): ProviderDao

    abstract fun pluginDao(): PluginDao

    companion object {

        /**
         * 数据库文件名。
         *
         * **改这个 = 老数据全部读不到**：Room 按名字找文件，找不到就建一个新的空库，
         * 而且不会报错 —— 用户看到的是「会话全没了」。
         *
         * ## 为什么现在可以叫 `patchbay.db`（五十四轮）
         *
         * 十九轮时这里叫过 `patchbay.db`，后来被**改回** `aichat.db` —— 因为当时
         * `applicationId` 还是 `com.aichat`，数据目录没变（`/data/user/0/com.aichat`），
         * 设备上躺着的是 `aichat.db`。改名的构建一装上去，Room 找不到 `patchbay.db`
         * 就新建一个空库，会话、服务商、API Key 会**当场全部「消失」**
         * （其实还在 `aichat.db` 里，但 App 看不见）。
         *
         * **那个前提已经不成立了**：`applicationId` 换成了 `io.github.nian.patchbay`
         * （见 `app/build.gradle.kts`），系统会给一个**全新的数据目录**，老
         * `com.aichat` 的数据不会、也不该跟过来。新 App 从零开始，所以文件名可以
         * 随便起 —— 这正是当初记下的那个顺序：「`applicationId` 先改 → 系统给全新
         * 数据目录 → 老数据本来就要重来 → 这时才顺手把文件名也改成 patchbay」。
         *
         * 反过来说：**这是唯一一次机会。** 一旦发过版，这个名字就和线上那个库绑死了，
         * 再改就是又一次「用户的会话全没了」。
         */
        const val DB_NAME = "patchbay.db"

        /**
         * 建库。**不要**开 `fallbackToDestructiveMigration()`。
         *
         * [name] 参数是为了测试能开内存库 / 临时文件库。
         */
        fun create(context: Context, name: String = DB_NAME): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(*ALL_MIGRATIONS)
                .build()

        /**
         * 全部迁移，按版本顺序。
         *
         * 做成数组而不是一条条 `addMigrations`，是为了让「漏加一条迁移」
         * 这件事在新增版本时更难发生 —— 只改这里一处。
         */
        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
        )
    }
}
