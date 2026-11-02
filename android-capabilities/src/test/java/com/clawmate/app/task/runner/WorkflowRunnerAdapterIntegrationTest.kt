package com.clawmate.app.task.runner

import com.clawmate.app.task.hdc.BridgeBackedWorkflowDeviceAdapter
import com.clawmate.app.task.hdc.DisabledWorkflowDeviceAdapter
import com.clawmate.app.task.hdc.HdcActionResult
import com.clawmate.app.task.hdc.HdcBridgeClient
import com.clawmate.app.task.hdc.HdcBridgeResponse
import com.clawmate.app.task.hdc.HdcBridgeResult
import com.clawmate.app.task.hdc.HdcBridgeHealth
import com.clawmate.app.task.hdc.WorkflowDeviceAdapter
import com.clawmate.app.task.json.WorkflowJsonParser
import com.clawmate.app.task.model.WorkflowDefinition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runner integration with the Phase 8 device adapter:
 *  - default (disabled adapter) keeps GUI/HDC steps BLOCKED (never fabricated);
 *  - a caller-supplied configured bridge-backed adapter lets gui_action steps
 *    return honest typed bridge results/errors.
 * Deterministic; no live endpoints.
 */
class WorkflowRunnerAdapterIntegrationTest {

    private val fastClock = WorkflowRunnerImpl.StepClock { _ -> }

    private fun parse(raw: String): WorkflowDefinition {
        val r = WorkflowJsonParser.parse(raw)
        assertTrue("expected parse Ok, got $r", r is WorkflowJsonParser.Result.Ok)
        return (r as WorkflowJsonParser.Result.Ok).definition
    }

    private fun runner(adapter: WorkflowDeviceAdapter? = null): WorkflowRunnerImpl =
        WorkflowRunnerImpl(clock = fastClock, deviceAdapter = adapter ?: DisabledWorkflowDeviceAdapter)

    // ---------------------------------------------------------------- default

    @Test
    fun `default disabled adapter keeps gui actions and tasks BLOCKED`() = runBlocking {
        val def = parse(
            """
            { "steps": [
                { "id": 1, "type": "gui_action", "action": "screenshot" },
                { "id": 2, "type": "gui_task", "task_description": "app task" }
            ] }
            """.trimIndent()
        )
        val result = runner().run(def)
        assertEquals(RunStatus.BLOCKED, result.status)
        assertTrue(result.steps.all { it.status == RunStatus.BLOCKED })
    }

    @Test
    fun `default adapter treats gui_task as BLOCKED even when runner otherwise succeeds`() = runBlocking {
        val def = parse("""{ "steps": [ { "id": 1, "type": "gui_task", "task_description": "x" } ] }""")
        assertEquals(RunStatus.BLOCKED, runner().run(def).status)
    }

    // ----------------------------------------------------------- configured

    @Test
    fun `configured adapter lets app_start gui_action succeed honestly`() = runBlocking {
        val fake = fakeClient(workflow = { _, _ ->
            HdcBridgeResult.Response(HdcBridgeResponse(status = "ok", message = "app_start com.x", packageName = "com.x"))
        })
        val adapter = configuredAdapter(fake)

        val def = parse(
            """
            { "steps": [ { "id": 1, "type": "gui_action", "action": "app_start",
                           "package_name": "com.x", "reset_first": true } ] }
            """.trimIndent()
        )
        val result = runner(adapter).run(def)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(1, result.steps.size)
        assertTrue(result.steps.first().status == RunStatus.SUCCESS)
    }

    @Test
    fun `configured adapter surfaces a bridge error as honest FAILED`() = runBlocking {
        val fake = fakeClient(workflow = { _, _ ->
            HdcBridgeResult.Response(HdcBridgeResponse(status = "error", message = "app_identity_mismatch"))
        })
        val adapter = configuredAdapter(fake)

        val def = parse("""{ "steps": [ { "id": 1, "type": "gui_action", "action": "app_start", "package_name": "com.x" } ] }""")
        val result = runner(adapter).run(def)
        assertEquals(RunStatus.FAILED, result.status)
        assertTrue(result.message.contains("app_identity_mismatch"))
    }

    @Test
    fun `configured adapter surfaces a transport error as honest FAILED`() = runBlocking {
        val fake = fakeClient(health = HdcBridgeResult.Health(okHealth()))
        fake.workflowFn = { _, _ -> HdcBridgeResult.Error(com.clawmate.app.task.hdc.HdcBridgeErrorKind.TIMEOUT, "timed out") }
        val adapter = configuredAdapter(fake)

        val def = parse("""{ "steps": [ { "id": 1, "type": "gui_action", "action": "click" } ] }""")
        val result = runner(adapter).run(def)
        assertEquals(RunStatus.FAILED, result.status)
    }

    @Test
    fun `screenshot gui_action returns success with image output`() = runBlocking {
        val fake = fakeClient(workflow = { _, _ ->
            HdcBridgeResult.Response(
                HdcBridgeResponse(status = "ok", imageB64 = "AWESOME_IMAGE", width = 960, height = 1080)
            )
        })
        val adapter = configuredAdapter(fake)
        val def = parse("""{ "steps": [ { "id": 1, "type": "gui_action", "action": "screenshot" } ] }""")
        val result = runner(adapter).run(def)
        assertEquals(RunStatus.SUCCESS, result.status)
    }

    @Test
    fun `a configured but never-health-verified adapter still blocks gui steps`() = runBlocking {
        val fake = fakeClient() // health not ok
        val adapter = BridgeBackedWorkflowDeviceAdapter(fake) // isConfigured false
        val def = parse("""{ "steps": [ { "id": 1, "type": "gui_action", "action": "click" } ] }""")
        val result = runner(adapter).run(def)
        assertEquals(RunStatus.BLOCKED, result.status)
    }

    // --------------------------------------------------------------- helpers

    private fun okHealth() = HdcBridgeHealth(
        status = "ok", hdcConnected = true, fportReady = true, rportReady = true,
    )

    private fun fakeClient(
        health: HdcBridgeResult = HdcBridgeResult.Health(okHealth()),
        workflow: (String, Map<String, Any?>) -> HdcBridgeResult = { _, _ ->
            HdcBridgeResult.Response(HdcBridgeResponse(status = "ok"))
        },
    ): FakeClient = FakeClient(initialHealth = health, initialWorkflow = workflow)

    private class FakeClient(
        initialHealth: HdcBridgeResult,
        initialWorkflow: (String, Map<String, Any?>) -> HdcBridgeResult,
    ) : HdcBridgeClient {
        var health: HdcBridgeResult = initialHealth
        var workflowFn: (String, Map<String, Any?>) -> HdcBridgeResult = initialWorkflow
        override suspend fun health(): HdcBridgeResult = health
        override suspend fun workflow(action: String, payload: Map<String, Any?>): HdcBridgeResult =
            workflowFn(action, payload)
    }

    /** Returns an adapter already health-verified (isConfigured == true). */
    private fun configuredAdapter(client: HdcBridgeClient): WorkflowDeviceAdapter {
        val adapter = BridgeBackedWorkflowDeviceAdapter(client)
        // For non-suspending setup we call refreshHealth in a runBlocking side effect.
        runBlocking { adapter.refreshHealth() }
        assertEquals(true, adapter.isConfigured)
        return adapter
    }
}
