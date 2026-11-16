package com.clawmate.platform.gui

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.run.InputReceipt
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 插话入口只在任务运行期间收话；结束后不能再往队列里塞。 */
class MikaInboxTest {
    @Test fun steerIsRejectedAfterTheRunEnds() = runTest {
        val run = MikaPhoneRun("Read the fixture title", TextModel())
        run.execute(IdleScreen()) { }
        val receipt = run.inbox.steer("steer-1", "补充一句")
        assertNotEquals(InputReceipt.Accepted, receipt)
        assertTrue("run stayed completed", run.result.isCompleted)
    }

    private class TextModel : ChatModel {
        override val capabilities = Capabilities(true, false, 16_000, false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
            ModelReply(listOf(Part.Text("Fixture title")), StopReason.EndTurn, null)
    }

    private class IdleScreen : ScreenPort {
        override suspend fun snapshot() = ScreenSnapshot("p1", "example.fixture",
            listOf(UiElement("@e1", "TextView", "Fixture title", 5, 5, listOf(0, 0, 10, 10), null, false, false, false)), 10, 10)
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
