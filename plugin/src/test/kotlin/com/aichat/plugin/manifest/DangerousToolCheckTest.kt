package com.aichat.plugin.manifest

import com.aichat.plugin.Manifests
import com.aichat.plugin.runtime.ToolConfirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装校验对「这个工具会不会弹窗」的判断，必须和**运行时**同一套。
 *
 * ## 为什么值得一条测试
 *
 * `dangerous` 的检查要求「标了高危的工具必须会被确认」。而「会不会被确认」这条
 * 规则活在 `com.aichat.plugin.runtime.ToolConfirmation` 里 —— 安装校验够不到它
 * （`manifest` 不能依赖 `runtime`，那边反向依赖这边），所以只能自己再算一遍。
 *
 * ⚠️ **两处算错的方向是不对称的**：这里算错 = **报假警**，一条 `Error` 直接拦住
 * 安装，而作者照它写的做也解决不了问题（他写 `dangerous: true` 本来是对的）；
 * 运行时算错 = 不弹窗。所以这条测试不钉措辞，只钉**两个判断是否一致**。
 *
 * ## 原来的判据漏掉的几格
 *
 * 原判据是 `requiresConfirmation != true`，等于「没写就不安全」—— 那只在
 * **声明式 + GET** 上成立。实测：
 *
 * - **脚本 + 没写** ⇒ 运行时会弹窗（脚本默认确认），安装校验却报 Error
 * - **声明式 POST + 没写** ⇒ 运行时会弹窗，安装校验也报 Error
 * - **任意主机 + `false`** ⇒ 运行时**会**弹窗（任意主机压过签字），而另一条
 *   Warning 却说「作者明确关掉了确认弹窗」—— 反话
 */
class DangerousToolCheckTest {

    @Test
    fun `dangerous 的检查必须和运行时的确认规则一致`() {
        var checked = 0

        listOf(false, true).forEach { anyHost ->
            val network = if (anyHost) listOf("*") else listOf("api.example.com")

            // 声明式：没写时按 HTTP 方法兜底
            listOf(HttpMethod.Get, HttpMethod.Post).forEach { method ->
                listOf<Boolean?>(null, true, false).forEach { declared ->
                    val expected = ToolConfirmation.declarative(
                        anyHost = anyHost,
                        declared = declared,
                        method = method,
                    )
                    val actual = hasDangerousError(
                        Manifests.declarative(
                            network = network,
                            toolBody = { declarativeTool(it, method, declared) },
                        ),
                    )
                    assertAgrees("声明式 method=$method declared=$declared anyHost=$anyHost", expected, actual)
                    checked++
                }
            }

            // 脚本：没有 request 可依，没写时**默认确认**
            listOf<Boolean?>(null, true, false).forEach { declared ->
                val expected = ToolConfirmation.script(anyHost = anyHost, declared = declared)
                val actual = hasDangerousError(
                    Manifests.script(
                        network = network,
                        toolBody = { scriptTool(it, declared) },
                    ),
                )
                assertAgrees("脚本 declared=$declared anyHost=$anyHost", expected, actual)
                checked++
            }
        }

        // 表本身也要钉住：哪天加了运行形态或三态多了一档，回来补格子
        assertEquals("真值表的格子数变了 —— 补完再改这个数", 18, checked)
    }

    @Test
    fun `任意主机时不说「作者关掉了确认」`() {
        // `network: ["*"]` 下作者那句 `false` 不生效（任意主机一律确认），
        // 所以那句 Warning 说「作者明确关掉了确认弹窗」是反话 —— 弹窗其实还在
        val withAnyHost = ManifestParser.parse(
            Manifests.declarative(
                network = listOf("*"),
                toolBody = { declarativeTool(it, HttpMethod.Post, declared = false) },
            ),
        )
        assertTrue(
            "任意主机时不该说「作者关掉了确认」：${withAnyHost.problems.map { it.message }}",
            withAnyHost.problems.none { it.message.contains("作者明确关掉了确认弹窗") },
        )

        // 反向：没有任意主机时，那句话仍然要说
        val concreteHost = ManifestParser.parse(
            Manifests.declarative(
                toolBody = { declarativeTool(it, HttpMethod.Post, declared = false) },
            ),
        )
        assertTrue(
            "没有任意主机时，POST + 明确关掉确认仍然要说出来：${concreteHost.problems.map { it.message }}",
            concreteHost.problems.any { it.message.contains("作者明确关掉了确认弹窗") },
        )
    }

    // ── 判据 ─────────────────────────────────────────────────────────────────

    /**
     * 安装校验有没有因为 `dangerous` 报出**错误**？
     *
     * 只看 `Error`（那条 Warning 是另一回事），只看 `dangerous` 那一条 ——
     * 别把「声明式没写 request」之类的无关错误算进来。
     */
    private fun hasDangerousError(manifestJson: String): Boolean {
        val check = ManifestParser.parse(manifestJson)
        return check.problems.any {
            it.severity == ManifestProblem.Severity.Error && it.message.startsWith("标了 dangerous")
        }
    }

    private fun assertAgrees(what: String, runtimeConfirms: Boolean, parserErrors: Boolean) {
        assertEquals(
            "$what：运行时${if (runtimeConfirms) "会" else "不会"}弹窗，而安装校验" +
                if (parserErrors) "报了 Error（假警）" else "没报（漏网）",
            !runtimeConfirms,
            parserErrors,
        )
    }

    // ── 造数据 ───────────────────────────────────────────────────────────────

    private fun declarativeTool(name: String, method: HttpMethod, declared: Boolean?): String =
        buildString {
            append("{\"name\":\"$name\",\"description\":\"说明\",")
            append("\"parameters\":{\"type\":\"object\",\"properties\":{}},")
            append("\"dangerous\":true,")
            append("\"request\":{\"method\":\"${method.wire}\",\"path\":\"/p\"}")
            if (declared != null) append(",\"requiresConfirmation\":$declared")
            append("}")
        }

    private fun scriptTool(name: String, declared: Boolean?): String =
        buildString {
            append("{\"name\":\"$name\",\"description\":\"说明\",")
            append("\"parameters\":{\"type\":\"object\",\"properties\":{}},")
            append("\"dangerous\":true")
            if (declared != null) append(",\"requiresConfirmation\":$declared")
            append("}")
        }
}
