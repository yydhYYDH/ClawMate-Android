package com.clawmate.app.task.runner

import com.clawmate.app.datamanager.DailyLogWorkspace
import com.clawmate.app.task.model.WorkflowDefinition
import com.clawmate.app.task.model.WorkflowStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * A bounded, honest workflow runner for the Android Task slice.
 *
 * Only deterministic, non-GUI step types execute for real:
 *   - `sleep` -> real bounded delay (via an injectable [WorkflowRunnerImpl.StepClock]
 *                so unit tests do not wait);
 *   - `loop`  -> bounded iteration over nested steps with `loop.index`/`loop.last`;
 *   - `if`    -> deterministic condition evaluation driving then/else branches;
 *   - `tool`  -> only a deterministic `echo` no-op and a real `summary` output
 *                step; `vlm_qa` uses an optional host model port. Without it,
 *                model steps remain NOT_CONFIGURED.
 *
 * Every GUI / HDC / open-app / device-control step (`gui_task`, and `gui_action`
 * screenshot / swipe / click) is *never* executed and *never* fabricated: it is
 * reported as [StepOutcome.Unsupported] with a clear reason, and the overall run
 * becomes [RunStatus.BLOCKED]. A VLM `tool` step becomes [RunStatus.NOT_CONFIGURED].
 * The run therefore stays honest when blocked.
 *
 * Phase 41 (daily-log data source): when a run **succeeds** and the workflow
 * explicitly exposed a real `summary` output step (real text from its own
 * inputs/context — never inferred from bare `echo`), the runner appends that real,
 * bounded summary to the existing [DailyLogWorkspace] single writer as a numbered
 * entry under `filesDir/workflows/daily-log/YYYY-MM-DD/`, so
 * [com.clawmate.app.datamanager.DailyLogParser] /
 * [com.clawmate.app.datamanager.DailyLogEventSource] consume it. Ordinary runs
 * with no summary step, failed / UNSUPPORTED / NOT_CONFIGURED / cancelled runs
 * write **nothing** (honest — no fabricated daily-log).
 *
 * Bounds: total step budget, per-loop iteration cap, and a wall-clock timeout are
 * enforced; coroutine cancellation surfaces as [RunStatus.CANCELLED].
 */
interface WorkflowRunner {
    suspend fun run(definition: WorkflowDefinition): WorkflowRunResult
}

/**
 * Minimal daily-log write boundary the runner needs (Phase 41). Defaults to a
 * real file-backed writer over the app-private filesDir; tests inject a spy.
 */
fun interface WorkflowDailyLogSink {
    /** Append the real summary [text] for [day] as a numbered daily-log entry.
     * @return the absolute path written (handed back on the run result), or null
     *   when nothing was written (invalid/bounded out). */
    fun append(day: String, fileName: String, text: String, metadata: Map<String, String>): String?
}

/** File-backed [WorkflowDailyLogSink] over the app-private base dir. */
class FileWorkflowDailyLogSink(private val baseDir: File) : WorkflowDailyLogSink {
    override fun append(
        day: String,
        fileName: String,
        text: String,
        metadata: Map<String, String>,
    ): String? = DailyLogWorkspace.appendEntry(
        baseDir = baseDir,
        day = day,
        fileName = fileName,
        entryText = text,
        workflowMetadata = metadata,
    )?.absolutePath
}

