package com.continuousauth.network

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 轻量级的 gRPC/TLS 能力探测器。
 * 在 gRPC 端口上直接做 TLS 1.3/1.2 握手探测，成功则优先走 TLS，
 * 握手不可用时指导客户端降级到 h2c（HTTP/2 over cleartext）。
 */
@Singleton
class GrpcTlsInspector @Inject constructor() {

    companion object {
        private const val TAG = "GrpcTlsInspector"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5分钟缓存探测结果
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val SOCKET_TIMEOUT_MS = 3_000

        // 与服务端保持一致的 TLS 1.2/1.3 密码套件优先级
        private val TLS12_13_CIPHERS = listOf(
            "TLS_AES_256_GCM_SHA384",
            "TLS_AES_128_GCM_SHA256",
            "TLS_CHACHA20_POLY1305_SHA256",
            "ECDHE-ECDSA-AES256-GCM-SHA384",
            "ECDHE-RSA-AES256-GCM-SHA384",
            "ECDHE-ECDSA-AES128-GCM-SHA256",
            "ECDHE-RSA-AES128-GCM-SHA256"
        )
    }

    private data class CachedProbe(
        val timestamp: Long,
        val result: TlsProbeResult
    )

    private val cache = ConcurrentHashMap<String, CachedProbe>()

    suspend fun check(host: String, port: Int): TlsProbeResult = withContext(Dispatchers.IO) {
        val cacheKey = "$host:$port"
        val now = System.currentTimeMillis()

        cache[cacheKey]?.let { cached ->
            if (now - cached.timestamp < CACHE_TTL_MS) {
                return@withContext cached.result
            }
        }

        val result = probe(host, port)
        cache[cacheKey] = CachedProbe(now, result)
        result
    }

    private fun probe(host: String, port: Int): TlsProbeResult {
        val (sslSocketFactory, _) = buildSystemTrustedSsl()
        var sslSocket: SSLSocket? = null
        val rawSocket = Socket()

        return try {
            rawSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sslSocket = sslSocketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
            configureSocket(sslSocket)
            sslSocket.soTimeout = SOCKET_TIMEOUT_MS
            sslSocket.startHandshake()

            val tlsVersion = sslSocket.session?.protocol
            val negotiated = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) sslSocket.applicationProtocol else null
            }.getOrNull()
            val ok = tlsVersion == "TLSv1.3" || tlsVersion == "TLSv1.2"

            Log.i(
                TAG,
                "TLS probe result: ok=$ok, tls=$tlsVersion, alpn=${negotiated ?: "unknown"}, cipher=${sslSocket.session?.cipherSuite}"
            )

            TlsProbeResult(
                success = ok,
                tlsVersion = tlsVersion,
                negotiatedProtocol = negotiated ?: "h2",
                errorMessage = null
            )
        } catch (e: Exception) {
            val cause = if (e is SSLHandshakeException) "handshake_failed" else "connect_failed"
            val message = "TLS probe failed for $host:$port ($cause) - ${e.message}"
            if (cause == "connect_failed") {
                Log.i(TAG, message)
            } else {
                Log.w(TAG, message)
            }
            TlsProbeResult(
                success = false,
                tlsVersion = null,
                negotiatedProtocol = null,
                errorMessage = e.message
            )
        } finally {
            try {
                sslSocket?.close()
            } catch (_: Exception) {
            }
            try {
                rawSocket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun configureSocket(socket: SSLSocket) {
        val supportedProtocols = socket.supportedProtocols
        val protocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
        if (protocols.isNotEmpty()) {
            socket.enabledProtocols = protocols.toTypedArray()
        }

        val supportedCipherSet = socket.supportedCipherSuites.toSet()
        val preferredCiphers = TLS12_13_CIPHERS.filter { supportedCipherSet.contains(it) }
        if (preferredCiphers.isNotEmpty()) {
            socket.enabledCipherSuites = preferredCiphers.toTypedArray()
        }
    }

    /**
     * 使用系统信任锚的SSL配置，确保探测结果与真实gRPC握手一致。
     * 当服务端未部署可信TLS证书时，探测会失败，从而触发客户端降级到h2c。
     */
    private fun buildSystemTrustedSsl(): Pair<SSLSocketFactory, X509TrustManager> {
        val trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        trustManagerFactory.init(null as KeyStore?)
        val trustManager = trustManagerFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

        val sslContext = try {
            SSLContext.getInstance("TLSv1.3")
        } catch (_: Exception) {
            SSLContext.getInstance("TLSv1.2")
        }
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        return sslContext.socketFactory to trustManager
    }
}

data class TlsProbeResult(
    val success: Boolean,
    val tlsVersion: String?,
    val negotiatedProtocol: String?,
    val errorMessage: String?
) {
    val supportsTls12OrHigher: Boolean
        get() = success && (tlsVersion == "TLSv1.3" || tlsVersion == "TLSv1.2")
}
