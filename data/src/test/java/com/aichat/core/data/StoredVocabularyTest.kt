package com.aichat.core.data

import com.aichat.chat.ChatMessage
import com.aichat.chat.MessageStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落库词表的守卫：Kotlin 侧的枚举 / 映射函数，与 SQL 里**写死的字符串**必须一致。
 *
 * 目前守两套，而**两套的失败方式不一样**，所以守的姿势也不一样：
 *
 * | 词表 | Kotlin 侧 | SQL 侧 |
 * |---|---|---|
 * | 消息状态 | `:chat` 的 [MessageStatus.wire] | `MessageDao` / `Migrations` 里的 `'streaming'` / `'failed'` / `'complete'` |
 * | 消息角色 | `:data` 的 `toWire()` / `roleFromWire()` | `Migrations` 里的 `DEFAULT 'user'` |
 *
 * ## 状态：两边**没有任何编译期联系**
 *
 * `@Query` 要的是编译期常量，而 `MessageStatus.Streaming.wire` 是枚举的属性，写不进注解。
 * 于是改一边、另一边不会报错。
 *
 * ### 改错了会怎样（三种，全都是静默的）
 *
 * 1. `recentIn` 的 `status != 'streaming'` 失效 ⇒ **没写完的半截回复被喂给模型**，
 *    它会以为自己上一轮就是这么说的，于是接着往下编而不是重新回答。
 * 2. `listStreaming` 的 `status = 'streaming'` 失效 ⇒ 启动清理**扫不到**上次被
 *    杀进程留下的消息，界面上那条回复永远停在「正在输入」。
 * 3. `failInterrupted` 的 `SET status = 'failed'` 失效 ⇒ 同 2，且错误原因也写不进去。
 *
 * 反方向的错（把 `'streaming'` 改成别的）更隐蔽：[MessageStatus.fromWire] 认不出的值
 * **一律当 `Complete`**，于是老消息全部从「写了一半」「报错了」变成「写完了」，
 * 界面上一点区别都看不出来。这也是 [MessageStatus] 的 KDoc 早就写着
 * 「不要用 `enum.name` 或 `ordinal`」的原因 —— 只是**没有任何东西在守它**。
 *
 * ## 角色：两个方向**只被编译器保证了一半**
 *
 * `toWire()` 与 `roleFromWire()` 是同一套词表的两个方向，但：
 *
 * - **正向**的 `when (this)` 是**穷尽**的 —— 加了新角色不写映射，**编译不过**
 * - **反向**的 `when (raw)` 有一个 `else -> User` **静默兜底** —— 加了新角色、只改了正向，
 *   那个角色的消息读回来会**变成 `User`**
 *
 * 第二种是真正的坑：读成 `User` 意味着模型会以为**那是用户说的话**，而 `toWire()` 的
 * KDoc 里早就写着「以后加角色（比如 OpenAI 的 `developer`）时……」—— 也就是说这个场景
 * 是被计划过的，只是没有任何东西在守它。所以这边最要紧的一条是
 * **「两个方向互为反函数」**：它把那个 `else` 从「兜底」变成「会被发现」。
 *
 * ## 为什么是「读源码文本」而不是反射
 *
 * SQL 住在注解的字符串里，反射看不到。要守的就是那段文本本身，所以扫文本。
 * 同 [com.aichat.NetworkEgressTest] 与 `PrivacyCopyTest` 的做法。
 *
 * ## 为什么**不需要**在 build.gradle.kts 里声明 `inputs`
 *
 * 这一条和上面那两处**不一样**，值得写下来：那两处守的是 README 和**注释**，
 * 而注释不改变编译产物 ⇒ 只改文档时测试任务会被判 UP-TO-DATE **整个跳过**，
 * 于是守卫以「通过」的形式安静地失效，所以它们必须显式声明 `inputs`。
 *
 * 这里守的两边**都是生产源码**：改 `MessageDao.kt` 会让 `:data` 重编译，
 * 改 [MessageStatus] 会让 `:chat` 重编译、`:data` 跟着重编译 —— 两条路都会
 * 让测试任务重跑。Gradle 自带的依赖追踪就够了，声明反而是多余的。
 *
 * ## 空集合恒过
 *
 * 正则要是哪天对不上写法（一个都没扫到），下面每条都会「通过」得毫无意义。
 * 所以每处都先断**不是空的** —— 扫不到时宁可红，那说明这个文件要跟着改。
 *
 * ## 注释不算，代码算
 *
 * 扫描前整行注释被去掉（`//` 开头的行、`*` 开头的续行、以及块注释的起始行）。
 * 理由和 [com.aichat.NetworkEgressTest]
 * 一样：`PatchbayApp` 和 `ChatSessionTest` 的注释里就**原样写着** `status = 'streaming'`，
 * 不跳注释的话守卫会对着解释它自己的注释报警。代价是行尾注释里的字面量会漏掉，
 * 这在本文件守的两处不会发生。
 */
