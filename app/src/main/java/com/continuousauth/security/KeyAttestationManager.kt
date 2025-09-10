package com.continuousauth.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 密钥证明结果
 * 包含密钥证明验证的详细信息
 */
data class AttestationResult(
    val isSuccess: Boolean,                     // 证明是否成功
    val isHardwareBacked: Boolean,              // 是否硬件支持
    val isStrongBoxBacked: Boolean,             // 是否StrongBox支持
    val attestationSecurityLevel: Int,          // 证明安全级别
    val keySecurityLevel: Int,                  // 密钥安全级别
    val bootPatchLevel: Int?,                   // 启动补丁级别
    val vendorPatchLevel: Int?,                 // 厂商补丁级别
    val appId: String?,                         // 应用ID
    val appVersion: Long?,                      // 应用版本
    val errorMessage: String? = null            // 错误信息
)

/**
 * 密钥证明管理器接口
 * 定义密钥证明的基本功能
 */
interface KeyAttestationManager {
    /**
     * 生成并证明密钥
     * @param keyAlias 密钥别名
     * @param challenge 挑战值
     * @return 证明结果
     */
    suspend fun generateAndAttestKey(keyAlias: String, challenge: ByteArray): AttestationResult
    
    /**
     * 验证已存在密钥的证明
     * @param keyAlias 密钥别名
     * @return 证明结果
     */
    suspend fun verifyKeyAttestation(keyAlias: String): AttestationResult
    
    /**
     * 检查设备是否支持密钥证明
     * @return 是否支持
     */
    fun isAttestationSupported(): Boolean
    
    /**
     * 检查设备是否支持StrongBox
     * @return 是否支持StrongBox
     */
    fun isStrongBoxSupported(): Boolean
}

/**
 * 密钥证明管理器实现
 * 使用Android Keystore的Key Attestation功能验证密钥安全性
 */
@Singleton
class KeyAttestationManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : KeyAttestationManager {
    
    companion object {
        private const val TAG = "KeyAttestationManager"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        
        // 证明扩展OID
        private const val KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17"
        
        // 安全级别常量
        private const val SECURITY_LEVEL_SOFTWARE = 0
        private const val SECURITY_LEVEL_TRUSTED_ENVIRONMENT = 1
        private const val SECURITY_LEVEL_STRONGBOX = 2
    }
    
    override suspend fun generateAndAttestKey(keyAlias: String, challenge: ByteArray): AttestationResult {
        return try {
            if (!isAttestationSupported()) {
                return AttestationResult(
                    isSuccess = false,
                    isHardwareBacked = false,
                    isStrongBoxBacked = false,
                    attestationSecurityLevel = SECURITY_LEVEL_SOFTWARE,
                    keySecurityLevel = SECURITY_LEVEL_SOFTWARE,
                    bootPatchLevel = null,
                    vendorPatchLevel = null,
                    appId = null,
                    appVersion = null,
                    errorMessage = "设备不支持密钥证明"
                )
            }
            
            // 生成密钥对并请求证明
            val keyPair = generateAttestationKey(keyAlias, challenge)
            
            // 获取证明证书链
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val certChain = keyStore.getCertificateChain(keyAlias)
            
            if (certChain.isNullOrEmpty()) {
                return AttestationResult(
                    isSuccess = false,
                    isHardwareBacked = false,
                    isStrongBoxBacked = false,
                    attestationSecurityLevel = SECURITY_LEVEL_SOFTWARE,
                    keySecurityLevel = SECURITY_LEVEL_SOFTWARE,
                    bootPatchLevel = null,
                    vendorPatchLevel = null,
                    appId = null,
                    appVersion = null,
                    errorMessage = "无法获取证明证书链"
                )
            }
            
            // 解析证明信息
            parseAttestationCertificate(certChain[0] as X509Certificate)
            
        } catch (e: Exception) {
            Log.e(TAG, "密钥证明失败", e)
            AttestationResult(
                isSuccess = false,
                isHardwareBacked = false,
                isStrongBoxBacked = false,
                attestationSecurityLevel = SECURITY_LEVEL_SOFTWARE,
                keySecurityLevel = SECURITY_LEVEL_SOFTWARE,
                bootPatchLevel = null,
                vendorPatchLevel = null,
                appId = null,
                appVersion = null,
                errorMessage = e.message
            )
        }
    }
    
