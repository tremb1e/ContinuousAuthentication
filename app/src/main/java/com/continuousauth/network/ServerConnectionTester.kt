package com.continuousauth.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import io.grpc.ManagedChannelBuilder
import io.grpc.ConnectivityState
import com.continuousauth.proto.SensorDataServiceGrpc
import kotlinx.coroutines.TimeoutCancellationException

/**
 * 服务器连接测试器
 * 负责测试服务器的连接状态和响应时间
 */
@Singleton
class ServerConnectionTester @Inject constructor(
    private val grpcManager: GrpcManager
) {
    
    companion object {
        private const val TAG = "ServerConnectionTester"
        private const val DEFAULT_TIMEOUT_MS = 5000L
        private const val SOCKET_CONNECT_TIMEOUT_MS = 3000
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    
    /**
     * 测试服务器连接结果
     */
    data class TestResult(
        val isReachable: Boolean,
        val latencyMs: Long? = null,
        val statusCode: Int? = null,
        val errorMessage: String? = null,
        val testType: TestType,
        val details: Map<String, String> = emptyMap()
    )
    
    /**
     * 测试类型
     */
    enum class TestType {
        SOCKET_TEST,    // TCP Socket测试
        HTTP_TEST,      // HTTP请求测试
        GRPC_TEST      // gRPC连接测试
    }
    
    /**
     * 综合测试服务器连接
     */
    suspend fun testServerConnection(
        serverIp: String,
        serverPort: Int,
        useHttps: Boolean = false,
        testGrpc: Boolean = true
    ): TestResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始测试服务器连接: $serverIp:$serverPort")
            
            // 首先进行Socket层面的连接测试
            val socketResult = testSocketConnection(serverIp, serverPort)
            if (!socketResult.isReachable) {
                Log.w(TAG, "Socket连接失败: ${socketResult.errorMessage}")
                return@withContext socketResult
            }
            
            // 如果需要测试gRPC
            if (testGrpc) {
                val grpcResult = testGrpcConnection(serverIp, serverPort)
                if (grpcResult.isReachable) {
                    return@withContext grpcResult
                }
            }
            
            // 尝试HTTP/HTTPS测试
            val httpResult = testHttpConnection(serverIp, serverPort, useHttps)
            return@withContext httpResult
            
        } catch (e: Exception) {
            Log.e(TAG, "服务器连接测试异常", e)
            TestResult(
                isReachable = false,
                errorMessage = "测试异常: ${e.message}",
                testType = TestType.SOCKET_TEST
            )
        }
    }
    
    /**
     * 测试TCP Socket连接
     */
    private suspend fun testSocketConnection(
        serverIp: String,
        serverPort: Int
    ): TestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        try {
            withTimeout(SOCKET_CONNECT_TIMEOUT_MS.toLong()) {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(serverIp, serverPort),
                        SOCKET_CONNECT_TIMEOUT_MS
                    )
                    
                    val latency = System.currentTimeMillis() - startTime
                    
                    Log.i(TAG, "Socket连接成功: $serverIp:$serverPort, 延迟: ${latency}ms")
                    
                    TestResult(
                        isReachable = true,
                        latencyMs = latency,
                        testType = TestType.SOCKET_TEST,
                        details = mapOf(
                            "local_address" to socket.localAddress.toString(),
                            "remote_address" to socket.remoteSocketAddress.toString()
                        )
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
            TestResult(
                isReachable = false,
                errorMessage = "连接超时",
                testType = TestType.SOCKET_TEST
            )
        } catch (e: Exception) {
            TestResult(
                isReachable = false,
                errorMessage = "Socket错误: ${e.message}",
                testType = TestType.SOCKET_TEST
            )
        }
    }
    
    /**
     * 测试HTTP/HTTPS连接
     */
    private suspend fun testHttpConnection(
        serverIp: String,
        serverPort: Int,
        useHttps: Boolean
    ): TestResult = withContext(Dispatchers.IO) {
        val protocol = if (useHttps) "https" else "http"
        val url = "$protocol://$serverIp:$serverPort/health"
        val startTime = System.currentTimeMillis()
        
        try {
            val request = Request.Builder()
                .url(url)
                .head() // 使用HEAD请求减少数据传输
                .build()
            
            val response = httpClient.newCall(request).execute()
            val latency = System.currentTimeMillis() - startTime
            
            response.use {
                Log.i(TAG, "HTTP测试完成: $url, 状态码: ${response.code}, 延迟: ${latency}ms")
                
                TestResult(
                    isReachable = response.isSuccessful,
                    latencyMs = latency,
                    statusCode = response.code,
                    testType = TestType.HTTP_TEST,
                    details = mapOf(
                        "protocol" to response.protocol.toString(),
                        "message" to response.message
                    )
                )
            }
        } catch (e: Exception) {
            TestResult(
                isReachable = false,
                errorMessage = "HTTP错误: ${e.message}",
                testType = TestType.HTTP_TEST
            )
        }
    }
    
    /**
     * 测试gRPC连接
     * 真正测试gRPC服务是否可用
     */
    private suspend fun testGrpcConnection(
        serverIp: String,
        serverPort: Int
    ): TestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var testChannel: io.grpc.ManagedChannel? = null
        
        try {
            // 创建临时的gRPC通道进行测试
            testChannel = ManagedChannelBuilder
                .forAddress(serverIp, serverPort)
                .usePlaintext() // 开发环境使用明文
                .build()
            
            // 等待通道连接
            withTimeout(DEFAULT_TIMEOUT_MS) {
                var attempts = 0
                while (testChannel.getState(true) != ConnectivityState.READY && attempts < 50) {
                    delay(100) // 等待100ms
                    attempts++
                }
                
                if (testChannel.getState(false) != ConnectivityState.READY) {
                    throw Exception("无法建立gRPC连接")
                }
            }
            
            val latency = System.currentTimeMillis() - startTime
            
            // 尝试创建stub验证服务是否存在
            val stub = SensorDataServiceGrpc.newBlockingStub(testChannel)
                .withDeadlineAfter(2, TimeUnit.SECONDS)
            
            // 通道已连接，表示gRPC服务可用
            Log.i(TAG, "gRPC服务测试成功: $serverIp:$serverPort, 延迟: ${latency}ms")
            
            TestResult(
                isReachable = true,
                latencyMs = latency,
                testType = TestType.GRPC_TEST,
                details = mapOf(
                    "state" to testChannel.getState(false).toString(),
                    "service" to "SensorDataService"
                )
            )
            
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "gRPC连接超时")
            TestResult(
                isReachable = false,
                errorMessage = "gRPC服务连接超时",
                testType = TestType.GRPC_TEST
            )
        } catch (e: Exception) {
            Log.e(TAG, "gRPC连接失败", e)
            TestResult(
                isReachable = false,
                errorMessage = "gRPC服务不可用: ${e.message}",
                testType = TestType.GRPC_TEST
            )
        } finally {
            // 清理测试通道
            try {
                testChannel?.shutdown()
                testChannel?.awaitTermination(1, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "关闭测试通道时出错", e)
            }
        }
    }
    
    /**
     * 批量测试多个服务器
     */
    suspend fun testMultipleServers(
        servers: List<Pair<String, Int>>
    ): List<Pair<Pair<String, Int>, TestResult>> = withContext(Dispatchers.IO) {
        servers.map { server ->
            server to testServerConnection(server.first, server.second)
        }
    }
    
    /**
     * 获取测试结果的可读描述
     */
    fun getTestResultDescription(result: TestResult): String {
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