package com.aichat.ui.settings

import com.aichat.domain.update.UpdateStatus
import com.aichat.domain.update.checkForUpdate
import com.aichat.network.ReleaseError
import com.aichat.network.ReleaseLookup

/**
 * 「检查更新」那一行现在该显示什么。
 *
 * ## 为什么这些派生逻辑不写在 ViewModel 里
 *
 * 和 [WebSearchUiState] 同一个理由：`ProviderListViewModel` 要一个真的
 * `AppContainer` 才能构造（Room、KeyStore、一堆 Android 依赖），
 * 于是写在里面的判断单测碰不到。搬到这里的纯函数就能直接断言 ——
 * 而这里的判断恰恰是「给用户看哪句话」，写错了用户会照着做错事。
 */
sealed interface UpdateUiState {

    /**
     * 还没查过。显示当前版本，让用户随时知道自己在哪一版上。
     *
     * [current] 可空：`versionName` 在某些打包方式下读不到，
     * 那时这一行就说「读不到当前版本」，而不是编一个。
     */
    data class Idle(val current: String?) : UpdateUiState

    data object Checking : UpdateUiState

    /** 远端不比当前新。**这是一个确定的结论**，所以可以说「已是最新」。 */
    data class UpToDate(val current: String) : UpdateUiState

    /**
     * 有更新的版本。[pageUrl] 是 Release 页面，界面上那个「去下载」用它。
     */
    data class Available(
        val current: String,
        val latest: String,
        val pageUrl: String,
    ) : UpdateUiState

    /**
     * 查不动了。[error] 保留**结构化**的类型而不是一句话 ——
     * 文案由 [updateFailureMessage] 给，这样改文案不会动到这里，
     * 而测试可以断言「每一类都有一句不同的话」。
     */
    data class Failed(val error: ReleaseError) : UpdateUiState

    /**
     * 查到了，但至少有一边的版本号看不懂，所以**比不了**。
     *
     * 和 [UpToDate] 分开是必须的：把它归进「已是最新」的话，用户会看到一句
     * **确定的错话**，然后永远不去更新。
     */
    data class Undecidable(val current: String, val latest: String) : UpdateUiState

    /**
     * 读不到**自己**的版本号，所以没法比 —— 这不是网络问题，是本地读不到。
     *
     * 单独一个状态而不是并进 [Failed]：那个的 `error` 描述的是「远端那边出了
     * 什么事」，而这里远端可能一切正常。混在一起的话，界面会把锅推给网络，
     * 用户就去反复检查自己的网 —— 而问题根本不在这儿。
     *
     * 也不能并进 [Idle]：用户**点了按钮**，什么都不变等于没反应。
     */
    data object CurrentVersionUnknown : UpdateUiState
}

/**
 * 把「查到的结果」变成「界面要显示的状态」。
 *
 * 纯函数：没有网络、没有 Android、没有协程。所以「限流和连不上要说不一样的话」
 * 这类判断能在毫秒级单测里穷举。
 *
 * [currentVersion] 为 null 表示读不到当前版本 —— 那时**连比都不用比**，
 * 直接说读不到。让它继续走下去的话，`checkForUpdate` 会返回「无法判断」，
 * 而界面会把锅推给远端的 tag（"看不懂 v1.2"），实际上问题在我们这边。
 */
fun resolveUpdate(currentVersion: String?, lookup: ReleaseLookup): UpdateUiState {
    if (currentVersion == null) {
        // **连比都不用比**：让它继续走下去的话，`checkForUpdate` 会返回
        // 「无法判断」，而界面会把锅推给远端的 tag（「看不懂 v1.2」），
        // 实际上问题在我们这边 —— 读不到自己的版本号
        return UpdateUiState.CurrentVersionUnknown
    }

    return when (lookup) {
        is ReleaseLookup.Failed -> UpdateUiState.Failed(error = lookup.error)

        is ReleaseLookup.Found -> when (
            val status = checkForUpdate(current = currentVersion, latestTag = lookup.tag)
        ) {
            is UpdateStatus.UpToDate -> UpdateUiState.UpToDate(current = status.current)

            is UpdateStatus.Newer -> UpdateUiState.Available(
                current = status.current,
                latest = status.latest,
                pageUrl = lookup.pageUrl,
            )

            is UpdateStatus.Undecidable -> UpdateUiState.Undecidable(
                current = status.current,
                latest = status.latest,
            )
        }
    }
}

