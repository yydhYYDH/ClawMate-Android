package com.clawmate.app.task.hdc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for [HdcUrlNormalizer]. No network, no Android.
 */
class HdcUrlNormalizerTest {

    @Test
    fun `health and workflow urls share a trimmed base`() {
        assertEquals(
            "http://192.168.1.30:9124/api/health",
            HdcUrlNormalizer.healthUrl(" http://192.168.1.30:9124/ "),
        )
        assertEquals(
            "http://192.168.1.30:9124/api/workflow",
            HdcUrlNormalizer.workflowUrl("http://192.168.1.30:9124/"),
        )
    }

    @Test
    fun `trailing slashes are stripped once and not duplicated`() {
        assertEquals("http://h:9124/api/health", HdcUrlNormalizer.healthUrl("http://h:9124///"))
        assertEquals("http://h:9124/api/workflow", HdcUrlNormalizer.workflowUrl("http://h:9124//"))
    }

    @Test
    fun `scheme-relative double slash is collapsed to http`() {
        assertEquals("http://192.168.1.30:9124/api/workflow", HdcUrlNormalizer.workflowUrl("//192.168.1.30:9124"))
    }

    @Test
    fun `scheme-less input defaults to http`() {
        assertEquals("http://192.168.1.30:9124", HdcUrlNormalizer.normalizeBaseUrl("192.168.1.30:9124"))
    }

    @Test
    fun `non http or https scheme is rejected`() {
        assertEquals("", HdcUrlNormalizer.healthUrl("ftp://host:9124"))
        assertEquals("", HdcUrlNormalizer.workflowUrl("tcp://host"))
        assertEquals("", HdcUrlNormalizer.normalizeBaseUrl("file:///x"))
    }

    @Test
    fun `empty or whitespace base is rejected`() {
        assertEquals("", HdcUrlNormalizer.healthUrl(""))
        assertEquals("", HdcUrlNormalizer.workflowUrl("   "))
        assertEquals("", HdcUrlNormalizer.normalizeBaseUrl(""))
    }

    @Test
    fun `path prefix is preserved on the base`() {
        assertEquals("http://host:9124/hdc/api/workflow", HdcUrlNormalizer.workflowUrl("http://host:9124/hdc"))
    }

    @Test
    fun `empty trailing port is dropped`() {
        assertEquals("http://host/api/health", HdcUrlNormalizer.healthUrl("http://host:"))
    }

    @Test
    fun `https scheme is preserved`() {
        assertEquals("https://host:9124/api/health", HdcUrlNormalizer.healthUrl("https://host:9124"))
    }

    @Test
    fun `normalizeBaseUrl removes trailing slash but keeps port and host`() {
        assertEquals("http://192.168.1.30:9124", HdcUrlNormalizer.normalizeBaseUrl("http://192.168.1.30:9124/"))
    }

    @Test
    fun `whitespace around base is trimmed`() {
        assertTrue(HdcUrlNormalizer.normalizeBaseUrl("  http://h:1  ").endsWith("h:1"))
    }
}
