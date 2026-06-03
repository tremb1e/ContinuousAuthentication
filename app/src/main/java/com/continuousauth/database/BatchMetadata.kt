package com.continuousauth.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 批次元数据实体
 * 记录每个数据包的状态和信息
 */
@Entity(tableName = "batch_metadata")
data class BatchMetadata(
    @PrimaryKey
    val packetId: String,                  // 数据包唯一标识（UUID）
    val filePath: String,                   // 加密数据文件路径
    val status: BatchStatus,               // 批次状态
    val createdTime: Long,                 // 创建时间戳
    val uploadTime: Long? = null,          // 上传时间戳
    val ackTime: Long? = null,             // ACK确认时间戳
    val fileSize: Long,                    // 文件大小（字节）
    val sampleCount: Int,                  // 样本数量
    val transmissionMode: String,          // 传输模式（SLOW/FAST）
    val ntpOffset: Long? = null,           // NTP偏移量（毫秒）
    val baseWallMs: Long,                  // 基准UTC时间（经NTP校正）
    val deviceUptimeNs: Long,              // 设备运行时间（纳秒）
    val retryCount: Int = 0,               // 重试次数
    val lastError: String? = null,         // 最后错误信息
    val sequenceNumber: Long? = null,      // 序列号（用于排序）
    val sessionId: String? = null,         // 会话标识
    val deviceId: String,                  // 设备标识
    val sha256: String? = null             // SHA256校验和
)

/**
 * 批次状态枚举
 */
enum class BatchStatus {
    PENDING,        // 待上传
    UPLOADING,      // 上传中
    UPLOADED,       // 已上传（等待ACK）
    ACKNOWLEDGED,   // 已确认（收到ACK）
    FAILED,         // 上传失败
    DISCARDED,      // 已丢弃（超过容量限制）
    CORRUPT        // 数据损坏（SHA256校验失败）
}
