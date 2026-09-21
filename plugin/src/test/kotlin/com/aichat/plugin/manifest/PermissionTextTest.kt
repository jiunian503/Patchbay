package com.aichat.plugin.manifest

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
}
