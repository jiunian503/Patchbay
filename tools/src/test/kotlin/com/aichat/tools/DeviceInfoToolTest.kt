package com.aichat.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备信息工具。
 *
 * 假实现刻意做成「每个字段都能单独置空」—— 真实 ROM 上就是这样，
 * 有的能读电量读不到型号，有的反过来。工具必须在**任意子集**下都给出
 * 合理输出，而不是假定「要么全有要么全无」。
 */
class DeviceInfoToolTest {

    private class FakeSource(
        val model: String? = "Xiaomi 22041216C",
        val os: String? = "Android 15（API 35）",
        val battery: Int? = 76,
        val charging: Boolean? = true,
        val locale: String? = "zh-CN",
        val appVersion: String? = "1.0.0 (1)",
    ) : DeviceInfoSource {
        override fun deviceModel() = model
        override fun osVersion() = os
        override fun batteryPercent() = battery
        override fun isCharging() = charging
        override fun locale() = locale
        override fun appVersion() = appVersion
    }

    private fun tool(source: DeviceInfoSource) = DeviceInfoTool(source)

    @Test
    fun `字段齐全时全部列出`() = runTest {
        val result = tool(FakeSource()).execute(buildJsonObject { })

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("Xiaomi 22041216C"))
        assertTrue(result.content, result.content.contains("Android 15（API 35）"))
        assertTrue(result.content, result.content.contains("76%"))
        assertTrue(result.content, result.content.contains("充电中"))
        assertTrue(result.content, result.content.contains("zh-CN"))
        assertTrue(result.content, result.content.contains("1.0.0 (1)"))
    }

    @Test
    fun `未充电时标注不同`() = runTest {
        val result = tool(FakeSource(charging = false)).execute(buildJsonObject { })
        assertTrue(result.content, result.content.contains("未充电"))
    }

    /**
     * 充电状态读不到时**不能**默认成「未充电」。
     * 那会让模型把「插着电的平板」说成「快没电了」。
     */
    @Test
    fun `充电状态未知时不瞎标注`() = runTest {
        val result = tool(FakeSource(charging = null)).execute(buildJsonObject { })

        assertTrue(result.content, result.content.contains("76%"))
        assertFalse(result.content, result.content.contains("充电"))
        assertFalse(result.content, result.content.contains("未充电"))
    }

    @Test
    fun `缺字段时只输出读到的那些`() = runTest {
        val result = tool(FakeSource(model = null, battery = null, locale = null))
            .execute(buildJsonObject { })

        assertFalse(result.content, result.isError)
        assertTrue(result.content, result.content.contains("Android 15"))
        assertTrue(result.content, result.content.contains("1.0.0"))
        // 不该出现「型号：null」这种
        assertFalse(result.content, result.content.contains("型号"))
        assertFalse(result.content, result.content.contains("电量"))
        assertFalse(result.content, result.content.contains("null"))
    }

    /**
     * 一项都读不到时必须报错。
     *
     * 如果返回空串，模型会以为「这台设备就是没有信息」，然后开始编一个型号。
     * 报错至少能让它说「我读不到」。
     */
    @Test
    fun `全部读不到时明确报错`() = runTest {
        val result = tool(
            FakeSource(model = null, os = null, battery = null, locale = null, appVersion = null),
        ).execute(buildJsonObject { })

        assertTrue(result.isError)
        assertTrue(result.content, result.content.contains("读不到"))
    }

    @Test
    fun `多余参数被忽略而不是报错`() = runTest {
        // 模型有时会自作主张塞参数；无参工具不该因此失败
        val result = tool(FakeSource()).execute(
            buildJsonObject { put("unused", "whatever") },
        )
        assertFalse(result.content, result.isError)
    }

    @Test
    fun `工具定义里声明了拿不到用户身份信息`() {
        val definition = tool(FakeSource()).definition
        assertEquals(DeviceInfoTool.NAME, definition.name)
        assertTrue(
            "描述里应说明拿不到用户身份，避免模型去猜手机号",
            definition.description.contains("身份"),
        )
        assertFalse(tool(FakeSource()).requiresConfirmation)
    }
}
