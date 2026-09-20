package com.aichat.plugin.workspace

import com.aichat.plugin.manifest.FilesystemScope
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * 插件工作区的根目录。**所有插件的工作区都在它下面，一个插件一个子目录。**
 *
 * ## 它和「插件自带的源码」是两回事
 *
 * 源码走 `PluginManifest.files`，整份在内存里传给沙箱，沙箱**不碰文件系统**
 * 就能读到（见 `ScriptRequest.files`）。这里管的是**运行时**产物：
 * 插件自己写下来的东西，跨调用留下来。
 *
 * 分开的理由是权限：「读随包的源码」不需要授权，它本来就是插件的一部分；
 * 「读写一个能留东西的目录」需要授权，因为它会累积、会占用户的存储。
 *
 * ## 目录名
 *
 * `<filesDir>/plugin-workspaces/<pluginId>/`。用 `filesDir` 而不是
 * `cacheDir` —— 后者的语义是「系统随时可以清」，而工作区的语义是
 * 「插件存下来的东西」。用 `cacheDir` 的话，插件刚写完的缓存可能在下一次
 * 调用前就被系统清掉了，而插件没有任何办法知道。
 *
 * 卸载时由 [delete] 清掉（见 `PluginRepository.uninstall`）。
 */
class PluginWorkspaces(private val base: File) {

    /**
     * 打开某个插件的工作区。构造出来的对象就绑定了「哪个插件」和「什么权限」——
     * 和 `NetworkGuard` 同一个形状：**策略在构造时确定，调用点不再重复传**。
     *
     * ## 它会把目录建出来
     *
     * 这不是顺手做的，是为了消掉一个会让插件作者写错代码的状态：
     * 如果工作区要等第一次 `write` 才存在，那么
     *
     * ```js
     * if (!host.fs.exists(".")) { host.fs.write("init.json", "{}"); }
     * ```
     *
     * 这种「第一次进来时初始化一下」的写法就会**每次都重新初始化** ——
     * 而且是在一个全新的工作区上，看不出任何异常。规则简单到没有例外
     * 才是对的：**有权限，工作区就存在。**
     *
     * 声明了 [FilesystemScope.None] 的插件**不会**被建目录，而且任何一次调用
     * 都会被拒。这不是多余的防御：调用点本该在 `None` 时干脆不给插件
     * `host.fs`，这里再挡一层，是为了让「权限」这件事只有一个执行者。
     */
    fun open(pluginId: String, scope: FilesystemScope): PluginWorkspace {
        val dir = dirOf(pluginId)
        // 建不出来也不在这里报 —— 真正的操作失败时会带上下文抛出来，
        // 而这里抛只会让调用方拿到一句「建目录失败」，反而更难查
        if (scope != FilesystemScope.None) dir.mkdirs()
        return PluginWorkspace(dir = dir, scope = scope)
    }

    /**
     * **宿主**（也就是用户界面）用的全权视图。
     *
     * ## 为什么它不受插件声明的读写方向约束
     *
     * [open] 拿到的权限来自插件清单里的 `permissions.filesystem`，那是
     * **插件对自己**的约束：声明 `read` 的插件不许写。但宿主不是插件 ——
     * 用户往自己的工作区里放一个文件，插件依然只是「读」它，一点都没越界。
     * 拿插件的声明去限制用户，会得出「只读插件的工作区不能导入文件」这种
     * 结论，而那显然是错的。
     *
     * 所以这里一律以 [FilesystemScope.ReadWrite] 打开。**用户是主人。**
     *
     * ## 为什么它不建目录
     *
     * [open] 建目录是因为插件作者会写 `if (!host.fs.exists("."))` 这种代码，
     * 需要「有权限就一定有目录」这条硬保证。宿主这边没有这个问题：
     * 用户可能只是**看一眼**工作区里有什么，看一眼不该凭空造出一个目录来
     * （那会让一个从没写过东西的插件多出一个空目录，而且它不在卸载清理
     * 的触发路径上，只能等 [sweep] 对账 —— 而插件记录还在，sweep 不会碰它）。
     *
     * 真正要写的时候 [PluginWorkspace.writeBytes] 自己会 `mkdirs`。
     */
    fun adminOf(pluginId: String): PluginWorkspace =
        PluginWorkspace(dir = dirOf(pluginId), scope = FilesystemScope.ReadWrite)

