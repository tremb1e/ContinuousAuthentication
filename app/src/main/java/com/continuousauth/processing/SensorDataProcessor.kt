package com.continuousauth.processing

import com.continuousauth.chunking.ChunkingManager
import com.continuousauth.compression.CompressionManager
import com.continuousauth.crypto.AADBuilder
import com.continuousauth.crypto.CryptoBox
import com.continuousauth.data.DataPacketBuilder
import com.continuousauth.model.SensorSample
import com.continuousauth.proto.DataPacket
import com.continuousauth.storage.FileQueueManager
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 传感器数据处理器
 * 负责接收传感器数据流，序列化 -> 压缩 -> 加密 -> 分片，并输出加密后的数据包
 * 符合 Epic 1.4.3 要求：先序列化 -> 压缩 -> 再加密
 * 符合 Epic 1.4.4 要求：支持数据分片
 */
@Singleton
class SensorDataProcessor @Inject constructor(
    private val cryptoBox: CryptoBox,
    private val envelopeCryptoBox: com.continuousauth.crypto.EnvelopeCryptoBox,
    private val aadBuilder: AADBuilder,
    private val dataPacketBuilder: DataPacketBuilder,
    private val compressionManager: CompressionManager,
    private val chunkingManager: ChunkingManager,
    private val fileQueueManager: FileQueueManager
) {
    
    companion object {
        private const val TAG = "SensorDataProcessor"
    }
    
    // 内部状态
    private val isProcessing = AtomicBoolean(false)
    private val processedCount = AtomicLong(0L)
    private val encryptedPacketChannel = Channel<DataPacket>(Channel.UNLIMITED)
    
    // 协程作用域
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var processingJob: Job? = null
    
    // 当前会话信息
    private var currentUserId = ""
    private var currentSessionId = ""
    
    /**
     * 开始处理传感器数据流
     */
    suspend fun startProcessing(
        sensorDataFlow: Flow<SensorSample>,
        userId: String,
        sessionId: String = UUID.randomUUID().toString()
    ): Boolean {
        if (isProcessing.get()) {
            android.util.Log.w(TAG, "数据处理已在运行中")
            return true
        }
        
        // 初始化加密系统
        if (!cryptoBox.initialize()) {
            android.util.Log.e(TAG, "加密系统初始化失败")
            return false
        }
        
        currentUserId = userId
        currentSessionId = sessionId
        
        // 启动数据处理协程
        processingJob = processingScope.launch {
            processSensorDataFlow(sensorDataFlow)
        }
        
        isProcessing.set(true)
        android.util.Log.i(TAG, "传感器数据处理已启动 - 会话ID: $sessionId")
        
        return true
    }
    
    /**
     * 停止数据处理
     */
    suspend fun stopProcessing() {
        if (!isProcessing.get()) {
            return
        }
        
        processingJob?.cancel()
        processingJob?.join()
        
        isProcessing.set(false)
        
        android.util.Log.i(TAG, "传感器数据处理已停止 - 总处理: ${processedCount.get()} 个样本")
    }
    
    /**
     * 获取加密后的数据包流
     */
    fun getEncryptedPacketFlow(): Flow<DataPacket> {
        return encryptedPacketChannel.receiveAsFlow()
    }
    
    /**
     * 获取处理状态
     */
    fun getProcessingStatus(): ProcessingStatus {
        return ProcessingStatus(
            isProcessing = isProcessing.get(),
            processedSampleCount = processedCount.get(),
            currentSessionId = currentSessionId
        )
    }
    
    /**
     * 处理传感器数据流的核心逻辑
     */
    private suspend fun processSensorDataFlow(sensorDataFlow: Flow<SensorSample>) {
        try {
            // 收集传感器样本进行批处理
            sensorDataFlow
                .buffer() // 使用缓冲区避免背压
                .chunked() // 按传输模式决定批次大小
                .collect { sampleBatch ->
                    if (sampleBatch.isNotEmpty()) {
                        processSampleBatch(sampleBatch)
                        processedCount.addAndGet(sampleBatch.size.toLong())
                    }
                }
        } catch (e: CancellationException) {
            android.util.Log.i(TAG, "数据处理被取消")
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "数据处理异常", e)
        }
    }
    
    /**
     * 处理一批传感器样本
     * Epic 1.4.3: 先序列化 -> 压缩 -> 再加密
     */
    private suspend fun processSampleBatch(samples: List<SensorSample>) {
        try {
            // 1. 构建传感器批次数据 (明文) - 序列化
            val sensorBatch = dataPacketBuilder.buildSensorBatch(
                sensorSamples = samples,
                userId = currentUserId,
                sessionId = currentSessionId
            )
            
            val sensorBatchBytes = sensorBatch.toByteArray()
            
            // 2. 压缩数据 (Epic 1.4.3 要求)
            val compressionType = CompressionManager.CompressionType.LZ4 // 默认使用LZ4压缩
            
            val compressedData = compressionManager.compress(sensorBatchBytes, compressionType)
            if (compressedData == null) {
                android.util.Log.e(TAG, "数据压缩失败，跳过此批次")
                return
            }
            
            // 3. 生成包ID并构建AAD
            val packetId = UUID.randomUUID().toString()
            val appVersion = getAppVersion()
            val packetSeqNo = envelopeCryptoBox.getNextPacketSeqNo()
            val dekKeyId = envelopeCryptoBox.getDekKeyId()
            
            val aad = aadBuilder.buildAAD(
                packetId = packetId,
                packetSeqNo = packetSeqNo,
                dekKeyId = dekKeyId,
                appVersion = appVersion,
                sampleCount = samples.size,
                keyVersion = dekKeyId
            )
            
            // 4. 加密压缩后的数据
            val encryptedPayload = cryptoBox.encrypt(compressedData, aad)
            
            if (encryptedPayload == null) {
                android.util.Log.e(TAG, "批次加密失败 - 包ID: $packetId")
                return
            }
            
            // 5. 使用共享对称密钥，无需发送单独的 DEK
            val encryptedDek: ByteArray? = null
            
            // 6. 计算SHA256校验和
            val sha256 = calculateSha256(encryptedPayload)
            
            // 7. 构建最终的数据包（包含压缩信息）
            val dataPacket = dataPacketBuilder.buildDataPacket(
                sensorSamples = samples,
                encryptedPayload = encryptedPayload,
                packetSeqNo = packetSeqNo,
                userId = currentUserId,
                sessionId = currentSessionId,
                encryptedDek = encryptedDek,
                dekKeyId = dekKeyId,
                sha256 = sha256,
                compressionType = compressionManager.getCompressionTypeString(compressionType)
            )
            
            // 8. 检查是否需要分片（Epic 1.4.4）
            val packets = if (chunkingManager.needsChunking(encryptedPayload)) {
                // 需要分片
                val chunkedPackets = chunkingManager.processPacketChunking(dataPacket)
                android.util.Log.i(TAG, "数据包需要分片，分为 ${chunkedPackets.size} 个分片")
                chunkedPackets
            } else {
                // 不需要分片
                listOf(dataPacket)
            }
            
            // 8a. 持久化到磁盘队列，便于统计与断点续传
            persistPackets(packets, samples.size)
            
            // 9. 发送到输出流（可能是多个分片）
            packets.forEach { packet ->
                encryptedPacketChannel.trySend(packet)
                val chunkInfo = chunkingManager.getChunkInfo(packet)
                android.util.Log.v(TAG, "数据包已发送 - $chunkInfo")
            }
            
            android.util.Log.d(TAG, "批次处理完成 - 包ID: $packetId, 样本数: ${samples.size}, " +
                    "压缩类型: ${compressionManager.getCompressionTypeString(compressionType)}, " +
                    "分片数: ${packets.size}")
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "处理批次失败", e)
        }
    }
    
    /**
     * 将数据流按时间窗口分块 (默认1秒窗口)
     */
    private fun <T> Flow<T>.chunked(): Flow<List<T>> {
        // 根据规范，使用1秒窗口
        return this.chunked(1000) // 1秒
    }
    
    /**
     * 按时间窗口分块数据流
     */
    private fun <T> Flow<T>.chunked(windowMillis: Long): Flow<List<T>> = flow {
        val buffer = mutableListOf<T>()
        var lastEmitTime = System.currentTimeMillis()
        
        collect { item ->
            buffer.add(item)
            val currentTime = System.currentTimeMillis()
            
            if (currentTime - lastEmitTime >= windowMillis) {
                if (buffer.isNotEmpty()) {
                    emit(buffer.toList())
                    buffer.clear()
                }
                lastEmitTime = currentTime
            }
        }
        
        // 发送剩余数据
        if (buffer.isNotEmpty()) {
            emit(buffer.toList())
        }
    }
    
    /**
     * 获取应用版本（简化版本）
     */
    private fun getAppVersion(): String {
        return "1.0.0" // 在实际实现中应该从PackageManager获取
    }
    
    /**
     * 获取设备ID（简化版本）
     */
    private fun getDeviceId(): String {
        return "device_${System.currentTimeMillis()}" // 在实际实现中应该使用真实的设备ID
    }
    
    /**
     * 计算数据的SHA256哈希值
     */
    private fun calculateSha256(data: ByteArray): ByteArray {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.digest(data)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "计算SHA256失败", e)
            ByteArray(0)
        }
    }

    /**
    * 将数据包写入文件队列，便于恢复与统计
    */
    private suspend fun persistPackets(packets: List<DataPacket>, sampleCount: Int) {
        packets.forEach { packet ->
            try {
                val packetBytes = packet.toByteArray()
                val metadata = com.continuousauth.database.BatchMetadata(
                    packetId = packet.packetId,
                    filePath = "",
                    status = com.continuousauth.database.BatchStatus.PENDING,
                    createdTime = System.currentTimeMillis(),
                    uploadTime = null,
                    ackTime = null,
                    fileSize = packetBytes.size.toLong(),
                    sampleCount = sampleCount,
                    transmissionMode = "STANDARD",
                    ntpOffset = if (packet.hasNtpOffsetMs()) packet.ntpOffsetMs else null,
                    baseWallMs = packet.baseWallMs,
                    deviceUptimeNs = packet.deviceUptimeNs,
                    retryCount = 0,
                    lastError = null,
                    sequenceNumber = packet.packetSeqNo,
                    userId = currentUserId,
                    sessionId = currentSessionId,
                    deviceId = packet.deviceIdHash,
                    sha256 = null
                )

                val result = fileQueueManager.saveDataPacket(
                    packet.packetId,
                    packetBytes,
                    metadata
                )

                if (result.isFailure) {
                    android.util.Log.e(TAG, "持久化数据包失败: ${packet.packetId}", result.exceptionOrNull())
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "持久化数据包异常: ${packet.packetId}", e)
            }
        }
    }
}

/**
 * 数据处理状态
 */
data class ProcessingStatus(
    val isProcessing: Boolean,
    val processedSampleCount: Long,
    val currentSessionId: String
)
