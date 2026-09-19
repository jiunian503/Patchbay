package com.aichat.tools

import com.aichat.domain.tool.Tool
import com.aichat.domain.tool.ToolDefinition
import com.aichat.domain.tool.ToolResult
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonObject

/**
 * 算算术。
 *
 * ## 为什么自己写解析器
 *
 * 三条路都试过，只有这条路可行：
 *
 * - `javax.script.ScriptEngine`（Nashorn）：**Android 上根本没有**，
 *   这是 JDK 的东西，编译期就找不到类。
 * - `eval` 之类的动态执行：Android 没有，而且让模型输出可执行代码
 *   本身就是个坏主意 —— 那等于把「模型被注入」升级成「代码执行」。
 * - 交给模型自己算：大数乘除、小数比较它会算错，而且错得很自信。
 *
 * 所以这里是一个**只认数学**的递归下降解析器：没有变量、没有赋值、
 * 没有函数定义、没有字符串，能表达的只有数和运算符。
 * 输入再恶意也只能算出一个数。
 *
 * ## 语法
 *
 * ```
 * 表达式  := 项 (('+' | '-') 项)*
 * 项      := 因子 (('*' | '/' | '%') 因子)*
 * 因子    := 一元
 * 一元    := ('-' | '+') 一元 | 幂
 * 幂      := 基本 ('^' 一元)?          // 右结合：2^3^2 = 2^9；指数可带符号：2^-1
 * 基本    := 数字 | 常量 | 函数'(' 参数表 ')' | '(' 表达式 ')'
 * ```
 *
 * ## 一元负号为什么必须在幂的外面
 *
 * 这是唯一一个「写错也看不出错」的地方。如果按 `因子 := 一元 ('^' 因子)?` 写，
 * `-2^2` 会先算出一元负号得到 -2，再平方，结果是 **4**；
 * 而数学惯例是 -(2^2) = **-4**。
 *
 * 两种写法在 `2^2`、`(-2)^2` 上结果完全一样，只有 `-2^2` 会不同 ——
 * 所以必须有一个专门测它的用例，否则这个错误会一直躺在这里。
 */
class CalculateTool : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        description = "精确计算一个数学表达式。涉及多位数乘除、百分比、幂、开方时必须用它，" +
            "不要心算 —— 心算这类式子经常出错。支持 + - * / % ^ 和括号，" +
            "以及 sqrt/abs/round/floor/ceil/min/max/pow，常量 pi、e。",
        parameters = schema(
            properties = mapOf(
                EXPRESSION to stringParam(
                    "要计算的表达式，例如 (1234 * 5678) / 100、sqrt(2)、2^10。" +
                        "只写数学式，不要写等号或单位。",
                ),
            ),
            required = listOf(EXPRESSION),
        ),
    )

    override val userSummary: String
        get() = "在设备本地计算一个数学表达式（不联网，也不能执行代码）"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val raw = arguments.stringOrNull(EXPRESSION)
            ?: return ToolResult.error(
                "缺少参数 $EXPRESSION。请把要计算的式子放进这个参数，例如 \"1+1\"。",
            )

        return try {
            val value = ExpressionParser(raw).parse()
            if (value.isNaN() || value.isInfinite()) {
                ToolResult.error("「$raw」的结果不是有限数（可能是除以零或数值溢出）。")
            } else {
                ToolResult.ok("$raw = ${formatNumber(value)}")
            }
        } catch (e: ExpressionException) {
            // 把解析器的报错原样回灌 —— 它写了具体位置，模型据此能改对
            ToolResult.error("算不了「$raw」：${e.message}")
        }
    }

    companion object {
        const val NAME = "calculate"
        const val EXPRESSION = "expression"

        /**
         * 数字怎么打印。
         *
         * 关键是把 `3.0000000000000004` 这种浮点噪声收掉：整数结果直接按整数打印，
         * 否则最多保留 10 位有效数字再去掉尾部的 0。
         * 用户看到 `1/3 = 0.3333333333` 是合理的，看到 `0.30000000000000004` 会以为算错了。
         */
        internal fun formatNumber(value: Double): String {
            if (value == 0.0) return "0"
            val asLong = value.toLong()
            if (asLong.toDouble() == value && abs(value) < 9.007199254740992E15) {
                return asLong.toString()
            }
            // 必须显式指定 Locale.US：`"%.10g".format(v)` 走默认 locale，
            // 在德语/法语等区域会输出 `1,5` 这样的逗号小数点，
            // 模型和用户都会读错，而且这种 bug 只在特定区域的机器上出现。
            val rounded = String.format(java.util.Locale.US, "%.10g", value)
            return if (rounded.contains('.')) rounded.trimEnd('0').trimEnd('.') else rounded
        }
    }
}

/** 解析失败。message 面向模型，要写清哪里错了。 */
internal class ExpressionException(message: String) : Exception(message)

/**
 * 递归下降解析器。
 *
 * 刻意不做「先分词再解析」：数学表达式的词法边界就是单个字符，
 * 直接边扫边算反而更短、更不容易错。
 */
internal class ExpressionParser(private val input: String) {

    private var pos = 0

