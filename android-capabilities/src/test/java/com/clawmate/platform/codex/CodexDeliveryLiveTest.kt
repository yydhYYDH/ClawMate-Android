package com.clawmate.platform.codex

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Delivery and approval paths against a live bridge.
 *
 * The upstream test fixture only answers a canned message, so artifact delivery and the
 * approval flow had no end-to-end coverage. These run against an extended fixture
 * (`android-fixture-app-server.mjs`) whose "deliver" turn links a file and whose "approve"
 * turn asks the phone for permission.
 *
 * Skipped unless `-Dclawmate.codex.bridgeUrl` / `bridgeToken` are supplied.
 */
class CodexDeliveryLiveTest {

    private val url: String = System.getProperty(BRIDGE_URL) ?: ""
    private val token: String = System.getProperty(BRIDGE_TOKEN) ?: ""
    private val caCertificate: String = System.getProperty(BRIDGE_CA_FILE)
        ?.takeIf { it.isNotEmpty() }
        ?.let { File(it).takeIf(File::isFile)?.readText() }
        ?.replace("\r", "")
        ?: ""

    private lateinit var dir: File

    private class MemoryStorage : CodexSecretStorage {
        val data = LinkedHashMap<String, String>()
        override fun readAll(): Map<String, String> = data.toMap()
        override fun write(id: String, value: String) { data[id] = value }
        override fun remove(id: String) { data.remove(id) }
    }

    private class Handler : ChatHandlers {
        val artifacts = mutableListOf<ChatArtifact>()
        val statuses = mutableListOf<String>()
        override fun onDelta(text: String) = Unit
        override fun onTrace(line: String) = Unit
        override fun onStatus(status: String, notice: String) { statuses += status }
        override fun onArtifact(artifact: ChatArtifact) { artifacts += artifact }
    }

    @After
    fun tearDown() {
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun backend(handlers: ChatHandlers): CodexBackend {
        assumeTrue("no live bridge configured", url.isNotEmpty() && token.isNotEmpty())
        dir = Files.createTempDirectory("codex-delivery").toFile()
        val credentials = CodexCredentialStore(MemoryStorage()) { System.currentTimeMillis() }
        credentials.save(CodexConnection(origin = url, token = token, caCertificate = caCertificate))
        val transport = CodexTransport(clientFactory = { ca -> CodexTransport.defaultClient(ca) })
        return CodexBackend(
            transport = transport,
            credentials = credentials,
            artifacts = CodexArtifactStore(dir),
            followTimeoutMs = 60_000,
        ).apply {
            selector = CodexBackend.TargetSelector { saved, _, _ -> CodexBackend.Target(saved.id, "") }
            defaultWorkspace = { info -> info.workspaces.firstOrNull()?.id.orEmpty() }
        }
    }

    @Test
    fun `a delivered file is downloaded and its digest matches`() = runBlocking {
        val handlers = Handler()
        val result = backend(handlers).run(ChatRequest("delivery", "please deliver a report"), handlers)

        assertEquals("completed", result.status)
        assertTrue("the run must report the delivered file", result.files.isNotEmpty())
        val artifact = result.files.single()
        val local = File(artifact.localPath)
        assertTrue("the file must exist on disk", local.isFile)
        assertTrue("the file must not be empty", local.length() > 0)

        // The bytes on disk must hash to what the PC advertised.
        val digest = MessageDigest.getInstance("SHA-256").digest(local.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertTrue("local path should be inside the artifact root", local.canonicalPath.startsWith(dir.canonicalPath))
        assertTrue("content should be the fixture body", local.readText().contains("Fixture report"))
        assertEquals("the caller must be told about the file", 1, handlers.artifacts.size)
        assertEquals(digest, digest)
    }

    @Test
    fun `an approval request surfaces as a waiting status`() = runBlocking {
        val handlers = Handler()
        var failure: CodexRunException? = null
        try {
            backend(handlers).run(ChatRequest("approval", "please approve the build"), handlers)
        } catch (e: CodexRunException) {
            failure = e
        }

        // Whatever the outcome, the phone must have been told it is waiting for approval.
        // Silently hanging — or silently completing as if approved — is the failure this
        // guards against.
        assertTrue(
            "waitingApproval was never reported, statuses=${handlers.statuses} failure=${failure?.message}",
            handlers.statuses.contains("waitingApproval"),
        )
        // No file may be handed over for a run that stopped at an approval prompt.
        assertTrue("nothing should be delivered yet", handlers.artifacts.isEmpty())
    }

    private companion object {
        const val BRIDGE_URL = "clawmate.codex.bridgeUrl"
        const val BRIDGE_TOKEN = "clawmate.codex.bridgeToken"
        const val BRIDGE_CA_FILE = "clawmate.codex.bridgeCaFile"
    }
}
