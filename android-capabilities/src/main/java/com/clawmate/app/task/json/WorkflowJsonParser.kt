package com.clawmate.app.task.json

import com.clawmate.app.task.model.WorkflowCondition
import com.clawmate.app.task.model.WorkflowDefaults
import com.clawmate.app.task.model.WorkflowDefinition
import com.clawmate.app.task.model.WorkflowMetadata
import com.clawmate.app.task.model.WorkflowStep
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Tolerant, deterministic parser for the workflow config JSON contract
 * (`metadata` / `defaults` / `context` / `steps`) described in
 * `utils/WorkflowTypes.ets`. It parses nested `if` / `loop` / `steps`
 * structures and preserves unknown or unsupported step types verbatim in
 * [WorkflowStep.raw] so they can be honestly surfaced as unsupported by the
 * runner rather than silently dropped.
 *
 * Original JSON is parsed first; [WorkflowJsonRepair] is only a fallback for
 * malformed input. A single corrupt config card must never crash the whole
 * Task screen, so deeply broken input degrades to an [Err] carrying a message.
 */
object WorkflowJsonParser {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowSpecialFloatingPointValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    /** Parse outcome: either a definition or a tolerant error message. */
    sealed class Result {
        data class Ok(val definition: WorkflowDefinition) : Result()
        data class Err(val message: String) : Result()
    }

    /** Parses raw workflow JSON text, tolerating malformed input. */
    fun parse(rawText: String): Result {
        val root: JsonElement? = try {
            // Preserve the existing uppercase-literal tolerance. This helper
            // never touches quoted data; valid JSON remains byte-for-byte text.
            json.parseToJsonElement(WorkflowJsonRepair.repairJsonLiteralCasing(rawText.trim()))
        } catch (_: Exception) {
            try {
                json.parseToJsonElement(WorkflowJsonRepair.normalizeJsonText(rawText))
            } catch (t: Exception) {
                return Result.Err("invalid JSON: ${t.message?.take(160) ?: "parse failure"}")
            }
        }
        val obj = root as? JsonObject
            ?: return Result.Err("workflow root must be a JSON object")
        return try {
            Result.Ok(parseDefinition(obj, rawText))
        } catch (t: Throwable) {
            Result.Err("malformed workflow: ${t.message?.take(160) ?: "unknown"}")
        }
    }

    fun parseOrDefault(rawText: String): WorkflowDefinition =
        when (val r = parse(rawText)) {
            is Result.Ok -> r.definition
            is Result.Err -> WorkflowDefinition(steps = emptyList(), rawText = rawText)
        }

    private fun parseDefinition(obj: JsonObject, rawText: String): WorkflowDefinition {
        val metadata = parseMetadata(obj["metadata"] as? JsonObject)
        val defaults = parseDefaults(obj["defaults"] as? JsonObject)
        val context = obj["context"] as? JsonObject ?: JsonObject(emptyMap())
        val steps = parseSteps(obj["steps"] as? JsonArray ?: JsonArray(emptyList()))
        return WorkflowDefinition(metadata, defaults, context, steps, rawText)
    }

    private fun parseMetadata(o: JsonObject?): WorkflowMetadata? {
        if (o == null) return null
        return MetadataImpl(
            name = o["name"]?.primitiveValue(),
            description = o["description"]?.primitiveValue(),
        )
    }

    private fun parseDefaults(o: JsonObject?): WorkflowDefaults? {
        if (o == null) return null
        return DefaultsImpl(
            device = o["device"]?.primitiveValue(),
            useQwen3 = o["use_qwen3"]?.let { it.primitiveOrNull()?.booleanOrNull },
            useE2e = o["use_e2e"]?.let { it.primitiveOrNull()?.booleanOrNull },
            useExperience = o["use_experience"]?.let { it.primitiveOrNull()?.booleanOrNull },
            acceptPlannerChanges = o["accept_planner_changes"]?.let { it.primitiveOrNull()?.booleanOrNull },
        )
    }

    /** Recursively parses a flat `steps` array including `if`/`loop` nesting. */
    fun parseSteps(array: JsonArray): List<WorkflowStep> = array.mapNotNull { parseStep(it) }

    private fun parseStep(element: JsonElement): WorkflowStep? {
        val obj = element as? JsonObject ?: return null
        val type = obj["type"]?.primitiveValue() ?: return null
        val id = obj["id"].toStepId()
        val nested = parseSteps(obj["steps"] as? JsonArray ?: JsonArray(emptyList()))
        val thenSteps = parseSteps(obj["then_steps"] as? JsonArray ?: JsonArray(emptyList()))
        val elseSteps = parseSteps(obj["else_steps"] as? JsonArray ?: JsonArray(emptyList()))
        val condition = parseCondition(obj["condition"])

        return WorkflowStep(
            id = id,
            type = type,
            name = obj["name"]?.primitiveValue(),
            onError = obj["on_error"]?.primitiveValue(),
            taskDescription = obj["task_description"]?.primitiveValue(),
            action = obj["action"]?.primitiveValue(),
            seconds = obj["seconds"]?.let { it.primitiveOrNull()?.longOrNull },
            times = obj["times"].asLong(),
            toolName = obj["tool_name"]?.primitiveValue(),
            maxSteps = obj["max_steps"].asLong(),
            steps = nested,
            thenSteps = thenSteps,
            elseSteps = elseSteps,
            condition = condition,
            raw = obj,
        )
    }

    private fun parseCondition(element: JsonElement?): WorkflowCondition? {
        val obj = element as? JsonObject ?: return null
        return ConditionImpl(left = obj["left"], operator = obj["operator"]?.primitiveValue(), right = obj["right"])
    }

    private fun JsonElement?.primitiveValue(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
    private fun JsonElement?.asLong(): Long? = (this as? JsonPrimitive)?.let {
        it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong()
    }

    private fun JsonElement?.toStepId(): String =
        (this as? JsonPrimitive)?.contentOrNull ?: "0"

    // --- small data impls for the interface contracts ---
    private data class MetadataImpl(override val name: String?, override val description: String?) : WorkflowMetadata
    private data class DefaultsImpl(
        override val device: String?,
        override val useQwen3: Boolean?,
        override val useE2e: Boolean?,
        override val useExperience: Boolean?,
        override val acceptPlannerChanges: Boolean?,
    ) : WorkflowDefaults

    private data class ConditionImpl(
        override val left: JsonElement?,
        override val operator: String?,
        override val right: JsonElement?,
    ) : WorkflowCondition
}
