package com.aichat.domain.character

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater

/**
 * PNG 里的一个文本块，三种块（`tEXt` / `iTXt` / `zTXt`）归一后的形态。
 *
 * @property keyword 块的关键字（如 `chara`）。规范要求它是 Latin-1，
 *   而实际生态里全是 ASCII，所以按 ASCII 解 —— 解错了也只是这一个块被跳过。
 * @property text 块的正文。三种块最终都归一到这一个字符串。
 */
internal data class PngText(val keyword: String, val text: String)

/**
 * 把 PNG 里的文本块读出来 —— 角色卡就藏在其中一个块里。
 *
 * ## 为什么手写，不引 PNG 解析库
 *
 * 我们要的只有「遍历块、挑出文本块」这一件事，而 PNG 的块结构就是
 * `[长度][类型][数据][CRC]` 四段循环。引一个库换来的是一个**会跟着升级的
 * 依赖**，外加一整套解码像素的能力 —— 而我们一个像素都不看。
 *
 * ## 为什么不校验 CRC
 *
 * 每个块末尾有 CRC32，校验它能发现「文件在传输中坏了」。但对我们这个用途
 * 它是**多余且有害**的：
 *
 * - 真正要的完整性保证来自后面两步 —— Base64 解码和 JSON 解析。一个字节坏了，
 *   那两步几乎必然失败，而报出来的错比「CRC 不匹配」更接近用户能理解的东西
 * - 有些工具（包括手写脚本）改写文本块时**不重算 CRC**。严格校验会把一张
 *   内容完好的卡拒之门外，而用户看到的是「这不是有效的角色卡」
 *
 * ## 读不下去就停，不抛异常
 *
 * 块长度超出文件末尾、关键字里没有终止符 —— 这类情况下**停止遍历并返回已经
 * 读到的**。判据是「有没有拿到角色卡数据」，由调用方判断；在这里抛异常等于
 * 把「图片尾部被截断」这种与角色卡无关的损坏，报成「导入失败」。
 */
internal object Png {

    /** PNG 的 8 字节签名：`\x89PNG\r\n\x1a\n`。 */
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** 签名对不对。调用方据此给出「这不是 PNG」这种**具体**的错。 */
    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= SIGNATURE.size && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

    /**
     * 读出所有文本块，**保持文件里的先后顺序**。
     *
     * 顺序有意义：SillyTavern 导出的新卡里 `ccv3` 和 `chara` 会同时存在，
     * 而调用方要按「`ccv3` 优先」挑 —— 不是按谁先出现。
     */
    fun readTexts(bytes: ByteArray): List<PngText> {
        if (!isPng(bytes)) return emptyList()

        val found = ArrayList<PngText>()
        var pos = SIGNATURE.size

        // 一个块至少 12 字节：4 长度 + 4 类型 + 0 数据 + 4 CRC。
        // 上界这样写是为了让「读到文件末尾」自然结束，不用额外判断
        while (pos + 12 <= bytes.size) {
            val length = readBigEndianInt(bytes, pos)
            val type = String(bytes, pos + 4, 4, StandardCharsets.US_ASCII)
            val dataStart = pos + 8

            // ⚠️ 末尾偏移要用 Long 算。长度最大可以是 2^31-1，加到偏移上会**溢出成
            // 负数** —— 而负的「末尾」会让下面那条边界检查整体失效（负数当然小于
            // 文件大小），于是循环拿着一个负的 pos 继续跑，下一次 readBigEndianInt
            // 直接抛数组越界。**边界检查自己也会被溢出绕过。**
            val dataEnd = dataStart.toLong() + length

            // 长度是坏的（负数 / 超出文件末尾）⇒ 后面每一个块的边界都不可信，停
            if (length < 0 || dataEnd > bytes.size) break

            // 到这里 dataEnd 已经保证 ≤ bytes.size，转回 Int 是安全的
            val end = dataEnd.toInt()

            when (type) {
                "tEXt" -> decodeText(bytes, dataStart, end)?.let(found::add)
                "iTXt" -> decodeItext(bytes, dataStart, end)?.let(found::add)
                "zTXt" -> decodeZtxt(bytes, dataStart, end)?.let(found::add)
            }

            // IEND 之后没有块了（规范如此）。不 break 也能自然结束，
            // 但显式写出来更省得下一个人去数边界
            if (type == "IEND") break
            pos = end + 4 // 跳过 CRC
        }
        return found
    }

