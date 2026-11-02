package com.clawmate.app.task.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Checks authored JSON without repairing, normalizing or dropping unknown fields. */
object WorkflowEditValidator {
    const val MAX_CHARS = 256_000
    private val json = Json { isLenient = false }

    fun error(raw: String): String? {
        if (raw.isBlank()) return "配置不能为空"
        if (raw.length > MAX_CHARS) return "配置过长，最多支持 $MAX_CHARS 个字符"
        val root = try {
            json.parseToJsonElement(raw)
        } catch (_: Exception) {
            return "JSON 格式错误，请检查引号、逗号和括号"
        } as? JsonObject ?: return "配置根节点必须是 JSON 对象"
        for (key in listOf("metadata", "defaults", "context")) {
            if (key in root && root[key] !is JsonObject) return "$key 必须是 JSON 对象"
        }
        val steps = root["steps"] as? JsonArray ?: return "steps 必须是数组"
        return checkSteps(steps, "steps", 0)
    }

    private fun checkSteps(steps: JsonArray, path: String, depth: Int): String? {
        if (depth > 32) return "步骤嵌套过深，最多支持 32 层"
        val seen = mutableSetOf<String>()
        steps.forEachIndexed { index, value ->
            val location = "$path[$index]"
            val step = value as? JsonObject ?: return "$location 必须是对象"
            val id = step["id"] as? JsonPrimitive
            if (id == null || id.content.isBlank() || (!id.isString && id.doubleOrNull?.isFinite() != true)) {
                return "$location.id 必须是非空字符串或数字"
            }
            if (!seen.add(id.content)) return "$path 中存在重复的步骤 id：${id.content.take(40)}"
            val type = step["type"] as? JsonPrimitive
            if (type == null || !type.isString || type.content.isBlank()) {
                return "$location.type 必须是非空字符串"
            }
            for (branch in listOf("steps", "then_steps", "else_steps")) {
                if (branch !in step) continue
                val children = step[branch] as? JsonArray ?: return "$location.$branch 必须是数组"
                checkSteps(children, "$location.$branch", depth + 1)?.let { return it }
            }
        }
        return null
    }
}
