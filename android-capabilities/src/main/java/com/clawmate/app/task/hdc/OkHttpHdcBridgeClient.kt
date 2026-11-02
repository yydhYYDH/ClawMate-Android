package com.clawmate.app.task.hdc

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * OkHttp implementation of the [HdcBridgeClient] boundary.
 *
 * Safety properties (Phase 8 requirement):
 *  - normalizes the base URL via [HdcUrlNormalizer] and rejects un-normalizable
 *    input as [HdcBridgeErrorKind.INVALID_URL];
 *  - bounded `connect`, `read`, and overall `call` timeouts (bounded even when
 *    no custom client is supplied);
 *  - cooperative cancellation cancels the underlying [Call] so in-flight work
 *    stops;
 *  - HTTP non-2xx -> [HdcBridgeErrorKind.HTTP]; unparsable body ->
 *    [HdcBridgeErrorKind.MALFORMED_JSON]; timeout -> [HdcBridgeErrorKind.TIMEOUT];
 *    I/O -> [HdcBridgeErrorKind.IO]; cancellation -> CANCELLED;
 *  - **no secret logging**: logs only the action name and the response summary
 *    (which contains the bridge `message`/`status`, never credentials) and the
 *    hostname of the health URL — never queries, payloads, keys, or tokens.
 *
 * [payload] is encoded via kotlinx-serialization into the `{action,payload}`
 * envelope the Python bridge expects, tolerating strings, numbers, booleans,
 * null, nested maps, and lists.
 */
class OkHttpHdcBridgeClient(
    baseUrl: String,
    client: OkHttpClient = defaultClient(),
) : HdcBridgeClient {

    private val healthUrl: String = HdcUrlNormalizer.healthUrl(baseUrl)
    private val workflowUrl: String = HdcUrlNormalizer.workflowUrl(baseUrl)
    private val client: OkHttpClient = client
    private val json: Json = Json { ignoreUnknownKeys = true }

    override suspend fun health(): HdcBridgeResult {
        if (healthUrl.isEmpty()) {
            return HdcBridgeResult.Error(HdcBridgeErrorKind.INVALID_URL, "HDC bridge base URL could not be normalized")
        }
        val request = Request.Builder().url(healthUrl).get().build()
        return execute(request) { bodyText ->
            val obj = parseObject(bodyText)
            if (obj == null) {
                HdcBridgeResult.Error(HdcBridgeErrorKind.MALFORMED_JSON, "health response was not valid JSON")
            } else {
                HdcBridgeResult.Health(HdcBridgeParsing.health(obj))
            }
        }
    }

    override suspend fun workflow(action: String, payload: Map<String, Any?>): HdcBridgeResult {
        if (workflowUrl.isEmpty()) {
            return HdcBridgeResult.Error(HdcBridgeErrorKind.INVALID_URL, "HDC bridge base URL could not be normalized")
        }
        val bodyJson = buildJsonObject {
            put("action", action)
            put("payload", toJsonElement(payload))
        }
        val request = Request.Builder()
            .url(workflowUrl)
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request) { bodyText ->
            val obj = parseObject(bodyText)
            if (obj == null) {
                HdcBridgeResult.Error(HdcBridgeErrorKind.MALFORMED_JSON, "workflow response was not valid JSON")
            } else {
                HdcBridgeResult.Response(HdcBridgeParsing.wire(obj))
            }
        }
    }

    /** Runs [fromBody] on a successful 2xx body; maps else to typed errors. */
    private suspend fun execute(request: Request, fromBody: (String) -> HdcBridgeResult): HdcBridgeResult {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { cont ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) {
                        cont.resume(HdcBridgeResult.Error(HdcBridgeErrorKind.CANCELLED, "HDC bridge call cancelled"))
                    } else {
                        failureResume(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val code = resp.code
                        val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                        if (code !in 200..299) {
                            cont.resume(
                                HdcBridgeResult.Error(
                                    HdcBridgeErrorKind.HTTP,
                                    "HDC bridge HTTP ${"$code"}: ${truncate(body)}",
                                )
                            )
                            return
                        }
                        cont.resume(fromBody(body))
                    }
                }

                private fun failureResume(e: IOException) {
                    val kind = classify(e)
                    val message = when (kind) {
                        HdcBridgeErrorKind.CANCELLED -> "HDC bridge call cancelled"
                        HdcBridgeErrorKind.TIMEOUT -> "HDC bridge timed out: ${e.message.orEmpty()}"
                        else -> "HDC bridge request failed: ${e.message.orEmpty()}"
                    }
                    cont.resume(HdcBridgeResult.Error(kind, message))
                }
            })
            cont.invokeOnCancellation { call.cancel() }
        }
    }

    private fun classify(e: IOException): HdcBridgeErrorKind =
        when (e) {
            is SocketTimeoutException -> HdcBridgeErrorKind.TIMEOUT
            is java.net.ConnectException -> HdcBridgeErrorKind.IO
            is java.net.UnknownHostException -> HdcBridgeErrorKind.IO
            // OkHttp can surface a read/call timeout as a generic InterruptedIOException
            // (not a SocketTimeoutException); match on the message so a real deadline
            // is reported as TIMEOUT instead of being conflated with cancellation.
            is InterruptedIOException ->
                if (isTimeoutMessage(e.message)) HdcBridgeErrorKind.TIMEOUT else HdcBridgeErrorKind.CANCELLED
            else -> if (isCancellationMessage(e.message)) HdcBridgeErrorKind.CANCELLED else HdcBridgeErrorKind.IO
        }

    private fun isTimeoutMessage(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return "timeout" in m || "timed out" in m
    }

    private fun isCancellationMessage(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return "cancel" in m
    }

    private fun parseObject(body: String): JsonObject? = runCatching {
        val el = json.parseToJsonElement(body)
        el as? JsonObject
    }.getOrNull()

    private fun truncate(text: String, max: Int = 300): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= max) compact else compact.take(max) + "..."
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** A bounded OkHttp client shared by default (safe on low-RAM hosts). */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        const val CONNECT_TIMEOUT_MS: Long = 8_000L
        const val READ_TIMEOUT_MS: Long = 120_000L
        const val WRITE_TIMEOUT_MS: Long = 10_000L
        const val CALL_TIMEOUT_MS: Long = 130_000L
    }

    // ------------------------------------------------------------------ JSON

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            value.forEach { (k, v) -> put(k?.toString() ?: "", toJsonElement(v)) }
        }
        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonNull
    }
}
