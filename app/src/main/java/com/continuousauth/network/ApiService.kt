package com.continuousauth.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * API服务接口
 * 定义与服务器通信的HTTP/REST端点
 */
interface ApiService {
    
    /**
     * 获取服务器公钥
     */
    @GET("/api/v1/crypto/public-key")
    suspend fun getPublicKey(): PublicKeyResponse
    
    /**
     * 获取HMAC密钥
     */
    @POST("/api/v1/crypto/hmac-key")
    suspend fun getHmacKey(@Query("device_instance_id") deviceInstanceId: String): HmacKeyResponse
    
    /**
     * 注册设备
     */
    @POST("/api/v1/device/register")
    suspend fun registerDevice(
        @Query("device_id_hash") deviceIdHash: String,
        @Body deviceInfo: com.continuousauth.registration.DeviceRegistration.DeviceInfo
    ): RegistrationResponse
    
    /**
     * 检查设备状态
     */
    @GET("/api/v1/device/status")
    suspend fun checkDeviceStatus(@Query("device_id_hash") deviceIdHash: String): DeviceStatusResponse
    
    /**
     * 获取最新策略
     */
    @GET("/api/v1/policy/latest")
    suspend fun getLatestPolicy(@Query("device_id_hash") deviceIdHash: String): PolicyResponse
    
    /**
     * 上报指标数据
     */
    @POST("/api/v1/metrics/report")
    suspend fun reportMetrics(@Body metrics: MetricsReportRequest): MetricsReportResponse
    
    /**
     * 请求删除数据（GDPR合规）
     */
    @POST("/api/v1/data/delete")
    suspend fun requestDataDeletion(@Query("device_id_hash") deviceIdHash: String): DataDeletionResponse
}

// Response数据类
data class PublicKeyResponse(
    val publicKey: ByteArray,
    val keyId: String,
    val fingerprint: String,
    val validFrom: Long,
    val validUntil: Long
)

data class HmacKeyResponse(
    val hmacKeyId: String,
    val hmacKey: String,
    val createdAt: Long,
    val expiresAt: Long?
)

data class RegistrationResponse(
    val success: Boolean,
    val sessionId: String,
    val serverTime: Long,
    val message: String?
)

data class DeviceStatusResponse(
    val isActive: Boolean,
    val isSuspended: Boolean,
    val lastSeen: Long?,
    val suspensionReason: String?
)

data class PolicyResponse(
    val policyId: String,
    val policyVersion: String,
    val batchIntervalMs: Int,
    val maxPayloadSizeBytes: Int,
    val transmissionProfile: String,
    val compressionAlgorithm: String,
    val sensorSamplingRates: Map<String, Int>,
    val enabledSensors: List<String>
)

data class MetricsReportRequest(
    val deviceIdHash: String,
    val timestamp: Long,
    val metrics: Map<String, Float>
)

data class MetricsReportResponse(
    val accepted: Boolean,
    val nextReportAfterMs: Long?
)

data class DataDeletionResponse(
    val requestId: String,
    val status: String,
    val estimatedCompletionTime: Long,
    val message: String
)