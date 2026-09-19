package com.aichat.domain.render

/**
 * Markdown 解析器抽象。
 *
 * ## 为什么留这个接口
 *
 * 内置实现（[BuiltinMarkdownParser]）覆盖的是**聊天场景需要的那部分** Markdown，
 * 不是完整 CommonMark —— 完整规范里有大量文档排版特性（缩进代码块、链接引用定义、
 * HTML 块、setext 的各种边界情形）在对话里几乎不会出现，为它们付出复杂度不划算。
 *
 * 将来如果发现某类内容渲染得不对，可以换一个库支撑的实现（commonmark-java、
 * markdown-it 等）而不动上层 —— [StreamingMarkdownRenderer] 与 Compose 渲染层
 * 只认 [MarkdownBlock]，不认任何具体语法。
 *
 * ## 换实现时必须重跑
 *
 * `MarkdownParserConsistencyTest` 里那 13 个用例是从 `spike/renderer_check.js`
 * 移植过来的，它们守的是「**分块解析拼接 == 整篇解析**」。
 * 这条性质是块缓存的前提，而它**高度依赖具体实现的块级判定** ——
 * 换库后必须重跑，别假定还成立。
 */
interface MarkdownParser {

    /** 把一段 Markdown 解析成块序列。必须是纯函数：同样的输入永远给同样的输出。 */
    fun parse(markdown: String): List<MarkdownBlock>

    companion object {
        val Default: MarkdownParser = BuiltinMarkdownParser()
    }
}
