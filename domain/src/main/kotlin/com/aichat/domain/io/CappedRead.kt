package com.aichat.domain.io

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** [readCappedBytes] 的结果。和 [CappedRead] 同形，只是装的是字节。 */
sealed interface CappedBytes {
    /**
     * **刻意不是 `data class`**：它装的数组用 `equals` 比的是**引用**，
     * 而 `data class` 会生成一个看起来能用、实际几乎永远返回 `false` 的
     * `equals` —— 那是比没有更糟的误导。要比内容请用 `contentEquals`。
     */
    class Ok(val bytes: ByteArray) : CappedBytes

    data object TooLarge : CappedBytes
}

/**
 * 读一个输入流，但**最多**读 [maxBytes] 个字节。
 *
 * ## 为什么不复用 [readCapped]
 *
 * 那个返回 `String` —— 它走的是字符流，会把无效的 UTF-8 字节替换成 `�`。
 * 这对「读一份 JSON 清单」无所谓（清单本来就该是文本），但对**二进制**是致命的：
 * 一张 PNG 被替换掉几个字节之后内容就变了，而报出来的错会是「这不是有效的
 * 角色卡」—— 指不到「是我们自己读坏的」这个真原因。
 *
 * ## 「先判再拼」在这里同样重要
 *
 * 同 [readCapped]：必须在 `write` **之前**确认这一块会不会超限。否则超限那一刻
 * 整个流已经进内存了，而这条上限防的正是 OOM。有用例钉住「提前停手」。
 */
fun readCappedBytes(input: InputStream, maxBytes: Int): CappedBytes {
    require(maxBytes >= 0) { "maxBytes 不能为负数，收到 $maxBytes" }

    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return CappedBytes.Ok(out.toByteArray())
        // 先判再拼，理由同上
        if (out.size() + n > maxBytes) return CappedBytes.TooLarge
        out.write(buffer, 0, n)
    }
}

/** [readCapped] 的结果。三态而不是可空，因为「太大」和「读到了空内容」要区别对待。 */
sealed interface CappedRead {
    data class Ok(val text: String) : CappedRead
    data object TooLarge : CappedRead
}

/**
 * 读一个输入流，但**最多**读 [maxChars] 个字符。
 *
 * ## 为什么放在 `:domain`
 *
 * 因为它有**两个消费者**，而且它们必须行为一致：
 *
 * - `:app` 读用户从文件选择器挑的清单（「从文件」入口）
 * - `:network` 读 HTTP 响应体（「从 URL」入口）
 *
 * 两边各写一份的话，「先判再拼」这个关键细节很容易在其中一份里丢掉 ——
 * 丢掉的后果不是报错，而是**那条上限在最需要它的场景下失效**（见下）。
 * 这和 `TextWindow` 是同一类判断：受众不同但逻辑相同，就抽出来。
 *
 * ## 为什么要设上限
 *
 * 用户点「从文件」时完全可能手滑选到一个几百 MB 的视频 —— 文件选择器拦不住
 * （清单就是个 `.json`，没有专属 MIME 类型可过滤）。从 URL 读同理：
 * 对方回一个 2 GB 的响应体，或者干脆是个不会结束的流。
 * 直接 `readText()` 会 OOM，而且是在一个「装插件」的页面上 OOM，
 * 报错信息完全指不到真实原因。
 *
 * ## 为什么超限时报错，而不是截断
 *
 * 截断出来的 JSON 一定解析不了。用户看到的是「第 3000 行语法错误」，
 * 比「这个东西太大了」难懂得多 —— 而且他会去改那份 JSON。
 *
 * ## 为什么必须「先判再拼」
 *
 * ```kotlin
 * out.append(buffer, 0, n)          // 先拼
 * if (out.length > maxChars) ...    // 再判  ← 错
 * ```
 *
 * 这样写在大文件上一样会 OOM：`append` 发生在判断之前，而 `n` 最大是
 * 整个缓冲区。真正的保护是**在追加之前**就确认「加上这一块会不会超」——
 * 这样超限时最多多读了 8 KB，而不是把整个流读进内存再报错。
 * 有一条用例（`CountingStream`）专门钉住「提前停手」。
 *
 * [maxChars] 刻意**没有默认值**：上限该设多少取决于场景
 * （清单 256 KB、别的可能不同），给默认值等于让调用方不必想这件事。
 */
fun readCapped(input: InputStream, maxChars: Int): CappedRead {
    require(maxChars >= 0) { "maxChars 不能为负数，收到 $maxChars" }

    val reader = input.bufferedReader()
    val buffer = CharArray(8 * 1024)
    val out = StringBuilder()
    while (true) {
        val n = reader.read(buffer)
        if (n < 0) return CappedRead.Ok(out.toString())
        // 先判再拼：不然一个 2 GB 的流还是会先把内存吃满
        if (out.length + n > maxChars) return CappedRead.TooLarge
        out.append(buffer, 0, n)
    }
}
