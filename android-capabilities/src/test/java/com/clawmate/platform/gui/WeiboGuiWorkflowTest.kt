package com.clawmate.platform.gui

import com.clawmate.app.task.json.WorkflowJsonParser
import com.clawmate.app.task.runner.RunStatus
import com.clawmate.app.task.runner.WorkflowRunResult
import com.clawmate.platform.SharedNativeWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WeiboGuiWorkflowTest {
    private val open = action(1, WeiboGuiWorkflow.OPEN)
    private val select = action(2, WeiboGuiWorkflow.SELECT)
    private val read = action(3, WeiboGuiWorkflow.READ, """, "item_limit": 3""")
    private val preview = action(4, WeiboGuiWorkflow.PREVIEW)

    private fun action(id: Int, action: String, extra: String = "") =
        """{"id":$id,"type":"android_accessibility","action":"$action"$extra}"""

    private fun graph(vararg steps: String) = """{"steps":[${steps.joinToString(",")}]}"""

    private fun validate(vararg steps: String) =
        WeiboGuiWorkflow.validate(WorkflowJsonParser.parseOrDefault(graph(*steps)).steps)

    @Test fun respectsEditedCountAndIntermediateWait() {
        val wait = """{"id":5,"type":"gui_action","action":"sleep","seconds":2}"""
        assertEquals(1, validate(open, select, wait, read.replace(": 3", ": 1"), preview))
        assertEquals(5, validate(open, wait, select, read.replace(": 3", ": 5"), preview))
    }

    @Test fun rejectsUnsafeOrInvalidGraphs() {
        assertThrows(IllegalArgumentException::class.java) { validate(open, read, select, preview) }
        assertThrows(IllegalArgumentException::class.java) { validate(open, select, read, read, preview) }
        assertThrows(IllegalArgumentException::class.java) { validate(open, select, read.replace(": 3", ": 6"), preview) }
        val injected = action(1, WeiboGuiWorkflow.OPEN, ", \"package_name\":\"com.example.other\"")
        assertThrows(IllegalArgumentException::class.java) { validate(injected, select, read, preview) }
        val longWait = """{"id":5,"type":"gui_action","action":"sleep","seconds":6}"""
        assertThrows(IllegalArgumentException::class.java) { validate(open, select, read, longWait, preview) }
    }

    @Test fun onlySingleRunPathCanRequestNativeConsent() {
        val raw = graph(open, select, read, preview)
        val batch = SharedNativeWork.runWorkflow(raw)
        assertEquals(RunStatus.BLOCKED, batch.status)
        assertEquals("", batch.resultSummary)
        var received = 0
        val interactive = SharedNativeWork.runWorkflow(raw) { definition ->
            received = WeiboGuiWorkflow.validate(definition.steps)
            WorkflowRunResult(RunStatus.CANCELLED, "测试取消")
        }
        assertEquals(3, received)
        assertEquals(RunStatus.CANCELLED, interactive.status)
        assertEquals("", interactive.resultSummary)
    }
}
