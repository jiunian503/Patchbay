package com.aichat.ui.chat

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aichat.domain.render.ColumnAlign
import com.aichat.domain.render.InlineSpan
import com.aichat.domain.render.MarkdownBlock
import com.aichat.domain.render.MarkdownParser
import com.aichat.domain.render.plainText
import com.aichat.domain.text.highlightSegments
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 把 Markdown 文本渲染成 Compose。
 *
 * ## 为什么不用 WebView / `Html.fromHtml`
 *
 * - `Html.fromHtml` 只认一个很小的子集：表格、嵌套列表、代码块语言标签全不支持，
 *   而这些恰好是模型最爱输出的东西。
 * - WebView 放进 `LazyColumn` 是出了名的难缠：高度要异步测量、嵌套滚动打架、
 *   每条消息一个 WebView 内存直接失控。
 *
 * 原生渲染还顺带拿到三件事：文字可以选中、链接可以点、代码块可以放复制按钮。
 *
 * ## 这里只负责「画」，不负责「解析」
 *
 * 解析全在 `:domain`（纯 Kotlin，JVM 单测覆盖）。这个文件里没有任何
 * 语法判断 —— 它只认 [MarkdownBlock]。
 *
 * ## [highlightQuery] 是「从搜索跳过来」那一次才有的
 *
 * 非空时，正文里命中的词会被加上底色。它只在**被定位到的那一条消息**上非空
 * （见 `ChatScreen.MessageArea`），不是整个会话 —— 用户点的是一个结果，
 * 要回答的是「我搜的词在**这条**消息的哪儿」；把整个会话里所有出现都标上
 * 是另一个功能（会话内查找），而且在长会话里会糊成一片。
 *
 * 查询词按**渲染出来的文字**匹配，不是 Markdown 源串 —— 细节见
 * `:domain` 的 `highlightRanges`。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
    highlightQuery: String? = null,
) {
    // 历史消息不会再变，按内容缓存解析结果就够了。
    // 流式那一条走的是另一条路（在 ChatViewModel 里增量解析，见 StreamingMarkdownRenderer）
    val blocks = remember(markdown) { MarkdownParser.Default.parse(markdown) }
    MarkdownBlocks(
        blocks = blocks,
        modifier = modifier,
        textStyle = textStyle,
        highlightQuery = highlightQuery,
    )
}

@Composable
fun MarkdownBlocks(
    blocks: List<MarkdownBlock>,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
    highlightQuery: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (block in blocks) {
            BlockView(block, textStyle, highlightQuery)
        }
    }
}

