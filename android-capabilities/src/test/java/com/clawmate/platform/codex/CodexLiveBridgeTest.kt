package com.clawmate.platform.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Runs the real transport against a live Codex bridge.
 *
 * This is the only test that exercises the actual HTTP contract; everything else uses
 * MockWebServer or a scripted dispatcher. It is skipped unless [BRIDGE_URL] and
 * [BRIDGE_TOKEN] are provided, so ordinary builds stay hermetic:
 *
 * ```
 * -Dclawmate.codex.bridgeUrl=http://127.0.0.1:8787
 * -Dclawmate.codex.bridgeToken=<token>
 * ```
 *
 * The bridge may front the repo's `test/fake-app-server.mjs` fixture; nothing here asserts
 * model quality, only protocol behaviour.
 *
 * A plain-HTTP origin is accepted for a loopback address because the transport's `https://`
 * rule guards real deployments, and `useHttpOriginForTest` enforces that restriction.
 */
class CodexLiveBridgeTest {

    private val url: String = System.getProperty(BRIDGE_URL) ?: ""
    private val token: String = System.getProperty(BRIDGE_TOKEN) ?: ""
    private val caCertificate: String = System.getProperty(BRIDGE_CA_FILE)
        ?.takeIf { it.isNotEmpty() }
        ?.let { java.io.File(it).takeIf(java.io.File::isFile)?.readText() }
        ?.replace("\r", "")
        ?: ""

    /**
     * Builds a transport that follows the production path exactly.
     *
     * With a CA configured this pins it for the connection, which is what a pairing file
     * does on the device; without one it trusts the platform store.
     */
    private fun transport(): CodexTransport {
        assumeTrue("no live bridge configured", url.isNotEmpty() && token.isNotEmpty())
        val transport = CodexTransport(clientFactory = { ca -> CodexTransport.defaultClient(ca) })
        if (url.startsWith("https://")) {
            transport.configure(CodexConnection(origin = url, token = token, caCertificate = caCertificate))
        } else {
            transport.configure(CodexConnection(origin = "https://example.invalid", token = token))
            transport.useHttpOriginForTest(url)
        }
        return transport
    }

    @Test
    fun `a pinned certificate secures the connection when supplied`() = runBlocking {
        assumeTrue("no https bridge with a certificate configured", url.startsWith("https://") && caCertificate.isNotEmpty())
        val info = transport().get("/v1/info")
        assertEquals(1, info.protocol)
        assertTrue(info.ready == true)
    }

    @Test
    fun `an unpinned client refuses the self-signed bridge`() = runBlocking {
        assumeTrue("no https bridge with a certificate configured", url.startsWith("https://") && caCertificate.isNotEmpty())
        // Without the pairing certificate the platform store must not accept the test CA.
        val transport = CodexTransport(clientFactory = { CodexTransport.defaultClient("") })
        transport.configure(CodexConnection(origin = url, token = token))
        try {
            transport.get("/v1/info")
            fail("an untrusted self-signed certificate must not be accepted")
        } catch (e: CodexTransportException) {
            assertTrue("expected a transport failure, got ${e.message}", e.message!!.isNotEmpty())
        }
    }

    @Test
    fun `info advertises the capabilities the client requires`() = runBlocking {
        val transport = transport()
        val info = transport.get("/v1/info")

        assertEquals(1, info.protocol)
        assertEquals(true, info.ready)
        assertTrue("permission settings are required", info.permissionSettingsSupported == true)
        assertTrue("artifact delivery is required", info.artifactsSupported == true)
        assertTrue("a workspace is required", info.workspaces.isNotEmpty())
        assertTrue("a model is required", info.models.isNotEmpty())
        val chunk = info.artifactChunkBytes ?: 0
        assertTrue("chunk size must be usable", chunk == 0L || chunk in 16_384..1_048_576)
    }

    @Test
    fun `a task runs to completion and its answer is readable`() = runBlocking {
        val transport = transport()
        val info = transport.get("/v1/info")
        val model = info.models.firstOrNull { it.isDefault } ?: info.models.first()

        val created = transport.postTyped(
            "/v1/tasks",
            CodexCreateBody(
                workspaceId = info.workspaces.first().id,
                model = model.model.ifEmpty { model.id },
                effort = model.defaultReasoningEffort,
            ),
        )
        val taskId = created.task?.id ?: fail("bridge created no task").let { "" }
        assertTrue(CodexTypes.isValidTaskId(taskId))

        val requestId = java.util.UUID.randomUUID().toString()
        val started = transport.postTyped(
            "/v1/tasks/$taskId/turns",
            CodexTurnBody(
                requestId = requestId,
                text = "describe this project",
                attachments = emptyList(),
                model = model.model.ifEmpty { model.id },
                effort = model.defaultReasoningEffort,
            ),
        )
        assertEquals(requestId, started.task?.lastRequestId)

        // Follow until the turn leaves a busy state.
        var task = started.task!!
        val deadline = System.currentTimeMillis() + 30_000
        while (CodexTypes.taskIsBusy(task.status) && System.currentTimeMillis() < deadline) {
            task = transport.get("/v1/tasks/$taskId?revision=${task.revision}&waitMs=2000").task ?: task
        }
        assertEquals("completed", task.status)
        assertTrue("the answer must be attributed to this turn", task.turnId.isNotEmpty())
        assertTrue("a submitted turn must record its id", task.items.isNotEmpty())
    }

    @Test
    fun `a wrong token is rejected as an auth failure`() = runBlocking {
        assumeTrue("no live bridge configured", url.isNotEmpty() && token.isNotEmpty())
        // Keep the real trust configuration so this exercises auth, not certificate failure.
        val transport = CodexTransport(clientFactory = { ca -> CodexTransport.defaultClient(ca) })
        if (url.startsWith("https://")) {
            transport.configure(
                CodexConnection(origin = url, token = "a".repeat(43), caCertificate = caCertificate),
            )
        } else {
            transport.configure(CodexConnection(origin = "https://example.invalid", token = "a".repeat(43)))
            transport.useHttpOriginForTest(url)
        }
        try {
            transport.get("/v1/info")
            fail("an invalid token must not be accepted")
        } catch (e: CodexAuthException) {
            assertNotNull(e.message)
        }
    }

    private companion object {
        const val BRIDGE_URL = "clawmate.codex.bridgeUrl"
        const val BRIDGE_TOKEN = "clawmate.codex.bridgeToken"
        const val BRIDGE_CA_FILE = "clawmate.codex.bridgeCaFile"
    }
}

