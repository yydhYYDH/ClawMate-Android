package com.clawmate.shared

import android.os.Bundle
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import local.meetingnotes.sdk.MeetingClient
import local.meetingnotes.sdk.MeetingOperation
import local.meetingnotes.sdk.MeetingSdk
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.io.IOException
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** Real ArkUI-X + Kotlin + embedded Python, using only the public model test WAV. */
@RunWith(AndroidJUnit4::class)
class MeetingIntegrationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    // Full-system ARM64 TCG is much slower than a phone. Keep the default
    // acceptance budget and all assertions; allow an explicit, bounded test-only budget.
    private val timeoutMultiplier = DeviceTestTimeouts.multiplier
    private fun readMeeting(client: MeetingClient, id: String, deadline: Long): JSONObject {
        repeat(timeoutMultiplier) { attempt ->
            try {
                client.request(MeetingOperation.MEETING, JSONObject().put("id", id)).use { request ->
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw TimeoutException("Meeting transcription deadline exceeded")
                    val response = request.result.get(minOf(remaining, TimeUnit.SECONDS.toNanos(15L * timeoutMultiplier)), TimeUnit.NANOSECONDS)
                    assertEquals(200, response.status)
                    return JSONObject(response.payload)
                }
            } catch (error: Exception) {
                val cause = if (error is ExecutionException) error.cause else error
                // A slow emulator can lose an idle connection while native models load.
                // Only repeat this read, never upload/create/update/import operations.
                if (attempt + 1 == timeoutMultiplier || (cause !is IOException && cause !is TimeoutException)) throw error
                evidence("meetingReadRetry", "${attempt + 1}: ${cause.javaClass.simpleName}")
                Thread.sleep(500)
            }
        }
        error("Unreachable meeting polling state")
    }
    private fun awaitJob(bridge: PlatformBridge, id: String): String {
        repeat(600) {
            val result = JSONObject(bridge.meetingJob(id))
            if (result.optString("status") == "done") return result.getString("payload")
            check(result.optString("status") == "running") { result.toString() }
            Thread.sleep(100)
        }
        error("Meeting bridge timeout")
    }
    private fun tap(bounds: Rect) {
        val command = "input tap ${bounds.centerX()} ${bounds.centerY()}"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        Thread.sleep(400)
    }
    private fun clickRenderedText(text: String): Boolean {
        // Conditional ArkUI-X children may be absent from the virtual accessibility cache.
        // Locate the actual rendered label with the app's existing offline Chinese OCR model.
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return false
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), DeviceTestTimeouts.seconds(15), TimeUnit.SECONDS)
            fun normalized(value: String) = value.replace(Regex("\\s+"), "").lowercase()
            val line = result.textBlocks.flatMap { it.lines }.firstOrNull { normalized(it.text) == normalized(text) }
            val bounds = line?.boundingBox ?: return false
            if (!DeviceTestTimeouts.targetStillCurrent(bitmap, bounds)) return false
            evidence("meetingRenderedTouch", "$text at $bounds")
            tap(bounds)
            return true
        } finally { recognizer.close(); bitmap.recycle() }
    }
    private fun clickText(text: String): Boolean {
        // ArkUI-X does not invalidate every virtual child when a conditional subtree changes.
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        root.refresh()
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) {
                val textBounds = Rect(); node.getBoundsInScreen(textBounds)
                var target: AccessibilityNodeInfo? = node
                repeat(4) {
                    val item = target
                    if (item != null && item.isEnabled && item.isClickable && item.isVisibleToUser) {
                        if (text == "汇总" || text == "本机会议 · 录音与纪要") {
                            if (item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { Thread.sleep(500); return true }
                        }
                        val bounds = Rect(textBounds)
                        val screen = Rect(); root.getBoundsInScreen(screen)
                        if (bounds.intersect(screen) && !bounds.isEmpty && bounds.height() > 12) {
                            evidence("meetingUiTouch", "$text at $bounds")
                            // ArkUI-X virtual ACTION_CLICK may acknowledge without dispatching onClick.
                            // Exercise the real page touch path at the observed visible node bounds.
                            tap(bounds)
                            Thread.sleep(300)
                            return true
                        }
                    }
                    target = item?.parent
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { queue.add(it) }
        }
        return if (text == "保存到本机记录" || text == "刷新") clickRenderedText(text) else false
    }
    private fun waitClick(text: String) {
        repeat(60) {
            if (clickText(text)) return
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val queue = ArrayDeque<AccessibilityNodeInfo>(); if (root != null) queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
                for (index in 0 until node.childCount) node.getChild(index)?.let { queue.add(it) }
            }
            Thread.sleep(500)
        }
        val visible = ArrayList<String>()
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let { nodes.add(it) }
        while (nodes.isNotEmpty() && visible.size < 160) {
            val node = nodes.removeFirst()
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { visible.add(it.take(250)) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { visible.add(it.take(250)) }
            for (index in 0 until node.childCount) node.getChild(index)?.let { nodes.add(it) }
        }
        evidence("meetingUiFailure", visible.joinToString(" | "))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.cacheDir, "meeting-ui-failure.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        error("Visible shared UI action not found: $text")
    }
    private fun evidence(name: String, value: String) = instrumentation.sendStatus(2, Bundle().apply { putString(name, value) })

    @Test fun sharedPageUsesSdkAndImportsOneImmutablePublicRecord() {
        val context = instrumentation.targetContext
        val fixture = File(context.filesDir, "meeting/models/sensevoice/test_wavs/zh.wav")
        assertTrue("Prepare public model fixture before running device check", fixture.isFile)
        var mid = ""
        ActivityScenario.launch(SharedActivity::class.java).use { scenario ->
            lateinit var bridge: PlatformBridge
            scenario.onActivity { activity ->
                val field = SharedActivity::class.java.getDeclaredField("platform").apply { isAccessible = true }
                bridge = field.get(activity) as PlatformBridge
            }
            waitClick("汇总"); waitClick("本机会议 · 录音与纪要")
            evidence("meetingTimeoutMultiplier", timeoutMultiplier.toString())
            val engineDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90L * timeoutMultiplier)
            do {
                if (JSONObject(bridge.meetingPoll("0")).getJSONObject("engine").optString("phase") == "ready") break
                Thread.sleep(500)
            } while (System.nanoTime() < engineDeadline)
            assertEquals("ready", JSONObject(bridge.meetingPoll("0")).getJSONObject("engine").getString("phase"))
            assertEquals(18090, MeetingSdk.getConfiguration().apiPort)
            assertFalse(JSONObject(bridge.meetingPoll("0")).getJSONObject("capture").getBoolean("streaming"))
            evidence("meetingEngine", "ArkUI-X and embedded Python running in com.clawmate.shared on configured port")
            mid = JSONObject(awaitJob(bridge, bridge.meetingRequest("create_draft", "{\"body\":{\"source\":\"upload\"}}"))).getString("id")
            val title = "SDK 集成公开样例 " + mid.take(8)
            MeetingClient().use { client ->
                client.uploadAudio(mid, "public-zh.wav", { fixture.inputStream() }).result.get(60, TimeUnit.SECONDS)
                var meeting = JSONObject()
                val transcriptionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90L * timeoutMultiplier)
                do {
                    meeting = readMeeting(client, mid, transcriptionDeadline)
                    if (meeting.optString("status") !in listOf("processing", "summarizing")) break
                    Thread.sleep(500)
                } while (System.nanoTime() < transcriptionDeadline)
                val doc = meeting.getJSONObject("document")
                assertTrue(meeting.toString(), doc.getString("transcript").isNotBlank())
                assertTrue(doc.getString("audio_id").isNotBlank())
                client.request(MeetingOperation.UPDATE_MEETING, JSONObject().put("id", mid)
                    .put("body", JSONObject().put("title", title))).result.get(15, TimeUnit.SECONDS)
                val listed = JSONObject(awaitJob(bridge, bridge.meetingRequest("history", "{\"query\":{\"view\":\"inbox\"}}")))
                assertTrue(listed.toString(), listed.getJSONArray("items").toString().contains(title))
                evidence("meetingHistoryCount", listed.getInt("total").toString())
                val handoff = JSONObject(awaitJob(bridge, bridge.meetingRequest("handoff", JSONObject().put("id", mid)
                    .put("body", JSONObject().put("base_revision", doc.getInt("revision")).put("source_hash", doc.getString("source_hash"))).toString())))
                val exportId = handoff.getString("export_id")
                evidence("meetingRealTranscript", doc.getString("transcript"))
                waitClick("刷新"); waitClick(title)
                waitClick("保存到本机记录")
                val receipt = File(context.filesDir, "files/workflows/meeting_imports/$exportId.json")
                val receiptDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                while (!receipt.isFile && System.nanoTime() < receiptDeadline) {
                    Thread.sleep(500)
                }
                assertTrue("ArkTS must persist the verified import receipt", receipt.isFile)
                val first = receipt.readText()
                assertEquals(exportId, JSONObject(first).getString("export_id"))
                Thread.sleep(1000); waitClick("保存到本机记录"); Thread.sleep(2000)
                assertEquals(first, receipt.readText())
                assertEquals(1, File(context.filesDir, "files/workflows").walkTopDown().count { it.isFile && it.name == "meeting-$exportId.md.state.json" })
                evidence("meetingImport", "real shared-page save, immutable provenance and duplicate suppression passed")
                // Preserve the imported public sample for inspection; source recording goes to reversible trash.
                client.request(MeetingOperation.HISTORY_BATCH, JSONObject().put("body", JSONObject()
                    .put("ids", org.json.JSONArray(listOf(mid))).put("action", "trash"))).result.get(15, TimeUnit.SECONDS)
            }
        }
        assertEquals(local.meetingnotes.sdk.MeetingEnginePhase.READY, MeetingSdk.getEngineState().phase)
        MeetingClient().use { client ->
            val device = JSONObject(client.request(MeetingOperation.RUNTIME_STATUS).result.get(15, TimeUnit.SECONDS).payload)
            assertFalse(device.getJSONObject("summary").getBoolean("loaded"))
        }
        evidence("meetingLifecycle", "Activity destroyed while SDK API remains available; microphone never started")
    }
}
