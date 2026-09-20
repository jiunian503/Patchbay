package com.aichat.ui.plugins

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 插件工作区那一块用到的两个小工具：把字节数说成人话、按上限读一个流。
 *
 * ## 为什么 `readCappedBytes` 没有和 `:domain` 的 `readCapped` 合并
 *
 * 那个是**按字符**的上限、读出来是 `String`，它的两个消费者（读清单文件、
 * 读 HTTP 响应体）要的都是文本。这里是**按字节**的上限、读出来是
 * `ByteArray` —— 因为导入的文件不能经过「解码成字符串再编码回去」这一趟：
 * 一份 GBK 编码的中文 CSV 会在那一趟里损坏，而界面上什么都看不出来。
 *
 * 两者的语义不同（字符 vs 字节），硬合并会让其中一边被迫接受另一边的规则。
 * 将来真有第二个「按字节读」的消费者，再把它挪到 `:domain` —— 那时候
 * 才有挪的理由（`readCapped` 当年就是这么被抽出去的）。
 */

/** [readCappedBytes] 的结果。 */
internal sealed interface CappedBytes {
    /**
     * 读到了。用普通类不用 `data class` —— `ByteArray` 的 `equals` 是引用比较，
     * `data class` 生成的 `equals` 会让人以为它在比内容。
     */
    class Ok(val bytes: ByteArray) : CappedBytes

    /** 超过了上限，已经停手（没有把整个流读进来）。 */
    data object TooLarge : CappedBytes
}

/**
 * 读一个输入流，但**最多**读 [maxBytes] 个字节。
 *
 * ## 为什么必须「先判再拼」
 *
 * 和 `:domain` 的 `readCapped` 是同一条纪律：
 *
 * ```kotlin
 * out.write(buffer, 0, n)            // 先拼
 * if (out.size() > maxBytes) ...     // 再判  ← 错
 * ```
 *
 * 这样写在大文件上一样会 OOM：`write` 发生在判断之前，而 `n` 最大是整个
 * 缓冲区。真正的保护是**在写入之前**就确认「加上这一块会不会超」——
 * 超限时最多多读了 8 KB。
 *
 * [maxBytes] 刻意没有默认值：上限该设多少取决于场景，给默认值等于
 * 让调用方不必想这件事。
 */
internal fun readCappedBytes(input: InputStream, maxBytes: Int): CappedBytes {
    require(maxBytes >= 0) { "maxBytes 不能为负数，收到 $maxBytes" }

    val buffer = ByteArray(8 * 1024)
    val out = ByteArrayOutputStream()
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return CappedBytes.Ok(out.toByteArray())
        if (out.size() + n > maxBytes) return CappedBytes.TooLarge
        out.write(buffer, 0, n)
    }
}

/**
 * 把字节数说成人话。
 *
 * ## 为什么不用 `String.format("%.1f MB", ...)`
 *
 * 那个走默认 `Locale`，小数点在某些地区会变成逗号（`1,2 MB`）——
 * 而这个字符串会出现在「超过 1 MB 的单文件上限」这种话里，用户要拿它
 * 和自己看到的文件大小对照。要么显式传 `Locale.US`，要么就做整数运算。
 * 整数运算更省事，也不会有人哪天顺手把 locale 参数删掉。
 */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> {
        // 一位小数，用整数算出十位 —— 8 MB 显示成 "8.0 MB"
        val tenths = bytes * 10 / (1024 * 1024)
        "${tenths / 10}.${tenths % 10} MB"
    }
}
