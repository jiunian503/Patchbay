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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
 * 不是输入框里的当前文字 —— 用户可能已经改了输入框但还没重新搜。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
        topBar = {
            TopAppBar(
                title = { Text("搜索消息") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("搜历史消息") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            // 先收键盘再查：结果列表就在下面，键盘挡着会看不到
                            focus.clearFocus()
                            viewModel.submit()
                        }
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        focus.clearFocus()
                        viewModel.submit()
                    },
                    enabled = state.query.isNotBlank(),
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
                    CenterHint("输入关键词，回车开始搜。中文按字匹配，英文按整词匹配。")
                }

                state.hits.isEmpty() -> {
                    CenterHint("没有找到包含「${state.query}」的消息。")
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Text(
                                text = "找到 ${state.hits.size} 条",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HitCard(hit: MessageHit, query: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 空标题是「用户还没命名」，和列表页一样显示成「新对话」
                    text = hit.conversationTitle.ifBlank { "新对话" },
                    style = MaterialTheme.typography.titleSmall,
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
            Spacer(Modifier.height(4.dp))
            Text(
                text = textWindow(hit.content, query).annotated(),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
 * 强调用**加粗**而不是换色：卡片本身有 `surfaceVariant` 底色，
 * 再加一层背景色会显脏，而加粗在任何配色方案下都读得出来。
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
