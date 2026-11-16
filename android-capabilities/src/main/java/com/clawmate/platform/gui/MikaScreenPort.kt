package com.clawmate.platform.gui

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.cap.phone.PHONE_SNAPSHOT_PREFIX
import com.wangzan101.mika.core.uistate.DOUBLE_TAP_GAP_MS
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.delay

/** 单次点按的手势时长；够短，系统会当成一次点击而不是长按。 */
private const val TAP_MS = 60L

@RequiresApi(34)
internal class MikaScreenPort(
    private val service: GuiControlService,
    private val run: MikaPhoneRun,
    private val target: String,
    private val label: String,
) : ScreenPort {
    private var sequence = 0
    private var previous: MikaFrame? = null

    override suspend fun checkAppAccess() { service.mikaWindow(run) }
    override suspend fun listApps() = listOf(target to label)
    override suspend fun screenshotPng(): ByteArray? = service.mikaScreenshot(run)
    override suspend fun snapshot() = readFrame().also { previous = it }.screen
    override suspend fun peek() = readFrame().screen

    private fun readFrame(): MikaFrame {
        service.clearCache()
        val window = service.mikaWindow(run)
        val bounds = service.mikaBounds(window)
        val root = checkNotNull(window.root) { "无法读取目标窗口" }
        return readMikaFrame(root, bounds, target, PHONE_SNAPSHOT_PREFIX + ++sequence)
    }

    private fun act(ref: String?, snapshotId: String?, action: Int, args: Bundle? = null,
        eligible: (UiElement) -> Boolean,
    ): ActionResult {
        val verified = previous ?: return ActionResult.Failed("observe_first")
        if (ref != null && snapshotId != verified.screen.snapshotId) return ActionResult.Failed("stale_snapshot")
        val candidates = verified.nodes.filter { eligible(it.description) && (ref == null || it.description.ref == ref) }
        val expected = candidates.singleOrNull() ?: return ActionResult.Failed("target_missing_or_ambiguous")
        val current = readFrame()
        if (current.window != verified.window) return ActionResult.Failed("stale_snapshot")
        val match = current.nodes.singleOrNull {
            it.node == expected.node && it.description.copy(ref = expected.description.ref) == expected.description
        } ?: return ActionResult.Failed("stale_snapshot")
        if (match.node.actionList.none { it.id == action }) return ActionResult.Failed("action_not_supported")
        return service.mikaAction(run, current.window, match.node, action, args)
    }

    override suspend fun tapRef(ref: String, snapshotId: String) =
        act(ref, snapshotId, AccessibilityNodeInfo.ACTION_CLICK) { it.clickable }

    /** 坐标点按：与读屏同一坐标系里的像素点，交给无障碍手势执行（浮窗、内容区域由服务端把关）。 */
    override suspend fun tapXY(x: Int, y: Int): ActionResult = tapPoint(x, y)

    override suspend fun doubleTapXY(x: Int, y: Int): ActionResult {
        val first = tapPoint(x, y)
        if (first !is ActionResult.Ok) return first
        delay(DOUBLE_TAP_GAP_MS)
        return tapPoint(x, y)
    }

    private suspend fun tapPoint(x: Int, y: Int): ActionResult =
        service.mikaGesture(run, previous?.window, listOf(listOf(x to y, x to y)), TAP_MS)

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult =
        service.mikaGesture(run, previous?.window, listOf(listOf(x1 to y1, x2 to y2)), durationMs)

    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): ActionResult =
        service.mikaGesture(run, previous?.window, paths, durationMs)

    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
        if (!MikaInput.allowed(run.goal, text)) return ActionResult.Failed("enter_only_text_from_user_request")
        val data = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return act(ref, snapshotId, AccessibilityNodeInfo.ACTION_SET_TEXT, data) { it.editable && (ref != null || it.focused) }
    }

    override suspend fun clearText(ref: String?, snapshotId: String?) = typeText(ref, snapshotId, "")
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult {
        val orientation = if (down) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN else
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
        val result = act(ref, snapshotId, orientation.id) { it.scrollable }
        if (result != ActionResult.Failed("action_not_supported") || ref == null) return result
        val fallback = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return act(ref, snapshotId, fallback) { it.scrollable }
    }

    override suspend fun back(): ActionResult {
        val window = service.mikaWindow(run)
        return service.mikaAction(run, window.id, null, AccessibilityService.GLOBAL_ACTION_BACK)
    }

    override suspend fun pressKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> back()
        KeyEvent.KEYCODE_ENTER -> act(null, null, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) {
            it.focused && it.editable
        }
        else -> ActionResult.Failed("unsupported_key")
    }

    override suspend fun openApp(packageName: String, cold: Boolean) =
        if (packageName == target && !cold) {
            service.mikaWindow(run)
            ActionResult.Ok(changed = false)
        } else ActionResult.Failed("app_not_approved")

    override suspend fun waitStable(timeoutMs: Long): Boolean {
        val first = peek()
        delay(timeoutMs.coerceIn(0, 260))
        val second = peek()
        return first.elements == second.elements
    }
}
