package com.continuousauth.storage

import android.content.Context
import android.util.Log
import com.continuousauth.database.BatchMetadata
import com.continuousauth.database.BatchMetadataDao
import com.continuousauth.database.BatchStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件队列管理器
 * 管理加密数据包的文件存储和队列操作
 */
@Singleton
class FileQueueManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val batchMetadataDao: BatchMetadataDao
) {
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO)
    
    companion object {
        private const val TAG = "FileQueueManager"
        private const val QUEUE_DIR = "data_queue"
        private const val MAX_QUEUE_SIZE_MB = 200  // 最大队列大小：200MB
        private const val MAX_QUEUE_SIZE_BYTES = MAX_QUEUE_SIZE_MB * 1024 * 1024L
        private const val MIN_FREE_SPACE_MB = 50   // 最小剩余空间：50MB
        private const val MIN_FREE_SPACE_BYTES = MIN_FREE_SPACE_MB * 1024 * 1024L
        private const val CLEANUP_THRESHOLD = 0.9f  // 清理阈值：90%
    }
    
    // 队列目录
    private val queueDir: File by lazy {
        File(context.cacheDir, QUEUE_DIR).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    
    // 队列状态
    private val _queueStats = MutableStateFlow(QueueStats())
    val queueStats: StateFlow<QueueStats> = _queueStats.asStateFlow()
    
    // 是否正在清理
    private var isCleaningUp = false
    
    init {
        // 启动时更新统计信息
        scope.launch {
            updateQueueStats()
        }
    }
    
    /**
     * 保存加密数据包到文件队列
     * 使用原子写入：tmp -> fsync -> rename
     */
    suspend fun saveDataPacket(
        packetId: String,
        encryptedData: ByteArray,
        metadata: BatchMetadata
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // 检查磁盘空间
            if (!hasEnoughSpace(encryptedData.size.toLong())) {
                // 尝试清理空间
                performCleanup()
                
                // 再次检查
                if (!hasEnoughSpace(encryptedData.size.toLong())) {
                    return@withContext Result.failure(
                        InsufficientStorageException("磁盘空间不足")
                    )
                }
            }
            
            // 计算SHA256
            val sha256 = calculateSHA256(encryptedData)
            
            // 创建临时文件和最终文件
            val tmpFile = File(queueDir, "$packetId.tmp")
            val finalFile = File(queueDir, "$packetId.dat")
            
            // 原子写入：先写入临时文件
            try {
                FileOutputStream(tmpFile).use { fos ->
                    fos.write(encryptedData)
                    // fsync 确保数据写入磁盘
                    fos.fd.sync()
                }
                
                // 重命名为最终文件（原子操作）
                if (!tmpFile.renameTo(finalFile)) {
                    throw Exception("Failed to rename temp file to final file")
                }
            } catch (e: Exception) {
                // 清理临时文件
                tmpFile.delete()
                throw e
            }
            
            // 更新元数据，包含SHA256
            val updatedMetadata = metadata.copy(
                filePath = finalFile.absolutePath,
                fileSize = finalFile.length(),
                sha256 = sha256
            )
            
            // 保存到数据库
            batchMetadataDao.insert(updatedMetadata)
            
            // 更新统计
            updateQueueStats()
            
            Log.i(TAG, "数据包已保存: $packetId, 大小: ${finalFile.length()} bytes, SHA256: $sha256")
            
            Result.success(finalFile)
            
        } catch (e: Exception) {
            Log.e(TAG, "保存数据包失败: $packetId", e)
            Result.failure(e)
        }
    }
    
    /**
     * 读取加密数据包
     * 读取时验证SHA256校验和
     */
    suspend fun readDataPacket(packetId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            // 从数据库获取元数据
            val metadata = batchMetadataDao.getById(packetId)
                ?: return@withContext Result.failure(
                    FileNotFoundException("未找到数据包: $packetId")
                )
            
            // 检查文件是否已标记为损坏
            if (metadata.status == BatchStatus.CORRUPT) {
                return@withContext Result.failure(
                    Exception("数据包已损坏: $packetId")
                )
            }
            
            // 读取文件
            val file = File(metadata.filePath)
            if (!file.exists()) {
                return@withContext Result.failure(
                    FileNotFoundException("文件不存在: ${metadata.filePath}")
                )
            }
            
            val data = file.readBytes()
            
            // 验证SHA256
            if (metadata.sha256 != null) {
                val calculatedSha256 = calculateSHA256(data)
                if (calculatedSha256 != metadata.sha256) {
                    // 标记为损坏
                    batchMetadataDao.updateStatus(packetId, BatchStatus.CORRUPT)
                    Log.e(TAG, "SHA256校验失败: $packetId, 期望: ${metadata.sha256}, 实际: $calculatedSha256")
                    return@withContext Result.failure(
                        Exception("SHA256校验失败，数据包已损坏")
                    )
                }
            }
            
            Result.success(data)
            
        } catch (e: Exception) {
            Log.e(TAG, "读取数据包失败: $packetId", e)
            Result.failure(e)
        }
    }
    
    /**
     * 删除数据包
     */
    suspend fun deleteDataPacket(packetId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 从数据库获取元数据
            val metadata = batchMetadataDao.getById(packetId) ?: return@withContext false
            
            // 删除文件
            val file = File(metadata.filePath)
            if (file.exists()) {
                file.delete()
            }
            
            // 删除数据库记录
            batchMetadataDao.deleteById(packetId)
            
            // 更新统计
            updateQueueStats()
            
            Log.i(TAG, "数据包已删除: $packetId")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "删除数据包失败: $packetId", e)
            false
        }
    }
    
    /**
     * 更新数据包ACK状态
     * 记录服务器确认时间戳
     */
    suspend fun updateAckStatus(
        packetId: String, 
        serverTimestamp: Long
    ) = withContext(Dispatchers.IO) {
        try {
            // 更新数据库状态为ACKNOWLEDGED，并记录服务器时间戳
            batchMetadataDao.updateAckStatus(
                packetId = packetId,
                status = BatchStatus.ACKNOWLEDGED,
                ackTime = serverTimestamp
            )
            Log.d(TAG, "数据包ACK状态已更新: $packetId, 服务器时间戳: $serverTimestamp")
            
        } catch (e: Exception) {
            Log.e(TAG, "更新ACK状态失败: $packetId", e)
        }
    }
    
    /**
     * 更新数据包失败状态
     */
    suspend fun updateFailedStatus(
        packetId: String,
        error: String
    ) = withContext(Dispatchers.IO) {
        try {
            // 更新状态为失败
            batchMetadataDao.updateStatus(packetId, BatchStatus.FAILED)
            // 更新重试信息
            batchMetadataDao.updateRetryInfo(packetId, error)
            updateQueueStats()
            Log.d(TAG, "数据包状态已标记为失败: $packetId, 错误: $error")
            
        } catch (e: Exception) {
            Log.e(TAG, "更新失败状态失败: $packetId", e)
        }
    }

    /**
     * 更新上传状态（已推送但未ACK）
     */
    suspend fun updateUploadStatus(
        packetId: String,
        status: BatchStatus = BatchStatus.UPLOADED,
        uploadTime: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        try {
            batchMetadataDao.updateUploadStatus(packetId, status, uploadTime)
            updateQueueStats()
            Log.d(TAG, "数据包上传状态已更新: $packetId -> $status")
        } catch (e: Exception) {
            Log.e(TAG, "更新上传状态失败: $packetId", e)
        }
    }
    
    /**
     * 更新重试信息
     */
    suspend fun updateRetryInfo(
        packetId: String,
        error: String
    ) = withContext(Dispatchers.IO) {
        try {
            batchMetadataDao.updateRetryInfo(packetId, error)
            Log.d(TAG, "数据包重试信息已更新: $packetId, 错误: $error")
            
        } catch (e: Exception) {
            Log.e(TAG, "更新重试信息失败: $packetId", e)
        }
    }
    
    /**
     * 批量删除已确认的数据包
     */
    suspend fun deleteAcknowledgedPackets() = withContext(Dispatchers.IO) {
        try {
            // 获取所有已确认的批次
            val acknowledgedBatches = batchMetadataDao.getPendingBatches(
                listOf(BatchStatus.ACKNOWLEDGED)
            )
            
            var deletedCount = 0
            acknowledgedBatches.forEach { batch ->
                val file = File(batch.filePath)
                if (file.exists()) {
                    file.delete()
                    deletedCount++
                }
            }
            
            // 批量删除数据库记录
            batchMetadataDao.deleteByStatus(BatchStatus.ACKNOWLEDGED)
            
            // 更新统计
            updateQueueStats()
            
            Log.i(TAG, "已删除 $deletedCount 个已确认数据包")
            
        } catch (e: Exception) {
            Log.e(TAG, "删除已确认数据包失败", e)
        }
    }
    
    /**
     * 获取待上传的数据包列表
     */
    suspend fun getPendingPackets(retryUploadedAfterMs: Long = 0L): List<BatchMetadata> = withContext(Dispatchers.IO) {
        try {
            val pending = batchMetadataDao.getPendingBatches(
                listOf(BatchStatus.PENDING, BatchStatus.FAILED)
            ).toMutableList()

            if (retryUploadedAfterMs > 0) {
                val cutoff = System.currentTimeMillis() - retryUploadedAfterMs
                val awaitingAck = batchMetadataDao.getPendingBatches(
                    listOf(BatchStatus.UPLOADED)
                ).filter { (it.uploadTime ?: 0L) <= cutoff }
                pending.addAll(awaitingAck)
            }

            pending
        } catch (e: Exception) {
            Log.e(TAG, "获取待上传数据包失败", e)
            emptyList()
        }
    }
    
    /**
     * 执行队列清理
     */
    private suspend fun performCleanup() = withContext(Dispatchers.IO) {
        if (isCleaningUp) {
            return@withContext
        }
        
        isCleaningUp = true
        
        try {
            Log.i(TAG, "开始清理队列")
            
            // 获取当前队列大小
            val currentSize = getQueueSizeBytes()
            
            // 计算需要清理的大小
            val targetSize = (MAX_QUEUE_SIZE_BYTES * 0.7).toLong()  // 清理到70%
            val needToFree = currentSize - targetSize
            
            if (needToFree <= 0) {
                return@withContext
            }
            
            // 获取所有批次，按时间排序
            val allBatches = batchMetadataDao.getPendingBatches(
                BatchStatus.values().toList()
            ).sortedBy { it.createdTime }
            
            var freedSize = 0L
            val toDelete = mutableListOf<BatchMetadata>()
            
            // 优先删除已确认的，然后是失败的，最后是待处理的
            val priorityOrder = listOf(
                BatchStatus.ACKNOWLEDGED,
                BatchStatus.DISCARDED,
                BatchStatus.FAILED,
                BatchStatus.PENDING
            )
            
            for (status in priorityOrder) {
                val batchesOfStatus = allBatches.filter { it.status == status }
                
                for (batch in batchesOfStatus) {
                    if (freedSize >= needToFree) {
                        break
                    }
                    
                    toDelete.add(batch)
                    freedSize += batch.fileSize
                }
                
                if (freedSize >= needToFree) {
                    break
                }
            }
            
            // 执行删除
            toDelete.forEach { batch ->
                val file = File(batch.filePath)
                if (file.exists()) {
                    file.delete()
                }
                batchMetadataDao.updateStatus(batch.packetId, BatchStatus.DISCARDED)
            }
            
            Log.i(TAG, "队列清理完成，删除了 ${toDelete.size} 个数据包，释放了 $freedSize bytes")
            
            // 更新统计
            updateQueueStats()
            
        } catch (e: Exception) {
            Log.e(TAG, "队列清理失败", e)
        } finally {
            isCleaningUp = false
        }
    }
    
    /**
     * 检查是否有足够的空间
     */
    private fun hasEnoughSpace(dataSize: Long): Boolean {
        val currentQueueSize = getQueueSizeBytes()
        val freeSpace = queueDir.freeSpace
        
        // 检查队列大小限制
        if (currentQueueSize + dataSize > MAX_QUEUE_SIZE_BYTES) {
            return false
        }
        
        // 检查磁盘剩余空间
        if (freeSpace - dataSize < MIN_FREE_SPACE_BYTES) {
            return false
        }
        
        return true
    }
    
    /**
     * 获取队列大小（字节）
     */
    private fun getQueueSizeBytes(): Long {
        return queueDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
    
    /**
     * 获取队列中待处理的数据包数量
     */
    fun getQueueSize(): Int {
        return runBlocking {
            try {
                batchMetadataDao.getPendingBatches(
                    listOf(BatchStatus.PENDING, BatchStatus.UPLOADING)
                ).size
            } catch (e: Exception) {
                Log.e(TAG, "获取队列大小失败", e)
                0
            }
        }
    }
    
    /**
     * 更新队列统计信息
     */
    private suspend fun updateQueueStats() = withContext(Dispatchers.IO) {
        try {
            val totalSize = getQueueSizeBytes()
            val fileCount = queueDir.listFiles()?.size ?: 0
            val statusCounts = batchMetadataDao.getStatusCounts()
            
            val pending = statusCounts.find { it.status == BatchStatus.PENDING }?.count ?: 0
            val uploading = statusCounts.find { it.status == BatchStatus.UPLOADING }?.count ?: 0
            val uploaded = statusCounts.find { it.status == BatchStatus.ACKNOWLEDGED }?.count ?: 0
            val pendingAck = statusCounts.find { it.status == BatchStatus.UPLOADED }?.count ?: 0
            val failed = statusCounts.find { it.status == BatchStatus.FAILED }?.count ?: 0
            val corrupted = statusCounts.find { it.status == BatchStatus.CORRUPT }?.count ?: 0
            val discarded = statusCounts.find { it.status == BatchStatus.DISCARDED }?.count ?: 0
            val total = pending + uploading + uploaded + failed + corrupted + pendingAck + discarded
            val pendingTotal = pending + failed + pendingAck
            
            _queueStats.value = QueueStats(
                totalSizeBytes = totalSize,
                fileCount = fileCount,
                pendingCount = pendingTotal,
                uploadingCount = uploading,
                failedCount = failed,
                acknowledgedCount = uploaded,
                queueUsagePercent = (totalSize.toFloat() / MAX_QUEUE_SIZE_BYTES * 100).coerceIn(0f, 100f),
                totalPackets = total,
                pendingPackets = pendingTotal,
                uploadedPackets = uploaded + pendingAck,
                corruptedPackets = corrupted
            )
        } catch (e: Exception) {
            Log.e(TAG, "更新队列统计失败", e)
        }
    }
    
    /**
     * 清空整个队列
     */
    suspend fun clearQueue() = withContext(Dispatchers.IO) {
        try {
            // 删除所有文件
            queueDir.listFiles()?.forEach { it.delete() }
            
            // 清空数据库
            batchMetadataDao.clearAll()
            
            // 更新统计
            updateQueueStats()
            
            Log.i(TAG, "队列已清空")
            
        } catch (e: Exception) {
            Log.e(TAG, "清空队列失败", e)
        }
    }
    
    /**
     * 获取队列统计详情
     */
    suspend fun getQueueStatistics(): QueueStatisticsDetail = withContext(Dispatchers.IO) {
        try {
            val totalSize = getQueueSizeBytes()
            val fileCount = queueDir.listFiles()?.size ?: 0
            val statusCounts = batchMetadataDao.getStatusCounts()
            
            val pending = statusCounts.find { it.status == BatchStatus.PENDING }?.count ?: 0
            val uploaded = statusCounts.find { it.status == BatchStatus.ACKNOWLEDGED }?.count ?: 0
            val pendingAck = statusCounts.find { it.status == BatchStatus.UPLOADED }?.count ?: 0
            val failed = statusCounts.find { it.status == BatchStatus.FAILED }?.count ?: 0
            val corrupted = statusCounts.find { it.status == BatchStatus.CORRUPT }?.count ?: 0
            val discarded = statusCounts.find { it.status == BatchStatus.DISCARDED }?.count ?: 0
            val total = pending + uploaded + failed + corrupted + pendingAck + discarded
            
            QueueStatisticsDetail(
                pendingPackets = pending + failed + pendingAck,
                totalSent = uploaded.toLong(),
                totalFailed = failed.toLong(),
                totalDiscarded = discarded.toLong(),
                totalSizeMB = totalSize / (1024f * 1024f),
                totalPackets = total,
                uploadedPackets = uploaded + pendingAck,
                corruptedPackets = corrupted,
                totalSizeBytes = totalSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取队列统计详情失败", e)
            QueueStatisticsDetail()
        }
    }
    
    /**
     * 清空所有待处理的数据包
     */
    suspend fun clearAllPendingPackets() = withContext(Dispatchers.IO) {
        try {
            // 获取所有待处理的批次
            val pendingBatches = batchMetadataDao.getPendingBatches(
                listOf(BatchStatus.PENDING, BatchStatus.FAILED)
            )
            
            // 删除文件
            pendingBatches.forEach { batch ->
                val file = File(batch.filePath)
                if (file.exists()) {
                    file.delete()
                }
            }
            
            // 更新数据库状态
            pendingBatches.forEach { batch ->
                batchMetadataDao.updateStatus(batch.packetId, BatchStatus.DISCARDED)
            }
            
            // 更新统计
            updateQueueStats()
            
            Log.i(TAG, "已清空 ${pendingBatches.size} 个待处理数据包")
            
        } catch (e: Exception) {
            Log.e(TAG, "清空待处理数据包失败", e)
        }
    }
    
    /**
     * 导出加密的待处理数据
     */
    suspend fun exportEncryptedPendingData(): String = withContext(Dispatchers.IO) {
        try {
            val pendingBatches = batchMetadataDao.getPendingBatches(
                listOf(BatchStatus.PENDING, BatchStatus.FAILED)
            )
            
            if (pendingBatches.isEmpty()) {
                return@withContext "没有待处理的数据包"
            }
            
            // 创建导出目录
            val exportDir = File(context.cacheDir, "export_${System.currentTimeMillis()}")
            exportDir.mkdirs()
            
            var exportedCount = 0
            pendingBatches.forEach { batch ->
                val sourceFile = File(batch.filePath)
                if (sourceFile.exists()) {
                    val destFile = File(exportDir, "${batch.packetId}.dat")
                    sourceFile.copyTo(destFile, overwrite = true)
                    exportedCount++
                }
            }
            
            Log.i(TAG, "已导出 $exportedCount 个加密数据包到: ${exportDir.absolutePath}")
            exportDir.absolutePath
            
        } catch (e: Exception) {
            Log.e(TAG, "导出加密数据失败", e)
            "导出失败: ${e.message}"
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        scope.cancel()
    }
    
    /**
     * 计算SHA256校验和
     */
    private fun calculateSHA256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 启用持久化
     */
    fun enablePersistence() {
        Log.i(TAG, "启用持久化")
        // TODO: 实现持久化逻辑
    }
    
    /**
     * 清理旧文件
     */
    suspend fun cleanupOldFiles() {
        Log.i(TAG, "清理旧文件")
        // TODO: 实现清理逻辑
    }
    
    /**
     * 压缩数据库
     */
    suspend fun compactDatabase() {
        Log.i(TAG, "压缩数据库")
        // TODO: 实现数据库压缩
    }
    
    /**
     * 清空待发送队列
     */
    suspend fun clearPendingQueue() {
        Log.i(TAG, "清空待发送队列")
        // TODO: 实现队列清空
    }
}

/**
 * 队列统计详情
 */
data class QueueStatisticsDetail(
    val pendingPackets: Int = 0,
    val totalSent: Long = 0L,
    val totalFailed: Long = 0L,
    val totalDiscarded: Long = 0L,
    val totalSizeMB: Float = 0f,
    val totalPackets: Int = 0,
    val uploadedPackets: Int = 0,
    val corruptedPackets: Int = 0,
    val totalSizeBytes: Long = 0L
)

/**
 * 队列统计信息
 */
data class QueueStats(
    val totalSizeBytes: Long = 0,      // 总大小（字节）
    val fileCount: Int = 0,            // 文件数量
    val pendingCount: Int = 0,         // 待上传数量
    val uploadingCount: Int = 0,       // 上传中数量
    val failedCount: Int = 0,          // 失败数量
    val acknowledgedCount: Int = 0,     // 已确认数量
    val queueUsagePercent: Float = 0f,  // 队列使用率
    val totalPackets: Int = 0,          // 总数据包数
    val pendingPackets: Int = 0,        // 待上传数据包数
    val uploadedPackets: Int = 0,       // 已上传数据包数
    val corruptedPackets: Int = 0       // 损坏的数据包数
)

/**
 * 存储空间不足异常
 */
class InsufficientStorageException(message: String) : Exception(message)

/**
 * 文件未找到异常
 */
class FileNotFoundException(message: String) : Exception(message)
