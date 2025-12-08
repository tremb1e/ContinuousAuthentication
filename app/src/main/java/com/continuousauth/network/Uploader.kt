package com.continuousauth.network

import com.continuousauth.proto.DataPacket
import com.continuousauth.proto.Heartbeat
import com.continuousauth.proto.HeartbeatAck
import com.continuousauth.proto.ServerDirective
import com.continuousauth.network.TransportState
import kotlinx.coroutines.flow.Flow

/**
 * gRPC双向流上传器接口
 * 负责与服务器进行双向流通信
 */
interface Uploader {
    
    /**
     * 开始双向流连接
     * @param serverEndpoint 服务器端点
     * @return 是否成功建立连接
     */
    suspend fun connect(serverEndpoint: String): Boolean
    
    /**
     * 发送数据包到服务器
     * @param dataPacket 要发送的数据包
     * @return 是否成功发送
     */
    suspend fun sendDataPacket(dataPacket: DataPacket): Boolean

    /**
     * 发送心跳
     */
    suspend fun sendHeartbeat(heartbeat: Heartbeat): HeartbeatAck?
    
    /**
     * 获取服务器指令流
     * @return 服务器指令的Flow
     */
    fun getServerDirectiveFlow(): Flow<ServerDirective>
    
    /**
     * 断开连接
     */
    suspend fun disconnect()
    
    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean
    
    /**
     * 获取连接统计信息
     */
    fun getConnectionStats(): ConnectionStats
    
    /**
     * 获取传输统计信息
     */
    fun getTransmissionStats(): TransmissionStats
    
    /**
     * 获取gRPC状态信息
     */
    fun getGrpcStatus(): GrpcStatus

    /**
     * 获取底层传输通道（TLS/h2c）状态
     */
    fun getTransportState(): TransportState

    /**
     * 获取底层通道状态
     */
    fun getChannelState(): String
    
    /**
     * 获取缓冲区统计信息
     */
    fun getBufferStats(): BufferStats
    
    /**
     * 获取服务器端点
     */
    fun getServerEndpoint(): String
    
    /**
     * 获取连接状态详情
     */
    fun getConnectionStatus(): ConnectionStatusDetail
    
    /**
     * 获取传输统计详情
     */
    fun getStatistics(): Statistics
    
    /**
     * 获取内存缓冲区统计
     */
    fun getMemoryBufferStats(): MemoryBufferStats
    
    /**
     * 获取最近的延迟数据
     */
    fun getRecentLatencies(): List<Long>
    
    /**
     * 获取服务器策略
     */
    suspend fun getServerPolicy(): ServerPolicy

    /**
     * 测试服务器可达性（Socket/HTTP/gRPC）
     */
    suspend fun testServerConnection(
        serverHost: String,
        serverPort: Int,
        useTls: Boolean = true,
        testGrpc: Boolean = true
    ): ServerTestResult

    /**
     * 将测试结果格式化为用户可读描述
     */
    fun getTestResultDescription(result: ServerTestResult): String
}

/**
 * 连接状态详情
 */
data class ConnectionStatusDetail(
    val state: String,
    val endpoint: String,
    val lastAckLatencyMs: Long,
    val usingTls: Boolean = false,
    val tlsVersion: String? = null,
    val negotiatedProtocol: String? = null,
    val downgradedToCleartext: Boolean = false
)

/**
 * 统计信息
 */
data class Statistics(
    val totalPacketsSent: Long,
    val totalPacketsAcknowledged: Long,
    val totalFailures: Long
)

/**
 * 内存缓冲区统计
 */
data class MemoryBufferStats(
    val samplesInMemory: Int,
    val totalSent: Long,
    val totalFailed: Long,
    val totalDiscarded: Long
)

/**
 * 服务器策略
 */
data class ServerPolicy(
    val version: String = "1.0",
    val lastUpdated: Long = System.currentTimeMillis(),
    val fastModeDurationSeconds: Int = 30,
    val anomalyThreshold: Float = 0.8f,
    val samplingRates: Map<String, Float> = mapOf(
        "ACCELEROMETER" to 100f,
        "GYROSCOPE" to 100f,
        "MAGNETOMETER" to 50f
    ),
    val transmissionStrategy: String = "ADAPTIVE"
) {
    fun toJson(): String {
        return """
            {
                "version": "$version",
                "lastUpdated": $lastUpdated,
                "fastModeDurationSeconds": $fastModeDurationSeconds,
                "anomalyThreshold": $anomalyThreshold,
                "samplingRates": {
                    ${samplingRates.entries.joinToString(",\n                    ") { 
                        "\"${it.key}\": ${it.value}"
                    }}
                },
                "transmissionStrategy": "$transmissionStrategy"
            }
        """.trimIndent()
    }
}

/**
 * 连接状态
 */
enum class ConnectionStatus {
    DISCONNECTED,       // 未连接
    CONNECTING,         // 连接中
    CONNECTED,          // 已连接
    RECONNECTING,       // 重新连接中
    ERROR               // 连接错误
}

/**
 * 连接统计信息
 */
data class ConnectionStats(
    val currentStatus: ConnectionStatus,
    val connectedSince: Long? = null,           // 连接建立时间戳
    val totalPacketsSent: Long = 0L,            // 总发送数据包数
    val totalAcksReceived: Long = 0L,           // 总收到ACK数
    val totalPolicyUpdates: Long = 0L,          // 总收到策略更新数
    val lastAckLatency: Long? = null,           // 最近ACK延迟（毫秒）
    val averageAckLatency: Double = 0.0,        // 平均ACK延迟（毫秒）
    val connectionErrors: Int = 0,              // 连接错误次数
    val lastErrorTimestamp: Long? = null,       // 最后错误时间戳
    val lastErrorMessage: String? = null        // 最后错误消息
)

/**
 * 传输统计信息
 */
data class TransmissionStats(
    val isFastMode: Boolean = false,            // 是否处于快速模式
    val fastModeRemainingSeconds: Int = 0,      // 快速模式剩余秒数
    val lastTriggerType: String? = null,        // 最后触发类型
    val successCount: Long = 0L,                // 成功上传数
    val failedCount: Long = 0L,                 // 失败上传数
    val averageLatency: Long = 0L               // 平均延迟（毫秒）
)

/**
 * gRPC状态信息
 */
data class GrpcStatus(
    val connectionState: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val lastAckLatency: Long = 0L               // 最近ACK延迟（毫秒）
)

/**
 * 服务器连接测试结果
 */
data class ServerTestResult(
    val isReachable: Boolean,
    val latencyMs: Long? = null,
    val statusCode: Int? = null,
    val errorMessage: String? = null,
    val testType: ServerTestType,
    val details: Map<String, String> = emptyMap()
)

/**
 * 服务器测试类型
 */
enum class ServerTestType {
    SOCKET_TEST,    // TCP Socket测试
    HTTP_TEST,      // HTTP请求测试
    GRPC_TEST      // gRPC连接测试
}

/**
 * 缓冲区统计信息
 */
data class BufferStats(
    val memorySamples: Int = 0,                 // 内存中样本数
    val diskBatches: Int = 0,                   // 磁盘上批次数
    val sentCount: Long = 0L,                   // 已发送数
    val discardedCount: Long = 0L               // 已丢弃数
)
/**
 * 重试结果
 */
data class RetryResult(
    val packetId: String,
    val success: Boolean,
    val error: String?,
    val retryCount: Int
)

/**
 * 批量重试结果
 */
data class BatchRetryResult(
    val totalPackets: Int,
    val successCount: Int,
    val failedCount: Int,
    val results: List<RetryResult>
)