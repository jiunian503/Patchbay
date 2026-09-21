package com.aichat.tools

import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * 让**模型**在这台设备上跑一条系统命令。
 *
 * ## 它和「内置终端」是同一个能力，两种使用者
 *
 * 终端页是**用户自己敲**，这个工具是**模型敲、用户点头**。底层是同一份实现
 * （`:app` 的 `SystemShell`）—— 三个坑（关 stdin、输出另起线程读、超时强杀）
 * 只写一遍，见 SKILL.md §104。
 *
 * ## 为什么必须逐次确认
 *
 * 它和 `fetch_url` 不是一类风险。`fetch_url` 的风险是「地址由模型挑」，
 * 最坏是泄露；**这个工具真的会改用户的东西** —— 命令是模型写的，
 * 一条 `rm -rf`、一次 `> 文件` 就能毁掉数据，而模型当时可能只是
 * 「想清理一下临时文件」。
 *
 * 所以 [requiresConfirmation] 为 true，而且 [userSummary] 要如实说清
 * 「跑起来和这个 App 一样有权限」。用户看到那条命令时得知道自己在批准什么 ——
 * 这是这个工具唯一的安全边界，不能写得含糊。
 *
 * ## 为什么排在工具列表最后
 *
 * `BuiltinTools.all` 的顺序是「风险从低到高」。它是唯一一个**能改用户数据**的，
 * 所以排在 `fetch_url` 之后。
 *
 * ## 描述里为什么专门写「这台设备上没有的东西」
 *
 * 系统里没有 `apt` / `curl` / `python`，而模型**默认会以为有** ——
 * 那是它在 Linux 上的经验。不写清的话，用户会看到模型反复尝试
 * `apt install python3`，每次都弹一个框让他点「拒绝」。
 * 这是「把边界写进提示词」，不是注释：改它等于改行为，所以也要跟着一起测。
 *
 * ⚠️ **措辞：「这台设备上没有」不是「做不到」** —— 前者是当前状态，后者是结论。
 * 写成后者，模型会把「Android 装不了」转述给用户，而真相是这个 App 选了零体积
 * 那条路（`lib*.so` + `useLegacyPackaging` 能 exec，proot + rootfs 那套的代价
 * 是 67 MB 起，见 SKILL.md §105.3–§105.5）。`RunCommandToolTest` 里有一条守着它。
 */
class RunCommandTool(private val shell: ShellSource) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        description = "在这台设备上执行一条 shell 命令，把输出读回来。" +
            "系统里自带的命令都能用（ls、cat、grep、find、ps、df、du、ip、sed、awk、tar 等，" +
            "约 190 个），不需要先安装任何东西。" +
            "命令以这个 App 的身份运行，能读 App 有权读的东西，包括共享存储（/sdcard）。" +
            "**这台设备上没有的东西，别去试**：装软件（没有 apt / pkg）、联网下载（没有 curl / wget）、" +
            "跑 python / node（没有这些解释器）；" +
            "`dumpsys`、`settings` 这类需要更高权限的命令会被系统拒绝。" +
            "每次调用都要用户点一次确认，所以别拆成几十个小步骤 —— " +
            "一条命令里用 && 和管道一次做完。",
        parameters = schema(
            properties = mapOf(
                COMMAND to stringParam(
                    "要执行的完整命令，会交给 /system/bin/sh 解释，" +
                        "所以管道、重定向、&& 都能用。" +
                        "例如 ls -la /sdcard/Download | head -20",
                ),
            ),
            required = listOf(COMMAND),
        ),
    )

    /**
     * 给用户看的那句话。
     *
     * 落点是「它跑起来和这个 App 一样有权限」—— 用户看着那条命令时，
     * 需要的正是这个判断依据。参数本身（那条命令）由弹框单独展示。
     */
    override val userSummary: String
        get() = "在你的设备上执行一条系统命令。它和这个 App 有一样的权限 —— " +
            "包括读、改、删你的文件，所以看清楚命令再点。"

    override val requiresConfirmation: Boolean get() = true

    override suspend fun execute(arguments: JsonObject): ToolResult {
        // `stringOrNull` 已经 trim 过、并且把空白串当成 null 返回，
        // 所以这里拿到的命令一定非空 —— 不需要再判一次空
        val command = arguments.stringOrNull(COMMAND)
            ?: return ToolResult.error("缺少参数 $COMMAND。要执行的命令写在这个字段里。")

        val outcome = shell.run(command)

        val body = buildString {
            appendLine("$ $command")
            if (outcome.truncated) {
                appendLine("注意：输出超过 ${ShellSource.MAX_OUTPUT_CHARS} 字，只保留了前面部分。")
            }
            appendLine()
            if (outcome.output.isEmpty()) {
                append("（这条命令没有产生输出）")
            } else {
                append(outcome.output)
            }
        }

        // 超时单独一路：它和「命令跑了但失败」是两件事。原因几乎总是命令在等输入，
        // 而模型看不出这一点时会一遍遍重试同一条命令 —— 每次都要用户再点一次确认。
        // 已经打印出来的部分也带上：那是它为什么被杀的唯一线索
        if (outcome.timedOut) {
            return ToolResult.error(
                "这条命令超过 ${ShellSource.TIMEOUT_SECONDS} 秒还没结束，已经强制停掉了。" +
                    "它多半在等输入，或者本来就是不会自己退出的常驻命令（top、logcat 这类）。" +
                    "这个工具跑不了交互式命令 —— 要读文件就把文件名直接写进命令" +
                    "（`cat 文件名`），别只写一个 `cat` 等标准输入。" +
                    "\n\n它在被停掉之前已经打印的部分：\n\n$body",
            )
        }

        val exit = outcome.exitCode
        return when {
            // 拿不到退出码：进程没正常结束（被系统杀了，或者根本没起来）
            exit == null -> ToolResult.error(
                "命令没有正常退出（拿不到退出码，可能被系统杀掉了）。\n\n$body",
            )

            exit == 0 -> ToolResult.ok(body)

            else -> ToolResult.error(
                "$body\n\n退出码 $exit，说明这条命令没有成功。" +
                    "127 = 这个系统里没有这个命令（别重试了，换别的办法）；" +
                    "126 = 没有执行权限；其他非 0 一般是命令自己报的错，看上面的输出。" +
                    "少数命令用非 0 表示正常结果（比如 grep 没匹配到就是 1），那种情况看输出判断。",
            )
        }
    }

    companion object {
        const val NAME = "run_command"
        const val COMMAND = "command"
    }
}
