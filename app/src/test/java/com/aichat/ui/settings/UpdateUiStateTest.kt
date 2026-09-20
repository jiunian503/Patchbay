package com.aichat.ui.settings

import com.aichat.network.ReleaseError
import com.aichat.network.ReleaseLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「检查更新」的结果怎么变成界面上的话。
 *
 * ## 为什么这个文件值得单独存在
 *
 * 这里每一个分支都是**一句要对用户说的话**，而说错话的代价是用户照着做错事：
 *
 * - 把「限流」说成「没有新版本」→ 用户以为自己在用最新的，其实没查成
 * - 把「连不上」说成「仓库还没有发布」→ 用户去折腾仓库，问题却在他的网
 * - 把「看不懂 tag」说成「已是最新」→ 一句确定的错话，用户永远不更新
 *
 * 而这些判断原来只能写在 `ProviderListViewModel` 里，那个类要一个真的
 * `AppContainer` 才能构造（Room、KeyStore、Android 依赖），单测碰不到。
 * 搬到 [resolveUpdate] 这个纯函数之后就能穷举了。
 */
class UpdateUiStateTest {

    private val page = "https://github.com/o/r/releases/tag/v1.2"

    private fun found(tag: String) = ReleaseLookup.Found(tag = tag, pageUrl = page)

    // ---------- 正常路径 ----------

    @Test
    fun `远端和当前一样时报已是最新`() {
        // 发版时的实际形态：`versionName` 不带 `v`，git tag 带（如 `1.2` vs `v1.2`）。
        // 夹具固定用 `1.1`，**不跟着发版改** —— 验的是「剥不剥 `v`」，与当前是哪一版无关
        assertEquals(
            UpdateUiState.UpToDate(current = "1.1"),
            resolveUpdate(currentVersion = "1.1", lookup = found("v1.1")),
        )
    }

    @Test
    fun `远端更高时报有新版本，并带上 Release 页地址`() {
        assertEquals(
            UpdateUiState.Available(
                current = "1.1",
                latest = "v1.2",
                pageUrl = page,
            ),
            resolveUpdate(currentVersion = "1.1", lookup = found("v1.2")),
        )
    }

    @Test
    fun `最新版号原样带出来，不剥 v 前缀`() {
        // 用户去 Release 页面看到的 tag 就是 `v1.2`。界面显示 `1.2` 的话，
        // 他拿着这个名字在页面上找不到对应的东西
        val state = resolveUpdate(currentVersion = "1.1", lookup = found("v1.2"))
        assertTrue(state is UpdateUiState.Available)
        assertEquals("v1.2", (state as UpdateUiState.Available).latest)
    }

    @Test
    fun `自己装的比线上还新时也算没有可更新的`() {
        // 从源码编的构建会走到这里
        assertEquals(
            UpdateUiState.UpToDate(current = "1.3"),
            resolveUpdate(currentVersion = "1.3", lookup = found("v1.2")),
        )
    }

    @Test
    fun `读不到当前版本时说自己读不到，不怪远端`() {
        // 这一条守的是「别把本地读不到说成远端有问题」。并进 Failed 的话，
        // 界面会说「连不上」或「看不懂对方返回的内容」—— 用户就去折腾自己的
        // 网络和对方的内容，而问题都不在那儿
        assertEquals(
            UpdateUiState.CurrentVersionUnknown,
            resolveUpdate(currentVersion = null, lookup = found("v1.2")),
        )
        // 远端那边其实是好的，这一点不能丢
        assertEquals(
            UpdateUiState.CurrentVersionUnknown,
            resolveUpdate(
                currentVersion = null,
                lookup = ReleaseLookup.Failed(ReleaseError.NoRelease),
            ),
        )
    }

    @Test
    fun `tag 看不懂时是无法判断，而不是已是最新`() {
        // 写成 UpToDate 的话用户会看到一句**确定的错话**
        assertEquals(
            UpdateUiState.Undecidable(current = "1.1", latest = "nightly"),
            resolveUpdate(currentVersion = "1.1", lookup = found("nightly")),
        )
    }

    // ---------- 失败分类 ----------

    @Test
    fun `失败时原样保留结构化的原因`() {
        // 状态里存的是**类型**不是一句话：这样断言不用匹配文案，
        // 改文案也不会误伤测试
        assertEquals(
            UpdateUiState.Failed(ReleaseError.RateLimited),
            resolveUpdate(
                currentVersion = "1.1",
                lookup = ReleaseLookup.Failed(ReleaseError.RateLimited),
            ),
        )
    }

