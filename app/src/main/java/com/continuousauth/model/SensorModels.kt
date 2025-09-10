package com.continuousauth.model

/**
 * 传感器类型枚举
 */
enum class SensorType {
    ACCELEROMETER,  // 加速度计
    GYROSCOPE,      // 陀螺仪  
    MAGNETOMETER    // 磁力计
}

/**
 * 传感器样本数据模型
 * 使用 event.timestamp 作为核心时间戳
 */
data class SensorSample(
    val type: SensorType,                   // 传感器类型
    val eventTimestampNs: Long,             // 相对时间戳 (纳秒，基于 SystemClock.elapsedRealtimeNanos)
    val x: Float,                          // X轴数据
    val y: Float,                          // Y轴数据  
    val z: Float,                          // Z轴数据
    val accuracy: Int,                     // 传感器精度标识
    val seqNo: Long,                       // 自增序号，保障一致性/防重放
    val foregroundApp: String = ""         // 前台应用包名
)

/**
 * 传输策略枚举
 */
enum class TransmissionProfile {
    WIFI_ONLY,      // 仅在WiFi下上传
    UNRESTRICTED    // 不限制网络类型（默认）
}