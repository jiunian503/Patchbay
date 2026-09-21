package com.aichat.ui.characters

import com.aichat.domain.prompt.WorldBookMatcher
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书说明里那句「一次注入有上限」。
 *
 * ## 为什么值得一条测试
 *
 * 上限的截断是**静默的**（`WorldBookMatcher.match` 按顺序累加到预算就停），
 * 所以「用户知道有上限」这件事**只靠这一句话**。而这句话有两个容易坏的地方：
 *
 * 1. **数字漂掉** —— 上限改了，文案还写着旧数（README 的体积数字就是这么过期的）。
 *    所以下面直接拿 [WorldBookMatcher.DEFAULT_BUDGET_CHARS] 去比，不写字面量。
 * 2. **抽出来了却没人调用** —— 那时函数、测试全绿，用户还是看不到。
 *    这正是本项目刚学到的「**修好了，但用户走不到**」（§110）。
 */
class WorldBookHintUiTest {

    @Test
    fun `说了上限，而且数字就是那个常量`() {
        val text = worldBookBudgetHint()
        val budget = WorldBookMatcher.DEFAULT_BUDGET_CHARS.toString()

        assertTrue(
            "说明里必须出现上限的实际数字（$budget）—— 文案里的数字是断言，\n" +
                "不能写死：改上限时它得跟着走。现在是：\n$text",
            text.contains(budget),
        )
    }

    @Test
    fun `说清了被丢掉的是排在后面的那几条`() {
        val text = worldBookBudgetHint()

        assertTrue("得说清谁会被丢下（不是随机丢）：\n$text", text.contains("排在后面"))
        assertTrue("得给出出路 —— 顺序能调，把要紧的往前排：\n$text", text.contains("往前排"))
    }

    @Test
    fun `没有把长度说成不要钱`() {
        val text = worldBookBudgetHint()

        assertTrue("必须明说有限制：\n$text", text.contains("上限"))
        assertTrue("这不是警告，是说明 —— 别写成「已超出」那种口气：\n$text", text.contains("有上限"))
    }

    @Test
    fun `角色编辑页真的把它显示出来了`() {
        // 「抽成函数」只解决可测，不解决可见 —— 没人调用的话用户照样看不到（§110）
        val screen = File(sourceRoot(), "app/src/main/java/com/aichat/ui/characters/CharacterEditScreen.kt")
        assertTrue("找不到 ${screen.path}", screen.isFile)

        val code = screen.readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .joinToString("\n")

        assertTrue(
            "CharacterEditScreen 里没有调用 worldBookBudgetHint() —— 那句话用户看不到。\n" +
                "抽成纯函数只解决了「能测」，可见性得靠这一条守。",
            code.contains("worldBookBudgetHint("),
        )
    }

    @Test
    fun `README 里那个上限数字也没漂`() {
        // 界面那句话是从常量插值的，**README 是手写的** —— 手写的那份才是会过期的那种
        // （README 的体积数字就是这么过期的，§107.8）。所以这里只能读文件比。
        val readme = File(sourceRoot(), "README.md")
        assertTrue("找不到 ${readme.path}", readme.isFile)
        val text = readme.readText()
        val budget = WorldBookMatcher.DEFAULT_BUDGET_CHARS.toString()

        assertTrue(
            "README 里必须写出上限的实际数字（$budget）—— 改上限时它得跟着走。\n" +
                "README 是手写的，所以只能靠这一条守着。",
            text.contains(budget),
        )
        assertTrue(
            "README 里不能再留着「设定集写多长都行」这句话 —— 它会让人推出「长度不要钱」，\n" +
                "而超预算的词条是**静默丢掉**的。改成「可以写很长，但发出去的有上限」这类写法。",
            !text.contains("写多长都行"),
        )
    }

    private fun sourceRoot(): File {
        val raw = System.getProperty("patchbay.sourceRoot")
        assertNotNull(
            "构建配置没把 patchbay.sourceRoot 传给测试 JVM —— 检查 app/build.gradle.kts 的 testOptions",
            raw,
        )
        return File(raw!!)
    }
}
