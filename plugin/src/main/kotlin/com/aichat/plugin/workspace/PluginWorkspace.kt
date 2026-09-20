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
        requireScope(needWrite = true)
        val file = resolve(path)

        val bytes = text.toByteArray(StandardCharsets.UTF_8)
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
                "工作区已经有 ${existing.entries} 个文件，到达 ${MAX_ENTRIES} 个的上限。" +
                    "先删掉不再需要的文件再写。",
            )
        }

        try {
            file.parentFile?.mkdirs()
            file.writeText(text, StandardCharsets.UTF_8)
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

    /** 当前用量。每次写都要算一遍，所以它必须便宜 —— 条目有上限，走一遍是够快的。 */
    private fun usage(): Usage {
        if (!dir.exists()) return Usage(0, 0L)
        var entries = 0
        var bytes = 0L
        dir.walkTopDown().forEach {
            if (it.isFile) {
                entries++
                bytes += it.length()
            }
        }
        return Usage(entries, bytes)
    }

    private data class Usage(val entries: Int, val bytes: Long)

    private companion object {
        const val MAX_FILE_BYTES = 1 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
        const val MAX_ENTRIES = 256
        const val MAX_DEPTH = 16
    }
}

/**
 * 工作区操作失败。
 *
 * 单独一个类型而不是 `IOException`，是因为**只有它的话是给插件作者看的**。
 * `IOException` 那种「磁盘满了」的原话是给宿主开发者看的，两者混在一起之后，
 * 沙箱就没法决定哪句能原样交给插件、哪句该包一层。
 */
class WorkspaceException(message: String) : Exception(message)
