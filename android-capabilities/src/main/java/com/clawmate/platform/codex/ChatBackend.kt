package com.clawmate.platform.codex

/**
 * One execution surface behind chat, regardless of where the work happens.
 *
 * Cloud chat, a Codex delegation on the user's PC and (later) on-device inference have
 * different lifecycles, so only the **boundary** is shared:
 *
 *  - [capabilities] lets the UI decide what to offer before a run starts;
 *  - [run] streams progress through [ChatHandlers] and resolves once the run reaches a
 *    terminal state;
 *  - [cancel] requests a stop without claiming the remote work has stopped.
 *
 * Implementations must not merge their internals. A cloud Q&A loop and a long remote task
 * with approvals and file delivery have different correctness rules — in particular, a
 * Codex run must never be resent after an ambiguous failure. Sharing that machinery would
 * erase the rule that makes remote delegation safe.
 */
interface ChatBackend {
    val id: ChatBackendId

    fun capabilities(): ChatBackendCapabilities

    /**
     * Runs one request to completion.
     *
     * @throws ChatBackendUnavailableException when this backend cannot run on the device.
     */
    suspend fun run(request: ChatRequest, handlers: ChatHandlers): ChatRunResult

    /**
     * Requests cancellation. Implementations report the outcome through
     * [ChatHandlers.onStatus]; a remote task is only "stopped" once the server confirms.
     */
    fun cancel(runId: String)
}

enum class ChatBackendId { CLOUD, CODEX, LOCAL }

/**
 * What a backend can do, declared up front so the UI never discovers a limit mid-run.
 */
data class ChatBackendCapabilities(
    val tools: Set<String> = emptySet(),
    val images: Boolean = false,
    val maxRounds: Int = 1,
    /** True when an interrupted run can be resynchronised instead of restarted. */
    val resumable: Boolean = false,
    /** True when the backend can deliver files back to the phone. */
    val deliversFiles: Boolean = false,
    /** True when a run may pause for an explicit user approval. */
    val requiresApproval: Boolean = false,
)

/** A request handed to a backend. */
data class ChatRequest(
    val sessionId: String,
    val text: String,
    val imagePaths: List<String> = emptyList(),
    /** Continue an earlier completed run instead of starting a new one. */
    val continueRunId: String = "",
)

/** Streaming callbacks. Every method must tolerate being called from a background thread. */
interface ChatHandlers {
    /** Incremental visible text. */
    fun onDelta(text: String)

    /** One-line progress/status trace for the transcript. */
    fun onTrace(line: String)

    /** Coarse run state plus a human-readable notice; [status] is backend-specific. */
    fun onStatus(status: String, notice: String) = Unit

    /** A file the backend produced for the user. */
    fun onArtifact(artifact: ChatArtifact) = Unit

    /** Asks the user to approve a remote operation. Returning `false` rejects it. */
    suspend fun onApproval(request: ApprovalRequest): ApprovalDecision = ApprovalDecision.REJECT

    /** A stable handle (e.g. a Codex run id) so the transcript can resume or resynchronise. */
    fun onRunCreated(runId: String) = Unit
}

/** A file offered to the user after a run. */
data class ChatArtifact(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val localPath: String = "",
)

/** A pending remote operation that needs an explicit decision. */
data class ApprovalRequest(
    val id: String,
    val kind: String,
    val title: String,
    val detail: String,
    val canAccept: Boolean = true,
)

enum class ApprovalDecision { ACCEPT, REJECT }

/** Terminal outcome of one run. */
data class ChatRunResult(
    val ok: Boolean,
    val status: String,
    val runId: String = "",
    val answer: String = "",
    val files: List<ChatArtifact> = emptyList(),
    val error: String = "",
    /**
     * True when the phone could not confirm the remote state. The work may still be running
     * on the PC, so callers must resynchronise rather than resend the request.
     */
    val outcomeUnknown: Boolean = false,
)

/** Raised when a backend is not usable on this device; the UI shows the message verbatim. */
class ChatBackendUnavailableException(message: String) : Exception(message)

/**
 * The local-inference backend slot.
 *
 * On-device inference is owned by a separate workstream. This implementation exists so the
 * shared orchestration can address three backends, and it always reports the honest
 * boundary instead of returning fabricated output.
 */
class UnavailableLocalBackend : ChatBackend {
    override val id: ChatBackendId = ChatBackendId.LOCAL

    override fun capabilities(): ChatBackendCapabilities =
        ChatBackendCapabilities(tools = emptySet(), images = false, maxRounds = 0, resumable = false)

    override suspend fun run(request: ChatRequest, handlers: ChatHandlers): ChatRunResult {
        throw ChatBackendUnavailableException("本地模型推理尚未接入安卓端；可继续使用云端聊天")
    }

    override fun cancel(runId: String) = Unit
}
