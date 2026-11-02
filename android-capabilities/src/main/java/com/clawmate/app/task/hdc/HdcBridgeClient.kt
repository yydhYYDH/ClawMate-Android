package com.clawmate.app.task.hdc

/**
 * Result contract of a single HDC bridge operation. Mirrors the HTTP reality:
 * a success carries the decoded [HdcBridgeResponse] (whose `status` may still
 * be `error` when the bridge ran but replied non-ok); transport failures
 * (HTTP non-2xx, malformed JSON, I/O/timeout/cancellation) carry a typed
 * [HdcBridgeError] instead. Nothing here ever logs or carries secrets.
 */
sealed class HdcBridgeResult {

    /** The bridge answered with a 2xx body that parsed as bridge JSON. */
    data class Response(val value: HdcBridgeResponse) : HdcBridgeResult()

    /** The bridge answered with a 2xx body that parsed into a health envelope. */
    data class Health(val value: HdcBridgeHealth) : HdcBridgeResult()

    /** The request could not succeed: transport / HTTP / parse / cancellation. */
    data class Error(val kind: HdcBridgeErrorKind, val message: String) : HdcBridgeResult()

    companion object {
        /** @return the raw bridge response when this was a [Response], else null. */
        fun HdcBridgeResult.asResponseOrNull(): HdcBridgeResponse? = (this as? Response)?.value
    }
}

/** Typed transport-level failure kinds, kept separate from bridge `error` status. */
enum class HdcBridgeErrorKind {
    /** HTTP status was not 2xx. */
    HTTP,
    /** The body could not be parsed as bridge JSON. */
    MALFORMED_JSON,
    /** Connect / read / call timeout exceeded. */
    TIMEOUT,
    /** Network I/O error (connect refused, DNS, reset, TLS). */
    IO,
    /** The caller cancelled the call (cooperative coroutine cancellation). */
    CANCELLED,
    /** The configured base URL could not be normalized to http(s). */
    INVALID_URL,
    /** An unexpected/unclassified failure occurred. Not a secret, just context. */
    UNKNOWN,
}

/**
 * Typed client boundary for the PC HDC bridge.
 *
 * Implementations must:
 *  - normalize the base URL via [com.clawmate.app.task.hdc.HdcUrlNormalizer] and
 *    refuse un-normalizable inputs as [HdcBridgeErrorKind.INVALID_URL];
 *  - call `GET {base}/api/health` and `POST {base}/api/workflow`;
 *  - enforce bounded connect/read/call timeouts;
 *  - support cooperative cancellation;
 *  - map HTTP errors, malformed JSON, timeouts, and I/O errors to typed
 *    [HdcBridgeError];
 *  - never log secrets (URLs hold no credentials; payloads/messages never
 *    include keys).
 *
 * This is intentionally a plain interface so disabled-DI, fakes, and the OkHttp
 * production implementation, plus tests, agree on one contract.
 */
interface HdcBridgeClient {

    /** `GET {base}/api/health` -> a health envelope or a typed error. */
    suspend fun health(): HdcBridgeResult

    /** `POST {base}/api/workflow` with a single `{action,payload}` body. */
    suspend fun workflow(action: String, payload: Map<String, Any?> = emptyMap()): HdcBridgeResult
}
