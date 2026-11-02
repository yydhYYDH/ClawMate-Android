package com.clawmate.app.task.json

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorkflowEditValidatorTest {
    @Test fun `strict saves reject repaired JSON and malformed structures`() {
        listOf("", "[]", "{steps:[]}", "{\"steps\":[],}", "{}", "{\"steps\":{}}",
            """{"steps":[null]}""", """{"steps":[{"type":7}]}""",
            """{"steps":[{"type":""}]}""", """{"steps":[],"context":[]}""",
            """{"steps":[{"type":"if","then_steps":[{}]}]}""",
            """{"steps":[{"type":"loop","steps":{}}]}""",
            """{"steps":[{"id":1,"type":"loop","steps":{}}]}""",
            """{"steps":[{"id":1,"type":"wait"},{"id":"1","type":"wait"}]}""",
            """{"steps":[{"id":false,"type":"wait"}]}""",
            """{"steps":[{"id":1,"type":"if","then_steps":[{}]}]}""",
        ).forEach { assertNotNull(it, WorkflowEditValidator.error(it)) }
    }

    @Test fun `unknown fields and future step types remain valid`() {
        assertNull(WorkflowEditValidator.error("""{
            "metadata":{"description":"custom"}, "context":{}, "futureRoot":true,
            "steps":[{"id":1,"type":"future-tool","payload":{"unknown":[1,2]}},
                {"id":2,"type":"if","then_steps":[],"else_steps":[{"id":3,"type":"loop","steps":[]}]}]
        }"""))
    }

    @Test fun `oversized and deeply nested workflow edits are bounded`() {
        assertNotNull(WorkflowEditValidator.error(" ".repeat(WorkflowEditValidator.MAX_CHARS + 1)))
        var step = """{"id":1,"type":"wait"}"""
        repeat(34) { step = """{"id":1,"type":"loop","steps":[$step]}""" }
        assertNotNull(WorkflowEditValidator.error("""{"steps":[$step]}"""))
    }
}
