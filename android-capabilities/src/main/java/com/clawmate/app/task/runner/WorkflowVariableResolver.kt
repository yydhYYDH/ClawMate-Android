package com.clawmate.app.task.runner

import com.clawmate.app.task.model.WorkflowDefinition
import com.clawmate.app.task.model.WorkflowStep
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray

/**
 * Typed result contract for a single workflow step, mirroring the source
 * `WorkflowStepResult` (status/output/error) while adding an honest
 * `blocked` kind so GUI/HDC/device-control steps are never faked as success.
 */
sealed class StepOutcome {
    /** Step ran deterministically and produced [output]. */
    data class Success(
        val output: JsonObject = JsonObject(emptyMap()),
    ) : StepOutcome()

    /** Step requires GUI/HDC/device-control the Android slice cannot perform. */
    data class Unsupported(val reason: String) : StepOutcome()

    /** Step needs a cloud model / tool that is not configured on this device. */
    data class NotConfigured(val reason: String) : StepOutcome()

    data class Failure(val message: String) : StepOutcome()

    /** Container (if/loop) finished without a leaf side-effect. */
    data class Container(val note: String = "") : StepOutcome()
}

/** Typed overall run status. A run is only SUCCESS if every leaf ran. */
enum class RunStatus { SUCCESS, BLOCKED, NOT_CONFIGURED, CANCELLED, FAILED }

data class StepResult(
    val stepId: String,
    val type: String,
    val status: RunStatus,
    val note: String,           // short description / reason
    val detail: String = "",    // longer reason text for blocked steps
    val output: JsonObject? = null,
)

/** Immutable user-facing result of a workflow run. */
data class WorkflowRunResult(
    val status: RunStatus,
    val message: String,
    val steps: List<StepResult> = emptyList(),
    /**
     * Bounded real structured summary text the run produced (Phase 41). Empty
     * unless the workflow exposed a real output step; never fabricated from bare
     * echo/failure/debug output. Mapped from real workflow output so the writer
     * never invents a daily-log entry.
     */
    val resultSummary: String = "",
    /** Absolute daily-log file written (app-private base + relative tree), or
     * empty when the run produced no daily-log entry (no summary, failed,
     * blocked, not-configured, or cancelled). */
    val dailyLogPath: String = "",
)

/**
 * Deterministic resolver for `${...}` placeholder expressions found in step
 * inputs / prompt strings. Supports:
 *   - `${steps.<id>.output.<field>}`        (previous step output lookup)
 *   - `${context.<key>}`                    (workflow context map)
 *   - `${loop.index}` / `${loop.last}`      (loop iteration vars)
 *   - `${defaults.<key>}`                   (workflow defaults)
 *
 * Unknown references resolve to the literal expression (never dangling), and
 * all lookups are JVM timezone-independent.
 */
object WorkflowVariableResolver {

    private val EXPR = Regex("\\$\\{([^}]+)\\}")

    fun resolve(text: String, lookup: (String) -> JsonElement?): String {
        return EXPR.replace(text) { m ->
            val expr = m.groupValues[1].trim()
            val value = lookup(expr)
            when (value) {
                null -> m.value
                else -> value.toText()
            }
        }
    }

    /** Evaluates a single expression like `steps.1.output.image_b64`. */
    fun lookup(
        stepOutputs: Map<String, JsonObject>,
        context: JsonObject,
        defaults: JsonObject,
        loopIndex: Int,
        loopLast: Boolean,
        expr: String,
    ): JsonElement? {
        val parts = expr.split(".")
        return when {
            expr == "loop.index" -> JsonPrimitive(loopIndex)
            expr == "loop.last" -> JsonPrimitive(loopLast)
            expr.startsWith("context") -> {
                val key = parts.drop(1).joinToString(".")
                context[key]
            }
            expr.startsWith("defaults") -> {
                val key = parts.drop(1).joinToString(".")
                defaults[key]
            }
            expr.startsWith("steps") -> {
                // steps.<id>.output.<field>
                val stepId = parts.getOrNull(1) ?: return null
                val out = stepOutputs[stepId] ?: return null
                if (parts.getOrNull(2) == "output") {
                    val field = parts.drop(3).joinToString(".")
                    out[field] ?: parts.drop(3).fold(out as JsonElement?) { current, key -> (current as? JsonObject)?.get(key) }
                } else null
            }
            else -> null
        }
    }

    private fun JsonElement.toText(): String = when (this) {
        is JsonNull -> ""
        is JsonPrimitive -> contentOrNull ?: toString()
        else -> toString()
    }
}
