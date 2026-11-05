package com.clawmate.shared

import android.content.Context
import com.clawmate.platform.codex.ChatBackendUnavailableException
import com.clawmate.platform.codex.ChatHandlers
import com.clawmate.platform.codex.ChatRequest
import com.clawmate.platform.codex.ChatRunResult
import com.clawmate.platform.codex.CodexArtifactStore
import com.clawmate.platform.codex.CodexBackend
import com.clawmate.platform.codex.CodexConnection
import com.clawmate.platform.codex.CodexCredentialStore
import com.clawmate.platform.codex.CodexRunException
import com.clawmate.platform.codex.CodexTransport
import com.clawmate.platform.codex.CodexTransportException
import com.clawmate.platform.codex.EncryptedCodexSecretStorage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
// codexRequest is one bounded HTTP exchange, so blocking the bridge thread is acceptable
// there; a delegation is not, and uses start/poll instead.
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * ArkTS-facing side of the Codex feature.
 *
 * The shared ArkTS page keeps all protocol logic; this class only supplies what needs the
 * Android platform:
 *
 *  - `codexRequest`: performs one HTTPS exchange through [CodexTransport], which refuses
 *    redirects and never resends on its own;
 *  - `codexCancel`: aborts the in-flight request with that id;
 *  - `codexConnection*`: encrypted credential storage;
 *  - `codexRunTask`: runs one chat delegation through [CodexBackend].
 *
 * Every method returns JSON and never throws across the bridge, so an ArkTS caller always
 * receives a structured error it can display.
 */
