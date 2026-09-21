package com.aichat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.chat.ChatMessage
import com.aichat.chat.MessageStatus
import com.aichat.chat.ToolApprovalGate
import com.aichat.di.AppContainer
import com.aichat.di.ProviderChoice
import com.aichat.theme.MonoLabelStyle
import com.aichat.theme.MonoTextStyle
import com.aichat.theme.Space
import com.aichat.ui.characters.CharacterPickerAction
import com.aichat.ui.common.PbIcons
import com.aichat.ui.common.PbTonalButton
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.filter

/**
 * 对话页。
 *
 * ## 渲染规则只有一条
 *
 * `streaming` 为真时画 [ChatUiState.live]（逐段拼出来的），
 * 否则画 [ChatUiState.messages]（从库里读出来的）。两者不会同时出现，
 * 所以不存在「同一句话显示两遍」这种经典 bug。
 *
 * ## 正文的 Markdown
 *
 * 助手正文走 [MarkdownText] / [MarkdownBlocks]（见 `MarkdownText.kt`），
 * 用户输入按纯文本 —— 用户写的 `*` 是字面意思，不该被解释成格式。
 *
 * 解析一律在渲染之前完成：历史消息在 `MarkdownText` 里按内容缓存，
 * 流式正文在 `ChatViewModel` 里用 `StreamingMarkdownRenderer` 增量解析。
 * **composable 里不做任何语法判断**，否则每帧都要重新解析。
 *
 * ## 顶栏左边那个图标是「汉堡」还是「返回」
 *
 * 由 [showBack] 决定，而它来自**返回栈的深度**（`MainNavigation` 算好传进来）：
 *
 * - 栈里只有它自己 → 它就是首页 → 给汉堡，拉开会话侧边栏
 * - 栈里还有别的 → 它是从搜索页压上来的 → 给返回箭头，回到搜索结果
 *
 * 两件事用一个图标表达，用户随时能看出「按这个会去哪」。抽屉本身挂在
 * [ChatScaffold] 上（见那个文件的注释），这一层只负责画。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    conversationId: String,
    showBack: Boolean,
    onBack: () -> Unit,
    /**
     * 拉开会话抽屉。**宽屏时是 null** —— 那时候侧边栏常驻，
     * 顶栏再放一个汉堡，等于让人去拉一个已经摊开的东西。
     */
    onOpenDrawer: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    /**
     * 打开服务商编辑页；`null` 表示新增一个。
     *
     * 首屏那条引导提示条走的是这条路，而不是 [onOpenSettings] ——
     * 理由写在 [SetupHint] 的 KDoc 里。
     */
    onOpenProviderEdit: (String?) -> Unit,
    /** 从搜索结果点进来时，要定位到的那条消息。普通打开会话是 null。 */
    highlightMessageId: Long? = null,
    /**
     * 同一个来源带过来的查询词，用来在 [highlightMessageId] 那条消息里
     * 把命中的词标出来。普通打开会话是 null。
     *
     * 两者一起有、一起没有 —— 只有 messageId 的话，用户跳过来还得在
     * 一条几千字的消息里自己找那个词，等于只做了半件事。
     */
    highlightQuery: String? = null,
) {
    val viewModel: ChatViewModel = viewModel(key = "chat-$conversationId") {
        ChatViewModel(container, conversationId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingApproval by viewModel.pendingApproval.collectAsStateWithLifecycle()

    // 回到前台时重读一遍。ViewModel 的 init 只跑一次，而用户可能刚从设置页
    // 回来 —— 改了服务商的模型名、把 Key 删了、或者新加了一个。
    // 不重读的话标题栏会一直显示旧值，而用户以为自己的修改没生效
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reload() }

    // 需要确认的工具调用：模型想执行，但得先有人点头。
    // 弹框期间整个对话是**挂起**的 —— 引擎在 await 用户的决定，
    // 所以这里既不能自动消失，也不能被别的东西盖住。
    pendingApproval?.let { request ->
        ToolApprovalDialog(
            request = request,
            onAllow = { viewModel.decideTool(true) },
            onDeny = { viewModel.decideTool(false) },
        )
    }

    // 「换这个会话的服务商」选择器。列表由 ViewModel 按需拉（点开时才读库）
    var pickingProvider by remember { mutableStateOf(false) }
    if (pickingProvider) {
        ProviderPickerDialog(
            choices = state.choices,
            currentId = state.providerId,
            onPick = { providerId ->
                pickingProvider = false
                viewModel.repin(providerId)
            },
            // 一个都没配时，这个弹窗里原先只有「取消」—— 它让用户去「设置」加一个，
            // 却不给出口。这里直接接到新增页。
            onAddProvider = {
                pickingProvider = false
                onOpenProviderEdit(null)
            },
            onDismiss = { pickingProvider = false },
        )
    }

    // 正在编辑重发的那条。放在这一层（而不是消息行里）：
    // 弹框一旦打开，消息行可能因为列表刷新而被回收，而弹框必须留着 ——
    // 它手里攥着用户刚敲的半截文字
    var editing by remember { mutableStateOf<MessageUi?>(null) }
    editing?.let { target ->
        EditResendDialog(
            initial = target.content,
            onConfirm = { text ->
                editing = null
                viewModel.editAndResend(target.id, text)
            },
            onDismiss = { editing = null },
        )
    }

    // 顶栏毛玻璃的源。挂在消息列表上（见下面的 `hazeSource`），
    // 顶栏自己用 `hazeChild` 取它背后那一条来模糊。
    val hazeState = rememberHazeState()

    // 顶栏压在滚动内容上时的样子。
    //
    // **三个字段都要显式给 —— 默认值在这里是有坑的。**
    // `HazeStyle` 的默认值全是 `Unspecified`：
    //   · 不给 `blurRadius` → 根本不模糊
    //   · 不给 `tint` → 顶栏背后是纯模糊，标题压在花掉的内容上读不清
    //   · 不给 `fallbackTint` → 不能模糊的机器上什么都不画
    //
    // `fallbackTint` 不是理论分支：真模糊要 Android 12（API 31）的 `RenderEffect`，
    // 而本 App 的 `minSdk` 是 28。haze 的判定就一行 ——
    // `HazeNode.android.kt` 的 `isBlurEnabledByDefault() = Build.VERSION.SDK_INT >= 31`
    // —— 32 以下全走这条路，那时 `tint` **会被完全忽略**（`HazeStyle` 的 KDoc：
    // "When the fallback tint is used, the tints provided in [tints] are ignored"）。
    // 所以调 `tint` 之前先确认走的是哪条路，别对着 fallback 路径调半天。
    //
    // tint 的 alpha 是这套东西里唯一需要调的旋钮，**它没有普适的正确答案** ——
    // 取决于背后内容的对比度。实测（2026-09-19，MuMu / Android 15，
    // 代码块滚到顶栏背后，取顶栏内 x∈[150,500] 的平均色）：
    //
    //     alpha 0.72 → (243,244,245)   与页面底色 (250,250,252) 差  7 个色阶
    //     alpha 0.45 → (240,241,243)   差 10
    //     alpha 0    → (227,229,234)   差 23
    //
    // 三档**单调变化**，说明模糊与 tint 都真的在工作。但官方默认的
    // `HazeDefaults.tintAlpha = 0.7` 在这个 App 上等于一条白条：消息里的内容
    // 本来就浅（背景近白、代码块浅灰 237、气泡浅紫），压上 70% 的 surface
    // 之后剩下的差异人眼分辨不出。**这个 App 的配色决定了毛玻璃只能做得很轻** ——
    // 想要 iOS 那种一眼可见的磨砂感，得有对比度更高的内容垫在底下。
    //
    // `backgroundColor` 按官方文档给**不透明**色：它画在模糊内容**下面**，
    // 职责是兜底 —— 模糊区里没有内容的地方露出来的应该是主题底色。
    // 顶栏背后的底色，用 `background` 而**不是** `surface`。
    //
    // 两者在本 App 的浅色主题下不是同一个值：`background = Ink25` (250,250,252)、
    // `surface = Ink0` (255,255,255)，差 5 个色阶。官方 KDoc 说
    // `backgroundColor` "typically would be MaterialTheme.colorScheme.surface" ——
    // 那是针对「页面底色就是 surface」的常见主题。这里页面底色是 `background`，
    // 而顶栏背后**没有内容的地方露出来的正是页面底色**，所以要用 `background`。
    //
    // 竖屏看不出来（内容限宽 600dp 几乎铺满），横屏才暴露：1097dp 下内容两侧
    // 各留出约 98dp，顶栏横跨全宽，那 5 个色阶会沿着顶栏下沿拉出一条可见的横线。
    //
    // `fallbackTint` 反过来用 `surface`：它模拟的是「不能模糊时的普通不透明顶栏」，
    // 而 M3 的顶栏本来就该比页面底色亮一档（默认 `surfaceContainer` 就是为此）。
    val topBarBackdrop = MaterialTheme.colorScheme.background
    val topBarFallback = MaterialTheme.colorScheme.surface
    val topBarHazeStyle = remember(topBarBackdrop, topBarFallback) {
        HazeStyle(
            backgroundColor = topBarBackdrop,
            tint = HazeTint(topBarBackdrop.copy(alpha = 0.35f)),
            blurRadius = 20.dp,
            fallbackTint = HazeTint(topBarFallback.copy(alpha = 0.94f)),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // 背景必须透明，否则把 haze 的模糊整个盖住 ——
                // M3 的默认 containerColor 是 surfaceContainer，不透明
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
                modifier = Modifier.hazeEffect(
                    state = hazeState,
                    style = topBarHazeStyle,
                ),
                title = {
                    // 可点 —— 换这个会话用的服务商。
                    //
                    // 加那个「▾」不是装饰：标题栏上的文字默认看起来是**显示**，
                    // 没人会想到能点。会话是黏住的，不给出口的话它就再也换不掉了
                    Column(
                        modifier = Modifier
                            .clickable {
                                viewModel.loadProviders()
                                pickingProvider = true
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = state.providerName ?: "AI 对话",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = " ▾",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.model?.let { model ->
                            Text(
                                text = model,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    // 一个位置，两种含义：是首页就给汉堡（拉开侧边栏），
                    // 是从搜索页压上来的就给返回箭头。判据是返回栈深度，
                    // 由 MainNavigation 算好传进来 —— 这一层不去猜
                    //
                    // 还有第三种情况：`onOpenDrawer` 为 null（宽屏，侧边栏常驻）。
                    // 那时左侧**整个留空** —— 没有抽屉可拉，摆一个汉堡是骗人
                    when {
                        showBack ->
                            IconButton(onClick = onBack) {
                                Icon(PbIcons.ArrowBack, contentDescription = "返回")
                            }

                        onOpenDrawer != null ->
                            IconButton(onClick = onOpenDrawer) {
                                Icon(PbIcons.Menu, contentDescription = "会话列表")
                            }
                    }
                },
                actions = {
                    // 角色选择器。按钮和弹窗都收在这个组件里 ——
                    // 弹窗是个独立窗口，写在 RowScope 里照样能正常显示
                    CharacterPickerAction(container = container, conversationId = conversationId)
                    IconButton(onClick = onOpenSettings) {
                        Icon(PbIcons.Settings, contentDescription = "设置")
                    }
                    Spacer(Modifier.width(Space.xs))
                },
            )
        },
    ) { padding ->
        // 正文列宽上限。
        //
        // 1097dp 的横屏下（MuMu 1920×1080 @280dpi），一行中文能拉到 60 多字 ——
        // 眼睛追不住行首行尾。这是**不可读**，不是审美问题。
        //
        // 取 600dp 有两个依据：一是 Material 3 窗口尺寸分类的 Compact/Medium 断点，
        // 二是 600dp ≈ 一行 37 个中文字（bodyLarge 16sp，一个汉字约占 16dp），
        // 落在排版惯例的 35–45 字舒适区间里。
        //
        // 只在宽屏生效：411dp 的手机、617dp 的竖屏都不受影响，所以没有回归。
        val contentMaxWidth = 600.dp

        // 顶栏盖住的高度。Scaffold 给的 `padding.top` 就是它 ——
        // TopAppBar 自己处理状态栏 inset，所以这个值**已经含状态栏**，
        // 不用再自己加一遍
        val topBarHeight = padding.calculateTopPadding()
        val layoutDirection = LocalLayoutDirection.current

        // 限宽加在**最外层**，而不是给消息列表、错误条、输入栏各加一次 ——
        // 三者的宽度必须一致，漏掉一个就会在宽屏上左右错开
        Box(
            modifier = Modifier
                .fillMaxSize()
                // ⚠️ **顶部故意不消费 Scaffold 给的 padding。**
                //
                // Scaffold 把 content 放在 (0,0) 铺满、topBar 后绘制（画在上层），
                // 而 `padding.top` 正是 topBar 的实测高度。吃掉它，消息就永远滚
                // 不到顶栏下面，haze 也就没有可模糊的东西 —— 顶栏会退化成一块
                // 不透明的白条，毛玻璃白做。
                //
                // 让出来之后，第一条消息靠列表自己的 `contentPadding` 避开顶栏
                // （见 MessageArea 的 topInset），而滚动中的内容会从顶栏背后经过。
                .padding(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = padding.calculateBottomPadding(),
                )
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            // ⚠️ `widthIn` 必须在 `fillMaxSize` **前面**。
            //
            // 反过来的话，`fillMaxSize` 先把 minWidth 顶到父宽，`widthIn(max=600)`
            // 就只能把 maxWidth 压到 600，而 min 还是父宽 —— min > max 时宽度
            // 取 min，限宽整个失效，而且不报任何错。
            Column(
                modifier = Modifier
                    .widthIn(max = contentMaxWidth)
                    .fillMaxSize()
                    // 毛玻璃的**源**。挂在这里而不是挂在 Scaffold 上：
                    // 顶栏用 `HazeInput.Sources(..., Behind)` 只取自己背后那一条，
                    // 所以源的范围就是「屏幕上会从顶栏下面经过的东西」。
                    .hazeSource(hazeState),
            ) {
                MessageArea(
                    state = state,
                    conversationId = conversationId,
                    onLoadEarlier = viewModel::loadEarlier,
                    onRegenerate = viewModel::regenerate,
                    onEdit = { editing = it },
                    highlightMessageId = highlightMessageId,
                    highlightQuery = highlightQuery,
                    topInset = topBarHeight,
                    modifier = Modifier.weight(1f),
                )

                state.error?.let { message ->
                    ErrorBanner(message = message, onDismiss = viewModel::dismissError)
                }

                if (state.needsProvider) {
                    // providerId 一起带下去：它是不是 null 决定这条提示条在讲哪件事
                    //（一个服务商都没有 vs 有服务商但没填 Key），也决定按钮把人
                    // 送到「新增」还是「编辑那一份」。
                    SetupHint(
                        providerId = state.providerId,
                        onOpenProvider = onOpenProviderEdit,
                    )
                }

                InputBar(
                    value = state.input,
                    canSend = state.canSend,
                    streaming = state.streaming,
                    onValueChange = viewModel::onInputChange,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                )
            }
        }
    }
}

@Composable
private fun MessageArea(
    state: ChatUiState,
    onLoadEarlier: () -> Unit,
    onRegenerate: (Long) -> Unit,
    onEdit: (MessageUi) -> Unit,
    highlightMessageId: Long? = null,
    highlightQuery: String? = null,
    /**
     * 当前会话。**必须传进来** —— 下面的落位 `LaunchedEffect` 拿它当 key。
     *
     * 不加的话：切会话时 [MessageArea] 并没有离开 composition，而
     * `state.loading`（新旧都是 false）和 `highlightMessageId`（都是 null）
     * 也都没变 —— key 没变，落位逻辑就**不会重新执行**，于是新会话继承了
     * 上一个会话的滚动位置。症状是切过去看到的是中间某一段，不是最新消息。
     */
    conversationId: String,
    /**
     * 顶栏盖住的高度，列表拿它当 `contentPadding` 的顶部。
     *
     * 顶栏是**浮在列表上方**的（见 `ChatScreen` 里那段布局说明），所以第一条
     * 消息必须自己避开它。用 `contentPadding` 而不是 `Modifier.padding`：
     * 前者是**内容内边距**，滚动时消息照样会从顶栏背后经过（毛玻璃才有东西
     * 可模糊）；后者会把列表整个推下去，顶栏背后永远是空的。
     */
    topInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // 只在用户本来就贴着底部时才自动跟随。否则用户往上翻看历史时，
    // 每来一个 token 就会被拽回底部，完全没法读。
    //
    // 判断放在「一次滚动结束」这一刻做，而不是每帧从布局反推：
    // 流式过程中内容长得比滚动快，每帧反推会把「暂时没跟上」误判成
    // 「用户不想跟」，跟随就断了。
    // 程序自己的滚动结束时必然停在底部，所以不会误关；只有用户真的
    // 往上拖过，`follow` 才会变 false。
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect { follow = listState.atBottom() }
    }

    // 「加载更早的消息」会占一个条目。**所有按下标算的地方都必须把它算进去** ——
    // 漏掉的话 `scrollToItem` 会整体偏一位，症状是「点了搜索结果，
    // 跳进去停在它的上一条」，而且看起来像定位功能坏了
    val earlierSlots = if (state.hasMore) 1 else 0

    val itemCount = state.messages.size + state.live.size + earlierSlots
    val liveLength = state.live.sumOf { it.lengthHint() }
    LaunchedEffect(itemCount, liveLength) {
        if (follow) listState.scrollToBottom(itemCount)
    }

    // 发送时无条件跳到底部。
    //
    // 光靠上面的 `follow` 不够：用户可能正翻着历史，而「我刚发了一句话」
    // 明确表达了他想看回复的意思，所以这里强制跳一次并恢复跟随。
    LaunchedEffect(state.streaming) {
        if (state.streaming) {
            follow = true
            listState.scrollToBottom(itemCount)
        }
    }

    // 初始加载完成后的落位。**两条路径共用这一次触发**，因为它们是同一个时机
    // （`itemCount` 刚变成真实值）：
    //
    // - 普通打开：落到底部，直接看到最近的内容
    // - 从搜索结果点进来：滚到命中的那一条，并且**关掉自动跟随** ——
    //   用户是带着目的来的，不该被下一轮自动滚底拽走
    //
    // 不新开一条 `LaunchedEffect` 是刻意的：定位必须发生在「落到底部」之后，
    // 拆成两条就得依赖它们的执行顺序，而那个顺序没有保证 ——
    // 一旦反过来，定位会被随后的滚底覆盖，症状是「点了搜索结果，跳进去还在底部」。
    //
    // `conversationId` 必须在 key 里。切会话时这个 composable **没有**离开
    // composition，而 `state.loading`（新旧都是 false）和 `highlightMessageId`
    // （都是 null）也没变 —— 只靠那两个的话这条 effect 根本不会重启，
    // 于是新会话继承了上一个会话的滚动位置。
    LaunchedEffect(conversationId, state.loading, highlightMessageId) {
        if (state.loading) return@LaunchedEffect

        val target = highlightIndex(state.messages, highlightMessageId)
        if (target == null) {
            follow = true
            listState.scrollToBottom(itemCount)
        } else {
            follow = false
            // `scrollToItem` 只对齐条目**顶部**，而「定位到某条消息」要的正是
            // 它的开头，所以这里不需要再补一次 `scrollBy`。
            // 加 `earlierSlots`：highlightIndex 给的是 messages 里的下标，
            // 而 LazyColumn 里前面还有「加载更早」那一条
            listState.scrollToItem(target + earlierSlots)
        }
    }

    if (state.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // 用「有没有消息」判断而不是 `itemCount == 0`：后者现在把
    // 「加载更早」那一条也算进去了，语义已经不是「空会话」了
    if (state.messages.isEmpty() && state.live.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    PbIcons.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(Space.md))
                Text(
                    text = "问点什么吧",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    text = "模型能用你装上的工具查天气、读网页、算数",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // 「重新生成」只给**最后一条**助手消息。
    //
    // 对更早的那条做，等于把它后面的所有对话一起删掉再重跑 ——
    // 用户点它时想的是「这条答得不好，换一个」。想重问更早的问题，
    // 正确入口是长按自己那句话「编辑重发」。
    //
    // 在循环外面算一次：写在 items 的 lambda 里的话，每一条都要把整个
    // 列表扫一遍，200 条的历史就是 O(n²)
    val regeneratableId = state.messages.lastOrNull { it.canRegenerate }?.id

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        // 左右 16dp 而不是 12dp：12dp 在 411dp 的手机宽度上，正文几乎是贴着屏幕
        // 边走的，而右边用户气泡又自带内边距 —— 两侧视觉重量不对称
        //
        // 顶部再加 `topInset`（顶栏高度）：顶栏浮在列表上方，第一条消息要避开它。
        // 用 `contentPadding` 而不是给 LazyColumn 加 `Modifier.padding`，是为了让
        // **滚动中的**消息仍然从顶栏背后经过 —— 顶栏的毛玻璃全靠它。
        contentPadding = PaddingValues(
            start = Space.lg,
            end = Space.lg,
            top = Space.md + topInset,
            bottom = Space.md,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.hasMore) {
            item(key = "load-earlier") {
                LoadEarlierRow(
                    loading = state.loadingEarlier,
                    onClick = onLoadEarlier,
                )
            }
        }

        items(state.messages) { message ->
            MessageRow(
                message = message,
                highlightQuery = highlightQueryFor(message, highlightMessageId, highlightQuery),
                canRegenerate = !state.streaming && message.id == regeneratableId,
                onRegenerate = { onRegenerate(message.id) },
                onEdit = { onEdit(message) },
            )
        }

        items(state.live) { segment -> LiveSegmentView(segment) }
    }
}

/**
 * 「加载更早的消息」。
 *
 * ## 为什么是按钮而不是滚到顶部自动加载
 *
 * 自动加载要在列表**顶部插入内容**，而 LazyColumn 的滚动位置是按条目下标
 * 算的 —— 插进去之后同一个下标指向的内容变了，视口会跳。要修就得手动补偿
 * 偏移量，那是一段很容易写错、又只在长会话里才显形的代码。
 *
 * 按钮没有这个问题，而且用户明确知道发生了什么。代价是多一次点击，
 * 而这是 200 条以上才出现的边界场景，值。
 *
 * [loading] 时按钮禁用：连点两次会用同一个游标取回同一页，然后拼两遍 ——
 * 界面上会出现两份一模一样的历史。
 */
@Composable
private fun LoadEarlierRow(loading: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = onClick, enabled = !loading) {
            Text(if (loading) "加载中…" else "加载更早的消息")
        }
    }
}

/**
 * 助手这条消息的**正文区**该显示什么。
 *
 * ## 为什么失败不能再走「（没有内容）」
 *
 * 原来的判断只看「有没有正文、有没有工具调用」，而请求失败时正文恰好是空的、
 * 也没有工具调用 —— 于是走了和「模型真的没说话」同一个分支。真机上看到的是一屏：
 *
 * ```
 * （没有内容）
 * HTTP 401: Authentication Fails, Your api key: ****abcd is invalid
 * ```
 *
 * 第一句在说「模型选择不说」，第二句在说「这次请求根本没成功」。**前者是错的** ——
 * 模型没有被叫醒，谈不上沉默。而用户看到「（没有内容）」的第一反应是
 * 「模型坏了 / 这软件不行」，真正该做的那个动作（换一个 Key）却缩在下面一行小字里。
 *
 * 所以失败单独成一支：**错误信息本身就是这条消息的内容**。
 *
 * ## 为什么「失败」排在「有工具调用」前面
 *
 * 工具卡片仍然会照常渲染在正文下方（它记录了实际发生过什么），但正文位置留给原因。
 * 一次成功的工具调用之后才轮到模型说话，能走到失败就说明这一轮没有产出可用结果 ——
 * 此时用户最需要知道的是「为什么没有」，不是「曾经调过一个工具」。
 */
internal sealed interface AssistantBody {
    /** 正常正文，走 Markdown 渲染。 */
    data class Text(val content: String) : AssistantBody

    /** 这一轮什么都没产出，而且失败了 —— 原因是唯一的正文。 */
    data class Failure(val reason: String) : AssistantBody

    /** 没正文、没工具、也没失败。状态机允许，兜一句提示。 */
    data object Empty : AssistantBody

    /** 什么都不显示：工具卡片已经说明了发生过什么。 */
    data object Suppressed : AssistantBody
}

internal fun assistantBody(message: MessageUi): AssistantBody = when {
    message.content.isNotBlank() -> AssistantBody.Text(message.content)

    // 失败优先。注意这里**不看 toolCalls** —— 理由见上面的 KDoc
    message.status == MessageStatus.Failed ->
        AssistantBody.Failure(message.error ?: "请求失败")

    message.toolCalls.isNotEmpty() -> AssistantBody.Suppressed

    else -> AssistantBody.Empty
}

/**
 * 这一条消息该不该标命中词 —— 只有**被定位到的那一条**会拿到非空的值。
 *
 * ## 为什么不给整个会话都标上
 *
 * 用户点的是搜索结果里的**某一条**，要回答的问题是「我搜的词在**这条**
 * 消息的哪儿」。把会话里所有出现都标上是另一个功能（会话内查找），
 * 而且在长会话里会糊成一片 —— 同一个词出现几十次时，满屏底色反而让人
 * 找不到刚才点进来的那一条。
 *
 * ## 为什么按 id 比而不是按「第几条」
 *
 * `state.messages` 会随着新消息、流式落库而变化，下标不稳定。
 * id 是稳定的（`MessageUi.isPending` 用负数 id 标记还没落库的乐观消息，
 * 那些也不会是搜索目标 —— 搜索读的是库）。
 *
 * [query] 为空或全空白时一律不标：空白查询在 [highlightRanges] 里
 * 本来就返回空，这里再挡一道是为了不必让下游每个渲染点都处理空串。
 */
internal fun highlightQueryFor(message: MessageUi, targetId: Long?, query: String?): String? =
    query?.takeIf { it.isNotBlank() && targetId != null && message.id == targetId }

/**
 * 一条消息 + 它的长按菜单。
 *
 * ## 为什么是长按
 *
 * 「复制 / 编辑重发 / 重新生成」对**每一条**消息都成立，而屏幕上有几十条。
 * 每条都挂一排按钮的话，一屏里多出上百个可点目标，正文反而看不见了。
 * 长按是移动端表达「关于这一条」的既有手势，不占版面。
 *
 * ## 与文本选择的关系（已知取舍）
 *
 * 正文外面套着 `SelectionContainer`，它也用长按 —— 两者在同一块区域上
 * 抢同一个手势。实测（见 SKILL.md）长按会打开菜单，同时底下可能起一段选区；
 * 关掉菜单后选区自己会消失，不影响下一次操作。
 *
 * 反过来把 `SelectionContainer` 去掉是不行的：**局部选择是长回答里最常用的
 * 动作**（复制一段代码、摘一句话），而「复制整条」覆盖不了它。
 * 宁可让两个手势共存。
 *
 * ## 菜单项的可见性由**能力**决定，不由位置决定
 *
 * `canEdit` / `canRegenerate` 来自 [MessageUi]（角色 + 有没有落库），
 * 而「是不是最后一条」这种位置判断留在外面 —— 位置会随着列表刷新变，
 * 而能力不会。
 */
@Composable
private fun MessageRow(
    message: MessageUi,
    highlightQuery: String?,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
        ) {
            PersistedMessage(message, highlightQuery)
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            // 工具消息也能复制：它的正文就是工具返回的原文，
            // 有时用户要的正是那段（一段 JSON、一份网页摘要）
            if (message.content.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("复制") },
                    onClick = {
                        menuOpen = false
                        copyToClipboard(context, message.content)
                    },
                )
            }
            if (message.canEdit) {
                DropdownMenuItem(
                    text = { Text("编辑重发") },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
            }
            if (canRegenerate) {
                DropdownMenuItem(
                    text = { Text("重新生成") },
                    onClick = {
                        menuOpen = false
                        onRegenerate()
                    },
                )
            }
        }
    }
}

