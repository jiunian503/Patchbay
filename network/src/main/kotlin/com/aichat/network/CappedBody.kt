package com.aichat.network

import okhttp3.Response

/**
 * 取一个**非流式**响应体的文本，最多 [limit] 字节。超了返回 `null`。
 *
 * ## 为什么不能直接 `peekBody(limit).string()`
 *
 * 那会把正文**悄悄截断**，而且截在任意一个字节上。下游拿到的是一段残废的
 * JSON，报出来的话是「对端返回的不是 JSON」—— **宿主自己的限制被说成了
 * 对端的问题**，用户会去投诉一个没毛病的服务端。
 *
 * 多读 1 个字节再比，就能把「正好到上限」和「超过上限」分开：
 * `peekBody` 最多给出 `limit + 1` 字节，比 `limit` 大就说明后面还有。
 * 多读的那 1 个字节不会进内存 —— `peekBody` 自己就把结果卡在 `limit + 1` 上，
 * 所以一个 100 MB 的响应也只是「先分配 1 MB 再发现超了」，不存在
 * 「先整个读进来再判断」的窗口（这一点和 [CappedSource] 的类注释同源）。
 *
 * ## 为什么按字节比，而不是 `String.length`
 *
 * 上限的名字（`MAX_BODY_BYTES`）和报给用户的话（「超过 N KB」）都是字节。
 * 按字符比的话，一段 40 万字的中文（约 1.2 MB）会被判成「没超」——
 * 上限写着 1 MB，实际放行到 1.2 MB，而且没有任何地方会说话。
 *
 * ## 为什么返回 `null` 而不是抛异常
 *
 * 两个消费者失败之后要说的话**不一样**，而且都不是一句通用的话能表达的：
 * MCP 那边要包成 `McpFailure`（给用户看），沙箱那边要包成一条 `ok = false`
 * 的信封（给模型看，还得带上下一步动作）。让调用方自己决定说什么。
 *
 * 返回 `String?` 而不是别的形态，也是为了让**忘了判**在 Kotlin 里编译不过 ——
 * 这个函数存在的全部理由就是「别把超限当成成功」。
 *
 * ## 和 [CappedSource] 的分工
 *
 * [CappedSource] 管**流式**：读着读着发现超了，当场停。
 * 这里管**一次性读完**：读完才知道超没超。
 * 「怎么卡」仍然只有一处 —— 多读 1 字节、比字节数，**上限由调用方给**。
 * 两个消费者的上限不同（MCP 的 256 KB、沙箱 `host.http` 的 1 MB），
 * 而且都不该照抄对方那个数（§111.19）。
 */
fun Response.peekTextCapped(limit: Long): String? {
    val peeked = peekBody(limit + 1L)
    val bytes = peeked.bytes()
    if (bytes.size > limit) return null
    // 用响应自己声明的字符集解码 —— 和 `ResponseBody.string()` 的规矩一致。
    // 硬编 UTF-8 会把一个声明了 GBK 的接口解成乱码，那是另一种「悄悄弄坏数据」。
    return bytes.toString(peeked.contentType()?.charset() ?: Charsets.UTF_8)
}
