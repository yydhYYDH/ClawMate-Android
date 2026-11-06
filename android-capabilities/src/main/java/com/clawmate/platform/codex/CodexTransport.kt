package com.clawmate.platform.codex

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer

/**
 * OkHttp transport for the ClawMate Codex bridge.
 *
 * Replaces the HarmonyOS `@kit.RemoteCommunicationKit` session in
 * `CodexClient.ets`. The behavioural contract is the important part:
 *
 *  - **No redirects.** `followRedirects`/`followSslRedirects` are off so the bearer token
 *    can never be replayed to another host; a 3xx is a configuration error.
 *  - **No automatic resend.** OkHttp's connection retry is disabled and [request] only
 *    retries once when the call is an idempotent read (GET with no body) and the failure is
 *    transient. A lost response for a turn, upload, approval, interrupt or pairing must be
 *    reconciled by reading task state, never by resending.
 *  - **Generation isolation.** Every call is stamped with the current generation; [close]
 *    bumps it, cancels outstanding calls, and any late response is discarded rather than
 *    written into a replacement connection.
 *  - **Certificate pinning per connection.** A pairing-supplied CA is used only for that
 *    connection. The system trust store stays in force otherwise and there is no
 *    "accept anything" fallback.
 *  - **Bounded bodies.** Responses above [MAX_RESPONSE_BYTES] are rejected.
 *
 * Tokens are never placed in a URL, logged, or echoed in an error message.
 */
