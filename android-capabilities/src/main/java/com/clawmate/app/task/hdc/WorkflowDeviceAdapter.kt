package com.clawmate.app.task.hdc

import com.clawmate.app.wechat.domain.WechatAutoSummaryRequest

/**
 * Result of a single device-control action through a [WorkflowDeviceAdapter].
 *
 * Safety invariant: a [Success] is produced only when the bridge itself
 * returned an `ok` envelope; a [BridgeError] is an honest envelope whose
 * `status` is `error`; a [TransportError] is a typed transport/parse failure;
 * and [Unsupported] describes a step the configured adapter is not allowed to
 * perform (the default disabled adapter emits these for every action). The
 * WorkflowRunner translates these into its own [com.clawmate.app.task.runner
 * .RunStatus] (SUCCESS / FAILED / NOT_CONFIGURED / BLOCKED) without ever
 * fabricating device control.
 */
sealed class HdcActionResult {

    /** The bridge replied `status=ok` with a usable response. */
    data class Success(val response: HdcBridgeResponse) : HdcActionResult()

    /** The bridge replied 2xx but with a non-ok envelope (honest failure). */
    data class BridgeError(val response: HdcBridgeResponse) : HdcActionResult()

    /** Transport/HTTP/parse/cancellation failure (no bridge envelope). */
    data class TransportError(val kind: HdcBridgeErrorKind, val message: String) : HdcActionResult()

    /** The device-control step is not permitted/supported by this adapter. */
    data class Unsupported(val reason: String) : HdcActionResult()
}

/**
 * Boundary between the bounded [com.clawmate.app.task.runner.WorkflowRunner]
 * and a concrete device-control backend.
 *
 * Only the explicitly supported *safe* protocol actions are exposed:
 *  - [health]                        -> `GET /api/health` (or hub `health`)
 *  - [startApp]                      -> `app_start`
 *  - [screenshot]                    -> `screenshot`
 *  - [guiAction]                     -> `gui_action`
 *  - [executeDeciderAction]          -> `execute_decider_action`
 *  - [wechatCollect]                 -> `wechat_collect`
 *
 * It must **never** send `/api/run_cmd` or arbitrary command strings, and
 * must **never** itself execute shell/adb/hdc/accessibility or spawn processes.
 * The runner only surfaces GUI/HDC results/errors when a caller explicitly
 * provides a configured adapter; the default [DisabledWorkflowDeviceAdapter]
 * leaves every GUI step UNSUPPORTED/BLOCKED.
 */
interface WorkflowDeviceAdapter {

    /** True only when the adapter holds a validated, live bridge health reply. */
    val isConfigured: Boolean

    suspend fun health(): HdcActionResult

    suspend fun startApp(appName: String?, packageName: String?, resetFirst: Boolean = true): HdcActionResult

    suspend fun screenshot(factor: Double = 0.5): HdcActionResult

    suspend fun guiAction(payload: Map<String, Any?>): HdcActionResult

    suspend fun executeDeciderAction(responseText: String, width: Int, height: Int): HdcActionResult

    /** Sends a fixed `wechat_collect` action with the WeChat auto-summary request payload. */
    suspend fun wechatCollect(request: WechatAutoSummaryRequest): HdcActionResult
}

/**
 * The default adapter used by the WorkflowRunner unless a caller explicitly
 * supplies a configured one. Every action reports [HdcActionResult.Unsupported]
 * with an honest reason; [isConfigured] is always false. This is what keeps
 * GUI/HDC workflow steps BLOCKED (never fabricated) absent a real bridge.
 */
object DisabledWorkflowDeviceAdapter : WorkflowDeviceAdapter {
    override val isConfigured: Boolean = false

    private const val REASON =
        "GUI/HDC device control requires a configured PC HDC bridge; no configured adapter is available on Android."

    override suspend fun health() = HdcActionResult.Unsupported(REASON)
    override suspend fun startApp(appName: String?, packageName: String?, resetFirst: Boolean) =
        HdcActionResult.Unsupported(REASON)
    override suspend fun screenshot(factor: Double) = HdcActionResult.Unsupported(REASON)
    override suspend fun guiAction(payload: Map<String, Any?>) = HdcActionResult.Unsupported(REASON)
    override suspend fun executeDeciderAction(responseText: String, width: Int, height: Int) =
        HdcActionResult.Unsupported(REASON)
    override suspend fun wechatCollect(request: WechatAutoSummaryRequest) =
        HdcActionResult.Unsupported(REASON)
}
