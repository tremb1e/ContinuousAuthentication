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
        // 降低速率模式的参数
        private const val REDUCED_BATCH_SIZE = 10 // 降低模式下每批处理的数据包数量
        private const val REDUCED_INTERVAL_MS = 5000L // 降低模式下的上传间隔

        private const val CONNECTION_MONITOR_INTERVAL_MS = 2000L // 2秒检查一次连接状态
        private const val MAX_CONNECTION_ERRORS = 3 // 最大连接错误次数
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
    private val isPaused = AtomicBoolean(false)
    private val uploadPauseReasons = mutableSetOf<String>()
    private val uploadedPackets = AtomicLong(0L)
    
    // 协程作用域
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var uploadJob: Job? = null
    private var directiveJob: Job? = null
    private var connectionMonitorJob: Job? = null // 连接监控任务

    // 策略管理
    private var currentPolicy: PolicyUpdate? = null
    private var policyUpdateCallback: ((PolicyUpdate) -> Unit)? = null

    private val _authResultFlow = MutableSharedFlow<AuthResult>(extraBufferCapacity = 64)
    val authResultFlow: SharedFlow<AuthResult> = _authResultFlow
    
    // 网络模式管理
    private var isWifiOnlyMode = false
    private val smartTransmissionEnabled = AtomicBoolean(false)
    private val smartTransmissionUploadAllowed = AtomicBoolean(true)

    // 连接状态跟踪
    private var lastConnectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED
    private var connectionErrorCount = 0

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
        isPaused.set(false)
        synchronized(uploadPauseReasons) {
            uploadPauseReasons.clear()
        }

        // 保存服务器端点，以便离线模式关闭时重新连接
        lastServerEndpoint = serverEndpoint

        // 启动服务器指令处理
        startDirectiveProcessing()
        
        // 启动数据包上传循环
        startUploadLoop()

        // 启动连接状态监控
        startConnectionMonitoring()

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
        isPaused.set(false)
        synchronized(uploadPauseReasons) {
            uploadPauseReasons.clear()
        }
        
        // 取消所有任务
        uploadJob?.cancel()
        directiveJob?.cancel()

        // 取消连接监控任务
        connectionMonitorJob?.cancel()

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
     * 暂停上传
     */
    fun pauseUpload(reason: String = "manual") {
        if (!isRunning.get()) {
            Log.w(TAG, "上传管理器未运行，无法暂停")
            return
        }

        val shouldPause = synchronized(uploadPauseReasons) {
            val wasActive = uploadPauseReasons.isEmpty()
            uploadPauseReasons.add(reason)
            wasActive
        }

        if (!shouldPause) {
            Log.i(TAG, "上传已经处于暂停状态，新增暂停原因: $reason")
            return
        }
        
        isPaused.set(true)
        
        // 取消上传任务，但保持指令处理运行
        uploadJob?.cancel()
        
        Log.i(TAG, "上传已暂停 - reason=$reason")
    }
    
    /**
     * 恢复上传
     */
    fun resumeUpload(reason: String = "manual") {
        if (!isRunning.get()) {
            Log.w(TAG, "上传管理器未运行，无法恢复")
            return
        }

        val remainingReasons = synchronized(uploadPauseReasons) {
            uploadPauseReasons.remove(reason)
            uploadPauseReasons.toList()
        }

        if (remainingReasons.isNotEmpty()) {
            Log.i(TAG, "上传仍因 ${remainingReasons.joinToString()} 暂停，暂不恢复")
            return
        }

        if (!isPaused.get()) {
            Log.i(TAG, "上传未暂停，无需恢复")
            return
        }
        
        isPaused.set(false)
        
        // 重新启动上传循环
        startUploadLoop()
        
        Log.i(TAG, "上传已恢复 - reason=$reason")
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
     * 发起认证会话
     */
    suspend fun startAuthentication(deviceIdHash: String, sessionId: String?): AuthSessionResponse? {
        if (!isConnected()) return null
        return uploader.startAuthentication(deviceIdHash, sessionId)
    }
    
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
            isPaused = isPaused.get(),
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
                    // 检查是否处于暂停状态
                    if (isPaused.get()) {
                        delay(currentUploadInterval)
                        continue
                    }

                    // 检查连接状态，如果连接断开则等待重连
                    if (!isConnected()) {
                        Log.w(TAG, "连接断开，等待重连...")
                        delay(5000L) // 等待5秒后重试
                        continue
                    }

                    var processed: Int
                    do {
                        processed = uploadBatchFromBuffer()
                    } while (isRunning.get() && !isPaused.get() && processed > 0)

                    // 如果处于暂停状态，跳过延迟等待
                    if (!isPaused.get()) {
                        delay(currentUploadInterval)
                    }
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
        // 检查是否处于暂停状态
        if (isPaused.get()) {
            Log.v(TAG, "上传已暂停，跳过批次上传")
            return 0
        }

        // 智能传输启用时，仅在满足窗口条件后才允许上传
        if (smartTransmissionEnabled.get() && !smartTransmissionUploadAllowed.get()) {
            return 0
        }
        
        // 检查WiFi-only模式
        if (isWifiOnlyMode && !networkEnvironmentDetector.isWifiConnected()) {
            Log.v(TAG, "WiFi-only模式启用，当前非WiFi网络，跳过上传")
            return 0
        }

        // 检查连接状态
        if (!isConnected()) {
            Log.v(TAG, "连接断开，跳过上传")
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

                // 发送前再次检查连接状态
                if (!isConnected()) {
                    Log.w(TAG, "发送过程中连接断开，停止发送")
                    break
                }

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
                    // 发送失败时检查连接状态
                    if (!isConnected()) {
                        Log.w(TAG, "发送失败，连接已断开")
                        // 不标记为失败，等待重连后重试
                    } else {
                        fileQueueManager.updateFailedStatus(
                            packetId = packet.packetId,
                            error = "SEND_FAILED"
                        )
                    }
                }
            }
            
            uploadedPackets.addAndGet(successCount.toLong())
            Log.d(TAG, "批次上传完成 - 成功: $successCount/${packets.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "批次上传异常", e)
            // 异常处理中检查连接状态
            if (!isConnected()) {
                Log.w(TAG, "上传异常，连接已断开")
            }
        }

        return packets.size
    }

    /**
     * 启动连接状态监控
     */
    private fun startConnectionMonitoring() {
        connectionMonitorJob = managerScope.launch {
            while (isRunning.get() && isActive) {
                try {
                    val currentStatus = uploader.getConnectionStatus()

                    // 检查连接状态变化
                    if (currentStatus.state != lastConnectionStatus.name) {
                        Log.i(TAG, "连接状态变化: $lastConnectionStatus -> ${currentStatus.state}")
                        lastConnectionStatus = ConnectionStatus.valueOf(currentStatus.state)

                        // 处理连接恢复
                        if (lastConnectionStatus == ConnectionStatus.CONNECTED) {
                            onConnectionRestored()
                        } else if (lastConnectionStatus == ConnectionStatus.ERROR) {
                            connectionErrorCount++
                            if (connectionErrorCount >= MAX_CONNECTION_ERRORS) {
                                Log.w(TAG, "连接错误次数过多，尝试重新连接")
                                attemptReconnect()
                            }
                        } else if (lastConnectionStatus == ConnectionStatus.DISCONNECTED) {
                            connectionErrorCount = 0 // 重置错误计数
                        }
                    }

                    delay(CONNECTION_MONITOR_INTERVAL_MS)

                } catch (e: CancellationException) {
                    Log.i(TAG, "连接监控已取消")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "连接监控异常", e)
                    delay(5000L)
                }
            }
        }
    }

    /**
     * 连接恢复时的处理
     */
    private fun onConnectionRestored() {
        Log.i(TAG, "连接已恢复，重新启动上传循环")
        connectionErrorCount = 0 // 重置错误计数

        // 如果上传循环没有运行，重新启动它
        if (isRunning.get() && isPaused.get()) {
            val hasPauseReasons = synchronized(uploadPauseReasons) {
                uploadPauseReasons.isNotEmpty()
            }
            if (!hasPauseReasons) {
                isPaused.set(false)
                startUploadLoop()
            }
        }

        // 重新处理失败的数据包
        managerScope.launch {
            retryFailedPackets()
        }
    }

    /**
     * 尝试重新连接
     */
    private fun attemptReconnect() {
        val endpoint = lastServerEndpoint
        if (endpoint != null) {
            managerScope.launch {
                Log.i(TAG, "尝试重新连接到服务器: $endpoint")
                if (uploader.connect(endpoint)) {
                    Log.i(TAG, "重新连接成功")
                    onConnectionRestored()
                } else {
                    Log.e(TAG, "重新连接失败")
                }
            }
        }
    }

    /**
     * 检查是否连接
     */
    private fun isConnected(): Boolean {
        val status = uploader.getConnectionStatus()
        return status.state == ConnectionStatus.CONNECTED.name
    }

    /**
     * 重试失败的数据包
     */
    private suspend fun retryFailedPackets() {
        try {
            // 使用getPendingPackets获取失败的数据包（包含FAILED状态）
            val failedPackets = fileQueueManager.getPendingPackets(ACK_RETRY_MS)
                .filter { it.status == com.continuousauth.database.BatchStatus.FAILED }
                .take(UPLOAD_BATCH_SIZE)
            if (failedPackets.isNotEmpty()) {
                Log.i(TAG, "开始重试 ${failedPackets.size} 个失败的数据包")

                for (packetMeta in failedPackets) {
                    if (!isConnected()) {
                        Log.w(TAG, "重试过程中连接断开，停止重试")
                        break
                    }

                    val bytes = fileQueueManager.readDataPacket(packetMeta.packetId).getOrNull()
                    if (bytes != null) {
                        val packet = runCatching { DataPacket.parseFrom(bytes) }.getOrNull()
                        if (packet != null && uploader.sendDataPacket(packet)) {
                            // 重试成功，更新状态为已上传
                            fileQueueManager.updateUploadStatus(
                                packetId = packet.packetId,
                                status = com.continuousauth.database.BatchStatus.UPLOADED,
                                uploadTime = System.currentTimeMillis()
                            )
                            Log.d(TAG, "数据包重试成功: ${packet.packetId}")
                        }
                    }
                }

                Log.i(TAG, "失败数据包重试完成")
            }
        } catch (e: Exception) {
            Log.e(TAG, "重试失败数据包异常", e)
        }
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
                directive.hasAuthResult() -> {
                    _authResultFlow.emit(directive.authResult)
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

    fun setSmartTransmissionEnabled(enabled: Boolean) {
        val previous = smartTransmissionEnabled.getAndSet(enabled)
        if (!enabled) {
            smartTransmissionUploadAllowed.set(true)
        }
        if (previous != enabled) {
            Log.i(TAG, "智能传输开关: $enabled")
        }
    }

    fun setSmartTransmissionUploadAllowed(allowed: Boolean) {
        if (!smartTransmissionEnabled.get()) {
            return
        }
        val previous = smartTransmissionUploadAllowed.getAndSet(allowed)
        if (previous != allowed) {
            Log.i(TAG, "智能传输上传窗口: ${if (allowed) "允许上传" else "仅落盘"}")
        }
    }

    fun isSmartTransmissionEnabled(): Boolean = smartTransmissionEnabled.get()

    fun isSmartTransmissionUploadAllowed(): Boolean = smartTransmissionUploadAllowed.get()
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
    suspend fun retryPacket(packet: DataPacket): RetryResult {
        Log.i(TAG, "开始重试数据包: ${packet.packetId}")

        // 检查上传管理器是否正在运行
        if (!isRunning.get()) {
            Log.w(TAG, "上传管理器未运行，无法重试数据包")
            return RetryResult(
                packetId = packet.packetId,
                success = false,
                error = "UPLOAD_MANAGER_NOT_RUNNING",
                retryCount = 0
            )
        }
        // 检查是否处于暂停状态
        if (isPaused.get()) {
            Log.w(TAG, "上传已暂停，无法重试数据包")
            return RetryResult(
                packetId = packet.packetId,
                success = false,
                error = "UPLOAD_PAUSED",
                retryCount = 0
            )
        }
        // 检查WiFi-only模式
        if (isWifiOnlyMode && !networkEnvironmentDetector.isWifiConnected()) {
            Log.w(TAG, "WiFi-only模式启用，当前非WiFi网络，无法重试数据包")
            return RetryResult(
                packetId = packet.packetId,
                success = false,
                error = "NOT_WIFI_NETWORK",
                retryCount = 0
            )
        }
        // 检查服务器连接状态
        val connectionStatus = uploader.getConnectionStatus()
        if (connectionStatus.state != "CONNECTED") {
            Log.w(TAG, "服务器连接状态异常: ${connectionStatus.state}，无法重试数据包")
            return RetryResult(
                packetId = packet.packetId,
                success = false,
                error = "SERVER_NOT_CONNECTED",
                retryCount = 0
            )
        }
        // 执行重试逻辑
        return try {
            // 尝试发送数据包
            val success = uploader.sendDataPacket(packet)

            if (success) {
                Log.i(TAG, "数据包重试成功: ${packet.packetId}")

                // 更新磁盘队列状态为已上传（等待ACK）
                fileQueueManager.updateUploadStatus(
                    packetId = packet.packetId,
                    status = com.continuousauth.database.BatchStatus.UPLOADED,
                    uploadTime = System.currentTimeMillis()
                )

                // 增加上传计数
                uploadedPackets.incrementAndGet()

                RetryResult(
                    packetId = packet.packetId,
                    success = true,
                    error = null,
                    retryCount = 1
                )
            } else {
                Log.w(TAG, "数据包重试失败: ${packet.packetId}")

                // 更新失败状态
                fileQueueManager.updateFailedStatus(
                    packetId = packet.packetId,
                    error = "RETRY_FAILED"
                )

                RetryResult(
                    packetId = packet.packetId,
                    success = false,
                    error = "SEND_FAILED",
                    retryCount = 1
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "数据包重试异常: ${packet.packetId}", e)

            // 更新失败状态
            fileQueueManager.updateFailedStatus(
                packetId = packet.packetId,
                error = "RETRY_EXCEPTION: ${e.message}"
            )

            RetryResult(
                packetId = packet.packetId,
                success = false,
                error = "EXCEPTION: ${e.message}",
                retryCount = 1
            )
        }
    }
    /**
     * 批量重试数据包
     */
    suspend fun retryPackets(packets: List<DataPacket>): BatchRetryResult {
        Log.i(TAG, "开始批量重试数据包，数量: ${packets.size}")

        val results = mutableListOf<RetryResult>()
        var successCount = 0
        var failedCount = 0

        for (packet in packets) {
            val result = retryPacket(packet)
            results.add(result)

            if (result.success) {
                successCount++
            } else {
                failedCount++
            }
        }

        Log.i(TAG, "批量重试完成 - 成功: $successCount, 失败: $failedCount")

        return BatchRetryResult(
            totalPackets = packets.size,
            successCount = successCount,
            failedCount = failedCount,
            results = results
        )
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
    val isPaused: Boolean,
    val connectionStatus: ConnectionStatus,
    val uploadedPackets: Long,
    val bufferedPackets: Int,
    val connectionStats: ConnectionStats,
    val fileQueueStats: com.continuousauth.storage.QueueStats? = null
)