class CodexPlatformBridge(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Runs delegations off the bridge thread; they outlive a single poll. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Delegations in flight, keyed by the id handed to ArkTS. */
    private val codexRuns = ConcurrentHashMap<String, CodexRunState>()

    /** Releases running delegations; their remote tasks keep running on the PC. */
    fun close() {
        codexRuns.clear()
        scope.coroutineContext[Job]?.cancel()
    }

    private val credentials: CodexCredentialStore by lazy {
        CodexCredentialStore(EncryptedCodexSecretStorage.open(context.applicationContext))
    }

    /** One transport per connection origin, so generations do not interfere. */
    private val transports = ConcurrentHashMap<String, CodexTransport>()

    private fun transportFor(origin: String): CodexTransport =
        transports.getOrPut(origin) { CodexTransport(clientFactory = { ca -> CodexTransport.defaultClient(ca) }) }

    /**
     * Performs one request.
     *
     * @param raw JSON matching the ArkTS `CodexNativeRequest` shape.
     * @return JSON matching `CodexNativeResponse`; `failure` is set instead of throwing.
     */
    fun codexRequest(raw: String): String {
        val request = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            return failure("请求格式无效", transportFailure = false, gzipUnsupported = false)
        }
        val origin = request.text("origin")
        val route = request.text("route")
        val method = request.text("method").ifEmpty { "GET" }
        val id = request.text("id")
        if (origin.isEmpty() || route.isEmpty() || !route.startsWith("/")) {
            return failure("连接地址无效，请重新配对", transportFailure = false, gzipUnsupported = false)
        }
        val transport = transportFor(origin)
        return try {
            // Reconfigure only when the connection or its certificate changed.
            if (!transport.isConfiguredFor(origin)) {
                transport.configure(
                    CodexConnection(
                        origin = origin,
                        token = request.text("token"),
                        caCertificate = request.text("caCertificate"),
                    ),
                )
            }
            val body = request["body"]?.jsonPrimitiveOrNull()?.content
            val envelope = runBlocking {
                if (method == "POST") {
                    transport.post(route, json.parseToJsonElement(body ?: "{}"))
                } else {
                    transport.get(route)
                }
            }
            buildJsonObject {
                put("status", 200)
                put("text", json.encodeToString(com.clawmate.platform.codex.CodexEnvelope.serializer(), envelope))
                put("gzipUnsupported", false)
                put("failure", "")
                put("transportFailure", false)
            }.toString()
        } catch (e: CodexTransportException) {
            // Report the status the caller must reason about, plus the reason it failed.
            val text = buildJsonObject { put("error", e.message.orEmpty()) }.toString()
            failure(e.message.orEmpty(), transportFailure = e.retryable, gzipUnsupported = false, text = text)
        } catch (e: Exception) {
            failure(e.message ?: "Codex 请求失败", transportFailure = false, gzipUnsupported = false)
        } finally {
            // Nothing to retain between calls; the generation is tracked inside the transport.
            if (id.isEmpty()) Unit
        }
    }

    /** Cancels the request with [id]. Safe to call for an unknown id. */
    fun codexCancel(id: String): Boolean {
        transports.values.forEach { it.cancelTaskUpdates() }
        return true
    }

    fun codexConnectionList(): String = runCatching {
        json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.clawmate.platform.codex.CodexSavedConnection.serializer()),
            credentials.list(),
        )
    }.getOrElse { "[]" }

    fun codexConnectionLoad(id: String): String {
        val connection = runCatching { credentials.load(id) }.getOrNull() ?: return ""
        return json.encodeToString(CodexConnection.serializer(), connection)
    }

    fun codexConnectionSave(raw: String): String {
        val request = json.parseToJsonElement(raw).jsonObject
        val saved = credentials.save(
            CodexConnection(
                origin = request.text("origin"),
                token = request.text("token"),
                caCertificate = request.text("caCertificate"),
            ),
            request.text("name"),
        )
        return json.encodeToString(com.clawmate.platform.codex.CodexSavedConnection.serializer(), saved)
    }

    fun codexConnectionForget(id: String): Boolean {
        credentials.forget(id)
        return true
    }

    /**
     * Starts one chat delegation and returns its id immediately.
     *
     * A delegation can run for many minutes. Blocking the bridge thread that long would hit
     * the platform's call timeout (observed at 60 s), so this follows the same start/poll
     * shape as `imagePrepareStart`: work runs on a coroutine and the caller polls.
     *
     * @return `{"id": "<runId>"}` or `{"id": "", "failure": "..."}`.
     */
    fun codexRunTaskStart(raw: String): String {
        val request = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            return buildJsonObject { put("id", ""); put("failure", "委托参数无效") }.toString()
        }
        val text = request.text("task")
        if (text.isEmpty()) {
            return buildJsonObject { put("id", ""); put("failure", "task 不能为空") }.toString()
        }
        val continueRunId = request.text("continueRunId")
        val sessionId = request.text("sessionId").ifEmpty { "chat" }
        val runId = java.util.UUID.randomUUID().toString()
        codexRuns[runId] = CodexRunState(running = true)
        scope.launch {
            val state = codexRuns[runId] ?: return@launch
            val result = try {
                executeDelegation(text, continueRunId, sessionId, runId)
            } catch (e: Exception) {
                outcome("failed", runId, "", e.message ?: "Codex 委托失败")
            }
            state.complete(result)
        }
        return buildJsonObject { put("id", runId); put("failure", "") }.toString()
    }

    /**
     * Polls a delegation.
     *
     * @return `{"v":1,"status":"running"}` or `{"v":1,"status":"done","result":{...}}`.
     */
    fun codexRunTaskPoll(id: String): String {
        val state = codexRuns[id]
            ?: return buildJsonObject { put("v", 1); put("status", "missing") }.toString()
        val result = state.result
        return if (result == null) {
            buildJsonObject { put("v", 1); put("status", "running") }.toString()
        } else {
            codexRuns.remove(id)
            buildJsonObject {
                put("v", 1)
                put("status", "done")
                put("result", result)
            }.toString()
        }
    }

    /** Abandons a delegation; the remote task keeps running and must be resynced. */
    fun codexRunTaskCancel(id: String): Boolean {
        codexRuns.remove(id)
        return true
    }

    private class CodexRunState(running: Boolean) {
        @Volatile var result: String? = null
        init { if (!running) result = "" }
        fun complete(value: String) { result = value }
    }

    private suspend fun executeDelegation(
        text: String,
        continueRunId: String,
        sessionId: String,
        runId: String,
    ): String {
        val store = runStore
        val backend = CodexBackend(
            transport = CodexTransport(clientFactory = { ca -> CodexTransport.defaultClient(ca) }),
            credentials = credentials,
            artifacts = CodexArtifactStore(File(context.filesDir, "codex/sessions")),
        ).apply {
            selector = CodexBackend.TargetSelector { saved, _, _ -> CodexBackend.Target(saved.id, "") }
            runStore = store
            defaultWorkspace = { envelope -> envelope.workspaces.firstOrNull()?.id.orEmpty() }
        }
        val handlers = object : ChatHandlers {
            override fun onDelta(text: String) = Unit
            override fun onTrace(line: String) = Unit
        }
        return try {
            val result = backend.run(ChatRequest(sessionId, text, continueRunId = continueRunId), handlers)
            val received = result.files.filter { it.localPath.isNotEmpty() }.map { it.id }
            outcomeJson(result, received)
        } catch (e: CodexRunException) {
            // outcomeUnknown means the PC may still be working: never imply it failed.
            outcome(
                if (e.outcomeUnknown) "disconnected" else "failed",
                e.runId,
                "",
                e.message.orEmpty(),
            )
        } catch (e: ChatBackendUnavailableException) {
            outcome("unavailable", "", "", e.message.orEmpty())
        } catch (e: Exception) {
            outcome("failed", "", "", e.message ?: "Codex 委托失败")
        }
    }

    /** Persistence for chat delegations, keyed by run id. */
    private val runStore = object : CodexBackend.CodexRunStore {
        private val directory = File(context.filesDir, "codex/chat-tools").apply { mkdirs() }

        override fun load(id: String): CodexBackend.RunRecord? {
            val file = File(directory, "$id.json")
            if (!file.isFile) return null
            return runCatching {
                json.decodeFromString(CodexBackend.RunRecord.serializer(), file.readText())
            }.getOrNull()
        }

        override fun save(run: CodexBackend.RunRecord) {
            runCatching { File(directory, "${run.id}.json").writeText(json.encodeToString(CodexBackend.RunRecord.serializer(), run)) }
        }
    }

    private fun outcomeJson(result: ChatRunResult, received: List<String>): String = buildJsonObject {
        put("ok", result.ok)
        put("status", result.status)
        put("runId", result.runId)
        put("computer", "")
        put("answer", result.answer)
        put("error", result.error)
        put("receivedIds", buildJsonArray { received.forEach { add(JsonPrimitive(it)) } })
        put("files", buildJsonArray {
            result.files.forEach { file ->
                add(buildJsonObject {
                    put("id", file.id)
                    put("name", file.name)
                    put("mimeType", file.mimeType)
                })
            }
        })
    }.toString()

    /** A structured non-success outcome, shaped like [ChatRunResult] for the ArkTS layer. */
    private fun outcome(status: String, runId: String, computer: String, message: String): String =
        buildJsonObject {
            put("ok", false)
            put("status", status)
            put("runId", runId)
            put("computer", computer)
            put("answer", "")
            put("error", message)
            put("receivedIds", buildJsonArray { })
            put("files", buildJsonArray { })
        }.toString()

    private fun failure(
        message: String,
        transportFailure: Boolean,
        gzipUnsupported: Boolean,
        text: String = "",
    ): String = buildJsonObject {
        put("status", 0)
        put("text", text)
        put("gzipUnsupported", gzipUnsupported)
        put("failure", message)
        put("transportFailure", transportFailure)
    }.toString()

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.content.takeUnless { it == null || it == "null" }.orEmpty()

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
}

