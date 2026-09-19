package com.aichat.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 单测，不需要模拟器。验证查询表达式的构造正确性。
 * 真机上的召回验证见 CjkSearchInstrumentedTest。
 */
class CjkTextTest {

    @Test
    fun `汉字逐字切分，拉丁保持完整`() {
        assertEquals("帮 我 把 会 议 纪 要 整 理 成 表 格",
            CjkText.forIndex("帮我把会议纪要整理成表格"))
        assertEquals("RikkaHub", CjkText.forIndex("RikkaHub"))
        assertEquals("我 想 做 一 个 AI 对 话 软 件",
            CjkText.forIndex("我想做一个AI对话软件"))
    }

    @Test
    fun `单个汉字不加引号，多个汉字组成短语`() {
        assertEquals("会", CjkText.forQuery("会"))
        assertEquals("\"会 议\"", CjkText.forQuery("会议"))
        assertEquals("\"会 议 纪 要\"", CjkText.forQuery("会议纪要"))
    }

    @Test
    fun `拉丁词转小写并支持前缀`() {
        assertEquals("rikkahub", CjkText.forQuery("RikkaHub"))
        assertEquals("rikka*", CjkText.forQuery("Rikka", prefixLast = true))
    }

    /**
     * 多段之间是**空格并置**，不是 `AND`。
     *
     * 这条断言看着只是字符串拼接，实际守的是一个只在真机上暴露的坑：
     * Android 的 SQLite 用的是 Standard Query Syntax，`AND` 在那里是普通
     * token 而不是运算符，`"会 议" AND "纪 要"` 会去找一个不存在的 token
     * `and`，永远返回空。详见 [CjkText.forQuery] 的 KDoc。
     */
    @Test
    fun `多段用空格并置而不是 AND`() {
        assertEquals("\"会 议\" \"纪 要\"", CjkText.forQuery("会议 纪要"))
    }

    @Test
    fun `中英混排拆成短语与词`() {
        // 拉丁段与其他拉丁查询一样统一转小写，见 `拉丁词转小写并支持前缀`
        assertEquals("\"对 话\" ai", CjkText.forQuery("对话AI"))
        assertEquals("\"类 似\" rikkahub", CjkText.forQuery("类似RikkaHub"))
    }

    @Test
    fun `空输入返回空串，调用方需短路`() {
        assertEquals("", CjkText.forQuery(""))
        assertEquals("", CjkText.forQuery("   "))
    }

    @Test
    fun `含 FTS 特殊字符的拉丁串被引号包裹`() {
        assertEquals("\"gpt-4o\"", CjkText.forQuery("gpt-4o"))
        assertTrue(CjkText.forQuery("a\"b").startsWith("\""))
    }

    @Test
    fun `加引号的分支同样转小写，与纯字母数字分支保持一致`() {
        assertEquals("\"gpt-4o\"", CjkText.forQuery("GPT-4o"))
        assertEquals("\"gpt-4o\"", CjkText.forQuery("gpt-4o"))
    }

    @Test
    fun `还原函数去掉填充空格但保留真实空格`() {
        assertEquals("会议纪要", CjkText.stripIndexPadding("会 议 纪 要"))
        assertEquals("hello world", CjkText.stripIndexPadding("hello world"))
    }

    /**
     * 下划线在 FTS 查询里是**分隔符**，一个只含下划线的段会解析成「空」。
     * 空出现在 `AND` 右边时 SQLite 直接报 `malformed MATCH expression` ——
     * 也就是说用户只是在搜索框里打了个「会议 _」，查询就整个失败。
     *
     * 这条和下面几条是同一类问题：白名单放行了分词器**不认**的字符。
     */
    @Test
    fun `纯下划线段被引号包住`() {
        assertEquals("\"会 议\" \"_\"", CjkText.forQuery("会议 _"))
        assertEquals("\"_\"", CjkText.forQuery("_"))
    }

    @Test
    fun `含下划线的拉丁词整体加引号`() {
        // 修复前产出裸的 my_file，同样会踩到上面那个坑
        assertEquals("\"my_file\"", CjkText.forQuery("my_file"))
        assertEquals("\"snake_case\"", CjkText.forQuery("snake_case"))
    }

    /**
     * 分词器只把 ASCII 字母数字当 token 字符，所以韩文、带重音的拉丁字母
     * 裸着出去同样产生不了 token。白名单宁可窄：多包一层引号最坏是把语义
     * 收成「要求相邻」，裸着出去是查询报错。
     */
    @Test
    fun `非 ASCII 字母也被引号包住`() {
        assertEquals("\"café\"", CjkText.forQuery("café"))
        assertEquals("\"한국\"", CjkText.forQuery("한국"))
    }

    /**
     * Java 的 `\s` 不认全角空格 U+3000。
     *
     * 修复前 `会议　纪要` 会被当成**一个** segment，走中英混排分支渲染成
     * `"会 议" "　" "纪 要"`，中间那个空短语让整条查询匹配不到任何东西 ——
     * 用户看到的是「全角空格搜不到、半角空格能搜到」，且没有任何报错。
     */
    @Test
    fun `全角空格与不间断空格都当分隔符`() {
        assertEquals("\"会 议\" \"纪 要\"", CjkText.forQuery("会议\u3000纪要"))
        assertEquals("\"会 议\" \"纪 要\"", CjkText.forQuery("会议\u00A0纪要"))
        // 首尾也要能被清掉
        assertEquals("\"会 议\"", CjkText.forQuery("\u3000会议\u3000"))
    }

    /** 索引侧同样要归一化，否则全角空格会混进 token 里。 */
    @Test
    fun `索引侧把全角空格归一成半角`() {
        assertEquals("会 议 纪 要", CjkText.forIndex("会议\u3000纪要"))
        assertEquals("会 议 纪 要", CjkText.forIndex("会议\u00A0纪要"))
    }
}
