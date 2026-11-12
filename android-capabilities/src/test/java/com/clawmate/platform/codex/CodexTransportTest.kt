package com.clawmate.platform.codex

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Transport contract against a real HTTP server.
 *
 * The properties under test are the ones that make remote delegation safe: a lost POST is
 * never resent, only an idempotent read retries (once), redirects and 401s are surfaced
 * instead of followed or retried, and a replaced connection cannot accept a stale response.
 *
 * `assertThrows` cannot wrap a suspending call without an awkward bridge, so failures are
 * captured explicitly.
 */
class CodexTransportTest {

    private lateinit var server: MockWebServer

    private val origin: String get() = server.url("/").toString().trimEnd('/')
    private val token = "a".repeat(43)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun transport(client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()): CodexTransport = CodexTransport(clientFactory = { client }).apply {
        // configure() enforces https; the loopback seam is applied afterwards so the
        // transport talks to MockWebServer while the production rule stays intact.
        configure(CodexConnection(origin = "https://placeholder.example", token = token))
        useHttpOriginForTest(origin)
    }

    /** Runs [block] and returns the failure, or fails the test when nothing was thrown. */
    private suspend inline fun failureOf(block: () -> Unit): Throwable {
        try {
            block()
        } catch (t: Throwable) {
            return t
        }
        fail("expected the call to fail")
        throw IllegalStateException("unreachable")
    }

