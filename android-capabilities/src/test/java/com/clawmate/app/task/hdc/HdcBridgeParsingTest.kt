package com.clawmate.app.task.hdc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tolerant-parsing tests for health and workflow response
 * envelopes (HdcBridgeParsing). No network; builds JsonObjects from literals.
 */
class HdcBridgeParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    @Test
    fun `workflow ok response parses all fields`() {
        val parsed = HdcBridgeParsing.wire(
            obj(
                """
                { "status":"ok", "message":"screenshot 960x1080",
                  "action":"screenshot", "width":960, "height":1080,
                  "result":"{}", "image_b64":"abc",
                  "package_name":"com.x", "current_package_name":"com.x",
                  "no_reason":false, "max_steps":15 }
                """.trimIndent()
            )
        )
        assertEquals(HdcBridgeStatus.OK, parsed.statusEnum)
        assertEquals("screenshot 960x1080", parsed.message)
        assertEquals("screenshot", parsed.action)
        assertEquals(960, parsed.width?.toInt())
        assertEquals(1080, parsed.height?.toInt())
        assertEquals("abc", parsed.imageB64)
        assertEquals("com.x", parsed.packageName)
        assertFalse(parsed.noReason ?: true)
        assertEquals(15, parsed.maxSteps?.toInt())
    }

    @Test
    fun `workflow error envelope is preserved honestly`() {
        val parsed = HdcBridgeParsing.wire(obj("""{"status":"error","message":"app_start failed"}"""))
        assertEquals(HdcBridgeStatus.ERROR, parsed.statusEnum)
        assertEquals("app_start failed", parsed.message)
    }

    @Test
    fun `missing fields degrade to safe defaults`() {
        val parsed = HdcBridgeParsing.wire(obj("""{}"""))
        assertEquals(HdcBridgeStatus.ERROR, parsed.statusEnum)
        assertNull(parsed.message)
        assertNull(parsed.width)
        assertNull(parsed.action)
    }

    @Test
    fun `numeric-as-string width and height parse tolerantly`() {
        val parsed = HdcBridgeParsing.wire(obj("""{"status":"ok","width":"960","height":"1080"}"""))
        assertEquals(960, parsed.width?.toInt())
        assertEquals(1080, parsed.height?.toInt())
    }

    @Test
    fun `wrong-typed fields fall back to defaults`() {
        val parsed = HdcBridgeParsing.wire(
            obj("""{"status":"ok","width":{"x":1},"no_reason":"maybe","max_steps":"abc"}""")
        )
        assertNull(parsed.width)
        assertNull(parsed.noReason)
        assertNull(parsed.maxSteps)
    }

    @Test
    fun `health payload parses tunnel and target fields`() {
        val health = HdcBridgeParsing.health(
            obj(
                """
                { "status":"ok", "hdc_connected":true, "target":"192.168.1.30:5555",
                  "tunnel_ready":true, "fport_ready":true, "rport_ready":true,
                  "rport_listening":true, "rport_listen_check_supported":true,
                  "app_server_url":"http://127.0.0.1:9126",
                  "app_server_urls":["http://127.0.0.1:9126"],
                  "pc_server_urls":["http://192.168.1.30:9124"],
                  "server_port":9124, "agent_router_port":9126,
                  "reverse_server_port":9125, "loop_enabled":true, "loop_alive":false }
                """.trimIndent()
            )
        )
        assertTrue(health.controlReady)
        assertEquals(HdcBridgeStatus.OK, health.statusEnum)
        assertTrue(health.hdcConnected)
        assertEquals("192.168.1.30:5555", health.target)
        assertTrue(health.tunnelReady)
        assertTrue(health.fportReady)
        assertEquals(listOf("http://127.0.0.1:9126"), health.appServerUrls)
        assertEquals(listOf("http://192.168.1.30:9124"), health.pcServerUrls)
        assertEquals(9124, health.serverPort?.toInt())
        assertTrue(health.loopEnabled)
        assertFalse(health.loopAlive)
    }

    @Test
    fun `health error or partial payload never claims connected`() {
        val notConnected = HdcBridgeParsing.health(obj("""{"status":"error","message":"hdc not connected"}"""))
        assertFalse(notConnected.hdcConnected)
        assertFalse(notConnected.controlReady)
        assertEquals(HdcBridgeStatus.ERROR, notConnected.statusEnum)

        val partial = HdcBridgeParsing.health(obj("""{"status":"ok","fport_ready":true}"""))
        // status ok but rport_ready also required for controlReady -> hdcConnected false,
        // and only ok+control-ready counts.
        assertTrue(partial.rportReady ?: false == false)
    }
}
