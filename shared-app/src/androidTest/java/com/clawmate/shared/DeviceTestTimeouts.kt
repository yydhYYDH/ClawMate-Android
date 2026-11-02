package com.clawmate.shared

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/** Explicit test-only budget for full-system ARM64 emulation; phone defaults stay strict. */
internal object DeviceTestTimeouts {
    val multiplier: Int by lazy {
        InstrumentationRegistry.getArguments().getString("deviceTimeoutMultiplier", "1")
            .toInt().also { require(it in 1..5) { "deviceTimeoutMultiplier must be between 1 and 5" } }
    }

    fun seconds(value: Long): Long = value * multiplier

    /** Main navigation has stable virtual nodes; conditional page buttons still use OCR. */
    fun clickNavigation(text: String): Boolean {
        require(text in setOf("首页", "汇总", "聊天", "任务", "我的"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        val nodes = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (!bounds.isEmpty) {
                    val command = "input tap ${bounds.centerX()} ${bounds.centerY()}"
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
                    Thread.sleep(300)
                    return true
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { nodes.add(it) }
        }
        return false
    }

    /** Reject OCR coordinates if the page moved while recognition was running. */
    fun targetStillCurrent(reference: Bitmap, bounds: Rect): Boolean {
        if (multiplier == 1) return true
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val current = instrumentation.uiAutomation.takeScreenshot() ?: return false
        try {
            val region = Rect(bounds)
            if (current.width != reference.width || current.height != reference.height ||
                !region.intersect(0, 0, reference.width, reference.height) || region.isEmpty) return false
            val before = IntArray(region.width() * region.height())
            val after = IntArray(before.size)
            reference.getPixels(before, 0, region.width(), region.left, region.top, region.width(), region.height())
            current.getPixels(after, 0, region.width(), region.left, region.top, region.width(), region.height())
            if (before.contentEquals(after)) return true
            instrumentation.sendStatus(2, Bundle().apply { putString("staleOcrTarget", region.toShortString()) })
            return false
        } finally { current.recycle() }
    }
}
