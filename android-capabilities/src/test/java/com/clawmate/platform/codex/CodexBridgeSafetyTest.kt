package com.clawmate.platform.codex

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Safety properties of a real bridge that the client depends on.
 *
 * The client never resends a turn: it relies on the bridge to recognise a repeated request
 * id. These checks confirm that guarantee end to end, so a client change that starts
 * resending would be a functional bug rather than a harmless duplicate.
 *
 * Skipped unless `-Dclawmate.codex.bridgeUrl` / `bridgeToken` are supplied.
 */
class CodexBridgeSafetyTest {

    private val url: String = System.getProperty(BRIDGE_URL) ?: ""
    private val token: String = System.getProperty(BRIDGE_TOKEN) ?: ""
    private val caCertificate: String = System.getProperty(BRIDGE_CA_FILE)
        ?.takeIf { it.isNotEmpty() }
        ?.let { File(it).takeIf(File::isFile)?.readText() }
        ?.replace("\r", "")
        ?: ""

    private fun transport(): CodexTransport {
        assumeTrue("no live bridge configured", url.isNotEmpty() && token.isNotEmpty())
        return CodexTransport(clientFactory = { ca -> CodexTransport.defaultClient(ca) }).apply {
            transportConfigure(this)
        }
    }

    private fun transportConfigure(target: CodexTransport) {
        target.configure(CodexConnection(origin = url, token = token, caCertificate = caCertificate))
    }

    private suspend fun newTask(transport: CodexTransport, model: String, workspace: String): String {
        val created = transport.postTyped(
            "/v1/tasks",
            CodexCreateBody(workspaceId = workspace, model = model, effort = ""),
        )
        return created.task?.id ?: error("bridge created no task")
    }

    @Test
    fun `the same request id is answered without running the turn twice`() = runBlocking {
        val transport = transport()
        val info = transport.get("/v1/info")
        val model = (info.models.firstOrNull { it.isDefault } ?: info.models.first()).model
        val taskId = newTask(transport, model, info.workspaces.first().id)
        val requestId = java.util.UUID.randomUUID().toString()
        val body = CodexTurnBody(requestId = requestId, text = "once", model = model, effort = "")

        val first = transport.postTyped("/v1/tasks/$taskId/turns", body)
        // Repeating the identical body is the exact case a naive retry would produce.
        val second = transport.postTyped("/v1/tasks/$taskId/turns", body)

        assertEquals(requestId, first.task?.lastRequestId)
        assertEquals(requestId, second.task?.lastRequestId)
        // The user record for this request must appear exactly once.
        val userItems = second.task!!.items.filter { it.id == requestId }
        assertEquals("the turn must not be recorded twice", 1, userItems.size)
    }

    @Test
    fun `reusing a request id with different input is refused`() = runBlocking {
        val transport = transport()
        val info = transport.get("/v1/info")
        val model = (info.models.firstOrNull { it.isDefault } ?: info.models.first()).model
        val taskId = newTask(transport, model, info.workspaces.first().id)
        val requestId = java.util.UUID.randomUUID().toString()

        transport.postTyped(
            "/v1/tasks/$taskId/turns",
            CodexTurnBody(requestId = requestId, text = "original", model = model, effort = ""),
        )
        try {
            transport.postTyped(
                "/v1/tasks/$taskId/turns",
                CodexTurnBody(requestId = requestId, text = "different", model = model, effort = ""),
            )
            fail("a request id must not be reusable with different content")
        } catch (e: CodexHttpException) {
            assertEquals(409, e.status)
            assertNotNull(e.message)
        }
    }

    @Test
    fun `an interrupt is acknowledged with a task snapshot`() = runBlocking {
        val transport = transport()
        val info = transport.get("/v1/info")
        val model = (info.models.firstOrNull { it.isDefault } ?: info.models.first()).model
        val taskId = newTask(transport, model, info.workspaces.first().id)

        val envelope = transport.post(
            "/v1/tasks/$taskId/interrupt",
            buildJsonObject {
                put("id", "")
                put("decision", "")
                put("answers", kotlinx.serialization.json.JsonArray(emptyList()))
            },
        )

        // The bridge answers with the task, which is what lets the UI show a confirmed stop.
        assertNotNull("an interrupt must return task state", envelope.task)
        assertEquals(taskId, envelope.task!!.id)
    }

    @Test
    fun `an unknown route is a typed failure not a crash`() = runBlocking {
        val transport = transport()
        try {
            transport.get("/v1/definitely-not-a-route")
            fail("an unknown route must fail")
        } catch (e: CodexHttpException) {
            assertTrue("expected a 4xx, got ${e.status}", e.status in 400..499)
        }
    }

    private companion object {
        const val BRIDGE_URL = "clawmate.codex.bridgeUrl"
        const val BRIDGE_TOKEN = "clawmate.codex.bridgeToken"
        const val BRIDGE_CA_FILE = "clawmate.codex.bridgeCaFile"
    }
}
