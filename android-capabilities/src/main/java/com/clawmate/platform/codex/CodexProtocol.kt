package com.clawmate.platform.codex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire model of the ClawMate Codex bridge (`bridge/codex/server.mjs`).
 *
 * Every type mirrors the HarmonyOS client's `CodexTypes.ets` so field names match the
 * bridge exactly; unknown fields are ignored by the parser. Nothing here holds a URL
 * containing a token: the token travels only in the `Authorization` header.
 */

@Serializable
data class CodexPairingBundle(
    val type: String = "",
    val version: Int = 0,
    val name: String = "",
    val origin: String = "",
    val serverId: String = "",
    val caCertificate: String = "",
    val code: String = "",
    val expiresAt: Long = 0,
)

/** Stored connection: origin plus the bridge token (never persisted in plain text). */
@Serializable
data class CodexConnection(
    val origin: String,
    val token: String,
    val caCertificate: String = "",
)

/** A saved connection as shown in the connection list. */
@Serializable
data class CodexSavedConnection(
    val id: String,
    val name: String,
    val origin: String,
    val updatedAt: Long,
    /** True when the certificate came from a pairing file rather than the system store. */
    val hasCustomCa: Boolean = false,
)

@Serializable
data class CodexEffort(
    val reasoningEffort: String = "",
    val description: String = "",
)

@Serializable
data class CodexModel(
    val id: String = "",
    val model: String = "",
    val displayName: String = "",
    val defaultReasoningEffort: String = "",
    val supportedReasoningEfforts: List<CodexEffort> = emptyList(),
    val inputModalities: List<String>? = null,
    val isDefault: Boolean = false,
)

@Serializable
data class CodexWorkspace(
    val id: String,
    val name: String,
)

@Serializable
data class CodexItem(
    val id: String,
    val kind: String,
    val text: String = "",
    val turnId: String? = null,
)

@Serializable
data class CodexItemPatch(
    val id: String,
    val kind: String,
    val text: String = "",
    val turnId: String? = null,
    val append: Boolean = false,
)

@Serializable
data class CodexProgress(
    val kind: String = "",
    val message: String = "",
    val detail: String = "",
    val since: Long = 0,
    val attempt: Int? = null,
    val total: Int? = null,
)

@Serializable
data class CodexQuestionOption(
    val label: String = "",
    val description: String = "",
)

@Serializable
data class CodexQuestion(
    val id: String = "",
    val header: String = "",
    val question: String = "",
    val options: List<CodexQuestionOption> = emptyList(),
    val isSecret: Boolean = false,
)

@Serializable
data class CodexAnswer(
    val id: String,
    val text: String,
)

@Serializable
data class CodexApproval(
    val id: String,
    val kind: String = "",
    val title: String = "",
    val detail: String = "",
    val questions: List<CodexQuestion> = emptyList(),
    val canAccept: Boolean = true,
)

@Serializable
data class CodexArtifact(
    val id: String,
    val turnId: String = "",
    val name: String,
    val size: Long = 0,
    @SerialName("mimeType") val mimeType: String = "",
    val sha256: String,
    val createdAt: Long = 0,
)

@Serializable
data class CodexLocalArtifact(
    val artifact: CodexArtifact,
    val localPath: String,
    val receivedBytes: Long,
)

@Serializable
data class CodexArtifactChunk(
    val id: String,
    val offset: Long,
    val total: Long,
    val sha256: String,
    val base64: String,
)

@Serializable
data class CodexTaskSummary(
    val id: String,
    val title: String = "",
    val status: String = "",
    val workspaceId: String = "",
    val model: String = "",
    val updatedAt: Long = 0,
    val source: String? = null,
    val sharedThread: Boolean = false,
)

@Serializable
data class CodexTask(
    val id: String,
    val title: String = "",
    val status: String = "",
    val workspaceId: String = "",
    val model: String = "",
    val effort: String = "",
    val mode: String = "",
    val turnId: String = "",
    val items: List<CodexItem> = emptyList(),
    val approvals: List<CodexApproval> = emptyList(),
    val updatedAt: Long = 0,
    val revision: Long = 0,
    val error: String = "",
    val truncated: Boolean = false,
    val lastRequestId: String? = null,
    val artifacts: List<CodexArtifact> = emptyList(),
    val artifactWarning: String = "",
    val external: Boolean = false,
    val sourceThreadId: String? = null,
    val sharedThread: Boolean = false,
    val historyCursor: String? = null,
    val approvalMode: String? = null,
    val progress: CodexProgress? = null,
    val startedAt: Long = 0,
    val lastActivityAt: Long = 0,
    val executionTransport: String? = null,
    val executionSource: String? = null,
    val desktopConnected: Boolean? = null,
    val lastRequestConfirmed: Boolean? = null,
)

@Serializable
data class CodexTaskDelta(
    val baseRevision: Long,
    val task: CodexTask,
    val items: List<CodexItemPatch> = emptyList(),
    val itemOrder: List<String> = emptyList(),
)

@Serializable
data class CodexAttachment(
    val id: String,
    val name: String = "",
    val size: Long = 0,
    val image: Boolean = false,
)

/** The single JSON envelope every bridge route returns. */
@Serializable
data class CodexEnvelope(
    val protocol: Int? = null,
    val ready: Boolean? = null,
    val name: String = "",
    val workspaces: List<CodexWorkspace> = emptyList(),
    val models: List<CodexModel> = emptyList(),
    val tasks: List<CodexTaskSummary> = emptyList(),
    val task: CodexTask? = null,
    val taskDelta: CodexTaskDelta? = null,
    val attachment: CodexAttachment? = null,
    val unchanged: Boolean = false,
    val error: String = "",
    val token: String? = null,
    val deviceId: String? = null,
    val serverId: String? = null,
    val artifactsSupported: Boolean? = null,
    val artifactChunk: CodexArtifactChunk? = null,
    val existingSessionsSupported: Boolean? = null,
    val sameSessionSupported: Boolean? = null,
    val permissionSettingsSupported: Boolean? = null,
    val taskUpdatesSupported: Boolean? = null,
    val healthSupported: Boolean? = null,
    val taskDeltaSupported: Boolean? = null,
    val artifactChunkBytes: Long? = null,
    val desktopSessionSharingSupported: Boolean? = null,
    val threads: List<CodexTaskSummary> = emptyList(),
    val nextThreadCursor: String? = null,
)

/** Request bodies accepted by the bridge. Serialized with the bridge's exact field names. */
@Serializable
data class CodexCreateBody(
    val workspaceId: String,
    val model: String,
    val effort: String,
    val mode: String = "workspace-write",
    val approvalMode: String = "ask",
)

@Serializable
data class CodexTurnBody(
    val requestId: String,
    val text: String,
    val attachments: List<String> = emptyList(),
    val model: String,
    val effort: String,
    val mode: String = "workspace-write",
    val approvalMode: String = "ask",
)

@Serializable
data class CodexUploadBody(
    val workspaceId: String,
    val name: String,
    val base64: String,
    val image: Boolean,
)

@Serializable
data class CodexApprovalBody(
    val id: String,
    val decision: String,
    val answers: List<CodexAnswer> = emptyList(),
)

@Serializable
data class CodexPairBody(
    val code: String,
    val deviceName: String,
)
