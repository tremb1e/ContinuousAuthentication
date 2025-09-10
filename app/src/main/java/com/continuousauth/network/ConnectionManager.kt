package com.continuousauth.network

import android.util.Log
import com.continuousauth.proto.DataPacket
import com.continuousauth.proto.Heartbeat
import com.continuousauth.proto.HeartbeatAck
import com.continuousauth.proto.ServerDirective
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * 连接管理器
 * 负责管理网络连接、重连、心跳等
 * 符合 claude.md Epic 2.1 和 2.4 要求
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val grpcManager: GrpcManager,
    private val uploadManager: UploadManager
) {
    
    companion object {
        private const val TAG = "ConnectionManager"
        
        // 重试配置
        private const val MAX_RETRIES = 10
        private const val BASE_DELAY = 1000L // 1秒
        private const val MAX_DELAY = 60_000L // 最大60秒
        
        // 心跳配置
        private const val HEARTBEAT_INTERVAL = 30_000L // 30秒
        private const val HEARTBEAT_TIMEOUT = 5_000L // 5秒超时
        
        // 连接状态检查间隔
        private const val CONNECTION_CHECK_INTERVAL = 10_000L // 10秒
    }
    
    /**
     * 连接状态
     */
    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        ERROR,
        SUSPENDED  // 服务器要求暂停
    }
    
    /**
     * 连接事件
     */
    sealed class ConnectionEvent {
        object Connected : ConnectionEvent()
        object Disconnected : ConnectionEvent()
        data class Error(val message: String, val throwable: Throwable? = null) : ConnectionEvent()
        data class Reconnecting(val attempt: Int, val maxAttempts: Int) : ConnectionEvent()
        data class ServerDirectiveReceived(val directive: ServerDirective) : ConnectionEvent()
    }
    
    // 状态管理
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()
    
    private val _connectionEvents = MutableStateFlow<ConnectionEvent?>(null)
    val connectionEvents: StateFlow<ConnectionEvent?> = _connectionEvents.asStateFlow()
    
    // 协程管理
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var connectionMonitorJob: Job? = null
    private var reconnectJob: Job? = null
    
    // 连接参数
    private var retryCount = 0
    private var lastConnectionTime = 0L
    private var lastHeartbeatTime = 0L
    private var currentStreamObserver: StreamObserver<DataPacket>? = null
    
    // 回调处理器
    private var serverDirectiveHandler: ((ServerDirective) -> Unit)? = null
    
    /**
     * 初始化连接管理器
     */
    fun initialize(serverHost: String, serverPort: Int) {
        Log.i(TAG, "初始化连接管理器: $serverHost:$serverPort")
        grpcManager.configureServer(serverHost, serverPort)
    }
    
    /**
     * 建立连接（带重试）
     */
    suspend fun connectWithRetry(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            Log.i(TAG, "开始建立连接（带重试）")
            _connectionStatus.value = ConnectionStatus.CONNECTING
            retryCount = 0
            
            while (retryCount < MAX_RETRIES) {
                try {
                    // 尝试连接
                    val result = grpcManager.connect()
                    
                    if (result.isSuccess) {
                        onConnectionEstablished()
                        return@withContext Result.success(Unit)
                    }
                    
                    // 连接失败，准备重试
                    handleConnectionFailure(result.exceptionOrNull())
                    
                } catch (e: Exception) {
                    handleConnectionFailure(e)
                }
                
                // 计算退避延迟
                val delay = calculateBackoff(retryCount)
                Log.i(TAG, "第 ${retryCount + 1}/$MAX_RETRIES 次连接失败，${delay}ms 后重试")
                
                _connectionStatus.value = ConnectionStatus.RECONNECTING
                _connectionEvents.value = ConnectionEvent.Reconnecting(retryCount + 1, MAX_RETRIES)
                
                delay(delay)
                retryCount++
            }
            
            // 超过最大重试次数
            Log.e(TAG, "连接失败，已达到最大重试次数")
            _connectionStatus.value = ConnectionStatus.ERROR
            _connectionEvents.value = ConnectionEvent.Error("连接失败，已达到最大重试次数")
            Result.failure(Exception("连接失败，已达到最大重试次数"))
        }
    }
    
    /**
     * 断开连接
     */
    suspend fun disconnect() {
        Log.i(TAG, "断开连接")
        
        // 取消所有协程任务
        heartbeatJob?.cancel()
        connectionMonitorJob?.cancel()
        reconnectJob?.cancel()
        
        // 关闭流
        currentStreamObserver?.onCompleted()
        currentStreamObserver = null
        
        // 断开gRPC连接
        grpcManager.disconnect()
        
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _connectionEvents.value = ConnectionEvent.Disconnected
    }
    
    /**
     * 建立双向流
     */
    fun establishBidirectionalStream(): Boolean {
        Log.i(TAG, "建立双向流")
        
        val responseObserver = object : StreamObserver<ServerDirective> {
            override fun onNext(directive: ServerDirective) {
                Log.d(TAG, "收到服务器指令: ${directive.directiveCase}")
                handleServerDirective(directive)
            }
            
            override fun onError(t: Throwable) {
                Log.e(TAG, "流错误", t)
                handleStreamError(t)
            }
            
            override fun onCompleted() {
                Log.i(TAG, "流已完成")
                handleStreamCompleted()
            }
        }
        
        currentStreamObserver = grpcManager.establishBidirectionalStream(responseObserver)
        return currentStreamObserver != null
    }
    
    /**
     * 发送数据包
     */
    fun sendDataPacket(packet: DataPacket): Boolean {
        if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
            Log.w(TAG, "未连接，无法发送数据包")
            return false
        }
        
        return try {
            currentStreamObserver?.onNext(packet)
            true
        } catch (e: Exception) {
            Log.e(TAG, "发送数据包失败", e)
            // 可能需要重连
            handleSendError(e)
            false
        }
    }
    
    /**
     * 设置服务器指令处理器
     */
    fun setServerDirectiveHandler(handler: (ServerDirective) -> Unit) {
        serverDirectiveHandler = handler
    }
    
    /**
     * 连接建立成功处理
     */
    private fun onConnectionEstablished() {
        Log.i(TAG, "连接建立成功")
        
        retryCount = 0
        lastConnectionTime = System.currentTimeMillis()
        _connectionStatus.value = ConnectionStatus.CONNECTED
        _connectionEvents.value = ConnectionEvent.Connected
        
        // 建立双向流
        if (!establishBidirectionalStream()) {
            Log.e(TAG, "建立双向流失败")
            return
        }
        
        // 启动心跳
        startHeartbeat()
        
        // 启动连接监控
        startConnectionMonitor()
    }
    
    /**
     * 启动心跳机制
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            Log.i(TAG, "启动心跳机制")
            
            while (isActive && _connectionStatus.value == ConnectionStatus.CONNECTED) {
                delay(HEARTBEAT_INTERVAL)
                
                try {
                    sendHeartbeat()
                } catch (e: Exception) {
                    Log.e(TAG, "心跳发送失败", e)
                    // 心跳失败，可能需要重连
                    triggerReconnect()
                    break
                }
            }
        }
    }
    
    /**
     * 发送心跳
     */
    private suspend fun sendHeartbeat() {
        Log.v(TAG, "发送心跳")
        
        try {
            // 获取待发送包数和最后的序列号
            val pendingPackets = uploadManager.getPendingPacketsCount()
            val lastSeqNo = uploadManager.getLastPacketSeqNo()
            
            val heartbeat = Heartbeat.newBuilder()
                .setClientTimestamp(System.currentTimeMillis())
                .setPendingPackets(pendingPackets)
                .setLastPacketSeqNo(lastSeqNo)
                .build()
            
            // 通过gRPC发送心跳并等待响应
            val heartbeatResponse = withTimeoutOrNull(HEARTBEAT_TIMEOUT) {
                grpcManager.sendHeartbeat(heartbeat)
            }
            
            if (heartbeatResponse != null) {
                // 处理心跳响应
                val latency = System.currentTimeMillis() - heartbeatResponse.clientTimestampEcho
                Log.d(TAG, "心跳响应成功，延迟: ${latency}ms")
                
                // 更新连接状态
                if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                }
                lastHeartbeatTime = System.currentTimeMillis()
            } else {
                Log.w(TAG, "心跳超时，未收到响应")
                throw Exception("心跳超时")
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送心跳失败", e)
            throw e
        }
    }
    
    /**
     * 启动连接监控
     */
    private fun startConnectionMonitor() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = scope.launch {
            Log.i(TAG, "启动连接监控")
            
            while (isActive) {
                delay(CONNECTION_CHECK_INTERVAL)
                
                if (!grpcManager.isConnected()) {
                    Log.w(TAG, "检测到连接断开")
                    triggerReconnect()
                    break
                }
                
                // 记录连接状态
                val channelState = grpcManager.getChannelState()
                Log.v(TAG, "连接状态: $channelState")
            }
        }
    }
    
    /**
     * 触发重连
     */
    private fun triggerReconnect() {
        if (reconnectJob?.isActive == true) {
            Log.d(TAG, "重连任务已在进行中")
            return
        }
        
        reconnectJob = scope.launch {
            Log.i(TAG, "触发重连")
            
            // 先断开现有连接
            disconnect()
            
            // 等待一段时间
            delay(1000)
            
            // 尝试重新连接
            connectWithRetry()
        }
    }
    
    /**
     * 计算指数退避延迟
     */
    private fun calculateBackoff(attempt: Int): Long {
        // 指数退避：base * 2^attempt，最大不超过MAX_DELAY
        val delay = BASE_DELAY * (1 shl attempt)
        return min(delay, MAX_DELAY)
    }
    
    /**
     * 处理连接失败
     */
    private fun handleConnectionFailure(error: Throwable?) {
        Log.e(TAG, "连接失败", error)
        _connectionEvents.value = ConnectionEvent.Error(
            error?.message ?: "未知错误",
            error
        )
    }
    
    /**
     * 处理服务器指令
     */
    private fun handleServerDirective(directive: ServerDirective) {
        // 通知事件
        _connectionEvents.value = ConnectionEvent.ServerDirectiveReceived(directive)
        
        // 调用处理器
        serverDirectiveHandler?.invoke(directive)
        
        // 特殊指令处理
        when (directive.directiveCase) {
            ServerDirective.DirectiveCase.EMERGENCY -> {
                handleEmergencyStop(directive.emergency)
            }
            ServerDirective.DirectiveCase.KEY_ROTATION -> {
                Log.i(TAG, "收到密钥轮换通知")
            }
            else -> {
                // 其他指令由处理器处理
            }
        }
    }
    
    /**
     * 处理紧急停止
     */
    private fun handleEmergencyStop(emergency: com.continuousauth.proto.EmergencyStop) {
        Log.w(TAG, "收到紧急停止指令: ${emergency.reason}")
        _connectionStatus.value = ConnectionStatus.SUSPENDED
        
        scope.launch {
            // 停止所有活动
            heartbeatJob?.cancel()
            connectionMonitorJob?.cancel()
            
            // 如果需要清空本地缓存
            if (emergency.clearLocalCache) {
                Log.i(TAG, "清空本地缓存")
                // TODO: 调用清空缓存的方法
            }
            
            // 等待指定时间后尝试重连
            if (emergency.stopDurationSec > 0) {
                delay(emergency.stopDurationSec * 1000L)
                connectWithRetry()
            }
        }
    }
    
    /**
     * 处理流错误
     */
    private fun handleStreamError(error: Throwable) {
        Log.e(TAG, "流错误", error)
        _connectionEvents.value = ConnectionEvent.Error("流错误", error)
        
        // 触发重连
        triggerReconnect()
    }
    
    /**
     * 处理流完成
     */
    private fun handleStreamCompleted() {
        Log.i(TAG, "流已完成")
        currentStreamObserver = null
        
        // 如果还在连接状态，重新建立流
        if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
            establishBidirectionalStream()
        }
    }
    
    /**
     * 处理发送错误
     */
    private fun handleSendError(error: Throwable) {
        Log.e(TAG, "发送错误", error)
        
        // 检查是否需要重连
        if (!grpcManager.isConnected()) {
            triggerReconnect()
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        Log.i(TAG, "清理连接管理器")
        scope.cancel()
    }
}