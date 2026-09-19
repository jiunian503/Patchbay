package com.aichat.ui.plugins

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.di.AppContainer
import com.aichat.plugin.manifest.ManifestProblem
import com.aichat.plugin.manifest.describe
import com.aichat.plugin.manifest.displayName
import com.aichat.plugin.manifest.isHighRisk
import androidx.compose.ui.platform.LocalContext

/**
 * 安装插件。
 *
 * ## 五条入口，一个核心
 *
 * 手输 / 内置示例 / 从剪贴板 / 从文件 / 从网址 —— 五条路都只做同一件事：
 * **拿到一段清单文本**。校验、权限展示、扩权确认全在下面那套共用的流程里，
 * 所以从剪贴板粘进来的一份和手打的一份受到完全一样的审查。
 *
 * 「从网址」是唯一**没有白名单兜底**的一条（白名单是插件自己的权限，
 * 而装之前那个插件还不存在），所以协议上的把关在取数层自己手里 ——
 * 只认 http/https、`https://` 不接受被 302 降级成明文。
 * 见 `RemoteTextFetcher` 的 KDoc。
 *
 * ## 校验是边输边做的
 *
 * 清单是 JSON，写错一个括号就整个解析不了。等到点「安装」才报错的话，
 * 用户得在「改一点、点一下、看一段红字」之间来回好几轮。
 * 这里每次改动都重新解析，问题列表跟着输入实时更新。
 *
 * ## 权限区为什么要单独一块
 *
 * 因为安装是用户**唯一一次**真正做决定的机会。装完之后插件就在后台
 * 按声明的方式发请求了，用户不会再看到那份权限清单。所以这里把它
 * 单独拎出来、高危的标红，而不是折叠在「详情」里。
 *
 * ## 升级扩权为什么必须在装**之前**确认
 *
 * 原来那份「这次更新扩大了权限」的提示挂在装完之后的结果块上，
 * 而装成功会立刻 `onInstalled` 跳走 —— 它最多渲染一帧。
 * 用户看到的是「点了一下安装，页面自己跳走了」，权限清单一眼都没看到。
 *
 * 所以确认挪到了装之前（[UpgradeWarning] + `confirmUpgrade`）：
 * 点「安装」时先问一句会发生什么，需要确认就停下来，等他点
 * 「确认扩权并更新」才写库。用户不但能看到，还能直接放弃。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginInstallScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onInstalled: (String) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: PluginInstallViewModel = viewModel { PluginInstallViewModel(container, context) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 装成功后跳详情页：用户下一步多半是去填配置，而不是回列表。
    //
    // **必须先消费结果、再导航，顺序不能反。**
    //
    // `onInstalled` 会让本组合离开（安装页被从返回栈里去掉），协程在这里
    // 被取消 —— 如果把 `consumeResult()` 放在 `onInstalled` 之后，它永远
    // 执行不到，结果就留在了 ViewModel 里。下次再进这个页面时，
    // `LaunchedEffect` 的 key 一上来就是非 null，于是用户刚点「安装插件」
    // 就被直接弹到上次那个插件的详情页。
    //
    // 实测踩到过：`tap 安装插件` → 出现的是详情页，全程没有任何报错。
    // 当时的第一反应是「按钮绑错了回调」，实际是这里漏了一次消费。
    //
    // 注意 `result` 现在**只**用来触发这次跳转，页面不再渲染它：
    // 成功会立刻跳走（渲染了也看不见），失败又走不到这里
    // （`canInstall` 已经保证了清单能解析，`install` 就不会失败）。
    LaunchedEffect(state.result?.pluginId) {
        val pluginId = state.result?.pluginId ?: return@LaunchedEffect
        viewModel.consumeResult()
        onInstalled(pluginId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("安装插件") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExampleRow(onPick = { viewModel.loadExample(it) })

            // 另外两条「把清单弄进来」的路。
            //
            // 它们和「填入示例」一样，只负责**拿到文本**；校验、权限展示、
            // 扩权确认全都在下面那套共用的流程里 —— 从剪贴板来的清单
            // 一样要过严格解析、一样要过白名单检查。
            //
            // 文件用 SAF（`OpenDocument`）而不是 `File` 路径：Android 10 起
            // 拿不到别人的绝对路径，而且 SAF 给的是 content URI，
            // 用户从哪里选（下载、网盘、聊天记录）都行。
            val pickFile = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri -> uri?.let(viewModel::loadFromFile) }

            var urlDialog by remember { mutableStateOf(false) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::loadFromClipboard,
                    modifier = Modifier.weight(1f),
                    enabled = !state.fetching,
                ) {
                    Text("从剪贴板")
                }
                OutlinedButton(
                    onClick = {
                        // MIME 过滤只能给建议，各家文件管理器对 .json 的
                        // 归类并不一致（有的是 application/json、有的干脆
                        // 是 application/octet-stream）。所以放宽到
                        // 「JSON + 文本 + 任意」，让用户至少能选到自己的文件 ——
                        // 选错的话，下面 MAX_MANIFEST_CHARS 那道闸和校验会兜住
                        pickFile.launch(arrayOf("application/json", "text/*", "*/*"))
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !state.fetching,
                ) {
                    Text("从文件…")
                }
                OutlinedButton(
                    onClick = { urlDialog = true },
                    modifier = Modifier.weight(1f),
                    enabled = !state.fetching,
                ) {
                    // 拉取期间**就地把按钮文字换掉**，而不是在别处再挂一行
                    // 「正在拉取…」—— 用户刚点的就是它，视线本来就在这里
                    Text(if (state.fetching) "拉取中…" else "从网址…")
                }
            }

            if (urlDialog) {
                ManifestUrlDialog(
                    onDismiss = { urlDialog = false },
                    onConfirm = { url ->
                        urlDialog = false
                        viewModel.loadFromUrl(url)
                    },
                )
            }

            // 四条入口**共用**一处报错。
            //
            // 不能挂在示例那张卡里：用户点「从文件」选了个 400 KB 的东西，
            // 报错却出现在「试试内置示例」下面，他会以为自己点错了按钮。
            state.loadError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::onTextChange,
                label = { Text("插件清单（JSON）") },
                placeholder = { Text("把 manifest.json 的内容粘到这里") },
                modifier = Modifier.fillMaxWidth().height(220.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )

            val check = state.check
            if (check != null) {
                ProblemList(errors = check.errors, warnings = check.warnings)
                check.manifest?.let { manifest ->
                    ManifestSummary(
                        name = manifest.name,
                        version = manifest.version,
                        runtime = manifest.runtime.displayName,
                        toolCount = manifest.tools.size,
                        permissions = manifest.permissions.describe(),
                        highRisk = manifest.permissions.isHighRisk,
                    )
                }
            }

            // 扩权确认**在装之前**。
            //
            // 这段提示原来挂在「装完之后」的结果块上，而装成功会立刻触发
            // `onInstalled` 跳走 —— 它最多渲染一帧。用户看到的是
            // 「点了一下安装，页面自己跳走了」，那份权限清单一眼都没看到。
            // 等于把「插件这次多要了 shell 权限」写进了一份没人读得到的日志。
            state.pendingUpgrade?.let { pending ->
                UpgradeWarning(changes = pending.permissionChanges)
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 只有停在确认态时才出现「取消」。取消不写任何东西，
                // 用户可以直接改清单、也可以直接离开
                if (state.pendingUpgrade != null) {
                    TextButton(onClick = viewModel::cancelUpgrade, enabled = !state.installing) {
                        Text("取消")
                    }
                }
                Button(
                    onClick = if (state.pendingUpgrade != null) {
                        viewModel::confirmUpgrade
                    } else {
                        viewModel::install
                    },
                    enabled = state.canInstall,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(state.primaryLabel)
                }
            }

            Text(
                text = "插件只会按清单里声明的权限工作：网络请求限制在白名单主机上，" +
                    "敏感配置项加密存在这台设备上，不会上传。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExampleRow(onPick: (BundledExample) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("试试内置示例", style = MaterialTheme.typography.titleSmall)
            PluginInstallViewModel.EXAMPLES.forEach { example ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(example.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = example.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onPick(example) }) { Text("填入") }
                }
            }
        }
    }
}

