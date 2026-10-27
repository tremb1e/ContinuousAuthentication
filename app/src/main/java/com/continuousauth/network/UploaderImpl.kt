package com.continuousauth.network

import android.util.Log
import com.continuousauth.BuildConfig
import com.continuousauth.buffer.InMemoryBuffer
import com.continuousauth.proto.*
import com.continuousauth.policy.PolicyManager
import com.continuousauth.storage.FileQueueManager
import io.grpc.*
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.StreamObserver
import io.grpc.stub.ClientCalls
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
class UploaderImpl internal constructor(
    private val tlsSecurityManager: TlsSecurityManager,
    private val policyManager: PolicyManager,
    private val inMemoryBuffer: InMemoryBuffer,
    private val fileQueueManager: FileQueueManager,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val channelFactory: ((ServerEndpoint, Set<String>) -> ManagedChannel)?
) : Uploader {
    @Inject constructor(
        tlsSecurityManager: TlsSecurityManager,
        policyManager: PolicyManager,
        inMemoryBuffer: InMemoryBuffer,
        fileQueueManager: FileQueueManager
    ) : this(tlsSecurityManager, policyManager, inMemoryBuffer, fileQueueManager, channelFactory = null)


    companion object {
        private const val TAG = "Uploader"
        private const val CONNECTION_TIMEOUT_SECONDS = 20L
        private const val KEEPALIVE_TIME_SECONDS = 30L
        private const val KEEPALIVE_TIMEOUT_SECONDS = 5L
        private const val ACK_TIMEOUT_MS = 10000L // 10秒ACK超时
    }

    // gRPC相关
    private var channel: ManagedChannel? = null
    private var stub: SensorDataServiceGrpc.SensorDataServiceStub? = null
    private var requestObserver: StreamObserver<DataPacket>? = null
    private val connectionMutex = Mutex()
    private val streamLock = Any()
    private val connectionGeneration = AtomicLong(0L)

    // 状态管理
    private val connectionStatus = AtomicReference(ConnectionStatus.DISCONNECTED)
    private var connectedSince: Long? = null
    private val transportState = AtomicReference(TransportState())

    // 数据流
    private val serverDirectiveChannel = Channel<ServerDirective>(Channel.UNLIMITED)

    // 协程作用域
    private val uploaderScope = CoroutineScope(SupervisorJob() + dispatcher)

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

    private var currentEndpoint: String = ""

    private fun buildChannel(endpoint: ServerEndpoint, pinnedCertificates: Set<String>): ManagedChannel {
        channelFactory?.let { return it(endpoint, pinnedCertificates) }
        val baseBuilder = OkHttpChannelBuilder
            .forAddress(endpoint.host, endpoint.port)
            .keepAliveTime(KEEPALIVE_TIME_SECONDS, TimeUnit.SECONDS)
            .keepAliveTimeout(KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .maxInboundMessageSize(4 * 1024 * 1024) // 4MB

        return if (endpoint.useTls) {
            tlsSecurityManager.configureTlsForChannelBuilder(
                baseBuilder,
                pinnedCertificates,
                endpoint.host
            ).build()
        } else {
            baseBuilder.usePlaintext().build()
        }
    }

    /** Verify the actual RPC before declaring a connection. HTTP/2 READY alone can be a web proxy. */
    override suspend fun connect(serverEndpoint: String): Boolean = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            val endpoint = try {
                ServerEndpointNormalizer.normalize(serverEndpoint)
            } catch (e: IllegalArgumentException) {
                if (!isConnected()) handleConnectionError("服务器地址或端口无效，请检查设置")
                return@withLock false
            }
            if (isConnected() && currentEndpoint == endpoint.endpoint) return@withLock true

            val generation = connectionGeneration.incrementAndGet()
            currentEndpoint = endpoint.endpoint
            connectionStatus.set(ConnectionStatus.CONNECTING)
            connectedSince = null
            requestObserver = null
            stub = null
            channel?.shutdownNow()
            channel = null
            pendingAcks.clear()
            val attemptedAt = System.currentTimeMillis()
            try {
                val pins = policyManager.getCurrentPolicyConfiguration().securityConfig.pinnedCertificates
                val newChannel = buildChannel(endpoint, pins).also { channel = it }
                probeHeartbeat(newChannel)
                currentCoroutineContext().ensureActive()
                stub = SensorDataServiceGrpc.newStub(newChannel)
                check(establishBidirectionalStream(generation) &&
                    connectionStatus.compareAndSet(ConnectionStatus.CONNECTING, ConnectionStatus.CONNECTED)) {
                    "认证数据连接未能建立"
                }
                connectedSince = System.currentTimeMillis()
                lastErrorMessage = null
                updateTransportState(TransportState(
                    mode = if (endpoint.useTls) TransportMode.HTTPS else TransportMode.HTTP,
                    negotiatedProtocol = if (endpoint.useTls) "h2" else "h2c",
                    tlsCapable = endpoint.useTls,
                    preferredScheme = endpoint.scheme,
                    lastResultSuccess = true,
                    lastAttemptMs = attemptedAt
                ))
                true
            } catch (e: CancellationException) {
                connectionGeneration.incrementAndGet()
                channel?.shutdownNow()
                channel = null
                requestObserver = null
                stub = null
                connectionStatus.set(ConnectionStatus.DISCONNECTED)
                throw e
            } catch (e: Exception) {
                connectionGeneration.incrementAndGet()
                channel?.shutdownNow()
                channel = null
                requestObserver = null
                stub = null
                val message = describeConnectionFailure(e)
                handleConnectionError(message)
                updateTransportState(TransportState(
                    mode = if (endpoint.useTls) TransportMode.HTTPS else TransportMode.HTTP,
                    preferredScheme = endpoint.scheme,
                    lastResultSuccess = false,
                    lastError = message,
                    lastAttemptMs = attemptedAt
                ))
                false
            }
        }
    }

    private suspend fun probeHeartbeat(channel: ManagedChannel): HeartbeatAck {
        val request = Heartbeat.newBuilder().setClientTimestamp(System.currentTimeMillis()).build()
        val ack = unaryCall(channel, SensorDataServiceGrpc.getSendHeartbeatMethod(), request)
        check(ack.serverTimestamp > 0L && ack.clientTimestampEcho == request.clientTimestamp) {
            "服务器返回了无效的连接检测结果"
        }
        return ack
    }

    /** Cancellation must cancel the RPC too; a blocking stub keeps authentication alive after Stop. */
    private suspend fun <Req : Any, Resp : Any> unaryCall(
        channel: ManagedChannel,
        method: MethodDescriptor<Req, Resp>,
        request: Req
    ): Resp = suspendCancellableCoroutine { continuation ->
        val call = channel.newCall(method, CallOptions.DEFAULT
            .withDeadlineAfter(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        continuation.invokeOnCancellation { call.cancel("操作已取消", null) }
        ClientCalls.asyncUnaryCall(call, request, object : StreamObserver<Resp> {
            private var response: Resp? = null
            override fun onNext(value: Resp) { response = value }
            override fun onError(error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onCompleted() {
                if (!continuation.isActive) return
                val value = response
                if (value != null) continuation.resume(value)
                else continuation.resumeWithException(IllegalStateException("服务器未返回连接检测结果"))
            }
        })
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
            synchronized(streamLock) { observer.onNext(dataPacket) }
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

    override suspend fun sendHeartbeat(heartbeat: Heartbeat): HeartbeatAck? = withContext(Dispatchers.IO) {
        val ch = channel ?: return@withContext null
        try {
            unaryCall(ch, SensorDataServiceGrpc.getSendHeartbeatMethod(), heartbeat).also {
                lastAckLatency = System.currentTimeMillis() - heartbeat.clientTimestamp
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "发送心跳失败", e)
            null
        }
    }

    override fun getServerDirectiveFlow(): Flow<ServerDirective> {
        return serverDirectiveChannel.receiveAsFlow()
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            connectionGeneration.incrementAndGet()
            connectionStatus.set(ConnectionStatus.DISCONNECTED)
            connectedSince = null

            // 关闭请求流
            try {
                synchronized(streamLock) { requestObserver?.onCompleted() }
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
    }

    override fun isConnected(): Boolean {
        return connectionStatus.get() == ConnectionStatus.CONNECTED
    }

    override fun getConnectionStatus(): ConnectionStatusDetail {
        val transport = transportState.get()
        return ConnectionStatusDetail(
            state = connectionStatus.get().name,
            endpoint = currentEndpoint,
            lastAckLatencyMs = lastAckLatency ?: 0L,
            usingTls = transport.mode == TransportMode.HTTPS,
            tlsVersion = transport.tlsVersion,
            negotiatedProtocol = transport.negotiatedProtocol,
            downgradedToCleartext = transport.downgradedToCleartext
        )
    }

    override fun getConnectionStats(): ConnectionStats {
        synchronized(ackLatencies) {
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
    }

    /**
     * 建立双向流连接
     */
    private fun establishBidirectionalStream(generation: Long): Boolean {
        return try {
            val responseObserver = object : StreamObserver<ServerDirective> {
                override fun onNext(directive: ServerDirective) {
                    if (generation == connectionGeneration.get()) handleServerDirective(directive)
                }

                // UploadManager owns retry timing and cancels that work when collection stops.
                override fun onError(t: Throwable) {
                    if (generation != connectionGeneration.get()) return
                    Log.e(TAG, "服务器响应流错误", t)
                    handleConnectionError(describeConnectionFailure(t))
                }

                override fun onCompleted() {
                    if (generation != connectionGeneration.get()) return
                    Log.i(TAG, "服务器响应流已完成")
                    connectionStatus.set(ConnectionStatus.DISCONNECTED)
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
                    directive.hasAuthResult() -> handleAuthResult(directive.authResult)
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

    private fun handleAuthResult(authResult: AuthResult) {
        Log.d(TAG, "收到认证结果: session=${authResult.sessionId}, score=${authResult.score}")
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
     * 获取传输统计信息
     */
    override fun getTransmissionStats(): TransmissionStats {
        synchronized(ackLatencies) {
            return TransmissionStats(
                isFastMode = false,  // 默认值，根据实际逻辑更新
                fastModeRemainingSeconds = 0,
                lastTriggerType = null,
                successCount = totalAcksReceived.get(),
                failedCount = totalPacketsSent.get() - totalAcksReceived.get(),
                averageLatency = if (ackLatencies.isEmpty()) 0L else ackLatencies.average().toLong()
            )
        }
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

    override fun getTransportState(): TransportState = transportState.get()

    private fun updateTransportState(state: TransportState) {
        transportState.set(state)
    }

    override fun getChannelState(): String {
        return channel?.getState(false)?.toString() ?: "NO_CHANNEL"
    }

    /**
     * 获取缓冲区统计信息
     */
    override fun getBufferStats(): BufferStats {
        // 获取内存缓冲区状态
        val bufferStatus = inMemoryBuffer.getBufferStatus()
        val memorySamples = bufferStatus.currentSize

        // 计算丢弃的数据包数量（总入队 - 总出队）
        val discardedCount = bufferStatus.totalEnqueued - bufferStatus.totalDequeued

        // 获取磁盘队列统计信息
        val queueStats = fileQueueManager.queueStats.value
        val diskBatches = queueStats.pendingPackets
        return BufferStats(
           memorySamples = memorySamples,
            diskBatches = diskBatches,
            sentCount = totalPacketsSent.get(),
            discardedCount = discardedCount
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
        // 获取内存缓冲区状态
        val bufferStatus = inMemoryBuffer.getBufferStatus()
        val samplesInMemory = bufferStatus.currentSize

        // 计算丢弃的数据包数量
        val totalDiscarded = bufferStatus.totalEnqueued - bufferStatus.totalDequeued
        return MemoryBufferStats(
            samplesInMemory = samplesInMemory,
            totalSent = totalPacketsSent.get(),
            totalFailed = totalPacketsSent.get() - totalAcksReceived.get(),
            totalDiscarded = totalDiscarded
        )
    }

    /**
     * 获取最近的延迟数据
     */
    override fun getRecentLatencies(): List<Long> {
        synchronized(ackLatencies) {
            return ackLatencies.toList()
        }
    }

    override suspend fun startAuthentication(
        deviceIdHash: String,
        sessionId: String?
    ): AuthSessionResponse? = withContext(Dispatchers.IO) {
        val ch = channel ?: return@withContext null
        val request = AuthSessionRequest.newBuilder()
            .setDeviceIdHash(deviceIdHash)
            .setAppVersion(BuildConfig.VERSION_NAME)
            .setAndroidApiLevel(android.os.Build.VERSION.SDK_INT)
            .apply {
                if (!sessionId.isNullOrBlank()) {
                    setSessionId(sessionId)
                }
            }
            .build()
        return@withContext try {
            unaryCall(ch, SensorDataServiceGrpc.getStartAuthenticationMethod(), request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastErrorMessage = describeConnectionFailure(e)
            lastErrorTimestamp = System.currentTimeMillis()
            Log.e(TAG, "开始认证会话失败", e)
            null
        }
    }

    /**
     * 获取服务器策略
     */
    override suspend fun getServerPolicy(): ServerPolicy {
        val config = policyManager.getCurrentPolicyConfiguration()
        return ServerPolicy(
            policyId = config.policyId,
            policyVersion = config.policyVersion,
            batchIntervalMs = config.transmissionConfig.batchIntervalMs,
            maxPayloadSizeBytes = config.transmissionConfig.maxPayloadSizeBytes,
            uploadRateLimit = config.transmissionConfig.uploadRateLimit,
            compressionAlgorithm = config.transmissionConfig.compressionAlgorithm,
            batchSizeThreshold = config.transmissionConfig.batchSizeThreshold,
            enabledSensors = config.collectionConfig.enabledSensors,
            samplingRates = config.collectionConfig.sensorSamplingRates,
            anomalyEnabled = config.collectionConfig.anomalyEnabled,
            anomalyThresholdMultiplier = config.collectionConfig.anomalyThresholdMultiplier,
            anomalyWindowSizeSec = config.collectionConfig.anomalyWindowSizeSec,
            anomalyCooldownSec = config.collectionConfig.anomalyCooldownSec,
            lastUpdated = System.currentTimeMillis()
        )
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun testServerConnection(
        serverHost: String,
        serverPort: Int,
        useTls: Boolean,
        testGrpc: Boolean
    ): ServerTestResult = withContext(Dispatchers.IO) {
        // A separate channel always tests the requested endpoint and never closes an active upload.
        var probeChannel: ManagedChannel? = null
        val startedAt = System.currentTimeMillis()
        try {
            val endpoint = ServerEndpointNormalizer.normalize(
                serverHost, serverPort, if (useTls) "https" else "http"
            )
            val pins = policyManager.getCurrentPolicyConfiguration().securityConfig.pinnedCertificates
            probeChannel = buildChannel(endpoint, pins)
            probeHeartbeat(probeChannel)
            ServerTestResult(
                isReachable = true,
                latencyMs = System.currentTimeMillis() - startedAt,
                testType = ServerTestType.GRPC_TEST,
                details = mapOf("endpoint" to endpoint.endpoint, "protocol" to if (endpoint.useTls) "h2" else "h2c")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ServerTestResult(
                isReachable = false,
                errorMessage = describeConnectionFailure(e),
                testType = ServerTestType.GRPC_TEST
            )
        } finally {
            probeChannel?.shutdownNow()
        }
    }

    override fun getTestResultDescription(result: ServerTestResult): String =
        if (result.isReachable) "服务器连接正常" + (result.latencyMs?.let { "（${it}毫秒）" } ?: "")
        else result.errorMessage ?: "暂时无法连接服务器，请稍后重试"
}
