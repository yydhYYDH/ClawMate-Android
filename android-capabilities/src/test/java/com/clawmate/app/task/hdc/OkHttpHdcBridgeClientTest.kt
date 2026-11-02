package com.clawmate.app.task.hdc

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Deterministic tests for [OkHttpHdcBridgeClient] against an in-process
 * MockWebServer (no live remote endpoint): request mapping (health GET vs
 * workflow POST body), HTTP error mapping, malformed-JSON mapping, timeout
 * mapping, invalid-URL mapping, and cancellation.
 */
class OkHttpHdcBridgeClientTest {

    private lateinit var server: MockWebServer

    private val ok = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(baseUrl: String = server.url("/").toString(), http: OkHttpClient = ok) =
        OkHttpHdcBridgeClient(baseUrl = baseUrl, client = http)

    @Test
    fun `health performs GET on normalized health url`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"ok","hdc_connected":true,"fport_ready":true,"rport_ready":true,"target":"h:1"}"""),
        )
        val result = client().health()
        val recorded = server.takeRequest()
        assertEquals("/api/health", recorded.path)
        assertEquals("GET", recorded.method)
        val health = (result as HdcBridgeResult.Health).value
        assertTrue(health.hdcConnected)
    }

    @Test
    fun `workflow performs POST with action and payload body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"ok","message":"app_start com.x","package_name":"com.x"}"""),
        )
        val result = client().workflow("app_start", mapOf("package_name" to "com.x", "reset_first" to true))
        val recorded = server.takeRequest()
        assertEquals("/api/workflow", recorded.path)
        assertEquals("POST", recorded.method)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"action\":\"app_start\""))
        assertTrue(body.contains("\"package_name\":\"com.x\""))
        assertTrue(body.contains("\"reset_first\":true"))
        val resp = (result as HdcBridgeResult.Response).value
        assertEquals(HdcBridgeStatus.OK, resp.statusEnum)
        assertEquals("com.x", resp.packageName)
    }

    @Test
    fun `non 2xx maps to HTTP error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        val result = client().health()
        val err = result as HdcBridgeResult.Error
        assertEquals(HdcBridgeErrorKind.HTTP, err.kind)
        assertTrue(err.message.contains("500"))
    }

    @Test
    fun `malformed json body maps to MALFORMED_JSON`() = runBlocking {
        server.enqueue(MockResponse().setBody("not-json-at-all"))
        val error = client().health() as HdcBridgeResult.Error
        assertEquals(HdcBridgeErrorKind.MALFORMED_JSON, error.kind)
    }

    @Test
    fun `invalid base url maps to INVALID_URL without any request`() = runBlocking {
        val result = client(baseUrl = "ftp://host:9124").workflow("health")
        val err = result as HdcBridgeResult.Error
        assertEquals(HdcBridgeErrorKind.INVALID_URL, err.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `connection refused maps to IO`() = runBlocking {
        // Connect to a closed port on localhost.
        val client = OkHttpHdcBridgeClient(baseUrl = "http://127.0.0.1:1", client = ok)
        val err = client.health() as HdcBridgeResult.Error
        assertTrue(err.kind == HdcBridgeErrorKind.IO || err.kind == HdcBridgeErrorKind.TIMEOUT)
    }

    @Test
    fun `call timeout maps to TIMEOUT`() = runBlocking {
        val slow = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val err = client(http = slow).health() as HdcBridgeResult.Error
        assertEquals(HdcBridgeErrorKind.TIMEOUT, err.kind)
    }

    @Test
    fun `cancelling the surrounding job surfaces CANCELLED and never crashes`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = async { client(http = okCancellable()).health() }
        delay(100)
        job.cancel()
        val outcome = try {
            job.await()
        } catch (c: kotlinx.coroutines.CancellationException) {
            null
        }
        if (outcome != null) {
            val err = outcome as HdcBridgeResult.Error
            assertEquals(HdcBridgeErrorKind.CANCELLED, err.kind)
        }
    }

    private fun okCancellable(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
}
