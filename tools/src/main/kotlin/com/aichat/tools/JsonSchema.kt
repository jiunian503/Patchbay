package com.aichat.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 工具参数用的 JSON Schema 小构造器。
 *
 * 为什么不用字符串拼：Schema 里嵌套三层很常见（object → properties → 某个字段），
 * 手写字符串一旦括号对不上，模型收到的是一坨无法解析的东西 ——
 * 而**服务端多半不会报错**，它只会默默忽略 tools 字段，
 * 表现为「模型永远不调工具」，极难排查。用 `buildJsonObject` 至少保证语法正确。
 */
internal fun schema(
    properties: Map<String, JsonObject>,
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        properties.forEach { (name, spec) -> put(name, spec) }
    }
    // 不写 required 时**必须整个字段省略**，不能给空数组：
    // 部分网关看到 `"required": []` 会直接 400。
    if (required.isNotEmpty()) {
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }
}

internal fun stringParam(description: String, enum: List<String>? = null): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enum != null) putJsonArray("enum") { enum.forEach { add(JsonPrimitive(it)) } }
    }

internal fun numberParam(description: String): JsonObject = buildJsonObject {
    put("type", "number")
    put("description", description)
}

internal fun integerParam(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}
