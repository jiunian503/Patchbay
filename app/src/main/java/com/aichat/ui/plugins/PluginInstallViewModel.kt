package com.aichat.ui.plugins

import android.content.ClipboardManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.core.data.InstallPreview
import com.aichat.core.data.InstallResult
import com.aichat.core.data.PluginRepository
import com.aichat.di.AppContainer
import com.aichat.domain.io.CappedRead
import com.aichat.domain.io.readCapped
import com.aichat.network.FetchError
import com.aichat.network.FetchResult
import com.aichat.plugin.manifest.ManifestCheck
import com.aichat.plugin.manifest.ManifestParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 内置示例。装了才能让用户看到「插件到底长什么样」。 */
data class BundledExample(
    val label: String,
    val assetPath: String,
    val note: String,
)

/**
 * 一份清单**最多**多少字符。
 *
 * 一个真实的清单是几 KB 量级（内置天气示例约 3 KB）。256 KB 已经宽出两个数量级，
 * 只用来挡住「选错文件」和「URL 指向了一个大文件」。
 *
 * 「从 URL」入口用的是**同一个**上限、同一个 [readCapped] ——
 * 两处各写一个数字的话，迟早有一处忘记跟着改。
 */
internal const val MAX_MANIFEST_CHARS = 256 * 1024

data class PluginInstallUiState(
    val text: String = "",

    /**
     * 这份清单是从哪来的，会原样写进 `plugin.source` 列。
     *
     * 每次文本变化都会跟着更新：从剪贴板粘进来之后又手改了两笔，
     * 那它就已经不是「从剪贴板来的」了，报 `clipboard` 是撒谎。
     */
    val source: String = PluginRepository.SOURCE_PASTE,

    /**
     * 实时校验结果。
     *
     * **边输边校验**是这个页面唯一值得花心思的地方：清单是 JSON，
     * 而 JSON 写错一个括号就整个解析不了。等到用户点「安装」才报错的话，
     * 他得在「改一点、点一下、看一段红字」之间来回好几轮。
     * 这里每次改动都重新解析，红字跟着光标走。
     */
    val check: ManifestCheck? = null,

    /**
     * 非 null 表示**停在了确认这一步**：这份清单装下去会扩大权限，
     * 等用户点确认才写库。
     *
     * ## 为什么确认必须发生在装之前
     *
     * 扩权提示原来跟着 [InstallResult] 一起返回，而界面拿到结果的
     * 第一件事是跳到插件详情页 —— 那段提示最多渲染一帧就被扔掉了。
     * 等于「插件这次多要了 shell 权限」这句话写进了一份用户永远读不到的日志。
     *
     * 放到装之前，用户不但能看到，还能直接放弃 ——
     * 而不是「已经装上了，再去卸载」，后者要求他先意识到发生了什么。
     */
    val pendingUpgrade: InstallPreview? = null,

    val result: InstallResult? = null,
    val installing: Boolean = false,

    /**
     * 正在从 URL 拉取。
     *
     * 和 [installing] 分开：一个是「去网上取文本」，一个是「往库里写」。
     * 用户在这两段时间里能做的事不一样（前者可以改个网址重来，后者只能等），
     * 合成一个 `busy` 会让界面没法区分。
     */
    val fetching: Boolean = false,

    /**
     * 从示例 / 剪贴板 / 文件读文本时失败的原因（打包漏了、剪贴板是空的、
     * 文件太大之类）。
     *
     * 读**成功**时不留任何提示：文本已经出现在下面的输入框里了，
     * 那本身就是回执。再挂一句「已读入」是噪音。
     */
    val loadError: String? = null,
) {
    /** 清单本身是好的，可以装（或可以确认）。 */
    val canInstall: Boolean get() = check?.isUsable == true && !installing && !fetching

    /**
     * 主按钮写什么。
     *
     * 只有停在确认态时才会变成「确认扩权并更新」。
     *
     * **刻意不在用户点之前就把「安装」改成「更新」**：那需要在每次
     * 按键时去查一次库里有没有同 id 的插件，而按键是同步的、查库是挂起的 ——
     * 要么加个缓存（会过期），要么让按钮上的字跟着一个竞态走。
     * 为了一个措辞，在「用户点一下插件就获得新权限」这条路上
     * 引入一个竞态，不划算。真正危险的那种情况（扩权）本来就
     * 一定会停下来问。
     */
    val primaryLabel: String get() = when {
        installing -> "处理中…"
        pendingUpgrade != null -> "确认扩权并更新"
        else -> "安装"
    }
}

