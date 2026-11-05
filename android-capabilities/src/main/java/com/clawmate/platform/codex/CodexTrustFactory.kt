package com.clawmate.platform.codex

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust material for one Codex connection.
 *
 * A pairing file may carry the CA that signed the PC's certificate. That CA is trusted
 * **only** for the connection it was paired with; every other connection keeps the platform
 * trust store. There is deliberately no "trust everything" path: an unparsable certificate
 * fails the connection instead of silently downgrading verification.
 */
class CodexTrustFactory {

    private val systemManagers: List<X509TrustManager> by lazy { managersFrom(null) }

    /**
     * Builds the client managers for [caCertificate].
     *
     * @return the platform managers when [caCertificate] is empty, otherwise exactly the
     *   pinned managers for that certificate.
     * @throws CodexValidationException when the PEM cannot be parsed as an X.509 certificate.
     */
    fun trustManagers(caCertificate: String): List<X509TrustManager> {
        if (caCertificate.isEmpty()) return systemManagers
        return managersFrom(parse(caCertificate))
    }

    /** A trust manager covering exactly the pinned certificate. */
    fun pinnedTrustManager(caCertificate: String): X509TrustManager =
        trustManagers(caCertificate).single()

    /** A socket factory bound to exactly the pinned certificate. */
    fun pinnedSocketFactory(caCertificate: String, serverNames: String?): SSLSocketFactory =
        context(trustManagers(caCertificate)).socketFactory

    private fun context(managers: List<X509TrustManager>): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, managers.toTypedArray(), null) }

    private fun parse(pem: String): X509Certificate {
        if (!CodexTypes.isValidCa(pem)) throw CodexValidationException("配对证书格式无效")
        return try {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII))) as X509Certificate
        } catch (e: Exception) {
            throw CodexValidationException("配对证书无法解析，请重新生成配对信息")
        }
    }

    private fun managersFrom(certificate: X509Certificate?): List<X509TrustManager> {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        if (certificate != null) keyStore.setCertificateEntry("codex-ca", certificate)
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().ifEmpty {
            throw CodexValidationException("设备缺少可用的 TLS 信任库")
        }
    }
}
