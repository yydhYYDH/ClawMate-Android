package com.clawmate.app.task.hdc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Typed, tolerant models for the PC HDC bridge HTTP protocol, ported from the
 * HarmonyOS contract `utils/HdcWorkflowBridge.ets` and
 * `utils/WorkflowTypes.ets` (reference only). The bridge is a small Python
 * HTTP server (`python/hdc_server.py`) that the app calls with
 * `GET /api/health` and `POST /api/workflow` (body `{action, payload}`).
 *
 * All models parse tolerantly: unknown/missing fields never crash parsing; the
 * client maps malformed/missing values to safe defaults and surfaces the HTTP /
 * malformed-JSON / bridge-`error` status honestly rather than fabricating a
 * connected device.
 */

/** `status` values returned by the bridge (and mirrored on HTTP/parse errors). */
enum class HdcBridgeStatus {
    OK, ERROR, UNKNOWN;

    companion object {
        /** Case-insensitive parse; anything else -> [UNKNOWN]. */
        fun parse(raw: String?): HdcBridgeStatus = when (raw?.trim()?.lowercase()) {
            "ok" -> OK
            "error" -> ERROR
            else -> UNKNOWN
        }
    }
}

/** A `POST /api/workflow` request body: an action plus an optional payload map. */
data class HdcBridgeRequest(
    val action: String,
    val payload: Map<String, Any?> = emptyMap(),
)

/**
 * A single success/error envelope carried back from the bridge. Mirrors the
 * source `HdcBridgeResponse`. [statusEnum], [width], [height] are derived with
 * tolerant parsing so a numeric-as-string or missing field degrades safely.
 */
data class HdcBridgeResponse(
    val status: String = "error",
    val message: String? = null,
    val result: String? = null,
    val imageB64: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val action: String? = null,
    val packageName: String? = null,
    val currentPackageName: String? = null,
    val noReason: Boolean? = null,
    val maxSteps: Int? = null,
) {
    val statusEnum: HdcBridgeStatus get() = HdcBridgeStatus.parse(status)

    /** Short human/diagnostic summary of the response (no secrets ever included). */
    fun summary(): String {
        val messageText = if (message.isNullOrBlank()) "(empty)" else message
        val widthText = width?.toString() ?: "(empty)"
        val heightText = height?.toString() ?: "(empty)"
        val actionText = action?.takeIf { it.isNotBlank() } ?: "(none)"
        return "status=$status, message=$messageText, action=$actionText, width=$widthText, height=$heightText"
    }
}

/**
 * Typed model of the `GET /api/health` (or `health` workflow action) response.
 * The bridge returns a rich payload including HDC target and tunnel fields
 * (`hdc_connected`, `target`, `tunnel_ready`, `fport_ready`, `rport_ready`,
 * `rport_listening`, `rport_listen_check_supported`, `app_server_url`,
 * `app_server_urls`, `pc_server_urls`, `server_port`, `agent_router_port`,
 * `reverse_server_port`, `loop_enabled`, `loop_alive`). Each field is parsed
 * tolerantly and absent/malformed fields degrade to safe defaults.
 */
data class HdcBridgeHealth(
    val status: String = "error",
    val message: String? = null,
    val hdcConnected: Boolean = false,
    val target: String? = null,
    val tunnelReady: Boolean = false,
    val fportReady: Boolean = false,
    val rportReady: Boolean = false,
    val rportListening: Boolean? = null,
    val rportListenCheckSupported: Boolean = false,
    val appServerUrl: String? = null,
    val appServerUrls: List<String> = emptyList(),
    val pcServerUrls: List<String> = emptyList(),
    val serverPort: Int? = null,
    val agentRouterPort: Int? = null,
    val reverseServerPort: Int? = null,
    val loopEnabled: Boolean = false,
    val loopAlive: Boolean = false,
) {
    val statusEnum: HdcBridgeStatus get() = HdcBridgeStatus.parse(status)

    /**
     * A device is considered truly controllable only by a live `ok` reply
     * **and** both HDC forward (`fport_ready`) and reverse (`rport_ready`) port
     * mappings ready. A one-sided tunnel is not usable for GUI automation, so
     * an `ok`-but-partial reply is still honestly not control-ready.
     */
    val controlReady: Boolean get() = statusEnum == HdcBridgeStatus.OK && fportReady && rportReady
}