    /**
     * **每一类失败必须给一句不一样的话。**
     *
     * 这是这个文件里最要紧的一条。分类的全部意义就是「用户该做的事不同」——
     * 如果限流和连不上说同一句话，那分类就是白分的，而用户唯一能做的就是
     * 反复点同一个按钮。
     *
     * 同时断言**五类都有话说**：`when` 在 Kotlin 里对 sealed 是穷举的，
     * 但将来给 [ReleaseError] 加了第六个成员之后，编译会提醒去补分支 ——
     * 这条断言守的是「补的时候别顺手复制一句一样的话」。
     */
    @Test
    fun `五类失败给五句不同的话`() {
        val errors = listOf(
            ReleaseError.NoRelease,
            ReleaseError.RateLimited,
            ReleaseError.Malformed,
            ReleaseError.HttpStatus(500),
            ReleaseError.Network("timeout"),
        )

        val messages = errors.map { updateFailureMessage(it) }

        messages.forEach { message ->
            assertTrue("失败文案不能为空：$message", message.isNotBlank())
        }
        assertEquals(
            "五类失败应当给五句不同的话，实际只有 ${messages.toSet().size} 种：" +
                messages.joinToString(" / "),
            errors.size,
            messages.toSet().size,
        )
    }

    @Test
    fun `状态码会写进文案，好让用户能去搜`() {
        // 只说「对方返回了一个错误」的话，用户没法查
        assertTrue(
            updateFailureMessage(ReleaseError.HttpStatus(503)).contains("503"),
        )
    }

    @Test
    fun `没有发布版本和限流不能是同一句话`() {
        // 这两类最容易混：都表现为「没查到东西」。但它们对用户的含义相反 ——
        // 一个等一会儿就好，另一个等多久都一样
        assertNotEquals(
            updateFailureMessage(ReleaseError.NoRelease),
            updateFailureMessage(ReleaseError.RateLimited),
        )
    }

    // ---------- 那一行的正文 ----------

    @Test
    fun `还没查过时把当前版本号写出来`() {
        // 「检查更新」这一行在用户点它之前也得有信息量：他至少该知道自己在哪一版。
        // 写死一句「点这里检查更新」的话，这一行等于一个按钮的标签
        assertTrue(
            updateRowText(UpdateUiState.Idle(current = "1.1")).contains("1.1"),
        )
    }

    @Test
    fun `读不到版本号时不写 null`() {
        // `Idle(current = null)` 直接拼字符串会得到「当前版本 null」——
        // 用户看到这个词只会以为 App 坏了
        val text = updateRowText(UpdateUiState.Idle(current = null))
        assertTrue("不能把 null 拼进给用户看的句子：$text", !text.contains("null"))
        assertTrue(text.isNotBlank())
    }

    @Test
    fun `有新版本时两边版本号都写出来`() {
        // 只说「有新版本」的话，用户不知道自己是落后一个小版本还是三个大版本 ——
        // 而这两件事的「要不要现在更新」答案不一样
        val text =
            updateRowText(
                UpdateUiState.Available(current = "1.1", latest = "v1.2", pageUrl = page),
            )
        assertTrue("要写出远端版本：$text", text.contains("v1.2"))
        assertTrue("也要写出本机版本：$text", text.contains("1.1"))
    }

    @Test
    fun `比不了和已是最新不能是同一句话`() {
        // 这一条是 updateRowText 版的「分类要分干净」：
        // 「比不了」说成「已是最新」的话，用户看到一句确定的错话，永远不更新
        assertNotEquals(
            updateRowText(UpdateUiState.UpToDate(current = "1.1")),
            updateRowText(UpdateUiState.Undecidable(current = "1.1", latest = "nightly")),
        )
    }

    @Test
    fun `失败那一行直接转发失败文案，不另写一份`() {
        // 钉住「只有一处文案」。将来有人在这一行里另写一句「检查更新失败」，
        // 五类失败就又退回成同一句话了 —— 而这里会红
        val error = ReleaseError.HttpStatus(503)
        assertEquals(updateFailureMessage(error), updateRowText(UpdateUiState.Failed(error)))
    }

    /**
     * **每一个状态都要给一句不一样的话，而且都不是空话。**
     *
     * 和 `五类失败给五句不同的话` 同一个形状，但覆盖面是整个 sealed 接口 ——
     * 将来加一个状态（比如「正在下载」）时，忘了给文案的话这一条会红：
     * `when` 是穷举的所以编译能过，但可能被顺手塞进某个 `else -> ""`。
     */
    @Test
    fun `每个状态都给一句不同的话`() {
        val states = listOf(
            UpdateUiState.Idle(current = "1.1"),
            UpdateUiState.Idle(current = null),
            UpdateUiState.Checking,
            UpdateUiState.UpToDate(current = "1.1"),
            UpdateUiState.Available(current = "1.1", latest = "v1.2", pageUrl = page),
            UpdateUiState.Failed(ReleaseError.RateLimited),
            UpdateUiState.Undecidable(current = "1.1", latest = "nightly"),
            UpdateUiState.CurrentVersionUnknown,
        )

        val texts = states.map { updateRowText(it) }

        texts.forEach { text ->
            assertTrue("这一行的正文不能为空", text.isNotBlank())
        }
        assertEquals(
            "每个状态应当给一句不同的话，实际只有 ${texts.toSet().size} 种：" +
                texts.joinToString(" / "),
            states.size,
            texts.toSet().size,
        )
    }
}