    /**
     * 删掉这个插件的工作区。**卸载时调**，所以它不看权限 ——
     * 用户决定卸载了，插件声明过什么都得清干净。
     *
     * 返回「目录现在确实不存在了」。删失败（文件被占、权限问题）返回 `false`
     * 而不是抛异常：卸载流程不该因为一个删不掉的缓存文件而失败，
     * 剩下的残渣由 [sweep] 下次启动时收。
     */
    fun delete(pluginId: String): Boolean {
        val dir = dirOf(pluginId)
        if (!dir.exists()) return true
        return runCatching {
            dir.walkBottomUp().forEach { it.delete() }
            !dir.exists()
        }.getOrDefault(false)
    }

    /**
     * 把**没有对应插件记录**的工作区目录删掉。
     *
     * ## 为什么需要它
     *
     * 卸载是「先删数据库行、再删目录」两步。中间任何一步没走完（进程被杀、
     * 删目录失败），都会留下一个孤儿目录。它占着用户的存储，而且再也没人
     * 会去清它 —— 因为插件记录已经没了，从界面上根本看不到它。
     *
     * 所以启动时对一遍账。传入的 [known] 是当前数据库里所有插件的 id。
     */
    fun sweep(known: Set<String>): Int {
        val dirs = base.listFiles()?.filter { it.isDirectory } ?: return 0
        var removed = 0
        for (dir in dirs) {
            if (dir.name in known) continue
            val gone = runCatching {
                dir.walkBottomUp().forEach { it.delete() }
                !dir.exists()
            }.getOrDefault(false)
            if (gone) removed++
        }
        return removed
    }

    /**
     * 插件 id 直接当目录名用。
     *
     * ## 为什么敢直接用
     *
     * 因为 `ManifestParser.ID_PATTERN` 是 `^[a-z0-9]+([.-][a-z0-9]+)+$`：
     * 里面**不可能有 `/`**，也**不可能有 `..`**（`..` 需要第二个字符是字母数字，
     * 而它是点）。所以它天然是一个安全的单层路径段。
     *
     * 但这里仍然再校验一次 —— 这个函数是「拿外部字符串当路径」的唯一入口，
     * 而将来调用它的人未必都从清单解析那条路来。**校验要贴着危险操作，
     * 不要贴着某一个调用者。**
     */
    private fun dirOf(pluginId: String): File {
        require(PLUGIN_ID.matches(pluginId)) {
            "插件 id「$pluginId」不能当目录名用：只允许小写字母、数字、点和连字符，" +
                "且必须以字母数字开头结尾。"
        }
        return File(base, pluginId)
    }

    companion object {
        /**
         * 和 `ManifestParser.ID_PATTERN` **必须一致**。
         *
         * 两份是有意的重复：`ManifestParser` 那份管「这个清单合不合法」，
         * 这份管「这个字符串能不能当目录名」。它们今天恰好是同一个规则，
         * 但理由不同 —— 合并成一处会让改其中一条的人以为另一条也跟着变了。
         */
        private val PLUGIN_ID = Regex("""^[a-z0-9]+([.-][a-z0-9]+)+$""")

        /** 工作区在 `filesDir` 下的目录名。 */
        const val BASE_DIR_NAME = "plugin-workspaces"

        /**
         * 在 App 的 `filesDir` 下面开一个。
         *
         * 存在的理由只有一个：**这个路径要有唯一的算法**。它至少有两个
         * 调用点（沙箱进程要读写、主进程卸载时要删），两边各拼一次字符串
         * 的话，改目录名时改一处、漏一处 —— 而漏掉的后果是「卸载后
         * 工作区还在，而且再也没人清」，从界面上完全看不出来。
         */
        fun under(filesDir: File): PluginWorkspaces = PluginWorkspaces(File(filesDir, BASE_DIR_NAME))
    }
}

