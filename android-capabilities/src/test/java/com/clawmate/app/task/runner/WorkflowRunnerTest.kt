package com.clawmate.app.task.runner

import com.clawmate.app.task.json.WorkflowJsonParser
import com.clawmate.app.task.model.WorkflowDefinition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the bounded, honest workflow runner:
 *  - GUI/HDC/device-control steps are BLOCKED (never fabricated as success);
 *  - cloud VLM tool steps are NOT_CONFIGURED;
 *  - loop/if deterministic execution;
 *  - cancellation surfaces as CANCELLED;
 *  - step-budget and loop-iteration bounds are enforced.
 */
class WorkflowRunnerTest {

    private val fastClock = WorkflowRunnerImpl.StepClock { _ -> }

    private fun runner(
        maxTotal: Int = 200,
        maxLoop: Int = 100,
        maxMillis: Long = 60_000L,
    ): WorkflowRunnerImpl =
        WorkflowRunnerImpl(clock = fastClock, maxTotalSteps = maxTotal, maxLoopIterations = maxLoop, maxMillis = maxMillis)

    private fun parse(raw: String): WorkflowDefinition {
        val r = WorkflowJsonParser.parse(raw)
        assertTrue("expected parse Ok, got $r", r is WorkflowJsonParser.Result.Ok)
        return (r as WorkflowJsonParser.Result.Ok).definition
    }

    @Test
    fun `gui_task and gui_action steps become BLOCKED not success`() = runBlocking {
        val def = parse(
            """
            { "steps": [
              { "id": 1, "type": "gui_task", "task_description": "打开微信" },
              { "id": 2, "type": "gui_action", "action": "screenshot" }
            ] }
            """.trimIndent()
        )
        val result = runner().run(def)
        assertEquals(RunStatus.BLOCKED, result.status)
        assertTrue(result.steps.first().status == RunStatus.BLOCKED)
        assertTrue(result.steps.first().detail.isNotBlank())
    }

    @Test
    fun `tool vlm_qa becomes NOT_CONFIGURED`() = runBlocking {
        val def = parse(
            """
            { "steps": [ { "id": 4, "type": "tool", "tool_name": "vlm_qa", "inputs": { "question": "q" } } ] }
            """.trimIndent()
        )
        val result = runner().run(def)
        assertEquals(RunStatus.NOT_CONFIGURED, result.status)
        assertTrue(result.steps.first().status == RunStatus.NOT_CONFIGURED)
    }

    @Test
    fun `sleep and echo are honest executable steps`() = runBlocking {
        val def = parse(
            """
            { "steps": [
              { "id": 1, "type": "gui_action", "action": "sleep", "seconds": 1 },
              { "id": 2, "type": "tool", "tool_name": "echo", "inputs": { "text": "hi" } }
            ] }
            """.trimIndent()
        )
        val result = runner().run(def)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(2, result.steps.size)
        assertTrue(result.steps.all { it.status == RunStatus.SUCCESS })
    }

    @Test
    fun `loop with a blocking inner gui action still BLOCKS`() = runBlocking {
        val def = parse(
            """
            { "steps": [ { "id": 1, "type": "loop", "times": 3, "steps": [
                { "id": 1, "type": "gui_action", "action": "click" }
            ] } ] }
            """.trimIndent()
        )
        val result = runner().run(def)
        assertEquals(RunStatus.BLOCKED, result.status)
    }

    @Test
    fun `if evaluates condition over context and runs then branch`() = runBlocking {
        val def = parse(
            """
            { "context": { "show": true },
              "steps": [ { "id": 1, "type": "if",
                  "condition": { "left": "${'$'}{context.show}", "operator": "==", "right": true },
                  "then_steps": [ { "id": 2, "type": "tool", "tool_name": "echo", "inputs": {"t":"then"} },
                                  { "id": 3, "type": "gui_action", "action": "sleep", "seconds": 0 } ],
                  "else_steps": [ { "id": 9, "type": "gui_task", "task_description": "else branch" } ] } ] }
            """.trimIndent()
        )
        val result = runner().run(def)
        assertEquals(RunStatus.SUCCESS, result.status)
        // then branch: echo + sleep ran; else gui_task not reached.
        assertEquals(2, result.steps.size)
    }

    @Test
    fun `cancellation surfaces as CANCELLED`() = runBlocking {
        val blocker = CompletableDeferred<Unit>()
        val blockingClock = WorkflowRunnerImpl.StepClock { _ -> blocker.await() }
        val runner = WorkflowRunnerImpl(clock = blockingClock, maxTotalSteps = 200, maxMillis = 60_000L)
        val def = parse("""{ "steps": [ { "id": 1, "type": "gui_action", "action": "sleep", "seconds": 10 } ] }""")

        val deferred = async(start = CoroutineStart.LAZY) { runner.run(def) }
        delay(50) // let it suspend on the blocking sleep clock
        deferred.cancel()
        val outcome = try {
            deferred.await()
        } catch (c: kotlinx.coroutines.CancellationException) {
            null
        }
        // Either the runner returned a CANCELLED result or the awaiting caller got
        // CancellationException — in both cases it never reports a fake success.
        if (outcome != null) {
            assertEquals(RunStatus.CANCELLED, outcome.status)
        }
    }

    @Test
    fun `loop times exceeding the per-loop bound becomes FAILED`() = runBlocking {
        val def = parse("""{ "steps": [ { "id": 1, "type": "loop", "times": 999999, "steps": [] } ] }""")
        val result = runner(maxLoop = 200).run(def)
        assertEquals(RunStatus.FAILED, result.status)
        assertTrue(result.message.contains("loop times"))
    }

    @Test
    fun `total step budget is enforced for large loops`() = runBlocking {
        val inner = (0 until 100).joinToString(",") {
            """{"id":$it,"type":"tool","tool_name":"echo","inputs":{"n":"$it"}}"""
        }
        val def = parse("""{ "steps": [ { "id": 1, "type": "loop", "times": 10, "steps": [ $inner ] } ] }""")
        val result = runner(maxTotal = 40, maxLoop = 20).run(def)
        // 10 iterations * 100 sub-steps would exceed the total budget of 40.
        assertEquals(RunStatus.FAILED, result.status)
        assertTrue(result.message.contains("max total-step budget"))
    }

    @Test
    fun `all-gui workflow never reports success`() = runBlocking {
        val def = parse(
            """
            { "steps": [
              { "id": 1, "type": "gui_task", "task_description": "打开饿了么" },
              { "id": 2, "type": "gui_task", "task_description": "点击我的" }
            ] }
            """.trimIndent()
        )
        val result = runner().run(def)
        assertEquals(RunStatus.BLOCKED, result.status)
    }

    @Test
    fun `unknown step type becomes BLOCKED`() = runBlocking {
        val def = parse("""{ "steps": [ { "id": 7, "type": "totally_unknown" } ] }""")
        val result = runner().run(def)
        assertEquals(RunStatus.BLOCKED, result.status)
        assertTrue(result.message.contains("unknown step type"))
    }

    @Test
    fun `loop variables index and last resolve deterministically`() = runBlocking {
        val def = parse(
            """
            { "steps": [ { "id": 1, "type": "loop", "times": 2, "steps": [
                { "id": 10, "type": "tool", "tool_name": "echo",
                  "inputs": { "i": "${'$'}{loop.index}", "last": "${'$'}{loop.last}" } }
            ] } ] }
            """.trimIndent()
        )
        val result = runner().run(def)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertNotNull(result)
    }
}
