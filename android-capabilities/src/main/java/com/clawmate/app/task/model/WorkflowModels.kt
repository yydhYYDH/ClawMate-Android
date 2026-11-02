package com.clawmate.app.task.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Android workflow domain models, ported from the HarmonyOS contract
 * `utils/WorkflowTypes.ets` (reference only — see /root/mobiinfra-oh). Plain
 * Kotlin data classes backed by the tolerant JSON reader (`task/json`) so
 * unrelated or malformed fields never crash loading.
 */

interface WorkflowMetadata {
    val name: String?
    val description: String?
}

interface WorkflowDefaults {
    val device: String?
    val useQwen3: Boolean?
    val useE2e: Boolean?
    val useExperience: Boolean?
    val acceptPlannerChanges: Boolean?
}

interface WorkflowCondition {
    val left: JsonElement?
    val operator: String?
    val right: JsonElement?
}

/**
 * A parsed workflow step. Unknown step types are preserved verbatim in [raw]
 * so that a later, more capable release (or a different runner) can still
 * interpret them — the Android slice never silently drops a step it cannot
 * execute; it reports it as unsupported.
 */
data class WorkflowStep(
    val id: String,
    val type: String,
    val name: String? = null,
    val onError: String? = null,
    val taskDescription: String? = null,
    val action: String? = null,
    val seconds: Long? = null,
    val times: Long? = null,
    val toolName: String? = null,
    val maxSteps: Long? = null,
    /** Nested steps for `if` / `loop` containers. */
    val steps: List<WorkflowStep> = emptyList(),
    val thenSteps: List<WorkflowStep> = emptyList(),
    val elseSteps: List<WorkflowStep> = emptyList(),
    val condition: WorkflowCondition? = null,
    /** Original JSON for faithful serialization / unknown-field preservation. */
    val raw: JsonObject,
) {
    val escapedName: String get() = name?.takeIf { it.isNotBlank() } ?: type
}

/** A parsed workflow definition (metadata + defaults + context + steps). */
data class WorkflowDefinition(
    val metadata: WorkflowMetadata? = null,
    val defaults: WorkflowDefaults? = null,
    val context: JsonObject = JsonObject(emptyMap()),
    val steps: List<WorkflowStep> = emptyList(),
    /** Raw source text (unmodified user/bundled JSON) for faithful round-trips. */
    val rawText: String = "",
) {
    val title: String
        get() = metadata?.name?.takeIf { it.isNotBlank() } ?: "workflow"
}

/**
 * A task in the task index — the user-facing card. Mirrors HarmonyOS
 * `WorkflowTask`. `configFileName` points at the editable workflow JSON in
 * app-private storage; `enabled` and last-run fields are persisted locally.
 */
data class WorkflowTask(
    val id: String,
    val sceneId: Int,
    val icon: String,
    val iconColor: String,
    val title: String,
    val source: String,
    val enabled: Boolean,
    val configFileName: String,
    val lastRunStatus: String,
    val lastRun: String,
    val lastMessage: String,
    val isUserModified: Boolean = false,
    val useEmbeddedWorkflowJson: Boolean = false,
)