    override suspend fun verifyKeyAttestation(keyAlias: String): AttestationResult {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            
            if (!keyStore.containsAlias(keyAlias)) {
                return AttestationResult(
                    isSuccess = false,
                    isHardwareBacked = false,
                    isStrongBoxBacked = false,
                    attestationSecurityLevel = SECURITY_LEVEL_SOFTWARE,
                    keySecurityLevel = SECURITY_LEVEL_SOFTWARE,
                    bootPatchLevel = null,
                    vendorPatchLevel = null,
                    appId = null,
                    appVersion = null,
                    errorMessage = "密钥不存在"
                )
            }
            
            val certChain = keyStore.getCertificateChain(keyAlias)
            if (certChain.isNullOrEmpty()) {
                return AttestationResult(
                    isSuccess = false,
                    isHardwareBacked = false,
                    isStrongBoxBacked = false,
                    attestationSecurityLevel = SECURITY_LEVEL_SOFTWARE,
                    keySecurityLevel = SECURITY_LEVEL_SOFTWARE,
                    bootPatchLevel = null,
                    vendorPatchLevel = null,
                    appId = null,
                    appVersion = null,
                    errorMessage = "无法获取证书链"
                )
            }
            
            parseAttestationCertificate(certChain[0] as X509Certificate)
            
        } catch (e: Exception) {
            Log.e(TAG, "密钥证明验证失败", e)
            AttestationResult(
                isSuccess = false,
                isHardwareBacked = false,
                isStrongBoxBacked = false,
                attestationSecurityLevel = SECURITY_LEVEL_SOFTWARE,
                keySecurityLevel = SECURITY_LEVEL_SOFTWARE,
                bootPatchLevel = null,
                vendorPatchLevel = null,
                appId = null,
                appVersion = null,
                errorMessage = e.message
            )
        }
    }
    
    override fun isAttestationSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }
    
    override fun isStrongBoxSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
               context.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")
    }
    
    /**
     * 生成带证明的密钥对
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun generateAttestationKey(keyAlias: String, challenge: ByteArray) {
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).apply {
            setDigests(KeyProperties.DIGEST_SHA256)
            setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            setKeySize(2048)
            setAttestationChallenge(challenge)
            
            // 如果支持StrongBox，优先使用
            if (isStrongBoxSupported()) {
                setIsStrongBoxBacked(true)
            }
            
            // 要求用户身份验证（如果需要）
            setUserAuthenticationRequired(false)
            
        }.build()
        
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA, 
            KEYSTORE_PROVIDER
        )
        keyPairGenerator.initialize(keyGenParameterSpec)
        keyPairGenerator.generateKeyPair()
    }
    
    /**
     * 解析证明证书
     * 注意：这是一个简化的实现，实际生产环境需要完整的ASN.1解析
     */
    private fun parseAttestationCertificate(certificate: X509Certificate): AttestationResult {
        return try {
            // 检查是否有证明扩展
            val attestationExtension = certificate.getExtensionValue(KEY_DESCRIPTION_OID)
            val hasAttestation = attestationExtension != null
            
            // 简化的证明信息解析（实际需要ASN.1解析）
            val isHardwareBacked = hasAttestation
            val isStrongBoxBacked = isStrongBoxSupported() && hasAttestation
            
            AttestationResult(
                isSuccess = hasAttestation,
                isHardwareBacked = isHardwareBacked,
                isStrongBoxBacked = isStrongBoxBacked,
                attestationSecurityLevel = if (isStrongBoxBacked) SECURITY_LEVEL_STRONGBOX 
                    else if (isHardwareBacked) SECURITY_LEVEL_TRUSTED_ENVIRONMENT 
                    else SECURITY_LEVEL_SOFTWARE,
                keySecurityLevel = if (isStrongBoxBacked) SECURITY_LEVEL_STRONGBOX 
                    else if (isHardwareBacked) SECURITY_LEVEL_TRUSTED_ENVIRONMENT 
                    else SECURITY_LEVEL_SOFTWARE,
                bootPatchLevel = extractPatchLevel("boot"),
                vendorPatchLevel = extractPatchLevel("vendor"),
                appId = context.packageName,
                appVersion = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                } catch (e: Exception) {
                    null
                }
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "证明证书解析失败", e)
            AttestationResult(
                isSuccess = false,
                isHardwareBacked = false,
                isStrongBoxBacked = false,
                attestationSecurityLevel = SECURITY_LEVEL_SOFTWARE,
                keySecurityLevel = SECURITY_LEVEL_SOFTWARE,
                bootPatchLevel = null,
                vendorPatchLevel = null,
                appId = null,
                appVersion = null,
                errorMessage = e.message
            )
        }
    }
    
    /**
     * 提取补丁级别信息
     * 注意：这是一个占位符实现，实际需要从证明扩展中解析
     */
    private fun extractPatchLevel(type: String): Int? {
        return try {
            when (type) {
                "boot" -> Build.VERSION.SECURITY_PATCH.replace("-", "").toIntOrNull()
                "vendor" -> null // 需要从证明扩展中解析
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 获取格式化的证明报告
     */
    fun getFormattedAttestationReport(result: AttestationResult): String {
        val sb = StringBuilder()
        
        sb.appendLine("=== 密钥证明报告 ===")
        sb.appendLine("证明状态: ${if (result.isSuccess) "成功" else "失败"}")
        sb.appendLine("硬件支持: ${if (result.isHardwareBacked) "是" else "否"}")
        sb.appendLine("StrongBox支持: ${if (result.isStrongBoxBacked) "是" else "否"}")
        sb.appendLine("证明安全级别: ${getSecurityLevelName(result.attestationSecurityLevel)}")
        sb.appendLine("密钥安全级别: ${getSecurityLevelName(result.keySecurityLevel)}")
        
        result.bootPatchLevel?.let {
            sb.appendLine("启动补丁级别: $it")
        }
        
        result.vendorPatchLevel?.let {
            sb.appendLine("厂商补丁级别: $it")
        }
        
        result.appId?.let {
            sb.appendLine("应用ID: $it")
        }
        
        result.appVersion?.let {
            sb.appendLine("应用版本: $it")
        }
        
        result.errorMessage?.let {
            sb.appendLine("错误信息: $it")
        }
        
        return sb.toString()
    }
    
    private fun getSecurityLevelName(level: Int): String {
        return when (level) {
            SECURITY_LEVEL_SOFTWARE -> "软件"
            SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "可信环境"
            SECURITY_LEVEL_STRONGBOX -> "StrongBox"
            else -> "未知($level)"
        }
    }
}