class StoredVocabularyTest {

    /**
     * 词表本身是**已经写进用户手机的格式**，改一个字符就要写数据迁移。
     *
     * 这不是「测试测试自己」：它冻的是**落库格式**，和「表结构不能随便改」是同一件事。
     * 真要改（比如觉得 `streaming` 该叫 `pending`），正确做法是先加一条
     * `UPDATE message SET status = 'pending' WHERE status = 'streaming'` 的迁移，
     * 然后回来改这里 —— 这个测试就是那个「先停下来想一想」的地方。
     */
    @Test
    fun `落库状态字符串是冻结的`() {
        assertEquals("streaming", MessageStatus.Streaming.wire)
        assertEquals("complete", MessageStatus.Complete.wire)
        assertEquals("stopped", MessageStatus.Stopped.wire)
        assertEquals("failed", MessageStatus.Failed.wire)
    }

    /**
     * `MessageDao` 里出现的状态字面量，必须**恰好**是 [MessageStatus.Streaming] 与
     * [MessageStatus.Failed] 两个。
     *
     * 「恰好」而不是「包含」：多扫出一个（比如有人新加一条 `status = 'stopped'` 的查询）
     * 也会红 —— 那不是错误，是要有人回来看一眼「这个新查询的条件对不对」。
     */
    @Test
    fun `MessageDao 里写死的状态字面量都来自词表`() {
        val found = literals(daoFile, COMPARED)
        assertTrue(
            "MessageDao.kt 里一个 `status = '…'` / `status != '…'` 都没扫到 —— " +
                "SQL 的写法变了、正则对不上，这条测试正在空转",
            found.isNotEmpty(),
        )
        assertEquals(
            "SQL 里的状态字面量与 MessageStatus.wire 不一致。\n" +
                "改枚举的 wire 值就要回来改 SQL，否则这些**全都不会报错**：\n" +
                "  `status != 'streaming'` 失效 ⇒ 没写完的半截回复被喂给模型\n" +
                "  `status = 'streaming'` 失效 ⇒ 启动清理扫不到，界面上永远「正在输入」\n" +
                "  `SET status = 'failed'` 失效 ⇒ 同上，且错误原因写不进去",
            setOf(MessageStatus.Streaming.wire, MessageStatus.Failed.wire),
            found,
        )
    }

    /**
     * 迁移里给 `status` 列补的默认值，必须等于 [MessageStatus.Complete.wire]。
     *
     * ## 这条迁移为什么**不能**改成引用枚举
     *
     * `db.execSQL(...)` 是普通运行时调用，把 `'complete'` 换成
     * `MessageStatus.Complete.wire` 是能编译、也能跑的 —— 但那是**做错**：
     * 迁移是**历史**，必须永远重放同一段 SQL。挂上枚举之后，将来谁改了 wire 值，
     * 这段迁移就会跟着改，于是「新装的人」和「升级上来的人」拿到不同的历史数据。
     * 迁移里的字面量写死是**特性不是缺陷**，所以这里只是把它钉住。
     */
    @Test
    fun `迁移里状态列的默认值也来自词表`() {
        val found = literals(migrationsFile, STATUS_DEFAULTED)
        assertTrue(
            "Migrations.kt 里没扫到状态列的 DEFAULT —— 写法和正则对不上了，这条测试正在空转",
            found.isNotEmpty(),
        )
        assertEquals(
            "v1→v2 迁移给 status 列补的默认值必须等于 MessageStatus.Complete.wire。" +
                "v1 没有「正在写」的概念，已存在的消息都是写完了的。",
            setOf(MessageStatus.Complete.wire),
            found,
        )
    }