/**
 * **一个插件**的工作区。
 *
 * 路径一律**相对工作区根**，只能在自己的目录里活动 —— 这是清单里
 * `permissions.filesystem` 那句「限插件工作区」的实际含义。
 *
 * ## 四条上限，各挡一种「写坏」的方式
 *
 * | 上限 | 值 | 挡的是 |
 * |---|---|---|
 * | [MAX_FILE_BYTES] | 1 MB | 一次写一个巨大的文件 |
 * | [MAX_TOTAL_BYTES] | 8 MB | 很多次调用慢慢堆满用户的存储 |
 * | [MAX_ENTRIES] | 256 | 8 MB 摊成几十万个小文件（比一个大文件更伤文件系统） |
 * | [MAX_DEPTH] | 16 | 一次调用建出几千层目录 |
 *
 * 单文件上限取 1 MB 不是随手定的：`host.http` 的响应体上限也是 1 MB
 * （`SandboxEngine.MAX_BODY_BYTES`）。所以「插件最多能存下它一次能看到的东西」——
 * 一个能解释给作者听的界，而不是一个魔法数字。
 *
 * 前三条是**公开**的：详情页要拿它们显示「12 KB / 8 MB」这样的用量，
 * 导入文件时也要先自己判一次、好给出用户能看懂的话术。
 *
 * ## 为什么上限必须在**这一层**执行
 *
 * 沙箱每次调用都是一个新进程，所以**进程内的用量不会累积** —— 但工作区会。
 * 一个写循环跑一百次调用，每次都「没超内存、没超时」，最后把用户的存储占满。
 * 两条上限（时间、内存）拦不住这件事，只有磁盘配额能。
 *
 * ## 失败一律抛 [WorkspaceException]
 *
 * message 是**写给插件作者看的**：说清哪条规矩、为什么、下一步怎么办。
 * 它最终会变成 JS 里的 `Error`（沙箱把它包成 `{ok:false,error}`，
 * 由 prelude 抛出），所以措辞要能直接出现在插件作者的调试信息里。
 *
 * 宿主侧（[PluginWorkspaces.adminOf]）拿到的是同一个类型，但**调用方有责任
 * 把它翻成用户话术** —— 作者看的「请拆成多个文件再写」对用户是没有意义的。
 */
