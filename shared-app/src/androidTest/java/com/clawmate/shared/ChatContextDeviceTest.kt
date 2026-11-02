package com.clawmate.shared

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Real ArkTS chat page, synthetic history and an intentionally unavailable model.
 * No provider inference claim; never exports or prints the original preferences. */
@RunWith(AndroidJUnit4::class)
class ChatContextDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun evidence(name: String, value: String) {
        instrumentation.sendStatus(2, Bundle().apply { putString(name, value) })
    }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun find(node: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (test(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), test)?.let { return it }
        return null
    }
    private fun tap(bounds: Rect) { shell("input tap ${bounds.centerX()} ${bounds.centerY()}"); Thread.sleep(250) }
    private fun click(text: String): Boolean {
        if (DeviceTestTimeouts.multiplier > 1 && text == "聊天") return DeviceTestTimeouts.clickNavigation(text)
        // ArkUI-X may retain stale virtual children after conditional updates.
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return false
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), DeviceTestTimeouts.seconds(15), TimeUnit.SECONDS)
            fun normalized(value: String) = value.replace(Regex("\\s+"), "")
            val line = result.textBlocks.flatMap { it.lines }.firstOrNull {
                normalized(it.text) == normalized(text) || (text == "发送" && normalized(it.text).endsWith(text)) ||
                    (text == "发消息" && normalized(it.text).contains(text))
            }
            line?.boundingBox?.let { tap(it); return true }
            return false
        } catch (failure: Exception) {
            DeviceTestDiagnostics.capture("chat-ocr", failure)
            throw failure
        } finally { recognizer.close(); bitmap.recycle() }
    }
    private fun waitClick(text: String) {
        if (DeviceTestTimeouts.multiplier == 1) {
            repeat(30) { if (click(text)) return; Thread.sleep(300) }
        } else {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DeviceTestTimeouts.seconds(30))
            do { if (click(text)) return; Thread.sleep(300) } while (System.nanoTime() < deadline)
        }
        error("Rendered control not found: $text")
    }
    private fun send(text: String) {
        var entered = false
        repeat(30) {
            if (!entered) {
                val editable = find(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable && it.isVisibleToUser }
                if (editable != null) {
                    val bounds = Rect().also { editable.getBoundsInScreen(it) }
                    tap(bounds)
                    shell("input text $text") // Fixed ASCII fixture, through actual editor key events.
                    entered = true
                } else if (click("发消息")) {
                    shell("input text $text")
                    entered = true
                }
                if (!entered) Thread.sleep(300)
            }
        }
        Thread.sleep(800)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.cacheDir, "migration-context-input.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        check(entered) { "Chat TextArea did not accept the synthetic input" }
        waitClick("发送")
    }
    private fun awaitRecord(file: File, current: String): JSONObject {
        repeat(600) {
            val value = runCatching { JSONObject(file.readText()) }.getOrNull()
            val messages = value?.optJSONArray("messages")
            if (value != null && messages != null && value.optString("contextSummary").isNotBlank()) {
                val last = messages.getJSONObject(messages.length() - 1)
                if (messages.toString().contains(current) && !last.optBoolean("streamingActive")) return value
            }
            Thread.sleep(100)
        }
        error("Shared page did not persist the summary and completed error state")
    }
    private fun message(id: String, role: String, text: String) = JSONObject().put("id", id).put("role", role)
        .put("kind", "chat").put("content", text).put("createdAt", 1).put("thinkingContent", "")
        .put("thinkingCollapsed", true).put("trace", JSONArray()).put("collapsed", false)

    @Test fun actualPageCompressesLocallyPreservesHistoryAndReusesSummaryAfterRecreate() {
        val context = instrumentation.targetContext
        val root = File(context.filesDir, "files/chat-sessions").also { File(it, "sessions").mkdirs() }
        val sessionId = "migration-context-${android.os.SystemClock.elapsedRealtime()}"
        val session = File(root, "sessions/$sessionId.json")
        val settings = File(context.filesDir, "preference/shared_frontend_settings")
        val privacy = File(context.filesDir, "preference/privacy_consent")
        val index = File(root, "session-index.json")
        val originals = listOf(settings, privacy, index).associateWith { if (it.exists()) it.readBytes() else null }
        // Recoverable local backups protect the user's configuration even if a
        // device disconnects. They stay inside this app's private cache.
        val backup = File(context.cacheDir, "migration-context-backup")
        check(!backup.exists()) { "Recover the previous context test backup before rerunning" }
        check(backup.mkdirs())
        originals.entries.forEachIndexed { position, entry -> entry.value?.let { File(backup, "$position.bin").writeBytes(it) } }
        val messages = JSONArray()
        repeat(6) { round ->
            messages.put(message("u$round", "user", "合成目标$round。".repeat(1800)))
            messages.put(message("a$round", "assistant", "已确认结论$round。".repeat(1800)))
        }
        val firstOriginal = messages.getJSONObject(0).getString("content")
        val now = System.currentTimeMillis()
        try {
            settings.parentFile!!.mkdirs()
            settings.writeText("""<?xml version="1.0" encoding="utf-8"?><preferences>
                <string key="firstLaunchOnboardingVersion">2026-07-core-preview-usage-guide</string>
                <bool key="androidCloudAccessGranted" value="true"/>
                <string key="cloudPlannerBaseUrl">https://context-summary-fixture.invalid</string>
                <string key="cloudPlannerModel">synthetic-unavailable-model</string>
                <string key="cloudPlannerApiKey"></string><string key="cloudApiKey"></string>
                <string key="chatContextCompressionThresholdPercent">50</string>
                <string key="chatContextRecentRounds">2</string></preferences>""")
            privacy.writeText("""<?xml version="1.0" encoding="utf-8"?><preferences>
                <string key="choice">accepted</string><string key="version">2026-10-26-android-v1</string></preferences>""")
            session.writeText(JSONObject().put("id", sessionId).put("title", "迁移摘要测试").put("mode", "cloud_agent")
                .put("createdAt", now).put("updatedAt", now).put("messages", messages).toString())
            index.writeText(JSONObject().put("sessions", JSONArray().put(JSONObject().put("id", sessionId)
                .put("title", "迁移摘要测试").put("mode", "cloud_agent").put("fileName", "$sessionId.json")
                .put("createdAt", now).put("updatedAt", now).put("messageCount", 12).put("lastMessagePreview", "合成历史"))).toString())
            ActivityScenario.launch(SharedActivity::class.java).use { scenario ->
                waitClick("聊天"); send("summary_fixture_first")
                val first = awaitRecord(session, "summary_fixture_first")
                assertTrue(first.getString("contextSummary").contains("本地抽取"))
                assertTrue(first.getString("contextSummaryUntilMessageId").isNotBlank())
                assertEquals(firstOriginal, first.getJSONArray("messages").getJSONObject(0).getString("content"))
                val summary = first.getString("contextSummary")
                val updated = first.getLong("contextSummaryUpdatedAt")
                evidence("contextLocalFallback", "Real ArkTS send, bounded labelled extraction, saved coverage and complete original history passed")
                shell("input keyevent KEYCODE_BACK")
                scenario.recreate()
                waitClick("聊天"); send("summary_fixture_second")
                val second = awaitRecord(session, "summary_fixture_second")
                assertEquals(summary, second.getString("contextSummary"))
                assertEquals(updated, second.getLong("contextSummaryUpdatedAt"))
                assertEquals(firstOriginal, second.getJSONArray("messages").getJSONObject(0).getString("content"))
                assertTrue(second.getJSONArray("messages").toString().contains("复用本机会话"))
                evidence("contextRecreate", "Saved summary reused after Activity recreation; no fake model reply")
            }
        } catch (failure: Throwable) {
            DeviceTestDiagnostics.capture("chat-lifecycle", failure)
            throw failure
        } finally {
            Thread.sleep(500)
            originals.forEach { (file, bytes) -> if (bytes == null) file.delete() else file.writeBytes(bytes) }
            session.delete()
            check(backup.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            backup.listFiles()?.forEach { it.delete() }; backup.delete()
        }
    }
}
