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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

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
    private val policyManager: PolicyManager,
    private val tlsInspector: GrpcTlsInspector,
    private val inMemoryBuffer: InMemoryBuffer,
    private val fileQueueManager: FileQueueManager
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
    private val transportState = AtomicReference(TransportState())

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

    private data class ParsedEndpoint(
        val host: String,
        val port: Int,
        val useTls: Boolean,
        val scheme: String,
        val endpoint: String,
        val strictTlsIngress: Boolean
    )

    private fun parseEndpoint(endpoint: String): ParsedEndpoint {
        val normalized = ServerEndpointNormalizer.normalize(endpoint)

        return ParsedEndpoint(
            host = normalized.host,
            port = normalized.port,
            useTls = normalized.useTls,
            scheme = normalized.scheme,
            endpoint = normalized.endpoint,
            strictTlsIngress = normalized.isPublicTlsIngress
        )
    }

    private fun isHttpFallbackError(t: Throwable): Boolean {
        val msg = t.message?.lowercase() ?: return false
        return msg.contains("invalid content-type") ||
            msg.contains("http status code 404") ||
            msg.contains("application/json")
    }

    /**
     * 等待通道进入READY状态，避免握手失败后仍然误判为已连接。
     */
    private suspend fun ManagedChannel.awaitReady(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        var state = getState(true)
        while (state != ConnectivityState.READY) {
            if (state == ConnectivityState.SHUTDOWN) return@withTimeoutOrNull false
            state = suspendCancellableCoroutine { cont ->
                notifyWhenStateChanged(state) {
                    cont.resume(getState(false))
                }
            }.let { next ->
                if (next == ConnectivityState.TRANSIENT_FAILURE) getState(true) else next
            }
        }
        true
    } ?: false

    private fun buildChannel(
        host: String,
        port: Int,
        useTls: Boolean,
        pinnedCertificates: Set<String>
    ): ManagedChannel {
        val baseBuilder = OkHttpChannelBuilder
            .forAddress(host, port)
            .keepAliveTime(KEEPALIVE_TIME_SECONDS, TimeUnit.SECONDS)
            .keepAliveTimeout(KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .maxInboundMessageSize(4 * 1024 * 1024) // 4MB

        return if (useTls) {
            tlsSecurityManager.configureTlsForChannelBuilder(
                baseBuilder,
                pinnedCertificates,
                host
            ).build()
        } else {
            baseBuilder.usePlaintext().build()
        }
    }

    override suspend fun connect(serverEndpoint: String): Boolean {
        if (connectionStatus.get() == ConnectionStatus.CONNECTED) {
            Log.w(TAG, "已经连接到服务器")
            return true
        }

        currentEndpoint = serverEndpoint
        connectionStatus.set(ConnectionStatus.CONNECTING)

        val attemptTimestamp = System.currentTimeMillis()

        return try {
            val parsed = parseEndpoint(serverEndpoint)
            currentEndpoint = parsed.endpoint
            val policyConfig = policyManager.getCurrentPolicyConfiguration()
            val pinnedCertificates = policyConfig.securityConfig.pinnedCertificates

            val tlsProbe = if (parsed.useTls) {
                runCatching { tlsInspector.check(parsed.host, parsed.port) }
                    .onFailure { Log.w(TAG, "TLS 握手探测失败，假定可回退到 h2c: ${it.message}") }
                    .getOrNull()
            } else {
                null
            }

            val tlsSupported = tlsProbe?.supportsTls12OrHigher == true
            var useTls = parsed.useTls && (tlsSupported || parsed.strictTlsIngress)
            var downgraded = parsed.useTls && !useTls
            var lastError: String? = null
            var finalTls = useTls

            if (parsed.useTls && !tlsSupported) {
                val probeReason = tlsProbe?.errorMessage ?: "TLS未就绪或证书未部署"
                if (parsed.strictTlsIngress) {
                    Log.w(TAG, "公网443入口 TLS 探测失败，仍按 TLS gRPC 尝试且不回退明文 ($probeReason)")
                } else {
                    Log.i(TAG, "未检测到可用的 TLS 1.2/1.3 证书，优先使用 h2c 明文模式 ($probeReason)")
                }
            }

            suspend fun buildAndStream(tls: Boolean, port: Int): Boolean {
                return try {
                    channel?.shutdownNow()
                    val builtChannel = buildChannel(
                        host = parsed.host,
                        port = port,
                        useTls = tls,
                        pinnedCertificates = pinnedCertificates
                    ).also { channel = it }

                    val ready = builtChannel.awaitReady(TimeUnit.SECONDS.toMillis(CONNECTION_TIMEOUT_SECONDS))
                    if (!ready) {
                        lastError = "通道未就绪或TLS握手失败"
                        Log.w(TAG, "gRPC 通道未就绪，${if (tls) "TLS" else "明文"}模式等待超时")
                        builtChannel.shutdownNow()
                        return false
                    }

                    stub = SensorDataServiceGrpc.newStub(builtChannel)
                    val ok = establishBidirectionalStream()
                    if (ok) {
                        finalTls = tls
                    }
                    ok
                } catch (e: Exception) {
                    lastError = e.message
                    Log.e(TAG, "建立${if (tls) "TLS" else "明文"}通道失败", e)
                    false
                }
            }

            var connected = buildAndStream(useTls, parsed.port)
            if (!connected && useTls && !parsed.strictTlsIngress) {
                Log.w(TAG, "TLS 握手/流建立失败，回退到明文 h2c")
                downgraded = true
                connected = buildAndStream(false, parsed.port)
            } else if (!connected && useTls && parsed.strictTlsIngress) {
                Log.w(TAG, "公网443 TLS gRPC 入口连接失败，不回退明文或其它端口")
            }

            if (connected) {
                connectionStatus.set(ConnectionStatus.CONNECTED)
                connectedSince = System.currentTimeMillis()
                updateTransportState(
                    TransportState(
                        mode = if (finalTls) TransportMode.HTTPS else TransportMode.HTTP,
                        tlsVersion = if (finalTls) tlsProbe?.tlsVersion else null,
                        negotiatedProtocol = if (finalTls) (tlsProbe?.negotiatedProtocol ?: "h2") else "h2c",
                        tlsCapable = tlsProbe?.supportsTls12OrHigher ?: false,
                        preferredScheme = parsed.scheme,
                        downgradedToCleartext = downgraded,
                        lastResultSuccess = true,
                        lastAttemptMs = attemptTimestamp
                    )
                )
                Log.i(TAG, "成功连接到服务器: ${parsed.endpoint} (tls=${finalTls})")
                true
            } else {
                handleConnectionError(lastError ?: "双向流建立失败")
                updateTransportState(
                    TransportState(
                        mode = if (downgraded || (!useTls && !parsed.strictTlsIngress)) TransportMode.HTTP else TransportMode.HTTPS,
                        tlsVersion = tlsProbe?.tlsVersion,
                        negotiatedProtocol = tlsProbe?.negotiatedProtocol,
                        tlsCapable = tlsProbe?.supportsTls12OrHigher ?: false,
                        preferredScheme = parsed.scheme,
                        downgradedToCleartext = downgraded,
                        lastResultSuccess = false,
                        lastError = lastError,
                        lastAttemptMs = attemptTimestamp
                    )
                )
                try {
                    channel?.shutdownNow()
                } catch (_: Exception) {
                }
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "连接服务器失败: $serverEndpoint", e)
            handleConnectionError("连接异常: ${e.message}")
            updateTransportState(
                transportState.get().copy(
                    lastResultSuccess = false,
                    lastError = e.message,
                    lastAttemptMs = attemptTimestamp
                )
            )
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

    override suspend fun sendHeartbeat(heartbeat: Heartbeat): HeartbeatAck? = withContext(Dispatchers.IO) {
        val ch = channel ?: return@withContext null
        return@withContext try {
            val blockingStub = SensorDataServiceGrpc.newBlockingStub(ch)
                .withDeadlineAfter(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val ack = blockingStub.sendHeartbeat(heartbeat)
            lastAckLatency = System.currentTimeMillis() - heartbeat.clientTimestamp
            Log.v(TAG, "心跳响应成功，服务器时间戳=${ack.serverTimestamp}")
            ack
        } catch (e: Exception) {
            Log.e(TAG, "发送心跳失败", e)
            null
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
    private fun establishBidirectionalStream(): Boolean {
        return try {
            val responseObserver = object : StreamObserver<ServerDirective> {
                override fun onNext(directive: ServerDirective) {
                    handleServerDirective(directive)
                }

                override fun onError(t: Throwable) {
                    Log.e(TAG, "服务器响应流错误", t)
                    handleConnectionError("服务器响应流错误: ${t.message}")
                    if (isHttpFallbackError(t)) {
                        Log.w(TAG, "检测到可能连接到非 gRPC 入口，按归一化后的当前端点重连")
                    }
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
     * 启动重连
     */
    private fun startReconnect(overrideEndpoint: String? = null) {
        if (currentEndpoint.isEmpty() && overrideEndpoint.isNullOrEmpty()) return
        
        reconnectJob?.cancel()
        reconnectJob = uploaderScope.launch {
            connectionStatus.set(ConnectionStatus.RECONNECTING)
            var retryCount = 0
            val targetEndpoint = overrideEndpoint ?: currentEndpoint
            
            while (retryCount < MAX_RETRY_ATTEMPTS && connectionStatus.get() != ConnectionStatus.CONNECTED) {
                delay(kotlin.math.min(1000 * (1 shl retryCount), 30000).toLong()) // 指数退避
                
                Log.i(TAG, "尝试重连 (${retryCount + 1}/$MAX_RETRY_ATTEMPTS): $targetEndpoint")
                
                if (connect(targetEndpoint)) {
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
        val queueStats = runBlocking { fileQueueManager.getQueueStatistics() }
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
            SensorDataServiceGrpc.newBlockingStub(ch).startAuthentication(request)
        } catch (e: Exception) {
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
        val scheme = if (useTls) "https" else "http"
        val endpoint = "$scheme://$serverHost:$serverPort"
        val alreadyConnected = connectionStatus.get() == ConnectionStatus.CONNECTED
        val startTs = System.currentTimeMillis()

        val reachable = if (alreadyConnected) {
            true
        } else {
            connect(endpoint)
        }

        val latency = System.currentTimeMillis() - startTs
        val transport = transportState.get()

        if (!alreadyConnected && reachable) {
            // 仅用于探测时断开连接，避免影响后续正式连接
            disconnect()
        }

        if (reachable) {
            ServerTestResult(
                isReachable = true,
                latencyMs = if (alreadyConnected) 0 else latency,
                testType = ServerTestType.GRPC_TEST,
                details = mapOf(
                    "mode" to transport.mode.name,
                    "tls" to (transport.tlsVersion ?: "none"),
                    "protocol" to (transport.negotiatedProtocol ?: "h2c"),
                    "downgraded" to transport.downgradedToCleartext.toString()
                )
            )
        } else {
            ServerTestResult(
                isReachable = false,
                errorMessage = lastErrorMessage ?: "连接失败",
                testType = ServerTestType.GRPC_TEST
            )
        }
    }

    override fun getTestResultDescription(result: ServerTestResult): String {
        return buildString {
            if (result.isReachable) {
                append("✓ 服务器可达")
                result.latencyMs?.let { append(" (延迟: ${it}ms)") }
                result.statusCode?.let { append(" [HTTP: $it]") }
            } else {
                append("✗ 服务器不可达")
                result.errorMessage?.let { append(" - $it") }
            }
            append(" [${result.testType}]")
        }
    }
}
