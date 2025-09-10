package com.continuousauth.crypto

/**
 * 加密盒子接口
 * 基于Google Tink实现的AEAD加密
 */
interface CryptoBox {
    
    /**
     * 初始化加密系统
     * 设置密钥层次结构和会话密钥
     */
    suspend fun initialize(): Boolean
    
    /**
     * 加密数据
     * @param plaintext 明文数据
     * @param associatedData 关联数据(AAD)，用于额外的完整性验证
     * @return 加密后的数据，如果失败返回null
     */
    suspend fun encrypt(plaintext: ByteArray, associatedData: ByteArray? = null): ByteArray?
    
    /**
     * 解密数据
     * @param ciphertext 密文数据
     * @param associatedData 关联数据(AAD)，必须与加密时一致
     * @return 解密后的数据，如果失败返回null
     */
    suspend fun decrypt(ciphertext: ByteArray, associatedData: ByteArray? = null): ByteArray?
    
    /**
     * 轮换会话密钥
     * 生成新的DEK用于后续加密操作
     */
    suspend fun rotateSessionKey(): Boolean
    
    /**
     * 获取当前密钥信息
     */
    fun getKeyInfo(): CryptoKeyInfo
    
    /**
     * 销毁敏感数据
     */
    fun destroy()
    
    /**
     * 获取当前密钥版本
     */
    fun getCurrentKeyVersion(): String {
        return "KEY_V1"
    }
    
    /**
     * 获取安全状态
     */
    fun getSecurityStatus(): SecurityStatus {
        return SecurityStatus()
    }
    
    /**
     * 轮换密钥
     */
    suspend fun rotateKeys(): Boolean {
        return rotateSessionKey()
    }
    
    /**
     * 更新服务器公钥
     */
    suspend fun updateServerPublicKey(publicKey: ByteArray): Boolean {
        return true
    }
}

/**
 * 安全状态数据类
 */
data class SecurityStatus(
    val isInitialized: Boolean = true,
    val hasValidKeys: Boolean = true,
    val isLocked: Boolean = false
)

/**
 * 密钥信息数据类
 */
data class CryptoKeyInfo(
    val masterKeyExists: Boolean,      // 主密钥是否存在
    val sessionKeyActive: Boolean,     // 会话密钥是否激活
    val keysetProvider: String,        // 密钥集提供者 (Android Keystore, StrongBox等)
    val encryptionAlgorithm: String,   // 加密算法
    val keyCreationTime: Long,         // 密钥创建时间
    val keyRotationCount: Long         // 密钥轮换次数
)