    /**
     * 三处最要紧的 SQL 片段，逐字与词表比对。
     *
     * 上一条测「字面量在词表里」，这条测「**哪个字面量出现在哪个位置**」——
     * 只测前一条的话，把 `!=` 写成 `=`、或把 `'streaming'` 换成同样合法的
     * `'stopped'`，前一条都是绿的。
     *
     * 比对前把连续空白压成一个空格，所以重新缩进、换行不会误报；
     * 只有词本身变了才会红。
     */
    @Test
    fun `三处关键 SQL 与词表逐字一致`() {
        val dao = normalized(daoFile)

        assertContains(
            dao,
            "AND status != '${MessageStatus.Streaming.wire}'",
            "上下文装配必须排除「还没写完」的消息，否则模型会以为自己上一轮就是这么说的、接着往下编",
        )
        assertContains(
            dao,
            "WHERE status = '${MessageStatus.Streaming.wire}'",
            "启动清理必须能找到那些「还没写完」的消息（listStreaming / failInterrupted），否则一条都改不动",
        )
        assertContains(
            dao,
            "SET status = '${MessageStatus.Failed.wire}'",
            "启动清理必须把上次被打断的消息标成「失败」，否则用户看到一条永远「正在输入」的回复",
        )
    }

    // ---- 角色 --------------------------------------------------------

    /**
     * 角色的落库字符串同样是冻结格式，理由和状态一样。
     *
     * 这一条是**四句写死的 `assertEquals`**，不是遍历枚举去调 `toWire()` ——
     * 遍历只能证明「映射是自洽的」，证明不了「映射没变过」。冻结要的就是后者。
     */
    @Test
    fun `落库角色字符串是冻结的`() {
        assertEquals("system", ChatMessage.Role.System.toWire())
        assertEquals("user", ChatMessage.Role.User.toWire())
        assertEquals("assistant", ChatMessage.Role.Assistant.toWire())
        assertEquals("tool", ChatMessage.Role.Tool.toWire())
    }

    /**
     * ⭐ `toWire()` 与 `roleFromWire()` 是**同一套词表的两个方向**，必须互为反函数。
     *
     * 这是这一套词表里最要紧的一条：正向的 `when (this)` 穷尽（编译器会拦），
     * 反向的 `when (raw)` 却有 `else -> User` —— 于是「加了新角色、只改了正向」
     * 这件事**编译得过、跑得动、也不报错**，只是那个角色的消息读回来变成了 `User`，
     * 看起来像用户自己说的话。
     *
     * 遍历枚举而不是列四句：将来加角色时**不用记得回来改这条测试**，
     * 它会自己把新角色带进来。这正是「反函数」这个说法的好处 ——
     * 它是一条**对任意新增值都成立**的性质，不是一张会过期的清单。
     */
    @Test
    fun `角色映射的两个方向互为反函数`() {
        val roles = ChatMessage.Role.entries
        assertTrue(
            "ChatMessage.Role 一个值都没取到 —— 这条测试正在空转",
            roles.isNotEmpty(),
        )

        roles.forEach { role ->
            assertEquals(
                "roleFromWire(role.toWire()) 必须回到同一个角色。\n" +
                    "两个 when 里少改一处时不会报错，只会让这个角色的消息读回来变成 User" +
                    "（看起来像用户说的话）：${role.name}",
                role,
                roleFromWire(role.toWire()),
            )
        }
    }

