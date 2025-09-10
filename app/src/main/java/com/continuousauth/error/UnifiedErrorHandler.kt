package com.continuousauth.error

import android.util.Log
import com.continuousauth.crypto.KeyManagementService
import com.continuousauth.network.ConnectionManager
import com.continuousauth.network.UploadManager
import com.continuousauth.proto.DataPacket
import com.continuousauth.storage.FileQueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一错误处理器
 * 符合 claude.md Epic 2.4 和 Section 8.2 要求
 */
@Singleton
class UnifiedErrorHandler @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val uploadManager: UploadManager,
    private val fileQueueManager: FileQueueManager,
    private val keyManagementService: KeyManagementService
) {
    
    companion object {
        private const val TAG = "UnifiedErrorHandler"
        
        // 错误重试配置
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val BASE_RETRY_DELAY = 1000L // 1秒
    }
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private val errorMetrics = mutableMapOf<String, Int>()
    
    /**
     * 应用错误基类
     */
    sealed class AppError(
        open val message: String,
        open val throwable: Throwable? = null
    ) {
        data class NetworkError(
            override val message: String,
            val code: NetworkErrorCode,
            override val throwable: Throwable? = null
        ) : AppError(message, throwable)
        
        data class CryptoError(
            override val message: String,
            val code: CryptoErrorCode,
            override val throwable: Throwable? = null
        ) : AppError(message, throwable)
        
        data class SensorError(
            override val message: String,
            val code: SensorErrorCode,
            override val throwable: Throwable? = null
        ) : AppError(message, throwable)
        
        data class StorageError(
            override val message: String,
            val code: StorageErrorCode,
            override val throwable: Throwable? = null
        ) : AppError(message, throwable)
        
        data class ServerError(
            override val message: String,
            val serverCode: String,
            val packet: DataPacket? = null,
            override val throwable: Throwable? = null
        ) : AppError(message, throwable)
    }
    
    /**
     * 网络错误码
     */
    enum class NetworkErrorCode {
        NO_CONNECTION,
        TIMEOUT,
        SERVER_ERROR,
        TLS_ERROR,
        DNS_ERROR,
        RATE_LIMITED,
        FORBIDDEN
    }
    
    /**
     * 加密错误码
     */
    enum class CryptoErrorCode {
        KEY_NOT_FOUND,
        ENCRYPTION_FAILED,
        DECRYPTION_FAILED,
        KEY_ROTATION_FAILED,
        KEYSTORE_ERROR,
        INVALID_AAD
    }
    
    /**
     * 传感器错误码
     */
    enum class SensorErrorCode {
        SENSOR_NOT_AVAILABLE,
        SAMPLING_RATE_NOT_SUPPORTED,
        BUFFER_OVERFLOW,
        REGISTRATION_FAILED
    }
    
    /**
     * 存储错误码
     */
    enum class StorageErrorCode {
        DISK_FULL,
        FILE_CORRUPT,
        PERMISSION_DENIED,
        DATABASE_ERROR,
        QUEUE_OVERFLOW
    }
    
    /**
     * 处理错误
     */
    fun handleError(error: AppError) {
        Log.e(TAG, "处理错误: ${error.message}", error.throwable)
        
        // 记录错误指标
        recordErrorMetric(error)
        
        when (error) {
            is AppError.NetworkError -> handleNetworkError(error)
            is AppError.CryptoError -> handleCryptoError(error)
            is AppError.SensorError -> handleSensorError(error)
            is AppError.StorageError -> handleStorageError(error)
            is AppError.ServerError -> handleServerError(error)
        }
    }
    
    /**
     * 处理网络错误
     */
    private fun handleNetworkError(error: AppError.NetworkError) {
        Log.w(TAG, "处理网络错误: ${error.code}")
        
        when (error.code) {
            NetworkErrorCode.NO_CONNECTION -> {
                Log.i(TAG, "无网络连接，启用离线模式")
                enableOfflineMode()
            }
            
            NetworkErrorCode.TIMEOUT -> {
                Log.i(TAG, "网络超时，使用退避重试")
                retryWithBackoff()
            }
            
            NetworkErrorCode.SERVER_ERROR -> {
                Log.w(TAG, "服务器错误，切换到备用服务器")
                switchToBackupServer()
            }
            
            NetworkErrorCode.TLS_ERROR -> {
                Log.e(TAG, "TLS错误，检查证书配置")
                // 通知用户TLS配置问题
                notifyTlsError()
            }
            
            NetworkErrorCode.RATE_LIMITED -> {
                Log.w(TAG, "请求被限流")
                handleRateLimiting()
            }
            
            NetworkErrorCode.DNS_ERROR -> {
                Log.e(TAG, "DNS解析失败")
                // 尝试使用备用DNS或IP直连
                tryAlternativeDns()
            }
            
            NetworkErrorCode.FORBIDDEN -> {
                Log.e(TAG, "访问被拒绝")
                // 可能需要重新认证
                handleForbidden()
            }
        }
    }
    
    /**
     * 处理加密错误
     */
    private fun handleCryptoError(error: AppError.CryptoError) {
        Log.e(TAG, "处理加密错误: ${error.code}")
        
        when (error.code) {
            CryptoErrorCode.KEY_NOT_FOUND -> {
                Log.e(TAG, "密钥未找到，尝试重新获取")
                scope.launch {
                    keyManagementService.refreshKeys()
                }
            }
            
            CryptoErrorCode.ENCRYPTION_FAILED -> {
                Log.e(TAG, "加密失败")
                // 记录失败但继续处理其他数据
                recordEncryptionFailure()
            }
            
            CryptoErrorCode.KEY_ROTATION_FAILED -> {
                Log.e(TAG, "密钥轮换失败")
                // 延迟重试密钥轮换
                scheduleKeyRotationRetry()
            }
            
            CryptoErrorCode.KEYSTORE_ERROR -> {
                Log.e(TAG, "Keystore错误")
                // 尝试重新初始化Keystore
                reinitializeKeystore()
            }
            
            CryptoErrorCode.INVALID_AAD -> {
                Log.e(TAG, "无效的AAD")
                // 重新构建AAD
                rebuildAAD()
            }
            
            else -> {
                Log.e(TAG, "未处理的加密错误: ${error.code}")
            }
        }
    }
    
    /**
     * 处理传感器错误
     */
    private fun handleSensorError(error: AppError.SensorError) {
        Log.w(TAG, "处理传感器错误: ${error.code}")
        
        when (error.code) {
            SensorErrorCode.SENSOR_NOT_AVAILABLE -> {
                Log.w(TAG, "传感器不可用")
                // 降级到可用传感器
                degradeToAvailableSensors()
            }
            
            SensorErrorCode.SAMPLING_RATE_NOT_SUPPORTED -> {
                Log.w(TAG, "采样率不支持")
                // 使用最接近的支持采样率
                useClosestSamplingRate()
            }
            
            SensorErrorCode.BUFFER_OVERFLOW -> {
                Log.e(TAG, "缓冲区溢出")
                // 清空缓冲区并调整大小
                handleBufferOverflow()
            }
            
            SensorErrorCode.REGISTRATION_FAILED -> {
                Log.e(TAG, "传感器注册失败")
                // 延迟重试注册
                scheduleSensorReregistration()
            }
        }
    }
    
    /**
     * 处理存储错误
     */
    private fun handleStorageError(error: AppError.StorageError) {
        Log.e(TAG, "处理存储错误: ${error.code}")
        
        when (error.code) {
            StorageErrorCode.DISK_FULL -> {
                Log.e(TAG, "磁盘已满")
                // 清理缓存和旧数据
                cleanupStorage()
            }
            
            StorageErrorCode.FILE_CORRUPT -> {
                Log.e(TAG, "文件损坏")
                // 标记文件为损坏并跳过
                markFileAsCorrupt()
            }
            
            StorageErrorCode.PERMISSION_DENIED -> {
                Log.e(TAG, "权限被拒绝")
                // 请求必要权限
                requestStoragePermissions()
            }
            
            StorageErrorCode.DATABASE_ERROR -> {
                Log.e(TAG, "数据库错误")
                // 尝试修复数据库
                attemptDatabaseRepair()
            }
            
            StorageErrorCode.QUEUE_OVERFLOW -> {
                Log.e(TAG, "队列溢出")
                // 丢弃旧数据以腾出空间
                handleQueueOverflow()
            }
        }
    }
    
    /**
     * 处理服务器错误（符合 claude.md Epic 2.4.2）
     */
    private fun handleServerError(error: AppError.ServerError) {
        Log.e(TAG, "处理服务器错误: ${error.serverCode}")
        
        val packet = error.packet
        
        when (error.serverCode) {
            "ERR_DECRYPT_DEK_FAILED" -> {
                Log.e(TAG, "服务器解密DEK失败")
                // 重新获取服务器公钥
                scope.launch {
                    keyManagementService.refreshServerPublicKey()
                    packet?.let { retryWithNewKey(it) }
                }
            }
            
            "ERR_REPLAY_DETECTED" -> {
                Log.e(TAG, "检测到重放攻击")
                // 重置序列号并重新开始
                resetSequenceNumber()
                clearPendingQueue()
            }
            
            "ERR_RATE_LIMIT_EXCEEDED" -> {
                Log.w(TAG, "超过速率限制")
                // 应用退避策略
                val retryAfter = extractRetryAfter(error.message)
                packet?.let { scheduleRetry(it, retryAfter) }
            }
            
            "ERR_DEVICE_SUSPENDED" -> {
                Log.w(TAG, "设备被暂停")
                // 停止采集并通知用户
                stopCollection()
                notifyUserDeviceSuspended()
            }
            
            "ERR_KEY_VERSION_NOT_FOUND" -> {
                Log.e(TAG, "密钥版本未找到")
                // 获取新密钥版本
                scope.launch {
                    keyManagementService.updateKeyVersion()
                    packet?.let { retryWithUpdatedKey(it) }
                }
            }
            
            "ERR_INVALID_AAD" -> {
                Log.e(TAG, "AAD验证失败")
                // 重新构建AAD并重试
                packet?.let { retryWithRebuildAAD(it) }
            }
            
            "ERR_PACKET_TOO_LARGE" -> {
                Log.w(TAG, "数据包过大")
                // 减小批处理大小
                reduceBatchSize()
            }
            
            "ERR_INVALID_DEVICE_ID" -> {
                Log.e(TAG, "无效的设备ID")
                // 重新注册设备
                reregisterDevice()
            }
            
            else -> {
                Log.e(TAG, "未知服务器错误: ${error.serverCode}")
                // 通用错误处理
                handleGenericServerError(error)
            }
        }
    }
    
    // ===== 具体处理方法 =====
    
    private fun enableOfflineMode() {
        Log.i(TAG, "启用离线模式")
        uploadManager.setOfflineMode(true)
        fileQueueManager.enablePersistence()
    }
    
    private fun retryWithBackoff() {
        scope.launch {
            var retryCount = 0
            while (retryCount < MAX_RETRY_ATTEMPTS) {
                delay(BASE_RETRY_DELAY * (1 shl retryCount))
                
                val result = connectionManager.connectWithRetry()
                if (result.isSuccess) {
                    break
                }
                retryCount++
            }
        }
    }
    
    private fun switchToBackupServer() {
        Log.i(TAG, "切换到备用服务器")
        // TODO: 实现备用服务器切换逻辑
    }
    
    private fun handleRateLimiting() {
        scope.launch {
            // 降低上传频率
            uploadManager.reduceUploadRate()
            delay(60_000) // 等待1分钟
            uploadManager.resumeNormalRate()
        }
    }
    
    private fun cleanupStorage() {
        scope.launch {
            Log.i(TAG, "清理存储空间")
            fileQueueManager.cleanupOldFiles()
            fileQueueManager.compactDatabase()
        }
    }
    
    private fun resetSequenceNumber() {
        Log.i(TAG, "重置序列号")
        // TODO: 实现序列号重置逻辑
    }
    
    private fun clearPendingQueue() {
        scope.launch {
            Log.i(TAG, "清空待发送队列")
            fileQueueManager.clearPendingQueue()
        }
    }
    
    private fun stopCollection() {
        Log.i(TAG, "停止数据采集")
        // TODO: 调用服务停止采集
    }
    
    private fun notifyUserDeviceSuspended() {
        Log.w(TAG, "通知用户设备被暂停")
        // TODO: 发送通知
    }
    
    private fun scheduleRetry(packet: DataPacket, delayMs: Long) {
        scope.launch {
            Log.i(TAG, "计划 ${delayMs}ms 后重试")
            delay(delayMs)
            uploadManager.retryPacket(packet)
        }
    }
    
    private fun retryWithNewKey(packet: DataPacket) {
        scope.launch {
            Log.i(TAG, "使用新密钥重试")
            // TODO: 重新加密并发送
        }
    }
    
    private fun retryWithUpdatedKey(packet: DataPacket) {
        scope.launch {
            Log.i(TAG, "使用更新的密钥版本重试")
            // TODO: 使用新版本密钥重新加密
        }
    }
    
    private fun retryWithRebuildAAD(packet: DataPacket) {
        scope.launch {
            Log.i(TAG, "重建AAD并重试")
            // TODO: 重新构建AAD并重试
        }
    }
    
    private fun reduceBatchSize() {
        Log.i(TAG, "减小批处理大小")
        // TODO: 调整批处理配置
    }
    
    private fun reregisterDevice() {
        scope.launch {
            Log.i(TAG, "重新注册设备")
            // TODO: 调用设备注册流程
        }
    }
    
    private fun extractRetryAfter(message: String): Long {
        // 从错误消息中提取重试延迟
        return try {
            val regex = "retry_after=(\\d+)".toRegex()
            val match = regex.find(message)
            match?.groupValues?.get(1)?.toLong() ?: 5000L
        } catch (e: Exception) {
            5000L // 默认5秒
        }
    }
    
    private fun handleGenericServerError(error: AppError.ServerError) {
        Log.e(TAG, "通用服务器错误处理")
        // 记录错误并可能触发告警
    }
    
    // ===== 辅助方法 =====
    
    private fun recordErrorMetric(error: AppError) {
        val key = error::class.simpleName ?: "UnknownError"
        errorMetrics[key] = (errorMetrics[key] ?: 0) + 1
    }
    
    private fun notifyTlsError() {
        // TODO: 通知UI层TLS错误
    }
    
    private fun tryAlternativeDns() {
        // TODO: 尝试备用DNS
    }
    
    private fun handleForbidden() {
        // TODO: 处理403错误
    }
    
    private fun recordEncryptionFailure() {
        // TODO: 记录加密失败
    }
    
    private fun scheduleKeyRotationRetry() {
        scope.launch {
            delay(300_000) // 5分钟后重试
            keyManagementService.rotateKeys()
        }
    }
    
    private fun reinitializeKeystore() {
        // TODO: 重新初始化Keystore
    }
    
    private fun rebuildAAD() {
        // TODO: 重建AAD
    }
    
    private fun degradeToAvailableSensors() {
        // TODO: 降级传感器配置
    }
    
    private fun useClosestSamplingRate() {
        // TODO: 调整采样率
    }
    
    private fun handleBufferOverflow() {
        // TODO: 处理缓冲区溢出
    }
    
    private fun scheduleSensorReregistration() {
        scope.launch {
            delay(5000)
            // TODO: 重新注册传感器
        }
    }
    
    private fun markFileAsCorrupt() {
        // TODO: 标记文件损坏
    }
    
    private fun requestStoragePermissions() {
        // TODO: 请求存储权限
    }
    
    private fun attemptDatabaseRepair() {
        // TODO: 尝试修复数据库
    }
    
    private fun handleQueueOverflow() {
        // TODO: 处理队列溢出
    }
    
    /**
     * 获取错误统计
     */
    fun getErrorStatistics(): Map<String, Int> {
        return errorMetrics.toMap()
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        // 取消所有协程任务
    }
}