package com.aichat.plugin.manifest

/**
 * 清单里的一处问题。
 *
 * [path] 用 JSON 路径（`$.tools[0].request.path`）而不是行号：清单是 JSON，
 * 解析它的地方没有行号信息，硬凑一个只会指错。路径能直接拿去对照原文，
 * 也能让编辑器插件高亮。
 *
 * [message] 要写成**给插件作者看的一句话**：指出哪里错了，以及怎么改。
 * 「非法值」没有用，「baseUrl 必须是 http 或 https 开头的完整地址，
 * 现在是 `api.example.com`（少了协议）」才有用。
 */
data class ManifestProblem(
    val path: String,
    val message: String,
    val severity: Severity = Severity.Error,
) {
    enum class Severity {
        /** 清单不能用。必须修掉才能安装。 */
        Error,

        /** 能用，但作者大概不是这么想的，或者安装时要给用户额外提示。 */
        Warning,
    }

    override fun toString(): String =
        "${if (severity == Severity.Error) "错误" else "警告"} $path: $message"
}

/**
 * 清单检查结果。
 *
 * ## 为什么不用 `Result<PluginManifest>`
 *
 * 因为**警告和错误要一起回来**。一个清单可能同时有「少写了 network 白名单」（错误，
 * 装了也用不了）和「声明了 `*` 任意主机」（警告，装的时候要吓一下用户）。
 * 用 `Result` 的话这两条得分两次跑校验，而它们本来就是同一遍扫描出来的。
 *
 * [manifest] 为 null 表示有 Error 级问题 —— 调用方拿不到清单，
 * 也就不可能「不小心用了一个有问题的清单」。
 */
data class ManifestCheck(
    val manifest: PluginManifest?,
    val problems: List<ManifestProblem>,
) {
    val errors: List<ManifestProblem> get() = problems.filter { it.severity == ManifestProblem.Severity.Error }
    val warnings: List<ManifestProblem> get() = problems.filter { it.severity == ManifestProblem.Severity.Warning }

    val isUsable: Boolean get() = manifest != null

    /** 一段可以直接显示给人看的报告。安装失败时用它。 */
    fun report(): String = buildString {
        val m = manifest
        if (m != null) {
            append("插件「${m.name}」(${m.id}) 检查通过")
            if (warnings.isEmpty()) {
                append("。")
            } else {
                append("，但有 ${warnings.size} 条提示：")
            }
        } else {
            append("插件清单有 ${errors.size} 处错误，无法安装：")
        }
        for (p in problems) {
            append('\n').append("  ").append(p)
        }
    }
}
