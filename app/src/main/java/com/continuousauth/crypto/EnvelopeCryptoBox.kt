package com.continuousauth.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 简化版的信封加密实现：
 * - 与服务端共享同一固定密钥（SHA-256("Continuous_Authentication")）
 * - AES-256-GCM，加密输出格式：IV(12) || TAG(16) || CIPHERTEXT
 * - 不再使用 Tink/Envelope DEK，避免服务端无法解密的问题
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
        private const val KEY_PACKET_SEQ_NO = "packet_seq_no"
        private const val KEY_ROTATION_COUNT = "key_rotation_count"
        private const val KEY_CREATION_TIME = "key_creation_time"
        private const val KEY_FAILED_COUNT = "crypto_failed_count"

        private const val SHARED_SECRET = "Continuous_Authentication"
        private const val STATIC_KEY_ID = "STATIC_KEY_V1"

        private const val AES_KEY_SIZE = 32
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH = 16
        private const val GCM_TAG_BITS = 128
        private const val MAX_FAILURE_COUNT = 5
    }

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

    private val mutex = Mutex()
    private val packetSeqNo = AtomicLong(0)
    private val rotationCount = AtomicInteger(0)
    private val failureCount = AtomicInteger(0)

    private var deviceInstanceId: String? = null
    private var hmacKey: ByteArray? = null
    private var aesKey: ByteArray? = null
    private var keyCreationTime: Long = 0L

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                // 设备实例 ID
                deviceInstanceId = encryptedPrefs.getString(KEY_DEVICE_ID, null)
                if (deviceInstanceId == null) {
                    deviceInstanceId = UUID.randomUUID().toString()
                    encryptedPrefs.edit().putString(KEY_DEVICE_ID, deviceInstanceId).apply()
                    Log.i(TAG, "生成新的设备实例ID")
                }

                // HMAC 密钥
                val savedHmac = encryptedPrefs.getString(KEY_HMAC_KEY, null)
                hmacKey = if (savedHmac == null) {
                    val newKey = generateRandomBytes(32)
                    encryptedPrefs.edit()
                        .putString(KEY_HMAC_KEY, Base64.encodeToString(newKey, Base64.NO_WRAP))
                        .apply()
                    Log.i(TAG, "生成新的HMAC密钥")
                    newKey
                } else {
                    Base64.decode(savedHmac, Base64.NO_WRAP)
                }

                // 序列号与轮换计数
                packetSeqNo.set(encryptedPrefs.getLong(KEY_PACKET_SEQ_NO, 0))
                rotationCount.set(encryptedPrefs.getInt(KEY_ROTATION_COUNT, 0))
                keyCreationTime = encryptedPrefs.getLong(KEY_CREATION_TIME, System.currentTimeMillis())

                aesKey = deriveSharedKey()
                failureCount.set(0)
                encryptedPrefs.edit().putInt(KEY_FAILED_COUNT, 0).apply()

                Log.i(TAG, "Shared AES-GCM 加密初始化成功")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "EnvelopeCryptoBox 初始化失败", e)
            false
        }
    }

    override suspend fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val key = aesKey ?: return@withContext null
                val iv = generateRandomBytes(IV_LENGTH)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(GCM_TAG_BITS, iv)
                )
                // 服务端未验证 AAD，因此这里不设置 AAD，避免解密不一致
                val cipherWithTag = cipher.doFinal(plaintext)
                if (cipherWithTag.size < TAG_LENGTH) {
                    handleCryptoFailure()
                    return@withContext null
                }
                val tag = cipherWithTag.copyOfRange(cipherWithTag.size - TAG_LENGTH, cipherWithTag.size)
                val ciphertext = cipherWithTag.copyOfRange(0, cipherWithTag.size - TAG_LENGTH)

                ByteArray(IV_LENGTH + TAG_LENGTH + ciphertext.size).apply {
                    System.arraycopy(iv, 0, this, 0, IV_LENGTH)
                    System.arraycopy(tag, 0, this, IV_LENGTH, TAG_LENGTH)
                    System.arraycopy(ciphertext, 0, this, IV_LENGTH + TAG_LENGTH, ciphertext.size)
                }
            } catch (e: Exception) {
                Log.e(TAG, "加密失败", e)
                handleCryptoFailure()
                null
            }
        }
    }

    override suspend fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                if (ciphertext.size <= IV_LENGTH + TAG_LENGTH) {
                    handleCryptoFailure()
                    return@withContext null
                }
                val key = aesKey ?: return@withContext null
                val iv = ciphertext.copyOfRange(0, IV_LENGTH)
                val tag = ciphertext.copyOfRange(IV_LENGTH, IV_LENGTH + TAG_LENGTH)
                val ct = ciphertext.copyOfRange(IV_LENGTH + TAG_LENGTH, ciphertext.size)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(GCM_TAG_BITS, iv)
                )
                val combined = ByteArray(ct.size + TAG_LENGTH).apply {
                    System.arraycopy(ct, 0, this, 0, ct.size)
                    System.arraycopy(tag, 0, this, ct.size, TAG_LENGTH)
                }
                cipher.doFinal(combined)
            } catch (e: Exception) {
                Log.e(TAG, "解密失败", e)
                handleCryptoFailure()
                null
            }
        }
    }

    override suspend fun rotateSessionKey(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            keyCreationTime = System.currentTimeMillis()
            val newCount = rotationCount.incrementAndGet()
            encryptedPrefs.edit()
                .putInt(KEY_ROTATION_COUNT, newCount)
                .putLong(KEY_CREATION_TIME, keyCreationTime)
                .apply()
            Log.i(TAG, "静态密钥轮换标记（逻辑计数）已更新: $newCount")
            true
        }
    }

    override fun getSecurityStatus(): SecurityStatus {
        return SecurityStatus(
            isInitialized = aesKey != null,
            hasValidKeys = aesKey != null,
            isLocked = failureCount.get() >= MAX_FAILURE_COUNT,
            failureCount = failureCount.get()
        )
    }

    override fun getKeyInfo(): CryptoKeyInfo {
        return CryptoKeyInfo(
            masterKeyExists = aesKey != null,
            sessionKeyActive = true,
            keysetProvider = "Static shared key",
            encryptionAlgorithm = "AES-256-GCM (iv|tag|ciphertext)",
            keyCreationTime = keyCreationTime,
            keyRotationCount = rotationCount.get().toLong()
        )
    }

    override fun destroy() {
        encryptedPrefs.edit()
            .putLong(KEY_PACKET_SEQ_NO, packetSeqNo.get())
            .putInt(KEY_ROTATION_COUNT, rotationCount.get())
            .putLong(KEY_CREATION_TIME, keyCreationTime)
            .apply()

        hmacKey?.fill(0)
        aesKey = null
        Log.i(TAG, "EnvelopeCryptoBox 已销毁")
    }

    fun getNextPacketSeqNo(): Long {
        val seqNo = packetSeqNo.incrementAndGet()
        if (seqNo % 100 == 0L) {
            encryptedPrefs.edit().putLong(KEY_PACKET_SEQ_NO, seqNo).apply()
        }
        return seqNo
    }

    fun getDekKeyId(): String = STATIC_KEY_ID

    fun getEncryptedDEK(): ByteArray? = null

    fun getDeviceIdHash(): String = computeHmac(deviceInstanceId ?: "", getDekKeyId())

    fun getAppPackageHash(packageName: String): String = computeHmac(packageName, getDekKeyId())

    fun getUserIdHash(userId: String): String = computeHmac(userId, getDekKeyId())

    fun isCryptoDisabled(): Boolean = failureCount.get() >= MAX_FAILURE_COUNT

    fun resetFailureCount() {
        failureCount.set(0)
        encryptedPrefs.edit().putInt(KEY_FAILED_COUNT, 0).apply()
        Log.w(TAG, "失败计数已重置")
    }

    private fun deriveSharedKey(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val key = digest.digest(SHARED_SECRET.toByteArray(StandardCharsets.UTF_8))
        require(key.size == AES_KEY_SIZE) { "Derived key length mismatch" }
        return key
    }

    private fun generateRandomBytes(size: Int): ByteArray {
        return ByteArray(size).also { SecureRandom().nextBytes(it) }
    }

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

    private fun handleCryptoFailure() {
        val count = failureCount.incrementAndGet()
        encryptedPrefs.edit().putInt(KEY_FAILED_COUNT, count).apply()
        if (count >= MAX_FAILURE_COUNT) {
            Log.e(TAG, "触发安全开关：加解密失败次数达到上限（${MAX_FAILURE_COUNT}次）")
        }
    }
}
