package com.clawmate.app.task.hdc

import java.net.URI

/**
 * Deterministic URL normalization for the PC HDC bridge endpoints.
 *
 * Ports (and hardens) the HarmonyOS `HdcWorkflowBridge.workflowUrl()` logic:
 * trim the base, collapse a leading scheme-relative `//`, strip trailing `/`
 * and whitespace, default a scheme-less input to `http`, reject any scheme
 * other than `http`/`https`, and drop a trailing empty port. The result is a
 * canonical `scheme://host[:port]/<path>` used to build:
 *   `.../api/health`  for `GET /api/health`
 *   `.../api/workflow` for `POST /api/workflow`
 *
 * Every function is a pure total function: it never throws and returns safe
 * values for input it cannot normalize, so callers (and unit tests) get one
 * deterministic answer.
 */
object HdcUrlNormalizer {

    /** Build the `GET /api/health` URL, or empty string when un-normalizable. */
    fun healthUrl(baseUrl: String): String = endpointUrl(baseUrl, HEALTH_PATH)

    /** Build the `POST /api/workflow` URL, or empty string when un-normalizable. */
    fun workflowUrl(baseUrl: String): String = endpointUrl(baseUrl, WORKFLOW_PATH)

    /**
     * Normalize a bare base URL to `scheme://host[:port]/path` (no trailing
     * slash, no query or fragment), or empty string when un-normalizable.
     */
    fun normalizeBaseUrl(baseUrl: String): String {
        val prepared = prepare(baseUrl)
        if (prepared == null) return ""
        return runCatching { canonicalBase(prepared) }.getOrElse { "" }
    }

    private const val HEALTH_PATH = "api/health"
    private const val WORKFLOW_PATH = "api/workflow"

    private fun endpointUrl(baseUrl: String, path: String): String {
        val base = normalizeBaseUrl(baseUrl)
        if (base.isEmpty()) return ""
        return "$base/$path"
    }

    /**
     * Trim whitespace, collapse a leading `//`, strip trailing slashes; add a
     * default `http` scheme when none is present; reject non-http(s) schemes.
     * Returns null when the input is unusable.
     */
    private fun prepare(raw: String): String? {
        var v = raw.trim()
        if (v.isEmpty()) return null
        if (v.startsWith("//")) v = v.substring(2)
        v = stripTrailingSlashes(v)
        if (v.isEmpty()) return null

        val scheme = v.substringBefore("://", "").lowercase()
        if (scheme.isNotEmpty()) {
            if (scheme != "http" && scheme != "https") return null
            // Drop a trailing empty port like `http://host:` -> `http://host`.
            v = v.replace(Regex("""(https?://[^:/]+):$"""), "$1")
        } else {
            v = "http://$v"
        }
        return v
    }

    private fun canonicalBase(prepared: String): String {
        val uri = URI(prepared)
        val scheme = uri.scheme?.lowercase() ?: return ""
        if (scheme != "http" && scheme != "https") return ""
        val host = uri.host ?: return ""
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = stripTrailingSlashes(uri.path ?: "")
        return "$scheme://$host$port$path"
    }

    private fun stripTrailingSlashes(value: String): String {
        var v = value
        while (v.endsWith("/") || v.endsWith(" ")) v = v.dropLast(1)
        return v
    }
}
