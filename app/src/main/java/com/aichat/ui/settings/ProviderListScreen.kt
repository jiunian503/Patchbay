package com.aichat.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.di.AppContainer
import com.aichat.theme.MonoLabelStyle
import com.aichat.theme.Space
import com.aichat.ui.common.PbCard
import com.aichat.ui.common.PbHintCard
import com.aichat.ui.common.PbIcons
import com.aichat.ui.common.PbNavRow
import com.aichat.ui.common.PbScaffold
import com.aichat.ui.common.PbSectionLabel
import com.aichat.ui.conversations.formatTime

/**
 * 设置页。
 *
 * ## 标题从「服务商」改成了「设置」
 *
 * 这个页面里装着六块东西：隐私说明、长期记忆开关、集成（插件 / 联网搜索）、
 * 服务商列表、关于（检查更新）、诊断（崩溃记录）。而标题一直写着「服务商」——
 * 用户从首页点
 * 「设置」进来，看到的是「服务商」，会以为走错了；而长期记忆和插件两个入口
 * **看起来像是服务商页的附注**，实际它们是这个 App 的主线功能。
 *
 * 现在改成「设置」，并给每一块加一行小节标题，让层级自己说话。
 *
 * 「诊断」那一节**只在真有崩溃记录时才渲染** —— 理由见文件末尾那段注释。
 *
 * ## 「集成」下面现在有两个入口
 *
 * 插件和**联网搜索**。两者都是「给模型接上外部能力」，差别在形态：
 * 插件是用户自己装的能力包，联网搜索是内置工具 + 用户自己填的搜索后端。
 * 放在同一节里是因为用户对它们的心理预期是同一件事 ——「让模型能查到更多」。
 * 卡片上的说明文字会随开关状态变（关着 / 已开启：SearXNG），
 * 这样不进二级页也能看出当前是什么状态。
 *
 * ## 整页一起滚
 *
 * 原来上半部分（隐私 + 记忆 + 插件）固定、只有服务商列表滚。四个服务商以上时，
 * 用户想看到最后一个必须在小区域里滚动，而屏幕上半截永远是那三张卡。
 * 现在整页一个 LazyColumn，跟手、跟普通设置页一样。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderListScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenWebSearch: () -> Unit,
    onOpenCrashLogs: () -> Unit,
) {
    val viewModel: ProviderListViewModel = viewModel { ProviderListViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }

    // 删服务商是不可撤销的：用户填的密钥会一起删掉（要重新填一遍）。
    // 另外黏在这个服务商上的会话会退回默认 —— 那等于它们**悄悄换了模型**，
    // 所以确认框里要把这件事说出来，而不是只问一句「确定吗」。
    var pendingDelete by remember { mutableStateOf<ProviderRow?>(null) }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「${target.name}」？") },
            text = {
                Text(
                    "这个服务商和它保存的密钥会一起删掉，而且不能撤销 —— " +
                        "要恢复只能重新填一遍。之前用它的会话会自动改用默认服务商，" +
                        "也就是换了模型。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.delete(target.id)
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    // 「有新版本」那个框。
    //
    // 它由 `updateDialog` 单独一个字段控制，而**不是**「update 是 Available 就弹」——
    // 那样用户关掉框之后，只要这一页重组一次（切出去再回来、改个开关）就又会弹，
    // 变成关不掉的骚扰。现在的规则是：查到的那一刻弹一次，之后只有他自己
    // 再点那一行才会弹。
    val update = state.update
    if (state.updateDialog && update is UpdateUiState.Available) {
        UpdateAvailableDialog(available = update, onDismiss = viewModel::dismissUpdateDialog)
    }

    PbScaffold(
        title = "设置",
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(null) },
                icon = { Icon(PbIcons.Add, contentDescription = null) },
                text = { Text("添加服务商") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { topInset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = Space.lg,
                    end = Space.lg,
                    // 顶栏浮在列表上方，第一条要自己避开它（见 PbScaffold 的 KDoc）
                    top = Space.md + topInset,
                    // 底部让开 FAB，否则最后一张卡会被它压住
                    bottom = 88.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            item { PrivacyNotice() }

            item { PbSectionLabel("记忆") }
            item {
                LongTermMemoryCard(
                    enabled = state.longTermMemory,
                    onChange = viewModel::setLongTermMemory,
                )
            }

            item { PbSectionLabel("集成") }
            // 插件和联网搜索都放在设置页顶部而不是底部：它们是这个 App 的主线功能
            //（「集成各种能力」），不是一条次要的配置项
            item {
                PbNavRow(
                    icon = PbIcons.Plugin,
                    title = "插件",
                    body = "装上插件之后，模型就能调用它提供的能力 —— 查天气、读表格、" +
                        "连你自己的服务。插件只能按它声明的权限工作。",
                    onClick = onOpenPlugins,
                )
            }
            item {
                PbNavRow(
                    icon = PbIcons.Globe,
                    title = "联网搜索",
                    body = if (state.webSearchLabel.isBlank()) {
                        "关着。配一个搜索后端之后，模型遇到不知道的事可以自己上网查。"
                    } else {
                        "已开启：${state.webSearchLabel}。模型可以自己决定搜什么。"
                    },
                    onClick = onOpenWebSearch,
                )
            }

            item { PbSectionLabel("服务商") }

            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(Space.xxl), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.items.isEmpty()) {
                item {
                    PbHintCard(
                        icon = PbIcons.Key,
                        title = "还没有配置服务商",
                        body = "填一个 OpenAI 兼容的接口地址和你的 API Key 就能开始。",
                    )
                }
            } else {
                items(state.items, key = { it.id }) { row ->
                    ProviderCard(
                        row = row,
                        onEdit = { onEdit(row.id) },
                        onSetDefault = { viewModel.setDefault(row.id) },
                        onDelete = { pendingDelete = row },
                    )
                }
            }

            // 「关于」放在服务商之后、诊断之前。
            //
            // **为什么不放最末**：诊断那一节只在真有崩溃记录时才出现（见下面），
            // 它的语义是「这里出了点事，你看看」。把一个不常出现的东西放在页尾，
            // 整页的高度会随状态跳；关于是常驻的，放它前面，页尾就稳定了。
            //
            // **为什么不放更上面**：这一节里没有需要经常改的东西。
            // 设置页的顺序是「越往下越少动」，关于排在最后一批是对的。
            item { PbSectionLabel("关于") }
            item {
                PbNavRow(
                    icon = PbIcons.Refresh,
                    title = "检查更新",
                    // 正文由 [updateRowText] 给 —— 那一行要说的不只是「点我」，
                    // 还包括当前是哪一版、查完的结果是什么。判断都在纯函数里，
                    // 所以「比不了」和「已是最新」说的是两句不同的话这件事，
                    // 有单测钉着
                    body = updateRowText(state.update),
                    // 已经查到有新版本时，点这一行是**把框再弹出来**而不是再查一次 ——
                    // 分流在 ViewModel 里（见 onUpdateRowClick 的 KDoc）
                    onClick = viewModel::onUpdateRowClick,
                )
            }

            // 「诊断」放在**最后**，两个理由：
            //
            // 1. 它是关于 App 自己的信息，不是配置。这一页的小节顺序是
            //    「越往下越具体」，诊断在语义上属于页尾 —— 而且不该把服务商
            //    列表（这一页的主内容）挤到下面去。
            // 2. 它只在**真有崩溃记录**时出现。放一个灰着的入口在那儿、点进去
            //    看到一页空白，用户只会以为这功能坏了 —— 和空状态是同一条
            //    道理（§76：文案要回答「为什么现在是空的」）。
            val crash = state.crashes
            if (crash != null) {
                item { PbSectionLabel("诊断") }
                item {
                    PbNavRow(
                        icon = PbIcons.Warning,
                        title = "崩溃记录",
                        body = "有 ${crash.count} 条记录，最近一次在 ${formatTime(crash.latestAt)}。" +
                            "堆栈只存在这台设备上，复制出来就能拿去查。",
                        onClick = onOpenCrashLogs,
                    )
                }
            }
        }
    }
}

/**
 * 「有新版本」的框。
 *
 * ## 为什么值得弹一个框
 *
 * 「检查更新」的结果本来只写在那一行上。但这一行在设置页**底部**，
 * 用户点了之后视线可能已经往下移了 —— 更常见的是他点了就退出去，
 * 那一行的变化他根本没看到。有新版本是**需要他做决定**的事
 * （现在去下载 / 以后再说），所以值得打断一次。
 *
 * 反过来，查失败 / 比不了 / 已是最新**都不弹框** —— 那些只是「一次点击的
 * 结果」，写在那一行上就够了，弹框等于让他多点一次「知道了」。
 *
 * ## 「去下载」打不开浏览器时不能默默什么都不发生
 *
 * [LocalUriHandler.openUri] 在没有能处理这个链接的应用时会抛
 * （`ActivityNotFoundException`）。原来这里只 `runCatching` 吞掉的话，
 * 用户点完框没关、浏览器没开、也没人说一句话 —— 他唯一能得出的结论是
 * 「这个按钮是坏的」。所以失败时**不关框**，并把地址明写出来让他复制。
 *
 * 这和聊天页里点 Markdown 链接的处理不一样（那边是静默吞掉）：
 * 那边链接是模型给的、几十个，弹一次错没有意义；这里是用户主动点的、
 * 全 App 唯一一处，值得说清楚。
 */
