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
import com.wangzan101.mika.core.uistate.SystemAction
import com.wangzan101.mika.core.uistate.SystemActionResult
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime

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

    @Test fun upstreamAlarmToolUsesTheSystemPortAndKeepsUiToolsOut() = runTest {
        val actions = mutableListOf<SystemAction>()
        val screen = object : FixtureScreen() {
            override suspend fun systemAction(action: SystemAction): SystemActionResult {
                actions.add(action)
                return SystemActionResult(ActionResult.Ok(), handledBy = "example.clock")
            }
        }
        val model = object : ChatModel {
            override val capabilities = Capabilities(true, false, 16_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                val reply = messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
                if (reply.isEmpty()) {
                    assertTrue(tools.any { it.name == "phone_set_alarm" })
                    assertTrue(tools.none { it.name in setOf("phone_tap", "phone_observe", "phone_system_command") })
                    val call = Part.ToolCall("alarm", "phone_set_alarm", """{"hour":10,"minute":0}""")
                    return ModelReply(listOf(call), StopReason.ToolUse, null)
                }
                assertTrue(reply.any { it.callId == "alarm" && "example.clock" in it.content })
                return ModelReply(listOf(Part.Text("Clock received the request")), StopReason.EndTurn, null)
            }
        }
        val run = MikaPhoneRun("Set an alarm at 10 AM", model, MikaPhoneMode.SYSTEM)
        run.execute(screen) {}
        assertEquals(listOf(SystemAction.SetAlarm(10, 0)), actions)
        assertEquals("success", run.result.await().status)
    }

    @Test fun nextAlarmRespectsPassedTimesAndRepeatDays() {
        val now = ZonedDateTime.parse("2026-11-16T15:00:00+08:00[Asia/Shanghai]")
        val once = MikaSystemScreen.nextOccurrence(SystemAction.SetAlarm(10, 0), now)
        assertEquals(now.plusDays(1).withHour(10).toInstant().toEpochMilli(), once)
        val monday = MikaSystemScreen.nextOccurrence(SystemAction.SetAlarm(10, 0, days = listOf(2)), now)
        assertEquals(now.plusWeeks(1).withHour(10).toInstant().toEpochMilli(), monday)
    }

    @Test fun upstreamCalendarToolValidatesTheBatchBeforeWritingIt() = runTest {
        val batches = mutableListOf<List<SystemAction.AddCalendarEvent>>()
        val screen = object : FixtureScreen() {
            override suspend fun bulkCalendarInsert(events: List<SystemAction.AddCalendarEvent>): SystemActionResult {
                batches.add(events)
                return SystemActionResult(ActionResult.Ok(), eventUris = listOf("content://com.android.calendar/events/20"))
            }
        }
        var turn = 0
        val model = object : ChatModel {
            override val capabilities = Capabilities(true, false, 16_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                val tool = "phone_calendar_bulk_insert"
                assertTrue(tools.any { it.name == tool })
                assertTrue(tools.none { it.name in setOf("phone_add_calendar_event", "phone_set_alarm", "phone_observe") })
                return when (++turn) {
                    1 -> ModelReply(listOf(Part.ToolCall("invalid", tool,
                        """{"events":[{"title":"First","start":"2026-11-18","allDay":true},{"title":"Broken","start":"invalid"}]}""")),
                        StopReason.ToolUse, null)
                    2 -> {
                        assertTrue(batches.isEmpty())
                        ModelReply(listOf(Part.ToolCall("valid", tool,
                            """{"events":[{"title":"First","start":"2026-11-18","allDay":true}]}""")), StopReason.ToolUse, null)
                    }
                    else -> {
                        val receipt = messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
                        assertTrue(receipt.any { it.callId == "valid" && "eventIds" in it.content && "20" in it.content })
                        ModelReply(listOf(Part.Text("Calendar accepted the batch")), StopReason.EndTurn, null)
                    }
                }
            }
        }
        val run = MikaPhoneRun("Write the calendar events", model, MikaPhoneMode.CALENDAR_BATCH)
        run.execute(screen) {}
        val event = batches.single().single()
        assertEquals(Instant.parse("2026-11-18T00:00:00Z").toEpochMilli(), event.beginMs)
        assertEquals(Instant.parse("2026-11-19T00:00:00Z").toEpochMilli(), event.endMs)
        assertEquals("success", run.result.await().status)
    }

    private open class FixtureScreen : ScreenPort {
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