@Composable
private fun BlockView(block: MarkdownBlock, base: TextStyle, highlightQuery: String?) {
    when (block) {
        is MarkdownBlock.Paragraph -> InlineText(block.spans, base, highlightQuery = highlightQuery)

        is MarkdownBlock.Heading ->
            InlineText(block.spans, headingStyle(block.level, base), highlightQuery = highlightQuery)

        is MarkdownBlock.CodeBlock -> CodeBlockCard(block, highlightQuery)

        is MarkdownBlock.Quote -> QuoteView(block, base, highlightQuery)

        is MarkdownBlock.BulletList -> ListView(block.items, block.tight, base, highlightQuery) { "•" }

        is MarkdownBlock.OrderedList -> ListView(block.items, block.tight, base, highlightQuery) { index ->
            "${block.start + index}."
        }

        is MarkdownBlock.Table -> TableView(block, base, highlightQuery)

        MarkdownBlock.ThematicBreak -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 2.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * 标题的字号。
 *
 * 不直接用 `MaterialTheme.typography` 里的档位：那套是给界面标题用的，
 * 塞进正文里要么太大（headlineMedium 有 28sp）要么层级感不够。
 * 这里按「正文的 1.0 ~ 1.6 倍」自己定，保证 h1..h6 在一条消息里能看出差别。
 */
private fun headingStyle(level: Int, base: TextStyle): TextStyle = when (level) {
    1 -> base.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    2 -> base.copy(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    3 -> base.copy(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
    4 -> base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    else -> base.copy(fontWeight = FontWeight.Medium)
}

// ================================================================ 命中词

/**
 * 命中词的底色。
 *
 * 用 `primary` 加透明度而不是某个写死的黄色：底色要压在**任何**背景之上 ——
 * 助手正文铺在 `surface` 上、用户气泡是 `primaryContainer`、
 * 代码块是 `surfaceVariant` —— 写死的颜色在其中某一种上必然不好看。
 * 半透明的强调色是「给底下的东西上一层色」，四种背景上都成立。
 *
 * 只用底色、不改字重：加粗会让文字宽度变化，整段跟着重排，
 * 而且命中词本来就落在 `**粗体**` 里时完全看不出来。
 */
@Composable
private fun highlightBackground(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

/**
 * 给一段**纯文本**里的命中词加底色。
 *
 * 给用户气泡用（用户输入不按 Markdown 渲染，见 `ChatScreen.PersistedMessage`）。
 * 助手正文走 [MarkdownText]，那条路径在 [appendSpans] 里逐段处理。
 */
@Composable
fun highlightedText(text: String, query: String?): AnnotatedString {
    val background = highlightBackground()
    return buildAnnotatedString { appendHighlighted(text, query, background) }
}

/**
 * 追加一段文字，并把命中词套上底色。
 *
 * 切段全在 `:domain` 的 `highlightSegments` 里做（那里有「拼回去必须
 * 逐字符等于原文」的单测），这里只负责逐段追加。空查询自然退化成
 * 一段不带标记的片段，不需要在这里单独判断。
 */
private fun AnnotatedString.Builder.appendHighlighted(
    text: String,
    query: String?,
    background: Color,
) {
    for (segment in highlightSegments(text, query.orEmpty())) {
        if (segment.highlighted) {
            withStyle(SpanStyle(background = background)) { append(segment.text) }
        } else {
            append(segment.text)
        }
    }
}

// ================================================================ 行内

@Composable
private fun InlineText(
    spans: List<InlineSpan>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    highlightQuery: String? = null,
) {
    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val highlightBackground = highlightBackground()

    val uriHandler = LocalUriHandler.current
    // 用 rememberUpdatedState 而不是直接捕获：annotated string 是 remember 出来的，
    // 不会因为 uriHandler 换了实例就重建，直接捕获会一直用旧的那个
    val currentUriHandler by rememberUpdatedState(uriHandler)

    val annotated = remember(spans, linkStyle, codeBackground, codeColor, highlightBackground, highlightQuery) {
        buildAnnotatedString {
            appendSpans(
                spans = spans,
                inherited = SpanStyle(),
                styles = InlineStyles(
                    link = linkStyle,
                    codeBackground = codeBackground,
                    codeColor = codeColor,
                    highlightBackground = highlightBackground,
                    highlightQuery = highlightQuery,
                    onOpen = { url ->
                        // 打不开就算了：白名单已经挡住了危险协议，
                        // 剩下的失败（没有浏览器、地址失效）不该让应用崩
                        runCatching { currentUriHandler.openUri(url) }
                    },
                ),
            )
        }
    }

    Text(
        text = annotated,
        style = style,
        textAlign = textAlign,
        modifier = modifier,
    )
}

/**
 * 行内渲染要用的全部样式与回调。
 *
 * 打包成一个值而不是散成六个参数：`appendSpans` 是递归的，每层都要原样
 * 往下传，六个参数乘上四五个递归点很容易传漏一个 —— 而传漏了不会编译不过，
 * 只会让某一种嵌套里的样式悄悄失效（比如列表里的代码块丢了底色）。
 */
private data class InlineStyles(
    val link: SpanStyle,
    val codeBackground: Color,
    val codeColor: Color,
    val highlightBackground: Color,
    val highlightQuery: String?,
    val onOpen: (String) -> Unit,
)

/**
 * 递归地把行内树摊平成 AnnotatedString。
 *
 * [inherited] 是**累积**的样式：`**粗体里的 `代码`**` 会让代码同时继承
 * 「粗体」和「等宽 + 底色」两层 —— 直接覆盖而不是 merge 的话，
 * 嵌套在粗体里的代码会丢掉粗体。
 */
private fun AnnotatedString.Builder.appendSpans(
    spans: List<InlineSpan>,
    inherited: SpanStyle,
    styles: InlineStyles,
) {
    for (span in spans) {
        when (span) {
            is InlineSpan.Text -> withStyle(inherited) {
                appendHighlighted(span.text, styles.highlightQuery, styles.highlightBackground)
            }

            InlineSpan.LineBreak -> withStyle(inherited) { append('\n') }

            is InlineSpan.Code -> withStyle(
                inherited.merge(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = styles.codeBackground,
                        color = styles.codeColor,
                        fontSize = 0.94.em,
                    )
                )
            ) {
                // 代码里也要标：用户搜的经常正是函数名、参数名这类标识符
                appendHighlighted(span.text, styles.highlightQuery, styles.highlightBackground)
            }

            is InlineSpan.Strong -> appendSpans(
                span.spans, inherited.merge(SpanStyle(fontWeight = FontWeight.Bold)), styles,
            )

            is InlineSpan.Emphasis -> appendSpans(
                span.spans, inherited.merge(SpanStyle(fontStyle = FontStyle.Italic)), styles,
            )

            is InlineSpan.Strikethrough -> appendSpans(
                span.spans, inherited.merge(SpanStyle(textDecoration = TextDecoration.LineThrough)), styles,
            )

            is InlineSpan.Link -> {
                val start = length
                appendSpans(span.spans, inherited.merge(styles.link), styles)
                // 只给非空范围加链接标注：空的 annotation 会让 Compose
                // 在点击命中测试里留下一个零宽区域
                if (length > start) {
                    addLink(
                        LinkAnnotation.Url(
                            url = span.href,
                            styles = TextLinkStyles(style = styles.link),
                            linkInteractionListener = object : LinkInteractionListener {
                                override fun onClick(link: LinkAnnotation) {
                                    val url = (link as? LinkAnnotation.Url)?.url ?: return
                                    styles.onOpen(url)
                                }
                            },
                        ),
                        start,
                        length,
                    )
                }
            }
        }
    }
}

// ================================================================ 代码块

@Composable
private fun CodeBlockCard(block: MarkdownBlock.CodeBlock, highlightQuery: String?) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val highlightBackground = highlightBackground()

    // 「已复制」两秒后自己变回去，否则用户以为复制坏了
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    // 代码里也要标出命中词：用户搜的经常正是函数名、参数名这类标识符，
    // 而它们只出现在代码块里。不标的话这条消息看起来「根本没这个词」
    val codeText = remember(block.code, highlightQuery, highlightBackground) {
        buildAnnotatedString {
            appendHighlighted(block.code, highlightQuery, highlightBackground)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.language ?: "代码",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        // 复制的是**原文**，不带任何渲染信息 ——
                        // 用户要的是能粘进编辑器里的代码
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("代码", block.code))
                            )
                            copied = true
                        }
                    }
                ) {
                    Text(
                        text = if (copied) "已复制" else "复制",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 横向滚动而不是折行：代码折行之后缩进层次全乱，反而没法读
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(
                    text = codeText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = false,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

// ================================================================ 引用

@Composable
private fun QuoteView(block: MarkdownBlock.Quote, base: TextStyle, highlightQuery: String?) {
    Row(
        // IntrinsicSize.Min 是必需的：不加的话左侧竖条的 fillMaxHeight
        // 拿不到内容高度，会退化成 0 高（竖条看不见）
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(2.dp),
                )
        )
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (inner in block.blocks) BlockView(inner, base, highlightQuery)
        }
    }
}

// ================================================================ 列表

@Composable
private fun ListView(
    items: List<List<MarkdownBlock>>,
    tight: Boolean,
    base: TextStyle,
    highlightQuery: String?,
    marker: (Int) -> String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        // 紧凑列表（项之间没空行）贴紧，松散列表拉开 ——
        // 模型用这个区分「这是一组」和「这是几条独立的话」
        verticalArrangement = Arrangement.spacedBy(if (tight) 2.dp else 8.dp),
    ) {
        items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = marker(index),
                    style = base,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(24.dp),
                )
                Spacer(Modifier.width(6.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (inner in item) BlockView(inner, base, highlightQuery)
                }
            }
        }
    }
}