/**
 * 安装插件。
 *
 * ## 五条入口，一个核心
 *
 * 手输、内置示例、剪贴板、文件、网址 —— 它们**共用同一个核心**：
 * 拿到一段清单文本 → 校验 → 落库。所以这一版把后三条也接上时，
 * 每个入口只做一件事：**把文本交给 [setText]，并老实报出自己的来源**。
 *
 * 反过来，先做文件选择器再想校验的话，会让人以为「安装」的难点在选文件 ——
 * 而真正的难点在**清单校验和权限确认**，那些在这里。
 *
 * ## 从外部读进来的清单是**不可信输入**
 *
 * 剪贴板、文件和网址都可能是任何东西。所以三条防线一条都不能省：
 * 严格模式的 [ManifestParser]（拼错字段名会被当成错误而不是默认值）、
 * 安装页上那份逐条列出的权限清单、以及装之前对**权限扩张**的确认。
 * 这里多做不了什么，也不该做 —— 加一层「看起来像不像清单」的启发式检查，
 * 只会让合法的清单在某些情况下装不上，而不合法的清单照样能过。
 */
class PluginInstallViewModel(
    private val container: AppContainer,
) : ViewModel() {

    // 平台服务（assets / contentResolver / 剪贴板）一律走 container.appContext，
    // 不自己接一个上下文参数。lint 的 StaticFieldLeak 分不清传进来的是不是
    // applicationContext，而 ViewModel 又活得比界面久 —— 干脆不给它这个机会。

    private val _state = MutableStateFlow(PluginInstallUiState())
    val state: StateFlow<PluginInstallUiState> = _state.asStateFlow()

    /**
     * 用户手动改动文本框。
     *
     * 来源一律回到 [PluginRepository.SOURCE_PASTE]：从剪贴板粘进来之后
     * 又手改了两笔，它就已经不是「从剪贴板来的」了。
     */
    fun onTextChange(text: String) = setText(text, PluginRepository.SOURCE_PASTE)

    /**
     * 所有入口的唯一收口。
     *
     * 三件事必须一起做，所以放在一个方法里而不是让每个入口自己 copy：
     * 换文本、**作废上一次的结果和待确认态**、重新校验。
     *
     * 中间那件最容易漏，而且漏了是**静默**的：用户改完清单再点「确认扩权并更新」，
     * 确认的是旧的那份权限列表、装进去的是新的 —— 恰好是这套机制要防的事。
     */
    private fun setText(text: String, source: String) {
        _state.update {
            it.copy(
                text = text,
                source = source,
                check = if (text.isBlank()) null else ManifestParser.parse(text),
                // 文本一换，上一次的安装结果就不再对应当前内容了。
                // 留着它会让用户以为「刚装的就是现在这个」
                result = null,
                // 同理，待确认的扩权提示也作废了
                pendingUpgrade = null,
                loadError = null,
            )
        }
    }

    fun loadExample(example: BundledExample) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    container.appContext.assets.open(example.assetPath).bufferedReader().use { it.readText() }
                }.getOrNull()
            }
            if (text == null) {
                fail("读不到内置示例 ${example.assetPath}，可能是打包时漏了。")
                return@launch
            }
            setText(text, PluginRepository.SOURCE_BUNDLED)
        }
    }

    /**
     * 从剪贴板一键读入。
     *
     * 用户点这个按钮就说明他刚复制了一份清单 —— 让他再手动长按粘贴一次
     * 是纯粹的浪费。读剪贴板在这里也是安全的：**用户主动点的**，
     * 不是后台偷看。
     */
    fun loadFromClipboard() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { readClipboard() }
            if (text.isNullOrBlank()) {
                fail("剪贴板里没有文字。先复制一份 manifest.json 的内容再回来。")
                return@launch
            }
            setText(text, PluginRepository.SOURCE_CLIPBOARD)
        }
    }

    /**
     * 从文件读入。
     *
     * [uri] 来自 `ActivityResultContracts.OpenDocument`，是 SAF 给的
     * content URI —— 不要当路径用，也不要假设能直接 `File()` 打开。
     */
    fun loadFromFile(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    container.appContext.contentResolver.openInputStream(uri)
                        ?.use { readCapped(it, MAX_MANIFEST_CHARS) }
                }.getOrNull()
            }
            when (result) {
                null -> fail("读不到这个文件，可能它已经被移走或删掉了。")
                is CappedRead.TooLarge -> fail(
                    "这个文件超过了 ${MAX_MANIFEST_CHARS / 1024} KB，不像是插件清单。" +
                        "检查一下是不是选错了文件。"
                )
                is CappedRead.Ok ->
                    if (result.text.isBlank()) {
                        fail("这个文件是空的。")
                    } else {
                        setText(result.text, PluginRepository.SOURCE_FILE)
                    }
            }
        }
    }

    /**
     * 从 URL 拉一份清单。
     *
     * ## 这个入口为什么不额外问一句
     *
     * 「从剪贴板」和「从文件」也没问 —— 因为它们都只是**取文本**。
     * 真正需要用户判断的东西（这份清单要什么权限、比上一版多要了什么）
     * 在下面的实时校验和扩权确认里，那里才是决策点。
     * 在这里再加一层「确定要从这个网址下载吗」，只会稀释真正的那次确认。
     *
     * ## 协议上的把关在 `RemoteTextFetcher` 里
     *
     * 这是四条入口里唯一**没有白名单兜底**的一条：白名单是插件自己的权限，
     * 而装之前那个插件还不存在。所以「只认 http/https」「https 不接受被
     * 降级成明文」这两条由取数层自己负责，理由见它的 KDoc。
     */
    fun loadFromUrl(rawUrl: String) {
        viewModelScope.launch {
            _state.update { it.copy(fetching = true, loadError = null) }
            val result = container.manifestFetcher.fetch(rawUrl)
            _state.update { it.copy(fetching = false) }

            when (result) {
                is FetchResult.Failed -> fail(describe(result.error))
                is FetchResult.Ok ->
                    if (result.text.isBlank()) {
                        fail("${result.finalUrl.host} 返回了空内容。")
                    } else {
                        setText(result.text, PluginRepository.SOURCE_URL)
                    }
            }
        }
    }

    /**
     * 把抓取失败翻成用户能看懂的一句话。
     *
     * ## 为什么不用一句「拉取失败」了事
     *
     * 用户此刻需要知道**下一步改什么**：网址写错了？对方没有这个文件？
     * 还是自己网络断了？这三件事的应对完全不同。
     *
     * 写成 `when` 表达式而不是带 `else` 兜底 —— [FetchError] 是 sealed，
     * 漏掉一种会编译不过。将来加了新的失败类型，也不会有人忘记写文案。
     */
    private fun describe(error: FetchError): String = when (error) {
        FetchError.BadUrl ->
            "这不像一个网址。填完整的地址，例如 https://example.com/manifest.json"
        FetchError.UnsupportedScheme ->
            "只支持 http:// 和 https:// 开头的地址。"
        FetchError.BadRedirect ->
            "对方把请求转到了一个无法识别的地址。"
        FetchError.InsecureDowngrade ->
            "这个地址把请求转成了明文 http，已经中止。" +
                "换成 https 的地址；或者你确实想用明文，就直接填 http:// 开头的原始地址。"
        FetchError.TooManyRedirects ->
            "跳转次数太多，可能是一个循环。"
        FetchError.TooLarge ->
            "返回的内容超过了 ${MAX_MANIFEST_CHARS / 1024} KB，不像是插件清单。"
        is FetchError.HttpStatus ->
            "服务器返回 HTTP ${error.code}。检查一下网址是否写对了。"
        is FetchError.Network ->
            "连不上这个地址。${error.detail}"
    }

    private fun fail(message: String) {
        _state.update { it.copy(loadError = message) }
    }

    /**
     * 读剪贴板里的文字。
     *
     * `coerceToText` 会把 Uri / Intent 这类条目也转成字符串 ——
     * 复制了一张图片时它会返回一个 `content://…` 地址。那种内容当然解析不了，
     * 但下面那份实时校验会把「这不是 JSON」直接摆在用户眼前，
     * 比在这里猜「用户到底想干什么」要诚实。
     */
    private fun readClipboard(): String? {
        val manager = container.appContext.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(container.appContext)?.toString()
    }

    /**
     * 用户点了主按钮。
     *
     * 会先问一句「这份清单装下去会发生什么」，只有确认**不需要**额外同意时
     * 才真的写库；需要的话就停在 [PluginInstallUiState.pendingUpgrade] 上等
     * [confirmUpgrade]。
     */
    fun install() {
        val text = _state.value.text
        if (!_state.value.canInstall) return

        viewModelScope.launch {
            _state.update { it.copy(installing = true) }

            // 每次都重新问，不信界面上缓存的那份 —— 从上次问到现在，
            // 用户可能改过文本框（那会清掉 pendingUpgrade），也可能
            // 这个插件在别处被卸载了
            val preview = container.plugins.preview(text)
            if (preview.needsConfirmation) {
                _state.update { it.copy(installing = false, pendingUpgrade = preview) }
                return@launch
            }
            doInstall(text)
        }
    }

    /**
     * 用户在扩权提示上点了「确认扩权并更新」。到这一步才真的写库。
     *
     * 没有待确认的扩权时**什么都不做** —— 这个方法的名字是一句承诺
     * 「用户已经看过那份扩张清单并且同意了」。没有清单可看的时候
     * 它就没有意义，此时唯一正确的行为是回到 [install]。
     *
     * 顺带挡住了「扩权确认被绕过」这条路：将来多加一个调用点
     * （比如从 URL 装），忘了先 preview 也不会变成静默扩权。
     */
    fun confirmUpgrade() {
        if (_state.value.pendingUpgrade == null) return
        val text = _state.value.text
        if (!_state.value.canInstall) return

        viewModelScope.launch {
            _state.update { it.copy(installing = true, pendingUpgrade = null) }
            doInstall(text)
        }
    }

    /** 用户在扩权提示上点了「取消」。什么都不写，停在当前这一页。 */
    fun cancelUpgrade() {
        _state.update { it.copy(pendingUpgrade = null) }
    }

    /**
     * 真正写库。
     *
     * 成功之后立刻重新装配 —— 用户下一步多半就是回对话页试试，
     * 而那时候注册表必须已经包含新工具了。
     */
    private suspend fun doInstall(text: String) {
        val result = container.plugins.install(text, _state.value.source)
        if (result.ok) container.tools.refresh()
        _state.update { it.copy(result = result, installing = false) }
    }

    /**
     * 取走安装结果，并把表单恢复成干净的一页。
     *
     * ## 为什么必须是「取走」而不是「读一下」
     *
     * `result` 是一次性**事件**，不是**状态**：它表示「刚刚装好了」，
     * 而不是「现在是装好的」。界面靠它触发一次跳转，触发完就必须清掉 ——
     * 否则只要这个 ViewModel 还活着（进程内、或者返回栈里），
     * 下次组合到安装页时 `LaunchedEffect` 会拿同一个值再触发一次，
     * 用户刚点「安装插件」就被弹到上次那个插件的详情页。
     *
     * 顺带清空表单：装完之后再进来，用户多半是要装**另一个**插件，
     * 留着一份已经装过的 JSON 只会让人以为「上次没装成功」。
     */
    fun consumeResult() {
        _state.value = PluginInstallUiState()
    }

    companion object {
        /**
         * 随 APK 打包的示例清单。
         *
         * 路径在 `app/src/main/assets/plugins/` 下。
         * `BundledManifestsTest` 会把每一个都过一遍 `ManifestParser` ——
         * 「随包发的示例装不上」是最尴尬的一类 bug。
         */
        val EXAMPLES = listOf(
            BundledExample(
                label = "天气查询（声明式）",
                assetPath = "plugins/weather.json",
                note = "零代码：只有一份 HTTP 声明。装了之后问「上海今天多少度」。",
            ),
        )
    }
}
