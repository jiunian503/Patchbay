package com.aichat.plugin.template

/**
 * 清单模板里的 `{{名字}}` 占位符。
 *
 * ## 两种来源
 *
 * | 写法 | 取自 |
 * |---|---|
 * | `{{latitude}}` | 模型这次调用给的参数 |
 * | `{{settings.unit}}` | 用户在插件配置里填的值 |
 *
 * 前缀 `settings.` 是唯一的区分方式。刻意不加别的语法（不用 `$`、不用 `${}`）：
 * 模板写在 JSON 字符串里，越简单的语法越不容易和 URL 自身的字符打架 ——
 * `{{}}` 在 URL 里不会自然出现，而 `$` 会（价格、正则、SQL 片段）。
 */
object Placeholders {

    /**
     * 匹配 `{{...}}`，内容里不允许再出现花括号。
     *
     * 不支持嵌套是有意的：嵌套模板会带来「替换结果里再出现占位符要不要继续替换」
     * 这个问题，而无论选哪一边都会有人踩到。**只替换一遍**，替换结果原样输出 ——
     * 于是模型给的参数里就算写了 `{{settings.key}}`，也只会变成字面文字，
     * 不可能反过来去读用户配置。这是一条安全性质，不是实现偷懒。
     */
    private val PATTERN = Regex("""\{\{([^{}]*)\}\}""")

    private const val SETTINGS_PREFIX = "settings."

    /** 模板里出现的全部占位符，去重、保持出现顺序。 */
    fun scan(template: String): List<Placeholder> {
        val seen = LinkedHashSet<String>()
        return PATTERN.findAll(template)
            .map { parse(it.groupValues[1]) }
            .filter { seen.add(it.raw) }
            .toList()
    }

    /**
     * 把一个占位符文本解析成来源。
     *
     * `{{}}`、`{{ }}`、`{{settings.}}` 这类都算 [Placeholder.Malformed] ——
     * 它们几乎必然是笔误，而且如果按「原样输出」处理，会在 URL 里留下
     * 一串花括号，报回来一个看不懂的 400。
     *
     * @param raw `{{ }}` 里的原文（已 trim）。注意它**包含** `settings.` 前缀，
     *   和 [Placeholder.name] 不同 —— 报错信息必须用 [Placeholder.raw]，
     *   否则作者会拿着一个清单里根本不存在的写法去排查。
     */
    fun parse(raw: String): Placeholder {
        val text = raw.trim()
        if (text.isEmpty()) return Placeholder.Malformed(raw)

        if (!text.startsWith(SETTINGS_PREFIX)) {
            return if (isValidIdentifier(text)) {
                Placeholder.Argument(raw = text, name = text)
            } else {
                Placeholder.Malformed(raw)
            }
        }

        val key = text.removePrefix(SETTINGS_PREFIX)
        return if (isValidIdentifier(key)) {
            Placeholder.Setting(raw = text, name = key)
        } else {
            Placeholder.Malformed(raw)
        }
    }

    /**
     * 占位符名字只允许 `[A-Za-z_][A-Za-z0-9_]*`。
     *
     * 收紧到标识符是有意的：占位符名字要么是 JSON Schema 里的参数名，
     * 要么是清单里的配置键，两者本来就受同样的约束。放宽（比如允许 `-`）
     * 只会让「模型把参数名写错」和「作者把占位符写错」都变得更难发现。
     */
    private fun isValidIdentifier(name: String): Boolean =
        name.isNotEmpty() &&
            (name[0].isLetter() || name[0] == '_') &&
            name.all { it.isLetterOrDigit() || it == '_' }

    /**
     * 把模板里的占位符替换掉。
     *
     * @param lookup 返回 null 表示「这个来源没有值」。**不抛异常、也不替换成空串** ——
     *   替换成空串会让「用户没填 API Key」和「用户填了空」变成同一种情况，
     *   而前者要报「去设置里填一下」，后者要报「值不能为空」。
     */
    fun render(template: String, lookup: (Placeholder) -> String?): Rendered {
        val missing = LinkedHashSet<String>()
        val text = PATTERN.replace(template) { match ->
            val placeholder = parse(match.groupValues[1])
            when (placeholder) {
                // 畸形占位符原样留着：它不该被静默吃掉，
                // 校验阶段会把它报成错误，运行时这里是最后的兜底
                is Placeholder.Malformed -> match.value

                else -> {
                    val value = lookup(placeholder)
                    if (value == null) {
                        missing += placeholder.raw
                        // 缺失时保留原文。调用方看到 missing 非空会走「省略 / 报错」分支，
                        // 不会真的把这个字符串发出去
                        match.value
                    } else {
                        value
                    }
                }
            }
        }
        return Rendered(text = text, missing = missing.toList())
    }
}

/** 渲染结果。[missing] 非空时 [text] 里对应的位置仍是原始 `{{...}}` 文本。 */
data class Rendered(
    val text: String,
    val missing: List<String>,
)

/**
 * 一个占位符的来源。
 *
 * ## 为什么有 [raw] 和 [name] 两个
 *
 * 它们对 `{{settings.unit}}` 是不同的东西：
 *
 * | | 值 | 用途 |
 * |---|---|---|
 * | [raw] | `settings.unit` | 报错信息。**必须**是清单里的原文 |
 * | [name] | `unit` | 去查找。配置项的键就是 `unit` |
 *
 * 曾经只有一个 `raw` 字段，存的是剥掉前缀的键，而报错信息按
 * `` `{{settings.$raw}}` `` 拼 —— 于是 `{{settings.unit}}` 没值时会被报成
 * 「占位符 `{{unit}}` 没有对应的值」。作者拿着这句话回清单里找 `{{unit}}`，
 * 找不到，只能怀疑是宿主坏了。**报错信息里的写法必须和作者写的一致。**
 */
sealed interface Placeholder {

    /** `{{ }}` 里的原文（trim 过）。报错信息用这个。 */
    val raw: String

    /** 去查找用的名字：参数名，或配置项键（不含 `settings.` 前缀）。 */
    val name: String

    /** 取自模型这次调用给的参数。 */
    data class Argument(override val raw: String, override val name: String) : Placeholder

    /** 取自用户在插件配置里填的值。 */
    data class Setting(override val raw: String, override val name: String) : Placeholder

    /** 写法不合法，比如 `{{}}`、`{{settings.}}`、`{{a-b}}`。[name] 无意义。 */
    data class Malformed(override val raw: String) : Placeholder {
        override val name: String get() = raw
    }
}
