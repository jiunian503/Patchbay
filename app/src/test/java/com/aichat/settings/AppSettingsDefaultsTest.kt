package com.aichat.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「让 AI 跑命令」的**默认值必须是关**。
 *
 * ## 为什么要为一行常量写一条测试
 *
 * 这是个**安全默认值**，不是产品偏好：打开它意味着模型可以改用户设备上的东西
 * （它跑起来和 App 有一样的权限）。而它的兄弟开关
 * [AppSettings.DEFAULT_LONG_TERM_MEMORY] 默认是 **true** ——
 * 两个常量紧挨着放在 `companion object` 里，形状一样、名字一样，
 * 谁顺手复制一行就把这个安全默认值改掉了，而且**不会有任何报错**：
 * 全新安装的用户会直接进入「模型能跑命令」的状态，他还不知道有这回事。
 *
 * 所以这里把「必须是 false」写成可执行的断言，而不是留在注释里。
 * 这条测试红了，说明有人动了它 —— 那就该有人认真回答一次
 * 「为什么这次要把默认值改成开」。
 *
 * ## 不测的部分
 *
 * [AppSettings] 本身要 `Context`（SharedPreferences），而 `:app` 的单测里
 * 没有 Robolectric，所以读写行为只能靠真机验收。这里守的是那个**常量**。
 */
class AppSettingsDefaultsTest {

    @Test
    fun `让 AI 跑命令默认关`() {
        assertFalse(
            "「让 AI 跑命令」的默认值被改成 true 了。这个开关是唯一一个" +
                "「打开就等于把设备交给模型」的能力，默认开会让全新安装的用户" +
                "在不知情的情况下进入这个状态。要改的话先想清楚为什么。",
            AppSettings.DEFAULT_SHELL_ENABLED,
        )
    }

    /**
     * 反过来把长期记忆那条也钉住。
     *
     * 不是为了「不许改」，是为了让**两个默认值不一致这件事本身**被看见：
     * 它们长得很像，答案却相反，而理由是「代价能不能撤销」。
     * 谁要是把它们统一了，这条会红，于是他至少会读一遍那段推导。
     */
    @Test
    fun `长期记忆默认开`() {
        assertTrue(AppSettings.DEFAULT_LONG_TERM_MEMORY)
    }
}