    /**
     * 迁移里给 `role` 列补的默认值，必须是 [ChatMessage.Role.User] 的落库形态。
     *
     * v1 没有角色的概念（`role` 列是 v1→v2 才加的），存量消息全是用户和助手混在
     * 一起的内容，所以默认给 `user`。**为什么是 `User` 而不是别的**：
     * 当 `Assistant` 会让模型以为那是自己说过的话，当 `System` 则会把它当指令 ——
     * 都是替用户编了一段他没说过的历史。这一点和 [roleFromWire] 兜底选 `User` 同一个理由。
     */
    @Test
    fun `迁移里角色列的默认值也来自词表`() {
        val found = literals(migrationsFile, ROLE_DEFAULTED)
        assertTrue(
            "Migrations.kt 里没扫到角色列的 DEFAULT —— 写法和正则对不上了，这条测试正在空转",
            found.isNotEmpty(),
        )
        assertEquals(
            "v1→v2 迁移给 role 列补的默认值必须等于 ChatMessage.Role.User.toWire()",
            setOf(ChatMessage.Role.User.toWire()),
            found,
        )
    }

    // ---- 工具 --------------------------------------------------------

    private fun assertContains(haystack: String, needle: String, why: String) {
        assertTrue(
            "$why\n在 MessageDao.kt 里找不到这段：$needle\n" +
                "（比对前已把连续空白压成一个空格，所以这不是缩进的问题）",
            haystack.contains(needle),
        )
    }

    private fun literals(file: File, pattern: Regex): Set<String> =
        pattern.findAll(codeOnly(file)).map { it.groupValues[1] }.toSet()

    /** 源码去掉整行注释后的文本。 */
    private fun codeOnly(file: File): String {
        assertTrue("文件不存在：${file.absolutePath}", file.isFile)
        return file.readLines()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
            }
            .joinToString("\n")
    }

    /** 去掉注释、再把连续空白压成一个空格 —— 这样重新缩进不会误报。 */
    private fun normalized(file: File): String =
        codeOnly(file).replace(WHITESPACE, " ")

    private fun repoFile(relative: String): File {
        val file = File(findRepoRoot(), relative)
        assertTrue(
            "找不到 ${file.path} —— 仓库结构变了？",
            file.isFile,
        )
        return file
    }

    /**
     * 从测试的工作目录往上找仓库根（`settings.gradle.kts` 所在处）。
     *
     * 为什么不用系统属性传路径（[com.aichat.NetworkEgressTest] 是那样做的）：
     * 这里两边都是生产源码，不需要显式声明 `inputs`，那也就没有别的理由去动
     * `:data/build.gradle.kts`。往上找而不是 `File(".")` 直接拼，
     * 是为了不管工作目录是模块目录还是仓库根都能找到；真找不到时**红得很明白**。
     */
    private fun findRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError(
            "从 ${File(".").absolutePath} 一路往上都找不到 settings.gradle.kts —— " +
                "测试的工作目录不在仓库里？",
        )
    }

    private val daoFile: File by lazy {
        repoFile("data/src/main/java/com/aichat/core/data/MessageDao.kt")
    }

    private val migrationsFile: File by lazy {
        repoFile("data/src/main/java/com/aichat/core/data/Migrations.kt")
    }

    private companion object {
        /** `status = 'x'` / `status != 'x'`。 */
        val COMPARED = Regex("""\bstatus\s*(?:!=|=)\s*'([a-z_]+)'""")

        /** `` ADD COLUMN `status` … DEFAULT 'x' ``。中间那段不允许跨过别的引号。 */
        val STATUS_DEFAULTED = Regex("""`status`[^']{0,80}DEFAULT\s+'([a-z_]+)'""")

        /** `` ADD COLUMN `role` … DEFAULT 'x' ``。同上。 */
        val ROLE_DEFAULTED = Regex("""`role`[^']{0,80}DEFAULT\s+'([a-z_]+)'""")

        val WHITESPACE = Regex("""\s+""")
    }
}
