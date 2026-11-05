package com.clawmate.platform.codex

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the full delegation flow against a live Codex bridge.
 *
 * Where [CodexTransportTest] proves the HTTP contract and [CodexLiveBridgeTest] proves a
 * single exchange, this exercises [CodexBackend] — connect, create, submit, follow and
 * deliver — with no scripted responses in between.
 *
 * Skipped unless `-Dclawmate.codex.bridgeUrl` / `-DbridgeToken` are supplied, matching the
 * other live tests. A `-DbridgeCaFile` pins a certificate the same way a pairing file would.
 *
 * The bridge may front the repo's `test/fake-app-server.mjs`; nothing here asserts model
 * quality, only that the delegation reaches a terminal state with a coherent result.
 */
class CodexBackendLiveTest {

    private val url: String = System.getProperty(BRIDGE_URL) ?: ""
    private val token: String = System.getProperty(BRIDGE_TOKEN) ?: ""
    private val caCertificate: String = System.getProperty(BRIDGE_CA_FILE)
        ?.takeIf { it.isNotEmpty() }
        ?.let { File(it).takeIf(File::isFile)?.readText() }
        ?.replace("\r", "")
        ?: ""

    private lateinit var dir: File
    private lateinit var credentials: CodexCredentialStore
    private lateinit var connectionId: String

    private class MemoryStorage : CodexSecretStorage {
        val data = LinkedHashMap<String, String>()
        override fun readAll(): Map<String, String> = data.toMap()
        override fun write(id: String, value: String) { data[id] = value }
        override fun remove(id: String) { data.remove(id) }
    }

    private class Handler : ChatHandlers {
        val statuses = mutableListOf<String>()
        val artifacts = mutableListOf<ChatArtifact>()
        val runIds = mutableListOf<String>()
        override fun onDelta(text: String) = Unit
        override fun onTrace(line: String) = Unit
        override fun onStatus(status: String, notice: String) { statuses += status }
        override fun onArtifact(artifact: ChatArtifact) { artifacts += artifact }
        override fun onRunCreated(runId: String) { runIds += runId }
    }

    @After
    fun tearDown() {
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun backend(): CodexBackend {
        assumeTrue("no live bridge configured", url.isNotEmpty() && token.isNotEmpty())
        dir = Files.createTempDirectory("codex-live-backend").toFile()
        credentials = CodexCredentialStore(MemoryStorage()) { System.currentTimeMillis() }
        val saved = credentials.save(
            CodexConnection(origin = url, token = token, caCertificate = caCertificate),
        )
        connectionId = saved.id
        val transport = CodexTransport(clientFactory = { ca -> CodexTransport.defaultClient(ca) })
        return CodexBackend(
            transport = transport,
            credentials = credentials,
            artifacts = CodexArtifactStore(dir),
            followTimeoutMs = 60_000,
        ).apply {
            // The chat path cannot prompt; it resolves the workspace from /v1/info.
            selector = CodexBackend.TargetSelector { saved2, _, _ -> CodexBackend.Target(saved2.id, "") }
            defaultWorkspace = { info -> info.workspaces.firstOrNull()?.id.orEmpty() }
        }
    }

    @Test
    fun `a delegation reaches a terminal state through the real bridge`() = runBlocking {
        val handlers = Handler()
        val result = backend().run(ChatRequest("session-1", "describe the project"), handlers)

        assertTrue("run id must be exposed", handlers.runIds.isNotEmpty())
        assertEquals("completed", result.status)
        assertTrue("a completed run must carry an answer", result.answer.isNotEmpty())
        assertTrue(
            "statuses must include the terminal state, saw ${handlers.statuses}",
            handlers.statuses.contains("completed"),
        )
    }

    @Test
    fun `reported capabilities match what the backend can actually do`() = runBlocking {
        val capabilities = backend().capabilities()
        assertEquals(ChatBackendId.CODEX, backend().id)
        assertTrue(capabilities.images)
        assertTrue(capabilities.deliversFiles)
        assertTrue(capabilities.resumable)
        assertTrue(capabilities.requiresApproval)
        assertTrue(capabilities.tools.contains(CodexBackend.TOOL_NAME))
    }

    @Test
    fun `an unpaired backend refuses to run`() = runBlocking {
        assumeTrue("no live bridge configured", url.isNotEmpty() && token.isNotEmpty())
        val empty = CodexCredentialStore(MemoryStorage())
        val backend = CodexBackend(
            CodexTransport(clientFactory = { CodexTransport.defaultClient("") }),
            empty,
            CodexArtifactStore(Files.createTempDirectory("codex-live-empty").toFile()),
        )
        try {
            backend.run(ChatRequest("s", "task"), Handler())
            org.junit.Assert.fail("an unpaired backend must not run")
        } catch (e: ChatBackendUnavailableException) {
            assertNotNull(e.message)
        }
    }

    private companion object {
        const val BRIDGE_URL = "clawmate.codex.bridgeUrl"
        const val BRIDGE_TOKEN = "clawmate.codex.bridgeToken"
        const val BRIDGE_CA_FILE = "clawmate.codex.bridgeCaFile"
    }
}
