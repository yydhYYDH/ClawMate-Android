package com.clawmate.platform.gui

import com.clawmate.app.task.json.WorkflowJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class XhsGuiWorkflowTest {
    @Test fun restrictsNodeToExplicitPublicSearchParameters() {
        fun validate(extra: String = "") = XhsGuiWorkflow.validate(WorkflowJsonParser.parseOrDefault(
            """{"steps":[{"id":1,"type":"android_accessibility","action":"xhs_public_notes","text":"城市漫步","task_description":"先读取再翻页"$extra}]}""",
        ).steps)
        assertEquals("城市漫步" to "先读取再翻页", validate())
        assertThrows(IllegalArgumentException::class.java) { validate(""","package_name":"com.example.other"""") }
    }

    @Test fun modelOnlyReceivesAvailableActionsAndCannotSupplyActionArguments() {
        val actions = setOf("read_visible", "scroll_forward")
        val request = XhsGuiWorkflow.request("先读当前页", actions, 0, 0)
        assertEquals("", request.image)
        assertFalse(request.question.contains("\"finish\""))
        assertEquals("scroll_forward", XhsGuiWorkflow.choice("""{"action":"scroll_forward"}""", actions))
        assertThrows(IllegalArgumentException::class.java) { XhsGuiWorkflow.choice("""{"action":"finish"}""", actions) }
        assertThrows(IllegalArgumentException::class.java) { XhsGuiWorkflow.choice("""{"action":"read_visible","x":20}""", actions) }
    }
}
