package com.continuousauth.network

import android.util.Log
import com.continuousauth.buffer.InMemoryBuffer
import com.continuousauth.policy.PolicyManager
import com.continuousauth.storage.FileQueueManager
import com.continuousauth.proto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上传管理器
 * 负责协调数据包上传、ACK处理和策略管理
 */
@Singleton
class UploadManager @Inject constructor(
    private val uploader: Uploader,
    private val inMemoryBuffer: InMemoryBuffer,
    private val policyManager: PolicyManager,
    private val fileQueueManager: FileQueueManager,
    private val networkEnvironmentDetector: NetworkEnvironmentDetector
) {
    
    companion object {
        private const val TAG = "UploadManager"
        private const val UPLOAD_BATCH_SIZE = 50
        private const val UPLOAD_INTERVAL_MS = 1000L // 1秒上传间隔
        private const val ACK_RETRY_MS = 10_000L
    }

    // 添加这些变量来跟踪当前使用的速率参数
    private var currentBatchSize = UPLOAD_BATCH_SIZE
    private var currentUploadInterval = UPLOAD_INTERVAL_MS
    private var isReducedRateMode = false

    // 添加离线模式状态变量
    private var isOfflineMode = false
    private var lastServerEndpoint: String? = null // 保存最后连接的服务器端点

    // 状态管理
    private val isRunning = AtomicBoolean(false)
    private val uploadedPackets = AtomicLong(0L)
    
    // 协程作用域
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var uploadJob: Job? = null
    private var directiveJob: Job? = null
    
    // 策略管理
    private var currentPolicy: PolicyUpdate? = null
    private var policyUpdateCallback: ((PolicyUpdate) -> Unit)? = null
    
    // 网络模式管理
    private var isWifiOnlyMode = false

    /**
     * 启动上传管理器
     */
    suspend fun start(serverEndpoint: String): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "上传管理器已在运行中")
            return true
        }
        
        // 连接到服务器
        if (!uploader.connect(serverEndpoint)) {
            Log.e(TAG, "连接服务器失败: $serverEndpoint")
            return false
        }
        
        isRunning.set(true)

        // 保存服务器端点，以便离线模式关闭时重新连接
        lastServerEndpoint = serverEndpoint

        // 启动服务器指令处理
        startDirectiveProcessing()
        
        // 启动数据包上传循环
        startUploadLoop()
        
        Log.i(TAG, "上传管理器已启动")
        return true
    }
    
    /**
     * 停止上传管理器
     */
    suspend fun stop() {
        if (!isRunning.get()) {
            return
        }
        
        isRunning.set(false)
        
        // 取消所有任务
        uploadJob?.cancel()
        directiveJob?.cancel()
        
        // 断开连接
        uploader.disconnect()
        
        Log.i(TAG, "上传管理器已停止")
    }
    
    /**
     * 手动发送数据包
     */
    suspend fun sendDataPacket(dataPacket: DataPacket): Boolean {
        if (!isRunning.get()) {
            Log.w(TAG, "上传管理器未运行，无法发送数据包")
            return false
        }
        
        return uploader.sendDataPacket(dataPacket)
    }
    
    /**
     * 设置策略更新回调
     */
    fun setPolicyUpdateCallback(callback: (PolicyUpdate) -> Unit) {
        policyUpdateCallback = callback
    }
    
    /**
     * 获取当前策略
     */
    fun getCurrentPolicy(): PolicyUpdate? = currentPolicy
    
    /**
     * 获取上传状态
     */
    fun getUploadStatus(): UploadStatus {
        val statusDetail = uploader.getConnectionStatus()
        val status = ConnectionStatus.values().find { it.name == statusDetail.state } 
            ?: ConnectionStatus.DISCONNECTED
        
        val queueStats = runBlocking { 
            val stats = fileQueueManager.getQueueStatistics()
            com.continuousauth.storage.QueueStats(
                totalSizeBytes = stats.totalSizeBytes,
                fileCount = stats.totalPackets,
                pendingCount = stats.pendingPackets,
                failedCount = stats.totalFailed.toInt(),
                acknowledgedCount = stats.totalSent.toInt(),
                totalPackets = stats.totalPackets,
                pendingPackets = stats.pendingPackets, 
                uploadedPackets = stats.uploadedPackets,
                corruptedPackets = stats.corruptedPackets
            )
        }
        
        return UploadStatus(
            isRunning = isRunning.get(),
            connectionStatus = status,
            uploadedPackets = uploadedPackets.get(),
            bufferedPackets = inMemoryBuffer.getSize(),
            connectionStats = uploader.getConnectionStats(),
            fileQueueStats = queueStats
        )
    }

    /**
     * 测试服务器连接（复用 UploaderImpl）
     */
    suspend fun testServerConnection(
        serverHost: String,
        serverPort: Int,
        useTls: Boolean = true,
        testGrpc: Boolean = true
    ): ServerTestResult {
        return uploader.testServerConnection(serverHost, serverPort, useTls, testGrpc)
    }

    /**
     * 将测试结果转换为可读字符串
     */
    fun getTestResultDescription(result: ServerTestResult): String {
        return uploader.getTestResultDescription(result)
    }

    /**
     * 获取当前传输通道状态（TLS/h2c）
     */
    fun getTransportState(): TransportState = uploader.getTransportState()
    
    /**
     * 启动服务器指令处理
     */
    private fun startDirectiveProcessing() {
        directiveJob = managerScope.launch {
            try {
                uploader.getServerDirectiveFlow()
                    .catch { e ->
                        Log.e(TAG, "处理服务器指令流异常", e)
                    }
                    .collect { directive ->
                        processServerDirective(directive)
                    }
            } catch (e: CancellationException) {
                Log.i(TAG, "服务器指令处理已取消")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "服务器指令处理异常", e)
            }
        }
    }
    
    /**
     * 启动数据包上传循环
     */
    private fun startUploadLoop() {
        uploadJob = managerScope.launch {
            while (isRunning.get() && isActive) {
                try {
                    var processed: Int
                    do {
                        processed = uploadBatchFromBuffer()
                    } while (isRunning.get() && processed > 0)
                    delay(UPLOAD_INTERVAL_MS)
                } catch (e: CancellationException) {
                    Log.i(TAG, "上传循环已取消")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "上传循环异常", e)
                    delay(5000L) // 出错后延迟5秒重试
                }
            }
        }
    }
    
    /**
     * 从缓冲区上传批次数据
     */
    private suspend fun uploadBatchFromBuffer(): Int {
        // 检查WiFi-only模式
        if (isWifiOnlyMode && !networkEnvironmentDetector.isWifiConnected()) {
            Log.v(TAG, "WiFi-only模式启用，当前非WiFi网络，跳过上传")
            return 0
        }
        
        // 优先从内存缓冲区获取
        val packets = if (!inMemoryBuffer.isEmpty()) {
            inMemoryBuffer.dequeue(currentBatchSize)
        } else {
            // 内存为空时，从磁盘队列拉取待上传数据
            val pendingMetadata = fileQueueManager.getPendingPackets(ACK_RETRY_MS).take(UPLOAD_BATCH_SIZE)
            pendingMetadata.mapNotNull { meta ->
                val bytes = fileQueueManager.readDataPacket(meta.packetId).getOrNull()
                if (bytes == null) {
                    Log.w(TAG, "读取待上传数据失败: ${meta.packetId}")
                    fileQueueManager.updateFailedStatus(meta.packetId, "READ_FAILED")
                    fileQueueManager.deleteDataPacket(meta.packetId)
                    null
                } else {
                    runCatching { DataPacket.parseFrom(bytes) }
                        .onFailure { 
                            Log.e(TAG, "解析待上传数据失败: ${meta.packetId}", it)
                            fileQueueManager.updateFailedStatus(meta.packetId, "PARSE_FAILED")
                            fileQueueManager.deleteDataPacket(meta.packetId)
                        }
                        .getOrNull()
                }
            }
        }
        
        if (packets.isEmpty()) {
            return 0
        }
        
        try {
            Log.d(TAG, "开始上传批次数据 - 数量: ${packets.size}")
            
            // 逐个发送数据包
            var successCount = 0
            
            for (packet in packets) {
                if (uploader.sendDataPacket(packet)) {
                    successCount++
                    // 更新磁盘队列状态为已上传（等待ACK）
                    fileQueueManager.updateUploadStatus(
                        packetId = packet.packetId,
                        status = com.continuousauth.database.BatchStatus.UPLOADED,
                        uploadTime = System.currentTimeMillis()
                    )
                } else {
                    Log.w(TAG, "数据包发送失败: ${packet.packetId}")
                    fileQueueManager.updateFailedStatus(
                        packetId = packet.packetId,
                        error = "SEND_FAILED"
                    )
                }
            }
            
            uploadedPackets.addAndGet(successCount.toLong())
            Log.d(TAG, "批次上传完成 - 成功: $successCount/${packets.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "批次上传异常", e)
        }

        return packets.size
    }
    
    /**
     * 处理服务器指令
     */
    private suspend fun processServerDirective(directive: ServerDirective) {
        try {
            when {
                directive.hasAck() -> {
                    processAck(directive.ack)
                }
                directive.hasPolicy() -> {
                    processPolicyUpdate(directive.policy)
                }
                else -> {
                    Log.w(TAG, "收到未知类型的服务器指令")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理服务器指令异常", e)
        }
    }
    
    /**
     * 处理ACK确认
     */
    private suspend fun processAck(ack: Ack) {
        Log.d(TAG, "处理ACK - 包ID: ${ack.packetId}, 成功: ${ack.success}")
        
        if (ack.success) {
            // 先更新数据库状态为ACKNOWLEDGED，记录服务器时间戳
            fileQueueManager.updateAckStatus(
                packetId = ack.packetId,
                serverTimestamp = ack.creationServerTs
            )
            // 然后删除本地文件
            fileQueueManager.deleteDataPacket(ack.packetId)
            Log.d(TAG, "数据包ACK成功，已更新状态并删除: ${ack.packetId}")
        } else {
            // 处理失败情况
            when (ack.errorCode) {
                "DUPLICATE" -> {
                    // 重复包也更新状态并删除
                    fileQueueManager.updateAckStatus(
                        packetId = ack.packetId,
                        serverTimestamp = ack.creationServerTs
                    )
                    fileQueueManager.deleteDataPacket(ack.packetId)
                    Log.w(TAG, "收到重复数据包ACK，已更新状态并删除: ${ack.packetId}")
                }
                "INVALID_FORMAT" -> {
                    // 格式无效的包标记为失败状态
                    fileQueueManager.updateFailedStatus(
                        packetId = ack.packetId,
                        error = ack.errorCode
                    )
                    fileQueueManager.deleteDataPacket(ack.packetId)
                    Log.e(TAG, "数据包格式无效，已标记失败并删除: ${ack.packetId}")
                }
                "DECRYPTION_FAILED" -> {
                    // 解密失败的包标记为失败状态
                    fileQueueManager.updateFailedStatus(
                        packetId = ack.packetId,
                        error = ack.errorCode
                    )
                    fileQueueManager.deleteDataPacket(ack.packetId)
                    Log.e(TAG, "数据包解密失败，已标记失败并删除: ${ack.packetId}")
                }
                "SERVER_ERROR" -> {
                    // 服务器错误，更新重试信息，保留包以便重试
                    fileQueueManager.updateRetryInfo(
                        packetId = ack.packetId,
                        error = ack.errorCode
                    )
                    // 如果有重试延迟，使用它
                    if (ack.retryAfterMs > 0) {
                        Log.e(TAG, "服务器处理错误，建议 ${ack.retryAfterMs}ms 后重试: ${ack.packetId}")
                    } else {
                        Log.e(TAG, "服务器处理错误，已更新重试信息: ${ack.packetId}")
                    }
                }
                else -> {
                    Log.w(TAG, "未知错误码: ${ack.errorCode}, 包ID: ${ack.packetId}")
                }
            }
        }
    }
    
    /**
     * 处理策略更新
     */
    private suspend fun processPolicyUpdate(policyUpdate: PolicyUpdate) {
        Log.i(TAG, "收到策略更新 - 策略ID: ${policyUpdate.policyId}")
        
        try {
            // 应用策略到PolicyManager
            policyManager.applyPolicyUpdate(policyUpdate)
            
            currentPolicy = policyUpdate
            
            // 通知应用层策略已更新
            policyUpdateCallback?.invoke(policyUpdate)
            
            // 记录策略信息
            Log.d(TAG, "策略更新详情:")
            // 记录策略更新信息
            Log.d(TAG, "  策略版本: ${policyUpdate.policyVersion}")
            if (policyUpdate.batchIntervalMs > 0) {
                Log.d(TAG, "  批处理间隔: ${policyUpdate.batchIntervalMs}ms")
            }
            if (policyUpdate.anomalyConfig != null) {
                val ac = policyUpdate.anomalyConfig
                Log.d(TAG, "  异常检测 - 启用: ${ac.enabled}, 阈值倍数: ${ac.thresholdMultiplier}")
            }
            Log.i(TAG, "策略更新处理完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "处理策略更新失败", e)
        }
    }
    
    /**
     * 设置离线模式
     */
    fun setOfflineMode(enabled: Boolean) {
        if (isOfflineMode == enabled) {
            Log.i(TAG, "离线模式已经${if (enabled) "启用" else "禁用"}")
            return
        }

        Log.i(TAG, "设置离线模式: $enabled")
        isOfflineMode = enabled

        if (enabled) {
            // 启用离线模式
            enableOfflineMode()
        } else {
            // 禁用离线模式，尝试恢复连接
            disableOfflineMode()
        }
    }
    /**
     * 启用离线模式的具体逻辑
     */
    private fun enableOfflineMode() {
        Log.i(TAG, "进入离线模式")

        // 取消上传任务，但不停止整体服务
        uploadJob?.cancel()
        directiveJob?.cancel()

        // 断开与服务器的连接
        try {
            runBlocking {
                uploader.disconnect()
            }
            Log.i(TAG, "已断开服务器连接")
        } catch (e: Exception) {
            Log.e(TAG, "断开连接时发生错误", e)
        }

        Log.d(TAG, "离线模式已启用 - 继续收集数据并存储到本地")
    }

    /**
     * 禁用离线模式的具体逻辑
     */
    private fun disableOfflineMode() {
        Log.i(TAG, "退出离线模式，尝试恢复连接")

        // 如果上传管理器没有运行，不执行恢复操作
        if (!isRunning.get()) {
            Log.w(TAG, "上传管理器未运行，无法恢复连接")
            return
        }

        // 如果有保存的服务器端点，尝试重新连接
        val endpoint = lastServerEndpoint
        if (endpoint != null) {
            Log.i(TAG, "尝试重新连接到服务器: $endpoint")

            // 在IO线程中执行连接操作
            managerScope.launch {
                try {
                    // 重新连接服务器
                    if (uploader.connect(endpoint)) {
                        Log.i(TAG, "服务器重新连接成功")

                        // 重新启动指令处理和上传循环
                        startDirectiveProcessing()
                        startUploadLoop()

                        Log.i(TAG, "离线模式已禁用 - 上传功能已恢复")
                    } else {
                        Log.e(TAG, "服务器重新连接失败")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "恢复连接过程中发生错误", e)
                }
            }
        } else {
            Log.w(TAG, "没有保存的服务器端点，无法自动恢复连接")
        }
    }
    /**
     * 减少上传速率
     */
    fun reduceUploadRate() {
        if (isReducedRateMode) {
            Log.i(TAG, "已经处于降低速率模式")
            return
        }

        Log.i(TAG, "减少上传速率")

        // 保存当前设置
        currentBatchSize = REDUCED_BATCH_SIZE
        currentUploadInterval = REDUCED_INTERVAL_MS
        isReducedRateMode = true

        // 如果上传循环正在运行，重启它以应用新的间隔
        if (isRunning.get() && uploadJob?.isActive == true) {
            uploadJob?.cancel()
            startUploadLoop()
        }

        Log.d(TAG, "上传速率已降低 - 批次大小: $REDUCED_BATCH_SIZE, 间隔: ${REDUCED_INTERVAL_MS}ms")
    }
    
    /**
     * 恢复正常速率
     */
    fun resumeNormalRate() {
        if (!isReducedRateMode) {
            Log.i(TAG, "已经处于正常速率模式")
            return
        }

        Log.i(TAG, "恢复正常上传速率")

        // 恢复默认设置
        currentBatchSize = UPLOAD_BATCH_SIZE
        currentUploadInterval = UPLOAD_INTERVAL_MS
        isReducedRateMode = false

        // 如果上传循环正在运行，重启它以应用新的间隔
        if (isRunning.get() && uploadJob?.isActive == true) {
            uploadJob?.cancel()
            startUploadLoop()
        }

        Log.d(TAG, "上传速率已恢复正常 - 批次大小: $UPLOAD_BATCH_SIZE, 间隔: ${UPLOAD_INTERVAL_MS}ms")
    }
    /**
     * 重试数据包
     */
    suspend fun retryPacket(packet: DataPacket) {
        Log.i(TAG, "重试数据包: ${packet.packetId}")
        // TODO: 实现重试逻辑
    }
    
    /**
     * 设置仅WiFi模式
     */
    fun setWifiOnlyMode(enabled: Boolean) {
        Log.i(TAG, "设置仅WiFi模式: $enabled")
        isWifiOnlyMode = enabled
        
        // 如果启用了WiFi-only模式且当前不是WiFi网络，暂停上传
        if (enabled && !networkEnvironmentDetector.isWifiConnected()) {
            Log.w(TAG, "WiFi-only模式已启用，但当前不是WiFi网络，暂停上传")
            // 暂时停止上传循环
            uploadJob?.cancel()
        } else if (!enabled || networkEnvironmentDetector.isWifiConnected()) {
            // 如果关闭了WiFi-only模式或当前是WiFi网络，恢复上传
            if (isRunning.get() && uploadJob?.isActive != true) {
                startUploadLoop()
            }
        }
    }
    
    /**
     * 设置本地缓存启用
     */
    fun setLocalCachingEnabled(enabled: Boolean) {
        Log.i(TAG, "设置本地缓存: $enabled")
        // TODO: 实现本地缓存控制
    }
    
    /**
     * 获取待发送数据包数量
     */
    fun getPendingPacketsCount(): Int {
        return inMemoryBuffer.getSize() + fileQueueManager.queueStats.value.pendingPackets
    }
    
    /**
     * 获取最后的包序列号
     */
    fun getLastPacketSeqNo(): Long {
        return uploadedPackets.get()
    }
}

/**
 * 上传状态
 */
data class UploadStatus(
    val isRunning: Boolean,
    val connectionStatus: ConnectionStatus,
    val uploadedPackets: Long,
    val bufferedPackets: Int,
    val connectionStats: ConnectionStats,
    val fileQueueStats: com.continuousauth.storage.QueueStats? = null
)
