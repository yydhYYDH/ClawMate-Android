package com.clawmate.shared

import android.content.Context
import android.util.AtomicFile
import com.clawmate.platform.codex.CodexAuthException
import com.clawmate.platform.codex.ChatBackendUnavailableException
import com.clawmate.platform.codex.ChatHandlers
import com.clawmate.platform.codex.ChatRequest
import com.clawmate.platform.codex.ChatRunResult
import com.clawmate.platform.codex.CodexArtifactStore
import com.clawmate.platform.codex.CodexBackend
import com.clawmate.platform.codex.CodexConnection
import com.clawmate.platform.codex.CodexCredentialStore
import com.clawmate.platform.codex.CodexHttpException
import com.clawmate.platform.codex.CodexNetworkException
import com.clawmate.platform.codex.CodexRunException
import com.clawmate.platform.codex.CodexTransport
import com.clawmate.platform.codex.EncryptedCodexSecretStorage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
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
@OptIn(ExperimentalSerializationApi::class)
class CodexPlatformBridge(private val context: Context) {

    // encodeDefaults matters on the way out as well as in: a field left at its default is
    // omitted, and the page then sees `undefined` instead of the value. That silently made
    // `canAccept` disappear, so the approval card could not be accepted — the same class of
    // bug as the missing `attachments` in a turn body.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /** Runs delegations off the bridge thread; they outlive a single poll. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Delegations in flight, keyed by the id handed to ArkTS. */
    private val codexRuns = ConcurrentHashMap<String, CodexRunState>()

    /**
     * One in-flight HTTP exchange, so `codexRequest` never blocks the bridge thread.
     *
     * The value is settled exactly once and collected by the next poll.
     */
    private class ExchangeSlot(val requestId: String) {
        lateinit var job: Job
        @Volatile var result: String? = null
        fun complete(value: String) { result = value }
    }

    private val exchanges = ConcurrentHashMap<String, ExchangeSlot>()

    /**
     * Collects the answer to a [codexRequestStart].
     *
     * @return `{"status":"pending"}` until the exchange finishes, then the same JSON the
     *   blocking form used to return. An unknown ticket reports `expired`, which happens when
     *   the caller polled again after collecting.
     */
    fun codexRequestPoll(ticket: String): String {
        val slot = exchanges[ticket]
            ?: return buildJsonObject { put("status", "expired") }.toString()
        val done = slot.result
        if (done == null) {
            return buildJsonObject { put("status", "pending") }.toString()
        }
        exchanges.remove(ticket)
        return done
    }

    /** Cancels a pending exchange without resubmitting it. */
    fun codexRequestCancel(ticket: String): Boolean {
        exchanges.remove(ticket)?.job?.cancel()
        return true
    }

    /** Releases running delegations; their remote tasks keep running on the PC. */
    fun close() {
        codexRuns.clear()
        scope.coroutineContext[Job]?.cancel()
        exchanges.clear()
        transports.values.forEach { it.transport.close() }
        transports.clear()
    }

    private val credentials: CodexCredentialStore by lazy {
        CodexCredentialStore(EncryptedCodexSecretStorage.open(context.applicationContext))
    }

    private data class ActiveConnection(val credentials: CodexConnection, val transport: CodexTransport)
    private val transports = ConcurrentHashMap<String, ActiveConnection>()

    private fun newTransport(connection: CodexConnection): CodexTransport =
        CodexTransport(clientFactory = { ca -> CodexTransport.defaultClient(ca) }).apply { configure(connection) }

