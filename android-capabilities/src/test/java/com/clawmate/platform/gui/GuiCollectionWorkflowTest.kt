package com.clawmate.platform.gui

import com.clawmate.app.task.json.WorkflowJsonParser
import com.clawmate.app.task.runner.RunStatus
import com.clawmate.platform.SharedNativeWork
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GuiCollectionWorkflowTest {
    private val raw = """
        {
          "context": {"contact_name":"测试联系人"},
          "steps": [
            {"id":1,"type":"android_accessibility","action":"collection_open_app","package_name":"com.tencent.mm",
             "task_description":"请打开指定联系人的聊天"},
            {"id":2,"type":"android_accessibility","action":"collection_read_pages","page_limit":3,"direction":"up"},
            {"id":3,"type":"android_accessibility","action":"collection_preview"}
          ]
        }
    """.trimIndent()

    private fun validate(json: String) = GuiCollectionWorkflow.validate(WorkflowJsonParser.parseOrDefault(json))

    @Test fun editedGraphRunsOnlyThroughTheInteractiveEntryAndPassesContext() {
        val batch = SharedNativeWork.runWorkflow(raw)
        assertEquals(RunStatus.BLOCKED, batch.status)
        val result = SharedNativeWork.runWorkflow(raw) { definition ->
            val plan = GuiCollectionWorkflow.validate(definition)
            assertEquals("com.tencent.mm", plan.target)
            assertEquals("请打开指定联系人的聊天\n请核对联系人：测试联系人", plan.instructions)
            assertEquals(3, plan.pages)
            assertEquals(CollectionDirection.UP, plan.direction)
            val run = PageGuiRun(plan)
            run.advance(plan.read, "opened")
            run.advance(plan.preview, "read")
            run.finish(RunStatus.SUCCESS, "saved")
            run.finish(RunStatus.CANCELLED, "late stop")
            run.result.await()
        }
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(listOf("1", "2", "3"), result.steps.map { it.stepId })
        assertEquals("", result.resultSummary)
    }

    @Test fun rejectsUnsafeFieldsAndRequiresASeparatePreviewNode() {
        for (invalid in listOf(
            raw.replace("com.tencent.mm", "https://invalid.example"),
            raw.replace(""""page_limit":3""", """"page_limit":"3""""),
            raw.replace(""""page_limit":3""", """"page_limit":4"""),
            raw.replace(""""direction":"up"""", """"direction":"left""""),
            raw.replace(""""page_limit":3""", """"page_limit":3,"package_name":"com.other.app""""),
            raw.replace("collection_preview", "collection_read_pages"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { validate(invalid) }
        }
    }

    @Test fun cancellationWhileWaitingForThePageDoesNotCompleteTheReadOrPreview() = runBlocking {
        val plan = validate(raw)
        val run = PageGuiRun(plan)
        run.advance(plan.read, "opened")
        run.finish(RunStatus.CANCELLED, "cancelled")
        assertEquals(listOf(RunStatus.SUCCESS, RunStatus.CANCELLED), run.result.await().steps.map { it.status })
    }
}
