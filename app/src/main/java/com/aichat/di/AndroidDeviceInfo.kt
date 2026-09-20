package com.aichat.di

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.aichat.tools.DeviceInfoSource
import java.util.Locale

/**
 * [DeviceInfoSource] 的 Android 实现。
 *
 * ## 每个方法都可能返回 null，这是设计而不是偷懒
 *
 * 这些 API 在不同 ROM 上行为不一致：电量广播在部分定制系统上不粘性、
 * `Build.MODEL` 偶尔是空串、`versionName` 在某些打包方式下取不到。
 * 与其在拿不到时编一个值（那会让模型一本正经地报错误的电量），
 * 不如返回 null，让工具只输出真正读到的字段。
 *
 * 另外**刻意不提供任何可以定位到人的信息**：没有 IMEI、没有序列号、
 * 没有手机号、没有账号。工具描述里也写明了它拿不到这些 ——
 * 免得模型被问到「我手机号多少」时去猜。
 */
class AndroidDeviceInfo(private val context: Context) : DeviceInfoSource {

    override fun deviceModel(): String? {
        val brand = Build.BRAND.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        if (model.isEmpty()) return null
        // 很多 ROM 的 MODEL 已经含品牌（"Xiaomi 14"），再拼一次会变成
        // "Xiaomi Xiaomi 14"。先做包含判断。
        return if (brand.isEmpty() || model.startsWith(brand, ignoreCase = true)) {
            model
        } else {
            "${brand.replaceFirstChar { it.uppercase() }} $model"
        }
    }

    override fun osVersion(): String? =
        "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"

    override fun batteryPercent(): Int? = batteryIntent()?.let { intent ->
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return@let null
        (level * 100f / scale).toInt().coerceIn(0, 100)
    }

    override fun isCharging(): Boolean? = batteryIntent()?.let { intent ->
        when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL,
            -> true

            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            -> false

            // 其它状态（UNKNOWN）就别猜了
            else -> null
        }
    }

    override fun locale(): String? = Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() }

    /**
     * 读一次包信息。`runCatching` 是因为 `getPackageInfo` 在某些打包方式下会抛
     * `NameNotFoundException` —— 拿不到就是拿不到，不编一个值出来。
     *
     * **整个仓库只有这里读 `versionName`**（见 `PatchbayApp.installCrashLogging`
     * 的注释）：再写一份的话，迟早会和这一份给出不一样的版本号，而两份都"看着对"。
     */
    private fun packageInfo() = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()

    override fun appVersion(): String? = packageInfo()?.let { info ->
        val name = info.versionName ?: return@let null
        // minSdk 是 28（= P），所以 longVersionCode 一定可用。
        // 这里原本有个 `SDK_INT >= P` 的分支 + 一个 @Suppress 的 versionCode
        // 回退，lint 的 ObsoleteSdkInt 指出它恒真 —— 是 minSdk 抬高之后
        // 留下来的死代码。
        "$name (${info.longVersionCode})"
    }

    /**
     * **裸的**版本名，形如 `1.1`，不带 `versionCode`。
     *
     * 和 [appVersion] 的区别就是那个 `(2)`：那个字符串是给**模型**看的
     * （`device_info` 工具的返回值），而「检查更新」要拿版本号和远端的 tag 比，
     * 比不了带括号的形态。
     *
     * 读不到时返回 null，由调用方决定怎么说 —— 这里不返回一个「未知」的占位串，
     * 那种串会被拿去参与比较，然后得出一个看着很确定的错结论。
     */
    fun versionName(): String? = packageInfo()?.versionName?.takeIf { it.isNotBlank() }

    /**
     * 电量广播是「粘性」的：传 null 的 receiver 不会真的注册，
     * 只是把最近一次广播的内容取回来。所以这里不需要权限，
     * 也不需要在生命周期里注销 —— 用完即走。
     */
    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
}
