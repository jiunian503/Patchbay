package com.aichat.ui.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.di.AppContainer
import com.aichat.domain.search.MessageHit
import com.aichat.domain.text.TextWindow
import com.aichat.domain.text.textWindow
import com.aichat.theme.Space
import com.aichat.ui.common.PbButton
import com.aichat.ui.common.PbCard
import com.aichat.ui.common.PbHintCard
import com.aichat.ui.common.PbIcons
import com.aichat.ui.common.PbTopBar

/**
 * 历史消息检索页（Hermes 三层记忆的第三层）。
 *
 * ## 这个页面存在的理由
 *
 * 对话越长，「我上次说过的那件事」越找不回来。滚动翻历史在几十条之后就
 * 完全不可用了，而 `message_fts` 的中文索引早就建好了 —— 只是没人用。
 *
 * ## 三条入口都通到同一个检索实现
 *
 * 用户输入的是**原始查询串**，切词和转义都在 `RoomConversationSearch` 里。
 * 这个页面不碰 FTS 语法，也不知道底层是 FTS4。
 *
 * ## 点结果会跳到命中的那一条，并把命中词标出来
 *
 * `onOpenConversation` 带上 `messageId` 和 `query`，对话页据此把列表滚到那条消息、
 * 并在正文里把查询词标上（见 `ChatScreen` 里的 `highlightIndex` 和
 * `MarkdownText` 的 `highlightQuery`）。只跳到会话底部是不够的 ——
 * 长会话里用户还得自己翻半天；而只定位不标词同样是半件事，
 * 一条几千字的消息里他还得自己找那个词。「搜到了却看不到」比搜不到更让人困惑。
 *
 * 定位发生在对话页**初始加载完成**的那一刻，和「普通打开落到底部」
 * 共用同一次触发，不新增时序依赖。
 *
 * 注意带过去的是 `state.submittedQuery`（产生这批结果的那次查询），
 * 不是输入框里的文字 —— 用户可能已经改了输入框但还没重新搜。
 *
 * ## 空状态从「一行灰字」改成了带图标的卡片
 *
 * 原来是屏幕正中一行 13sp 的灰字，上下 80% 是空白。那不只是「简陋」——
 * 它让用户不确定页面是不是加载完了。现在给一个图标 + 标题 + 一句说明，
 * 而且**「还没搜」和「搜了没结果」用不同的文案**（下面 `when` 里那两个分支）。
 */
@Composable
fun ConversationSearchScreen(
    container: AppContainer,
    onBack: () -> Unit,
    /** 打开会话，定位到命中的那条消息，并把查询词带过去用于标出命中位置。 */
    onOpenConversation: (conversationId: String, messageId: Long, query: String) -> Unit,
) {
    val viewModel: ConversationSearchViewModel =
        viewModel { ConversationSearchViewModel(container.search) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { PbTopBar(title = "搜索消息", onBack = onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier =
                    Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("搜历史消息") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions =
                        KeyboardActions(
                            onSearch = {
                                // 先收键盘再查：结果列表就在下面，键盘挡着会看不到
                                focus.clearFocus()
                                viewModel.submit()
                            }
                        ),
                )
                Spacer(Modifier.width(Space.sm))
                PbButton(
                    onClick = {
                        focus.clearFocus()
                        viewModel.submit()
                    },
                    enabled = state.query.isNotBlank(),
                    // 和输入框同高（OutlinedTextField 的默认最小高度就是 56dp）。
                    // 不给高度的话按钮只有 40dp，在居中对齐下会比输入框矮一截 ——
                    // 单看说得过去，和上面那条顶栏、下面那条列表放在一起就是「没对齐」。
                    modifier = Modifier.height(56.dp),
                ) {
                    Text("搜索")
                }
            }

            when {
                state.searching && state.hits.isEmpty() -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                // 「还没搜」和「搜了没结果」必须分开说。合成一句「没有找到」的话，
                // 用户刚进页面就会以为库里的消息搜不出来。
                !state.searched -> {
                    HintState(
                        title = "搜你以前的对话",
                        body = "输入关键词，回车开始搜。中文按字匹配，英文按整词匹配。",
                    )
                }

                state.hits.isEmpty() -> {
                    HintState(
                        title = "没有找到",
                        body = "没有任何消息包含「${state.query}」。换个说法，或者少打几个字再试。",
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding =
                            PaddingValues(
                                start = Space.lg,
                                end = Space.lg,
                                bottom = Space.lg,
                            ),
                        verticalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        item {
                            Text(
                                text = "找到 ${state.hits.size} 条",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = Space.xs, top = Space.md, bottom = Space.xs),
                            )
                        }
                        items(state.hits, key = { it.messageId }) { hit ->
                            HitCard(
                                hit = hit,
                                // 用 submittedQuery 而不是 query：用户可能已经
                                // 改了输入框但还没重新搜，那批 hits 仍属于上一次查询
                                query = state.submittedQuery,
                                onClick = {
                                    onOpenConversation(
                                        hit.conversationId,
                                        hit.messageId,
                                        state.submittedQuery,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 空状态。居中的一张说明卡，而不是一行悬在空白里的灰字。 */
@Composable
private fun HintState(title: String, body: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = Space.lg),
        contentAlignment = Alignment.Center,
    ) {
        PbHintCard(icon = PbIcons.Search, title = title, body = body)
    }
}

@Composable
private fun HitCard(hit: MessageHit, query: String, onClick: () -> Unit) {
    PbCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // 空标题是「用户还没命名」，和列表页一样显示成「新对话」
                text = hit.conversationTitle.ifBlank { "新对话" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatTime(hit.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Space.sm))
        Text(
            text = textWindow(hit.content, query).annotated(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 把命中区间渲染成带强调的文本。找不到命中位置时就是普通文本。
 *
 * ## 为什么这个函数留在 UI 层
 *
 * 截取窗口的算法在 `:domain` 的 [textWindow] 里 —— 那是纯文本逻辑，
 * 而且**模型用的历史检索工具需要同一套算法**（见 `:tools` 的
 * `SearchHistoryTool`）。两份实现漂移的后果是「用户看到的片段和模型看到的
 * 不是同一段」，这种不一致没有任何报错，只会让人觉得结果莫名其妙。
 *
 * 而「怎么强调」是展示问题：这里是加粗，将来换成背景色、下划线都不该
 * 牵动领域层。所以这里只做区间到富文本的翻译。
 *
 * 强调用**加粗**而不是换色：卡片本身是纯白底，加一层背景色在深浅色主题下
 * 都要单独调；而加粗在任何配色方案下都读得出来。
 */
private fun TextWindow.annotated(): AnnotatedString = buildAnnotatedString {
    val range = hitRange
    if (range == null || range.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    append(text.substring(0, range.first))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
        append(text.substring(range.first, range.last + 1))
    }
    append(text.substring(range.last + 1))
}