@Composable
private fun UpdateAvailableDialog(available: UpdateUiState.Available, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    // 只活在这个框里的状态，所以不往 ViewModel 里塞 —— 框关了就没了，
    // 没有需要跨重组或跨页面记住的东西
    var openFailed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("有新版本 ${available.latest}") },
        text = {
            Column {
                Text(
                    text = if (openFailed) {
                        "没能打开浏览器。发布页面在下面这个地址，复制到浏览器里打开就能下载。"
                    } else {
                        "你现在用的是 ${available.current}。去下载会打开 GitHub 上的发布页面。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (openFailed) {
                    Spacer(Modifier.height(Space.sm))
                    // 要能选中才能复制。不可选的文字在这儿等于没有
                    SelectionContainer {
                        Text(
                            text = available.pageUrl,
                            style = MonoLabelStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.height(Space.sm))
                    // 这句是给「装不上」做准备的。从浏览器下载 APK 再装，
                    // Android 一定会提「未知来源」；不提的话用户会以为
                    // 这个 App 有问题，而不是以为这是正常流程
                    Text(
                        text = "覆盖安装不会动你已有的会话和密钥 —— 它们都在这台设备上。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val opened =
                        runCatching { uriHandler.openUri(available.pageUrl) }.isSuccess
                    // 打开了才关框。没打开就留在这儿，并把地址亮出来
                    if (opened) onDismiss() else openFailed = true
                },
            ) {
                Text(if (openFailed) "再试一次" else "去下载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("以后再说") }
        },
    )
}

/**
 * 「密钥只存在这台设备上」。
 *
 * 用**带边框的普通卡片 + 一个信息图标**，而不是原来那块铺满宽度的浅蓝横幅：
 * 横幅在视觉上等同于「警告」，而这段话不是警告 —— 它是这个 App 的卖点。
 * 现在它读起来像一条说明，而不是一个需要处理的状况。
 */
@Composable
private fun PrivacyNotice() {
    PbCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                PbIcons.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Space.sm))
            Column {
                Text(
                    text = "密钥只存在这台设备上",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    text = "API Key 用 AndroidKeyStore 加密后保存，不会上传到任何服务器，" +
                        "也不会写进聊天数据库。请求直接从这台设备发往你填的地址。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 「长期记忆」开关。
 *
 * ## 为什么这个开关必须存在
 *
 * 开着的时候，模型能主动检索历史对话 —— 而检索到的内容会随**下一次请求**
 * 发给服务商。这是这个 App 里唯一一处「用户没做什么，数据就出去了」的地方，
 * 所以它不能只写在文档里。
 *
 * ## 文案为什么分成两段
 *
 * 第一段讲**关掉之后会怎样**（模型看不到历史），第二段讲两件容易被误解的事：
 *
 * 1. **关掉只影响模型**。用户自己的搜索页不受影响 —— 不然他会以为
 *    「关了连我自己都搜不了了」，于是不敢关。
 * 2. **开着的时候数据会去哪**。这句话不好写：写轻了等于没说，写重了像在
 *    吓唬人。落点是「检索到的内容会随下一次请求发给服务商」——
 *    只陈述事实，不评价。
 */
@Composable
private fun LongTermMemoryCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    PbCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                PbIcons.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Space.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    "长期记忆",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text =
                        if (enabled) {
                            "模型可以翻你以前的对话，回答时用上。"
                        } else {
                            "模型看不到你以前的对话。"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onChange)
        }
        Spacer(Modifier.height(Space.sm))
        Text(
            text = "关掉只影响模型 —— 你自己在搜索里找历史还是可以的。" +
                "开着的时候，模型检索到的内容会随下一次请求发给服务商。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 一个服务商。
 *
 * 几处调整：
 *
 * - **地址和模型名用等宽**。它们是要被逐字符核对的东西（`127.0.0.1:8765` 和
 *   `l`/`1`/`I` 分不清是常见事故），比例字体下容易看错。
 * - **「默认」从小字改成一个小标签**。它是这一行里最重要的状态 ——
 *   决定「新会话发到哪儿」，不该和旁边那些灰色小字一个分量。
 * - **没填密钥的那句从 `⚠️` 换成画出来的图标**，理由同聊天页的工具 chip。
 */
@Composable
private fun ProviderCard(
    row: ProviderRow,
    onEdit: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    PbCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.isDefault) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = "默认",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = Space.sm, vertical = 2.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(Space.sm))

        Text(
            text = row.baseUrl,
            style = MonoLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = row.model,
            style = MonoLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(Space.xs))

        if (row.hasApiKey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    PbIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(Space.xs))
                Text(
                    text = "密钥 ${row.keyHint}",
                    style = MonoLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    PbIcons.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(Space.xs))
                Text(
                    text = "还没有填密钥",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(Space.sm))

        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onEdit) { Text("编辑") }
            if (!row.isDefault) {
                TextButton(onClick = onSetDefault) { Text("设为默认") }
            }
            Spacer(Modifier.weight(1f))
            // 删除收进 ⋮ 溢出菜单（§54）。
            //
            // 原来它和「编辑」并排常驻，每个服务商一个红字 —— 两个服务商就是两个
            // 红色按钮，列表看起来像「一排删除」，滑动时也极易蹭到。而删服务商
            // 比删一条会话代价高得多：**它保存的密钥会一起删掉**，要恢复只能重填一遍。
            //
            // 收进菜单后仍然一眼能找到（不用长按、不用猜手势），但不再抢注意力。
            // 二次确认框照旧保留 —— 那是最后一道闸。
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        PbIcons.MoreVertical,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
