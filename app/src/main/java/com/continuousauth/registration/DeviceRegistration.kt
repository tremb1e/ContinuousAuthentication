package com.continuousauth.registration

import android.util.Log
import com.continuousauth.BuildConfig
import com.continuousauth.crypto.KeyManagementService
import com.continuousauth.network.ApiService
import com.continuousauth.utils.HmacKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备注册与密钥交换流程
 * 符合 Epic 2.3.1 的要求：设备注册、公钥获取、指纹验证、HMAC密钥管理
 */
@Singleton
class DeviceRegistration @Inject constructor(
    private val apiService: ApiService,
    private val keyManagementService: KeyManagementService,
    private val hmacKeyManager: HmacKeyManager
) {
    
    companion object {
        private const val TAG = "DeviceRegistration"
    }
    
    data class RegistrationResult(
        val success: Boolean,
        val deviceIdHash: String,
        val sessionId: String?,
        val errorMessage: String?
    )
    
    /**
     * 执行完整的设备注册流程
     * 包括：生成设备标识、获取服务器公钥、验证指纹、保存密钥、完成注册
     */
    suspend fun register(): RegistrationResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "开始设备注册流程")
            
            // 1. 生成设备标识
            val deviceInstanceId = UUID.randomUUID().toString()
            Log.d(TAG, "生成设备实例ID: $deviceInstanceId")
            
            // 2. 获取服务器公钥（HTTPS）
            Log.d(TAG, "获取服务器公钥")
            val publicKeyResponse = apiService.getPublicKey()
            
            // 3. 验证公钥指纹（可选：硬编码预期指纹）
            val expectedFingerprint = BuildConfig.SERVER_KEY_FINGERPRINT
            if (expectedFingerprint.isNotEmpty() && publicKeyResponse.fingerprint != expectedFingerprint) {
                val error = "公钥指纹不匹配: 期望=$expectedFingerprint, 实际=${publicKeyResponse.fingerprint}"
                Log.e(TAG, error)
                throw SecurityException(error)
            }
            Log.d(TAG, "公钥指纹验证成功")
            
            // 4. 保存公钥用于后续加密
            keyManagementService.saveServerPublicKey(
                publicKey = publicKeyResponse.publicKey,
                keyId = publicKeyResponse.keyId,
                fingerprint = publicKeyResponse.fingerprint
            )
            Log.d(TAG, "服务器公钥已保存: keyId=${publicKeyResponse.keyId}")
            
            // 5. 获取并保存 HMAC 密钥
            Log.d(TAG, "获取HMAC密钥")
            val hmacKeyResponse = apiService.getHmacKey(deviceInstanceId)
            
            hmacKeyManager.saveHmacKey(
                keyId = hmacKeyResponse.hmacKeyId,
                key = hmacKeyResponse.hmacKey
            )
            hmacKeyManager.saveDeviceInstanceId(deviceInstanceId)
            Log.d(TAG, "HMAC密钥已保存: keyId=${hmacKeyResponse.hmacKeyId}")
            
            // 6. 完成注册
            val deviceIdHash = hmacKeyManager.generateDeviceIdHash(deviceInstanceId)
            Log.d(TAG, "生成设备ID哈希: $deviceIdHash")
            
            val registrationResponse = apiService.registerDevice(
                deviceIdHash = deviceIdHash,
                deviceInfo = collectDeviceInfo()
            )
            
            Log.i(TAG, "设备注册成功: sessionId=${registrationResponse.sessionId}")
            
            return@withContext RegistrationResult(
                success = true,
                deviceIdHash = deviceIdHash,
                sessionId = registrationResponse.sessionId,
                errorMessage = null
            )
            
        } catch (e: SecurityException) {
            Log.e(TAG, "安全验证失败", e)
            return@withContext RegistrationResult(
                success = false,
                deviceIdHash = "",
                sessionId = null,
                errorMessage = "安全验证失败: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "设备注册失败", e)
            return@withContext RegistrationResult(
                success = false,
                deviceIdHash = "",
                sessionId = null,
                errorMessage = "注册失败: ${e.message}"
            )
        }
    }
    
    /**
     * 验证设备是否已注册
     */
    suspend fun isRegistered(): Boolean = withContext(Dispatchers.IO) {
        try {
            val deviceInstanceId = hmacKeyManager.getDeviceInstanceId()
            val hmacKeyId = hmacKeyManager.getHmacKeyId()
            
            return@withContext deviceInstanceId != null && hmacKeyId != null
        } catch (e: Exception) {
            Log.e(TAG, "检查注册状态失败", e)
            return@withContext false
        }
    }
    
    /**
     * 重新注册设备（用于密钥轮换或失效后）
     */
    suspend fun reregister(): RegistrationResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "重新注册设备")
            
            // 清除旧的密钥和标识
            hmacKeyManager.clearAll()
            
            // 执行新的注册流程
            return@withContext register()
        } catch (e: Exception) {
            Log.e(TAG, "重新注册失败", e)
            return@withContext RegistrationResult(
                success = false,
                deviceIdHash = "",
                sessionId = null,
                errorMessage = "重新注册失败: ${e.message}"
            )
        }
    }
    
    /**
     * 收集设备信息用于注册
     */
    private fun collectDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            manufacturer = android.os.Build.MANUFACTURER,
            model = android.os.Build.MODEL,
            androidVersion = android.os.Build.VERSION.RELEASE,
            apiLevel = android.os.Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE
        )
    }
    
    /**
     * 计算公钥指纹
     */
    fun calculateFingerprint(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    data class DeviceInfo(
        val manufacturer: String,
        val model: String,
        val androidVersion: String,
        val apiLevel: Int,
        val appVersion: String,
        val appVersionCode: Int
    )
}