// ================================================================ 表格

@Composable
private fun TableView(block: MarkdownBlock.Table, base: TextStyle, highlightQuery: String?) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellStyle = base.copy(fontSize = 13.sp, lineHeight = 18.sp)

    val minCellWidth = 56.dp
    val maxCellWidth = 200.dp
    val horizontalPadding = 8.dp

    val widths = remember(block, cellStyle, density) {
        columnWidths(
            block = block,
            cellStyle = cellStyle,
            measurer = measurer,
            minWidthPx = with(density) { (minCellWidth + horizontalPadding * 2).toPx() },
            maxWidthPx = with(density) { (maxCellWidth + horizontalPadding * 2).toPx() },
            paddingPx = with(density) { (horizontalPadding * 2).toPx() },
        ).map { with(density) { it.toDp() } }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column {
            Row {
                block.headers.forEachIndexed { c, cell ->
                    TableCell(
                        spans = cell,
                        width = widths[c],
                        style = cellStyle.copy(fontWeight = FontWeight.SemiBold),
                        align = block.aligns.getOrNull(c) ?: ColumnAlign.None,
                        background = MaterialTheme.colorScheme.surfaceVariant,
                        highlightQuery = highlightQuery,
                    )
                }
            }
            block.rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row {
                    block.aligns.indices.forEach { c ->
                        TableCell(
                            spans = row.getOrNull(c).orEmpty(),
                            width = widths[c],
                            style = cellStyle,
                            align = block.aligns[c],
                            background = Color.Transparent,
                            highlightQuery = highlightQuery,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TableCell(
    spans: List<InlineSpan>,
    width: Dp,
    style: TextStyle,
    align: ColumnAlign,
    background: Color,
    highlightQuery: String?,
) {
    Box(
        modifier = Modifier
            .width(width)
            .background(background)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        InlineText(
            spans = spans,
            style = style,
            textAlign = when (align) {
                ColumnAlign.Left -> TextAlign.Start
                ColumnAlign.Center -> TextAlign.Center
                ColumnAlign.Right -> TextAlign.End
                ColumnAlign.None -> TextAlign.Start
            },
            highlightQuery = highlightQuery,
        )
    }
}

/**
 * 量出每一列的宽度：取该列所有单元格里最宽的那个，夹在 [minWidthPx] 和 [maxWidthPx] 之间。
 *
 * 用 `TextMeasurer` 而不是 `IntrinsicSize`：`IntrinsicSize` 只能让**同一行内**
 * 的单元格等高，做不到跨行等宽，而表格的列不对齐是一眼就能看出来的破绽。
 *
 * 量的是纯文本（`plainText()`）而不是带样式的 AnnotatedString：
 * 单元格里的粗体会比量出来的略宽一点，所以外面留了 8dp 余量。
 * 这个近似换来的是测量逻辑简单到不会错。
 *
 * 全程用 Float（px）而不是 Int：`Density.toPx()` 给的就是 Float，
 * 来回取整只会在高密度屏上引入累积误差。
 */
private fun columnWidths(
    block: MarkdownBlock.Table,
    cellStyle: TextStyle,
    measurer: TextMeasurer,
    minWidthPx: Float,
    maxWidthPx: Float,
    paddingPx: Float,
): List<Float> {
    val maxContentPx = (maxWidthPx - paddingPx).coerceAtLeast(1f)
    return block.aligns.indices.map { c ->
        val header = block.headers.getOrNull(c).orEmpty().plainText()
        val headerWidth = measureWidth(measurer, header, cellStyle, maxContentPx)
        val bodyWidth = block.rows.maxOfOrNull { row ->
            measureWidth(
                measurer,
                row.getOrNull(c).orEmpty().plainText(),
                cellStyle,
                maxContentPx,
            )
        } ?: 0f

        (maxOf(headerWidth, bodyWidth) + paddingPx).coerceIn(minWidthPx, maxWidthPx)
    }
}

private fun measureWidth(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    maxWidthPx: Float,
): Float {
    if (text.isEmpty()) return 0f
    return measurer.measure(
        text = AnnotatedString(text),
        style = style,
        constraints = Constraints(maxWidth = maxWidthPx.toInt().coerceAtLeast(1)),
    ).size.width.toFloat()
}
