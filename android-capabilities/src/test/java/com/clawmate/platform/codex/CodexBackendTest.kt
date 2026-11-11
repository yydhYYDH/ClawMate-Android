package com.clawmate.platform.codex

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * End-to-end delegation flow against a scripted bridge.
 *
 * These cover the ordering guarantees rather than only the happy path: the turn is submitted
 * exactly once, a lost turn response is reconciled by a read, an unusable delta causes a full
 * resync, and a delivered file is hash-verified before it reaches the user.
 *
 * The bridge stamps each task with the caller's `requestId`, so the scripted responses echo
 * whatever the turn POST carried — that is what the not-resend guard compares against.
 */
class CodexBackendTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val token = "a".repeat(43)
    private val origin: String get() = server.url("/").toString().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    private class RecordingStorage : CodexSecretStorage {
        val data = LinkedHashMap<String, String>()
        override fun readAll(): Map<String, String> = data.toMap()
        override fun write(id: String, value: String) { data[id] = value }
        override fun remove(id: String) { data.remove(id) }
    }

    private class RecordingHandlers : ChatHandlers {
        val statuses = mutableListOf<String>()
        val artifacts = mutableListOf<ChatArtifact>()
        val runIds = mutableListOf<String>()
        override fun onDelta(text: String) = Unit
        override fun onTrace(line: String) = Unit
        override fun onStatus(status: String, notice: String) { statuses += status }
        override fun onArtifact(artifact: ChatArtifact) { artifacts += artifact }
        override fun onRunCreated(runId: String) { runIds += runId }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("codex-backend").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun store(): CodexCredentialStore =
        CodexCredentialStore(RecordingStorage()) { 1000L }.apply {
            save(CodexConnection(origin = "https://placeholder.example", token = token))
        }

    /** A backend whose transport talks to the mock after every `configure`. */
    private fun backend(credentials: CodexCredentialStore = store()): CodexBackend {
        val transport = CodexTransport(clientFactory = {
            OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build()
        })
        transport.onConfigureForTest = { transport.useHttpOriginForTest(origin) }
        return CodexBackend(transport, credentials, CodexArtifactStore(dir)).apply {
            selector = CodexBackend.TargetSelector { saved, _, _ -> CodexBackend.Target(saved.id, "app") }
        }
    }

    private fun info(taskUpdates: Boolean = false, artifactsSupported: Boolean = true) = """
        {"protocol":1,"name":"我的开发机","ready":true,
         "workspaces":[{"id":"app","name":"项目"}],
         "models":[{"id":"m1","model":"gpt-5","displayName":"GPT-5","defaultReasoningEffort":"medium","isDefault":true}],
         "artifactsSupported":$artifactsSupported,"permissionSettingsSupported":true,
         "taskUpdatesSupported":$taskUpdates,"taskDeltaSupported":$taskUpdates,"artifactChunkBytes":262144}
    """.trimIndent()

    private fun task(
        status: String = "running",
        revision: Long = 1,
        requestId: String = "req-1",
        turnId: String = "turn-1",
        artifacts: String = "[]",
        items: String = "[]",
        id: String = "task-1",
    ) = """
        {"task":{"id":"$id","title":"t","status":"$status","workspaceId":"app","model":"gpt-5",
          "effort":"medium","mode":"workspace-write","turnId":"$turnId","items":$items,"approvals":[],
          "updatedAt":1,"revision":$revision,"error":"","lastRequestId":"$requestId","artifacts":$artifacts}}
    """.trimIndent()

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    /**
     * A dispatcher that mirrors the bridge: it records the turn's request id and echoes it on
     * every later read.
     */
    private fun scripted(
        updates: Boolean = false,
        artifactsSupported: Boolean = true,
        onTask: (RecordedRequest, String) -> MockResponse,
    ): Dispatcher = object : Dispatcher() {
        @Volatile
        var requestId: String = "-"

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty().substringBefore('?')
            return when {
                path == "/v1/info" -> ok(info(updates, artifactsSupported))
                path == "/v1/tasks" -> ok(task(status = "idle", revision = 0, turnId = "", requestId = "-"))
                path.endsWith("/turns") -> {
                    val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    requestId = body["requestId"]!!.jsonPrimitive.content
                    ok(task(revision = 1, requestId = requestId))
                }
                else -> onTask(request, requestId)
            }
        }
    }

    private fun executedPaths(): List<String> =
        generateSequence { server.takeRequest(0, java.util.concurrent.TimeUnit.SECONDS) }
            .toList().map { it.path!!.substringBefore('?') }

    @Test
    fun `a full run creates one task submits one turn and returns the answer`() = runBlocking {
        server.dispatcher = scripted { _, requestId ->
            ok(task(status = "completed", revision = 2, requestId = requestId,
                items = """[{"id":"a","kind":"assistant","text":"done","turnId":"turn-1"}]"""))
        }

        val handlers = RecordingHandlers()
        val result = backend().run(ChatRequest("s1", "修复登录"), handlers)

        assertTrue("error: ${result.error}", result.ok)
        assertEquals("completed", result.status)
        assertEquals("done", result.answer)
        assertEquals(1, handlers.runIds.size)

        val paths = executedPaths()
        assertEquals(1, paths.count { it == "/v1/tasks" })
        assertEquals(1, paths.count { it.endsWith("/turns") })
    }

    @Test
    fun `a saved completed run continues with a new turn on the same task`() = runBlocking {
        val records = mutableMapOf<String, CodexBackend.RunRecord>()
        val runs = object : CodexBackend.CodexRunStore {
            override fun load(id: String) = records[id]?.copy()
            override fun save(run: CodexBackend.RunRecord) { records[run.id] = run.copy() }
        }
        server.dispatcher = object : Dispatcher() {
            private var reply = task(status = "idle", revision = 0, turnId = "")
            private var turns = 0
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/info" -> ok(info())
                "/v1/tasks", "/v1/tasks/task-1" -> ok(reply)
                "/v1/tasks/task-1/turns" -> {
                    val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val requestId = body.getValue("requestId").jsonPrimitive.content
                    val saved = records.values.single { it.requestId == requestId }
                    check(saved.submitted && saved.taskId == "task-1")
                    turns++
                    val turn = "turn-$turns"
                    reply = task(status = "completed", revision = turns.toLong(), requestId = requestId, turnId = turn,
                        items = """[{"id":"answer-$turns","kind":"assistant","text":"reply-$turns","turnId":"$turn"}]""")
                    ok(reply)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val credentials = store()
        val original = backend(credentials).apply { runStore = runs }.run(ChatRequest("s1", "第一轮"), RecordingHandlers())
        val continued = backend(credentials).apply { runStore = runs }.run(
            ChatRequest("s1", "请继续修改", continueRunId = original.runId), RecordingHandlers(),
        )
        assertEquals("reply-2", continued.answer)
        assertEquals("completed", records.getValue(continued.runId).status)
        val paths = executedPaths()
        assertEquals(1, paths.count { it == "/v1/tasks" })
        assertEquals(2, paths.count { it.endsWith("/turns") })
    }

    @Test
    fun `a lost turn response is reconciled by reading not resending`() = runBlocking {
        // The turn POST drops at the socket level; every subsequent read succeeds.
        server.dispatcher = object : Dispatcher() {
            @Volatile
            var requestId: String = "-"

            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                return when {
                    path == "/v1/info" -> ok(info())
                    path == "/v1/tasks" -> ok(task(status = "idle", revision = 0, turnId = "", requestId = "-"))
                    path.endsWith("/turns") -> {
                        // Capture the id even though the response is lost, as the bridge would.
                        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        requestId = body["requestId"]!!.jsonPrimitive.content
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                    }
                    else -> ok(task(status = "completed", revision = 2, requestId = requestId,
                        items = """[{"id":"a","kind":"assistant","text":"recovered","turnId":"turn-1"}]"""))
                }
            }
        }

        val result = backend().run(ChatRequest("s1", "任务"), RecordingHandlers())

        assertEquals("recovered", result.answer)
        assertEquals("the turn POST must not be repeated", 1, executedPaths().count { it.endsWith("/turns") })
    }

    @Test
    fun `an unusable delta triggers a full resync`() = runBlocking {
        var polls = 0
        server.dispatcher = object : Dispatcher() {
            @Volatile
            var requestId: String = "-"

            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                return when {
                    path == "/v1/info" -> ok(info(taskUpdates = true))
                    path == "/v1/tasks" -> ok(task(status = "idle", revision = 0, turnId = "", requestId = "-"))
                    path.endsWith("/turns") -> {
                        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        requestId = body["requestId"]!!.jsonPrimitive.content
                        ok(task(revision = 5, requestId = requestId))
                    }
                    else -> {
                        polls += 1
                        if (polls == 1) {
                            ok(
                                """{"taskDelta":{"baseRevision":2,"task":{"id":"task-1","workspaceId":"app",
                                "revision":6,"lastRequestId":"$requestId","status":"running","turnId":"turn-1"},
                                "items":[],"itemOrder":[]}}""",
                            )
                        } else {
                            ok(task(status = "completed", revision = 7, requestId = requestId,
                                items = """[{"id":"a","kind":"assistant","text":"resynced","turnId":"turn-1"}]"""))
                        }
                    }
                }
            }
        }

        val result = backend().run(ChatRequest("s1", "任务"), RecordingHandlers())

        assertEquals("resynced", result.answer)
        assertEquals("completed", result.status)
    }

    @Test
    fun `a failed info negotiation stops before creating a task`() = runBlocking {
        server.dispatcher = scripted(artifactsSupported = false) { _, _ -> ok(task()) }

        try {
            backend().run(ChatRequest("s1", "任务"), RecordingHandlers())
            fail("expected the run to be refused")
        } catch (e: ChatBackendUnavailableException) {
            assertTrue(e.message!!.contains("连接器"))
        }
        assertFalse(executedPaths().any { it == "/v1/tasks" })
    }

    @Test
    fun `an unpaired device reports an actionable error`() = runBlocking {
        val backend = CodexBackend(CodexTransport(clientFactory = { OkHttpClient() }),
            CodexCredentialStore(RecordingStorage()), CodexArtifactStore(dir))
        try {
            backend.run(ChatRequest("s1", "任务"), RecordingHandlers())
            fail("expected unavailable")
        } catch (e: ChatBackendUnavailableException) {
            assertTrue(e.message!!.contains("配对"))
        }
    }

    @Test
    fun `a delivered file is downloaded and verified`() = runBlocking {
        val content = "报告内容".toByteArray(Charsets.UTF_8)
        val sha = digest(content)
        val base64 = java.util.Base64.getEncoder().encodeToString(content)
        val artifacts = """[{"id":"art1","turnId":"turn-1","name":"report.txt","size":${content.size},
            "mimeType":"text/plain","sha256":"$sha","createdAt":1}]"""

        server.dispatcher = scripted { request, requestId ->
            val path = request.path.orEmpty().substringBefore('?')
            if (path.endsWith("/artifacts/art1")) {
                ok("""{"artifactChunk":{"id":"art1","offset":0,"total":${content.size},"sha256":"$sha","base64":"$base64"}}""")
            } else {
                ok(task(status = "completed", revision = 2, requestId = requestId, artifacts = artifacts,
                    items = """[{"id":"a","kind":"assistant","text":"交付完成","turnId":"turn-1"}]"""))
            }
        }

        val handlers = RecordingHandlers()
        val result = backend().run(ChatRequest("s1", "做一个报告"), handlers)

        assertTrue("errors: ${result.error}", result.ok)
        assertEquals(1, result.files.size)
        val saved = File(result.files.single().localPath)
        assertTrue(saved.isFile)
        assertEquals(String(content, Charsets.UTF_8), saved.readText())
        assertEquals(1, handlers.artifacts.size)
    }

    @Test
    fun `a file with a wrong digest is rejected`() = runBlocking {
        val content = "内容".toByteArray(Charsets.UTF_8)
        // The advertised digest matches the chunk but not the bytes, emulating corruption.
        val announced = digest(content)
        val corrupted = content.copyOf()
        corrupted[0] = (corrupted[0].toInt() xor 0xff).toByte()
        val base64 = java.util.Base64.getEncoder().encodeToString(corrupted)
        val artifacts = """[{"id":"art1","turnId":"turn-1","name":"r.txt","size":${content.size},
            "mimeType":"text/plain","sha256":"$announced","createdAt":1}]"""

        server.dispatcher = scripted { request, requestId ->
            val path = request.path.orEmpty().substringBefore('?')
            if (path.endsWith("/artifacts/art1")) {
                ok("""{"artifactChunk":{"id":"art1","offset":0,"total":${content.size},"sha256":"$announced","base64":"$base64"}}""")
            } else {
                ok(task(status = "completed", revision = 2, requestId = requestId, artifacts = artifacts))
            }
        }

        val result = backend().run(ChatRequest("s1", "任务"), RecordingHandlers())

        assertFalse(result.ok)
        assertTrue(result.files.isEmpty())
        assertTrue("error: ${result.error}", result.error.contains("校验"))
    }

    @Test
    fun `a failed run reports whether the remote outcome is known`() = runBlocking {
        server.dispatcher = scripted { _, _ ->
            MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""")
        }

        try {
            backend().run(ChatRequest("s1", "任务"), RecordingHandlers())
            fail("expected the run to fail")
        } catch (e: CodexRunException) {
            assertTrue("the remote may still be running", e.outcomeUnknown)
            assertTrue(e.runId.isNotEmpty())
        }
    }

    @Test
    fun `the interrupt body matches the bridge contract`() = runBlocking {
        val interruptBodies = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            @Volatile
            var requestId: String = "-"

            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                return when {
                    path == "/v1/info" -> ok(info())
                    path == "/v1/tasks" -> ok(task(status = "idle", revision = 0, turnId = "", requestId = "-"))
                    path.endsWith("/turns") -> {
                        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        requestId = body["requestId"]!!.jsonPrimitive.content
                        ok(task(revision = 1, requestId = requestId))
                    }
                    path.endsWith("/interrupt") -> {
                        synchronized(interruptBodies) { interruptBodies += request.body.readUtf8() }
                        ok(task(status = "interrupted", revision = 3, requestId = requestId))
                    }
                    else -> ok(task(status = "running", revision = 1, requestId = requestId))
                }
            }
        }

        val backend = backend()
        val handlers = RecordingHandlers()
        val stopping = object : ChatHandlers by handlers {
            override fun onRunCreated(runId: String) {
                handlers.onRunCreated(runId)
                backend.cancel(runId)
            }
        }
        backend.run(ChatRequest("s1", "任务"), stopping)

        val body = synchronized(interruptBodies) { interruptBodies.firstOrNull() }
        assertNotNull("the interrupt request was never sent", body)
        val fields = json.parseToJsonElement(body!!).jsonObject.keys
        assertTrue("body: $body", fields.containsAll(setOf("id", "decision", "answers")))
    }

    @Test
    fun `every call the backend makes fits inside one platform bridge call`() {
        // The platform bridge gives up well before a minute, so nothing the backend issues
        // may ask for longer than that. A long poll waits waitMs=12000 on the server.
        val ceiling = CodexTransport.CALL_TIMEOUT_MS
        assertTrue("the poll deadline must fit one bridge call", CodexBackend.POLL_TIMEOUT_MS < ceiling)
        assertTrue("the short deadline must fit one bridge call", CodexBackend.SHORT_TIMEOUT_MS < ceiling)
        assertTrue(
            "a long poll must outlast the server-side wait",
            CodexBackend.POLL_TIMEOUT_MS > 12_000,
        )
    }

    private fun digest(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