@Composable
private fun ProblemList(errors: List<ManifestProblem>, warnings: List<ManifestProblem>) {
    if (errors.isEmpty() && warnings.isEmpty()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                text = "清单检查通过，可以安装",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        }
        return
    }

    if (errors.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    text = "${errors.size} 处错误，无法安装",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                errors.forEach { problem ->
                    Text(
                        // 路径要显示出来：它直接对应清单原文里的位置，
                        // 用户能拿它去对照自己写的那份 JSON
                        text = "${problem.path}\n${problem.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    if (warnings.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    text = "${warnings.size} 条提示（不影响安装）",
                    style = MaterialTheme.typography.titleSmall,
                )
                warnings.forEach { problem ->
                    Text(
                        text = "${problem.path}\n${problem.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * 权限扩张的确认块。
 *
 * ## 为什么用 `errorContainer` 而不是普通的灰底
 *
 * 这是整个安装流程里**唯一**需要用户真正读懂再决定的一步。
 * 装完之后插件就在后台按声明的方式工作了，不会再有人问他第二次 ——
 * 也就是说，这一次点击是全部。灰底会被当成「又一条说明」划过去。
 *
 * ## 为什么不画成一个 AlertDialog
 *
 * 因为用户需要**边看边对照**上面那份权限清单。弹框会把清单盖住，
 * 而「原来要什么、现在多要什么」正是他判断的依据。
 */
@Composable
private fun UpgradeWarning(changes: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = "这次更新会扩大权限",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "插件可以借一次「修了个 bug」的版本更新多要一些能力。" +
                    "在你确认之前，它不会被写进设备。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(6.dp))
            changes.forEach {
                Text(
                    text = "· $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun ManifestSummary(
    name: String,
    version: String,
    runtime: String,
    toolCount: Int,
    permissions: List<String>,
    highRisk: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (highRisk) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "$name  $version",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "$runtime · $toolCount 个工具",
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (highRisk) "这个插件申请的权限需要你留意：" else "它申请的权限：",
                style = MaterialTheme.typography.labelMedium,
            )
            permissions.forEach {
                Text(text = "· $it", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 输入清单网址的对话框。
 *
 * ## 这里用 `AlertDialog` 是合适的
 *
 * 和扩权确认那次不同：那一次用户需要**边看边对照**上面那份权限清单，
 * 弹框会把清单盖住，所以当时刻意避开了弹框。而这里要输入的东西没有
 * 别的参考物，弹框反而是最清楚的形式。
 *
 * ## 那句小字不是装饰
 *
 * 「下载回来的内容和从剪贴板粘进来的一样」要传达的是**下载不等于信任**：
 * 用户很容易觉得「从网上下载的」比「自己粘的」更可靠，而实际上两者
 * 受到的审查完全一样（同一套严格解析、同一份权限清单、同一次扩权确认）。
 * 不说的话，他会以为自己省掉了一道检查。
 */
@Composable
private fun ManifestUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从网址安装") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("清单地址") },
                    placeholder = { Text("https://example.com/manifest.json") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "下载回来的内容和从剪贴板粘进来的一样，" +
                        "装之前照样会给你看它要什么权限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text("拉取")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
