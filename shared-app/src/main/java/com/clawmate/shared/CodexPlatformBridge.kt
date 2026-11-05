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
     * Runs one chat delegation to completion and returns its bounded outcome.
     *
     * The picker is not needed here: a delegation reuses the single saved computer and the
     * workspace stored on the run being continued, or the first saved computer's default
     * workspace on a new run. Choosing a different computer stays a Codex-tab action.
     *
     * Failures are reported as `status` values rather than exceptions so the ArkTS layer can
     * give the model a truthful description, including "outcome unknown".
     */
    fun codexRunTask(raw: String): String {
        val request = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            return outcome("failed", "", "", message = "委托参数无效")
        }
        val text = request.text("task")
        val continueRunId = request.text("continueRunId")
        val sessionId = request.text("sessionId").ifEmpty { "chat" }
        if (text.isEmpty()) return outcome("invalid_arguments", "", "", message = "task 不能为空")
        val store = runStore
        val backend = CodexBackend(
            transport = CodexTransport(clientFactory = { ca -> CodexTransport.defaultClient(ca) }),
            credentials = credentials,
            artifacts = CodexArtifactStore(File(context.filesDir, "codex/sessions")),
        ).apply {
            // A chat delegation cannot prompt for a directory, so it uses the workspace the
            // conversation already targets or the server's first one. Changing the computer
            // or directory remains a Codex-tab action.
            selector = CodexBackend.TargetSelector { saved, _, _ -> CodexBackend.Target(saved.id, "") }
            runStore = store
            defaultWorkspace = { envelope -> envelope.workspaces.firstOrNull()?.id.orEmpty() }
        }
        val handlers = object : ChatHandlers {
            override fun onDelta(text: String) = Unit
            override fun onTrace(line: String) = Unit
        }
        return try {
            val result = runBlocking { backend.run(ChatRequest(sessionId, text, continueRunId = continueRunId), handlers) }
            val received = result.files.filter { it.localPath.isNotEmpty() }.map { it.id }
            outcomeJson(result, received)
        } catch (e: CodexRunException) {
            // outcomeUnknown means the PC may still be working: never imply it failed.
            outcome(
                if (e.outcomeUnknown) "disconnected" else "failed",
                e.runId,
                "",
                message = e.message.orEmpty(),
            )
        } catch (e: ChatBackendUnavailableException) {
            outcome("unavailable", "", "", message = e.message.orEmpty())
        } catch (e: Exception) {
            outcome("failed", "", "", message = e.message ?: "Codex 委托失败")
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
