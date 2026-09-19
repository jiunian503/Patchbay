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

    override fun appVersion(): String? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = info.versionName ?: return@runCatching null
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "$name ($code)"
    }.getOrNull()

    /**
     * 电量广播是「粘性」的：传 null 的 receiver 不会真的注册，
     * 只是把最近一次广播的内容取回来。所以这里不需要权限，
     * 也不需要在生命周期里注销 —— 用完即走。
     */
    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
}
