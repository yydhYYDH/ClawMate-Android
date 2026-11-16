package com.clawmate.platform.gui

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MikaPhoneRunTest {
    @Test fun upstreamAgentReadsTheApprovedPortAndReturnsItsAnswer() = runTest {
        val screen = FixtureScreen()
        var calls = 0
        val model = object : ChatModel {
            override val capabilities = Capabilities(true, false, 16_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                if (++calls == 1) {
                    assertTrue(tools.any { it.name == "phone_observe" })
                    return ModelReply(listOf(Part.ToolCall("observe", "phone_observe", "{}")), StopReason.ToolUse, null)
                }
                assertTrue(messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
                    .any { it.callId == "observe" && "Fixture title" in it.content })
                return ModelReply(listOf(Part.Text("Fixture title")), StopReason.EndTurn, null)
            }
        }
        val run = MikaPhoneRun("Read the title in Fixture", model)
        run.execute(screen) { }
        assertEquals(MikaPhoneResult("success", "Fixture title"), run.result.await())
        assertTrue(screen.reads > 0)
    }

    private class FixtureScreen : ScreenPort {
        var reads = 0
        override suspend fun snapshot(): ScreenSnapshot {
            reads++
            val title = UiElement("@e1", "TextView", "Fixture title", 25, 25,
                listOf(10, 10, 90, 90), null, false, false, false)
            return ScreenSnapshot("p1", "example.fixture", listOf(title), 100, 100)
        }
        override suspend fun screenshotPng(): ByteArray? = null
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Failed("disabled")
        override suspend fun tapXY(x: Int, y: Int) = ActionResult.Failed("disabled")
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Failed("disabled")
        override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Failed("disabled")
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Failed("disabled")
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Failed("disabled")
        override suspend fun back() = ActionResult.Failed("disabled")
        override suspend fun pressKey(keyCode: Int) = ActionResult.Failed("disabled")
        override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Failed("disabled")
        override suspend fun waitStable(timeoutMs: Long) = true
        override suspend fun listApps() = listOf("example.fixture" to "Fixture")
    }
}