/**
 * 复制到剪贴板。
 *
 * ## 为什么 Android 13 上不弹自己的提示
 *
 * 13 起系统会自己弹一个「已复制」的浮层（那是它的隐私提示，顺带承担了
 * 确认的作用）。我们再弹一个，用户会看到两个叠在一起的提示 ——
 * 看起来像点了两次。
 *
 * 低版本没有这个浮层，所以那时必须自己弹：不弹的话用户完全不知道
 * 复制成功了没有。
 */
private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("消息", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 编辑重发对话框。
 *
 * ## 文案要说清楚「后面会被删掉」
 *
 * 编辑一条**历史**消息不是替换它一个 —— 它后面整段对话都会被丢掉再重跑。
 * 不写出来的话，用户改完点确定，会发现自己后面聊的东西全没了，
 * 而当时界面上什么警告都没有。这跟「删除」的严重程度是同一档，
 * 所以提示语要摆在输入框下面，而不是塞进标题。
 *
 * ## 输入框给多行
 *
 * 原消息可能就是一段带换行的长文本。单行框会把换行压成看不见，
 * 用户一改就丢格式。
 */
@Composable
private fun EditResendDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑并重新发送") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 6,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "发送后，这条消息之后的对话会被删掉，模型会基于新的内容重新回答。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            // 空白内容禁用确定：点下去只会让截断白做一次。
            // 真正的拦截在 ViewModel 里（`editAndResend` 直接返回），
            // 这里禁用只是为了让「按不动」这件事一眼可见
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text("发送")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PersistedMessage(message: MessageUi, highlightQuery: String? = null) {
    when (message.role) {
        // 用户输入**按纯文本渲染**，不做 Markdown。
        // 用户写的 `*` 和 `#` 是字面意思，把它变成斜体或标题是越权解释 ——
        // 而且用户没法像模型那样「换一种写法」来规避
        ChatMessage.Role.User -> Bubble(
            alignEnd = true,
            container = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) { Text(highlightedText(message.content, highlightQuery)) }

        ChatMessage.Role.Assistant -> Column(Modifier.fillMaxWidth()) {
            message.reasoning?.takeIf { it.isNotBlank() }?.let { ReasoningBlock(it) }

            // 助手正文不套气泡：Markdown 里的代码块和表格最吃宽度，
            // 气泡的左右留白加圆角会把可用宽度压掉一截。
            // 直接铺满整行也更接近用户对「读一段回答」的预期
            val body = assistantBody(message)
            when (body) {
                is AssistantBody.Text -> SelectionContainer {
                    MarkdownText(body.content, highlightQuery = highlightQuery)
                }
                is AssistantBody.Failure -> FailureBlock(body.reason)
                AssistantBody.Empty -> Text(
                    text = "（没有内容）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 有工具卡片在，那张卡片已经说明了发生过什么，
                // 再挂一句「（没有内容）」是多余的噪音
                AssistantBody.Suppressed -> Unit
            }

            message.toolCalls.forEach { call -> ToolChip(name = call.name, detail = call.argumentsJson) }

            // 失败且一个字都没产出时，原因已经由上面的 [FailureBlock] 承担了。
            // 页脚再来一句就是同一句话在一屏里出现两遍
            if (body !is AssistantBody.Failure) {
                StatusFooter(status = message.status, error = message.error, model = null)
            }
        }

        ChatMessage.Role.Tool -> ToolCard(
            name = "工具结果",
            output = message.content,
            isError = message.status == MessageStatus.Failed,
            running = false,
        )

        // 系统提示词不展示给用户：它是实现细节，而且往往很长
        ChatMessage.Role.System -> Unit
    }
}

@Composable
private fun LiveSegmentView(segment: LiveSegment) {
    when (segment) {
        // 流式正文用已经解析好的块。解析在 ViewModel 里做，
        // 这里不碰语法 —— 每帧重新解析会白费块缓存
        is LiveSegment.Text -> Column(Modifier.fillMaxWidth()) {
            if (segment.blocks.isNotEmpty()) {
                SelectionContainer { MarkdownBlocks(segment.blocks) }
            } else {
                // 解析还没产出任何块（正文刚起步），先按纯文本显示，
                // 免得出现一瞬间的空白
                Text(segment.text.ifBlank { "…" })
            }
        }

        is LiveSegment.Reasoning -> ReasoningBlock(segment.text)

        is LiveSegment.Tool -> ToolCard(
            name = segment.name,
            output = segment.output,
            isError = segment.isError,
            running = segment.running,
        )
    }
}

/**
 * 消息气泡。
 *
 * 三个细节：
 *
 * 1. **靠说话人那一侧的角收紧**（右下 6dp、其余 18dp）。四个角一样圆的话，
 *    气泡看起来像一个漂浮的圆角矩形，而不是「从这一侧说出来的话」。
 * 2. **最大宽度收到 300dp**。原来是 320dp，而多数手机逻辑宽度只有 360dp ——
 *    等于几乎占满整行，气泡就不成形了。留出约 17% 的空档才看得出「这是右对齐的」。
 * 3. 内边距从 12/8 加到 14/10：正文 15sp 配 8dp 上下边距，行与行之间会挤在一起。
 */
@Composable
private fun Bubble(
    alignEnd: Boolean,
    container: Color,
    contentColor: Color,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = container,
            contentColor = contentColor,
            shape =
                if (alignEnd) {
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = 6.dp,
                    )
                } else {
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 6.dp,
                        bottomEnd = 18.dp,
                    )
                },
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            SelectionContainer {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
            }
        }
    }
}

