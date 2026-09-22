package com.aichat.sandbox

import android.os.Process
import android.util.Log
import com.aichat.plugin.manifest.FilesystemScope
import com.aichat.plugin.permission.NetworkDeniedException
import com.aichat.plugin.permission.NetworkGuard
import com.aichat.plugin.runtime.script.ScriptOutcome
import com.aichat.plugin.runtime.script.ScriptRequest
import com.aichat.plugin.workspace.PluginWorkspace
import com.aichat.plugin.workspace.PluginWorkspaces
import com.aichat.plugin.workspace.WorkspaceException
import com.quickjs.CommonJSModule
import com.quickjs.JSArray
import com.quickjs.JSObject
import com.quickjs.JavaCallback
import com.quickjs.JavaVoidCallback
import com.quickjs.QuickJS
import com.quickjs.QuickJSScriptException
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 沙箱进程里真正跑脚本的那一层。
 *
 * ## 它只能跑在沙箱进程里，而且只跑一次
 *
 * 调用它的 [ScriptSandboxService] 在客户端断开绑定（= 一次调用结束）时
 * 就把整个进程 SIGKILL 掉。所以这里**不需要考虑复用**：没有「上一次的
 * 全局变量还留着」「上一次的内存还没还回去」这类问题 —— 那些都由
 * 进程边界解决了。
 *
 * 真机实测撑得住这个选择：一个脚本分配 42 MB 再全部释放，RSS 只还回去
 * 32 MB（残留 9.8 MB）。长驻进程会一直涨，而每次一个干净进程不会。
 *
 * ## 两条上限各由谁兑现
 *
 * | 上限 | 谁兑现 | 怎么兑现 |
 * |---|---|---|
 * | `timeoutMs` | **客户端** | 数着墙钟，到点断开绑定 → 本进程 `onUnbind` 自杀 |
 * | `memoryLimitMb` | **本进程** | [MemoryWatchdog] 盯 `/proc/self/status` 的 VmRSS，超了就写遗言再自杀 |
 *
 * 超时为什么不在这一侧做：脚本跑在 `QuickJS-0` 这条线程上，而它一旦
 * `while(true)` 就再也回不来 —— 连 `runtime.close()` 都不返回（§82）。
 * 进程里没有任何人能执行「到点了，停下来」这个动作，只有进程外的调用方
 * 能靠杀进程做到。
 *
 * ## 宿主方法**一律不许抛 Java 异常**
 *
 * `QuickJS.callJavaCallback` 的字节码里没有 try/catch，而它是被 JNI 调进来的。
 * 真机实测：从宿主回调里抛一个 `IllegalStateException`，进程直接 SIGABRT，
 * 栈顶是 `com.quickjs.EventQueue`。所以这里每个宿主方法都自己 `runCatching`
 * 包住，失败**包成返回值**交给 JS 判断。
 */
