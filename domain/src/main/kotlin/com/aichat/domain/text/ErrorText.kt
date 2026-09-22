package com.aichat.domain.text

/**
 * 从异常里挑一句**能给人看的**兜底说明。
 *
 * ## 为什么不能直接写 `t.message ?: t::class.simpleName`
 *
 * 这个写法在仓库里出现过**十几处**（`:tools` / `:network` / `:plugin` / `:app` / `:chat`
 * 都有），两个坑都是实测踩到的：
 *
 * 1. **`KClass.simpleName` 是 `String?`** —— 对**匿名类和局部类**它返回 `null`
 *    （具名类哪怕不给 message 也有值）。拼出来就是「请求解不开：null」。
 * 2. **就算不为 null，它也是英文类名**（`NullPointerException`、
 *    `SocketTimeoutException`）。而调用点的注释里明写着「插件不该因此拿到
 *    一句看不懂的英文」—— 注释和代码在互相打脸。
 *
 * 还有第三种写法同样坏：`t::class.simpleName.orEmpty()` —— 它不会吐 `null`，
 * 但会吐**空字符串**，于是提示变成「沙箱没能启动：。这是宿主的问题」，
 * 冒号后面什么都没有。空串比 `null` 更难发现，因为它看起来像排版问题。
 *
 * ## 所以这里只做一件事：给一句中文兜底
 *
 * 类名和堆栈该进日志（`Log.w`），不该进给用户或模型的字。调用点如果还想
 * 保留原话做诊断，自己写日志 —— 这个函数不碰日志，因为 `:domain` 是纯逻辑模块。
 *
 * ⚠️ `t.message` 本身仍可能是英文（`IOException` 的原话就是英文）。
 * 这个函数不负责翻译它 —— 那是异常自己的话，丢掉它反而更难查。
 */
fun errorDetail(t: Throwable): String =
    t.message?.takeIf { it.isNotBlank() } ?: "未知错误"


/**
 * 网络故障的裁决 —— 「稍后再试一次可能就好了」。
 *
 * ⚠️ 这句话**必须自己说清能不能重试**：模型只拿得到文案，拿不到任何标志位
 * （`McpFailure.retryable` 是给宿主看的，见它的 KDoc）。所以它不是装饰性的后缀，
 * 而是模型唯一的行为依据 —— 少了它，模型会对着一个永远不会成功的地址反复试。
 *
 * 原来它在 `:plugin` 里**写了四遍**（`DeclarativeTool` 的「请求失败」与「读响应失败」、
 * `McpClient` 的兜底、`McpHttpTransport` 的连接失败），前缀各不相同、这半句一字不差。
 * 那两处的注释还各自写着「这条语义只有一份实现」—— 收口到这里，那句话才成立。
 *
 * 由 `McpFailureVerdictTest` 里的源码扫描守着：全仓 main 源码里含「这是网络问题」的
 * 字面量只允许有一处，就是这里。
 */
const val NETWORK_RETRY_ADVICE: String = "这是网络问题，可以稍后重试。"
