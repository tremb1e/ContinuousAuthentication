package com.continuousauth.network

import android.util.Log
import com.continuousauth.buffer.InMemoryBuffer
import com.continuousauth.proto.DataPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 可恢复上传器
 * 负责处理断点续传和失败重试
 */
@Singleton
class ResumableUploader @Inject constructor(
    private val uploader: Uploader,
    private val networkEnvironmentDetector: NetworkEnvironmentDetector,
    private val errorHandler: ErrorHandler
) {
    
    companion object {
        private const val TAG = "ResumableUploader"
        private const val RESUME_CHECK_INTERVAL_MS = 30000L // 30秒检查间隔
        private const val MAX_CONCURRENT_UPLOADS = 3
    }
    
    // 协程作用域
    private val resumableScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // 状态管理
    private var isRunning = false
    private var resumeJob: Job? = null
    private var networkMonitorJob: Job? = null
    
    // 统计信息
    private var totalResumedPackets = 0L
    private var successfulResumes = 0L
    private var failedResumes = 0L
    
    // 当前正在上传的包ID集合
    private val uploadingPackets = mutableSetOf<String>()
    
    /**
     * 启动可恢复上传器
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "ResumableUploader已在运行中")
            return
        }
        
        isRunning = true
        Log.i(TAG, "启动可恢复上传器")
        
        // 启动网络状态监控
        startNetworkMonitoring()
        
        // 启动定期恢复检查
        startPeriodicResumeCheck()
        
        // 立即检查一次待恢复的上传
        resumableScope.launch {
            checkAndResumeUploads()
        }
    }
    
    /**
     * 停止可恢复上传器
     */
    fun stop() {
        if (!isRunning) {
            return
        }
        
        isRunning = false
        Log.i(TAG, "停止可恢复上传器")
        
        // 取消所有任务
        resumeJob?.cancel()
        networkMonitorJob?.cancel()
        
        // 清理状态
        synchronized(uploadingPackets) {
            uploadingPackets.clear()
        }
    }
    
    /**
     * 启动网络状态监控
     */
    private fun startNetworkMonitoring() {
        networkMonitorJob = resumableScope.launch {
            networkEnvironmentDetector.networkStateFlow
                .distinctUntilChanged()
                .collect { networkState ->
                    handleNetworkStateChange(networkState)
                }
        }
    }
    
    /**
     * 处理网络状态变化
     */
    private suspend fun handleNetworkStateChange(networkState: NetworkState) {
        Log.d(TAG, "网络状态变化: $networkState")
        
        when (networkState) {
            NetworkState.WIFI_EXCELLENT,
            NetworkState.WIFI_GOOD,
            NetworkState.CELLULAR_EXCELLENT,
            NetworkState.CELLULAR_GOOD -> {
                // 网络恢复良好状态，立即检查待上传数据
                Log.i(TAG, "网络恢复良好状态，开始检查待上传数据")
                checkAndResumeUploads()
            }
            
            NetworkState.DISCONNECTED -> {
                Log.w(TAG, "网络断开，暂停上传活动")
                // 网络断开时，可以选择取消当前上传任务
                // 这里我们选择让它们自然失败并等待重试
            }
            
            else -> {
                // 网络质量一般或较差，继续正常处理
                Log.d(TAG, "网络状态: $networkState，继续正常处理")
            }
        }
    }
    
    /**
     * 启动定期恢复检查
     */
    private fun startPeriodicResumeCheck() {
        resumeJob = resumableScope.launch {
            while (isRunning && isActive) {
                try {
                    delay(RESUME_CHECK_INTERVAL_MS)
                    if (isRunning) {
                        checkAndResumeUploads()
                    }
                } catch (e: CancellationException) {
                    Log.i(TAG, "定期恢复检查已取消")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "定期恢复检查异常", e)
                    delay(5000L) // 错误后延迟重试
                }
            }
        }
    }
    
    /**
     * 检查并恢复上传
     */
    private suspend fun checkAndResumeUploads() {
        if (!isRunning) {
            return
        }
        
        try {
            // 这里应该从Room数据库查询PENDING状态的批次
            // 由于我们还没有实现完整的Room数据库，这里使用模拟逻辑
            val pendingBatches = getPendingBatches()
            
            if (pendingBatches.isEmpty()) {
                Log.d(TAG, "没有待恢复的上传")
                return
            }
            
            Log.i(TAG, "发现 ${pendingBatches.size} 个待恢复的上传")
            
            // 获取网络传输配置
            val transmissionConfig = networkEnvironmentDetector.getTransmissionConfig()
            val maxConcurrent = minOf(transmissionConfig.maxConcurrentUploads, MAX_CONCURRENT_UPLOADS)
            
            // 按时间戳排序，优先处理较旧的数据
            val sortedBatches = pendingBatches.sortedBy { it.timestamp }
            
            // 并发处理，但限制并发数量
            val semaphore = Semaphore(maxConcurrent)
            
            sortedBatches.forEach { batch ->
                if (!isRunning) return@forEach
                
                resumableScope.launch {
                    semaphore.withPermit {
                        resumeUpload(batch, transmissionConfig)
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "检查和恢复上传异常", e)
        }
    }
    
    /**
     * 恢复单个上传
     */
    private suspend fun resumeUpload(
        batch: PendingBatch,
        transmissionConfig: TransmissionConfig
    ) {
        val packetId = batch.packetId
        
        // 检查是否已在上传中
        synchronized(uploadingPackets) {
            if (uploadingPackets.contains(packetId)) {
                Log.d(TAG, "数据包 $packetId 已在上传中，跳过")
                return
            }
            uploadingPackets.add(packetId)
        }
        
        try {
            Log.d(TAG, "开始恢复上传: $packetId")
            
            // 读取数据包文件
            val dataPacket = readDataPacketFromFile(batch.filePath)
            if (dataPacket == null) {
                Log.e(TAG, "无法读取数据包文件: ${batch.filePath}")
                handleUploadFailure(batch, Exception("数据包文件不存在或损坏"))
                return
            }
            
            // 执行带重试的上传
            var attemptNumber = 1
            val maxAttempts = transmissionConfig.maxRetryAttempts
            val currentNetworkState = networkEnvironmentDetector.getCurrentNetworkState()
            
            while (attemptNumber <= maxAttempts && isRunning) {
                try {
                    // 检查连接状态
                    if (uploader.getConnectionStatus().state != ConnectionStatus.CONNECTED.name) {
                        Log.w(TAG, "上传器未连接，跳过此次恢复")
                        break
                    }
                    
                    // 发送数据包
                    val success = uploader.sendDataPacket(dataPacket)
                    
                    if (success) {
                        Log.i(TAG, "恢复上传成功: $packetId")
                        handleUploadSuccess(batch)
                        break
                    } else {
                        throw Exception("上传失败，原因未知")
                    }
                    
                } catch (e: Exception) {
                    Log.w(TAG, "恢复上传失败 - 包ID: $packetId, 尝试: $attemptNumber", e)
                    
                    // 使用ErrorHandler处理错误
                    val errorResult = errorHandler.handleError(e, attemptNumber, currentNetworkState)
                    
                    when (errorResult) {
                        is ErrorHandlingResult.Retry -> {
                            Log.d(TAG, "准备重试: ${errorResult.message}")
                            errorHandler.executeWithRetry(errorResult.delayMs)
                            attemptNumber = errorResult.nextAttempt
                        }
                        
                        is ErrorHandlingResult.GiveUp -> {
                            Log.e(TAG, "放弃恢复上传: ${errorResult.message}")
                            handleUploadFailure(batch, e)
                            break
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "恢复上传异常: $packetId", e)
            handleUploadFailure(batch, e)
        } finally {
            // 从上传中集合移除
            synchronized(uploadingPackets) {
                uploadingPackets.remove(packetId)
            }
        }
    }
    
    /**
     * 处理上传成功
     */
    private suspend fun handleUploadSuccess(batch: PendingBatch) {
        try {
            // TODO: 更新Room数据库中的状态为UPLOADED
            // batchMetadataDao.updateStatus(batch.packetId, BatchStatus.UPLOADED)
            
            // 删除本地缓存文件
            val file = File(batch.filePath)
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "已删除缓存文件: ${batch.filePath}")
            }
            
            successfulResumes++
            Log.d(TAG, "上传成功处理完成: ${batch.packetId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "处理上传成功异常", e)
        }
    }
    
    /**
     * 处理上传失败
     */
    private suspend fun handleUploadFailure(batch: PendingBatch, error: Throwable) {
        try {
            // TODO: 更新Room数据库中的重试次数和错误信息
            // batchMetadataDao.updateRetryInfo(batch.packetId, batch.retryCount + 1, error.message)
            
            failedResumes++
            Log.w(TAG, "上传失败处理完成: ${batch.packetId}, 错误: ${error.message}")
            
        } catch (e: Exception) {
            Log.e(TAG, "处理上传失败异常", e)
        }
    }
    
    /**
     * 获取待处理的批次
     * TODO: 从Room数据库实际查询PENDING状态的批次
     */
    private suspend fun getPendingBatches(): List<PendingBatch> {
        // 这里是模拟实现，实际应该从Room数据库查询
        // return batchMetadataDao.getPendingBatches()
        
        return emptyList() // 暂时返回空列表
    }
    
    /**
     * 从文件读取数据包
     * TODO: 实际的文件读取逻辑
     */
    private suspend fun readDataPacketFromFile(filePath: String): DataPacket? {
        return try {
            // 这里应该读取并反序列化DataPacket
            // val file = File(filePath)
            // val bytes = file.readBytes()
            // DataPacket.parseFrom(bytes)
            
            null // 暂时返回null
        } catch (e: Exception) {
            Log.e(TAG, "读取数据包文件异常: $filePath", e)
            null
        }
    }
    
    /**
     * 手动触发恢复检查
     */
    suspend fun triggerResumeCheck() {
        if (!isRunning) {
            Log.w(TAG, "ResumableUploader未运行，无法触发恢复检查")
            return
        }
        
        Log.i(TAG, "手动触发恢复检查")
        checkAndResumeUploads()
    }
    
    /**
     * 获取统计信息
     */
    fun getStatistics(): ResumableUploaderStats {
        return ResumableUploaderStats(
            isRunning = isRunning,
            totalResumedPackets = totalResumedPackets,
            successfulResumes = successfulResumes,
            failedResumes = failedResumes,
            currentUploadingCount = synchronized(uploadingPackets) { uploadingPackets.size }
        )
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stop()
        resumableScope.cancel()
    }
}

/**
 * 待处理批次数据类
 * TODO: 这应该对应Room数据库中的BatchMetadata实体
 */
data class PendingBatch(
    val packetId: String,
    val filePath: String,
    val timestamp: Long,
    val retryCount: Int = 0,
    val lastError: String? = null
)

/**
 * ResumableUploader统计信息
 */
data class ResumableUploaderStats(
    val isRunning: Boolean,
    val totalResumedPackets: Long,
    val successfulResumes: Long,
    val failedResumes: Long,
    val currentUploadingCount: Int
)

/**
 * 信号量扩展函数，用于限制并发
 */
private class Semaphore(private val permits: Int) {
    private var available = permits
    
    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }
    
    private suspend fun acquire() {
        while (true) {
            synchronized(this) {
                if (available > 0) {
                    available--
                    return
                }
            }
            delay(10) // 短暂等待
        }
    }
    
    private fun release() {
        synchronized(this) {
            available++
        }
    }
}