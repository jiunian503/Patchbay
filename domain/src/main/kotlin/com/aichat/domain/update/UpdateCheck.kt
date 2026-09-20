package com.aichat.domain.update

/**
 * 「有没有新版本」这件事的**纯逻辑**部分。
 *
 * ## 为什么要单独一层
 *
 * 判断「1.10 比 1.9 新」是个字符串比较的经典陷阱：按字典序比会得出
 * **1.9 更新**（因为 `'9' > '1'`）。这个错误不会报错，只会让用户看到
 * 「已是最新」而永远收不到更新 —— 最难发现的那种失败。
 *
 * 而它完全可以脱离网络、脱离 Android 穷举测试。所以放在 `:domain`：
 * 输入是「当前版本名」和「远端 tag」两个字符串，输出是一个三态结果。
 *
 * ## 三态，不是两态
 *
 * 刻意**不**做成 `Boolean`（有没有新版）。因为「解析不出来」和「没有新版」
 * 是两件完全不同的事，而它们必须给用户不同的话：
 *
 * - 没有新版 → 「已是最新」—— 一个确定的结论
 * - 解析不出来 → 「无法判断」—— 一个诚实的拒绝
 *
 * 把后者塞进前者的话，一个 tag 写成 `2026-09` 的版本会被永远判成「已是最新」，
 * 而用户以为自己在用最新的。
 */

/** 「当前版本」和「远端最新」比出来的结果。 */
sealed interface UpdateStatus {

    /** 远端不比当前新。**包含「远端更旧」**（比如自己装的是更靠前的构建）。 */
    data class UpToDate(val current: String) : UpdateStatus

    /** 远端更新。[latest] 是原样的 tag（可能带 `v` 前缀），显示时不要再加工。 */
    data class Newer(val current: String, val latest: String) : UpdateStatus

    /**
     * 至少有一边解析不出数字版本号，所以**比不了**。
     *
     * 两边都原样带出来 —— 界面要如实说「没看懂哪个」，而不是含糊地说「检查失败」。
     */
    data class Undecidable(val current: String, val latest: String) : UpdateStatus
}

/**
 * 把版本串解析成一串数字，用于比较。**解析不出来返回 null，不猜。**
 *
 * 接受的形态：`1` · `1.1` · `1.2.0` · `v1.1` · `V1.1`（前缀 `v` 是 git tag 的常见写法）。
 *
 * 拒绝的形态（返回 null）：`""` · `1.` · `.1` · `1.1-beta` · `2026-09` · `nightly`。
 *
 * ## 为什么对后缀这么严
 *
 * 一度想过「遇到非数字就把后面的都忽略」，那样 `1.1-beta` 会解析成 `[1, 1]`。
 * 但那个宽容**恰好会掩盖最需要看见的情况**：远端发了一个预发布版
 * `v1.1-rc1`，而用户装的是 `1.1` —— 忽略后缀就会得出「已是最新」。
 *
 * 而这个项目的 release 走 `/releases/latest`，GitHub 那个端点**本来就不返回
 * 预发布版和草稿**。所以严格拒绝的代价几乎为零，而它换来的是：真遇到不认识的
 * tag 时界面会说「无法判断」，而不是给出一个可能错的结论。
 *
 * 数字部分用 [toIntOrNull] 而不是 `toInt()`：一个 20 位的 tag 会让后者抛异常，
 * 而那是在一个网络回调里 —— 崩在那儿既难查又毫无意义。
 */
fun parseVersion(raw: String): List<Int>? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    // 只剥**一个**前导 v/V（`vv1.1` 应当解析失败，那是打错的 tag）
    val body = if (trimmed.first() == 'v' || trimmed.first() == 'V') {
        trimmed.substring(1)
    } else {
        trimmed
    }
    if (body.isEmpty()) return null

    val parts = body.split('.')
    val numbers = parts.map { part ->
        // `part.isEmpty()` 挡住 `1.` 和 `.1`；`all { isDigit }` 挡住 `1-beta`
        // 和负号（`toIntOrNull` 会接受 "-1"，但版本号里没有负数）
        if (part.isEmpty() || !part.all { it in '0'..'9' }) return null
        part.toIntOrNull() ?: return null
    }
    return numbers
}

/**
 * 按**数值**逐段比较两个版本号。
 *
 * 缺的段当 0：`1.1` 和 `1.1.0` 相等，`1.1` 和 `1.1.1` 是后者大。
 * 长度不同不算「不一样」—— 这两种写法指的是同一个版本。
 *
 * 返回值沿用 [Comparable] 的约定：负数表示 [a] 小。
 */
fun compareVersions(a: List<Int>, b: List<Int>): Int {
    val length = maxOf(a.size, b.size)
    for (i in 0 until length) {
        val left = a.getOrElse(i) { 0 }
        val right = b.getOrElse(i) { 0 }
        if (left != right) return left.compareTo(right)
    }
    return 0
}

/**
 * 拿「当前装的版本名」和「远端 release 的 tag」比一下。
 *
 * [current] 是 `versionName`（如 `1.1`），[latestTag] 是 GitHub 的 `tag_name`
 * （如 `v1.1`）—— 两边形态本来就不一样，所以剥前缀这件事在这里做，
 * 而不是要求调用方先规整好。调用方少一步，就少一个忘掉的机会。
 *
 * 两边**任何一边**解析不出来都返回 [UpdateStatus.Undecidable]，
 * 而不是「保守地当作没有新版」—— 理由见 [UpdateStatus] 的 KDoc。
 */
fun checkForUpdate(current: String, latestTag: String): UpdateStatus {
    val currentParts = parseVersion(current)
    val latestParts = parseVersion(latestTag)

    if (currentParts == null || latestParts == null) {
        return UpdateStatus.Undecidable(current = current, latest = latestTag)
    }

    // 只有**远端更靠前**才算有新版本。远端更旧时（自己装的是更靠前的构建）
    // 结论同样是「没有可更新的」—— 这比说「你装的比线上还新」更少歧义，
    // 那种情况本来也不该出现在正式发版的用户身上
    return if (compareVersions(latestParts, currentParts) > 0) {
        UpdateStatus.Newer(current = current, latest = latestTag)
    } else {
        UpdateStatus.UpToDate(current = current)
    }
}
