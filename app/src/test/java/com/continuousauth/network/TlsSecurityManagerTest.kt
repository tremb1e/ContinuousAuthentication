package com.continuousauth.network

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class TlsSecurityManagerTest {
    private val tls = TlsSecurityManager()
    private val spkiBytes = "test-public-key-only".toByteArray()
    private val pin = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(spkiBytes))
    private val certificate = mockk<X509Certificate> {
        every { publicKey.encoded } returns spkiBytes
    }
    private val systemTrust = mockk<X509TrustManager>(relaxed = true)

    @Test fun validPinWithAndWithoutPrefixMatchesAfterSystemVerification() {
        listOf(pin, "sha256/$pin").forEach { configuredPin ->
            assertTrue(tls.validateCertificatePin(configuredPin))
            tls.createPinningTrustManager(setOf(configuredPin), "test.example", systemTrust)
                .checkServerTrusted(arrayOf(certificate), "RSA")
        }
        verify(exactly = 2) { systemTrust.checkServerTrusted(any(), "RSA") }
    }

    @Test(expected = SSLPeerUnverifiedException::class)
    fun differentPublicKeyIsRejectedEvenWithTrustedCertificateChain() {
        tls.createPinningTrustManager(setOf(Base64.getEncoder().encodeToString(ByteArray(32))), "test.example", systemTrust)
            .checkServerTrusted(arrayOf(certificate), "RSA")
    }

    @Test(expected = CertificateException::class)
    fun matchingPinDoesNotBypassSystemCertificateTrust() {
        every { systemTrust.checkServerTrusted(any(), any()) } throws CertificateException("Untrusted test CA")
        tls.createPinningTrustManager(setOf(pin), "test.example", systemTrust)
            .checkServerTrusted(arrayOf(certificate), "RSA")
    }

    @Test fun malformedHashesAreRejected() {
        listOf("", "=".repeat(44), "A".repeat(43) + "!", Base64.getEncoder().encodeToString(ByteArray(33)))
            .forEach { assertFalse(it, tls.validateCertificatePin(it)) }
    }
}
