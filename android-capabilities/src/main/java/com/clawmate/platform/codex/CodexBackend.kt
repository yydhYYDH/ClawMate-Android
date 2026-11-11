package com.clawmate.platform.codex

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json

/**
 * Runs one Codex turn on the user's paired computer.
 *
 * Android counterpart of `CodexChatService.ets`. The ordering is the valuable part:
 *
 *  1. connect and require `/v1/info` to advertise permission settings and artifacts;
 *  2. create an independent task, or re-verify the previous task before continuing it;
 *  3. upload attachments, bounded per turn;
 *  4. mark the run submitted **before** POSTing the turn, so an ambiguous response is
 *     reconciled by reading the task and never by resending;
 *  5. follow with bounded long-polling, applying snapshots or deltas with revision checks;
 *  6. after `completed`, download each delivered file and verify offset, length and SHA-256.
 *
 * A run whose turn was submitted but whose outcome could not be confirmed reports
 * [ChatRunResult.outcomeUnknown]; the work may still be running on the PC.
 */
class CodexBackend(
    private val transport: CodexTransport,
    private val credentials: CodexCredentialStore,
    private val artifacts: CodexArtifactStore,
    private val followTimeoutMs: Long = FOLLOW_TIMEOUT_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) : ChatBackend {

    override val id: ChatBackendId = ChatBackendId.CODEX

    private val json = Json { ignoreUnknownKeys = true }

    /** Supplies the computer/workspace picker implemented by the UI layer. */
    @Volatile
    var selector: TargetSelector? = null

    /** Optional persistence for delegation records, supplied by the chat layer. */
    @Volatile
    var runStore: CodexRunStore? = null

    /**
     * Resolves the workspace for a delegation the user did not pick one for.
     *
     * The chat path cannot open a directory picker mid-conversation, so it supplies the
     * workspace to use once `/v1/info` is known. Returning empty is an error, never a guess.
     */
    @Volatile
    var defaultWorkspace: ((CodexEnvelope) -> String)? = null

    private class ActiveRun(val id: String) {
        @Volatile var stopRequested: Boolean = false
        @Volatile var stopSent: Boolean = false
        @Volatile var task: CodexTask? = null
        @Volatile var submitted: Boolean = false
    }

    /** One run at a time: a chat may hold a single delegation. */
    @Volatile
    private var active: ActiveRun? = null

    @Volatile
    private var info: CodexEnvelope? = null

    override fun capabilities() = ChatBackendCapabilities(
        tools = setOf(TOOL_NAME),
        images = true,
        maxRounds = 1,
        resumable = true,
        deliversFiles = true,
        requiresApproval = true,
    )

    override suspend fun run(request: ChatRequest, handlers: ChatHandlers): ChatRunResult {
        check(active == null) { "本聊天已有 Codex 委托，请等待或停止后再试" }
        val previous = if (request.continueRunId.isNotEmpty()) loadRun(request.continueRunId, request.sessionId) else null
        val saved = resolveConnection(previous)
        val target = previous?.let { Target(it.connectionId, it.workspaceId) }
            ?: run {
                val picker = selector ?: throw ChatBackendUnavailableException("请先在 Codex 页选择电脑和工作目录")
                picker.select(saved, request.text, request.imagePaths.size)
                    ?: throw CodexValidationException("用户取消委托")
            }

        val current = ActiveRun(newRunId())
        active = current
        handlers.onRunCreated(current.id)
        val run = RunRecord(
            id = current.id,
            sessionId = request.sessionId,
            connectionId = target.connectionId,
            workspaceId = target.workspaceId,
            requestId = newRunId(),
            taskText = request.text,
            createdAt = now(),
        )
        try {
            connect(target.connectionId)
            // An empty workspace means "the caller could not ask"; resolve it from /v1/info.
            if (run.workspaceId.isEmpty()) {
                val resolved = defaultWorkspace?.invoke(info ?: CodexEnvelope())
                    ?: throw CodexValidationException("请先在 Codex 页选择电脑和工作目录")
                if (resolved.isEmpty()) throw CodexValidationException("电脑端尚未提供可用的工作目录")
                run.workspaceId = resolved
            }
            val workspace = info?.workspaces?.firstOrNull { it.id == run.workspaceId }
                ?: throw CodexValidationException("电脑端已移除此工作目录，请重新选择")
            val model = info?.models?.firstOrNull { it.isDefault } ?: info?.models?.firstOrNull()
                ?: throw CodexValidationException("电脑端尚未提供可用的 Codex 模型")
            run.computerName = info?.name?.ifEmpty { "Codex 电脑" } ?: "Codex 电脑"
            run.workspaceName = workspace.name
            run.model = model.model.ifEmpty { model.id }
            run.effort = model.defaultReasoningEffort
            transport.setArtifactChunkBytes(info?.artifactChunkBytes ?: 0L)

            val taskId = if (previous != null) {
                run.taskId = previous.taskId
                val envelope = transport.get("/v1/tasks/${previous.taskId}", timeoutMs = POLL_TIMEOUT_MS)
                val task = envelope.task
                if (task == null || task.id != previous.taskId || task.workspaceId != previous.workspaceId ||
                    task.lastRequestId != previous.requestId || task.turnId != previous.turnId ||
                    task.status != "completed"
                ) {
                    throw CodexValidationException("原会话已被其他端更新或仍在执行，请同步原结果或新建委托")
                }
                current.task = task
                task.id
            } else {
                val created = transport.postTyped(
                    "/v1/tasks",
                    CodexCreateBody(workspaceId = run.workspaceId, model = run.model, effort = run.effort),
                )
                created.task?.id ?: throw CodexValidationException("电脑未返回有效的新会话")
            }
            if (!CodexTypes.isValidTaskId(taskId)) throw CodexValidationException("电脑返回的会话编号无效")
            run.taskId = taskId

            val attachments = uploadImages(run, request.imagePaths)
            coroutineContext.ensureActive()
            // Record intent before the POST so a lost answer is reconciled by reading.
            run.submitted = true
            saveRun(run)
            current.submitted = true
            handlers.onStatus("starting", "任务正在下发，若断线将同步原任务")
            val turn = try {
                transport.postTyped(
                    "/v1/tasks/$taskId/turns",
                    CodexTurnBody(
                        requestId = run.requestId,
                        text = request.text + DELIVERY_INSTRUCTIONS,
                        attachments = attachments,
                        model = run.model,
                        effort = run.effort,
                    ),
                )
            } catch (e: CodexTransportException) {
                // Only a read may recover from a lost turn response.
                transport.get("/v1/tasks/$taskId", timeoutMs = POLL_TIMEOUT_MS)
            }
            accept(turn, run, current, handlers)
            val finished = follow(run, current, handlers)
            val result = resultOf(finished, run.turnId, current, handlers)
            run.answer = result.answer
            run.error = result.error
            run.status = result.status
            saveRun(run)
            return result
        } catch (e: ChatBackendUnavailableException) {
            throw e
        } catch (e: CodexValidationException) {
            throw CodexRunException(e.message ?: "Codex 委托失败", current.submitted, current.id)
        } catch (e: CodexTransportException) {
            // Submitted but unconfirmed means the remote may still be working.
            throw CodexRunException(e.message ?: "Codex 委托失败", current.submitted, current.id)
        } finally {
            active = null
        }
    }

    override fun cancel(runId: String) {
        active?.let { run -> if (runId.isEmpty() || run.id == runId) run.stopRequested = true }
        transport.cancelTaskUpdates()
    }

    private suspend fun connect(connectionId: String) {
        val connection = credentials.load(connectionId)
            ?: throw CodexValidationException("原电脑连接已删除，请先在 Codex 页重新配对")
        transport.configure(connection)
        val envelope = transport.get("/v1/info", timeoutMs = SHORT_TIMEOUT_MS)
        if (envelope.ready != true || envelope.permissionSettingsSupported != true ||
            envelope.artifactsSupported != true
        ) {
            throw ChatBackendUnavailableException("请更新并启动 PC Codex 连接器，需支持权限设置和文件回传")
        }
        info = envelope
    }

    private suspend fun uploadImages(run: RunRecord, paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        if (paths.size > MAX_IMAGES) throw CodexValidationException("一次最多委托 $MAX_IMAGES 张当前消息的图片")
        val ids = ArrayList<String>(paths.size)
        for (path in paths) {
            coroutineContext.ensureActive()
            val file = File(path)
            if (!file.isFile || file.length() !in 1..CodexTypes.MAX_FILE_BYTES) {
                throw CodexValidationException("单张图片需小于 8 MB")
            }
            val encoded = java.util.Base64.getEncoder().encodeToString(file.readBytes())
            val envelope = transport.postTyped(
                "/v1/uploads",
                CodexUploadBody(workspaceId = run.workspaceId, name = file.name, base64 = encoded, image = true),
            )
            ids += envelope.attachment?.id ?: throw CodexValidationException("电脑未确认图片上传")
        }
        return ids
    }

    /**
     * Drives the task until it leaves a busy state.
     *
     * A stop is only shown as requested; it becomes "stopped" when the PC confirms.
     */
    private suspend fun follow(run: RunRecord, current: ActiveRun, handlers: ChatHandlers): CodexTask {
        var failures = 0
        val deadline = now() + followTimeoutMs
        while (true) {
            coroutineContext.ensureActive()
            val task = current.task ?: throw CodexValidationException("未收到任务状态")
            if (current.stopRequested && !current.stopSent) {
                current.stopSent = true
                handlers.onStatus("stopping", "正在停止任务，等待电脑端确认")
                val envelope = try {
                    transport.post("/v1/tasks/${task.id}/interrupt", emptyPatch(), timeoutMs = SHORT_TIMEOUT_MS)
                } catch (e: CodexTransportException) {
                    throw CodexValidationException("停止请求未确认，远端可能仍在运行，请同步后重试停止")
                }
                accept(envelope, run, current, handlers)
                continue
            }
            if (!CodexTypes.taskIsBusy(task.status)) {
                handlers.onStatus(task.status, task.error)
                return task
            }
            if (now() > deadline) {
                throw CodexTransportException("远端任务尚未确认结束，可在本条卡片继续同步或请求停止")
            }
            val suffix = if (info?.taskUpdatesSupported == true) {
                "?revision=${task.revision}&waitMs=12000" +
                    if (info?.taskDeltaSupported == true) "&sync=delta" else ""
            } else {
                ""
            }
            try {
                // A long poll waits up to waitMs on the server, so the caller-side timeout
                // must exceed that but still fit inside one platform bridge call.
                val envelope = transport.get("/v1/tasks/${task.id}$suffix", timeoutMs = POLL_TIMEOUT_MS)
                try {
                    accept(envelope, run, current, handlers)
                } catch (e: CodexStaleDeltaException) {
                    // An unusable delta means a full resync, never a resend of the turn.
                    accept(
                        transport.get("/v1/tasks/${task.id}", timeoutMs = POLL_TIMEOUT_MS),
                        run, current, handlers,
                    )
                }
                failures = 0
                if (suffix.isEmpty()) delay(1200)
            } catch (e: CodexTransportException) {
                failures += 1
                if (failures >= MAX_FOLLOW_FAILURES) throw e
                handlers.onStatus("running", "连接不稳定，正在同步原任务（$failures/$MAX_FOLLOW_FAILURES）")
                delay(failures * 1500L)
            }
        }
    }

    private fun accept(
        envelope: CodexEnvelope,
        run: RunRecord,
        current: ActiveRun,
        handlers: ChatHandlers,
    ): CodexTask {
        // A long poll that times out without a change answers `unchanged` with no task. That
        // is the normal steady state while the PC works, not an error.
        if (envelope.unchanged && envelope.task == null && envelope.taskDelta == null) {
            return current.task ?: throw CodexValidationException("未收到任务状态")
        }
        val merged = CodexTypes.mergeTask(current.task, envelope)
            ?: throw CodexValidationException("未收到任务状态")
        if (merged.id != run.taskId || merged.workspaceId != run.workspaceId ||
            merged.lastRequestId != run.requestId || merged.status == "unknown"
        ) {
            throw CodexValidationException("远端执行状态尚未确认或会话已变化，请手动同步，勿重复下发")
        }
        // A concurrent long poll may have already applied a newer revision.
        val existing = current.task
        if (existing != null && merged.revision < existing.revision) return existing
        current.task = merged
        if (merged.turnId.isNotEmpty()) run.turnId = merged.turnId
        run.status = merged.status
        if (merged.status == "waitingApproval") {
            handlers.onStatus("waitingApproval", "有操作等待你的确认，请查看对话中的申请")
        }
        return merged
    }

    private suspend fun resultOf(
        task: CodexTask,
        turnId: String,
        current: ActiveRun,
        handlers: ChatHandlers,
    ): ChatRunResult {
        val answer = task.items
            .filter { it.kind == "assistant" && (it.turnId ?: it.id.substringBefore(':')) == turnId }
            .joinToString("\n\n") { it.text }
        if (task.status != "completed") {
            return ChatRunResult(
                ok = false,
                status = if (task.status == "interrupted") "cancelled" else task.status,
                runId = current.id,
                answer = answer,
                error = task.error,
            )
        }
        val delivered = task.artifacts.filter { turnId.isNotEmpty() && it.turnId == turnId }
        CodexTypes.validateTurnArtifacts(delivered)
        handlers.onStatus("receiving", "正在接收交付文件")
        val saved = ArrayList<ChatArtifact>(delivered.size)
        val failures = StringBuilder()
        for (artifact in delivered) {
            coroutineContext.ensureActive()
            try {
                val path = artifacts.receive(task.id, artifact, transport) { received, total ->
                    val percent = if (total > 0) received * 100 / total else 0
                    handlers.onStatus("receiving", "接收 ${artifact.name} · $percent%")
                }
                val chatArtifact = ChatArtifact(artifact.id, artifact.name, artifact.mimeType, artifact.size, path)
                saved += chatArtifact
                handlers.onArtifact(chatArtifact)
            } catch (e: Exception) {
                if (failures.isNotEmpty()) failures.append('\n')
                failures.append(artifact.name).append("：").append(e.message)
            }
        }
        return ChatRunResult(
            ok = saved.size == delivered.size && task.artifactWarning.isEmpty() && failures.isEmpty(),
            status = "completed",
            runId = current.id,
            answer = answer,
            files = saved,
            error = listOf(task.artifactWarning, failures.toString()).filter { it.isNotEmpty() }.joinToString("\n"),
        )
    }

    private fun resolveConnection(previous: RunRecord?): CodexSavedConnection =
        if (previous != null) {
            credentials.list().firstOrNull { it.id == previous.connectionId }
                ?: throw CodexValidationException("原电脑连接已删除或身份已变更，请先在 Codex 页重新配对")
        } else {
            credentials.list().firstOrNull()
                ?: throw ChatBackendUnavailableException("尚未配对任何电脑，请先在 Codex 页创建连接")
        }

    private fun saveRun(run: RunRecord) {
        run.updatedAt = now()
        try {
            runStore?.save(run)
        } catch (_: Exception) {
            throw CodexValidationException("无法保存手机端 Codex 委托状态")
        }
    }

    private fun loadRun(id: String, sessionId: String): RunRecord {
        if (!CodexTypes.isValidRunId(id)) throw CodexValidationException("委托编号无效")
        val run = runStore?.load(id) ?: throw CodexValidationException("未找到该 Codex 委托，请从本聊天选择有效的委托编号")
        if (run.sessionId != sessionId) throw CodexValidationException("只能继续本聊天中已完成的 Codex 委托")
        if (run.status != "completed" || !run.submitted || !CodexTypes.isValidTaskId(run.taskId)) {
            throw CodexValidationException("只能继续本聊天中已完成的 Codex 委托；状态不明时请先同步原卡片")
        }
        return run
    }

    private fun emptyPatch() = json.parseToJsonElement(EMPTY_PATCH)

    private fun newRunId(): String = java.util.UUID.randomUUID().toString()

    /** Which computer and workspace a run targets. */
    data class Target(val connectionId: String, val workspaceId: String)

    fun interface TargetSelector {
        /** @return the chosen target, or `null` when the user dismissed the picker. */
        suspend fun select(connection: CodexSavedConnection, task: String, imageCount: Int): Target?
    }

    /** One persisted delegation, mirroring `CodexChatRun`. */
    @kotlinx.serialization.Serializable
    data class RunRecord(
        val id: String,
        val sessionId: String,
        val connectionId: String,
        var workspaceId: String,
        val requestId: String,
        val taskText: String,
        val createdAt: Long,
        var computerName: String = "",
        var workspaceName: String = "",
        var model: String = "",
        var effort: String = "",
        var taskId: String = "",
        var submitted: Boolean = false,
        var turnId: String = "",
        var status: String = "starting",
        var answer: String = "",
        var error: String = "",
        var updatedAt: Long = 0,
    )

    /** Storage for delegation records, implemented in the app layer. */
    interface CodexRunStore {
        fun load(id: String): RunRecord?
        fun save(run: RunRecord)
    }

    companion object {
        const val TOOL_NAME: String = "run_codex_task"
        const val MAX_IMAGES: Int = 6
        const val MAX_FOLLOW_FAILURES: Int = 3
        /**
         * Timeout for one task-update exchange.
         *
         * A long poll may wait `waitMs=12000` on the server, and the platform bridge call
         * gives up around 60 s, so this leaves room for a slow network without ever
         * exceeding the bridge.
         */
        const val POLL_TIMEOUT_MS: Long = 35_000
        /** Deadline for a request that is expected to answer immediately. */
        const val SHORT_TIMEOUT_MS: Long = 20_000
        const val FOLLOW_TIMEOUT_MS: Long = 30L * 60 * 1000
        private const val EMPTY_PATCH = """{"id":"","decision":"","answers":[]}"""
        private const val DELIVERY_INSTRUCTIONS =
            "\n\n交付要求：完成后简述结果和验证情况；只为最终交付文件提供绝对路径 Markdown 链接，" +
                "成品优先保存到工作目录 outputs/。不要链接临时文件、凭据或依赖。"
    }
}

/** A Codex run that ended abnormally, carrying whether the remote outcome is known. */
class CodexRunException(
    message: String,
    val outcomeUnknown: Boolean,
    val runId: String,
) : Exception(message)

