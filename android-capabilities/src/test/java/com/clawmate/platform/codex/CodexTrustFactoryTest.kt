package com.clawmate.platform.codex

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trust-store policy for a paired Codex connection.
 *
 * The property that matters: a certificate supplied by a pairing file is trusted **only**
 * for that connection, and an unusable certificate fails closed. No code path accepts an
 * arbitrary certificate.
 *
 * Fixtures are generated locally with okhttp-tls (a test-only dependency); nothing is
 * fetched and nothing is asserted about public certificate authorities.
 */
class CodexTrustFactoryTest {

    private val factory = CodexTrustFactory()

    private data class Fixture(val certificate: X509Certificate, val pem: String)

    /** A self-signed certificate that can act as its own authority. */
    private fun fixture(name: String): Fixture {
        val held = HeldCertificate.Builder()
            .commonName(name)
            .certificateAuthority(0)
            .addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("localhost")
            .build()
        return Fixture(held.certificate, held.certificatePem())
    }

    @Test
    fun `an empty certificate uses the platform trust store`() {
        assertTrue("the platform store must be available", factory.trustManagers("").isNotEmpty())
    }

    @Test
    fun `a supplied certificate yields a usable trust manager`() {
        val pem = fixture("codex-test").pem
        assertTrue(CodexTypes.isValidCa(pem))
        assertTrue(factory.pinnedTrustManager(pem) is X509TrustManager)
        assertTrue(factory.pinnedSocketFactory(pem, null).supportedCipherSuites.isNotEmpty())
    }

    @Test
    fun `a pinned manager accepts the certificate it was pinned to`() {
        val self = fixture("codex-authority")
        factory.pinnedTrustManager(self.pem).checkServerTrusted(arrayOf(self.certificate), "RSA")
    }

    @Test
    fun `a pinned manager rejects a certificate it did not sign`() {
        val pinned = fixture("codex-authority")
        val foreign = fixture("someone-else")
        assertThrows(CertificateException::class.java) {
            factory.pinnedTrustManager(pinned.pem).checkServerTrusted(arrayOf(foreign.certificate), "RSA")
        }
    }

    @Test
    fun `two connections do not share trust`() {
        val first = fixture("first")
        val second = fixture("second")
        val firstManager = factory.pinnedTrustManager(first.pem)
        assertNotSame(firstManager, factory.pinnedTrustManager(second.pem))

        // Each connection's manager rejects the other connection's authority.
        assertThrows(CertificateException::class.java) {
            firstManager.checkServerTrusted(arrayOf(second.certificate), "RSA")
        }
        assertThrows(CertificateException::class.java) {
            factory.pinnedTrustManager(second.pem).checkServerTrusted(arrayOf(first.certificate), "RSA")
        }
    }

    @Test
    fun `a leaf signed by the pinned authority is accepted`() {
        val authority = HeldCertificate.Builder()
            .commonName("clawmate-codex-ca")
            .certificateAuthority(1)
            .build()
        val leaf = HeldCertificate.Builder()
            .commonName("codex.example.com")
            .addSubjectAlternativeName("codex.example.com")
            .signedBy(authority)
            .build()

        factory.pinnedTrustManager(authority.certificatePem())
            .checkServerTrusted(arrayOf(leaf.certificate, authority.certificate), "RSA")
    }

    @Test
    fun `a malformed certificate fails closed`() {
        assertThrows(CodexValidationException::class.java) { factory.trustManagers("system") }
        assertThrows(CodexValidationException::class.java) {
            factory.trustManagers("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----")
        }
    }

    @Test
    fun `an oversized certificate is rejected before parsing`() {
        val oversized = "-----BEGIN CERTIFICATE-----\n" + "A".repeat(3000) + "\n-----END CERTIFICATE-----"
        assertThrows(CodexValidationException::class.java) { factory.trustManagers(oversized) }
    }

    @Test
    fun `the default client never redirects or retries`() {
        val client = CodexTransport.defaultClient("")
        assertTrue(!client.followRedirects)
        assertTrue(!client.followSslRedirects)
        assertTrue(!client.retryOnConnectionFailure)
        assertEquals(CodexTransport.CONNECT_TIMEOUT_MS.toInt(), client.connectTimeoutMillis)
    }
}