class PluginWorkspace internal constructor(
    private val dir: File,
    private val scope: FilesystemScope,
) {

    /** 读一个文本文件。文件不存在、是目录、超过 [MAX_FILE_BYTES] 都会抛。 */
    fun read(path: String): String {
        requireScope(needWrite = false)
        val file = resolve(path)

        if (!file.exists()) {
            throw WorkspaceException(
                "工作区里没有「$path」。用 host.fs.list() 看看现在有哪些文件 —— " +
                    "工作区是空的吗？它只装这个插件自己写下来的东西，不会自动有什么。",
            )
        }
        if (file.isDirectory) {
            throw WorkspaceException("「$path」是目录，不是文件。列目录用 host.fs.list()。")
        }

        val size = file.length()
        if (size > MAX_FILE_BYTES) {
            throw WorkspaceException(
                "「$path」有 ${size / 1024} KB，超过 ${MAX_FILE_BYTES / 1024} KB 的单文件上限，" +
                    "宿主没有读它。写的时候就该拆开存 —— 比如按时间或按 id 分片。",
            )
        }

        return try {
            file.readText(StandardCharsets.UTF_8)
        } catch (e: IOException) {
            throw WorkspaceException("读「$path」失败：${e.message ?: e::class.simpleName}。")
        }
    }

    /**
     * 写一个文本文件。父目录会自动建。
     *
     * 会覆盖同名文件 —— 这是「存缓存」最常用的动作，要求作者先删再写
     * 只会让每个人多写一行 `if`。
     */
    fun write(path: String, text: String) {
        writeBytes(path, text.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * 写一个文件，**按字节**。
     *
     * ## 为什么除了 [write] 还要有这个
     *
     * 因为用户的文件不是 UTF-8 字符串。从文件选择器导入一份中文 CSV 时，
     * 它很可能是 GBK 编码的 —— 先按 UTF-8 解码成 `String` 再编码写回，
     * 那串字节**已经损坏了**（非法字节变成 U+FFFD，而且长度会变），
     * 插件拿到手的就是一份读不懂的数据，而界面上什么都看不出来。
     *
     * 字节进出，工作区里就是用户选中那个文件的**原样**。
     *
     * 限额检查只有这一份实现（[write] 是它的薄包装）—— 两条路各写一遍的话，
     * 漏掉一条的后果是「有一条写入路径不受上限约束」，而那种洞从界面上
     * 完全看不出来。
     */
    fun writeBytes(path: String, bytes: ByteArray) {
        requireScope(needWrite = true)
        val file = resolve(path)

        if (bytes.size > MAX_FILE_BYTES) {
            throw WorkspaceException(
                "要写的内容有 ${bytes.size / 1024} KB，超过 ${MAX_FILE_BYTES / 1024} KB 的单文件上限。" +
                    "请拆成多个文件再写。",
            )
        }

        val existing = usage()
        val replaced = if (file.isFile) file.length() else 0L
        val total = existing.bytes - replaced + bytes.size
        if (total > MAX_TOTAL_BYTES) {
            throw WorkspaceException(
                "写完会占 ${total / 1024} KB，超过工作区 ${MAX_TOTAL_BYTES / 1024} KB 的上限" +
                    "（现在已占 ${existing.bytes / 1024} KB）。先删掉不再需要的文件再写 —— " +
                    "工作区是给你留东西用的，不是给你囤东西用的。",
            )
        }
        if (!file.isFile && existing.entries >= MAX_ENTRIES) {
            throw WorkspaceException(
                "工作区已经有 ${existing.entries} 个文件，到达 $MAX_ENTRIES 个的上限。" +
                    "先删掉不再需要的文件再写。",
            )
        }

        try {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        } catch (e: IOException) {
            throw WorkspaceException("写「$path」失败：${e.message ?: e::class.simpleName}。")
        }
    }

    /** 存在吗。目录也算存在。 */
    fun exists(path: String): Boolean {
        requireScope(needWrite = false)
        return resolve(path).exists()
    }

    /**
     * 列出一个目录下的名字（文件和子目录都在内，已排序）。
     *
     * 只给名字，不给大小、不给类型、不给修改时间 —— 那些是「文件管理器」
     * 的需求。插件要判断「是不是目录」，用 `host.fs.exists(p + "/")`
     * 这种写法是不可靠的，所以这里**顺带说明**：本 API 不提供区分手段，
     * 插件应该按自己写下的命名约定来组织工作区。
     *
     * （宿主要看大小和类型，用 [allFiles] —— 那一侧不需要跟插件协议兼容。）
     */
    fun list(path: String): List<String> {
        requireScope(needWrite = false)
        val target = resolve(path)

        if (!target.exists()) {
            throw WorkspaceException("工作区里没有「$path」这个目录。")
        }
        if (!target.isDirectory) {
            throw WorkspaceException("「$path」是文件，不是目录。列一个目录用 host.fs.list()。")
        }

        return target.list()?.sorted() ?: emptyList()
    }

    /**
     * 工作区里的**全部文件**，递归展开，按路径排序。
     *
     * 路径是**相对工作区根**的，分隔符一律 `/`（`invariantSeparatorsPath`）——
     * 插件在 JS 里看到的也是 `/`，两边必须一致，否则界面上显示
     * `cache\a.csv` 而插件要写 `cache/a.csv`，用户照着界面填就错了。
     *
     * ## 为什么递归，而不是只列顶层
     *
     * 插件完全可能把东西写在子目录里（`cache/`、按日期分的目录）。
     * 只列顶层的话，用户会看到一个目录名、看不到里面有什么，
     * 然后合理地以为「我导入的文件不见了」。
     *
     * ## 深度上限和 `resolve` 的是同一个
     *
     * `resolve` 保证写进来的路径不超过 [MAX_DEPTH] 层，所以这里不会漏掉
     * 任何文件。加这个上限是为了让遍历有个头 —— 它防的是遍历失控
     * （目录成环），而不是文件太多。
     */
    fun allFiles(): List<WorkspaceFile> {
        requireScope(needWrite = false)
        if (!dir.isDirectory) return emptyList()

        val root = dir.canonicalFile
        return root.walkTopDown()
            .maxDepth(MAX_DEPTH)
            .filter { it.isFile }
            .map { WorkspaceFile(path = it.relativeTo(root).invariantSeparatorsPath, bytes = it.length()) }
            .sortedBy { it.path }
            .toList()
    }

    /**
     * 删掉工作区里的一个**文件**。
     *
     * 返回「真的删掉了」。**本来就不存在也返回 `false`** —— 调用点（界面）
     * 要据此决定提示什么，而「删掉了一个不存在的东西」和「删成功了」
     * 是两件不同的事。
     *
     * 传进来的如果是个目录，直接返回 `false`，**不递归删** —— 界面只会
     * 把 [allFiles] 的结果给用户点，那里没有目录；真有目录混进来，
     * 静默递归删掉一整棵子树是个太重的后果。
     */
    fun remove(path: String): Boolean {
        requireScope(needWrite = true)
        val file = resolve(path)
        if (!file.isFile) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /**
     * 当前用量。每次写都要算一遍，所以它必须便宜 —— 条目有上限，走一遍是够快的。
     *
     * 深度上限和 [allFiles] 同一个理由（见那里的说明）。
     */
    fun usage(): WorkspaceUsage {
        if (!dir.isDirectory) return WorkspaceUsage(0, 0L)

        var entries = 0
        var bytes = 0L
        dir.walkTopDown().maxDepth(MAX_DEPTH).forEach {
            if (it.isFile) {
                entries++
                bytes += it.length()
            }
        }
        return WorkspaceUsage(entries, bytes)
    }

    // ------------------------------------------------------------------ 内部

    private fun requireScope(needWrite: Boolean) {
        when {
            scope == FilesystemScope.None -> throw WorkspaceException(
                "这个插件没有声明 filesystem 权限，所以拿不到工作区。" +
                    "这一条应该由宿主在注入前就挡住 —— 如果你看到了这句话，那是宿主的问题。",
            )

            needWrite && scope != FilesystemScope.ReadWrite -> throw WorkspaceException(
                "这个插件只声明了 filesystem: \"read\"（只读），不能写工作区。" +
                    "需要写的话，清单里改成 \"readwrite\" 再让用户确认一次。",
            )
        }
    }

    /**
     * 把插件给的相对路径解析成工作区内的绝对文件，**越界就抛**。
     *
     * ## 两道检查，缺一不可
     *
     * 1. **逐段拒 `..`** —— 给出的话能说清是哪里错了（「路径里不能出现 ..」），
     *    而下面那道检查只能说出「跑到外面去了」。
     * 2. **`canonicalFile` 之后比对前缀** —— 这才是真正的守卫。
     *    它挡的是第一道想不到的东西：符号链接（工作区里如果出现一个指向
     *    外部的链接，第一道检查看不出来）、以及任何平台特有的写法。
     *
     * 只做第一道是不行的 —— 它是一张**规则表**，而路径的写法不止这一种。
     * 只做第二道也可以，但报错会很难懂。**规则表负责说人话，前缀比对负责兜底。**
     *
     * 前缀比对必须带上分隔符：不加的话 `/a/plugin-workspaces/foo` 会被
     * `/a/plugin-workspaces/foo-bar` 前缀匹配上 —— 一个只差一个连字符的
     * 插件目录，看起来完全正常，实际能越权。
     */
    private fun resolve(path: String): File {
        if (path.isBlank()) {
            throw WorkspaceException(
                "路径不能为空。工作区根目录用 \".\"，比如 host.fs.list(\".\")。",
            )
        }
        if (path.contains('\u0000')) {
            throw WorkspaceException("路径里不能有 NUL 字符。")
        }
        if (path.startsWith("/")) {
            throw WorkspaceException(
                "路径「$path」是绝对路径。工作区里的路径一律**相对工作区根**，" +
                    "比如 \"cache/a.csv\"；根目录写成 \".\"。",
            )
        }

        val segments = path.split('/')
        if (segments.any { it == ".." }) {
            throw WorkspaceException(
                "路径「$path」里有 ..。工作区之外的任何东西都读不到 —— " +
                    "这是 filesystem 权限的边界，不是配置问题。",
            )
        }
        if (segments.size > MAX_DEPTH) {
            throw WorkspaceException(
                "路径「$path」有 ${segments.size} 层，超过 $MAX_DEPTH 层的上限。",
            )
        }

        val root = dir.canonicalFile
        val target = File(root, path).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw WorkspaceException(
                "路径「$path」解析后跑到了工作区外面（${target.path}）。" +
                    "工作区里的路径一律相对工作区根。",
            )
        }
        return target
    }

    companion object {
        /**
         * 单文件上限。**这个数字要和 `host.http` 的响应体上限一致**
         * （`SandboxEngine.MAX_BODY_BYTES`）—— 那样「插件最多能存下它
         * 一次能看到的东西」这句话才成立，作者能理解，而不是记住一个魔法数。
         */
        const val MAX_FILE_BYTES = 1 * 1024 * 1024

        /** 一个插件的工作区总共能用多少。 */
        const val MAX_TOTAL_BYTES = 8 * 1024 * 1024

        /** 文件数上限。 */
        const val MAX_ENTRIES = 256

        /**
         * 路径深度上限。**这个不公开** —— 界面上没有「还有几层」这回事，
         * 公开它只会让调用点多一个可以传错的参数。
         */
        private const val MAX_DEPTH = 16

        /**
         * 文件名长度上限，按**字符**数算。
         *
         * 取 60 是因为 ext4 的上限是 255 **字节**，而 UTF-8 一个字符最多
         * 4 字节 —— 60 × 4 = 240，留了一点余量。按字符数而不是字节数限制，
         * 是因为「这个名字有多长」对用户来说是几个字，不是几个字节。
         */
        private const val MAX_NAME_CHARS = 60

        /** 名字完全洗不出来时用什么。 */
        private const val FALLBACK_NAME = "file"

        /**
         * 把一个**外部来的**文件名洗成工作区里能用的名字。
         *
         * ## 为什么必须洗
         *
         * 名字来自 SAF（`OpenableColumns.DISPLAY_NAME`），也就是文件提供方
         * 说了算。它可能是 `Download/foo.csv`（带路径）、可能带控制字符、
         * 可能长到让 `createNewFile` 抛 `ENAMETOOLONG`（那个报错信息里
         * 只有一个 errno，指不到「文件名太长」这件事上）。
         *
         * 直接把它拼进工作区路径，最坏的情况不是报错，是**写到了别的地方** ——
         * 带 `/` 的名字会被 [resolve] 当成子目录，于是用户的文件悄悄跑进了
         * 一个他没听说过的目录。
         *
         * ## 洗的规则，以及为什么是这几条
         *
         * - 先取最后一段（`a/b.csv` → `b.csv`）。带路径的名字**截断**而不是
         *   拒绝：用户选的就是那个文件，没道理因为提供方多给了几个字就失败
         * - 去掉控制字符和残留的分隔符
         * - 去掉**前导的点**：`.` 和 `..` 是路径里的特殊名，而 `.foo` 在
         *   文件管理器里默认是隐藏文件，用户导入完会发现「什么都没看到」
         * - 超长就截断，但**尽量留住扩展名**（`foo.csv` 比 `foo` 有用，
         *   插件可能按扩展名判断格式）
         * - 实在洗不出东西（空串、全是点）就退回 [FALLBACK_NAME]
         *
         * 它**永远返回一个能用的名字**，不返回 null —— 因为调用点没有别的选择：
         * SAF 给什么就是什么，用户没法「改个名字再导入」。让调用点去处理
         * 「这个名字不能用」只会让每个调用点各写一遍兜底。
         */
        fun cleanName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = base
                .filterNot { it.isISOControl() || it == '/' || it == '\\' }
                .trim()
                .trimStart('.')
                .let { truncate(it, MAX_NAME_CHARS) }
                .trim()
            return cleaned.ifEmpty { FALLBACK_NAME }
        }

        /** 截断到 [max] 个字符，但尽量留住扩展名。 */
        private fun truncate(name: String, max: Int): String {
            if (name.length <= max) return name
            val dot = name.lastIndexOf('.')
            // 扩展名超过 10 个字符就不当扩展名看 —— 那多半是名字里本来就有个点，
            // 硬留会把主干挤掉
            if (dot > 0 && name.length - dot in 1..11) {
                return name.take(max - (name.length - dot)) + name.substring(dot)
            }
            return name.take(max)
        }
    }
}

/**
 * 工作区里的一个文件。
 *
 * [path] 是**相对工作区根**的路径，分隔符一律 `/` —— 和插件在 JS 里看到的
 * 写法一致，用户可以照着它填工具参数（比如 `csvstat` 的 `path`）。
 */
data class WorkspaceFile(val path: String, val bytes: Long)

/**
 * 工作区用量。
 *
 * 字段名不用 `size` / `count` 而用 `bytes` / `entries`，是为了让
 * 「这是字节数不是文件数」在调用点读得出来 —— 这两个数字长得太像，
 * 而把 12 当字节、把 12288 当文件数都能编译通过。
 */
data class WorkspaceUsage(val entries: Int, val bytes: Long)

/**
 * 工作区操作失败。
 *
 * 单独一个类型而不是 `IOException`，是因为**只有它的话是给插件作者看的**。
 * `IOException` 那种「磁盘满了」的原话是给宿主开发者看的，两者混在一起之后，
 * 沙箱就没法决定哪句能原样交给插件、哪句该包一层。
 */
class WorkspaceException(message: String) : Exception(message)
