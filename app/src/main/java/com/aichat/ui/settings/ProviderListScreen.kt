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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.aichat.ui.common.PbSectionLabel

/**
 * 设置页。
 *
 * ## 标题从「服务商」改成了「设置」
 *
 * 这个页面里装着三样东西：隐私说明、长期记忆开关、插件入口、服务商列表。
 * 而标题一直写着「服务商」—— 用户从首页点「设置」进来，看到的是「服务商」，
 * 会以为走错了；而长期记忆和插件两个入口**看起来像是服务商页的附注**，
 * 实际它们是这个 App 的主线功能。
 *
 * 现在改成「设置」，并给三块内容各加一行小节标题，让层级自己说话。
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(PbIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(null) },
                icon = { Icon(PbIcons.Add, contentDescription = null) },
                text = { Text("添加服务商") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding =
                PaddingValues(
                    start = Space.lg,
                    end = Space.lg,
                    top = Space.md,
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
        }
    }
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

        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            TextButton(onClick = onEdit) { Text("编辑") }
            if (!row.isDefault) {
                TextButton(onClick = onSetDefault) { Text("设为默认") }
            }
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
