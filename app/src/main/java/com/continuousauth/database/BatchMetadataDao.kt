package com.continuousauth.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 批次元数据DAO接口
 * 提供数据库访问方法
 */
@Dao
interface BatchMetadataDao {
    
    /**
     * 插入新的批次元数据
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(batch: BatchMetadata)
    
    /**
     * 批量插入批次元数据
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vararg batches: BatchMetadata)
    
    /**
     * 更新批次元数据
     */
    @Update
    suspend fun update(batch: BatchMetadata)
    
    /**
     * 更新批次状态
     */
    @Query("UPDATE batch_metadata SET status = :status WHERE packetId = :packetId")
    suspend fun updateStatus(packetId: String, status: BatchStatus)
    
    /**
     * 更新批次状态和上传时间
     */
    @Query("UPDATE batch_metadata SET status = :status, uploadTime = :uploadTime WHERE packetId = :packetId")
    suspend fun updateUploadStatus(packetId: String, status: BatchStatus, uploadTime: Long)
    
    /**
     * 更新ACK确认
     */
    @Query("UPDATE batch_metadata SET status = :status, ackTime = :ackTime WHERE packetId = :packetId")
    suspend fun updateAckStatus(packetId: String, status: BatchStatus = BatchStatus.ACKNOWLEDGED, ackTime: Long)
    
    /**
     * 更新重试信息
     */
    @Query("UPDATE batch_metadata SET retryCount = retryCount + 1, lastError = :error WHERE packetId = :packetId")
    suspend fun updateRetryInfo(packetId: String, error: String)
    
    /**
     * 删除批次元数据
     */
    @Delete
    suspend fun delete(batch: BatchMetadata)
    
    /**
     * 根据ID删除批次
     */
    @Query("DELETE FROM batch_metadata WHERE packetId = :packetId")
    suspend fun deleteById(packetId: String)
    
    /**
     * 删除已确认的批次
     */
    @Query("DELETE FROM batch_metadata WHERE status = :status")
    suspend fun deleteByStatus(status: BatchStatus = BatchStatus.ACKNOWLEDGED)
    
    /**
     * 删除最旧的N个批次（用于容量管理）
     */
    @Query("DELETE FROM batch_metadata WHERE packetId IN (SELECT packetId FROM batch_metadata ORDER BY createdTime ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)
    
    /**
     * 获取所有待处理的批次（PENDING或FAILED）
     */
    @Query("SELECT * FROM batch_metadata WHERE status IN (:statuses) ORDER BY createdTime ASC")
    suspend fun getPendingBatches(statuses: List<BatchStatus> = listOf(BatchStatus.PENDING, BatchStatus.FAILED)): List<BatchMetadata>
    
    /**
     * 获取单个批次元数据
     */
    @Query("SELECT * FROM batch_metadata WHERE packetId = :packetId")
    suspend fun getById(packetId: String): BatchMetadata?
    
    /**
     * 获取所有批次（按时间倒序）
     */
    @Query("SELECT * FROM batch_metadata ORDER BY createdTime DESC")
    fun getAllBatches(): Flow<List<BatchMetadata>>
    
    /**
     * 获取批次总数
     */
    @Query("SELECT COUNT(*) FROM batch_metadata")
    suspend fun getTotalCount(): Int
    
    /**
     * 获取不同状态的批次数量
     */
    @Query("SELECT status, COUNT(*) as count FROM batch_metadata GROUP BY status")
    suspend fun getStatusCounts(): List<StatusCount>
    
    /**
     * 获取总文件大小
     */
    @Query("SELECT SUM(fileSize) FROM batch_metadata WHERE status != :excludeStatus")
    suspend fun getTotalFileSize(excludeStatus: BatchStatus = BatchStatus.ACKNOWLEDGED): Long?
    
    /**
     * 获取最旧的批次
     */
    @Query("SELECT * FROM batch_metadata ORDER BY createdTime ASC LIMIT 1")
    suspend fun getOldestBatch(): BatchMetadata?
    
    /**
     * 清空表
     */
    @Query("DELETE FROM batch_metadata")
    suspend fun clearAll()
}

/**
 * 状态计数数据类
 */
data class StatusCount(
    val status: BatchStatus,
    val count: Int
)
