package com.aichat.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 版本比较的边界。
 *
 * ## 这个文件主要守的是一条**不会报错的错**
 *
 * `1.10` 和 `1.9` 哪个新？按字典序比是 `1.9`（`'9' > '1'`），按数值比是 `1.10`。
 * 这个错误在任何地方都不会抛异常、不会打日志 —— 它的表现只是「用户永远
 * 收不到更新」，而界面上还理直气壮地写着「已是最新」。
 *
 * 版本号到两位数是很平常的事（1.9 → 1.10），所以这条必须在有测试的时候钉住，
 * 而不是等它真的发生。
 */
class UpdateCheckTest {

    // ---------- 解析 ----------

    @Test
    fun `常见的几种写法都能解析`() {
        assertEquals(listOf(1), parseVersion("1"))
        assertEquals(listOf(1, 1), parseVersion("1.1"))
        assertEquals(listOf(1, 2, 0), parseVersion("1.2.0"))
        // git tag 的惯例：发布时打 `v1.1`，而 versionName 里没有 v
        assertEquals(listOf(1, 1), parseVersion("v1.1"))
        assertEquals(listOf(1, 1), parseVersion("V1.1"))
        assertEquals(listOf(1, 1), parseVersion("  1.1  "))
    }

    @Test
    fun `解析不出来时返回 null，而不是猜一个`() {
        // 每一条都对应一种真会出现的输入：空串（读不到 versionName）、
        // 打错的 tag、按日期命名的 tag、纯文字
        assertNull(parseVersion(""))
        assertNull(parseVersion("   "))
        assertNull(parseVersion("1."))
        assertNull(parseVersion(".1"))
        assertNull(parseVersion("1..1"))
        assertNull(parseVersion("2026-09"))
        assertNull(parseVersion("nightly"))
        assertNull(parseVersion("v"))
        // `vv1.1` 是打错的 tag，不该被宽容地解析成 1.1
        assertNull(parseVersion("vv1.1"))
        // 预发布版**故意不解析** —— 忽略后缀会得出「已是最新」，
        // 正好掩盖了「远端有个预发布版」这件事（见 parseVersion 的 KDoc）
        assertNull(parseVersion("1.1-beta"))
        assertNull(parseVersion("1.1-rc1"))
        // 负数不是版本号。`toIntOrNull` 会接受 "-1"，所以这里要显式挡住
        assertNull(parseVersion("-1"))
    }

    @Test
    fun `超长的数字解析失败而不是崩掉`() {
        // 这是在网络回调里跑的，抛异常既难查又毫无意义
        assertNull(parseVersion("99999999999999999999"))
    }

    // ---------- 比较 ----------

    @Test
    fun `按数值比而不是按字典序比`() {
        // 这个文件存在的全部理由。字典序会给出相反的结果
        assertEquals(
            "1.10 应当比 1.9 新 —— 按字典序比会得出相反的结论",
            1,
            compareVersions(parseVersion("1.10")!!, parseVersion("1.9")!!),
        )
        assertEquals(1, compareVersions(parseVersion("1.2")!!, parseVersion("1.1")!!))
        assertEquals(-1, compareVersions(parseVersion("1.1")!!, parseVersion("1.2")!!))
    }

    @Test
    fun `段数不同但指的是同一个版本`() {
        assertEquals(0, compareVersions(parseVersion("1.1")!!, parseVersion("1.1.0")!!))
        assertEquals(0, compareVersions(parseVersion("1")!!, parseVersion("1.0.0")!!))
        // 少写的那个 0 只是省了，不代表更小
        assertEquals(1, compareVersions(parseVersion("1.1.1")!!, parseVersion("1.1")!!))
    }

    // ---------- 合起来 ----------

    @Test
    fun `远端 tag 带 v 前缀时能对上当前版本`() {
        // 这是**实际发版的形态**：versionName 是 `1.1`，tag 是 `v1.1`。
        // 两边形态本来就不一样，所以规整放在 checkForUpdate 里做
        assertEquals(
            UpdateStatus.UpToDate(current = "1.1"),
            checkForUpdate(current = "1.1", latestTag = "v1.1"),
        )
    }

    @Test
    fun `远端更高时报有新版本，并原样带上 tag`() {
        val status = checkForUpdate(current = "1.1", latestTag = "v1.2")
        assertEquals(
            UpdateStatus.Newer(current = "1.1", latest = "v1.2"),
            status,
        )
        // `latest` 是**原样**的 tag：界面要显示 `v1.2` 而不是 `1.2`，
        // 因为用户去 Release 页面看到的就是那个名字
    }

    @Test
    fun `远端更旧时也算没有可更新的`() {
        // 自己装的是更靠前的构建（比如从源码编的）。这时候说「有新版」是错的，
        // 说「你装的比线上还新」又没人看得懂 —— 统一成「没有可更新的」
        assertEquals(
            UpdateStatus.UpToDate(current = "1.2"),
            checkForUpdate(current = "1.2", latestTag = "v1.1"),
        )
    }

    @Test
    fun `1_9 到 1_10 这一步不会被漏掉`() {
        // 版本号走到两位数时最常见的那一步
        val status = checkForUpdate(current = "1.9", latestTag = "v1.10")
        assertEquals(UpdateStatus.Newer(current = "1.9", latest = "v1.10"), status)
    }

    @Test
    fun `任一边解析不出来都是无法判断，而不是已是最新`() {
        // 这一条守的是「不要用保守的默认值掩盖看不懂的输入」。
        // 写成 UpToDate 的话，用户会看到一句**确定的错话**
        assertEquals(
            UpdateStatus.Undecidable(current = "1.1", latest = "nightly"),
            checkForUpdate(current = "1.1", latestTag = "nightly"),
        )
        assertEquals(
            UpdateStatus.Undecidable(current = "1.1", latest = ""),
            checkForUpdate(current = "1.1", latestTag = ""),
        )
        assertEquals(
            UpdateStatus.Undecidable(current = "", latest = "v1.1"),
            checkForUpdate(current = "", latestTag = "v1.1"),
        )
        // 两边都读不出来也是同一类
        assertEquals(
            UpdateStatus.Undecidable(current = "unknown", latest = "unknown"),
            checkForUpdate(current = "unknown", latestTag = "unknown"),
        )
    }
}
