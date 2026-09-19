package com.aichat.ui.conversations

import com.aichat.chat.ChatMessage
import com.aichat.chat.MessageStatus
import com.aichat.chat.StoredMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次「导出 Markdown」的产物：**要写进文件的文本**和**建议的文件名**。
 *
 * 刻意只是一份数据，不带 Uri、不带 Context —— 落盘那一步在界面层做
 * （SAF 的 `ACTION_CREATE_DOCUMENT` 需要一个 Activity 结果回调）。
 * 分开之后，「导出的内容对不对」这件事能在 JVM 单测里逐字断言，
 * 而不用起模拟器点保存框。
 *
 * 必须是 `public`：它出现在 [ConversationListViewModel.export] 的类型里，
 * 而那个属性是给界面读的。
 */
data class ExportRequest(val fileName: String, val markdown: String)

/** 没有标题的会话（一条消息都没发过）显示成什么。和抽屉里的占位一致。 */
private const val FALLBACK_TITLE = "新对话"

/**
 * 导出文件的建议名：`标题-yyyyMMdd-HHmm.md`。
 *
 * 带上时间戳不是装饰 —— 同一个会话导出两次是很常见的事（聊完再导一次），
 * 而 SAF 遇到重名会自动改成 `xxx (1).md`。用户看到的是两个文件名不一样、
 * 内容也不一样，但**分不清哪个是新的**。时间戳把这件事说清楚了。
 */
internal fun exportFileName(title: String, now: Long): String =
    "${safeFileName(title)}-${fileStamp(now)}.md"

/**
 * 把标题变成能当文件名的一段。
 *
 * ## 为什么必须过滤
 *
 * 标题来自用户输入（`deriveTitle` 取的是第一条消息），里面完全可能有 `/`
 * 或 `:`。它们进了文件名要么被系统截断、要么让 SAF 拒绝 —— 而用户改名的
 * 时候并没有得到任何提示。全角标点（`：`、`？`）保留：它们在 FAT/exFAT 上
 * 合法，用户写的就是这个。
 *
 * 首尾的点也要去掉：以 `.` 开头的文件在类 Unix 系统上是隐藏文件，
 * 导出完「找不到文件」是这类 bug 里最费解的一种。
 */
private fun safeFileName(title: String): String {
    val cleaned = title
        .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
        .replace(Regex("_+"), "_")
        .trim()
        .trim('.')
        .trim()
    if (cleaned.isEmpty()) return FALLBACK_TITLE
    // 截断到 40 个字符：多数文件系统的单个名字上限是 255 字节，
    // 而中文在 UTF-8 下一个字 3 字节 —— 40 字约 120 字节，加上时间戳仍很宽裕
    return if (cleaned.length <= 40) cleaned else cleaned.take(40)
}

/**
 * 把整个会话拼成一份 Markdown。
 *
 * ## 为什么要包含失败和中断的消息
 *
 * 导出是「把这次对话带走」，不是「把好看的答案带走」。失败的那条
 * （`HTTP 401`、连不上）和用户主动停掉的那半截都是**真实发生过的事**，
 * 丢掉之后再看这份文件，会以为当时模型就是这么回答的。
 *
 * ## 为什么系统提示词不导出
 *
 * 它不在 `message` 表里（是每次请求现拼的），而且往往很长。
 *
 * ## 思考过程为什么用 `<details>` 折起来
 *
 * 思维链常常比正文还长，直接铺开会把正文挤散；但它是模型为什么这么答的
 * 唯一线索，删掉又等于丢信息。`<details>` 在 GitHub、Typora、Obsidian 里
 * 都能折叠，在纯文本编辑器里也只是多两行标签 —— 两边都不难看。
 */
internal fun buildMarkdown(
    title: String,
    messages: List<StoredMessage>,
    exportedAt: Long,
): String = buildString {
    append("# ").append(title.trim().ifBlank { FALLBACK_TITLE }).append("\n\n")
    append("> 导出时间 ").append(stamp(exportedAt))
    append(" · ").append(messages.size).append(" 条消息\n\n")

    messages.forEach { row ->
        val speaker = when (row.message.role) {
            ChatMessage.Role.User -> "我"
            ChatMessage.Role.Assistant -> "助手"
            ChatMessage.Role.Tool -> "工具结果"
            ChatMessage.Role.System -> return@forEach
        }
        append("## ").append(speaker)
        // 助手那一行带上模型名：同一次对话里换过服务商时，
        // 这是唯一能看出「这段是谁答的」的地方
        row.model?.takeIf { row.message.role == ChatMessage.Role.Assistant }?.let {
            append(" · ").append(it)
        }
        append("\n\n")

        val reasoning = row.message.reasoning
        if (reasoning != null && reasoning.isNotBlank()) {
            append("<details>\n<summary>思考过程</summary>\n\n")
            append(reasoning.trim()).append("\n\n</details>\n\n")
        }

        val body = row.message.content.trim()
        if (body.isNotEmpty()) {
            append(body).append("\n\n")
        }

        row.message.toolCalls.forEach { call ->
            append("- 调用工具 `").append(call.name).append("`")
            if (call.argumentsJson.isNotBlank()) {
                append(" `").append(call.argumentsJson.trim()).append("`")
            }
            append("\n")
        }
        if (row.message.toolCalls.isNotEmpty()) append("\n")

        when (row.status) {
            MessageStatus.Stopped -> append("*（这条回复被中断了）*\n\n")
            MessageStatus.Failed -> append("*（失败：").append(row.error ?: "未知原因").append("）*\n\n")
            MessageStatus.Streaming -> append("*（导出时这条还在生成）*\n\n")
            MessageStatus.Complete -> Unit
        }
    }
}

/**
 * 用 `Locale.US` 固定住数字形态：导出文件是要长期留存的，
 * 而某些区域设置下 `yyyy` 会变成非公历年份。这里不追本地化。
 */
private fun stamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMillis))

private fun fileStamp(epochMillis: Long): String =
    SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(epochMillis))
