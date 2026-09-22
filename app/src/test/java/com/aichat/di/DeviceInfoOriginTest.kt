package com.aichat.di

import com.aichat.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「读设备信息的地方**只有一处**」是**断言**，不是描述。
 *
 * ## 三处注释都这么写，但此前没有东西守着
 *
 * | 在哪 | 原话 |
 * |---|---|
 * | [AndroidDeviceInfo] 的 `packageInfo()` | 「**整个仓库只有这里读 `versionName`**」 |
 * | `PatchbayApp.installCrashLogging` | 「它已经是这个仓库里**唯一一处**读 `versionName` / `Build.MODEL` 的地方」 |
 * | `AppContainer.appVersionName` | 「它已经是这个仓库里**唯一读 `versionName` 的地方**」 |
 *
 * 三句说的是同一件事，而且都写明了破了会怎样：「再写一份的话，两份迟早会给出
 * 不一样的版本号，而两份都『看着对』」。
 *
 * 但在本文件之前**没有任何东西会红** —— 有人在别处再写一遍「读版本号」，三句话
 * 同时变成假的，构建照样绿。这正是「声明与实现是两份东西」那个形状，和
 * `PluginHost` 的「校验已经排除了的情况」、`DeclarativeTool` 的「这条语义只有一份
 * 实现」是同一类（那两处各自也已经收口并有了守卫）。
 *
 * ## 它守的是什么、不是什么
 *
 * - 守：**读包信息 / 读机型的那几个 API 只在一个文件里出现**
 * - 不守：[AndroidDeviceInfo] 被 new 了几次 —— 它现在被 new 了三次
 *   （`PatchbayApp` / `AppContainer` 两处），三次都合法
 *
 * ## 空集合恒过
 *
 * 扫描要是哪天一个都匹配不到（改了写法、模块列表解析失败），下面的断言会「通过」得
 * 毫无意义。所以每条都先断**不是空的** —— 匹配不到时**宁可红**：那说明本文件要跟着改。
 *
 * 扫描的公共设施在 [SourceScan]（和 `NetworkEgressTest` 共用一份，不是各写一遍）。
 */
class DeviceInfoOriginTest {

    @Test
    fun `读包信息的 API 只在 AndroidDeviceInfo 里`() {
        assertOnlyIn(GET_PACKAGE_INFO)
    }

    @Test
    fun `读机型的 API 只在 AndroidDeviceInfo 里`() {
        assertOnlyIn(BUILD_MODEL)
        assertOnlyIn(BUILD_BRAND)
    }

    private fun assertOnlyIn(marker: String) {
        val hits = SourceScan.scanFiles(marker)

        assertTrue(
            "生产代码里一个 `$marker` 都没扫到 —— 声明写法变了，这条断言会变成空转。\n" +
                "要改写法就一起改本文件的标记。",
            hits.isNotEmpty(),
        )

        assertEquals(
            "`$marker` 只能出现在 $OWNER 里，现在这些地方也有：${hits - OWNER}\n" +
                "三处注释（AndroidDeviceInfo / PatchbayApp / AppContainer）都写着「唯一一处」，\n" +
                "并且都写明后果：两份迟早会给出不一样的版本号，而两份都「看着对」。\n" +
                "要么改回用 AndroidDeviceInfo，要么把那三句话一起改掉。",
            setOf(OWNER),
            hits,
        )
    }

    private companion object {
        /** 唯一允许读设备/包信息的文件（相对仓库根）。 */
        const val OWNER = "app/src/main/java/com/aichat/di/AndroidDeviceInfo.kt"

        const val GET_PACKAGE_INFO = "getPackageInfo"
        const val BUILD_MODEL = "Build.MODEL"
        const val BUILD_BRAND = "Build.BRAND"
    }
}
