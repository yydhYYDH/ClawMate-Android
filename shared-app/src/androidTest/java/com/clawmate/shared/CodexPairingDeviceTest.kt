package com.clawmate.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CodexPairingDeviceTest {
    @Test fun pairingUsesNoBearerAndSameOriginUsesTheNewDeviceToken() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.start()
            val origin = "https://localhost:${server.port}"
            val bridge = CodexPlatformBridge(InstrumentationRegistry.getInstrumentation().targetContext)
            val oldToken = "a".repeat(43)
            val pairingCode = "b".repeat(43)
            val deviceToken = "c".repeat(43)
            fun exchange(route: String, token: String, body: JSONObject? = null): JSONObject {
                val raw = JSONObject().put("id", route).put("origin", origin).put("route", route)
                    .put("token", token).put("caCertificate", certificate.certificatePem())
                    .put("authenticated", body == null).put("method", if (body == null) "GET" else "POST")
                    .put("body", body?.toString()).toString()
                val ticket = JSONObject(bridge.codexRequest(raw)).getString("ticket")
                repeat(200) {
                    val result = JSONObject(bridge.codexRequestPoll(ticket))
                    if (result.optString("status") != "pending") return result
                    Thread.sleep(25)
                }
                error("Codex request did not complete")
            }
            try {
                server.enqueue(MockResponse().setBody("""{"ready":true}"""))
                assertEquals(200, exchange("/v1/info", oldToken).getInt("status"))
                assertEquals("Bearer $oldToken", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))

                server.enqueue(MockResponse().setBody(JSONObject().put("token", deviceToken).toString()))
                val paired = exchange("/v1/pair", pairingCode, JSONObject().put("code", pairingCode))
                assertEquals(deviceToken, JSONObject(paired.getString("text")).getString("token"))
                val pair = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertNull(pair.getHeader("Authorization"))
                assertEquals(pairingCode, JSONObject(pair.body.readUtf8()).getString("code"))

                server.enqueue(MockResponse().setBody("""
                    {"task":null,"taskDelta":{"baseRevision":1,"task":{"id":"task","revision":2,"status":"completed"}}}
                """.trimIndent()))
                val update = exchange("/v1/tasks/task/updates?waitMs=1", deviceToken)
                assertEquals(200, update.getInt("status"))
                val payload = JSONObject(update.getString("text"))
                assertFalse(payload.has("task"))
                assertEquals("completed", payload.getJSONObject("taskDelta").getJSONObject("task").getString("status"))
                val authorization = server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization")
                assertEquals("Bearer $deviceToken", authorization)

                server.enqueue(MockResponse().setResponseCode(401))
                assertEquals(401, exchange("/v1/info", deviceToken).getInt("status"))
                assertNotEquals("Bearer $pairingCode", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            } finally {
                bridge.close()
            }
        }
    }
}
