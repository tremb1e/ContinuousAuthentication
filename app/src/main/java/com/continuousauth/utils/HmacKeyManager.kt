package com.continuousauth.utils

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HMAC密钥管理器
 * 使用 EncryptedSharedPreferences 安全存储 HMAC 密钥
 * 符合 Epic 2.3.2 的要求：HMAC密钥安全存储和设备ID哈希生成
 */
@Singleton
class HmacKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "HmacKeyManager"
        private const val PREFS_NAME = "hmac_keys"
        private const val KEY_HMAC_KEY_ID = "hmac_key_id"
        private const val KEY_HMAC_KEY = "hmac_key"
        private const val KEY_DEVICE_INSTANCE_ID = "device_instance_id"
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }
    
    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }
    
    private val encryptedPrefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    /**
     * 保存HMAC密钥
     */
    fun saveHmacKey(keyId: String, key: String) {
        try {
            encryptedPrefs.edit()
                .putString(KEY_HMAC_KEY_ID, keyId)
                .putString(KEY_HMAC_KEY, key)
                .apply()
            Log.d(TAG, "HMAC密钥已保存: keyId=$keyId")
        } catch (e: Exception) {
            Log.e(TAG, "保存HMAC密钥失败", e)
            throw SecurityException("无法保存HMAC密钥", e)
        }
    }
    
    /**
     * 保存设备实例ID
     */
    fun saveDeviceInstanceId(deviceInstanceId: String) {
        try {
            encryptedPrefs.edit()
                .putString(KEY_DEVICE_INSTANCE_ID, deviceInstanceId)
                .apply()
            Log.d(TAG, "设备实例ID已保存")
        } catch (e: Exception) {
            Log.e(TAG, "保存设备实例ID失败", e)
            throw SecurityException("无法保存设备实例ID", e)
        }
    }
    
    /**
     * 获取HMAC密钥ID
     */
    fun getHmacKeyId(): String? {
        return try {
            encryptedPrefs.getString(KEY_HMAC_KEY_ID, null)
        } catch (e: Exception) {
            Log.e(TAG, "获取HMAC密钥ID失败", e)
            null
        }
    }
    
    /**
     * 获取设备实例ID
     */
    fun getDeviceInstanceId(): String? {
        return try {
            encryptedPrefs.getString(KEY_DEVICE_INSTANCE_ID, null)
        } catch (e: Exception) {
            Log.e(TAG, "获取设备实例ID失败", e)
            null
        }
    }
    
    /**
     * 生成设备ID哈希
     * 使用HMAC(key_id, device_instance_id)生成不可逆的设备标识
     */
    fun generateDeviceIdHash(deviceInstanceId: String? = null): String {
        try {
            val keyId = getHmacKeyId()
                ?: throw IllegalStateException("HMAC密钥ID未找到")
            
            val key = encryptedPrefs.getString(KEY_HMAC_KEY, null)
                ?: throw IllegalStateException("HMAC密钥未找到")
            
            val actualDeviceId = deviceInstanceId ?: getDeviceInstanceId()
                ?: throw IllegalStateException("设备实例ID未找到")
            
            return computeHMAC(key, "$keyId:$actualDeviceId")
        } catch (e: Exception) {
            Log.e(TAG, "生成设备ID哈希失败", e)
            throw SecurityException("无法生成设备ID哈希", e)
        }
    }
    
    /**
     * 生成应用包名哈希
     * 用于前台应用的隐私保护
     */
    fun generateAppHash(packageName: String): String {
        try {
            val keyId = getHmacKeyId()
                ?: throw IllegalStateException("HMAC密钥ID未找到")
            
            val key = encryptedPrefs.getString(KEY_HMAC_KEY, null)
                ?: throw IllegalStateException("HMAC密钥未找到")
            
            return computeHMAC(key, "$keyId:$packageName")
        } catch (e: Exception) {
            Log.e(TAG, "生成应用哈希失败", e)
            // 返回空字符串而不是抛出异常，避免影响数据采集
            return ""
        }
    }
    
    /**
     * 生成用户ID哈希
     */
    fun generateUserIdHash(userId: String): String {
        try {
            val keyId = getHmacKeyId()
                ?: throw IllegalStateException("HMAC密钥ID未找到")
            
            val key = encryptedPrefs.getString(KEY_HMAC_KEY, null)
                ?: throw IllegalStateException("HMAC密钥未找到")
            
            return computeHMAC(key, "$keyId:$userId")
        } catch (e: Exception) {
            Log.e(TAG, "生成用户ID哈希失败", e)
            throw SecurityException("无法生成用户ID哈希", e)
        }
    }
    
    /**
     * 计算HMAC
     */
    private fun computeHMAC(key: String, data: String): String {
        return try {
            val secretKey = SecretKeySpec(
                key.toByteArray(StandardCharsets.UTF_8),
                HMAC_ALGORITHM
            )
            
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(secretKey)
            
            val hmacBytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            
            // 转换为十六进制字符串
            hmacBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "HMAC计算失败", e)
            throw SecurityException("HMAC计算失败", e)
        }
    }
    
    /**
     * 验证HMAC
     */
    fun verifyHMAC(data: String, expectedHmac: String): Boolean {
        return try {
            val keyId = getHmacKeyId()
                ?: throw IllegalStateException("HMAC密钥ID未找到")
            
            val key = encryptedPrefs.getString(KEY_HMAC_KEY, null)
                ?: throw IllegalStateException("HMAC密钥未找到")
            
            val computedHmac = computeHMAC(key, data)
            
            // 使用时间恒定的比较防止时序攻击
            constantTimeEquals(computedHmac, expectedHmac)
        } catch (e: Exception) {
            Log.e(TAG, "HMAC验证失败", e)
            false
        }
    }
    
    /**
     * 时间恒定的字符串比较
     * 防止时序攻击
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) {
            return false
        }
        
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
    
    /**
     * 清除所有存储的密钥和标识
     * 用于设备注销或重新注册
     */
    fun clearAll() {
        try {
            encryptedPrefs.edit()
                .clear()
                .apply()
            Log.i(TAG, "所有HMAC密钥和标识已清除")
        } catch (e: Exception) {
            Log.e(TAG, "清除密钥失败", e)
            throw SecurityException("无法清除密钥", e)
        }
    }
    
    /**
     * 轮换HMAC密钥
     * 用于定期密钥更新
     */
    suspend fun rotateHmacKey(newKeyId: String, newKey: String) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "开始轮换HMAC密钥")
            
            // 保存新密钥
            saveHmacKey(newKeyId, newKey)
            
            // 重新生成设备ID哈希
            val deviceInstanceId = getDeviceInstanceId()
            if (deviceInstanceId != null) {
                val newDeviceIdHash = generateDeviceIdHash(deviceInstanceId)
                Log.d(TAG, "新设备ID哈希已生成: $newDeviceIdHash")
            }
            
            Log.i(TAG, "HMAC密钥轮换完成")
        } catch (e: Exception) {
            Log.e(TAG, "HMAC密钥轮换失败", e)
            throw SecurityException("HMAC密钥轮换失败", e)
        }
    }
    
    /**
     * 检查是否需要密钥轮换
     * 基于时间或使用次数
     */
    fun shouldRotateKey(): Boolean {
        // TODO: 实现密钥轮换策略
        // 例如：每月轮换一次或使用次数超过阈值
        return false
    }
}