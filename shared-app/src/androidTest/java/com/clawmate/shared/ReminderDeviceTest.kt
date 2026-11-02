package com.clawmate.shared

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.clawmate.platform.reminder.CollectionReminderEngine
import com.clawmate.platform.reminder.CollectionReminderReceiver
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReminderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun evidence(key: String, text: String) { instrumentation.sendStatus(2, Bundle().apply { putString(key, text) }) }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun tap(bounds: Rect) {
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
        val up = MotionEvent.obtain(time, time + 80, MotionEvent.ACTION_UP, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN; up.source = InputDevice.SOURCE_TOUCHSCREEN
        try { instrumentation.uiAutomation.injectInputEvent(down, true); instrumentation.uiAutomation.injectInputEvent(up, true) }
        finally { down.recycle(); up.recycle() }
        Thread.sleep(300)
    }
    private fun rendered(text: String, click: Boolean): Boolean {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return false
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 15, TimeUnit.SECONDS)
            val line = result.textBlocks.flatMap { it.lines }.firstOrNull { it.text.replace(Regex("\\s+"), "").contains(text) }
            if (line?.boundingBox == null) return false
            if (click) tap(line.boundingBox!!)
            return true
        } finally { recognizer.close(); bitmap.recycle() }
    }
    private fun waitText(text: String, click: Boolean = false) {
        val deadline = SystemClock.elapsedRealtime() + 30000
        while (SystemClock.elapsedRealtime() < deadline) { if (rendered(text, click)) return; Thread.sleep(300) }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.cacheDir, "migration-reminder-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        error("Rendered reminder control missing: $text")
    }
    private fun finishHost() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED).flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<SharedActivity>().distinct().forEach { it.finish() }
        }
        Thread.sleep(500)
    }
    private fun allowPermissionThroughSystemDialog() {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        ActivityScenario.launch(SharedActivity::class.java).use { scenario ->
            Thread.sleep(1500)
            scenario.onActivity { activity ->
                val field = SharedActivity::class.java.getDeclaredField("platform").apply { isAccessible = true }
                val bridge = field.get(activity) as PlatformBridge
                runCatching { bridge.reminderSetEnabled(true) } // First attempt requests permission, remains disabled.
            }
            fun findAllow(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.viewIdResourceName == "com.android.permissioncontroller:id/permission_allow_button") return node
                for (index in 0 until node.childCount) findAllow(node.getChild(index))?.let { return it }
                return null
            }
            var clicked = false
            repeat(100) {
                if (!clicked) {
                    findAllow(instrumentation.uiAutomation.rootInActiveWindow)?.let { node ->
                        clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    if (!clicked) Thread.sleep(100)
                }
            }
            check(clicked) { "System notification permission Allow button not found" }
            repeat(30) { if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) Thread.sleep(100) }
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            CollectionReminderEngine(context).setEnabled(false)
            evidence("reminderPermissionDialog", "Notification permission allowed through the real Android dialog; ADB grant was not used")
        }
    }
    private fun withReminder(allowPermission: Boolean = false, test: (CollectionReminderEngine) -> Unit) {
        if (allowPermission) allowPermissionThroughSystemDialog()
        val prefs = context.getSharedPreferences(CollectionReminderEngine.PREFERENCES, Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        check(!JSONObject(prefs.getString("state", "{}")!!).optBoolean("enabled")) { "Preserve an active user reminder; disable it before this isolated test" }
        val manager = context.getSystemService(NotificationManager::class.java)
        val hadChannel = manager.getNotificationChannel(CollectionReminderEngine.CHANNEL) != null
        val engine = CollectionReminderEngine(context)
        try { test(engine) } finally {
            engine.setEnabled(false)
            finishHost()
            val edit = prefs.edit().clear()
            before.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
                else -> error("Unexpected reminder preference type")
            } }
            assertTrue(edit.commit())
            if (!hadChannel) manager.deleteNotificationChannel(CollectionReminderEngine.CHANNEL)
        }
    }
    @Test fun deniedPermissionLeavesNoRegisteredReminder() = withReminder { engine ->
        assertNotEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        engine.initialize(SharedActivity::class.java.name, 21, 0)
        assertThrows(IllegalStateException::class.java) { engine.setEnabled(true) }
        val value = JSONObject(engine.snapshot())
        assertFalse(value.getBoolean("enabled")); assertEquals("", value.getString("registeredStamp"))
        assertEquals(0, value.getJSONArray("reminderIds").length()); assertTrue(value.getString("errorText").isNotBlank())
        evidence("reminderDenied", "Actual denied Android notification permission leaves disabled state and no registration")
    }
    @Test fun changedTimeRecoveryAndDisableOperateOnTheRealSystemAlarm() = withReminder(allowPermission = true) { engine ->
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        engine.initialize(SharedActivity::class.java.name, 21, 0)
        val first = JSONObject(engine.setEnabled(true))
        assertTrue(first.getString("registeredStamp").isNotEmpty())
        val second = JSONObject(engine.setTime(22, 15))
        assertEquals(22, second.getInt("hour")); assertNotEquals(first.getString("planToken"), second.getString("planToken"))
        val version = engine.version()
        engine.onAlarm(first.getString("planToken"), first.getLong("scheduledDue"))
        assertEquals(version, engine.version())
        CollectionReminderReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        val recovered = JSONObject(engine.snapshot())
        assertTrue(recovered.getString("registeredStamp").isNotEmpty())
        assertNotEquals(second.getString("planToken"), recovered.getString("planToken"))
        assertTrue(recovered.getLong("scheduledDue") > System.currentTimeMillis())
        val disabled = JSONObject(engine.setEnabled(false))
        assertFalse(disabled.getBoolean("enabled")); assertEquals("", disabled.getString("registeredStamp"))
        assertEquals(0, disabled.getJSONArray("reminderIds").length())
        evidence("reminderAlarmLifecycle", "AlarmManager registration, time edit, stale-plan rejection, timezone receiver recovery and disable passed")
    }
    @Test fun deliveredAlarmNotificationOpensConfirmationAndDurableClaimRejectsReplay() = withReminder(allowPermission = true) { engine ->
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertFalse(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val now = System.currentTimeMillis()
        val local = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        // Register the real system alarm, then deliver its authenticated request
        // through the real receiver with a controlled trigger. Inexact OS timing
        // is deliberately not asserted by this notification/UI contract test.
        val planner = CollectionReminderEngine(context) { now - 120000 }
        planner.initialize(SharedActivity::class.java.name, local.hour, local.minute)
        planner.setTime(local.hour, local.minute)
        val plan = JSONObject(planner.setEnabled(true))
        assertTrue(plan.getLong("scheduledDue") <= now)
        CollectionReminderReceiver().onReceive(context, Intent(context, CollectionReminderReceiver::class.java)
            .setAction(CollectionReminderEngine.ACTION).putExtra(CollectionReminderEngine.TOKEN, plan.getString("planToken"))
            .putExtra(CollectionReminderEngine.DUE, plan.getLong("scheduledDue")))
        val manager = context.getSystemService(NotificationManager::class.java)
        var posted = false
        repeat(100) {
            if (!posted) { posted = manager.activeNotifications.any { it.id == CollectionReminderEngine.NOTIFICATION_ID }; if (!posted) Thread.sleep(100) }
        }
        assertTrue("The real receiver must post an actual Android notification", posted)
        val request = engine.pending(plan.getString("planToken"), plan.getLong("scheduledDue"))
        assertTrue(request.isNotEmpty())
        assertTrue(JSONObject(engine.snapshot()).getLong("scheduledDue") > System.currentTimeMillis())
        val runs = File(context.filesDir, "files/workflows/runs")
        val beforeRuns = runs.list()?.toSet() ?: emptySet()
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
        waitText("该采集今天", click = true)
        waitText("执行任务")
        assertEquals(beforeRuns, runs.list()?.toSet() ?: emptySet<String>())
        waitText("取消", click = true)
        Thread.sleep(600)
        assertEquals(beforeRuns, runs.list()?.toSet() ?: emptySet<String>())
        assertTrue(engine.claim(request))
        assertFalse(CollectionReminderEngine(context).claim(request))
        assertEquals("", CollectionReminderEngine(context).pending(plan.getString("planToken"), plan.getLong("scheduledDue")))
        evidence("reminderReceiverDelivery", "Controlled trigger through real receiver -> real notification shade tap -> ArkTS confirmation; cancel starts no workflow; durable claim rejects replay")
    }
}