class WorkflowRunnerImpl(
    private val clock: StepClock = RealStepClock,
    private val maxTotalSteps: Int = 200,
    private val maxLoopIterations: Int = 100,
    private val maxMillis: Long = 60_000L,
    /**
     * Optional device-control boundary. The default [DisabledWorkflowDeviceAdapter]
     * keeps every GUI/HDC step UNSUPPORTED/BLOCKED. A caller may supply a
     * configured bridge-backed adapter so `gui_action` steps return honest
     * typed bridge errors/results; the runner never fabricates device control
     * and only checks `isConfigured` at run time.
     */
    private val deviceAdapter: com.clawmate.app.task.hdc.WorkflowDeviceAdapter =
        com.clawmate.app.task.hdc.DisabledWorkflowDeviceAdapter,
    /**
     * App-private files dir enabling the real file-backed daily-log writer
     * (Phase 41). Null = no daily-log write (runner stays pure/callable without
     * storage). Production wires `context.applicationContext.filesDir`.
     */
    private val baseDir: File? = null,
    /**
     * Overridable daily-log write boundary. Defaults to [FileWorkflowDailyLogSink]
     * bound to [baseDir] when [baseDir] is provided, else a no-op.
     */
    private val dailyLogSink: WorkflowDailyLogSink? = null,
    /** Date provider for the `YYYY-MM-DD` run day (injectable for tests). */
    private val runDateProvider: () -> String = { LazyIsoDate.now() },
    private val modelTool: WorkflowModelTool? = null,
) : WorkflowRunner {

    /** Injectable time source so tests avoid real delays. */
    fun interface StepClock {
        suspend fun sleep(millis: Long): Unit
    }

    private class Session(
        val context: JsonObject,
        val defaults: JsonObject,
        val results: MutableList<StepResult> = mutableListOf(),
        val stepOutputs: MutableMap<String, JsonObject> = mutableMapOf(),
        var stepCount: Int = 0,
        var blocked: RunStatus? = null,
        var blockedMessage: String = "",
        /** Phase 41: real structured summary output exposed by the workflow. */
        var resultSummary: String = "",
          var loopIndex: Int = 0,
          var loopLast: Boolean = false,
        val summaryStepId: MutableList<String> = mutableListOf(),
    )

    private val sink: WorkflowDailyLogSink =
        dailyLogSink ?: (baseDir?.let { FileWorkflowDailyLogSink(it) } ?: NoOpDailyLogSink)

    override suspend fun run(definition: WorkflowDefinition): WorkflowRunResult = coroutineScope {
        val deadline = if (maxMillis > 0) nowNanos() + maxMillis * 1_000_000L else Long.MAX_VALUE
        try {
            val session = Session(
                context = definition.context,
                defaults = definition.defaults.toJson(),
            )
            for (step in definition.steps) {
                if (session.blocked == null) executeStep(session, step, deadline)
            }
            val status = session.blocked ?: RunStatus.SUCCESS
            val message = when (status) {
                RunStatus.SUCCESS -> "Workflow completed successfully"
                RunStatus.BLOCKED -> session.blockedMessage
                RunStatus.NOT_CONFIGURED -> session.blockedMessage
                RunStatus.CANCELLED -> "Workflow cancelled"
                RunStatus.FAILED -> session.blockedMessage
            }
            // Phase 41: only a genuinely successful run with a real structured
            // summary output writes a daily-log entry. Failed / UNSUPPORTED /
            // NOT_CONFIGURED / cancelled runs write nothing (no fabricated log).
            val summary = session.resultSummary.trim()
            val dailyLogPath = if (status == RunStatus.SUCCESS && summary.isNotEmpty()) {
                writeDailyLog(definition, session)
            } else {
                ""
            }
            WorkflowRunResult(
                status = status,
                message = message,
                steps = session.results.toList(),
                resultSummary = summary,
                dailyLogPath = dailyLogPath.orEmpty(),
            )
        } catch (c: CancellationException) {
            // Cooperative cancellation: surface an honest CANCELLED result.
            WorkflowRunResult(status = RunStatus.CANCELLED, message = "Workflow cancelled", steps = emptyList())
        }
    }

    /**
     * Append the real summary output as a numbered daily-log entry through the
     * single [DailyLogWorkspace] writer. Real, bounded metadata (name/description
     * from the workflow definition) preserve provenance so downstream
     * consolidation/enrichment/Home see genuine workflow input. Returns the
     * absolute written path, or null when the writer refused (bounds/IO).
     */
    private fun writeDailyLog(definition: WorkflowDefinition, session: Session): String? {
        val day = runDateProvider()
        val title = definition.title
        val fileName = dailyLogFileName(title)
        val metadata = buildMap {
            definition.metadata?.name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
            definition.metadata?.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
            // scene id is not carried by WorkflowDefinition; keep honest real fields only.
        }
        return sink.append(day, fileName, session.resultSummary, metadata)
    }

    private suspend fun executeStep(
        session: Session,
        step: WorkflowStep,
        deadlineNanos: Long,
    ) {
        if (nowNanos() >= deadlineNanos) {
            block(session, RunStatus.FAILED, "max-seconds timeout exceeded")
            session.results += StepResult(step.id, step.type, RunStatus.FAILED, "timeout")
            return
        }
        if (!ensureBudget(session, step, "top-level step")) return
        val outcome = execute(session, step, deadlineNanos)
        applyOutcome(session, step, outcome)
    }

    private suspend fun execute(session: Session, step: WorkflowStep, deadlineNanos: Long): StepOutcome {
        return when (step.type) {
            // A gui_task is a full screenshot+VLM "decider" workflow (the source
            // drives screenshots + execute_decider_action iteratively). The safe
            // adapter surface covers only discrete protocol calls, so the macro
            // task stays honestly UNSUPPORTED; individual gui_action steps below
            // CAN route through a configured bridge adapter.
            "gui_task" -> StepOutcome.Unsupported(
                "GUI task automation (${step.taskDescription ?: step.name ?: ""}) requires a screenshot-driven VLM decider loop, which the safe device adapter does not orchestrate; only discrete gui_action protocol calls are exposed."
            )
            "gui_action" -> executeGuiAction(step, deadlineNanos)
            "tool" -> executeTool(session, step, deadlineNanos)
            "loop" -> executeLoop(session, step, deadlineNanos)
            "if" -> executeIf(session, step, deadlineNanos)
            else -> StepOutcome.Unsupported("unknown step type '${step.type}' cannot be executed by this bounded runner.")
        }
    }

    private suspend fun executeGuiAction(step: WorkflowStep, deadlineNanos: Long): StepOutcome {
        val action = step.action ?: return StepOutcome.Unsupported("gui_action without an action field.")
        return when (action) {
            // `sleep` is a deterministic non-GUI op: bounded real delay.
            "sleep" -> {
                val millis = (step.seconds ?: 1L).coerceAtLeast(0L) * 1000L
                awaitDeadlineSleep(millis, deadlineNanos)
                StepOutcome.Success(JsonObject(emptyMap()))
            }
            else -> executeConfiguredGui(step, action)
        }
    }

    /**
     * Routes a device-control gui_action through [deviceAdapter] — but only when
     * the adapter is actually configured (health-verified). With the default
     * disabled adapter this remains UNSUPPORTED/BLOCKED; a configured
     * bridge-backed adapter returns honest bridge errors/results. Never executes
     * a command locally and never fabricates success.
     */
    private suspend fun executeConfiguredGui(step: WorkflowStep, action: String): StepOutcome {
        if (!deviceAdapter.isConfigured) {
            return StepOutcome.Unsupported(
                "gui_action '$action' is a device-control step that requires a configured PC HDC bridge adapter; none is configured on Android."
            )
        }
        val payload = step.raw.filterKeys { it != "id" && it != "type" && it != "action" }
        val factor = ((step.raw["factor"] as? JsonPrimitive)?.floatOrNull)?.toDouble()
            ?: step.raw["factor"]?.toString()?.toDoubleOrNull()
        val result = try {
            when (action) {
                "screenshot" -> deviceAdapter.screenshot(factor ?: 0.5)
                "app_start" -> deviceAdapter.startApp(
                    appName = stringOf(step.raw["app_name"]),
                    packageName = stringOf(step.raw["package_name"]),
                    resetFirst = boolOf(step.raw["reset_first"]) ?: true,
                )
                else -> deviceAdapter.guiAction(payload)
            }
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            return StepOutcome.Failure("gui_action '$action' failed locally: ${t.message}")
        }
        return when (result) {
            is com.clawmate.app.task.hdc.HdcActionResult.Success -> {
                StepOutcome.Success(bridgeOutput(result.response))
            }
            is com.clawmate.app.task.hdc.HdcActionResult.BridgeError -> {
                val reason = result.response.message?.takeIf { it.isNotBlank() }
                    ?: "bridge reported ${result.response.status}"
                StepOutcome.Failure("gui_action '$action' bridge error: $reason")
            }
            is com.clawmate.app.task.hdc.HdcActionResult.TransportError -> {
                StepOutcome.Failure("gui_action '$action' transport ${result.kind.name.lowercase()}: ${result.message}")
            }
            is com.clawmate.app.task.hdc.HdcActionResult.Unsupported -> StepOutcome.Unsupported(result.reason)
        }
    }

    private fun stringOf(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

    private fun boolOf(element: JsonElement?): Boolean? {
        val prim = element as? JsonPrimitive ?: return null
        val b = prim.booleanOrNull
        if (b != null) return b
        return when (prim.contentOrNull?.trim()?.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    private fun bridgeOutput(response: com.clawmate.app.task.hdc.HdcBridgeResponse): JsonObject =
        buildJsonObject {
            put("status", response.status)
            response.message?.let { put("message", it) }
            response.result?.let { put("result", it) }
            response.imageB64?.let { put("image_b64", it) }
            response.action?.let { put("action", it) }
            response.packageName?.let { put("package_name", it) }
            response.currentPackageName?.let { put("current_package_name", it) }
            response.width?.let { put("width", JsonPrimitive(it)) }
            response.height?.let { put("height", JsonPrimitive(it)) }
        }

    private suspend fun executeTool(session: Session, step: WorkflowStep, deadlineNanos: Long): StepOutcome {
        val toolName = step.toolName ?: return StepOutcome.Unsupported("tool step without tool_name.")
        return when (toolName) {
            // Deterministic no-op used to exercise the runner in tests.
            "echo" -> StepOutcome.Success(buildJsonObject { put("echo", step.raw["inputs"]?.toString() ?: "") })
            // Phase 41: a workflow may expose a real structured summary output step.
            // The text is resolved from the workflow's own inputs/context (real
            // content, resolved over real context/step outputs) — never fabricated
            // and never inferred from bare `echo`. A blank/missing text keeps the
            // summary empty so nothing is written.
            "summary" -> executeSummaryTool(session, step)
            "vlm_qa" -> executeModelTool(session, step, deadlineNanos)
            else -> StepOutcome.NotConfigured(
                "tool '$toolName' requires a cloud VLM/model that is not configured on this device; GUI instrumentation is unavailable."
            )
        }
    }

    private suspend fun executeModelTool(session: Session, step: WorkflowStep, deadline: Long): StepOutcome {
        val port = modelTool ?: return StepOutcome.NotConfigured("vlm_qa未配置；请使用共享版并在发送前确认视觉模型及图片")
        return try {
            val inputs = step.raw["inputs"] as? JsonObject ?: error("vlm_qa需要inputs")
            require(inputs.keys.all { it in setOf("mode", "question", "image", "json_schema", "blocking") }) { "vlm_qa包含不支持的输入字段" }
            fun text(key: String, fallback: String = ""): String {
                val raw = inputs[key] ?: return fallback
                require(raw is JsonPrimitive && raw.isString) { "vlm_qa的${key}必须是文本" }
                val value = WorkflowVariableResolver.resolve(raw.content) { expression ->
                    WorkflowVariableResolver.lookup(session.stepOutputs, session.context, session.defaults, session.loopIndex, session.loopLast, expression)
                }.trim()
                require(!value.contains("\u0024{")) { "vlm_qa的${key}存在未解析变量" }
                return value
            }
            val mode = text("mode", "answer")
            val question = text("question")
            val image = text("image")
            require(mode in setOf("answer", "summary")) { "vlm_qa仅支持answer和summary" }
            require(question.length in 1..8000 && image.length in 1..2000) { "vlm_qa需要有界question及本机图片引用" }
            val schema = inputs["json_schema"]?.let { require(it is JsonObject && it.toString().length <= 8000) { "json_schema必须是有界JSON对象" }; it }
            val remaining = ((deadline - nowNanos()) / 1_000_000L).coerceAtMost(60_000)
            require(remaining > 0) { "工作流已超时" }
            val response = withTimeout(remaining) { port.evaluate(WorkflowModelRequest("", mode, question, image, schema)) }.trim()
            require(response.length in 1..16000) { "模型返回为空或超出限制" }
            val structured = if (schema == null) null else (Json.parseToJsonElement(response) as? JsonObject
                ?: error("模型未返回要求的JSON对象"))
            if (mode == "summary") {
                val summary = (structured?.get("summary") as? JsonPrimitive)?.contentOrNull ?: response
                session.resultSummary = (session.resultSummary + "\n" + summary).trim().take(2000)
            }
            StepOutcome.Success(buildJsonObject {
                put("tool_name", "vlm_qa"); put("mode", mode); put("question", question); put("response", response)
                structured?.let { put("structured_output", it) }
            })
        } catch (timeout: TimeoutCancellationException) { StepOutcome.Failure("视觉问答超过工作流时间上限") }
          catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { StepOutcome.Failure(error.message ?: "视觉模型调用失败") }
    }

    /** Resolve the real summary text of an explicit `summary` tool step. */
    private fun executeSummaryTool(session: Session, step: WorkflowStep): StepOutcome {
        val inputs = step.raw["inputs"] as? JsonObject ?: JsonObject(emptyMap())
        val template = stringOf(inputs["text"])
            ?: stringOf(inputs["summary"])
            ?: stringOf(inputs["value"])
            ?: return StepOutcome.Success(buildJsonObject { put("summary", "") })
        val resolved = WorkflowVariableResolver.resolve(template) { expr ->
            WorkflowVariableResolver.lookup(
                session.stepOutputs, session.context, session.defaults, 0, false, expr,
            )
        }
        // Real output only: reject blanks. Bound to the daily-log entry cap.
        val bounded = resolved.trim().take(com.clawmate.app.datamanager.DailyLogBounds.MAX_ENTRY_CHARACTERS)
        if (bounded.isEmpty()) {
            return StepOutcome.Success(buildJsonObject { put("summary", "") })
        }
        session.resultSummary = bounded
        return StepOutcome.Success(buildJsonObject { put("summary", bounded) })
    }

    private suspend fun executeLoop(
        session: Session,
        step: WorkflowStep,
        deadlineNanos: Long,
    ): StepOutcome {
        val times = step.times ?: 1
        if (times <= 0) return StepOutcome.Container("loop skipped (times<=0)")
        if (times > maxLoopIterations.toLong()) {
            return StepOutcome.Failure("loop times=$times exceeds per-loop bound of $maxLoopIterations")
        }
        for (i in 0 until times.toInt()) {
            if (session.blocked != null) return StepOutcome.Container("stopped")
            val loopLast = (i == times.toInt() - 1)
            for (sub in step.steps) {
                if (session.blocked != null) return StepOutcome.Container("stopped")
                if (!ensureBudget(session, sub, "loop step")) return StepOutcome.Container("budget")
                val previousIndex = session.loopIndex; val previousLast = session.loopLast
                session.loopIndex = i + 1; session.loopLast = loopLast
                val out = try { execute(session, sub, deadlineNanos) }
                    finally { session.loopIndex = previousIndex; session.loopLast = previousLast }
                when (out) {
                    is StepOutcome.Success -> {
                        val resolved = if (sub.toolName == "vlm_qa") out.output else out.output.resolve(session, loopIndex = i + 1, loopLast = loopLast)
                        session.stepOutputs["${step.id}.${i + 1}"] = resolved
                        if (sub.toolName == "vlm_qa") session.stepOutputs[sub.id] = resolved
                        session.results += StepResult(sub.id, sub.type, RunStatus.SUCCESS, sub.escapedName, output = if (sub.toolName == "vlm_qa") resolved else null)
                    }
                    is StepOutcome.Container -> { /* walked itself */ }
                    else -> {
                        applyOutcome(session, sub, out)
                        return StepOutcome.Container("stopped on sub-step")
                    }
                }
            }
        }
        return StepOutcome.Container("loop completed $times iterations")
    }

    private suspend fun executeIf(
        session: Session,
        step: WorkflowStep,
        deadlineNanos: Long,
    ): StepOutcome {
        val cond = step.condition ?: return StepOutcome.Container("if without condition")
        val ok = try {
            ConditionEvaluator.evaluate(cond.left, cond.operator, cond.right, session.context, session.defaults)
        } catch (t: Throwable) {
            return StepOutcome.Failure("if condition error: ${t.message}")
        }
        val branch = if (ok) step.thenSteps else step.elseSteps
        for (sub in branch) {
            if (session.blocked != null) return StepOutcome.Container("stopped")
            if (!ensureBudget(session, sub, "if branch step")) return StepOutcome.Container("budget")
            val out = execute(session, sub, deadlineNanos)
            when (out) {
                is StepOutcome.Success -> {
                    session.stepOutputs[sub.id] = out.output.resolve(session, 0, false)
                    session.results += StepResult(sub.id, sub.type, RunStatus.SUCCESS, sub.escapedName)
                }
                is StepOutcome.Container -> { }
                else -> {
                    applyOutcome(session, sub, out)
                    return StepOutcome.Container("stopped on sub-step")
                }
            }
        }
        return StepOutcome.Container("if branch: ${if (ok) "then" else "else"}")
    }

    private suspend fun awaitDeadlineSleep(millis: Long, deadlineNanos: Long) {
        val remaining = deadlineNanos - nowNanos()
        val effective = if (remaining <= 0) 0L else minOf(millis, remaining / 1_000_000L)
        if (effective > 0) clock.sleep(effective)
        // Leaving actual wall-clock timeout enforcement to the top-level deadline check.
    }

    private fun applyOutcome(session: Session, step: WorkflowStep, outcome: StepOutcome) {
        when (outcome) {
            is StepOutcome.Success -> {
                session.stepOutputs[step.id] = outcome.output
                session.results += StepResult(step.id, step.type, RunStatus.SUCCESS, step.escapedName, output = if (step.toolName == "vlm_qa") outcome.output else null)
            }
            is StepOutcome.Container -> { /* containers report via their internal branch results */ }
            is StepOutcome.Unsupported -> {
                block(session, RunStatus.BLOCKED, "step ${step.id} unsupported: ${outcome.reason}")
                session.results += StepResult(step.id, step.type, RunStatus.BLOCKED, "unsupported", outcome.reason)
            }
            is StepOutcome.NotConfigured -> {
                block(session, RunStatus.NOT_CONFIGURED, "step ${step.id} not configured: ${outcome.reason}")
                session.results += StepResult(step.id, step.type, RunStatus.NOT_CONFIGURED, "not configured", outcome.reason)
            }
            is StepOutcome.Failure -> {
                block(session, RunStatus.FAILED, "step ${step.id} failed: ${outcome.message}")
                session.results += StepResult(step.id, step.type, RunStatus.FAILED, "failed", outcome.message)
            }
        }
    }

    private fun block(session: Session, status: RunStatus, message: String) {
        if (session.blocked == null) {
            session.blocked = status
            session.blockedMessage = message
        }
    }

    /** Returns false (and blocks) when the global budget/deadline is exceeded. */
    private fun ensureBudget(session: Session, step: WorkflowStep, label: String): Boolean {
        session.stepCount++
        if (session.stepCount > maxTotalSteps) {
            block(session, RunStatus.FAILED, "max total-step budget of $maxTotalSteps exceeded ($label)")
            session.results += StepResult(step.id, step.type, RunStatus.FAILED, "max step budget", detail = label)
            return false
        }
        return true
    }

    private fun nowNanos(): Long = System.nanoTime()

    private fun JsonObject.resolve(session: Session, loopIndex: Int, loopLast: Boolean): JsonObject =
        buildJsonObject {
            for ((k, v) in this@resolve.entries) {
                val resolved = WorkflowVariableResolver.resolve(v.toString()) { expr ->
                    WorkflowVariableResolver.lookup(
                        session.stepOutputs, session.context, session.defaults, loopIndex, loopLast, expr
                    )
                }
                put(k, resolved)
            }
        }

    private fun com.clawmate.app.task.model.WorkflowDefaults?.toJson(): JsonObject = buildJsonObject {
        this@toJson?.device?.let { put("device", JsonPrimitive(it)) }
        this@toJson?.useQwen3?.let { put("use_qwen3", JsonPrimitive(it)) }
        this@toJson?.useE2e?.let { put("use_e2e", JsonPrimitive(it)) }
        this@toJson?.useExperience?.let { put("use_experience", JsonPrimitive(it)) }
        this@toJson?.acceptPlannerChanges?.let { put("accept_planner_changes", JsonPrimitive(it)) }
    }

    /**
     * Deterministic, repeat-run-friendly daily-log file name (mirrors the Harmony
     * `buildDailyLogFileName` shape but without a GUI package). A stable name means
     * repeat runs of the same workflow on the same day **append** (via the
     * numbered-entry index) instead of creating unbounded new files.
     */
    private fun dailyLogFileName(title: String): String {
        val safe = sanitizeFileName(title.ifBlank { "workflow" })
        return "$safe.md"
    }

    private fun sanitizeFileName(value: String): String =
        value.replace("[^A-Za-z0-9._-]".toRegex(), "_").take(48).ifBlank { "workflow" }
}


/** Default clock that really sleeps for the given millis. */
object RealStepClock : WorkflowRunnerImpl.StepClock {
    override suspend fun sleep(millis: Long): Unit {
        if (millis > 0) delay(millis)
    }
}

/** No-op daily-log sink used when no baseDir is wired (pure runner). */
internal object NoOpDailyLogSink : WorkflowDailyLogSink {
    override fun append(day: String, fileName: String, text: String, metadata: Map<String, String>): String? = null
}

/** Lazy ISO `YYYY-MM-DD` supplier (kept in a tiny object so tests can override the provider). */
internal object LazyIsoDate {
    fun now(): String = DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDate.now())
}

/** Deterministic condition evaluator for `if` steps. */
internal object ConditionEvaluator {
    fun evaluate(
        left: JsonElement?,
        operator: String?,
        right: JsonElement?,
        context: JsonObject,
        defaults: JsonObject,
    ): Boolean {
        val op = operator?.trim() ?: "=="
        val l = resolveOperand(left, context, defaults)
        val r = resolveOperand(right, context, defaults)
        if (l is JsonObject || r is JsonObject) {
            return if (op == "==") l == r else l != r
        }
        val ls = l?.toString() ?: ""
        val rs = r?.toString() ?: ""
        return when (op) {
            "==" -> ls == rs
            "!=" -> ls != rs
            ">" -> num(ls) > num(rs)
            "<" -> num(ls) < num(rs)
            ">=" -> num(ls) >= num(rs)
            "<=" -> num(ls) <= num(rs)
            else -> ls == rs
        }
    }

    private fun num(s: String): Double = s.toDoubleOrNull() ?: when (s) {
        "true" -> 1.0
        "false" -> 0.0
        else -> Double.NaN
    }

    private fun resolveOperand(e: JsonElement?, context: JsonObject, defaults: JsonObject): JsonElement? {
        if (e == null) return null
        val p = e as? JsonPrimitive ?: return e
        val text = p.content
        if (text.startsWith("\${") && text.endsWith("}")) {
            return WorkflowVariableResolver.lookup(emptyMap(), context, defaults, 0, false, text.substring(2, text.length - 1))
        }
        return e
    }
}