    /**
     * `tEXt`：`关键字 \0 正文`。
     *
     * 正文按 **UTF-8** 解，虽然规范说的是 Latin-1。理由：我们要的正文是 Base64
     * （纯 ASCII，两种解都一样），而生态里确实有人往 `tEXt` 里直接塞中文
     * （非标准，但存在）—— 那种情况按 Latin-1 解会变成乱码，按 UTF-8 解反而对。
     * 这个选择**只在「有人违反规范」时才起作用**，而那时它是对的。
     */
    private fun decodeText(bytes: ByteArray, start: Int, end: Int): PngText? {
        val nul = indexOfZero(bytes, start, end) ?: return null
        return PngText(
            keyword = String(bytes, start, nul - start, StandardCharsets.US_ASCII),
            text = String(bytes, nul + 1, end - nul - 1, StandardCharsets.UTF_8),
        )
    }

    /**
     * `iTXt`：`关键字 \0 压缩标志(1) 压缩方法(1) 语言 \0 译名 \0 正文`。
     *
     * 正文按 UTF-8 解（这是规范要求的，和 `tEXt` 不同）。压缩标志为 1 时
     * 正文是一段 zlib 流。语言标签和译名我们不关心，只跳过。
     */
    private fun decodeItext(bytes: ByteArray, start: Int, end: Int): PngText? {
        val kwEnd = indexOfZero(bytes, start, end) ?: return null
        var p = kwEnd + 1
        if (p + 2 > end) return null
        val compressed = bytes[p].toInt() == 1
        p += 2 // 压缩标志 + 压缩方法
        p = (indexOfZero(bytes, p, end) ?: return null) + 1 // 语言标签
        p = (indexOfZero(bytes, p, end) ?: return null) + 1 // 译名

        val raw = bytes.copyOfRange(p, end)
        val text =
            if (compressed) {
                inflate(raw)?.toString(StandardCharsets.UTF_8) ?: return null
            } else {
                raw.toString(StandardCharsets.UTF_8)
            }
        return PngText(String(bytes, start, kwEnd - start, StandardCharsets.US_ASCII), text)
    }

    /** `zTXt`：`关键字 \0 压缩方法(1) 压缩正文`。 */
    private fun decodeZtxt(bytes: ByteArray, start: Int, end: Int): PngText? {
        val kwEnd = indexOfZero(bytes, start, end) ?: return null
        val dataStart = kwEnd + 2 // 跳过 \0 和「压缩方法」那一字节
        if (dataStart > end) return null
        val text = inflate(bytes.copyOfRange(dataStart, end))?.toString(StandardCharsets.UTF_8)
            ?: return null
        return PngText(String(bytes, start, kwEnd - start, StandardCharsets.US_ASCII), text)
    }

    /**
     * 解 zlib 流。解不开返回 `null`（不抛）—— 调用方据此判定「这个块读不了」。
     *
     * 循环 `inflate` 而不是一次到位：输出缓冲区是我们给的，一次可能填不满，
     * 而 `inflate` 返回 0 只在**它无法继续**时发生（需要更多输入 / 需要字典 /
     * 已经结束）。所以 `n <= 0` 就跳出，既不会漏数据也不会死循环。
     */
    private fun inflate(data: ByteArray): ByteArray? = runCatching {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream((data.size.coerceAtLeast(64)) * 4)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }.getOrNull()

    /** [start, end) 里第一个 `\0` 的下标，没有就是 `null`。 */
    private fun indexOfZero(bytes: ByteArray, start: Int, end: Int): Int? {
        for (i in start until end) if (bytes[i].toInt() == 0) return i
        return null
    }

    /** PNG 的块长度是**大端** 4 字节。 */
    private fun readBigEndianInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
}
