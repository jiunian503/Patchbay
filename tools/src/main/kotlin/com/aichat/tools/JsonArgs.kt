package com.aichat.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 从模型给的参数里安全取值。
 *
 * ## 为什么不直接 `jsonPrimitive.content`
 *
 * 模型给的参数**类型是不可信的**。schema 里写 `"type": "integer"`，
 * 模型仍然可能发 `"3"`（字符串）、`"three"`、或者 `null`。
 * 直接 `.int` 会在第一种情况抛异常，整场对话就断在这里 ——
 * 而这是完全可以容忍的输入。
 *
 * 好在 kotlinx.serialization 自带的 `intOrNull` / `doubleOrNull` 就是按
 * `content.toXxxOrNull()` 实现的，**带引号的数字也能解析**，
 * `contentOrNull` 在 `JsonNull` 上返回 null 而不是抛异常 —— 正好是我们想要的。
 * 所以这里只需要额外挡住「模型把对象/数组塞进标量字段」这一种情况。
 */
internal fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.scalarOrNull()?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject.intOrNull(key: String): Int? =
    this[key]?.scalarOrNull()?.intOrNull

internal fun JsonObject.doubleOrNull(key: String): Double? =
    this[key]?.scalarOrNull()?.doubleOrNull

internal fun JsonObject.booleanOrNull(key: String): Boolean? =
    this[key]?.scalarOrNull()?.booleanOrNull

/**
 * `jsonPrimitive` 遇到 JSON 对象或数组会抛异常。
 * 模型偶尔会把 `{"city": "北京"}` 塞进一个本该是字符串的字段里，
 * 那不该让整场对话崩掉 —— 返回 null，让调用方回灌「参数不对」。
 */
private fun JsonElement.scalarOrNull(): JsonPrimitive? =
    runCatching { jsonPrimitive }.getOrNull()