class CodexTransport(
    /** Builds the client for one connection; see [defaultClient]. */
    private val clientFactory: (caCertificate: String) -> OkHttpClient = { ca -> defaultClient(ca) },
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
        // The bridge validates `Array.isArray(body.attachments)` and rejects a missing list.
        // Serializing defaults keeps empty collections and known modes on the wire.
        encodeDefaults = true
    }

    /** Encoder shared with [postTyped] and [post]. */
    @PublishedApi
    internal val encoder: Json get() = json

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var baseUrl: String = ""

    @Volatile
    private var token: String = ""

    @Volatile
    private var generation: Int = 0

    private val active = LinkedHashMap<Call, Int>()

    @Volatile
    private var gzipEnabled: Boolean = true

    @Volatile
    private var chunkBytes: Long = DEFAULT_CHUNK_BYTES

    /** Receives reachability transitions from transport failures only. */
    @Volatile
    var onReachability: (reachable: Boolean, message: String) -> Unit = { _, _ -> }

    /**
     * Test hook invoked immediately after a successful [configure].
     *
     * Production never sets it; the transport tests use it to point a configured connection
     * at a loopback server without weakening the `https://` rule in [configure].
     */
    @Volatile
    internal var onConfigureForTest: (() -> Unit)? = null

    /**
     * Installs [connection] and discards any previous one.
     *
     * @throws CodexValidationException when the origin, token or CA is malformed.
     */
    @Synchronized
    fun configure(connection: CodexConnection) {
        close()
        val origin = CodexTypes.normalizeOrigin(connection.origin)
        if (!CodexTypes.isValidToken(connection.token)) {
            throw CodexValidationException("桥接令牌格式无效，请使用服务器生成的 token")
        }
        if (connection.caCertificate.isNotEmpty() && !CodexTypes.isValidCa(connection.caCertificate)) {
            throw CodexValidationException("配对证书格式无效")
        }
        baseUrl = origin
        token = connection.token
        client = clientFactory(connection.caCertificate)
        generation += 1
        onConfigureForTest?.invoke()
    }

    /** Drops the connection, cancels every outstanding call and forgets the token. */
    @Synchronized
    fun close() {
        generation += 1
        val calls = active.keys.toList()
        active.clear()
        calls.forEach { runCatching { it.cancel() } }
        client = null
        token = ""
        baseUrl = ""
        gzipEnabled = true
        chunkBytes = DEFAULT_CHUNK_BYTES
    }

    /** Cancels only the long-poll update calls, leaving other requests connected. */
    @Synchronized
    fun cancelTaskUpdates() {
        val stale = active.entries.filter { it.key.request().url.queryParameter("waitMs") != null }.map { it.key }
        stale.forEach {
            active.remove(it)
            runCatching { it.cancel() }
        }
    }

    fun isConfigured(): Boolean = token.isNotEmpty() && baseUrl.isNotEmpty()

    /** True when this transport already targets [origin]. */
    @Synchronized
    fun isConfiguredFor(origin: String): Boolean =
        token.isNotEmpty() && baseUrl.isNotEmpty() && baseUrl == origin

    /** Returns the origin this transport currently targets, or empty when unconfigured. */
    @Synchronized
    fun origin(): String = baseUrl

    /**
     * Points an already-configured connection at a loopback server.
     *
     * Test seam only: [configure] always enforces `https://`, and nothing in the app calls
     * this. It exists so the transport contract can be exercised against a local plain-HTTP
     * server without weakening the production origin rule.
     */
    @Synchronized
    internal fun useHttpOriginForTest(origin: String) {
        if (!origin.startsWith("http://127.0.0.1") && !origin.startsWith("http://localhost")) {
            throw CodexValidationException("测试入口只接受回环地址")
        }
        baseUrl = origin
    }

    fun artifactChunkBytes(): Long = chunkBytes

    /** Adopts the server-advertised chunk size, bounded to its accepted range. */
    fun setArtifactChunkBytes(bytes: Long) {
        chunkBytes = if (bytes in MIN_CHUNK_BYTES..MAX_CHUNK_BYTES) bytes else DEFAULT_CHUNK_BYTES
    }

    /**
     * `GET [route]`.
     *
     * @param timeoutMs override for callers whose platform side limits how long one call may
     *   block; clamped to [MIN_TIMEOUT_MS]..[CALL_TIMEOUT_MS].
     * @throws CodexTransportException on cancellation, timeout, redirect, auth failure or transport loss.
     * @throws CodexHttpException when the bridge answers with a non-2xx status.
     */
    suspend fun get(route: String, authenticated: Boolean = true, timeoutMs: Long = 0L): CodexEnvelope =
        execute(route = route, body = null, authenticated = authenticated, timeoutMs = timeoutMs)

    /**
     * `POST [route]` with a JSON body. Never retried: see the class contract.
     */
    suspend fun post(
        route: String,
        body: JsonElement,
        authenticated: Boolean = true,
        timeoutMs: Long = 0L,
    ): CodexEnvelope = execute(
        route = route,
        body = json.encodeToString(JsonElement.serializer(), body),
        authenticated = authenticated,
        timeoutMs = timeoutMs,
    )

    /** `POST [route]` with a typed body. Never retried. */
    suspend inline fun <reified T> postTyped(route: String, body: T, authenticated: Boolean = true): CodexEnvelope =
        post(route, encoder.encodeToJsonElement(serializer<T>(), body), authenticated)

    private suspend fun execute(
        route: String,
        body: String?,
        authenticated: Boolean,
        timeoutMs: Long = 0L,
    ): CodexEnvelope {
        val generationAtStart = generation
        // Only a read may be retried, and only once.
        val retryable = body == null && !route.contains("waitMs=")
        var attempt = 0
        while (true) {
            try {
                return fetchOnce(route, body, authenticated, generationAtStart, timeoutMs)
            } catch (e: CodexTransportException) {
                val mayRetry = retryable && attempt == 0 && e.retryable && generation == generationAtStart
                if (!mayRetry) throw e
                attempt += 1
                kotlinx.coroutines.delay(RETRY_DELAY_MS + (0..RETRY_DELAY_JITTER_MS).random())
                if (generation != generationAtStart) throw CodexConnectionChangedException()
            }
        }
    }

    private suspend fun fetchOnce(
        route: String,
        body: String?,
        authenticated: Boolean,
        generationAtStart: Int,
        requestedTimeoutMs: Long = 0L,
    ): CodexEnvelope {
        if (!isConfigured()) throw CodexValidationException("请先连接 Codex 服务器")
        val http = client ?: throw CodexValidationException("请先连接 Codex 服务器")
        val url = try {
            (baseUrl + route).toHttpUrl()
        } catch (e: IllegalArgumentException) {
            throw CodexValidationException("连接地址无效，请重新配对")
        }
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        // Accept-Encoding is deliberately not set here. OkHttp only inflates a gzip response
        // when it added the header itself; setting it manually returns the raw compressed
        // bytes, which the bridge sends for any body of 1024 bytes or more.
        if (!gzipEnabled) builder.header("Accept-Encoding", "identity")
        if (authenticated) builder.header("Authorization", "Bearer $token")
        if (body == null) builder.get() else builder.post(body.toRequestBody(JSON_MEDIA_TYPE))

        val call = http.newCall(builder.build())
        synchronized(this) { active[call] = generationAtStart }
        val timeoutMs = clampTimeout(requestedTimeoutMs, if (route == "/v1/health") HEALTH_TIMEOUT_MS else CALL_TIMEOUT_MS)
        try {
            val response = await(call, timeoutMs)
            if (generation != generationAtStart) throw CodexConnectionChangedException()
            response.use { resp -> return interpret(resp, generationAtStart) }
        } finally {
            synchronized(this) { active.remove(call) }
        }
    }

    private fun interpret(response: Response, generationAtStart: Int): CodexEnvelope {
        val code = response.code
        // Status classification comes first: a 3xx/401/429 body is irrelevant and may be
        // empty, so reporting "no response body" there would hide the real cause.
        when {
            code in 300..399 -> throw CodexValidationException("服务器重定向已拒绝，请填写最终 HTTPS 地址")
            code == 401 -> {
                onReachability(false, "连接授权已失效，请重新配对")
                throw CodexAuthException()
            }
            code == 429 -> throw CodexHttpException(429, "请求过于频繁，请稍后重试", false)
        }
        val text = bodyText(response) ?: throw CodexTransportException(
            if (verificationNeedFor(response) == "gzip") {
                // A compressed body that cannot be read means this device cannot inflate it.
                gzipEnabled = false
                "压缩响应未能解析，将改用普通传输，请同步会话后重试"
            } else {
                "服务器未返回有效的文本响应，请稍后重试"
            },
            retryable = true,
        )
        onReachability(true, "")
        val envelope: CodexEnvelope = try {
            json.decodeFromString(CodexEnvelope.serializer(), text)
        } catch (e: Exception) {
            // Include a short excerpt so a protocol mismatch is diagnosable from the message.
            // The body comes from the bridge and never carries the bearer token.
            throw CodexProtocolException(
                "服务器响应不是有效的 Codex 协议数据（${code}）：" + text.take(160).replace('\n', ' '),
            )
        }
        // The bridge reports a failure as a non-2xx status plus `error`. 502/504 are
        // transient and may be retried for reads only.
        if (code !in 200..299) {
            val message = envelope.error.ifEmpty { "服务器请求失败" }
            throw CodexHttpException(code, message, code == 502 || code == 504)
        }
        return envelope
    }

    /**
     * The content encoding that was requested for this response.
     *
     * OkHttp already inflates a `gzip` body it was told about, so a body still labelled
     * `gzip` here means the bytes could not be decoded.
     */
    private fun verificationNeedFor(response: Response): String =
        if (response.header("Content-Encoding").equals("gzip", ignoreCase = true)) "gzip" else "identity"

    /** Returns the decoded body, or `null` when it cannot be used. */
    private fun bodyText(response: Response): String? {
        val bytes = try {
            response.body?.bytes()
        } catch (e: Exception) {
            // A body that cannot be read (unexpected content encoding) is reported as absent
            // so `interpret` can decide whether it is a compression problem.
            return null
        } ?: return null
        if (bytes.isEmpty()) return null
        if (bytes.size > MAX_RESPONSE_BYTES) throw CodexProtocolException("服务器响应过大")
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
        return text.takeIf { it.isNotBlank() }
    }

    /**
     * Awaits [call] with an explicit deadline. A timeout cancels the call so the socket is
     * closed instead of leaking a pending request; a cancelled call is reported as a
     * connection change rather than a timeout.
     */
    private suspend fun await(call: Call, timeoutMs: Long): Response = try {
        withTimeout(timeoutMs) { call.await() }
    } catch (_: TimeoutCancellationException) {
        call.cancel()
        throw CodexTransportException("连接电脑超时，请检查网络后重试；已下发的任务请先同步状态", retryable = true)
    } catch (e: IOException) {
        throw classify(e)
    }

    /** Maps an I/O failure to the transport error the UI reports. */
    fun classify(e: IOException): CodexTransportException = when {        e is SocketTimeoutException -> CodexTransportException("连接电脑超时，请检查网络后重试；已下发的任务请先同步状态", true)
        e is InterruptedIOException && e.message.orEmpty().contains("timeout", true) ->
            CodexTransportException("连接电脑超时，请检查网络后重试；已下发的任务请先同步状态", true)
        else -> {
            // The user-facing text stays generic, but the real cause is logged: a TLS, DNS
            // and plain TCP failure all read the same to the user yet need different fixes.
            android.util.Log.i("ClawMateCodex", "transport failure: " + e.javaClass.simpleName, e)
            CodexTransportException("与电脑的网络连接中断，请稍后重试", true)
        }
    }

    /**
     * Bounds a caller-supplied timeout.
     *
     * A platform bridge call cannot block longer than the host allows, so a request that
     * asked for more is capped instead of being left to time out at the bridge. A zero or
     * negative request keeps [fallback].
     */
    private fun clampTimeout(requested: Long, fallback: Long): Long =
        if (requested <= 0L) fallback else requested.coerceIn(MIN_TIMEOUT_MS, fallback)

    companion object {
        const val CONNECT_TIMEOUT_MS: Long = 25_000
        const val CALL_TIMEOUT_MS: Long = 70_000
        const val HEALTH_TIMEOUT_MS: Long = 18_000
        /** Shortest timeout worth issuing; below this a request should not be attempted. */
        const val MIN_TIMEOUT_MS: Long = 3_000
        const val RETRY_DELAY_MS: Long = 600
        const val RETRY_DELAY_JITTER_MS: Long = 600
        const val DEFAULT_CHUNK_BYTES: Long = 1024 * 1024
        const val MIN_CHUNK_BYTES: Long = 16 * 1024
        const val MAX_CHUNK_BYTES: Long = 1024 * 1024
        const val MAX_RESPONSE_BYTES: Int = 16 * 1024 * 1024

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * A client that never redirects and never resends on its own.
         *
         * @param caCertificate a PEM CA supplied by a pairing file, or empty for the system store.
         */
        fun defaultClient(caCertificate: String, trust: CodexTrustFactory = CodexTrustFactory()): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                // A redirect would move the bearer token to another host.
                .followRedirects(false)
                .followSslRedirects(false)
                // The transport decides what may be retried; the client must not.
                .retryOnConnectionFailure(false)
            if (caCertificate.isNotEmpty()) {
                builder.sslSocketFactory(
                    trust.pinnedSocketFactory(caCertificate, null),
                    trust.pinnedTrustManager(caCertificate),
                )
            }
            return builder.build()
        }
    }
}

/** Base class for transport-level failures. */
open class CodexTransportException(
    message: String,
    val retryable: Boolean = false,
) : Exception(message)

/** Suspends until this call completes, or throws the failure as an [IOException]. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCompleted) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isCompleted) continuation.resume(response) else response.close()
        }
    })
}

/** The bearer token was rejected; the connection must be re-paired. */
class CodexAuthException : CodexTransportException("令牌无效或已撤销，请在连接设置中更新")

/** The connection was replaced while the request was in flight. */
class CodexConnectionChangedException : CodexTransportException("请求已取消或连接已更换")

/** The bridge answered with an error status. */
class CodexHttpException(
    val status: Int,
    message: String,
    retryable: Boolean,
) : CodexTransportException(message, retryable)

/** The bridge answered with something that is not a Codex envelope. */
class CodexProtocolException(message: String) : CodexTransportException(message)



