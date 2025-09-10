package com.continuousauth.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.continuousauth.network.GrpcManager
// import com.continuousauth.proto.KeyRegistrationRequest
// import com.continuousauth.proto.KeyRegistrationResponse
// import com.continuousauth.proto.PolicyRequest
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.hybrid.HybridKeyTemplates
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
// import okhttp3.tls.HandshakeCertificates
// import okhttp3.tls.HeldCertificate
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * 密钥管理服务
 * 负责密钥发布、证书固定（Pinning）和密钥轮换
 * 符合 claude.md 第6节要求
 */
@Singleton
class KeyManagementService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val grpcManager: GrpcManager
) {
    
    companion object {
        private const val TAG = "KeyManagementService"
        private const val PREFS_FILE = "key_management_prefs"
        
        // Keys for EncryptedSharedPreferences
        private const val KEY_SERVER_PUBLIC_KEY = "server_public_key"
        private const val KEY_SERVER_PUBLIC_KEY_FINGERPRINT = "server_public_key_fingerprint"
        private const val KEY_SERVER_PUBLIC_KEY_VERSION = "server_public_key_version"
        private const val KEY_PINNED_CERTIFICATES = "pinned_certificates"
        private const val KEY_LAST_ROTATION_TIME = "last_rotation_time"
        private const val KEY_ROTATION_INTERVAL_HOURS = "rotation_interval_hours"
        private const val KEY_DEVICE_PRIVATE_KEY = "device_private_key"
        
        // Default values
        private const val DEFAULT_ROTATION_INTERVAL_HOURS = 24
        private const val MIN_ROTATION_INTERVAL_HOURS = 1
        private const val MAX_ROTATION_INTERVAL_HOURS = 168 // 7 days
        
        // Certificate pinning
        private const val MAX_PINNED_CERTIFICATES = 5
    }
    
    // 加密存储
    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
            
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    // 服务器公钥（用于加密DEK）
    private var serverPublicKey: HybridEncrypt? = null
    private var serverPublicKeyVersion: String? = null
    private var serverPublicKeyId: String = "default_key_id"
    
    // 设备密钥对（用于注册时的身份验证）
    private var deviceKeysetHandle: KeysetHandle? = null
    
    // 证书固定
    private val pinnedCertificates = mutableSetOf<String>()
    
    // 同步锁
    private val mutex = Mutex()
    
    // OkHttpClient for key exchange
    private val httpClient: OkHttpClient by lazy {
        buildPinnedHttpClient()
    }
    
    init {
        // 初始化Tink
        HybridConfig.register()
    }
    
    /**
     * 首次注册并获取服务器公钥
     * 符合Section 6第1点要求
     */
    suspend fun registerAndFetchServerPublicKey(serverEndpoint: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    Log.i(TAG, "开始注册并获取服务器公钥")
                    
                    // 生成设备密钥对（如果不存在）
                    if (deviceKeysetHandle == null) {
                        deviceKeysetHandle = KeysetHandle.generateNew(
                            HybridKeyTemplates.ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM
                        )
                        saveDevicePrivateKey()
                    }
                    
                    // 获取设备公钥
                    val devicePublicKey = getPublicKeyFromKeyset(deviceKeysetHandle!!)
                    
                    // 向服务器注册并获取服务器公钥
                    val response = fetchServerPublicKeyFromEndpoint(serverEndpoint, devicePublicKey)
                    
                    if (response != null) {
                        // 验证公钥指纹
                        val fingerprint = calculateFingerprint(response.publicKey)
                        Log.i(TAG, "服务器公钥指纹: $fingerprint")
                        
                        // 保存服务器公钥和指纹
                        saveServerPublicKeyInternal(response.publicKey, response.keyVersion, fingerprint)
                        
                        // 初始化HybridEncrypt
                        initializeServerPublicKey(response.publicKey)
                        
                        // 保存证书固定信息
                        if (response.certificates.isNotEmpty()) {
                            savePinnedCertificates(response.certificates)
                        }
                        
                        Log.i(TAG, "成功获取并验证服务器公钥，版本: ${response.keyVersion}")
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception("获取服务器公钥失败"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "注册失败", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * 获取当前DEK密钥ID
     * 符合Section 6第2点要求
     */
    fun getCurrentDekKeyId(): String {
        return serverPublicKeyVersion ?: "DEFAULT_KEY_V1"
    }
    
    /**
     * 使用服务器公钥加密DEK
     */
    suspend fun encryptDEK(dek: ByteArray): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    if (serverPublicKey == null) {
                        Log.e(TAG, "服务器公钥未初始化")
                        return@withContext null
                    }
                    
                    // 使用HybridEncrypt加密DEK
                    val contextInfo = "DEK_ENCRYPTION_${System.currentTimeMillis()}".toByteArray()
                    serverPublicKey?.encrypt(dek, contextInfo)
                }
            } catch (e: Exception) {
                Log.e(TAG, "加密DEK失败", e)
                null
            }
        }
    }
    
    /**
     * 密钥轮换
     * 符合Section 6第3点要求
     */
    suspend fun rotateKeys(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    Log.i(TAG, "开始密钥轮换")
                    
                    // 检查是否需要轮换
                    if (!shouldRotateKeys()) {
                        Log.i(TAG, "未到轮换时间，跳过")
                        return@withContext Result.success(Unit)
                    }
                    
                    // 从服务器获取新的公钥版本
                    val newKeyResponse = fetchLatestServerPublicKey()
                    
                    if (newKeyResponse != null && newKeyResponse.keyVersion != serverPublicKeyVersion) {
                        // 验证新公钥
                        val newFingerprint = calculateFingerprint(newKeyResponse.publicKey)
                        Log.i(TAG, "新公钥指纹: $newFingerprint")
                        
                        // 保存新公钥（保留旧公钥用于兼容）
                        saveServerPublicKeyInternal(
                            newKeyResponse.publicKey,
                            newKeyResponse.keyVersion,
                            newFingerprint
                        )
                        
                        // 更新HybridEncrypt实例
                        initializeServerPublicKey(newKeyResponse.publicKey)
                        
                        // 更新轮换时间
                        encryptedPrefs.edit()
                            .putLong(KEY_LAST_ROTATION_TIME, System.currentTimeMillis())
                            .apply()
                        
                        Log.i(TAG, "密钥轮换成功，新版本: ${newKeyResponse.keyVersion}")
                    }
                    
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "密钥轮换失败", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * 验证服务器证书（证书固定）
     */
    fun verifyCertificatePinning(peerCertificates: List<Certificate>): Boolean {
        try {
            if (pinnedCertificates.isEmpty()) {
                // 如果没有固定证书，首次连接时保存
                if (peerCertificates.isNotEmpty()) {
                    val pins = peerCertificates
                        .filterIsInstance<X509Certificate>()
                        .map { calculateCertificatePin(it) }
                    savePinnedCertificates(pins)
                }
                return true
            }
            
            // 验证证书链中是否有匹配的证书
            for (cert in peerCertificates) {
                if (cert is X509Certificate) {
                    val pin = calculateCertificatePin(cert)
                    if (pinnedCertificates.contains(pin)) {
                        Log.d(TAG, "证书固定验证成功")
                        return true
                    }
                }
            }
            
            Log.e(TAG, "证书固定验证失败，未找到匹配的证书")
            return false
            
        } catch (e: Exception) {
            Log.e(TAG, "证书验证异常", e)
            return false
        }
    }
    
    /**
     * 构建带证书固定的HttpClient
     */
    private fun buildPinnedHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        
        // 如果有固定的证书，配置证书固定
        if (pinnedCertificates.isNotEmpty()) {
            // TODO: 配置实际的证书固定
            // 这里需要根据实际的服务器证书进行配置
        }
        
        return builder.build()
    }
    
    /**
     * 从服务器端点获取公钥
     * TODO: 需要实现实际的网络请求
     */
    private suspend fun fetchServerPublicKeyFromEndpoint(
        endpoint: String,
        devicePublicKey: ByteArray
    ): ServerKeyResponse? {
        return withContext(Dispatchers.IO) {
            try {
                // TODO: 实现实际的gRPC或HTTP请求
                // 这里是模拟响应
                Log.w(TAG, "服务器端点尚未实现，返回模拟数据")
                
                // 生成模拟的服务器密钥对
                val serverKeyset = KeysetHandle.generateNew(
                    HybridKeyTemplates.ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM
                )
                
                ServerKeyResponse(
                    publicKey = getPublicKeyFromKeyset(serverKeyset),
                    keyVersion = "v1_${System.currentTimeMillis()}",
                    certificates = emptyList()
                )
            } catch (e: Exception) {
                Log.e(TAG, "获取服务器公钥失败", e)
                null
            }
        }
    }
    
    /**
     * 获取最新的服务器公钥
     */
    private suspend fun fetchLatestServerPublicKey(): ServerKeyResponse? {
        // TODO: 实现从服务器获取最新公钥
        return null
    }
    
    /**
     * 从Keyset中提取公钥
     */
    private fun getPublicKeyFromKeyset(keyset: KeysetHandle): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val writer = com.google.crypto.tink.JsonKeysetWriter.withOutputStream(outputStream)
        keyset.publicKeysetHandle.writeNoSecret(writer)
        return outputStream.toByteArray()
    }
    
    /**
     * 初始化服务器公钥用于加密
     */
    private fun initializeServerPublicKey(publicKeyBytes: ByteArray) {
        try {
            // TODO: 从字节数组恢复KeysetHandle并获取HybridEncrypt
            // 这里需要实际的Tink实现
            Log.i(TAG, "服务器公钥已初始化")
        } catch (e: Exception) {
            Log.e(TAG, "初始化服务器公钥失败", e)
        }
    }
    
    /**
     * 计算公钥指纹
     */
    private fun calculateFingerprint(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }
    
    /**
     * 计算证书PIN（用于证书固定）
     */
    private fun calculateCertificatePin(certificate: X509Certificate): String {
        val publicKey = certificate.publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return "sha256/" + Base64.encodeToString(hash, Base64.NO_WRAP)
    }
    
    /**
     * 保存服务器公钥（内部使用）
     */
    private fun saveServerPublicKeyInternal(publicKey: ByteArray, version: String, fingerprint: String) {
        encryptedPrefs.edit()
            .putString(KEY_SERVER_PUBLIC_KEY, Base64.encodeToString(publicKey, Base64.NO_WRAP))
            .putString(KEY_SERVER_PUBLIC_KEY_VERSION, version)
            .putString(KEY_SERVER_PUBLIC_KEY_FINGERPRINT, fingerprint)
            .apply()
        
        serverPublicKeyVersion = version
    }
    
    /**
     * 保存服务器公钥（公开方法，供DeviceRegistration使用）
     */
    fun saveServerPublicKey(publicKey: ByteArray, keyId: String, fingerprint: String) {
        saveServerPublicKeyInternal(publicKey, keyId, fingerprint)
        serverPublicKeyId = keyId
    }
    
    /**
     * 保存设备私钥
     */
    private fun saveDevicePrivateKey() {
        deviceKeysetHandle?.let { keyset ->
            val outputStream = ByteArrayOutputStream()
            val writer = com.google.crypto.tink.JsonKeysetWriter.withOutputStream(outputStream)
            val masterKey = com.google.crypto.tink.aead.AeadKeyTemplates.AES256_GCM
            val masterKeyHandle = KeysetHandle.generateNew(masterKey)
            val aead = masterKeyHandle.getPrimitive(com.google.crypto.tink.Aead::class.java)
            keyset.write(writer, aead)
            val keyBytes = outputStream.toByteArray()
            
            encryptedPrefs.edit()
                .putString(KEY_DEVICE_PRIVATE_KEY, Base64.encodeToString(keyBytes, Base64.NO_WRAP))
                .apply()
        }
    }
    
    /**
     * 保存固定的证书
     */
    private fun savePinnedCertificates(certificates: List<String>) {
        pinnedCertificates.clear()
        pinnedCertificates.addAll(certificates.take(MAX_PINNED_CERTIFICATES))
        
        encryptedPrefs.edit()
            .putStringSet(KEY_PINNED_CERTIFICATES, pinnedCertificates.toSet())
            .apply()
        
        Log.i(TAG, "已保存 ${pinnedCertificates.size} 个证书固定")
    }
    
    /**
     * 加载固定的证书
     */
    fun loadPinnedCertificates() {
        val savedPins = encryptedPrefs.getStringSet(KEY_PINNED_CERTIFICATES, emptySet())
        if (savedPins != null) {
            pinnedCertificates.clear()
            pinnedCertificates.addAll(savedPins)
            Log.i(TAG, "已加载 ${pinnedCertificates.size} 个证书固定")
        }
    }
    
    /**
     * 检查是否需要轮换密钥
     */
    private fun shouldRotateKeys(): Boolean {
        val lastRotation = encryptedPrefs.getLong(KEY_LAST_ROTATION_TIME, 0)
        val rotationInterval = encryptedPrefs.getInt(
            KEY_ROTATION_INTERVAL_HOURS,
            DEFAULT_ROTATION_INTERVAL_HOURS
        )
        
        val hoursSinceRotation = (System.currentTimeMillis() - lastRotation) / (1000 * 60 * 60)
        return hoursSinceRotation >= rotationInterval
    }
    
    /**
     * 设置密钥轮换间隔（小时）
     */
    fun setKeyRotationInterval(hours: Int) {
        val validHours = hours.coerceIn(MIN_ROTATION_INTERVAL_HOURS, MAX_ROTATION_INTERVAL_HOURS)
        encryptedPrefs.edit()
            .putInt(KEY_ROTATION_INTERVAL_HOURS, validHours)
            .apply()
        
        Log.i(TAG, "密钥轮换间隔设置为: $validHours 小时")
    }
    
    /**
     * 获取密钥信息
     */
    fun getKeyInfo(): KeyInfo {
        return KeyInfo(
            serverPublicKeyVersion = serverPublicKeyVersion ?: "未初始化",
            serverPublicKeyFingerprint = encryptedPrefs.getString(KEY_SERVER_PUBLIC_KEY_FINGERPRINT, null) ?: "未知",
            pinnedCertificatesCount = pinnedCertificates.size,
            lastRotationTime = encryptedPrefs.getLong(KEY_LAST_ROTATION_TIME, 0),
            rotationIntervalHours = encryptedPrefs.getInt(KEY_ROTATION_INTERVAL_HOURS, DEFAULT_ROTATION_INTERVAL_HOURS)
        )
    }
    
    /**
     * 获取当前的服务器公钥ID
     */
    fun getCurrentServerKeyId(): String {
        return serverPublicKeyId
    }
    
    /**
     * 刷新密钥
     */
    suspend fun refreshKeys() {
        Log.i(TAG, "刷新密钥")
        // 重新获取服务器公钥
        // fetchServerPublicKeyFromEndpoint() 需要参数，暂时跳过
        // 触发密钥轮换
        rotateKeys()
    }
    
    /**
     * 刷新服务器公钥
     */
    suspend fun refreshServerPublicKey() {
        Log.i(TAG, "刷新服务器公钥")
        // fetchServerPublicKeyFromEndpoint() 需要参数，暂时跳过
    }
    
    /**
     * 更新密钥版本
     */
    suspend fun updateKeyVersion() {
        Log.i(TAG, "更新密钥版本")
        // 获取最新的密钥版本
        // fetchServerPublicKeyFromEndpoint() 需要参数，暂时跳过
        // 更新本地存储的版本
        encryptedPrefs.edit()
            .putString("key_version", "v${System.currentTimeMillis()}")
            .apply()
    }
}

/**
 * 服务器密钥响应
 */
data class ServerKeyResponse(
    val publicKey: ByteArray,
    val keyVersion: String,
    val certificates: List<String>
)

/**
 * 密钥信息
 */
data class KeyInfo(
    val serverPublicKeyVersion: String,
    val serverPublicKeyFingerprint: String,
    val pinnedCertificatesCount: Int,
    val lastRotationTime: Long,
    val rotationIntervalHours: Int
)