    @Test
    fun `a GET retries exactly once after a transient failure`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ready":true}"""))

        val result = transport().get("/v1/info")

        assertEquals(true, result.ready)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a lost POST is never resent`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ready":true}"""))

        val transport = transport()
        val failure = failureOf { transport.post("/v1/tasks/task-1/turns", buildJsonObject { put("requestId", "r") }) }

        assertTrue(failure is CodexTransportException)
        // Exactly one attempt reached the server: a resend could run the task twice.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 401 is reported as an auth failure and not retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"denied"}"""))

        var reachable: Boolean? = null
        val transport = transport().apply { onReachability = { ok, _ -> reachable = ok } }
        val failure = failureOf { transport.get("/v1/info") }

        assertTrue(failure is CodexAuthException)
        assertEquals(1, server.requestCount)
        assertEquals(false, reachable)
    }

    @Test
    fun `health and task update reads leave retries to the poller`() = runBlocking {
        val transport = transport()
        for (route in listOf("/v1/health", "/v1/tasks/task/updates?waitMs=12000")) {
            server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"unavailable"}"""))
            val failure = failureOf { transport.get(route) }
            assertEquals(502, (failure as CodexHttpException).status)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the timeout also covers a stalled response body`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"name":"${"x".repeat(60)}"}""")
            .throttleBody(1, 80, TimeUnit.MILLISECONDS))
        val failure = failureOf { transport().get("/v1/info", timeoutMs = 3_000L) }
        assertTrue("unexpected: $failure", failure is CodexNetworkException)
        assertTrue(failure.message.orEmpty().contains("超时"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `route TLS and loopback failures have distinct recovery messages`() {
        val transport = CodexTransport()
        val noRoute = transport.classify(ConnectException("connect failed: EHOSTUNREACH"), "192.168.1.2")
        assertTrue(noRoute.message.orEmpty().contains("互访"))
        assertFalse(noRoute.retryable)
        val tls = transport.classify(SSLPeerUnverifiedException("hostname mismatch"), "192.168.1.2")
        assertTrue(tls.message.orEmpty().contains("证书"))
        assertFalse(tls.retryable)
        val usb = transport.classify(ConnectException("Connection refused"), "127.0.0.1")
        assertTrue(usb.message.orEmpty().contains("USB"))
    }

    @Test
    fun `a redirect is refused instead of followed`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "https://elsewhere.example/v1/info")
                .setBody("""{"error":"moved"}"""),
        )

        val failure = failureOf { transport().get("/v1/info") }

        // The redirect must be refused, never followed: following it would replay the token.
        assertTrue("unexpected: $failure", failure is CodexValidationException)
        assertTrue(failure.message!!.contains("重定向"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a redirect without a body is still reported as a redirect`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(301).addHeader("Location", "https://elsewhere.example/"))

        val failure = failureOf { transport().get("/v1/info") }

        assertTrue("unexpected: $failure", failure is CodexValidationException)
        assertTrue(failure.message!!.contains("重定向"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `429 is surfaced without retrying`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"slow down"}"""))

        val failure = failureOf { transport().get("/v1/info") }

        assertTrue(failure is CodexHttpException)
        assertEquals(429, (failure as CodexHttpException).status)
        assertFalse(failure.retryable)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 502 read is retryable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"bad gateway"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ready":true}"""))

        val result = transport().get("/v1/info")

        assertEquals(true, result.ready)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a 502 POST is not retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"bad gateway"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ready":true}"""))

        val failure = failureOf { transport().post("/v1/tasks", buildJsonObject { put("a", "b") }) }

        assertTrue(failure is CodexHttpException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a non protocol body is rejected`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        val failure = failureOf { transport().get("/v1/info") }
        assertTrue(failure is CodexProtocolException)
    }

    @Test
    fun `unknown envelope fields are ignored`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ready":true,"futureField":{"x":1}}"""))
        val result = transport().get("/v1/info")
        assertEquals(true, result.ready)
    }

    @Test
    fun `the bearer token is sent and never placed in the URL`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ready":true}"""))

        transport().get("/v1/info")

        val request = server.takeRequest()
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertFalse(request.path!!.contains(token))
        assertFalse((request.requestUrl!!.query ?: "").contains(token))
    }

    @Test
    fun `closing the transport isolates the connection`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ready":true}"""))
        val transport = transport()
        transport.get("/v1/info")

        transport.close()

        assertFalse(transport.isConfigured())
        // After close nothing is configured, so a call cannot inherit the old connection.
        val failure = failureOf { transport.get("/v1/info") }
        assertTrue(failure is CodexValidationException)
    }

    @Test
    fun `an invalid token is rejected before any request`() = runBlocking {
        val transport = CodexTransport(clientFactory = { OkHttpClient() })
        val failure = failureOf {
            transport.configure(CodexConnection(origin = "https://codex.example.com", token = "short"))
        }
        assertTrue(failure is CodexValidationException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an http origin cannot be configured`() = runBlocking {
        val transport = CodexTransport(clientFactory = { OkHttpClient() })
        val failure = failureOf { transport.configure(CodexConnection(origin = "http://codex.example.com", token = token)) }
        assertTrue(failure is CodexValidationException)
    }

    @Test
    fun `the loopback seam rejects a non loopback host`() {
        val transport = CodexTransport()
        val failure = runCatching { transport.useHttpOriginForTest("http://evil.example") }.exceptionOrNull()
        assertTrue(failure is CodexValidationException)
    }

    @Test
    fun `a gzip response is decoded transparently`() = runBlocking {
        // The bridge compresses any body of 1024 bytes or more. Setting Accept-Encoding by
        // hand bypasses OkHttp's transparent inflation and yields raw gzip bytes, which is
        // how every large response failed once against a real server.
        val large = """{"ready":true,"name":"${"x".repeat(4096)}"}"""
        val raw = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(large.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Encoding", "gzip")
                .setBody(okio.Buffer().apply { write(raw) }),
        )

        val result = transport().get("/v1/info")

        assertEquals(true, result.ready)
        assertEquals(4096, result.name.length)
    }

    @Test
    fun `a caller-supplied timeout is bounded by the transport ceiling`() = runBlocking {
        // A platform bridge call cannot block indefinitely, so a caller asking for more than
        // the transport allows must be capped rather than left to fail at the bridge.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ready":true}"""))
        val transport = transport()
        transport.get("/v1/info", timeoutMs = 10L * 60 * 1000)
        assertEquals(1, server.requestCount)
        // A non-positive request keeps the transport default and still works.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ready":true}"""))
        transport.get("/v1/info", timeoutMs = 0L)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a very small timeout still issues the call`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ready":true}"""))
        // Clamped up to the minimum instead of becoming an instant timeout.
        val result = transport().get("/v1/info", timeoutMs = 1L)
        assertEquals(true, result.ready)
    }

    @Test
    fun `the artifact chunk size is bounded`() {
        val transport = CodexTransport()
        transport.setArtifactChunkBytes(262_144)
        assertEquals(262_144L, transport.artifactChunkBytes())
        transport.setArtifactChunkBytes(1)
        assertEquals(CodexTransport.DEFAULT_CHUNK_BYTES, transport.artifactChunkBytes())
        transport.setArtifactChunkBytes(8L * 1024 * 1024)
        assertEquals(CodexTransport.DEFAULT_CHUNK_BYTES, transport.artifactChunkBytes())
    }
}

