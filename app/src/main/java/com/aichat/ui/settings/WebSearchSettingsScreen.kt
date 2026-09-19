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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.di.AppContainer
import com.aichat.theme.MonoTextStyle
import com.aichat.theme.Space
import com.aichat.tools.WebSearchBackend
import com.aichat.ui.common.PbButton
import com.aichat.ui.common.PbCard
import com.aichat.ui.common.PbIcons
import com.aichat.ui.common.PbOutlinedButton
import com.aichat.ui.common.PbScaffold
import com.aichat.ui.common.PbSectionLabel

/**
 * 联网搜索的设置页。
 *
 * ## 这一页要回答的三个问题
 *
 * 1. **这是什么** —— 顶部那张说明卡。它不是「一个功能」，是给模型加了一个
 *    能自己上网查的工具，所以开启之后模型的行为会变（它会开始搜索）。
 * 2. **数据去哪了** —— 关键词会发给用户选的那个搜索服务。这句话必须写在这一页上，
 *    因为这是这个 App 里**唯一一处内容会流向第三方**（对话本身只流向用户自己的
 *    服务商）。用户配搜索的那一刻就该知道。
 * 3. **怎么知道自己配对了** —— 「测试」按钮。BYOK 的配置错误有九成是
 *    地址多一个斜杠、密钥少一位，而这两种错在保存时都看不出来。
 *
 * ## 为什么后端选项要带一段说明，而且 Brave 排在最前面
 *
 * 「SearXNG / Brave / Tavily」对没接触过的人来说是三个无意义的词。
 * 每行下面那句是**选它的理由**，不是它的定义 —— 用户是按理由选的，
 * 不是按名字选的。
 *
 * 顺序按「第一次试就能成」的概率排，而不是按「技术上更纯粹」排：
 *
 * - **Brave / Tavily** 要注册，但注册完就一定能用。
 * - **SearXNG** 不用注册，可是**公共实例基本都不能用** —— 实测了一圈，
 *   要么关掉了 json 输出格式（返回 HTML 搜索页），要么挡在一层人机验证
 *   后面（Anubis 那类），要么直接 429。所以它实际上意味着「自己部署一个」。
 *
 * 一个功能如果第一次试就失败，用户不会再试第二次 —— 所以把最可能成的
 * 放前面，把「要自己搭」的放最后，并在它的说明里直说这件事。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSearchSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: WebSearchSettingsViewModel = viewModel { WebSearchSettingsViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.savedTick) {
        if (state.savedTick > 0) {
            snackbar.showSnackbar(if (state.backend == null) "已关闭联网搜索" else "已保存")
        }
    }

    PbScaffold(
        title = "联网搜索",
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { topInset ->
        if (state.loading) {
            Box(
                Modifier.fillMaxSize().padding(top = topInset),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@PbScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = Space.lg,
                    end = Space.lg,
                    // 顶栏浮在列表上方，第一条要自己避开它（见 PbScaffold 的 KDoc）
                    top = Space.md + topInset,
                    bottom = Space.xxl,
                ),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            item { IntroCard() }

            item { PbSectionLabel("搜索后端") }

            item {
                BackendOption(
                    title = "关闭",
                    body = "模型只能靠自己的知识回答，遇到不知道的会说不知道。",
                    selected = state.backend == null,
                    onClick = { viewModel.selectBackend(null) },
                )
            }
            item {
                BackendOption(
                    title = "Brave Search",
                    body = "有免费额度，结果稳定，接口最简单。到 Brave 的控制台申请一个 token " +
                        "就能用，是三个里最省事的。",
                    selected = state.backend == WebSearchBackend.BRAVE,
                    onClick = { viewModel.selectBackend(WebSearchBackend.BRAVE) },
                )
            }
            item {
                BackendOption(
                    title = "Tavily",
                    body = "为模型设计的搜索，返回的摘要更长更整齐，适合需要读一段话的问题。" +
                        "同样有免费额度，注册后拿一个 API Key。",
                    selected = state.backend == WebSearchBackend.TAVILY,
                    onClick = { viewModel.selectBackend(WebSearchBackend.TAVILY) },
                )
            }
            item {
                BackendOption(
                    title = "SearXNG",
                    body = "自己搭一个元搜索实例。不用注册、不用付费、没有配额，" +
                        "但要找一个打开了 JSON 输出的实例 —— 公共实例大多关掉了它，" +
                        "而且不少挡在人机验证后面，所以这条路通常意味着自己部署一个。",
                    selected = state.backend == WebSearchBackend.SEARXNG,
                    onClick = { viewModel.selectBackend(WebSearchBackend.SEARXNG) },
                )
            }

            val backend = state.backend
            if (backend != null) {
                item { PbSectionLabel("接口地址") }
                item {
                    EndpointField(
                        value = state.endpoint,
                        backend = backend,
                        onChange = viewModel::onEndpointChange,
                    )
                }

                item { PbSectionLabel("API Key") }
                item {
                    KeyField(
                        value = state.apiKeyInput,
                        backend = backend,
                        configured = state.keyConfigured && !state.clearedKey,
                        onChange = viewModel::onKeyChange,
                        onClear = viewModel::clearKey,
                    )
                }

                state.testMessage?.let { message ->
                    item { TestResultCard(message = message, failed = state.testFailed) }
                }
            }

            state.formError?.let { message ->
                item { ErrorLine(message) }
            }

            // ## 保存按钮在 if 外面
            //
            // 一度写在 `if (backend != null)` 里面，于是**选「关闭」之后按钮整个消失** ——
            // 用户根本没办法把联网搜索关掉并落盘（真机实测踩到）。
            // 「关闭」是一个要被保存的状态，不是「没有状态」。
            //
            // 「测试」则是另一回事：没选后端时没有可测的东西，所以它只在选了后端时出现。
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Space.sm),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    PbButton(
                        onClick = viewModel::save,
                        enabled = !state.saving && !state.testing,
                    ) {
                        Text(if (state.saving) "保存中…" else "保存")
                    }
                    if (backend != null) {
                        PbOutlinedButton(
                            onClick = viewModel::test,
                            enabled = !state.saving && !state.testing,
                        ) {
                            Text(if (state.testing) "测试中…" else "测试")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 顶部说明。
 *
 * 落点只有两句：**它改变了模型的行为**（会开始自己搜），以及
 * **关键词会离开这台设备**。第二句不好写 —— 写轻了等于没说，
 * 写重了像在吓唬人。所以只陈述事实：发给谁、发的是什么。
 */