    private fun transportFor(connection: CodexConnection, authenticated: Boolean): CodexTransport {
        if (!authenticated) return newTransport(connection)
        return checkNotNull(transports.compute(connection.origin) { _, previous ->
            if (previous?.credentials == connection) previous
            else ActiveConnection(connection, newTransport(connection)).also { previous?.transport?.close() }
        }).transport
    }

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
        return try {
            val authenticated = (request["authenticated"] as? JsonPrimitive)?.content != "false"
            val connection = CodexConnection(origin, request.text("token"), request.text("caCertificate"))
            val body = request["body"]?.jsonPrimitiveOrNull()?.content
            // Honour the caller's deadline: a bridge call cannot block indefinitely, and the
            // page already knows how long it is prepared to wait.
            val requestedTimeout = (request["timeoutMs"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            // A long poll (waitMs=12000) plus network slack can occupy this thread for tens
            // of seconds. Blocking the bridge thread that long is what the system reports as
            // an ANR, so the exchange runs on a coroutine and the caller polls for it.
            val ticket = java.util.UUID.randomUUID().toString()
            val slot = ExchangeSlot(id)
            slot.job = scope.launch(start = CoroutineStart.LAZY) {
                val outcome = try {
                    val transport = transportFor(connection, authenticated)
                    val envelope = try {
                        if (method == "POST") {
                            transport.post(route, json.parseToJsonElement(body ?: "{}"), authenticated, requestedTimeout)
                        } else {
                            transport.get(route, authenticated, requestedTimeout)
                        }
                    } finally {
                        if (!authenticated) transport.close()
                    }
                    buildJsonObject {
                        put("status", 200)
                        put("text", json.encodeToString(com.clawmate.platform.codex.CodexEnvelope.serializer(), envelope))
                        put("gzipUnsupported", false)
                        put("failure", "")
                        put("transportFailure", false)
                    }.toString()
                } catch (e: Exception) {
                    failureJson(e)
                }
                slot.complete(outcome)
            }
            exchanges[ticket] = slot
            slot.job.start()
            return buildJsonObject { put("ticket", ticket) }.toString()
        } catch (e: Exception) {
            failureJson(e)
        }
    }

    /**
     * Turns an exchange failure into the reply the page expects.
     *
     * Network failures are reported independently of whether they can be retried.
     */
    private fun failureJson(e: Exception): String = when (e) {
        is CodexAuthException -> httpFailure(401, e)
        is CodexHttpException -> httpFailure(e.status, e)
        is CodexNetworkException -> failure(e.message.orEmpty(), transportFailure = true, gzipUnsupported = false)
        else -> failure(e.message ?: "Codex 请求失败", transportFailure = false, gzipUnsupported = false)
    }

    private fun httpFailure(status: Int, error: Exception): String = buildJsonObject {
        put("status", status)
        put("text", buildJsonObject { put("error", error.message.orEmpty()) }.toString())
    }.toString()

    /** Cancels the request with [id]. Safe to call for an unknown id. */
    fun codexCancel(id: String): Boolean {
        exchanges.entries.filter { it.value.requestId == id }.forEach { (ticket, _) ->
            codexRequestCancel(ticket)
        }
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
        val expectedOrigin = request.text("expectedOrigin")
        val target = CodexBackend.Target(request.text("connectionId"), request.text("workspaceId"))
        if (continueRunId.isNotEmpty() && (expectedOrigin.isNotEmpty() ||
                target.connectionId.isNotEmpty() || target.workspaceId.isNotEmpty())) {
            return buildJsonObject { put("id", ""); put("failure", "请在 Codex 直接提问以附加新的本机记录") }.toString()
        }
        val runId = java.util.UUID.randomUUID().toString()
        codexRuns[runId] = CodexRunState(running = true)
        scope.launch {
            val state = codexRuns[runId] ?: return@launch
            val result = try {
                executeDelegation(text, continueRunId, sessionId, runId, expectedOrigin, target)
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
        expectedOrigin: String,
        target: CodexBackend.Target,
    ): String {
        val store = runStore
        val backend = CodexBackend(
            transport = CodexTransport(clientFactory = { ca -> CodexTransport.defaultClient(ca) }),
            credentials = credentials,
            artifacts = CodexArtifactStore(File(context.filesDir, "codex/sessions")),
        ).apply {
            selector = CodexBackend.TargetSelector { saved, _, _ ->
                val selected = if (target.connectionId.isEmpty()) saved
                    else credentials.list().firstOrNull { it.id == target.connectionId }
                        ?: throw ChatBackendUnavailableException("电脑连接已移除，请重新配对")
                check(expectedOrigin.isEmpty() || selected.origin == expectedOrigin) { "Codex 电脑已变化，本机记录未发送" }
                CodexBackend.Target(selected.id, target.workspaceId)
            }
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
            return runCatching {
                val file = AtomicFile(File(directory, "$id.json"))
                json.decodeFromString(CodexBackend.RunRecord.serializer(), file.readFully().toString(Charsets.UTF_8))
            }.getOrNull()
        }

        override fun save(run: CodexBackend.RunRecord) {
            val file = AtomicFile(File(directory, "${run.id}.json"))
            val bytes = json.encodeToString(CodexBackend.RunRecord.serializer(), run).toByteArray(Charsets.UTF_8)
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (e: Exception) {
                file.failWrite(output)
                throw e
            }
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