    fun parse(): Double {
        val value = parseExpression()
        skipSpaces()
        if (pos < input.length) {
            throw ExpressionException(
                "第 ${pos + 1} 个字符「${input[pos]}」看不懂。" +
                    "只支持 + - * / % ^、括号、数字和内置函数。",
            )
        }
        return value
    }

    private fun parseExpression(): Double {
        var left = parseTerm()
        while (true) {
            skipSpaces()
            when (peek()) {
                '+' -> { pos++; left += parseTerm() }
                '-' -> { pos++; left -= parseTerm() }
                else -> return left
            }
        }
    }

    private fun parseTerm(): Double {
        var left = parseFactor()
        while (true) {
            skipSpaces()
            when (peek()) {
                '*' -> { pos++; left *= parseFactor() }
                // 除零不抛异常：让它变成 Infinity，由调用方统一报「不是有限数」，
                // 这样错误信息里能带上完整表达式，比在这里抛更有用
                '/' -> { pos++; left /= parseFactor() }
                '%' -> {
                    pos++
                    val right = parseFactor()
                    if (right == 0.0) throw ExpressionException("不能对 0 取余。")
                    left %= right
                }
                else -> return left
            }
        }
    }

    /**
     * 因子 = 一元。中间这层是为了让一元负号落在幂的**外面**，
     * 从而得到 -2^2 = -4（数学惯例）而不是 4。
     */
    private fun parseFactor(): Double = parseUnary()

    private fun parseUnary(): Double {
        skipSpaces()
        return when (peek()) {
            '-' -> { pos++; -parseUnary() }
            '+' -> { pos++; parseUnary() }
            else -> parsePower()
        }
    }

    /** 幂右结合，指数侧再回到 [parseUnary]，所以 `2^-1` 也合法。 */
    private fun parsePower(): Double {
        val base = parsePrimary()
        skipSpaces()
        if (peek() == '^') {
            pos++
            return base.pow(parseUnary())
        }
        return base
    }

    private fun parsePrimary(): Double {
        skipSpaces()
        val c = peek() ?: throw ExpressionException("表达式不完整，末尾缺一个数。")

        if (c == '(') {
            pos++
            val value = parseExpression()
            skipSpaces()
            if (peek() != ')') throw ExpressionException("括号没有闭合。")
            pos++
            return value
        }

        if (c.isDigit() || c == '.') return parseNumber()

        if (c.isLetter()) return parseIdentifier()

        throw ExpressionException("这里期望一个数或左括号，但看到「$c」。")
    }

    private fun parseNumber(): Double {
        val start = pos
        while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
        // 科学计数法：1e3、2.5E-4
        if (pos < input.length && (input[pos] == 'e' || input[pos] == 'E')) {
            val save = pos
            pos++
            if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) pos++
            if (pos < input.length && input[pos].isDigit()) {
                while (pos < input.length && input[pos].isDigit()) pos++
            } else {
                pos = save // 不是科学计数法，退回去（比如 2e 这种）
            }
        }
        val text = input.substring(start, pos)
        return text.toDoubleOrNull()
            ?: throw ExpressionException("「$text」不是一个合法的数。")
    }

    private fun parseIdentifier(): Double {
        val start = pos
        while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_')) pos++
        val name = input.substring(start, pos).lowercase()

        skipSpaces()
        if (peek() != '(') {
            // 没跟括号 → 只能是常量
            return when (name) {
                "pi" -> Math.PI
                "e" -> Math.E
                else -> {
                    pos = start
                    throw ExpressionException(
                        "不认识「$name」。常量只有 pi 和 e；函数要写括号，例如 sqrt(2)。",
                    )
                }
            }
        }

        pos++ // 吃掉 '('
        val args = mutableListOf<Double>()
        skipSpaces()
        if (peek() != ')') {
            args += parseExpression()
            skipSpaces()
            while (peek() == ',') {
                pos++
                args += parseExpression()
                skipSpaces()
            }
        }
        if (peek() != ')') throw ExpressionException("函数 $name 的括号没有闭合。")
        pos++

        return applyFunction(name, args)
    }

    private fun applyFunction(name: String, args: List<Double>): Double {
        fun arity(n: Int) {
            if (args.size != n) {
                throw ExpressionException("$name 需要 $n 个参数，但给了 ${args.size} 个。")
            }
        }
        return when (name) {
            "sqrt" -> { arity(1); sqrt(args[0]) }
            "abs" -> { arity(1); abs(args[0]) }
            "round" -> { arity(1); round(args[0]) }
            "floor" -> { arity(1); floor(args[0]) }
            "ceil" -> { arity(1); ceil(args[0]) }
            "pow" -> { arity(2); args[0].pow(args[1]) }
            "min" -> { arity(2); min(args[0], args[1]) }
            "max" -> { arity(2); max(args[0], args[1]) }
            else -> throw ExpressionException(
                "没有 $name 这个函数。可用的：sqrt、abs、round、floor、ceil、pow、min、max。",
            )
        }
    }

    private fun peek(): Char? = input.getOrNull(pos)

    private fun skipSpaces() {
        while (pos < input.length && input[pos].isWhitespace()) pos++
    }
}