/**
 * Tolerant parsing of a raw [JsonObject] into typed bridge models.
 * Every read is defensive: a missing, malformed, or wrong-typed member
 * degrades to a safe default without throwing.
 */
internal object HdcBridgeParsing {

    fun wire(json: JsonObject): HdcBridgeResponse {
        val width = json.numberBody("width")
        val height = json.numberBody("height")
        val maxSteps = json.numberBody("max_steps")
        return HdcBridgeResponse(
            status = json.stringBody("status") ?: "error",
            message = json.stringBody("message"),
            result = json.stringBody("result"),
            imageB64 = json.stringBody("image_b64"),
            width = width?.toInt(),
            height = height?.toInt(),
            action = json.stringBody("action"),
            packageName = json.stringBody("package_name"),
            currentPackageName = json.stringBody("current_package_name"),
            noReason = json.boolBody("no_reason"),
            maxSteps = maxSteps?.toInt(),
        )
    }

    fun health(json: JsonObject): HdcBridgeHealth = HdcBridgeHealth(
        status = json.stringBody("status") ?: "error",
        message = json.stringBody("message"),
        hdcConnected = json.boolBody("hdc_connected") ?: false,
        target = json.stringBody("target"),
        tunnelReady = json.boolBody("tunnel_ready") ?: false,
        fportReady = json.boolBody("fport_ready") ?: false,
        rportReady = json.boolBody("rport_ready") ?: false,
        rportListening = json.boolBody("rport_listening"),
        rportListenCheckSupported = json.boolBody("rport_listen_check_supported") ?: false,
        appServerUrl = json.stringBody("app_server_url"),
        appServerUrls = json.stringListBody("app_server_urls"),
        pcServerUrls = json.stringListBody("pc_server_urls"),
        serverPort = json.numberBody("server_port")?.toInt(),
        agentRouterPort = json.numberBody("agent_router_port")?.toInt(),
        reverseServerPort = json.numberBody("reverse_server_port")?.toInt(),
        loopEnabled = json.boolBody("loop_enabled") ?: false,
        loopAlive = json.boolBody("loop_alive") ?: false,
    )
}

internal fun JsonObject.stringBody(key: String): String? {
    val el = this[key] ?: return null
    if (el !is JsonPrimitive) return null
    return when {
        el.isString -> el.content
        el.booleanOrNull != null -> el.booleanOrNull.toString()
        el.floatOrNull != null -> el.content // preserve numeric text e.g. "0.5"
        else -> el.contentOrNull
    }
}

internal fun JsonObject.boolBody(key: String): Boolean? {
    val el = this[key] ?: return null
    if (el is JsonPrimitive) {
        val b = el.booleanOrNull
        if (b != null) return b
        val text = el.contentOrNull?.trim()?.lowercase()
        return when (text) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }
    return null
}

/** Parses a number member, tolerating numeric strings like "1000". */
internal fun JsonObject.numberBody(key: String): Double? {
    val el = this[key] ?: return null
    if (el is JsonPrimitive) {
        val n = el.floatOrNull
        if (n != null) return n.toDouble()
        val text = el.contentOrNull?.trim()
        if (text != null) return text.toDoubleOrNull()
    }
    return null
}

internal fun JsonObject.stringListBody(key: String): List<String> {
    val el = this[key] ?: return emptyList()
    if (el !is kotlinx.serialization.json.JsonArray) return emptyList()
    return el.mapNotNull { it.stringBody() }
}

internal fun JsonElement.stringBody(): String? {
    if (this !is JsonPrimitive) return null
    if (isString) return content
    val text = contentOrNull ?: return null
    return when {
        text == "null" -> null
        text == "true" || text == "false" -> text
        text.toDoubleOrNull() != null -> text
        else -> text
    }
}