/** 思维链默认折叠。它是模型的草稿纸，不是回答 —— 展开会淹没正文。 */
@Composable
private fun ReasoningBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                text = if (expanded) "收起思考过程" else "展开思考过程",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Space.md),
                )
            }
        }
    }
}

/**
 * 一次工具调用（**参数**那一侧）。和 [ToolCard]（结果那一侧）配成一对。
 *
 * 用**画出来的图标**而不是原来那个 `🔧` emoji：emoji 的字形由系统字体决定，
 * 各家 ROM 长得都不一样、基线也压不住，而且**颜色不受 `contentColor` 控制**。
 * 在一套自己定的配色里塞一个不受控的彩色字形，是全屏最扎眼的一处不一致。
 */
@Composable
private fun ToolChip(name: String, detail: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(top = Space.xs),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(PbIcons.Tool, contentDescription = null, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(Space.xs))
            Text(text = name, style = MaterialTheme.typography.labelSmall)
            if (detail.isNotBlank()) {
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = detail,
                    style = MonoLabelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 折叠时那一行摘要。
 *
 * 取**首行 + 总长度**：首行能让人看出这是什么（JSON 的第一行、一句错误信息），
 * 总长度告诉他「值不值得点开」。
 *
 * 不给空行——折叠后留一片空白的话，用户根本不知道这里有东西可看。
 */
private fun toolOutputSummary(output: String): String {
    val firstLine = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val head = if (firstLine.length > 56) firstLine.take(56) + "…" else firstLine
    return "$head  ·  ${output.length} 字符"
}

/**
 * 一次工具调用的**结果**。
 *
 * ## 为什么默认折叠
 *
 * 工具返回给**模型**的那段文字是提示词（§48），它必须信息完整 —— 一次城市查询
 * 返回十条候选、每条十四个字段，对模型**全都有用**（`elevation` / `feature_code` /
 * `timezone` 帮它判断哪条候选才对得上用户说的那个地方）。
 *
 * 但**界面不该原样转述**。真机上实测过：`geocode_city` 那坨 JSON 占掉约四分之一
 * 屏高，而用户真正要的那句话被挤到屏幕下面。同一个工具有两个消费者，就该有两份
 * 产物 —— 给模型的保持完整，给用户的默认收起。**别为了界面好看去裁工具返回值**：
 * 裁了模型就丢信息，而模型才是主要消费者。
 *
 * ## 失败时相反
 *
 * 出错时**默认展开** —— 这时候错误信息本身就是正文，折起来等于把唯一的线索藏了。
 * （和 `assistantBody` 的四态是同一个道理。）
 */
@Composable
private fun ToolCard(name: String, output: String?, isError: Boolean, running: Boolean) {
    var expanded by remember { mutableStateOf(false) }

    // 失败时无条件展开，不看 `expanded`：错误信息必须第一眼可见
    val showBody = isError || expanded
    val canToggle = !isError && output != null

    Surface(
        color =
            if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        contentColor =
            if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.md)) {
            Row(
                // 整行可点，而不是只让那个小箭头可点：箭头只有 18dp，
                // 手指点不中；整行是 48dp 高的目标，而且不用瞄准
                modifier =
                    Modifier.fillMaxWidth().then(
                        if (canToggle) Modifier.clickable { expanded = !expanded } else Modifier,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (isError) PbIcons.Warning else PbIcons.Tool,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = if (running) "$name · 执行中…" else name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (canToggle) {
                    Icon(
                        imageVector = if (expanded) PbIcons.ExpandMore else PbIcons.ChevronRight,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (output != null) {
                if (showBody) {
                    Spacer(Modifier.height(Space.sm))
                    SelectionContainer {
                        Text(
                            text = output,
                            style = MonoTextStyle,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    // 上限是**屏高的一部分**，不是「不限」：一次
                                    // `fetch_url` 能返回几百 KB，不限高的话这张卡片
                                    // 会把整段对话推得没法看。超出部分自己滚。
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState()),
                        )
                    }
                } else {
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        text = toolOutputSummary(output),
                        style = MonoLabelStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusFooter(status: MessageStatus, error: String?, model: String?) {
    val label = when (status) {
        MessageStatus.Complete -> null
        MessageStatus.Streaming -> "正在输入…"
        MessageStatus.Stopped -> "已停止"
        MessageStatus.Failed -> error ?: "出错了"
    }
    if (label == null) return
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (status == MessageStatus.Failed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
    )
}

/**
 * 失败时占据正文位置的那一块。
 *
 * 比 [StatusFooter] 那行 `labelSmall` 大一号、还带个底色：它此刻是这条消息
 * **唯一的内容**，用户来这儿就是为了看它 —— 不该比页脚注脚还小。
 *
 * 刻意不写成「（没有内容）」。那句话把「模型选择不说」和「请求没成功」
 * 混成了一件看起来像前者的事，而这两者要用户做的事完全不同。
 */
@Composable
private fun FailureBlock(reason: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = "这条回复没有生成出来",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(2.dp))
            // 原文照抄，不加工：这段是服务端或网络层给的，
            // 改写一遍只会让用户拿它去搜索时搜不到
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

/**
 * 「还不能用」的引导条。
 *
 * ## 它现在讲两件事，而不是一件
 *
 * `needsProvider` 有两种来源（`ChatViewModel.providerOrExplain` 与 `reload`）：
 *
 * 1. **一个服务商都没有** —— 用户刚装上，什么都没配。
 * 2. **有服务商，但它没填 API Key** —— 换过机器、或者在编辑页把 Key 删了。
 *
 * 原来两种情况共用一句「还没有可用的服务商」。第 2 种情况下这句话是**错的** ——
 * 服务商明明在列表里躺着，只是缺一把 Key。用户照着这句话去「找一个服务商」，
 * 找不到就会以为 App 坏了。
 *
 * 两种情况的按钮落点也不同：第 1 种是「新增」，第 2 种是「编辑那一份」。
 * 而 `providerId` 恰好就是这两者的判别式（`providerForConversation` 返回 null 时
 * 它才是 null），所以不必再往 state 里加字段。
 *
 * ## 为什么按钮不叫「去设置」
 *
 * 原来它叫「去设置」，落点是设置页。但设置页是个 hub：**服务商在最后一节**，
 * 前面隔着「记忆」和「集成」两块（那两块的位置是四十轮有意定的，见
 * `ProviderListScreen` 的 KDoc）。于是新用户点完「去设置」，第一屏看不到任何跟
 * 服务商有关的东西，得先往下滚 —— 而他要做的本来只有这一件事。
 *
 * 现在直接进编辑页，文案也换成跟落点对得上的动词短语。
 *
 * ## 这个落点依赖「返回刷新」
 *
 * 编辑页保存后 `onBack()` 回到对话页，`LifecycleEventEffect(ON_RESUME)` 触发
 * `reload()`，那里重算 `needsProvider` —— 所以保存成功之后这条会自己消失。
 * 若哪天把那个 effect 去掉，这里就变成一条**永远消不掉的横幅**。
 */
@Composable
private fun SetupHint(
    /** 一个服务商都没有时是 null；有服务商但缺 Key 时是那一个的 id。 */
    providerId: String?,
    onOpenProvider: (String?) -> Unit,
) {
    val noneAtAll = providerId == null
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = if (noneAtAll) "还没有可用的服务商" else "这个服务商还缺 API Key",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                // ⚠️ 这里**不能**说「不会经过任何服务器」—— 那句话和事实相反：
                // Key 会作为 `Authorization` 头发往用户自己填的那个地址
                // （`OpenAiChatClient` 里那一处）。想表达的其实是「**没有中转**」，
                // 而唯一说得准的说法是**目的地**：只发往你填的地址。
                // 判据在 `PrivacyCopyTest` 里守着。
                text =
                    if (noneAtAll) {
                        "填一个 OpenAI 兼容的接口地址和你的 API Key 就能开始。" +
                            "Key 只存在这台设备上，请求直接从这台设备发往你填的地址。"
                    } else {
                        "没有 Key 就发不出请求。Key 只存在这台设备上，" +
                            "请求直接从这台设备发往你填的地址。"
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            PbTonalButton(onClick = { onOpenProvider(providerId) }) {
                Text(if (noneAtAll) "添加服务商" else "去填 API Key")
            }
        }
    }
}

/**
 * 「这个会话用哪个服务商」选择器。
 *
 * 会话是**黏住**的（见 `ConversationRouter`）：钉上之后不会因为用户改了
 * 默认服务商而变。所以必须有一个出口 —— 没有它，用户唯一的办法是删掉
 * 整个会话，而消息会跟着一起没。
 *
 * [choices] 为 null 表示还没读出来，空列表表示确实一个都没配 ——
 * 这两种状态在界面上是两句话，不能混。
 *
 * ## 空列表时按钮要跟着换
 *
 * 原来这个弹窗的按钮**恒为「取消」**：正文写着「去「设置」加一个」，
 * 而唯一的出路是把弹窗关掉、自己去找设置入口 —— 指了个方向却不给出口。
 * 现在空列表时主按钮直接变成「添加服务商」，跟正文说的是同一件事。
 * 有服务商可选时按钮仍是「取消」，那时候「取消」才是用户真要的那个。
 */
@Composable
private fun ProviderPickerDialog(
    choices: List<ProviderChoice>?,
    currentId: String?,
    onPick: (String) -> Unit,
    onAddProvider: () -> Unit,
    onDismiss: () -> Unit,
) {
    val noneAtAll = choices != null && choices.isEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这个会话用哪个服务商") },
        text = {
            when {
                choices == null -> Text("正在读取…")

                noneAtAll -> Text(
                    "还没有配置服务商。填一个 OpenAI 兼容的接口地址和你的 API Key 就能开始。"
                )

                else -> Column {
                    Text(
                        text = "只影响这一个会话 —— 其它会话各自用它们原来的服务商。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    choices.forEach { choice ->
                        ProviderChoiceRow(
                            choice = choice,
                            isCurrent = choice.id == currentId,
                            onClick = { onPick(choice.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (noneAtAll) {
                TextButton(onClick = onAddProvider) { Text("添加服务商") }
            } else {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun ProviderChoiceRow(
    choice: ProviderChoice,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = choice.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = choice.model,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            // 「当前」优先于「没填 Key」：两个都占是可能的（用户把当前
            // 服务商的 Key 删了），但此刻他最需要知道的是「哪个在用」
            isCurrent -> Text(
                text = "当前",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            !choice.hasApiKey -> Text(
                text = "没填 Key",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    canSend: Boolean,
    streaming: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Space.sm),
            // Bottom 而不是 CenterVertically：输入框能长到 5 行，按钮该跟着**底边**
            // 走（聊天输入栏的惯例），而不是浮到垂直中线上
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么…") },
                maxLines = 5,
            )
            Spacer(Modifier.size(Space.sm))
            // 高度必须和输入框**单行**时一样：56dp 是 M3 文本字段的最小高度。
            //
            // 原来这里是裸的 FilledTonalButton，高 40dp，和 56dp 的输入框底边对齐 ——
            // 于是按钮看起来像贴在输入框右下角的一枚邮票。在 411dp 的手机宽度上，
            // 这个错位比数字本身明显得多。
            //
            // 这里只对齐**高度**。形状由 PbTonalButton 统一给（10dp 圆角）；
            // 输入框仍是 Material 的默认圆角，那一半还没收（见 `theme/Shape.kt`）。
            val actionHeight = Modifier.height(56.dp)
            if (streaming) {
                PbTonalButton(onClick = onStop, modifier = actionHeight) { Text("停止") }
            } else {
                PbTonalButton(onClick = onSend, enabled = canSend, modifier = actionHeight) {
                    Text("发送")
                }
            }
        }
    }
}

/** 给自动跟随用：段的「长度」。文本段随 token 增长，工具段不增长。 */
private fun LiveSegment.lengthHint(): Int = when (this) {
    is LiveSegment.Text -> text.length
    is LiveSegment.Reasoning -> 0
    is LiveSegment.Tool -> 0
}

/**
 * 「最后一个 item 的底边是不是已经进了视口」。
 *
 * ## 为什么不能按条数判断
 *
 * 最早的写法是 `firstVisibleItemIndex >= totalItemsCount - 2`，
 * 意思是「第一个可见条目已经很靠近末尾了」。这是拿**条数**猜位置，
 * 而一条消息的高度可以差几十倍：一段 Markdown 回复能比整屏还高，
 * 也可能只有一行字。
 *
 * 实测后果：发一条长回复，可见窗口里只装得下两三条，第一个可见条目的
 * 序号离总数还差好几个，于是 `follow` 被判成 false，**自动滚动整个失效**。
 * 回复确实到了 —— 数据库里有、Compose 语义树里也有（`uiautomator dump`
 * 能看到它的文字）—— 但停在屏幕下方看不见，表现成「模型没回」。
 * 短回复恰好塞得进剩余空间，所以只有长回复会暴露这个 bug。
 *
 * ## 为什么抽成纯函数
 *
 * 它只依赖布局数据，不需要真的跑一个 LazyColumn，因此能在 JVM 单测里
 * 把这个判断钉住。这个 bug 只在真机上、只对长回复可见，靠手测很容易漏。
 *
 * @param lastVisibleBottom 最后一个可见条目的底边（相对视口顶部，可能为负）
 * @param afterContentPadding 列表底部的内容留白 —— 滚到底时它也在视口外，
 *   不算进去的话「到底了」永远差这几十像素，跟随会一直被判成 false
 * @param tolerance 允许的像素误差，避免亚像素抖动让跟随反复开关
 */
internal fun isAtBottom(
    totalItems: Int,
    lastVisibleIndex: Int,
    lastVisibleBottom: Int,
    viewportEndOffset: Int,
    afterContentPadding: Int,
    tolerance: Int = 4,
): Boolean {
    if (totalItems <= 0) return true
    if (lastVisibleIndex != totalItems - 1) return false
    return lastVisibleBottom + afterContentPadding <= viewportEndOffset + tolerance
}

/**
 * 从搜索结果点进来时，要定位的那条消息在列表里的下标。
 *
 * 返回 `null` 表示**不定位**，有两种情况：
 *
 * - 本来就没带定位意图（普通打开会话）；
 * - 带了，但那条消息不在这个会话里 —— 会话被清空过、消息被删了，
 *   或者导航参数被伪造了。
 *
 * 第二种必须退回「落到底部」，而不是滚到一个不存在的位置：
 * `scrollToItem` 传一个越界下标会抛，而静默停在别处则会让用户以为
 * 自己点错了。
 */
internal fun highlightIndex(messages: List<MessageUi>, targetId: Long?): Int? {
    if (targetId == null) return null
    val index = messages.indexOfFirst { it.id == targetId }
    return index.takeIf { it >= 0 }
}

private fun LazyListState.atBottom(): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    return isAtBottom(
        totalItems = info.totalItemsCount,
        lastVisibleIndex = last.index,
        lastVisibleBottom = last.offset + last.size,
        viewportEndOffset = info.viewportEndOffset,
        afterContentPadding = info.afterContentPadding,
    )
}

/**
 * 滚到**真正的底部**。
 *
 * 不能只写 `scrollToItem(itemCount - 1)` —— 那只是把最后一个 item 的
 * **顶部**对齐到视口顶部。一条长回复比屏幕还高，于是「滚到底」的结果是
 * 停在它的开头，后面吐出来的字全在屏幕外面。这和 [isAtBottom] 是同一个
 * 症状的两个来源。
 *
 * 所以滚到顶部之后**再量一次**：它离底部还差多少像素，补上。
 * 差的量从 [LazyListState.layoutInfo] 拿，是布局之后的真实值，不靠估算。
 */
private suspend fun LazyListState.scrollToBottom(itemCount: Int) {
    if (itemCount <= 0) return
    scrollToItem(itemCount - 1)

    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    if (last.index != itemCount - 1) return

    val deficit = last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
    if (deficit > 0) scrollBy(deficit.toFloat())
}

/**
 * 「允许执行这个工具吗」。
 *
 * ## 三件必须同时给到用户的信息
 *
 * 1. **工具名** —— 发生了什么
 * 2. **一句话说明**（[ToolApprovalGate.Request.toolSummary]）—— 这类操作意味着什么
 * 3. **参数原文** —— 具体要动什么
 *
 * 第 3 条最容易被忽略，但它才是用户真正要判断的东西：
 * 「要不要允许发网络请求」没法回答，而「要不要允许请求
 * `http://192.168.1.1/admin`」是可以回答的。
 *
 * 所以参数必须**格式化后原样展示**，不做截断、不折叠、不美化掉可疑部分。
 * 提示注入的攻击面正是藏在这些参数里 —— 把它们藏起来等于把最后一道
 * 人工检查也关掉了。
 *
 * ## 为什么没有「本次对话不再询问」
 *
 * 那个开关会把确认机制变成一次性的形式：用户点过一次之后，模型就能在
 * 剩余对话里任意发请求，而用户以为自己还在盯着。真嫌烦的话，正确的做法是
 * 让插件声明更细的权限范围（只允许某几个域名），而不是「不再询问」。
 */
@Composable
private fun ToolApprovalDialog(
    request: ToolApprovalGate.Request,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        // 点外面或按返回 = 拒绝。绝不能默认放行 —— 误触的代价不对称：
        // 误拒只是这次调用失败（模型会换方式），误允许是请求真的发出去了。
        onDismissRequest = onDeny,
        title = { Text("允许执行「${request.toolName}」？") },
        text = {
            Column {
                Text(
                    text = request.toolSummary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "它要用的参数：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Surface(
                    // 底色**不能**用 `surfaceVariant` —— 它在浅色主题下是 `Ink100`，
                    // 而 `AlertDialog` 的默认底色是 `surfaceContainerHigh`，**也是
                    // `Ink100`**（见 `theme/Theme.kt`）。两者相同 = 这个块在视觉上
                    // 根本不存在：三十六轮量它的圆角时，整行像素扫描没有任何跳变，
                    // 全程 `edeff4`。
                    //
                    // 换 `surfaceContainerHighest`：浅色下是 `Ink200`（比弹窗底色深
                    // 16 个色阶），深色下是 `#262A33`（比弹窗底色亮 8 个色阶）——
                    // 两个主题下都分得开。
                    //
                    // ⚠️ 消息里的代码块用的是 `surfaceVariant`，这里**故意不同**。
                    // 同一个颜色在不同上下文里的可见性不一样：代码块的背景是页面
                    // 底色（`Ink25`，浅），参数块的背景是弹窗底色（本身就是 `Ink100`）。
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Text(
                            text = request.argumentsJson,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text("允许") } },
        dismissButton = { TextButton(onClick = onDeny) { Text("拒绝") } },
    )
}
