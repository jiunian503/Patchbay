package com.aichat.tools

import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolResult
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.JsonObject

/**
 * 报当前时间。
 *
 * ## 为什么这是个必需的工具，而不是「模型自己知道」
 *
 * 模型的训练数据有截止时间，它对「现在」没有概念。没有这个工具时，
 * 问「今天几号」得到的答案是训练数据里的某个日期，而且**语气非常自信** ——
 * 用户很难分辨那是编的。
 *
 * 参数里给 [TIMEZONE] 而不是让模型自己算：模型知道用户说「明天」时
 * 该用哪个时区，但算时差经常出错。让它只负责选时区，算术交给这里。
 */
class CurrentTimeTool(
    private val clock: () -> Instant = Instant::now,
    private val systemZone: ZoneId = ZoneId.systemDefault(),
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        description = "获取当前日期和时间。回答「今天几号」「现在几点」「还有几天到 X」" +
            "这类问题前必须先调用它，不要凭记忆回答。" +
            "不传 timezone 时用设备所在时区。",
        parameters = schema(
            properties = mapOf(
                TIMEZONE to stringParam(
                    "IANA 时区名，例如 Asia/Shanghai、America/New_York。" +
                        "用户提到别的城市时填对应的，否则省略。",
                ),
            ),
        ),
    )

    override val userSummary: String
        get() = "读取设备当前的日期和时间（只读本地时钟，不联网）"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val raw = arguments.stringOrNull(TIMEZONE)
        val zone = if (raw == null) {
            systemZone
        } else {
            runCatching { ZoneId.of(raw) }.getOrElse {
                return ToolResult.error(
                    "不认识的时区「$raw」。请用 IANA 名字，例如 Asia/Shanghai。" +
                        "常见写法：Asia/Tokyo、Europe/London、America/New_York、UTC。",
                )
            }
        }

        val now = ZonedDateTime.ofInstant(clock(), zone)
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE)
        return ToolResult.ok(
            buildString {
                appendLine("时间：${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
                appendLine("星期：$weekday")
                appendLine("时区：${zone.id}（${now.offset}）")
                append("时间戳：${now.toEpochSecond()}")
            }
        )
    }

    companion object {
        const val NAME = "get_current_time"
        const val TIMEZONE = "timezone"
    }
}
