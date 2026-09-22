package com.aichat.plugin.manifest

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限清单上的话，必须和宿主**真的**会做的事对得上。
 *
 * ## 为什么值得一条测试
 *
 * 这些字符串是用户**唯一一次**做决定时看到的东西（安装页的权限区，
 * 以及插件详情页）。它们不像代码那样有编译器和测试兜着 —— 文案改一个字，
 * 不会有任何东西变红。
 *
 * 于是它们真的漂移过：`shell` / `linuxEnv` / `device` 三项的描述一直写着
 * 「可以执行系统命令」「需要 Ubuntu 环境」，而宿主**从来没有兑现过**
 * （运行时没有任何读点 —— 在 `src/main` 里搜 `permissions.shell`，
 * 只有校验处命中，终端那个 `.shell` 是另一回事）。
 * 用户装完发现一条命令都跑不了 —— 那不是「功能缺失」，是**误导**：
 * 他基于一个假前提做了安装决定。
 *
 * ## 这条测试钉的是什么
 *
 * 钉的是**「有没有说出实话」**，不是具体措辞：
 *
 * - 三项不兑现的权限，文案里必须出现「不支持」
 * - 只有真会发生的风险才带 `⚠️`（`isHighRisk` 同理）
 *
 * 所以调措辞不会红（改字面值没关系），但谁把文案改回
 * 「⚠️ 可以执行系统命令」，这条测试就会红 —— 那正是它要防的那一步。
 */
class PermissionTextTest {

    /**
     * 三项「声明了但宿主不兑现」的权限，在清单里必须照实说。
     *
     * 注意这里**没有**要求把它们藏起来 —— 用户有权知道这个插件想要什么，
     * 只是不能让他以为已经给了。
     */
    @Test
    fun `宿主不支持的权限，文案里必须写出「不支持」`() {
        val lines = PluginPermissions(
            shell = true,
            linuxEnv = true,
            device = listOf(DeviceCapability.Contacts, DeviceCapability.Clipboard),
        ).describe()

        val shellLine = lines.single { it.startsWith("shell") }
        val ubuntuLine = lines.single { it.startsWith("Ubuntu") }
        val deviceLine = lines.single { it.startsWith("设备能力") }

        listOf(shellLine, ubuntuLine, deviceLine).forEach { line ->
            assertTrue(
                "这条没说明它当前不兑现，用户会以为插件真能做到：$line",
                line.contains("不支持"),
            )
        }
    }

    /**
     * `⚠️` 的含义是「这条真的会发生」—— 所以它只能挂在真会发生的风险上。
     *
     * `network` 是**真的会被执行**的：`PluginHost` 拿它构造 `NetworkGuard`，
     * 白名单外的主机在第一次连接时就被拦下。所以 `*` 值得一个 `⚠️`。
     *
     * 另外三项不是。给它们挂 `⚠️` 等于告诉用户「小心，它能读你联系人」，
     * 而它读不到。
     */
    @Test
    fun `只有真会发生的风险才带警告标记`() {
        val risky = PluginPermissions(network = listOf("*")).describe()
        assertTrue("网络 `*` 是真会发生的，该带 ⚠️：$risky", risky.any { it.startsWith("⚠️") })

        val notRisky = PluginPermissions(
            shell = true,
            linuxEnv = true,
            device = listOf(DeviceCapability.Contacts),
        ).describe()
        assertTrue(
            "这三项当前不兑现，不该带 ⚠️：$notRisky",
            notRisky.none { it.startsWith("⚠️") },
        )
    }

    /**
     * `isHighRisk` 决定安装页要不要把整张权限卡染红、标题换成「需要你留意」。
     *
     * 标红要对应一个**真会发生的风险**。为一个「声明了但拿不到」的东西
     * 把整张卡染红，是把用户往错的方向推 —— 他会基于一个假前提做决定。
     */
    @Test
    fun `只有真会发生的风险才算高危`() {
        assertTrue(
            "`network: *` 等于没有网络限制 ⇒ 高危",
            PluginPermissions(network = listOf("*")).isHighRisk,
        )
        assertFalse(
            "这三项当前不兑现，不该把整张卡染红",
            PluginPermissions(shell = true).isHighRisk,
        )
        assertFalse(
            "同上",
            PluginPermissions(linuxEnv = true).isHighRisk,
        )
        assertFalse(
            "同上",
            PluginPermissions(device = listOf(DeviceCapability.Contacts)).isHighRisk,
        )
    }

