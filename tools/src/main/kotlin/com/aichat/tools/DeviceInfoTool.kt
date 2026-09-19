package com.aichat.tools

import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * 设备与运行环境信息。
 *
 * ## 为什么用窄接口而不是直接读 Android API
 *
 * 直接 `import android.os.Build` 的话，`:tools` 就必须是 Android library，
 * 每个用例都要上模拟器跑（分钟级）。而这个工具里真正需要测的逻辑
 * （字段怎么拼、缺字段怎么降级、电量怎么换算）跟 Android 一点关系都没有。
 *
 * 所以把「数据从哪来」抽成 [DeviceInfoSource]，Android 实现放在 `:app`，
 * 单测里塞一个假实现 —— 全部逻辑秒级覆盖。
 */
interface DeviceInfoSource {

    /** 例如 `Xiaomi 22041216C`。拿不到就返回 null，不要编。 */
    fun deviceModel(): String?

    /** 例如 `Android 15（API 35）`。 */
    fun osVersion(): String?

    /** 电池电量百分比，0–100。拿不到返回 null。 */
    fun batteryPercent(): Int?

    /** 是否正在充电。 */
    fun isCharging(): Boolean?

    /** 界面语言，例如 `zh-CN`。 */
    fun locale(): String?

    /** 应用版本，例如 `1.0.0 (1)`。 */
    fun appVersion(): String?
}

class DeviceInfoTool(private val source: DeviceInfoSource) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        description = "获取当前设备的型号、系统版本、电量、语言等运行环境信息。" +
            "用户问「我手机什么型号」「还有多少电」或者需要针对设备给建议时调用。" +
            "不要用它来获取用户身份信息 —— 它拿不到，也不该拿。",
        // 无参数。仍然要显式给一个空的 properties：
        // 部分网关对缺 properties 的 tools 会直接 400。
        parameters = schema(properties = emptyMap()),
    )

    override val userSummary: String
        get() = "读取这台设备的型号、系统版本、电量和界面语言"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val lines = buildList {
            source.deviceModel()?.let { add("设备型号：$it") }
            source.osVersion()?.let { add("系统：$it") }
            source.batteryPercent()?.let { percent ->
                val charging = source.isCharging()
                val suffix = when (charging) {
                    true -> "（充电中）"
                    false -> "（未充电）"
                    null -> ""
                }
                add("电量：$percent%$suffix")
            }
            source.locale()?.let { add("界面语言：$it") }
            source.appVersion()?.let { add("应用版本：$it") }
        }

        // 一项都读不到说明注入的实现有问题，要明确报错而不是返回空串 ——
        // 空结果会让模型以为「设备就是没有信息」，然后开始编
        if (lines.isEmpty()) {
            return ToolResult.error("读不到任何设备信息（DeviceInfoSource 没有提供数据）。")
        }
        return ToolResult.ok(lines.joinToString("\n"))
    }

    companion object {
        const val NAME = "device_info"
    }
}
