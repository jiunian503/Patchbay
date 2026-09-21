package com.aichat.tools

/**
 * 一条命令跑完之后的结果。
 *
 * [exitCode] 为 `null` 表示**拿不到退出码** —— 超时被杀、或者进程根本没起来。
 * 别把它当成 0：「跑完了什么都没输出」和「没跑完」是两件事，模型要靠这个区分。
 */
data class ShellOutcome(
    /** stdout + stderr 合并后的文本。分开看对模型没有意义，反而要多解释一层。 */
    val output: String,
    val exitCode: Int?,
    val timedOut: Boolean = false,
    /** 输出超过上限，被截断了。 */
    val truncated: Boolean = false,
)

/**
 * 「在这台设备上跑一条系统 shell 命令」，对 `:tools` 暴露的窄接口。
 *
 * ## 为什么是接口
 *
 * `:tools` 必须保持纯 JVM（§44 铁律），而真正的实现要用 `/system/bin/sh` ——
 * 那是 Android 才有的路径。所以这里只声明能力，实现由 `:app` 注入，
 * 单测注入一个假的。于是「参数怎么校验、结果怎么拼、错误怎么说」全都能在
 * 秒级的 `:tools:test` 里覆盖，不用上模拟器。
 *
 * ## 为什么 [enabled] 和 [run] 在同一个接口上
 *
 * 「这次该不该把工具注册给模型」和「真的跑」必须是**同一个决定的两面**。
 * 拆成两个接口的话会出现「开关读的是 A、执行走的是 B」，而那种错配
 * 没有任何运行时症状：用户以为关掉了，模型照样能跑。
 *
 * 装配路径不能挂起，所以 [enabled] 是同步的 —— 和 `WebSearchSource.configured()`
 * 同款，理由也一样（见那个接口的 KDoc）。
 */
interface ShellSource {

    /** 同步：用户有没有打开「让 AI 跑命令」。装配路径上要问它。 */
    fun enabled(): Boolean

    /**
     * 跑一条命令。
     *
     * 实现方负责三件事，调用方不用管：超时、输出上限、**关掉子进程的 stdin**
     * （不关的话 `cat` 这类命令会永久挂住，而且超时也救不了）。
     * 完整推导见 SKILL.md §104。
     */
    suspend fun run(command: String): ShellOutcome

    companion object {
        /**
         * 超时秒数。
         *
         * 和内置终端同一个数：交互式命令本来就不支持（那要 JNI，§104），
         * 长任务也不该在手机里跑。
         */
        const val TIMEOUT_SECONDS = 15L

        /**
         * 输出上限（字符）。
         *
         * `yes` 或者 `find /` 能吐几百 MB，不设上限就是 OOM。
         * 超了截断并在结果里说明 —— 悄悄吞掉后半截比报错更糟。
         */
        const val MAX_OUTPUT_CHARS = 32_768
    }
}
