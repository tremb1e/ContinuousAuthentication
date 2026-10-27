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
 * 仅用于诊断 TLS 能力；真实连接由认证服务的心跳 RPC 验证。
 */
@Singleton
class GrpcTlsInspector @Inject constructor() {

    companion object {
        private const val TAG = "GrpcTlsInspector"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5分钟缓存探测结果
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val SOCKET_TIMEOUT_MS = 3_000

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
        if (result.success) cache[cacheKey] = CachedProbe(now, result)
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
                negotiatedProtocol = negotiated?.takeIf { it.isNotBlank() },
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

        socket.sslParameters = socket.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) applicationProtocols = arrayOf("h2")
        }
    }

    /**
     * 使用系统信任锚的SSL配置，确保探测结果与真实gRPC握手一致。
     * 不缓存失败结果，也不将失败结果用作明文降级的依据。
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
