package com.aichat.ui.conversations

import com.aichat.chat.ChatMessage
import com.aichat.chat.MessageStatus
import com.aichat.chat.StoredMessage
import com.aichat.domain.llm.AssistantToolCall
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出文本的格式测试。
 *
 * ## 为什么这些断言值得逐字写
 *
 * 导出是**一次性**的动作：用户点了、文件存下来了、以后再也不看第二遍。
 * 格式错了（少一段、时间戳不对、标题里的斜杠把文件名截断）不会报任何错，
 * 只会让那份文件在几个月后变得没法用。所以这里全是精确匹配，
 * 不用 `contains` 之类的松散断言 —— 那对「多出一段」是瞎的。
 */
class ConversationExportTest {

    /** 2026-09-19 15:30:00 GMT+8，用一个固定的瞬间让断言可复现。 */
    private val at: Long = Calendar.getInstance(TimeZone.getTimeZone("GMT+8")).apply {
        set(2026, Calendar.SEPTEMBER, 19, 15, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun msg(
        id: Long,
        text: String,
        role: ChatMessage.Role = ChatMessage.Role.User,
        reasoning: String? = null,
        toolCalls: List<AssistantToolCall> = emptyList(),
        status: MessageStatus = MessageStatus.Complete,
        error: String? = null,
        model: String? = null,
    ) = StoredMessage(
        id = id,
        conversationId = "c1",
        message = ChatMessage(
            role = role,
            content = text,
            reasoning = reasoning,
            toolCalls = toolCalls,
        ),
        status = status,
        model = model,
        error = error,
        createdAt = id,
        updatedAt = id,
    )

    // ------------------------------------------------------------ 文件名

    @Test
    fun `文件名带上标题与时间`() {
        val name = exportFileName("会议纪要", at)

        assertTrue("必须是 .md 结尾，否则系统不知道该用什么打开：$name", name.endsWith(".md"))
        assertTrue(name, name.startsWith("会议纪要-"))
        // 时间戳让「同一个会话导出两次」的两个文件分得清哪个是新的 ——
        // 不带的话 SAF 会自动改成 `xxx (1).md`，而用户看不出先后
        assertTrue("要带可排序的时间戳：$name", name.contains("20260919-1530"))
    }

    @Test
    fun `标题里的路径分隔符会被换掉`() {
        // 标题取自用户第一条消息，里面完全可能有斜杠。
        // 不换掉的话 SAF 要么截断要么直接拒绝，而用户改名时得不到任何提示
        val name = exportFileName("a/b\\c:d*e?f\"g<h>i|j", at)

        listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|').forEach {
            assertFalse("文件名里不能出现 $it：$name", name.contains(it))
        }
        assertTrue(name.endsWith(".md"))
    }

    @Test
    fun `全角标点保留`() {
        // 中文用户写的就是全角，它们在文件系统上完全合法 ——
        // 一起过滤掉的话标题会变成一串下划线
        assertTrue(exportFileName("会议纪要：第二轮", at).startsWith("会议纪要：第二轮-"))
    }

    @Test
    fun `没有标题时用占位名`() {
        assertTrue(exportFileName("", at).startsWith("新对话-"))
        assertTrue(exportFileName("   ", at).startsWith("新对话-"))
    }

    @Test
    fun `标题只有点时也用占位名`() {
        // 以 `.` 开头的文件在类 Unix 系统上是隐藏文件 ——
        // 导出完「找不到文件」是这类 bug 里最费解的一种
        val name = exportFileName("...", at)

        assertFalse(name, name.startsWith("."))
        assertTrue(name, name.startsWith("新对话-"))
    }

    @Test
    fun `超长标题会截断`() {
        val name = exportFileName("很长的标题".repeat(40), at)

        // 多数文件系统单个名字上限 255 字节，中文一字 3 字节
        assertTrue("标题部分该被截断：${name.length}", name.length < 80)
        assertTrue(name.endsWith(".md"))
    }

    // ------------------------------------------------------------ 正文

    @Test
    fun `一份最简对话的完整形态`() {
        val markdown = buildMarkdown(
            title = "会议纪要",
            messages = listOf(
                msg(1L, "帮我把会议纪要整理成表格"),
                msg(2L, "好的，这是整理后的表格。", role = ChatMessage.Role.Assistant, model = "deepseek-chat"),
            ),
            exportedAt = at,
        )

        // 逐行写出来，不用三引号 —— 空行和结尾的换行是这份格式的一部分，
        // 而 trimIndent 会把首尾的空行吃掉，断言就变成了「大概长这样」
        assertEquals(
            listOf(
                "# 会议纪要",
                "",
                "> 导出时间 2026-09-19 15:30 · 2 条消息",
                "",
                "## 我",
                "",
                "帮我把会议纪要整理成表格",
                "",
                "## 助手 · deepseek-chat",
                "",
                "好的，这是整理后的表格。",
                "",
                "",
            ).joinToString("\n"),
            markdown,
        )
    }

    @Test
    fun `没有标题时正文也用占位标题`() {
        val markdown = buildMarkdown("  ", emptyList(), at)

        assertTrue(markdown, markdown.startsWith("# 新对话\n"))
        assertTrue("空会话也要能导出，而且要说清楚是空的", markdown.contains("0 条消息"))
    }

    @Test
    fun `思维链折进 details 里`() {
        val markdown = buildMarkdown(
            title = "t",
            messages = listOf(
                msg(
                    1L,
                    "答案是 42",
                    role = ChatMessage.Role.Assistant,
                    reasoning = "让我想想…\n先排除掉不可能是 42 的",
                )
            ),
            exportedAt = at,
        )

        // 思维链常常比正文还长，直接铺开会把正文挤散；但它是模型为什么这么答的
        // 唯一线索，删掉又等于丢信息
        assertTrue(markdown, markdown.contains("<details>\n<summary>思考过程</summary>"))
        assertTrue(markdown, markdown.contains("先排除掉不可能是 42 的"))
        assertTrue(markdown, markdown.contains("</details>"))
    }

    @Test
    fun `没有思维链时不出现 details 标签`() {
        val markdown = buildMarkdown("t", listOf(msg(1L, "问")), at)

        assertFalse("空标签只会让文件变脏", markdown.contains("<details>"))
    }

    @Test
    fun `工具调用与工具结果都保留`() {
        val markdown = buildMarkdown(
            title = "t",
            messages = listOf(
                msg(1L, "北京天气"),
                msg(
                    2L,
                    "",
                    role = ChatMessage.Role.Assistant,
                    toolCalls = listOf(AssistantToolCall("call_a", "get_weather", """{"city":"北京"}""")),
                ),
                msg(3L, "北京 25°C 晴", role = ChatMessage.Role.Tool),
            ),
            exportedAt = at,
        )

        assertTrue(markdown, markdown.contains("- 调用工具 `get_weather` `{\"city\":\"北京\"}`"))
        assertTrue(markdown, markdown.contains("## 工具结果"))
        assertTrue(markdown, markdown.contains("北京 25°C 晴"))
    }

    @Test
    fun `失败与中断的消息都带上说明`() {
        val markdown = buildMarkdown(
            title = "t",
            messages = listOf(
                msg(1L, "问"),
                msg(
                    2L,
                    "",
                    role = ChatMessage.Role.Assistant,
                    status = MessageStatus.Failed,
                    error = "HTTP 401：Invalid API key",
                ),
                msg(3L, "写了一半", role = ChatMessage.Role.Assistant, status = MessageStatus.Stopped),
            ),
            exportedAt = at,
        )

        // 导出是「把这次对话带走」，不是「把好看的答案带走」。
        // 丢掉之后再看这份文件，会以为当时模型就是这么回答的
        assertTrue(markdown, markdown.contains("*（失败：HTTP 401：Invalid API key）*"))
        assertTrue(markdown, markdown.contains("*（这条回复被中断了）*"))
        assertTrue(markdown, markdown.contains("写了一半"))
    }

    @Test
    fun `系统消息不导出`() {
        val markdown = buildMarkdown(
            title = "t",
            messages = listOf(
                msg(1L, "你是 Patchbay，一个助手", role = ChatMessage.Role.System),
                msg(2L, "问"),
            ),
            exportedAt = at,
        )

        assertFalse(markdown, markdown.contains("你是 Patchbay"))
        assertTrue("条数要按实际导出的算，不然和正文对不上", markdown.contains("2 条消息"))
    }

    @Test
    fun `失败原因里的换行不会把后面的内容顶乱`() {
        val markdown = buildMarkdown(
            title = "t",
            messages = listOf(
                msg(1L, "问"),
                msg(2L, "", role = ChatMessage.Role.Assistant, status = MessageStatus.Failed, error = "第一行\n第二行"),
            ),
            exportedAt = at,
        )

        // 原因原文照抄（用户要拿它去搜索），但结尾那行 `*（失败：…）*`
        // 必须在同一次输出里闭合 —— 断成两行的话后面的消息会被吞进斜体
        assertTrue(markdown, markdown.contains("*（失败：第一行\n第二行）*\n\n"))
    }
}