internal class SandboxEngine(
    private val client: OkHttpClient,
    private val filesDir: File,
    private val workspaces: PluginWorkspaces,
) {

    fun run(request: ScriptRequest): ScriptOutcome {
        // 基线在**建运行时之前**取：预算里应该包含引擎自身的开销，
        // 否则一个 memoryLimitMb 写得很小的插件会因为「引擎本身就占了几 MB」
        // 而在什么都没做的时候就被杀
        val watchdog = MemoryWatchdog(
            filesDir = filesDir,
            baselineKb = rssKb(),
            limitKb = request.memoryLimitMb.toLong() * 1024,
        )
        watchdog.start()

        val runtime = QuickJS.createRuntimeWithEventQueue()

        // 插件 require 了、但插件里没有的模块。
        //
        // **不能在 require 那一刻报** —— 那还在 JNI 回调栈里，而在这里抛
        // Java 异常会把进程带走（见类注释）。所以先记下来，等模块加载完再一起报。
        // 这条是实测撞出来的：第一版在 `getModuleScript` 里返回一段
        // `throw new Error(...)` 的 JS 源码，入口模块那一层没问题，
        // 而 `require` 触发的**嵌套**加载直接让沙箱 SIGABRT
        val missing = mutableListOf<String>()

        return try {
            val module = SandboxModule(runtime, request.files, missing)

            // 宿主自己的那段 JS。它必须比插件的代码先跑 —— 后面 install/run
            // 两个入口都在它返回的那个对象上
            val api = module.executeObjectScript(PRELUDE, PRELUDE_NAME)

            val record = module.executeModule(request.entryFile)

            // 模块加载完了才报。这时已经回到普通的 Java 栈上
            missing.firstOrNull()?.let { return missingModule(request, it) }

            val exports = record.getObject("exports")

            val host = buildHost(module, request)
            api.executeVoidFunction(
                "install",
                JSArray(module)
                    .push(host)
                    // 声明了 filesystem 才给 host.fs 的桩 —— 没声明的插件
                    // 连它的形状都不该看到（「插件拿不到清单里没声明的东西」）
                    .push(request.filesystem != FilesystemScope.None),
            )

            val json = api.executeStringFunction(
                "run",
                JSArray(module).push(exports).push(request.inputJson).push(host),
            )
            ScriptOutcome.Ok(json)
        } catch (e: QuickJSScriptException) {
            // 有模块没找到时，那个才是根因 —— 插件后面抛的 TypeError
            // （「读不到 undefined 的属性」）只是它的后果，报出来对作者没用
            missing.firstOrNull()?.let { return missingModule(request, it) }
            failed(request, e.message, e)
        } catch (t: Throwable) {
            missing.firstOrNull()?.let { return missingModule(request, it) }
            failed(request, t.message, t)
        } finally {
            watchdog.stop()
            // 关不掉也不要紧 —— 进程马上就要被 onUnbind 杀掉了。
            // 但**必须**吞掉它的异常：close() 里可能撞上已经被关掉的上下文
            runCatching { runtime.close() }
        }
    }

    /**
     * 脚本自己出的错（语法错、抛异常、没导出 run、返回值不能序列化）。
     *
     * 分类是 `ScriptError`，message 里带上原始信息 —— 作者要靠它定位。
     * **不加工成「执行失败」**：那四个字对写插件的人毫无用处。
     */
    private fun failed(request: ScriptRequest, message: String?, cause: Throwable): ScriptOutcome.Failed {
        Log.w(LOG_TAG, "插件 ${request.pluginId} 的脚本出错", cause)
        val detail = message?.takeIf { it.isNotBlank() } ?: cause::class.simpleName.orEmpty()
        // 消息构造放在 `SandboxProtocol` 里（那里是纯函数，JVM 上测得到）——
        // 和 deathOutcome / timeoutOutcome / sandboxBrokenOutcome 同一个模式。
        // 这句话里必须带动作：模型看不到 Kind，它只拿到这一个字符串
        return SandboxProtocol.scriptErrorOutcome(request.pluginName, detail)
    }

    /**
     * 插件 `require` 了一个它没带的模块。
     *
     * ## 为什么单独一条，而不是并进 [failed]
     *
     * 因为它比「脚本抛异常了」更具体：作者看到「没有模块 X」，下一个问题
     * 一定是「那我带了什么」，所以这里一次答完（列出 `request.files` 的键）。
     * 并进 [failed] 的话，作者拿到的会是一句
     * `TypeError: undefined is not an object`，指不到自己写漏的那个文件。
     *
     * 分类仍是 `ScriptError` —— 这是插件的清单写漏了，不是沙箱坏了、
     * 也不是宿主的错，所以**不能**说「重试一下」。
     *
     * ## 调用时机是有约束的
     *
     * 只能在 `executeModule` 返回**之后**调（见 [SandboxModule] 的注释）。
     */
    private fun missingModule(request: ScriptRequest, name: String): ScriptOutcome.Failed {
        Log.w(LOG_TAG, "插件 ${request.pluginId} require 了不存在的模块：$name")
        val known = request.files.keys.sorted().joinToString("、").ifEmpty { "（一个都没有）" }
        return ScriptOutcome.Failed(
            "插件「${request.pluginName}」里没有模块「$name」。它带的文件是：$known。" +
                "注意 require 的路径一律相对插件根，不相对当前文件。",
            ScriptOutcome.Kind.ScriptError,
        )
    }

    // ------------------------------------------------------------------ 宿主对象

    private fun buildHost(module: CommonJSModule, request: ScriptRequest): JSObject {
        val host = JSObject(module)

        // 一个插件的多个工具可以共用一个 run，靠这个分派
        host.set("tool", request.toolName)

        // **只含有值的键** —— 空串不该出现在 JS 里，否则脚本分不清
        // 「用户没填」和「用户填了个空的」。这条和 PluginSettings.value 是同一个规则
        val settings = JSObject(module)
        request.settings.forEach { (key, value) -> settings.set(key, value) }
        host.set("settings", settings)

        // 工作区**在装配期就打开**，而不是每次调用现开：
        // 权限检查（只读能不能写）在 PluginWorkspace 构造时就绑好了，
        // 之后每个 fs 调用不再重复判断 —— 少一处「忘了检查」的机会。
        //
        // 没声明 filesystem 的插件拿到 null，__fs 对它一律返回失败。
        // prelude 那边连 host.fs 都不装，所以这只是第二道防线
        val workspace = if (request.filesystem == FilesystemScope.None) {
            null
        } else {
            workspaces.open(request.pluginId, request.filesystem)
        }

        // 名字带下划线前缀：这三个是**原料**，装到 host 上的成品由 prelude 里的
        // install() 定义（它负责把参数规整成字符串、把信封 JSON 解成对象）。
        // 这样 JNI 边界上只过 String，不需要赌「JSObject 参数能不能读出来」。
        // **别把它注册成 `log`** —— prelude 会用它定义 `host.log`，同名的话
        // 那行 JS 就变成了自我递归
        host.registerJavaMethod(JavaVoidCallback { _, args -> logFromPlugin(request, args) }, LOG_RAW)
        val guard = NetworkGuard(request.network)
        host.registerJavaMethod(
            JavaCallback { _, args ->
                httpCall(
                    guard = guard,
                    method = args.getString(0),
                    url = args.getString(1),
                    headersJson = args.getString(2),
                    body = args.getString(3),
                )
            },
            HTTP_RAW,
        )
        host.registerJavaMethod(
            JavaCallback { _, args ->
                fsCall(
                    workspace = workspace,
                    op = args.getString(0),
                    path = args.getString(1),
                    text = args.getString(2),
                )
            },
            FS_RAW,
        )
        return host
    }

    /** 插件日志。截断长度，否则一个写循环能把 logcat 灌满。 */
    private fun logFromPlugin(request: ScriptRequest, args: JSArray) {
        // 整个包在 runCatching 里：参数不是字符串时 getString 会抛，
        // 而抛出去的异常会**跨 JNI** 把进程带走（见类注释）
        runCatching {
            val text = args.getString(0).orEmpty().take(LOG_MAX_CHARS)
            Log.i(LOG_TAG, "[${request.pluginId}] $text")
        }
    }

    /**
     * `host.http` 的实现。**返回一段 JSON 文本**，由 JS 侧 `JSON.parse` 成对象。
     *
     * ## 为什么不直接返回一个 JSObject
     *
     * 实测 `JSObject` 是能从宿主回调返回的（JS 侧读得到字段），但
     * `org.json.JSONObject` **会静默变成 `undefined`** —— 也就是这条路
     * 有一条「看起来对、其实丢了数据」的分支。只走字符串就只剩一条路，
     * 而且 JNI 边界上只过 String 是最不容易出意外的。
     *
     * ## 失败也是返回值，不是异常
     *
     * 见类注释：异常跨 JNI 会杀进程。所以这里一律返回
     * `{ok:false, error:"..."}`，让脚本自己决定要不要 throw。
     * 白名单拒绝的原文来自 [NetworkGuard.check]，那是**写给模型看**的话，
     * 不能在这里改写成「请求失败」。
     */
    private fun httpCall(
        guard: NetworkGuard,
        method: String,
        url: String,
        headersJson: String,
        body: String,
    ): String = runCatching {
        val parsed = url.toHttpUrlOrNull()
            ?: return@runCatching envelope(ok = false, error = "「$url」不是合法地址，host.http 没有发出请求。")

        guard.check(parsed)?.let { return@runCatching envelope(ok = false, error = it) }

        val builder = Request.Builder().url(parsed)
        runCatching { SandboxProtocol.json.decodeFromString<Map<String, String>>(headersJson) }
            .getOrDefault(emptyMap())
            .forEach { (name, value) -> builder.header(name, value) }

        val payload = when (method) {
            "GET", "HEAD" -> null
            // 媒体类型交给插件自己通过 Content-Type 头声明：
            // OkHttp 的规则是「body 没有类型时用头上的那个」，而这里猜错类型
            // 比不猜更糟（对端会按错的方式解析请求体）
            else -> body.toRequestBody(null)
        }
        builder.method(method, payload)

        guard.call(client, builder.build()).use { response ->
            val text = response.peekBody(MAX_BODY_BYTES + 1L).string()
            if (text.length > MAX_BODY_BYTES) {
                envelope(
                    ok = false,
                    error = "响应体超过 ${MAX_BODY_BYTES / 1024} KB，宿主没有把它整个读进来。" +
                        "如果这个接口本来就会返回这么大的东西，请让作者改成带分页或筛选参数的调用。",
                )
            } else {
                val headers = response.headers.names().associateWith { response.header(it).orEmpty() }
                envelope(ok = true, status = response.code, body = text, headers = headers)
            }
        }
    }.getOrElse { t ->
        val message = when (t) {
            // 白名单拒绝的原文已经是给模型看的一句话（它包含「插件被允许访问的是…」），
            // 这里再包一层前缀只会把它埋掉
            is NetworkDeniedException -> t.message.orEmpty()

            is IOException ->
                "网络请求失败：${t.message ?: t::class.simpleName}。网络恢复后可以重试。"

            else -> "请求出错：${t.message ?: t::class.simpleName}。"
        }
        envelope(ok = false, error = message)
    }

    /**
     * `host.http` 的信封。
     *
     * 成功给 `status` / `headers` / `body`，失败只给 `error` —— 两者互斥，
     * 所以脚本只要看 `ok` 就够了，不需要判断「status 是 0 是什么意思」。
     */
    private fun envelope(
        ok: Boolean,
        status: Int = 0,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
        error: String = "",
    ): String = buildJsonObject {
        put("ok", ok)
        if (ok) {
            put("status", status)
            put("body", body)
            putJsonObject("headers") { headers.forEach { (name, value) -> put(name, value) } }
        } else {
            put("error", error)
        }
    }.toString()

    // ------------------------------------------------------------------ 工作区

    /**
     * `host.fs` 的实现。**和 [httpCall] 同一个形状**：返回一段信封 JSON，
     * 由 JS 侧解成返回值或者抛出。
     *
     * ## 为什么也走字符串
     *
     * 除了「JNI 边界上只过 String」那条通用理由（见类注释），这里还多一条：
     * 四种操作的返回类型本来就不一样（字符串 / 无 / 布尔 / 数组）。用
     * `JSObject` 表达的话，JS 侧得靠「哪个字段有值」去猜；用信封就只有一个
     * 规矩 —— **先看 `ok`**。
     *
     * ## 失败也是返回值，不是异常
     *
     * 见类注释：异常跨 JNI 会杀进程。所以这里一律返回 `{ok:false, error}`。
     *
     * [WorkspaceException] 的 message 是**写给插件作者看的**（说清哪条规矩、
     * 下一步怎么办），这里原样搬进 `error`，一个字都不加工 ——
     * 和 `NetworkGuard` 那句「插件没有被授权访问…」是同一条规则。
     */
    private fun fsCall(
        workspace: PluginWorkspace?,
        op: String,
        path: String,
        text: String,
    ): String = runCatching {
        if (workspace == null) {
            return@runCatching fsError("这个插件没有声明 filesystem 权限，所以拿不到工作区。")
        }
        when (op) {
            OP_READ -> fsOk { put("text", workspace.read(path)) }
            OP_WRITE -> {
                workspace.write(path, text)
                fsOk()
            }
            OP_EXISTS -> fsOk { put("flag", workspace.exists(path)) }
            OP_LIST -> fsOk { putJsonArray("items") { workspace.list(path).forEach { add(it) } } }
            // 不该发生：op 是 prelude 里的字面量，不是插件给的
            else -> fsError("宿主不认识的文件操作「$op」。")
        }
    }.getOrElse { t ->
        if (t is WorkspaceException) {
            fsError(t.message.orEmpty())
        } else {
            // 走到这里说明是宿主的 bug（磁盘坏了、id 校验没过…）。插件不该
            // 因此拿到一句看不懂的英文，所以包一句能读的，原话留在日志里
            Log.w(LOG_TAG, "工作区操作 $op 出错", t)
            fsError("工作区操作失败：${t.message ?: t::class.simpleName}。")
        }
    }

    private fun fsOk(build: JsonObjectBuilder.() -> Unit = {}): String =
        buildJsonObject {
            put("ok", true)
            build()
        }.toString()

    private fun fsError(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()

    // ------------------------------------------------------------------ 模块

    /**
     * 插件的模块表。
     *
     * ## 读不到的模块**返回一个空模块**，只把名字记下来
     *
     * 不在这一刻报错，是因为这里还在 `require` 的 JNI 回调栈里，而在这里
     * 抛 Java 异常会把进程带走（见类注释）。那返回一段
     * `throw new Error(...)` 的 **JS** 源码呢？也不行 —— 实测：入口模块
     * 那一层这样写没事，而 `require` 触发的**嵌套**加载会让沙箱 SIGABRT。
     *
     * 所以这里只做两件无害的事：把名字记进 [missing]，返回一个空模块。
     * 真正的报错由 [run] 在 `executeModule` 回到普通 Java 栈之后统一发出 ——
     * 那时无论插件有没有因为读到 `undefined` 而二次抛错，我们都能把
     * **根因**（少了哪个模块）而不是**后果**（TypeError）报出来。
     *
     * ## 路径一律相对**插件根**，不相对当前文件
     *
     * 这是引擎的行为，不是我们的选择：`require` 的模块名归一化里，父路径
     * 取自回调的 `this`，而模块包装函数在严格模式下 `this` 是 undefined，
     * 非严格模式下 `this` 是全局对象（上面没有 `module`）—— 两种情况都拿不到
     * 「当前是哪个文件」。真机实测：`index.js` 里 `require("./lib/util.js")`
     * 拿到的模块名就是 `lib/util.js`。
     *
     * 所以子目录里的文件要引用同目录的兄弟，也得写 `require("./lib/util.js")`。
     * 这条写在 `PluginManifest.files` 的 KDoc 里给作者看。
     */
    private class SandboxModule(
        runtime: QuickJS,
        private val files: Map<String, String>,
        private val missing: MutableList<String>,
    ) : CommonJSModule(runtime) {

        override fun getModuleScript(moduleName: String): String =
            files[moduleName] ?: run {
                // 同一个名字可能被 require 多次，只记第一次 —— 报错只要一个名字
                if (moduleName !in missing) missing += moduleName
                EMPTY_MODULE
            }
    }

    /**
     * 内存看门狗。
     *
     * ## 为什么读 `/proc/self/status` 而不是 Java 堆
     *
     * QuickJS 的堆是 native `malloc`，不经过 Java 堆 —— `Runtime.totalMemory()`
     * 完全看不到脚本分配的东西。真机实测：JS 侧分配 42 MB，`VmRSS` 涨 42 MB，
     * 而这是唯一一条能看见它的路。
     *
     * ## 它兑现不了「一次分配就超限」那种情况
     *
     * 轮询间隔是 25 ms，一次 `new Array(1e9)` 在两次轮询之间就发生了。
     * 那种情况由 QuickJS 自己处理：malloc 失败时它抛一个 JS 的
     * out-of-memory 错误，变成一条正常的 `ScriptError`。两条机制互补，
     * 合起来覆盖了「慢慢涨上去」和「一下冲过头」两种。
     */
    private class MemoryWatchdog(
        private val filesDir: File,
        private val baselineKb: Long,
        private val limitKb: Long,
    ) {
        @Volatile
        private var running = false

        fun start() {
            if (limitKb <= 0 || baselineKb <= 0) return
            running = true
            Thread({
                while (running) {
                    if (rssKb() - baselineKb > limitKb) {
                        // 先留遗言再死。writeDeath 里有 fsync —— SIGKILL 不给
                        // 任何刷缓冲的机会，少了那一步客户端会把内存超限读成「崩了」
                        runCatching { SandboxProtocol.writeDeath(filesDir, SandboxProtocol.DEATH_OOM) }
                        Process.killProcess(Process.myPid())
                        return@Thread
                    }
                    Thread.sleep(POLL_MS)
                }
            }, WATCHDOG_THREAD).apply {
                isDaemon = true
                start()
            }
        }

        fun stop() {
            running = false
        }
    }

    private companion object {
        const val LOG_TAG = "PB_PLUGIN"

        /** 沙箱自己的那段 JS 的「文件名」。只出现在报错栈里，方便认出来是谁。 */
        const val PRELUDE_NAME = "patchbay-host.js"

        /** host 上的原料方法名。装到 host 上的成品由 [PRELUDE] 里的 install() 定义。 */
        const val HTTP_RAW = "__http"
        const val LOG_RAW = "__log"
        const val FS_RAW = "__fs"

        /**
         * `__fs` 的四种操作。
         *
         * 用字面量而不是枚举：它们只在 [PRELUDE] 里和 [fsCall] 里各出现一次，
         * 中间隔着一次跨进程的 JSON 编码 —— 枚举在这里只会多一层映射。
         * **`else` 分支必须存在**（[fsCall] 里有），因为 JS 侧的字符串
         * 在类型上不受这里约束。
         */
        const val OP_READ = "read"
        const val OP_WRITE = "write"
        const val OP_EXISTS = "exists"
        const val OP_LIST = "list"

        const val LOG_MAX_CHARS = 2_000
        const val MAX_BODY_BYTES = 1L * 1024 * 1024
        const val POLL_MS = 25L
        const val WATCHDOG_THREAD = "pb-sandbox-watchdog"

        /**
         * `require` 到一个插件没带的模块时，顶上去的那个空模块。
         *
         * 显式写出来而不是给空串：空串也合法（`module.exports` 保持默认的
         * `{}`），但读代码的人会以为「返回空串」是漏了写，而这里是有意的。
         */
        const val EMPTY_MODULE = "module.exports = {};"

        /**
         * 宿主自己的一段 JS。**它比插件的代码先跑**，返回一个带两个方法的对象。
         *
         * ## 为什么要有这一层（而不是全部用 Java 注册）
         *
         * 三件事只能在 JS 侧做：
         *
         * 1. **把参数规整成字符串再交给 Java。** 从 `JSArray` 里读一个对象属性
         *    有很多「读不到就抛」的分支，而宿主方法抛异常会跨 JNI 杀进程。
         *    在这里 `String(...)` 一次，边界上就只剩 String。
         * 2. **把 `host.http` 的信封解成对象。** Java 侧返回的是 JSON 文本
         *    （见 [httpCall] 的理由），`JSON.parse` 在这里做。
         * 3. **在 JS 里序列化返回值。** `JSON.stringify` 才是引擎自己认的规矩；
         *    用 Java 侧的 `toJSONObject()` 等于把「undefined 怎么办、循环引用怎么办」
         *    重写一遍，而且两边迟早会不一致。
         * 4. **把工作区的失败变成 JS 的异常。** Java 侧返回的是信封
         *    （见 [fsCall]），在这里解成返回值或者 `throw` —— 插件作者拿到的
         *    是 `Error`，和 `host.http` 那套保持一致。
         *
         * ## 两个检查是替作者挡掉「文档里写了但很难自查」的坑
         *
         * - `async run` —— 引擎不 drain 微任务队列，async 的后半段永远不执行，
         *   返回值是个 Promise（`JSON.stringify` 出来是 `{}`）。**静默的错**，
         *   所以在这里明说。作者照别处的习惯写 async 是最可能踩的一条。
         * - 返回值不能序列化 —— 不拦的话模型会收到一个 `{}` 或者一句
         *   「不能序列化」，而后者更接近原因。
         */
        val PRELUDE = """
            "use strict";
            ({
              install: function (host, declaredFs) {
                host.log = function (msg) {
                  try { host.$LOG_RAW(String(msg)); } catch (e) { /* 日志不该让脚本失败 */ }
                };

                host.http = function (req) {
                  req = req || {};
                  var url = req.url ? String(req.url) : "";
                  if (!url) return { ok: false, error: "host.http 需要 url。" };

                  var method = String(req.method || "GET").toUpperCase();
                  var headers = {};
                  if (req.headers) {
                    var names = Object.keys(req.headers);
                    for (var i = 0; i < names.length; i++) {
                      headers[names[i]] = String(req.headers[names[i]]);
                    }
                  }
                  var body = (req.body === undefined || req.body === null) ? "" : String(req.body);
                  return JSON.parse(host.$HTTP_RAW(method, url, JSON.stringify(headers), body));
                };

                // 工作区。清单里 filesystem 权限对应的那个目录，路径一律相对它。
                //
                // 权限检查**不在这里**：Java 侧的 PluginWorkspace 在构造时就绑好了
                // 「哪个插件、什么权限」，每个调用都过一遍。这里只管两件 JS 的事：
                // 把参数规整成字符串（JNI 边界上只过 String），以及把信封解成
                // 返回值或者 throw。
                if (declaredFs) {
                  var unwrap = function (raw) {
                    var r = JSON.parse(raw);
                    if (!r.ok) throw new Error(r.error);
                    return r;
                  };
                  // 参数在**进 JNI 之前**检查。交给 String(p) 的话，传个对象
                  // 会静默变成 "[object Object]" —— 一个能跑、但存错了的路径
                  var needPath = function (p, who) {
                    if (typeof p !== "string" || p === "") {
                      throw new Error(who + " 需要一个**相对工作区**的路径字符串，" +
                        "比如 \"cache/a.csv\"；根目录写成 \".\"。");
                    }
                    return p;
                  };

                  host.fs = {
                    readText: function (p) {
                      return unwrap(host.$FS_RAW("read", needPath(p, "host.fs.readText"), "")).text;
                    },
                    writeText: function (p, text) {
                      if (typeof text !== "string") {
                        throw new Error("host.fs.writeText 的第二个参数必须是字符串。" +
                          "工作区只存文本 —— 要存对象就先 JSON.stringify。");
                      }
                      unwrap(host.$FS_RAW("write", needPath(p, "host.fs.writeText"), text));
                    },
                    exists: function (p) {
                      return unwrap(host.$FS_RAW("exists", needPath(p, "host.fs.exists"), "")).flag;
                    },
                    list: function (p) {
                      var dir = (p === undefined || p === null) ? "." : needPath(p, "host.fs.list");
                      return unwrap(host.$FS_RAW("list", dir, "")).items;
                    },
                  };
                }
              },

              run: function (exports, inputJson, host) {
                if (!exports || typeof exports.run !== "function") {
                  throw new Error(
                    "这个插件没有导出 run(input, host)，它导出的是：" +
                    Object.keys(exports || {}).join("、") + "。"
                  );
                }

                var input = JSON.parse(inputJson);
                var value = exports.run(input, host);

                if (value && typeof value.then === "function") {
                  throw new Error(
                    "插件的 run 是 async 的。QuickJS 里没有事件循环，宿主也不会 drain 微任务队列，" +
                    "所以 async 函数的后半段永远不会执行。请把 run 改成同步函数 —— " +
                    "host.http 本身就是同步阻塞的，不需要 await。"
                  );
                }

                if (typeof value === "undefined") return "null";
                var text = JSON.stringify(value);
                if (typeof text !== "string") {
                  throw new Error(
                    "插件 run 的返回值不能序列化成 JSON —— 函数、循环引用、BigInt 都不行。"
                  );
                }
                return text;
              },
            })
        """.trimIndent()
    }
}

/**
 * 本进程的常驻内存（kB）。
 *
 * 读不到时返回 -1，[SandboxEngine] 会据此**关掉**看门狗而不是「当成 0」——
 * 把读不到当成「用量是 0」会让看门狗变成「一启动就杀」，而把读不到当成
 * 「不限制」至少和「没有看门狗」的行为一致，是安全的那一侧。
 */
private fun rssKb(): Long = runCatching {
    File("/proc/self/status").readLines()
        .first { it.startsWith("VmRSS:") }
        .filter { it.isDigit() }
        .toLong()
}.getOrElse { -1L }
