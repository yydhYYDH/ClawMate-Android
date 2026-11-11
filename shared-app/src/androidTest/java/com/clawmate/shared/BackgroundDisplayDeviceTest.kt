package com.clawmate.shared

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.platform.display.BackgroundDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run explicitly with -e backgroundDisplayProbe fixture (or xhs), then attach the adb shell host. */
class BackgroundDisplayDeviceTest {
    @Test
    fun startsAnApprovedAppOnItsOwnDisplay() {
        val probe = InstrumentationRegistry.getArguments().getString("backgroundDisplayProbe")
        assumeTrue(probe == "fixture" || probe == "xhs")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val main = instrumentation.startActivitySync(
            Intent(context, SharedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val keyword = "上海城市漫步"
        val pkg = if (probe == "fixture") "com.clawmate.gui.fixture" else "com.xingin.xhs"
        val target = if (probe == "fixture") {
            checkNotNull(context.packageManager.getLaunchIntentForPackage(pkg))
        } else {
            Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("xhsdiscover").authority("search")
                .appendPath("result").appendQueryParameter("keyword", keyword).build()).setPackage(pkg)
        }
        fun requireMainFocus() {
            instrumentation.runOnMainSync { assertTrue("Main window lost focus", main.hasWindowFocus()) }
        }
        fun displays(): Set<Int> = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("cmd display get-displays -i"),
        ).bufferedReader().useLines { lines -> lines.mapNotNull { it.trim().toIntOrNull() }.toSet() }
        try {
            await("shell helper connection", 35_000) { BackgroundDisplay.available.takeIf { it } }
            requireMainFocus()
            val display = BackgroundDisplay.open(target)
            val id = display.id
            display.use {
                assertTrue(id in displays())
                fun targetRoot(): AccessibilityNodeInfo? = automation.windowsOnAllDisplays.get(id).orEmpty()
                    .filter { window -> window.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .singleOrNull()?.root?.takeIf { root -> root.packageName?.toString() == pkg }
                val root = await("target on the assigned virtual display") { targetRoot() }
                requireMainFocus()
                if (probe == "fixture") {
                    val scroll = await("fixture scroll node") { targetRoot()?.let(::findScroll) }
                    assertTrue(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                    await("fixture scroll offset changed") {
                        targetRoot()?.let(::findScroll)?.contentDescription?.toString()
                            ?.takeIf { it.startsWith("Fixture scroll offset: ") && !it.startsWith("Fixture scroll offset: 0,") }
                    }
                } else {
                    await("public search keyword and search action") {
                        targetRoot()?.takeIf {
                            it.findAccessibilityNodeInfosByText(keyword).any { node -> node.text?.toString() == keyword } &&
                                it.findAccessibilityNodeInfosByText("搜索").any { node -> node.text?.toString() == "搜索" && node.isClickable }
                        }
                    }
                }
                assertEquals(pkg, root.packageName.toString())
                requireMainFocus()
            }
            await("virtual display released") { true.takeIf { id !in displays() } }
        } finally {
            instrumentation.runOnMainSync { main.finish() }
        }
    }

    private fun findScroll(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isScrollable &&
            node.contentDescription?.toString()?.startsWith("Fixture scroll offset: ") == true) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let(::findScroll)?.let { return it }
        }
        return null
    }

    private fun <T : Any> await(label: String, timeoutMs: Long = 12_000, value: () -> T?): T {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        do {
            value()?.let { return it }
            SystemClock.sleep(150)
        } while (SystemClock.elapsedRealtime() < end)
        error("Timed out waiting for $label")
    }
}
