package com.continuousauth.network

import android.util.Log
import io.grpc.okhttp.OkHttpChannelBuilder
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import java.net.InetAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TLS安全配置管理器
 * 启用TLS 1.3/1.2，确保网络传输具备安全协议版本并兼容生产反代入口
 * 支持SPKI (Public Key) Pinning证书固定
 */
@Singleton
class TlsSecurityManager @Inject constructor() {
    
    companion object {
        private const val TAG = "TlsSecurityManager"
    }
    
    /**
     * 配置OkHttp通道构建器以使用TLS 1.3/1.2并启用证书固定
     */
    fun configureTlsForChannelBuilder(
        builder: OkHttpChannelBuilder,
        pinnedCertificates: Set<String> = emptySet(),
        hostname: String = ""
    ): OkHttpChannelBuilder {
        val trustManager = if (pinnedCertificates.isEmpty()) null else {
            require(hostname.isNotBlank()) { "启用证书固定时必须提供服务器地址" }
            require(pinnedCertificates.all(::validateCertificatePin)) { "无效的服务器证书指纹" }
            createPinningTrustManager(pinnedCertificates, hostname)
        }
        // Pass the pinning manager into SSLContext. Constructing it without installing it
        // silently disabled every configured pin in the previous implementation.
        return builder.sslSocketFactory(createTlsSocketFactory(trustManager))
    }

    /**
     * 验证证书Pin格式
     */
    fun validateCertificatePin(pin: String): Boolean {
        val cleanPin = pin.removePrefix("sha256/")
        return runCatching {
            cleanPin.length == 44 && java.util.Base64.getDecoder().decode(cleanPin).size == 32
        }.getOrDefault(false)
    }

    /**
     * 从X509证书中提取SPKI Pin
     */
    fun extractSpkiPin(certificate: X509Certificate): String? {
        return try {
            val publicKeyInfo = certificate.publicKey.encoded
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(publicKeyInfo)
            val pin = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
            Log.d(TAG, "提取证书SPKI Pin: sha256/$pin")
            "sha256/$pin"
        } catch (e: Exception) {
            Log.e(TAG, "提取证书Pin失败", e)
            null
        }
    }
    
