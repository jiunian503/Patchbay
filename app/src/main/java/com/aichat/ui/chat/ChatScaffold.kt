package com.aichat.ui.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.di.AppContainer
import com.aichat.ui.conversations.ConversationDrawer
import com.aichat.ui.conversations.ConversationListViewModel
import com.aichat.ui.conversations.ExportRequest
import kotlinx.coroutines.launch

/**
 * 对话页 + 会话侧边栏。
 *
 * ## 这是 App 的首页
 *
 * 打开 App 直接落在会话里（起始目的地由 `MainNavigation` 决定：
 * 上次退出的那个会话），左侧抽屉用来换会话。原来的「会话列表页」
 * 已经不存在了 —— 它的内容变成了抽屉。
 *
 * 为什么这么改：**「换会话」和「接着聊」是同一个动作的两面**。
 * 用户在列表页和对话页之间来回跳的时候，想换会话的那一刻人已经在对话里了；
 * 而两级结构逼他先退出去。
 *
 * ## 抽屉为什么放在这一层，而不是包住整个 NavDisplay
 *
 * 包住整个 NavDisplay 的话，设置页、插件页、搜索页上也都能从左边缘拉出会话抽屉 ——
 * 那三个页面和「我在哪个会话里」没有关系，拉出来只会让人困惑。
 * 放在这里，抽屉就只属于对话页。
 *
 * 反过来，搜索页和设置页是**压在对话页上面**的（backStack 里在它之上），
 * 所以它们出现时抽屉自然不可达。
 *
 * ## 导出为什么落在这里
 *
 * 导出入口在抽屉的会话菜单里，而抽屉挂在 [ChatScaffold] 上。SAF 的
 * `ACTION_CREATE_DOCUMENT` 需要一个 Activity 结果回调，所以这段胶水
 * 只能待在一个 Composable 里 —— 放在这里正好和它的触发点在一起。
 *
 * 导出内容本身由 [ConversationListViewModel] 拼好（见 `ConversationExport.kt`），
 * 这一层只负责「弹保存框、把字节写进去」。
 */
@Composable
fun ChatScaffold(
    container: AppContainer,
    conversationId: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onSwitchConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    highlightMessageId: Long? = null,
    highlightQuery: String? = null,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val listViewModel: ConversationListViewModel = viewModel { ConversationListViewModel(container) }
    val listState by listViewModel.state.collectAsStateWithLifecycle()
    val pendingExport by listViewModel.export.collectAsStateWithLifecycle()

    // 每次拉开抽屉重读一次列表。会话的 `updated_at` 只在发消息时变，
    // 而「刚聊完就拉开抽屉」正是最常见的那一刻 —— 不刷的话用户看到的是旧顺序，
    // 会以为刚才那条消息没发出去。
    //
    // 用 `drawerState.isOpen` 当 key（而不是在打开的那一刻手动调）：
    // 手势拉开和点汉堡是两条路径，挂在状态上两条都能覆盖。
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) listViewModel.refresh()
    }

    ExportToFile(
        request = pendingExport,
        onDone = listViewModel::consumeExport,
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                items = listState.items,
                loading = listState.loading,
                currentConversationId = conversationId,
                onOpenConversation = { id ->
                    scope.launch { drawerState.close() }
                    // 点的是当前这个就什么都不做。不拦的话会 pop 再 push
                    // 同一个 key —— 对话页被重建，滚动位置和输入框里的草稿全没了
                    if (id != conversationId) onSwitchConversation(id)
                },
                onNewConversation = {
                    scope.launch { drawerState.close() }
                    onNewConversation()
                },
                onDeleteConversation = { id ->
                    listViewModel.delete(id)
                    // 删掉的正是当前打开的这一个 → 换到一个新会话。
                    //
                    // 不换的话用户会停留在一个库里已经不存在的会话上，
                    // 而**再发一条消息会把它复活**（`ensureConversation` 会建行）——
                    // 于是「我明明删了」变成一条新会话凭空冒出来。
                    if (id == conversationId) onNewConversation()
                },
                onRenameConversation = listViewModel::rename,
                onTogglePinned = listViewModel::setPinned,
                onExportConversation = listViewModel::exportMarkdown,
                onOpenSearch = {
                    scope.launch { drawerState.close() }
                    onOpenSearch()
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
            )
        },
    ) {
        ChatScreen(
            container = container,
            conversationId = conversationId,
            showBack = showBack,
            onBack = onBack,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onOpenSettings = onOpenSettings,
            highlightMessageId = highlightMessageId,
            highlightQuery = highlightQuery,
        )
    }
}

