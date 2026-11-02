package com.clawmate.app.task.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the deterministic text-level JSON repair heuristics. */
class WorkflowJsonRepairTest {

    @Test
    fun `extracts first top-level json object skipping trailing text`() {
        val input = """NOTE: { "a": 1, "b": { "c": 2 } } and this trailing text"""
        val extracted = WorkflowJsonRepair.extractFirstJsonObjectText(input)
        assertEquals("""{ "a": 1, "b": { "c": 2 } }""", extracted)
    }

    @Test
    fun `strips code fence`() {
        val input = "```json\n{\"x\":1}\n```"
        assertEquals("{\"x\":1}", WorkflowJsonRepair.stripFencing(input))
    }

    @Test
    fun `closes truncated json object`() {
        val input = """{"steps":[{"id":1,"type":"gui_task"}"""
        val closed = WorkflowJsonRepair.closeTruncatedJson(input)
        assertTrue("expected to end with ]}", closed.endsWith("]}"))
    }

    @Test
    fun `normalizes cjk punctuation`() {
        assertEquals("{\"a\":1,\"b\":2}", WorkflowJsonRepair.normalizeCjkJsonPunctuation("{\"a\"：1，“b”：2}"))
    }

    @Test
    fun `repair preserves punctuation in ASCII strings and quoted literal text`() {
        val raw = """{"message":"点击“我的”，输入：TRUE，路径\\\"a\""} """.trim()
        assertEquals(raw, WorkflowJsonRepair.normalizeCjkJsonPunctuation(raw))
        assertEquals("""{"message":"TRUE，NONE"}""", WorkflowJsonRepair.normalizeJsonText("{“message”：“TRUE，NONE”}"))
    }

    @Test
    fun `repairs literal casing`() {
        assertEquals("true", WorkflowJsonRepair.repairJsonLiteralCasing("TRUE"))
        assertEquals("null", WorkflowJsonRepair.repairJsonLiteralCasing("None"))
        // inside a string it must not be touched
        assertEquals("\"TRUE\"", WorkflowJsonRepair.repairJsonLiteralCasing("\"TRUE\""))
    }

    @Test
    fun `normalize combines repairs`() {
        val raw = "```json\n{\"steps\":[{\"id\":1,\"type\":\"gui_task\",\"done\":TRUE}]\n```"
        val normalized = WorkflowJsonRepair.normalizeJsonText(raw)
        assertTrue(normalized.contains("\"done\":true") || normalized.contains("\"done\":true"))
        // Should be parseable
        val ok = com.clawmate.app.task.json.WorkflowJsonParser.parse(normalized)
        assertTrue(ok is com.clawmate.app.task.json.WorkflowJsonParser.Result.Ok)
    }
}
