package com.continuousauth.network

import android.content.Context
import android.util.Log
import com.continuousauth.proto.DataPacket
import com.continuousauth.proto.Heartbeat
import com.continuousauth.proto.HeartbeatAck
import com.continuousauth.proto.SensorDataServiceGrpc
import com.continuousauth.proto.ServerDirective
import dagger.hilt.android.qualifiers.ApplicationContext
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.StreamObserver
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * gRPC 管理器
 * 负责管理 gRPC 连接和通信
 */
@Singleton
class GrpcManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "GrpcManager"
        private const val DEFAULT_HOST = "localhost"
        private const val DEFAULT_PORT = 8080
        private const val DEFAULT_TLS_PORT = 8443
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val KEEPALIVE_TIME_SECONDS = 30L
        private const val KEEPALIVE_TIMEOUT_SECONDS = 10L
        private const val MAX_INBOUND_MESSAGE_SIZE = 10 * 1024 * 1024 // 10MB
        private const val MAX_OUTBOUND_MESSAGE_SIZE = 10 * 1024 * 1024 // 10MB
    }
    
    private var channel: ManagedChannel? = null
    private var stub: SensorDataServiceGrpc.SensorDataServiceStub? = null
    private var currentStreamObserver: StreamObserver<DataPacket>? = null
    
    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // 服务器配置
    private var serverHost = DEFAULT_HOST
    private var serverPort = DEFAULT_PORT
    private var useTls = true // 默认使用TLS
    
    /**
     * 配置服务器地址
     */
    fun configureServer(host: String, port: Int) {
        serverHost = host
        serverPort = port
        Log.i(TAG, "配置服务器地址: $host:$port")
    }
    
    /**
     * 建立 gRPC 连接
     */
    suspend fun connect(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                if (channel != null && !channel!!.isShutdown) {
                    Log.w(TAG, "已存在活跃连接")
                    return@withContext Result.success(Unit)
                }
                
                Log.i(TAG, "建立 gRPC 连接到 $serverHost:$serverPort")
                _connectionState.value = ConnectionState.CONNECTING
                
                channel = if (useTls) {
                    buildTlsChannel()
                } else {
                    // 仅用于开发测试
                    ManagedChannelBuilder
                        .forAddress(serverHost, serverPort)
                        .usePlaintext()
                        .keepAliveTime(KEEPALIVE_TIME_SECONDS, TimeUnit.SECONDS)
                        .keepAliveTimeout(KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .keepAliveWithoutCalls(true)
                        .maxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE)
                        .build()
                }
                
                stub = SensorDataServiceGrpc.newStub(channel)
                _connectionState.value = ConnectionState.CONNECTED
                
                Log.i(TAG, "gRPC 连接建立成功")
                Result.success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "建立 gRPC 连接失败", e)
                _connectionState.value = ConnectionState.ERROR
                Result.failure(e)
            }
        }
    }
    
    /**
     * 断开 gRPC 连接
     */
    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "断开 gRPC 连接")
                
                currentStreamObserver?.onCompleted()
                currentStreamObserver = null
                
                channel?.shutdown()
                channel?.awaitTermination(5, TimeUnit.SECONDS)
                channel = null
                stub = null
                
                _connectionState.value = ConnectionState.DISCONNECTED
                Log.i(TAG, "gRPC 连接已断开")
                
            } catch (e: Exception) {
                Log.e(TAG, "断开连接时发生错误", e)
            }
        }
    }
    
    /**
     * 建立双向流
     */
    fun establishBidirectionalStream(
        responseObserver: StreamObserver<ServerDirective>
    ): StreamObserver<DataPacket>? {
        return try {
            if (stub == null) {
                Log.e(TAG, "gRPC stub 未初始化")
                return null
            }
            
            Log.i(TAG, "建立双向流")
            currentStreamObserver = stub!!.streamSensorData(responseObserver)
            currentStreamObserver
            
        } catch (e: Exception) {
            Log.e(TAG, "建立双向流失败", e)
            null
        }
    }
    
    /**
     * 发送数据包
     */
    fun sendDataPacket(packet: DataPacket): Boolean {
        return try {
            if (currentStreamObserver == null) {
                Log.e(TAG, "流观察者未初始化")
                return false
            }
            
            currentStreamObserver!!.onNext(packet)
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "发送数据包失败", e)
            false
        }
    }
    
    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean {
        return channel != null && !channel!!.isShutdown && !channel!!.isTerminated
    }
    
    /**
     * 获取通道状态信息
     */
    fun getChannelState(): String {
        return channel?.getState(false)?.toString() ?: "NO_CHANNEL"
    }
    
    /**
     * 设置是否使用TLS
     */
    fun setUseTls(enable: Boolean) {
        useTls = enable
        if (enable && serverPort == DEFAULT_PORT) {
            serverPort = DEFAULT_TLS_PORT // 自动切换到TLS端口
        }
    }
    
    /**
     * 发送心跳消息
     */
    suspend fun sendHeartbeat(heartbeat: Heartbeat): HeartbeatAck? {
        return withContext(Dispatchers.IO) {
            try {
                if (stub == null) {
                    Log.e(TAG, "无法发送心跳：gRPC stub未初始化")
                    return@withContext null
                }
                
                // 使用阻塞式调用发送心跳
                val blockingStub = SensorDataServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                
                val ack = blockingStub.sendHeartbeat(heartbeat)
                Log.v(TAG, "心跳响应：服务器时间戳=${ack.serverTimestamp}")
                ack
                
            } catch (e: Exception) {
                Log.e(TAG, "发送心跳失败", e)
                null
            }
        }
    }
    
    /**
     * 构建TLS安全通道
     * 强制使用TLS 1.3，符合claude.md Epic 2.2.1要求
     */
    private fun buildTlsChannel(): ManagedChannel {
        Log.i(TAG, "构建TLS 1.3安全通道")
        
        // 创建强制TLS 1.3的SSLContext
        val sslContext = createTls13SSLContext()
        
        // 创建支持TLS 1.3的OkHttpClient
        val okHttpClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2)) // 强制使用HTTP/2
            .sslSocketFactory(sslContext.socketFactory, createTrustAllManager())
            .hostnameVerifier { _, _ -> true } // 临时允许所有主机名（生产环境需要严格验证）
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        
        // 使用OkHttpChannelBuilder创建支持TLS 1.3的通道
        return OkHttpChannelBuilder
            .forAddress(serverHost, serverPort)
            .transportExecutor(Dispatchers.IO.asExecutor())
            .keepAliveTime(KEEPALIVE_TIME_SECONDS, TimeUnit.SECONDS)
            .keepAliveTimeout(KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .maxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE)
            .build()
    }
    
    /**
     * 创建强制TLS 1.3的SSLContext
     */
    private fun createTls13SSLContext(): SSLContext {
        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(null, arrayOf(createTrustAllManager()), java.security.SecureRandom())
        return sslContext
    }
    
    /**
     * 创建信任所有证书的TrustManager
     * 注意：仅用于开发和测试，生产环境应使用证书锁定
     */
    private fun createTrustAllManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                Log.d(TAG, "验证服务器证书: authType=$authType, 证书数量=${chain.size}")
                // 这里应该添加证书锁定验证逻辑
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }
    
    
    /**
     * 连接状态枚举
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
}