/**
 * 把一次 [ExportRequest] 落到用户选的位置。
 *
 * ## 为什么需要 `pending` 这份副本
 *
 * SAF 的保存框是**异步**的：`launcher.launch(name)` 立刻返回，用户在系统
 * 文件选择器里点完之后才回调。而回调拿到的是 `Uri`，不是我们那份文本 ——
 * 所以要有一份跨过这段时间的副本。
 *
 * 不能靠 `remember(request)` 之类的办法在回调里现取：回调的时机不受
 * Compose 控制，那时 `request` 可能已经是 null 了（[onDone] 已经把状态清掉）。
 *
 * ## 用户点取消也要清状态
 *
 * 取消时 `uri` 是 null。这时候**必须**照样调 [onDone] —— 不清的话待办
 * 会一直挂着，下次点导出时界面先看到旧值再看到新值，会连弹两次保存框。
 */
@Composable
private fun ExportToFile(request: ExportRequest?, onDone: () -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<ExportRequest?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val job = pending
        pending = null
        onDone()
        if (result.resultCode != Activity.RESULT_OK || job == null) return@rememberLauncherForActivityResult
        val uri = result.data?.data
        if (uri == null) return@rememberLauncherForActivityResult

        // 写失败要说出来。静默吞掉的话用户会以为文件存好了，
        // 而去文件管理器里找到的是一份 0 字节的空文件
        val failure = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(job.markdown.toByteArray(Charsets.UTF_8))
            } ?: error("打不开目标文件")
        }.exceptionOrNull()
        // 失败要留一条日志：Toast 只活 3 秒，而且会被别的提示顶掉，
        // 用户报「导出没反应」时这是唯一的线索
        if (failure != null) Log.w(TAG, "导出失败 uri=$uri", failure)

        Toast.makeText(
            context,
            if (failure == null) "已导出" else "导出失败：${failure.message ?: "未知原因"}",
            Toast.LENGTH_LONG,
        ).show()
    }

    LaunchedEffect(request) {
        val job = request ?: return@LaunchedEffect
        pending = job
        launcher.launch(createDocumentIntent(job.fileName))
    }
}

/**
 * 造一个 `ACTION_CREATE_DOCUMENT` 请求。
 *
 * ## 为什么不用 `ActivityResultContracts.CreateDocument`
 *
 * 它生成的 Intent **不带任何 flag**。在真机（Pixel / 主流 ROM）上这不影响 ——
 * 系统会把写权限附在返回的 Intent 上自动授权。但在某些模拟器 ROM 上
 * （实测 MuMu 的 Android 13 镜像），返回的 URI 拿不到写授权，写的时候被
 * `ActivityManagerService` 拒掉：
 *
 * ```
 * SecurityException: Permission Denial: writing
 * com.android.providers.downloads.DownloadStorageProvider uri content://... from
 * uid=... requires android.permission.MANAGE_DOCUMENTS, or grantUriPermission()
 * ```
 *
 * 结果是一份 0 字节的文件 —— 比报错更糟，用户以为导出成功了。
 *
 * 显式写上 [Intent.FLAG_GRANT_WRITE_URI_PERMISSION] 就能绕过这个差异：
 * 它是「我要写这个 URI」的正式表达，系统据此授权，不依赖 ROM 的默认行为。
 * `FLAG_GRANT_READ_URI_PERMISSION` 一并写上 —— 覆盖同名文件时系统会先读它。
 *
 * `CATEGORY_OPENABLE` 也是必须的：不加的话某些 ROM 的文件选择器会给一个
 * 只能查元数据、打不开流的 URI。
 *
 * ## 为什么要写 `EXTRA_INITIAL_URI`
 *
 * 不写的话选择器打开在「上次用过的目录」—— 那可能是任何一个位置，用户
 * 得自己找一圈。实测 MuMu 上它落在了 `Screenshots/`，于是「导出到下载」
 * 变成了「导出到截图」。
 *
 * 指到主存储的 `Download` 上，行为才是「导出」这个词承诺的那个意思。
 * 某些 ROM 会忽略这个 extra —— 那只是回到旧行为，不会出错。
 */
private fun createDocumentIntent(fileName: String): Intent =
    Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("text/markdown")
        .putExtra(Intent.EXTRA_TITLE, fileName)
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOADS_URI)
        .addFlags(
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

private const val TAG = "PatchbayExport"

/** 主存储的 `Download` 目录。`primary:` 后是相对路径，`:` 要转义成 `%3A`。 */
private val DOWNLOADS_URI: Uri =
    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
