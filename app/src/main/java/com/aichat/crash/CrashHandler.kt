package com.aichat.crash

import java.lang.Thread.UncaughtExceptionHandler

/**
 * 造一个「写盘 + 交回系统」的未捕获异常处理器。
 *
 * 单独一个函数、而不是直接在 [installCrashHandler] 里 `set`，是为了让测试能
 * 拿到这个处理器**直接调用** —— 装到全局之后再触发它，就得去污染 `Thread`
 * 的静态状态，还得想办法还原。
 *
 * ## 为什么要交回上一个处理器
 *
 * 系统的默认处理器（`RuntimeInit$KillApplicationHandler`）会弹「应用已停止」
 * 并把进程杀掉。我们**只记录、不改变行为**：不交回的话，进程会带着半死的
 * 状态继续跑（界面卡住、协程全乱、数据库连接半开），用户会以为 App 还活着，
 * 然后在更莫名其妙的地方再崩一次。
 *
 * ## 为什么写盘要在崩溃线程上同步做完
 *
 * 处理器返回之后，系统那个马上就会把进程杀掉。异步写（丢给协程或线程池）
 * 等于永远写不完 —— 文件还没建出来，进程已经没了。一份 64KB 以内的文件，
 * 同步写完是毫秒级的事。
 *
 * [previous] 为 null（纯 JVM 环境，没装过默认处理器）时什么都不做：那正是
 * 没有这个处理器之前的默认行为 —— 线程带着异常结束。
 */
fun crashHandler(
    store: CrashStore,
    context: () -> CrashContext,
    previous: UncaughtExceptionHandler?,
): UncaughtExceptionHandler = UncaughtExceptionHandler { thread, error ->
    // 不包 try：[CrashStore.record] 的硬契约是「永不抛」—— 目录建不出来、
    // 磁盘写不进去、连元信息都取不到，都只让这份报告缺一块或者干脆没有。
    // 在外面再包一层 try 是在掩盖契约违约，不是在防御
    store.record(context, thread, error)
    previous?.uncaughtException(thread, error)
}

/**
 * 装上崩溃留痕，返回装上去的那个处理器（测试用得上）。
 *
 * 必须在**建依赖容器之前**调用（见 `PatchbayApp.onCreate`）：容器构造里要碰
 * 数据库、KeyStore、OkHttp，其中任何一步在某个 ROM 上炸掉，都是我们最想
 * 拿到堆栈的那种崩溃。
 */
fun installCrashHandler(
    store: CrashStore,
    context: () -> CrashContext,
): UncaughtExceptionHandler {
    val handler = crashHandler(store, context, Thread.getDefaultUncaughtExceptionHandler())
    Thread.setDefaultUncaughtExceptionHandler(handler)
    return handler
}