    /**
     * 创建自定义TrustManager以支持证书固定验证
     */
    internal fun createPinningTrustManager(
        pinnedCertificates: Set<String>,
        hostname: String,
        defaultTrustManager: X509TrustManager = createDefaultTrustManager()
    ): X509TrustManager {
        val normalizedPins = pinnedCertificates.map { "sha256/${it.removePrefix("sha256/")}" }.toSet()
        return object : X509TrustManager {
            
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                defaultTrustManager.checkClientTrusted(chain, authType)
            }
            
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // 首先进行标准证书链验证
                defaultTrustManager.checkServerTrusted(chain, authType)
                
                // 然后验证证书固定
                if (chain != null && pinnedCertificates.isNotEmpty()) {
                    validateCertificatePinning(chain, normalizedPins, hostname)
                }
            }
            
            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return defaultTrustManager.acceptedIssuers
            }
        }
    }
    
    /**
     * 验证证书固定
     */
    private fun validateCertificatePinning(
        chain: Array<out X509Certificate>,
        pinnedCertificates: Set<String>,
        hostname: String
    ) {
        var pinMatched = false
        
        // 检查证书链中的每个证书
        for (cert in chain) {
            val extractedPin = extractSpkiPin(cert)
            if (extractedPin != null && pinnedCertificates.contains(extractedPin)) {
                pinMatched = true
                Log.d(TAG, "证书Pin匹配成功: $hostname")
                break
            }
        }
        
        if (!pinMatched) {
            val errorMessage = "证书Pin验证失败 - 主机: $hostname"
            Log.e(TAG, errorMessage)
            throw javax.net.ssl.SSLPeerUnverifiedException(errorMessage)
        }
    }
    
    /**
     * 创建默认的TrustManager
     */
    private fun createDefaultTrustManager(): X509TrustManager {
        val trustManagerFactory = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        trustManagerFactory.init(null as java.security.KeyStore?)
        
        return trustManagerFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw IllegalStateException("无法获取默认TrustManager")
    }
    
    /**
     * 创建TLS 1.3/1.2的SSLSocketFactory
     */
    private fun createTlsSocketFactory(trustManager: X509TrustManager? = null): SSLSocketFactory {
        val sslContext = createSecureSSLContext(trustManager)
        
        return object : SSLSocketFactory() {
            private val delegate = sslContext.socketFactory
            
            override fun createSocket(): Socket = 
                configureSocket(delegate.createSocket())
            
            override fun createSocket(host: String?, port: Int): Socket = 
                configureSocket(delegate.createSocket(host, port))
            
            override fun createSocket(
                host: String?, 
                port: Int, 
                localHost: InetAddress?, 
                localPort: Int
            ): Socket = 
                configureSocket(delegate.createSocket(host, port, localHost, localPort))
            
            override fun createSocket(host: InetAddress?, port: Int): Socket = 
                configureSocket(delegate.createSocket(host, port))
            
            override fun createSocket(
                address: InetAddress?, 
                port: Int, 
                localAddress: InetAddress?, 
                localPort: Int
            ): Socket = 
                configureSocket(delegate.createSocket(address, port, localAddress, localPort))
            
            override fun createSocket(
                s: Socket?, 
                host: String?, 
                port: Int, 
                autoClose: Boolean
            ): Socket = 
                configureSocket(delegate.createSocket(s, host, port, autoClose))
            
            override fun getDefaultCipherSuites(): Array<String> = 
                delegate.defaultCipherSuites
            
            override fun getSupportedCipherSuites(): Array<String> = 
                delegate.supportedCipherSuites
        }
    }
    
    /**
     * 创建安全的SSLContext
     */
    private fun createSecureSSLContext(trustManager: X509TrustManager? = null): SSLContext {
        val context = SSLContext.getInstance("TLS")
        context.init(null, trustManager?.let { arrayOf(it) }, null)
        Log.i(TAG, "成功创建TLS SSLContext")
        return context
    }
    
    /**
     * 配置SSL Socket以使用TLS 1.3/1.2和安全密码套件
     */
    private fun configureSocket(socket: Socket): Socket {
        if (socket is SSLSocket) {
            configureTlsProtocols(socket)
            configureCipherSuites(socket)
        }
        return socket
    }
    
    /**
     * 配置TLS协议版本
     */
    private fun configureTlsProtocols(sslSocket: SSLSocket) {
        try {
            val supportedProtocols = sslSocket.supportedProtocols
            val enabledProtocols = supportedProtocols
                .filter { it == "TLSv1.3" || it == "TLSv1.2" }

            if (enabledProtocols.isNotEmpty()) {
                sslSocket.enabledProtocols = enabledProtocols.toTypedArray()
                Log.d(TAG, "启用TLS协议: ${enabledProtocols.joinToString()}")
            } else {
                Log.e(TAG, "不支持安全的TLS版本")
            }
        } catch (e: Exception) {
            Log.e(TAG, "配置TLS协议失败", e)
        }
    }
    
    /**
     * 配置安全的密码套件
     */
    private fun configureCipherSuites(sslSocket: SSLSocket) {
        try {
            val supportedCiphers = sslSocket.supportedCipherSuites
            
            // TLS 1.3 推荐的密码套件
            val tls13Ciphers = supportedCiphers.filter { cipher ->
                cipher.startsWith("TLS_AES_") ||
                cipher.startsWith("TLS_CHACHA20_") 
            }
            
            // TLS 1.2 安全的密码套件（作为备选）
            val tls12SecureCiphers = supportedCiphers.filter { cipher ->
                (cipher.contains("ECDHE", ignoreCase = true) && 
                 cipher.contains("AES", ignoreCase = true) && 
                 cipher.contains("GCM", ignoreCase = true)) ||
                (cipher.contains("DHE", ignoreCase = true) && 
                 cipher.contains("AES", ignoreCase = true))
            }
            
            val preferredCiphers = (tls13Ciphers + tls12SecureCiphers).distinct()

            val enabledCiphers = if (preferredCiphers.isNotEmpty()) {
                    Log.d(TAG, "使用TLS 1.3/1.2安全密码套件")
                    preferredCiphers
                } else {
                    Log.w(TAG, "无安全密码套件可用，使用默认设置")
                    supportedCiphers.toList()
                }
            
            sslSocket.enabledCipherSuites = enabledCiphers.toTypedArray()
            Log.d(TAG, "已设置${enabledCiphers.size}个安全密码套件")
            
        } catch (e: Exception) {
            Log.e(TAG, "配置密码套件失败", e)
        }
    }
    
    /**
     * 获取TLS配置信息
     */
    fun getTlsConfigInfo(): TlsConfigInfo {
        return try {
            val context = createSecureSSLContext()
            val socketFactory = context.socketFactory
            val socket = socketFactory.createSocket() as? SSLSocket
            
            socket?.use { sslSocket ->
                val supportedProtocols = sslSocket.supportedProtocols?.toList() ?: emptyList()
                val supportedCiphers = sslSocket.supportedCipherSuites?.toList() ?: emptyList()
                
                TlsConfigInfo(
                    supportsTls13 = supportedProtocols.contains("TLSv1.3"),
                    supportedProtocols = supportedProtocols,
                    recommendedCiphers = supportedCiphers.filter { cipher ->
                        cipher.startsWith("TLS_AES_") || 
                        cipher.startsWith("TLS_CHACHA20_") ||
                        (cipher.contains("ECDHE") && cipher.contains("GCM"))
                    }
                )
            } ?: TlsConfigInfo()
            
        } catch (e: Exception) {
            Log.e(TAG, "获取TLS配置信息失败", e)
            TlsConfigInfo()
        }
    }
}

/**
 * TLS配置信息
 */
data class TlsConfigInfo(
    val supportsTls13: Boolean = false,
    val supportedProtocols: List<String> = emptyList(),
    val recommendedCiphers: List<String> = emptyList()
)