    /**
     * ⭐ `describe()` 里的 `⚠️` 和 [isHighRisk] 必须说的是**同一件事**。
     *
     * 这两条判据是**同一份风险的两种渲染**，但各自独立写着：
     *
     * - `describe()` 决定权限清单里**哪一行**带 `⚠️`
     * - `isHighRisk` 决定整张卡要不要染红、标题要不要换成「需要你留意」
     *
     * 两个消费者（安装页 / 插件详情页）都**同时**用这一对，所以只改一边
     * 的后果都是**静默**的：
     *
     * - 标红了却不说为什么 ⇒ 用户看到「需要你留意」却找不到要留意哪一条
     * - 有风险却不标红 ⇒ 用户扫过去，以为那是一条普通权限
     *
     * ⚠️ 上面那两条测试是**各自手写例子的清单**，它们今天恰好一致 ——
     * 但加一项高危权限、只改一边时，两边都不会红。所以这里改成**逐字段**过一遍：
     * 表里每一项单独打开一次，两种判据必须同进同退。
     *
     * 最后那条断言钉的是**这张表本身**：`PluginPermissions` 加了字段就必须
     * 回来给它一例，否则守卫会以「通过」的形式安静失效 —— 那比没有守卫更糟。
     */
    @Test
    fun `describe 的警告标记与 isHighRisk 说的是同一件事`() {
        val cases = linkedMapOf(
            "network = *" to PluginPermissions(network = listOf("*")),
            "network = 具体主机" to PluginPermissions(network = listOf("api.open-meteo.com")),
            "network = 空" to PluginPermissions(),
            "filesystem = read" to PluginPermissions(filesystem = FilesystemScope.Read),
            "filesystem = readwrite" to PluginPermissions(filesystem = FilesystemScope.ReadWrite),
            "shell = true" to PluginPermissions(shell = true),
            "device = 全部" to PluginPermissions(device = DeviceCapability.entries.toList()),
            "linuxEnv = true" to PluginPermissions(linuxEnv = true),
            "全开" to PluginPermissions(
                network = listOf("*"),
                filesystem = FilesystemScope.ReadWrite,
                shell = true,
                device = DeviceCapability.entries.toList(),
                linuxEnv = true,
            ),
        )

        cases.forEach { (name, permissions) ->
            assertEquals(
                "「$name」这一例上两条判据说法不一致 —— 要么标红了不说为什么，" +
                    "要么有风险却不标红，用户两种都看不出来",
                permissions.isHighRisk,
                permissions.describe().any { it.startsWith("⚠️") },
            )
        }

        val covered = setOf("network", "filesystem", "shell", "device", "linuxEnv")
        val actual = PluginPermissions::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name }
            .toSet()
        assertEquals(
            "PluginPermissions 的字段变了 —— 给新字段在上面那张表里加一例，" +
                "并想清楚它算不算高危（算的话 describe() 要给它一行 ⚠️）",
            covered,
            actual,
        )
    }

    /**
     * 「这次更新扩权了吗」这张真值表 —— 八种组合逐一钉死。
     *
     * 这里曾经只有「原来必须是 `none`」一条判据（写在 `PluginRepository.permissionDiff` 里），
     * 于是 `read → readwrite` 这种**真的**扩权被静默漏掉：只读插件写工作区会被
     * `PluginWorkspace` 拒掉（「只声明了 filesystem: "read"（只读），不能写工作区」），
     * 升级之后就写得进去了。漏报的后果是用户确认了一次「看起来没变」的更新，
     * 插件却多了一项能力。
     *
     * 反过来，收紧**不能**报 —— `permissionDiff` 的 KDoc：「每次更新都弹一堆
     * 『权限变了』会让人养成无脑点确认的习惯，真正危险的那次也就跟着被点掉了」。
     */
    @Test
    fun `文件系统权限的扩权真值表`() {
        val none = FilesystemScope.None
        val read = FilesystemScope.Read
        val readWrite = FilesystemScope.ReadWrite

        // 三条扩权
        assertTrue("none → read 是扩权", read.isExpansionOver(none))
        assertTrue("none → readwrite 是扩权", readWrite.isExpansionOver(none))
        assertTrue("read → readwrite 是扩权", readWrite.isExpansionOver(read))

        // 三条收紧
        assertFalse("readwrite → read 是收紧", read.isExpansionOver(readWrite))
        assertFalse("read → none 是收紧", none.isExpansionOver(read))
        assertFalse("readwrite → none 是收紧", none.isExpansionOver(readWrite))

        // 原地不动
        assertFalse("read → read 不算变化", read.isExpansionOver(read))
        assertFalse("none → none 不算变化", none.isExpansionOver(none))
    }
}
