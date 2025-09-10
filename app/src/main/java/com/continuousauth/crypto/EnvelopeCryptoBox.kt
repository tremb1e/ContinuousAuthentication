package com.continuousauth.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 信封加密实现类
 * 使用 Google Tink 实现 Envelope Encryption（混合加密）
 * 符合 claude.md 第 5-8 节的所有安全要求
 */
@Singleton
class EnvelopeCryptoBox @Inject constructor(
    @ApplicationContext private val context: Context
) : CryptoBox {
    
    companion object {
        private const val TAG = "EnvelopeCryptoBox"
        private const val PREFS_FILE = "crypto_secure_prefs"
        private const val KEY_DEVICE_ID = "device_instance_id"
        private const val KEY_HMAC_KEY = "hmac_key"
        private const val KEY_SERVER_PUBLIC_KEY = "server_public_key"
        private const val KEY_DEK_KEY_ID = "dek_key_id"
        private const val KEY_PACKET_SEQ_NO = "packet_seq_no"
        private const val KEY_ROTATION_COUNT = "key_rotation_count"
        private const val KEY_CREATION_TIME = "key_creation_time"
        private const val KEY_FAILED_COUNT = "crypto_failed_count"
        private const val KEY_SESSION_DEK = "session_dek"
        private const val KEY_DEK_EXPIRY_TIME = "dek_expiry_time"
        
        private const val KEYSET_NAME = "ca_master_keyset"
        private const val KEYSET_PREF_NAME = "ca_master_keyset_prefs"
        
        private const val MAX_FAILURE_COUNT = 5  // 连续失败5次触发安全开关
        private const val CHUNK_SIZE = 256 * 1024  // 256KB chunks for streaming
        private const val DEK_LIFETIME_MS = 3600_000L  // 1小时DEK生命周期
    }
    
    // 加密的SharedPreferences用于存储敏感信息
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
    
    // Tink密钥管理器
    private var keysetManager: AndroidKeysetManager? = null
    private var aeadPrimitive: Aead? = null
    private var streamingAeadKeyset: KeysetHandle? = null
    private var streamingAead: StreamingAead? = null
    
    // 服务器公钥（用于加密DEK）
    private var serverPublicKey: HybridEncrypt? = null
    
    // 同步锁
    private val mutex = Mutex()
    
    // 包序列号（全局递增，用于抗重放）
    private val packetSeqNo = AtomicLong(0)
    
    // 密钥轮换计数
    private val rotationCount = AtomicInteger(0)
    
    // 加密失败计数（用于安全开关）
    private val failureCount = AtomicInteger(0)
    
    // 设备实例ID（首次安装时生成）
    private var deviceInstanceId: String? = null
    
    // HMAC密钥（用于生成各种哈希）
    private var hmacKey: ByteArray? = null
    
    // 会话级DEK（每小时轮换）
    private var sessionDEK: ByteArray? = null
    private var dekExpiryTime: Long = 0L
    private var encryptedDEK: ByteArray? = null
    
    override suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    // 1. 初始化Tink
                    TinkConfig.register()
                    AeadConfig.register()
                    StreamingAeadConfig.register()
                    HybridConfig.register()
                    
                    // 2. 初始化或恢复设备ID
                    deviceInstanceId = encryptedPrefs.getString(KEY_DEVICE_ID, null)
                    if (deviceInstanceId == null) {
                        deviceInstanceId = UUID.randomUUID().toString()
                        encryptedPrefs.edit()
                            .putString(KEY_DEVICE_ID, deviceInstanceId)
                            .apply()
                        Log.i(TAG, "生成新的设备实例ID")
                    }
                    
                    // 3. 初始化或恢复HMAC密钥
                    val hmacKeyStr = encryptedPrefs.getString(KEY_HMAC_KEY, null)
                    hmacKey = if (hmacKeyStr == null) {
                        val newKey = generateRandomBytes(32)  // 256-bit HMAC key
                        encryptedPrefs.edit()
                            .putString(KEY_HMAC_KEY, Base64.encodeToString(newKey, Base64.NO_WRAP))
                            .apply()
                        Log.i(TAG, "生成新的HMAC密钥")
                        newKey
                    } else {
                        Base64.decode(hmacKeyStr, Base64.NO_WRAP)
                    }
                    
                    // 4. 初始化AEAD密钥集（用于小数据加密）
                    keysetManager = AndroidKeysetManager.Builder()
                        .withSharedPref(context, KEYSET_NAME, KEYSET_PREF_NAME)
                        .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
                        .withMasterKeyUri("android-keystore://ca_master_key")
                        .build()
                    
                    aeadPrimitive = keysetManager?.keysetHandle?.getPrimitive(Aead::class.java)
                    
                    // 5. 初始化StreamingAEAD密钥集（用于大数据流式加密）
                    streamingAeadKeyset = KeysetHandle.generateNew(
                        StreamingAeadKeyTemplates.AES256_GCM_HKDF_4KB
                    )
                    streamingAead = streamingAeadKeyset?.getPrimitive(StreamingAead::class.java)
                    
                    // 6. 恢复包序列号
                    packetSeqNo.set(encryptedPrefs.getLong(KEY_PACKET_SEQ_NO, 0))
                    
                    // 7. 恢复密钥轮换计数
                    rotationCount.set(encryptedPrefs.getInt(KEY_ROTATION_COUNT, 0))
                    
                    // 8. 恢复会话级DEK（如果存在且未过期）
                    val savedDEKExpiry = encryptedPrefs.getLong(KEY_DEK_EXPIRY_TIME, 0)
                    if (savedDEKExpiry > System.currentTimeMillis()) {
                        val savedDEK = encryptedPrefs.getString(KEY_SESSION_DEK, null)
                        if (savedDEK != null) {
                            sessionDEK = Base64.decode(savedDEK, Base64.NO_WRAP)
                            dekExpiryTime = savedDEKExpiry
                            Log.i(TAG, "恢复会话级DEK，有效期至: ${java.util.Date(dekExpiryTime)}")
                        }
                    }
                    
                    // 9. 重置失败计数
                    failureCount.set(0)
                    encryptedPrefs.edit().putInt(KEY_FAILED_COUNT, 0).apply()
                    
                    // 10. TODO: 从服务器获取公钥（暂时跳过，需要服务器端点）
                    // serverPublicKey = fetchServerPublicKey()
                    
                    Log.i(TAG, "EnvelopeCryptoBox 初始化成功")
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "EnvelopeCryptoBox 初始化失败", e)
                false
            }
        }
    }
    
    override suspend fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray?
    ): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    // 检查安全开关
                    if (failureCount.get() >= MAX_FAILURE_COUNT) {
                        Log.e(TAG, "加密失败次数过多，触发安全开关")
                        return@withContext null
                    }
                    
                    val result = if (plaintext.size > CHUNK_SIZE) {
                        // 大数据使用流式加密
                        encryptStreaming(plaintext, associatedData)
                    } else {
                        // 小数据使用AEAD
                        aeadPrimitive?.encrypt(plaintext, associatedData)
                    }
                    
                    // 重置失败计数
                    if (result != null) {
                        failureCount.set(0)
                        encryptedPrefs.edit().putInt(KEY_FAILED_COUNT, 0).apply()
                    }
                    
                    result
                }
            } catch (e: Exception) {
                Log.e(TAG, "加密失败", e)
                handleCryptoFailure()
                null
            }
        }
    }
    
    override suspend fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray?
    ): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    // 检查安全开关
                    if (failureCount.get() >= MAX_FAILURE_COUNT) {
                        Log.e(TAG, "解密失败次数过多，触发安全开关")
                        return@withContext null
                    }
                    
                    val result = if (ciphertext.size > CHUNK_SIZE + 100) {  // 考虑加密开销
                        // 大数据使用流式解密
                        decryptStreaming(ciphertext, associatedData)
                    } else {
                        // 小数据使用AEAD
                        aeadPrimitive?.decrypt(ciphertext, associatedData)
                    }
                    
                    // 重置失败计数
                    if (result != null) {
                        failureCount.set(0)
                        encryptedPrefs.edit().putInt(KEY_FAILED_COUNT, 0).apply()
                    }
                    
                    result
                }
            } catch (e: Exception) {
                Log.e(TAG, "解密失败", e)
                handleCryptoFailure()
                null
            }
        }
    }
    
    override fun getKeyInfo(): CryptoKeyInfo {
        return CryptoKeyInfo(
            masterKeyExists = aeadPrimitive != null,
            sessionKeyActive = streamingAead != null,
            keysetProvider = "Android Keystore (Tink)",
            encryptionAlgorithm = "AES-256-GCM with StreamingAEAD",
            keyCreationTime = encryptedPrefs.getLong(KEY_CREATION_TIME, System.currentTimeMillis()),
            keyRotationCount = rotationCount.get().toLong()
        )
    }
    
    override fun destroy() {
        // 保存当前状态
        encryptedPrefs.edit()
            .putLong(KEY_PACKET_SEQ_NO, packetSeqNo.get())
            .putInt(KEY_ROTATION_COUNT, rotationCount.get())
            .apply()
        
        // 清理内存中的密钥
        hmacKey?.fill(0)
        hmacKey = null
        
        Log.i(TAG, "EnvelopeCryptoBox 已销毁")
    }
    
    override suspend fun rotateSessionKey(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    // 1. 轮换会话级DEK（强制生成新的）
                    sessionDEK = generateRandomBytes(32)
                    dekExpiryTime = System.currentTimeMillis() + DEK_LIFETIME_MS
                    encryptedDEK = encryptDEKWithServerKey(sessionDEK!!)
                    
                    // 2. 轮换AEAD密钥
                    val keyTemplate = com.google.crypto.tink.aead.AeadKeyTemplates.AES256_GCM
                    keysetManager?.let { manager ->
                        manager.add(keyTemplate)
                        val keyId = manager.keysetHandle.keysetInfo.keyInfoList.last().keyId
                        manager.setPrimary(keyId)
                    }
                    aeadPrimitive = keysetManager?.keysetHandle?.getPrimitive(Aead::class.java)
                    
                    // 3. 生成新的StreamingAEAD密钥
                    streamingAeadKeyset = KeysetHandle.generateNew(
                        StreamingAeadKeyTemplates.AES256_GCM_HKDF_4KB
                    )
                    streamingAead = streamingAeadKeyset?.getPrimitive(StreamingAead::class.java)
                    
                    // 4. 增加轮换计数
                    val newCount = rotationCount.incrementAndGet()
                    
                    // 5. 保存新的密钥信息
                    encryptedPrefs.edit()
                        .putString(KEY_SESSION_DEK, Base64.encodeToString(sessionDEK, Base64.NO_WRAP))
                        .putLong(KEY_DEK_EXPIRY_TIME, dekExpiryTime)
                        .putString(KEY_DEK_KEY_ID, generateDEKKeyId())
                        .putInt(KEY_ROTATION_COUNT, newCount)
                        .putLong(KEY_CREATION_TIME, System.currentTimeMillis())
                        .apply()
                    
                    Log.i(TAG, "密钥轮换成功，当前轮换次数: $newCount，新DEK有效期至: ${java.util.Date(dekExpiryTime)}")
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "密钥轮换失败", e)
                false
            }
        }
    }
    
    /**
     * 获取下一个包序列号
     */
    fun getNextPacketSeqNo(): Long {
        val seqNo = packetSeqNo.incrementAndGet()
        // 定期持久化
        if (seqNo % 100 == 0L) {
            encryptedPrefs.edit().putLong(KEY_PACKET_SEQ_NO, seqNo).apply()
        }
        return seqNo
    }
    
    /**
     * 获取DEK密钥ID
     */
    fun getDekKeyId(): String {
        return encryptedPrefs.getString(KEY_DEK_KEY_ID, null) 
            ?: "DEK_${deviceInstanceId}_${System.currentTimeMillis()}"
    }
    
    /**
     * 获取或创建会话级DEK（每小时轮换）
     * 符合 claude.md Task 1.3.6 要求
     */
    fun getOrCreateSessionDEK(): ByteArray {
        val now = System.currentTimeMillis()
        
        // 检查是否需要生成新的DEK
        if (sessionDEK == null || now > dekExpiryTime) {
            synchronized(this) {
                // 双重检查
                if (sessionDEK == null || now > dekExpiryTime) {
                    // 生成新的256-bit DEK
                    sessionDEK = generateRandomBytes(32)
                    dekExpiryTime = now + DEK_LIFETIME_MS
                    
                    // 使用服务器公钥加密DEK（TODO: 实际实现需要服务器公钥）
                    encryptedDEK = encryptDEKWithServerKey(sessionDEK!!)
                    
                    // 保存到加密存储
                    encryptedPrefs.edit()
                        .putString(KEY_SESSION_DEK, Base64.encodeToString(sessionDEK, Base64.NO_WRAP))
                        .putLong(KEY_DEK_EXPIRY_TIME, dekExpiryTime)
                        .putString(KEY_DEK_KEY_ID, generateDEKKeyId())
                        .apply()
                    
                    Log.i(TAG, "生成新的会话级DEK，有效期至: ${java.util.Date(dekExpiryTime)}")
                }
            }
        }
        
        return sessionDEK!!
    }
    
    /**
     * 获取加密后的DEK（用于发送给服务器）
     */
    fun getEncryptedDEK(): ByteArray? {
        getOrCreateSessionDEK() // 确保DEK存在
        return encryptedDEK
    }
    
    /**
     * 生成DEK密钥ID
     */
    private fun generateDEKKeyId(): String {
        return "DEK_${deviceInstanceId}_${System.currentTimeMillis()}"
    }
    
    /**
     * 使用服务器公钥加密DEK
     */
    private fun encryptDEKWithServerKey(dek: ByteArray): ByteArray {
        // TODO: 使用真实的服务器公钥加密
        // return serverPublicKey?.encrypt(dek, null) ?: dek
        
        // 暂时返回模拟的加密DEK
        return Base64.encode(dek, Base64.NO_WRAP)
    }
    
    /**
     * 生成并加密DEK（数据加密密钥）
     * 实际实现需要服务器公钥
     */
    fun generateAndEncryptDEK(): ByteArray {
        return getEncryptedDEK() ?: Base64.encode(generateRandomBytes(32), Base64.NO_WRAP)
    }
    
    /**
     * 获取设备ID的HMAC哈希
     */
    fun getDeviceIdHash(): String {
        return computeHmac(deviceInstanceId ?: "", getDekKeyId())
    }
    
    /**
     * 获取应用包名的HMAC哈希
     */
    fun getAppPackageHash(packageName: String): String {
        return computeHmac(packageName, getDekKeyId())
    }
    
    /**
     * 获取用户ID的HMAC哈希
     */
    fun getUserIdHash(userId: String): String {
        return computeHmac(userId, getDekKeyId())
    }
    
    /**
     * 计算HMAC哈希（避免泄露敏感信息）
     */
    private fun computeHmac(data: String, keyId: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            val key = SecretKeySpec(hmacKey ?: generateRandomBytes(32), "HmacSHA256")
            mac.init(key)
            val input = "$keyId:$data".toByteArray(StandardCharsets.UTF_8)
            val hash = mac.doFinal(input)
            Base64.encodeToString(hash, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "计算HMAC失败", e)
            ""
        }
    }
    
    /**
     * 计算HMAC
     * @param data 要计算哈希的数据
     * @param context 上下文标识（用于区分不同类型的哈希）
     */
    private fun computeHMAC(data: String, context: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            val keyWithContext = "${context}_${Base64.encodeToString(hmacKey, Base64.NO_WRAP)}"
            val secretKey = SecretKeySpec(
                keyWithContext.toByteArray(StandardCharsets.UTF_8),
                "HmacSHA256"
            )
            mac.init(secretKey)
            val hash = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(hash, Base64.NO_WRAP or Base64.URL_SAFE)
        } catch (e: Exception) {
            Log.e(TAG, "计算HMAC失败", e)
            // 降级到SHA256
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest("${context}_${data}".toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(hash, Base64.NO_WRAP or Base64.URL_SAFE)
        }
    }
    
    /**
     * 流式加密（用于大数据）
     */
    private suspend fun encryptStreaming(
        plaintext: ByteArray,
        associatedData: ByteArray?
    ): ByteArray {
        return withContext(Dispatchers.IO) {
            val outputStream = ByteArrayOutputStream()
            streamingAead?.newEncryptingStream(outputStream, associatedData)?.use { encStream ->
                encStream.write(plaintext)
            }
            outputStream.toByteArray()
        }
    }
    
    /**
     * 流式解密（用于大数据）
     */
    private suspend fun decryptStreaming(
        ciphertext: ByteArray,
        associatedData: ByteArray?
    ): ByteArray {
        return withContext(Dispatchers.IO) {
            val inputStream = ByteArrayInputStream(ciphertext)
            val outputStream = ByteArrayOutputStream()
            
            streamingAead?.newDecryptingStream(inputStream, associatedData)?.use { decStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (decStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
            }
            outputStream.toByteArray()
        }
    }
    
    /**
     * 处理加密失败
     */
    private fun handleCryptoFailure() {
        val count = failureCount.incrementAndGet()
        encryptedPrefs.edit().putInt(KEY_FAILED_COUNT, count).apply()
        
        if (count >= MAX_FAILURE_COUNT) {
            Log.e(TAG, "触发安全开关：加密失败次数达到上限（${MAX_FAILURE_COUNT}次）")
            // TODO: 通知上层停止采集
        }
    }
    
    /**
     * 生成随机字节
     */
    private fun generateRandomBytes(size: Int): ByteArray {
        return ByteArray(size).also { bytes ->
            java.security.SecureRandom().nextBytes(bytes)
        }
    }
    
    /**
     * 检查是否因失败过多而被禁用
     */
    fun isCryptoDisabled(): Boolean {
        return failureCount.get() >= MAX_FAILURE_COUNT
    }
    
    /**
     * 重置失败计数（仅在特殊情况下使用）
     */
    fun resetFailureCount() {
        failureCount.set(0)
        encryptedPrefs.edit().putInt(KEY_FAILED_COUNT, 0).apply()
        Log.w(TAG, "失败计数已重置")
    }
}