@Composable
private fun IntroCard() {
    PbCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                PbIcons.Globe,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Space.sm))
            Column {
                Text(
                    "让模型能上网查",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    text = "开启之后，模型遇到它不知道、或者可能已经变化的事情时，" +
                        "会自己去搜，而不是凭记忆猜。它每次搜什么由它自己决定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.sm))
                Text(
                    text = "搜索的关键词会发给你选的那个服务 —— 不是发给我们，" +
                        "这个 App 没有任何中转服务器。除此之外的内容仍然只发给你自己的服务商。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一个后端选项。选中时左侧出现对勾，边框变主题色 —— 两个信号，不只靠颜色。 */
@Composable
private fun BackendOption(
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    PbCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                if (selected) PbIcons.Check else PbIcons.Circle,
                contentDescription = null,
                tint =
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
            )
            Spacer(Modifier.width(Space.sm))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color =
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 接口地址。
 *
 * 用等宽字体：这是要被逐字符核对的东西（`searx.be` 和 `searx.be/` 是两回事，
 * `l`/`1`/`I` 分不清是常见事故），比例字体下容易看错。
 */
@Composable
private fun EndpointField(
    value: String,
    backend: WebSearchBackend,
    onChange: (String) -> Unit,
) {
    val hint = when (backend) {
        WebSearchBackend.SEARXNG ->
            "填你用的那个实例的地址，例如 https://searx.be。" +
                "不用自己写 /search，我们会补上。"
        WebSearchBackend.BRAVE, WebSearchBackend.TAVILY ->
            "默认已经填好，除非你要走代理，一般不用改。"
    }

    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("地址") },
        singleLine = true,
        textStyle = MonoTextStyle,
        placeholder = {
            Text(
                when (backend) {
                    WebSearchBackend.SEARXNG -> "https://searx.be"
                    else -> "https://…"
                },
                style = MonoTextStyle,
            )
        },
        supportingText = { Text(hint, style = MaterialTheme.typography.labelSmall) },
    )
}

/**
 * 密钥。
 *
 * 已存的密钥**永远不回显** —— 它从 KeyStore 里读出来放进 Compose 状态，
 * 等于把它多复制一份到内存和快照系统里（插件配置页也是这个规矩）。
 * 代价是「没碰」和「想删掉」在框里长得一样，所以必须有一个显式的「清除」。
 */
@Composable
private fun KeyField(
    value: String,
    backend: WebSearchBackend,
    configured: Boolean,
    onChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val hint = when (backend) {
        WebSearchBackend.SEARXNG -> "大多数公共实例不需要密钥。你的实例要求的话再填。"
        WebSearchBackend.BRAVE -> "在 Brave Search API 的控制台创建订阅后拿到的 token。"
        WebSearchBackend.TAVILY -> "以 tvly- 开头的 API Key。"
    }

    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") },
            singleLine = true,
            textStyle = MonoTextStyle,
            visualTransformation = PasswordVisualTransformation(),
            placeholder = {
                Text(if (configured) "已配置（重新输入可替换）" else "还没有填")
            },
            supportingText = { Text(hint, style = MaterialTheme.typography.labelSmall) },
            trailingIcon = {
                if (configured) {
                    TextButton(onClick = onClear) { Text("清除") }
                }
            },
        )
        if (configured) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    PbIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(Space.xs))
                Text(
                    "已保存一个密钥，存在这台设备的 KeyStore 里",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorLine(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Space.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            PbIcons.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(Space.xs))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * 测试结果。
 *
 * 直接把工具回灌给模型的那段文字显示出来 —— 它本来就要写给「读的人」看
 * （见 `ToolResult` 的 KDoc），而且这正是模型真调用时看到的东西。
 * 中间再包一层「测试成功」的话，用户看到的是转述，不是证据。
 */
@Composable
private fun TestResultCard(message: String, failed: Boolean) {
    PbCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                if (failed) PbIcons.Warning else PbIcons.Check,
                contentDescription = null,
                tint =
                    if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Space.sm))
            Column {
                Text(
                    if (failed) "测试没通过" else "测试通过",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