/**
 * 每一类失败对用户说的话。
 *
 * ## 为什么必须**每一类都不一样**
 *
 * 因为用户能采取的行动完全不同：
 *
 * - 限流 → 等一会儿再试（他自己能解决）
 * - 没有发布版本 → 等多久都一样，别等了
 * - 连不上 → 去看网络
 * - 其它状态码 → 说清是几，好去搜
 *
 * 全说成「检查更新失败」的话，用户唯一能做的就是反复点同一个按钮 ——
 * 所以「分类」是这个功能的一部分，不是错误处理的细节。
 * `UpdateUiStateTest` 里有一条断言专门钉这件事：五类必须给五句不同的话。
 */
fun updateFailureMessage(error: ReleaseError): String = when (error) {
    is ReleaseError.NoRelease -> "没找到发布版本 —— 仓库里还没有发布过，或者地址不对。"

    is ReleaseError.RateLimited -> "请求太频繁了，过一会儿再试。"

    is ReleaseError.Malformed -> "看不懂对方返回的内容，稍后再试。"

    is ReleaseError.HttpStatus -> "对方返回了 HTTP ${error.code}。"

    is ReleaseError.Network -> "连不上。检查一下网络再试。"
}

/**
 * 设置页「检查更新」那一行的正文。
 *
 * ## 为什么和 [updateFailureMessage] 分开
 *
 * 那个只讲「失败的五种原因」，而这一行还要讲**成功的那几种**：
 * 没查过（带当前版本号）、正在查、已是最新、有新版本、比不了。
 * 合成一个 `when` 也不是不行，但那样「检查更新失败」这句文案就同时活在
 * 两个函数里了 —— 分开之后 [Failed] 这一支只是**转发**，改文案只有一处。
 *
 * ## 每一句都要回答「我（用户）现在该干什么」
 *
 * 和失败分类同一条道理：
 *
 * - 没查过 → 告诉他当前是哪一版，并暗示这里可以点
 * - 已是最新 → **确定**的结论，可以放心
 * - 有新版本 → 说清两边各是几，不然他不知道差多远
 * - 比不了 → 说清是「谁看不懂」，而不是含糊一句「检查失败」
 *   （含糊的话他会去查自己的网络，而问题在版本号格式上）
 *
 * `UpdateUiStateTest` 里有一条断言钉住「这几个状态的话两两不同」。
 */
fun updateRowText(state: UpdateUiState): String = when (state) {
    // 版本号读不到时不说「当前版本 null」—— 那句在界面上就是一行乱码
    is UpdateUiState.Idle ->
        state.current
            ?.let { current -> "当前版本 $current。点一下看看有没有新版本。" }
            ?: "读不到当前版本号。点一下试试，但可能比不了。"

    is UpdateUiState.Checking -> "正在检查…"

    is UpdateUiState.UpToDate -> "已是最新（${state.current}）。"

    // 两边都写出来：只说「有新版本」的话，用户不知道自己是落后一个小版本
    // 还是三个大版本 —— 而这两件事的「要不要现在更新」答案不一样
    is UpdateUiState.Available ->
        "有新版本 ${state.latest} —— 你现在用的是 ${state.current}。"

    is UpdateUiState.Failed -> updateFailureMessage(state.error)

    is UpdateUiState.Undecidable ->
        "远端最新是 ${state.latest}，本机是 ${state.current}，" +
            "但其中有一个版本号看不懂，比不出来。"

    is UpdateUiState.CurrentVersionUnknown -> "读不到本机的版本号，所以没法比。"
}
