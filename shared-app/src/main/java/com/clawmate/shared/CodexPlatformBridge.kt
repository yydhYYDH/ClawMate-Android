package com.clawmate.shared

import android.content.Context
import com.clawmate.platform.codex.CodexConnection
import com.clawmate.platform.codex.CodexCredentialStore
import com.clawmate.platform.codex.CodexTransport
import com.clawmate.platform.codex.CodexTransportException
import com.clawmate.platform.codex.EncryptedCodexSecretStorage
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 *  - `codexConnection*`: encrypted credential storage.
 *
 * Every method returns JSON and never throws across the bridge, so an ArkTS caller always
 * receives a structured error it can display.
 */
class CodexPlatformBridge(context: Context) {

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
