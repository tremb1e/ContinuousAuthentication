package com.continuousauth.network

import android.util.Log
import com.continuousauth.proto.*
import com.continuousauth.policy.PolicyManager
import io.grpc.*
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 待确认数据包
 */
data class PendingAck(
    val packetId: String,
    val sentTimestamp: Long,
    val dataPacket: DataPacket
)

/**
 * gRPC双向流上传器实现
 * 采用双向流RPC进行数据传输和指令接收
 */
@Singleton
class UploaderImpl @Inject constructor(
    private val tlsSecurityManager: TlsSecurityManager,
    private val policyManager: PolicyManager
) : Uploader {

    companion object {
        private const val TAG = "Uploader"
        private const val CONNECTION_TIMEOUT_SECONDS = 30L
        private const val KEEPALIVE_TIME_SECONDS = 30L
        private const val KEEPALIVE_TIMEOUT_SECONDS = 5L
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val ACK_TIMEOUT_MS = 10000L // 10秒ACK超时
    }

    // gRPC相关
    private var channel: ManagedChannel? = null
    private var stub: SensorDataServiceGrpc.SensorDataServiceStub? = null
    private var requestObserver: StreamObserver<DataPacket>? = null

    // 状态管理
    private val connectionStatus = AtomicReference(ConnectionStatus.DISCONNECTED)
    private var connectedSince: Long? = null

    // 数据流
    private val serverDirectiveChannel = Channel<ServerDirective>(Channel.UNLIMITED)

    // 协程作用域
    private val uploaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 统计信息
    private val totalPacketsSent = AtomicLong(0L)
    private val totalAcksReceived = AtomicLong(0L)
    private val totalPolicyUpdates = AtomicLong(0L)
    private var connectionErrors = 0
    private var lastErrorTimestamp: Long? = null
    private var lastErrorMessage: String? = null

    // ACK跟踪
    private val pendingAcks = ConcurrentHashMap<String, PendingAck>()
    private var lastAckLatency: Long? = null
    private val ackLatencies = mutableListOf<Long>()

    // 重连逻辑
    private var reconnectJob: Job? = null
    private var currentEndpoint: String = ""

    override suspend fun connect(serverEndpoint: String): Boolean {
        if (connectionStatus.get() == ConnectionStatus.CONNECTED) {
            Log.w(TAG, "已经连接到服务器")
            return true
        }

        currentEndpoint = serverEndpoint
        connectionStatus.set(ConnectionStatus.CONNECTING)

        return try {
            // 解析服务器端点
            val parts = serverEndpoint.split(":")
            val host = parts[0]
            val port = if (parts.size > 1) parts[1].toInt() else 443

            // 创建gRPC通道
            val baseBuilder = OkHttpChannelBuilder
                .forAddress(host, port)
                .keepAliveTime(KEEPALIVE_TIME_SECONDS, TimeUnit.SECONDS)
                .keepAliveTimeout(KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .maxInboundMessageSize(4 * 1024 * 1024) // 4MB

            // 获取当前策略配置中的证书固定信息
            val policyConfig = policyManager.getCurrentPolicyConfiguration()
            val pinnedCertificates = policyConfig.securityConfig.pinnedCertificates

            // 应用TLS 1.3安全配置和SPKI证书固定
            channel = tlsSecurityManager.configureTlsForChannelBuilder(
                baseBuilder,
                pinnedCertificates,
                host
            ).build()
            stub = SensorDataServiceGrpc.newStub(channel)

            // 建立双向流
            if (establishBidirectionalStream()) {
                connectionStatus.set(ConnectionStatus.CONNECTED)
                connectedSince = System.currentTimeMillis()

                Log.i(TAG, "成功连接到服务器: $serverEndpoint")
                true
            } else {
                handleConnectionError("双向流建立失败")
                false
            }

        } catch (e: Exception) {
            Log.e(TAG, "连接服务器失败: $serverEndpoint", e)
            handleConnectionError("连接异常: ${e.message}")
            false
        }
    }

    override suspend fun sendDataPacket(dataPacket: DataPacket): Boolean {
        val observer = requestObserver
        if (observer == null || connectionStatus.get() != ConnectionStatus.CONNECTED) {
            Log.w(TAG, "连接未建立，无法发送数据包")
            return false
        }

        return try {
            // 记录待确认的包
            val pendingAck = PendingAck(
                packetId = dataPacket.packetId,
                sentTimestamp = System.currentTimeMillis(),
                dataPacket = dataPacket
            )
            pendingAcks[dataPacket.packetId] = pendingAck

            // 发送数据包
            observer.onNext(dataPacket)
            totalPacketsSent.incrementAndGet()

            Log.d(TAG, "数据包已发送: ${dataPacket.packetId}")

            // 启动ACK超时检查
            uploaderScope.launch {
                delay(ACK_TIMEOUT_MS)
                checkAckTimeout(dataPacket.packetId)
            }

            true

        } catch (e: Exception) {
            Log.e(TAG, "发送数据包失败: ${dataPacket.packetId}", e)
            pendingAcks.remove(dataPacket.packetId)
            false
        }
    }

    override fun getServerDirectiveFlow(): Flow<ServerDirective> {
        return serverDirectiveChannel.receiveAsFlow()
    }

    override suspend fun disconnect() {
        connectionStatus.set(ConnectionStatus.DISCONNECTED)
        connectedSince = null

        // 取消重连任务
        reconnectJob?.cancel()

        // 关闭请求流
        try {
            requestObserver?.onCompleted()
        } catch (e: Exception) {
            Log.w(TAG, "关闭请求流异常", e)
        }

        // 关闭gRPC通道
        channel?.let { ch ->
            try {
                ch.shutdown().awaitTermination(5, TimeUnit.SECONDS)
                if (!ch.isTerminated) {
                    ch.shutdownNow()
                }
            } catch (e: Exception) {
                Log.w(TAG, "关闭gRPC通道异常", e)
            }
            Unit // 确保lambda有明确的返回值
        }

        // 清理资源
        requestObserver = null
        stub = null
        channel = null
        pendingAcks.clear()

        Log.i(TAG, "已断开服务器连接")
    }

    override fun isConnected(): Boolean {
        return connectionStatus.get() == ConnectionStatus.CONNECTED
    }

    override fun getConnectionStatus(): ConnectionStatusDetail {
        return ConnectionStatusDetail(
            state = connectionStatus.get().name,
            endpoint = currentEndpoint,
            lastAckLatencyMs = lastAckLatency ?: 0L
        )
    }

    override fun getConnectionStats(): ConnectionStats {
        return ConnectionStats(
            currentStatus = connectionStatus.get(),
            connectedSince = connectedSince,
            totalPacketsSent = totalPacketsSent.get(),
            totalAcksReceived = totalAcksReceived.get(),
            totalPolicyUpdates = totalPolicyUpdates.get(),
            lastAckLatency = lastAckLatency,
            averageAckLatency = if (ackLatencies.isEmpty()) 0.0 else ackLatencies.average(),
            connectionErrors = connectionErrors,
            lastErrorTimestamp = lastErrorTimestamp,
            lastErrorMessage = lastErrorMessage
        )
    }

    /**
     * 建立双向流连接
     */
    private fun establishBidirectionalStream(): Boolean {
        return try {
            val responseObserver = object : StreamObserver<ServerDirective> {
                override fun onNext(directive: ServerDirective) {
                    handleServerDirective(directive)
                }

                override fun onError(t: Throwable) {
                    Log.e(TAG, "服务器响应流错误", t)
                    handleConnectionError("服务器响应流错误: ${t.message}")
                    // 启动重连
                    startReconnect()
                }

                override fun onCompleted() {
                    Log.i(TAG, "服务器响应流已完成")
                    connectionStatus.set(ConnectionStatus.DISCONNECTED)
                    startReconnect()
                }
            }

            // 创建请求观察者
            requestObserver = stub?.streamSensorData(responseObserver)
            requestObserver != null

        } catch (e: Exception) {
            Log.e(TAG, "建立双向流失败", e)
            false
        }
    }

    /**
     * 处理服务器指令
     */
    private fun handleServerDirective(directive: ServerDirective) {
        uploaderScope.launch {
            try {
                when {
                    directive.hasAck() -> handleAck(directive.ack)
                    directive.hasPolicy() -> handlePolicyUpdate(directive.policy)
                    directive.hasKeyRotation() -> handleKeyRotation(directive.keyRotation)
                    directive.hasEmergency() -> handleEmergencyStop(directive.emergency)
                }

                // 发送到指令流
                serverDirectiveChannel.trySend(directive)

            } catch (e: Exception) {
                Log.e(TAG, "处理服务器指令失败", e)
            }
        }
    }

    /**
     * 处理ACK确认
     */
    private fun handleAck(ack: Ack) {
        val pendingAck = pendingAcks.remove(ack.packetId)
        if (pendingAck != null) {
            // 计算ACK延迟
            val latency = System.currentTimeMillis() - pendingAck.sentTimestamp
            lastAckLatency = latency
            
            synchronized(ackLatencies) {
                ackLatencies.add(latency)
                // 保持最近100个延迟记录
                if (ackLatencies.size > 100) {
                    ackLatencies.removeAt(0)
                }
            }

            totalAcksReceived.incrementAndGet()

            Log.d(TAG, "收到ACK确认: ${ack.packetId}, 延迟: ${latency}ms, 成功: ${ack.success}")
        } else {
            Log.w(TAG, "收到未知数据包的ACK: ${ack.packetId}")
        }
    }

    /**
     * 处理策略更新
     */
    private fun handlePolicyUpdate(policyUpdate: PolicyUpdate) {
        totalPolicyUpdates.incrementAndGet()
        Log.i(TAG, "收到策略更新: ${policyUpdate.policyId}")
        
        // 通知策略管理器更新策略
        uploaderScope.launch {
            policyManager.updatePolicy(policyUpdate)
        }
    }

    /**
     * 检查ACK超时
     */
    private fun checkAckTimeout(packetId: String) {
        val pendingAck = pendingAcks.remove(packetId)
        if (pendingAck != null) {
            Log.w(TAG, "数据包ACK超时: $packetId")
            // 这里可以添加重发逻辑
        }
    }

    /**
     * 处理密钥轮换通知
     */
    private fun handleKeyRotation(keyRotation: KeyRotationNotice) {
        Log.i(TAG, "收到密钥轮换通知: ${keyRotation.newKeyId}")
        // TODO: 实现密钥轮换逻辑
    }
    
    /**
     * 处理紧急停止指令
     */
    private fun handleEmergencyStop(emergency: EmergencyStop) {
        Log.w(TAG, "收到紧急停止指令: ${emergency.reason}")
        // TODO: 实现紧急停止逻辑
    }

    /**
     * 处理连接错误
     */
    private fun handleConnectionError(errorMessage: String) {
        connectionErrors++
        lastErrorTimestamp = System.currentTimeMillis()
        lastErrorMessage = errorMessage
        connectionStatus.set(ConnectionStatus.ERROR)
        
        Log.e(TAG, "连接错误: $errorMessage")
    }

    /**
     * 启动重连
     */
    private fun startReconnect() {
        if (currentEndpoint.isEmpty()) return
        
        reconnectJob?.cancel()
        reconnectJob = uploaderScope.launch {
            connectionStatus.set(ConnectionStatus.RECONNECTING)
            var retryCount = 0
            
            while (retryCount < MAX_RETRY_ATTEMPTS && connectionStatus.get() != ConnectionStatus.CONNECTED) {
                delay(kotlin.math.min(1000 * (1 shl retryCount), 30000).toLong()) // 指数退避
                
                Log.i(TAG, "尝试重连 (${retryCount + 1}/$MAX_RETRY_ATTEMPTS): $currentEndpoint")
                
                if (connect(currentEndpoint)) {
                    Log.i(TAG, "重连成功")
                    return@launch
                }
                
                retryCount++
            }
            
            if (connectionStatus.get() != ConnectionStatus.CONNECTED) {
                Log.e(TAG, "重连失败，已达到最大重试次数")
                connectionStatus.set(ConnectionStatus.ERROR)
            }
        }
    }

    /**
     * 获取传输统计信息
     */
    override fun getTransmissionStats(): TransmissionStats {
        return TransmissionStats(
            isFastMode = false,  // 默认值，根据实际逻辑更新
            fastModeRemainingSeconds = 0,
            lastTriggerType = null,
            successCount = totalAcksReceived.get(),
            failedCount = totalPacketsSent.get() - totalAcksReceived.get(),
            averageLatency = if (ackLatencies.isEmpty()) 0L else ackLatencies.average().toLong()
        )
    }

    /**
     * 获取gRPC状态信息
     */
    override fun getGrpcStatus(): GrpcStatus {
        return GrpcStatus(
            connectionState = connectionStatus.get(),
            lastAckLatency = lastAckLatency ?: 0L
        )
    }

    /**
     * 获取缓冲区统计信息
     */
    override fun getBufferStats(): BufferStats {
        return BufferStats(
            memorySamples = 0,  // 默认值，根据实际缓冲区逻辑更新
            diskBatches = 0,
            sentCount = totalPacketsSent.get(),
            discardedCount = 0L
        )
    }

    /**
     * 获取服务器端点
     */
    override fun getServerEndpoint(): String {
        return currentEndpoint
    }

    /**
     * 获取统计信息
     */
    override fun getStatistics(): Statistics {
        return Statistics(
            totalPacketsSent = totalPacketsSent.get(),
            totalPacketsAcknowledged = totalAcksReceived.get(),
            totalFailures = totalPacketsSent.get() - totalAcksReceived.get()
        )
    }

    /**
     * 获取内存缓冲区统计
     */
    override fun getMemoryBufferStats(): MemoryBufferStats {
        return MemoryBufferStats(
            samplesInMemory = 0,  // 默认值，根据实际逻辑更新
            totalSent = totalPacketsSent.get(),
            totalFailed = totalPacketsSent.get() - totalAcksReceived.get(),
            totalDiscarded = 0L
        )
    }

    /**
     * 获取最近的延迟数据
     */
    override fun getRecentLatencies(): List<Long> {
        return ackLatencies.toList()
    }

    /**
     * 获取服务器策略
     */
    override suspend fun getServerPolicy(): ServerPolicy {
        return ServerPolicy(
            version = "1.0",
            lastUpdated = System.currentTimeMillis(),
            fastModeDurationSeconds = 30,  // 默认值，规范中已移除快速模式
            anomalyThreshold = 0.8f,       // 默认值，规范中已移除
            samplingRates = mapOf(
                "ACCELEROMETER" to 200f,    // 规范中加速度计200Hz
                "GYROSCOPE" to 200f,        // 规范中陀螺仪200Hz
                "MAGNETOMETER" to 100f      // 规范中磁力计100Hz
            ),
            transmissionStrategy = "ADAPTIVE"
        )
    }
}