package com.continuousauth.network

import android.util.Log
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * 结构化错误处理器
 * 根据不同错误类型执行相应的重试策略
 */
@Singleton
class ErrorHandler @Inject constructor() {
    
    companion object {
        private const val TAG = "ErrorHandler"
        private const val DEFAULT_BASE_DELAY_MS = 1000L
        private const val DEFAULT_MAX_DELAY_MS = 30000L
        private const val DEFAULT_BACKOFF_MULTIPLIER = 2.0
        private const val DEFAULT_JITTER_FACTOR = 0.1
    }
    
    /**
     * 处理错误并决定重试策略
     */
    suspend fun handleError(
        throwable: Throwable,
        attemptNumber: Int,
        networkState: NetworkState = NetworkState.UNKNOWN
    ): ErrorHandlingResult {
        
        val errorType = classifyError(throwable)
        val retryStrategy = determineRetryStrategy(errorType, networkState, attemptNumber)
        
        Log.w(TAG, "错误处理 - 类型: $errorType, 尝试次数: $attemptNumber, 策略: ${retryStrategy.action}")
        Log.d(TAG, "错误详情", throwable)
        
        return when (retryStrategy.action) {
            RetryAction.IMMEDIATE_RETRY -> {
                ErrorHandlingResult.Retry(
                    delayMs = 0,
                    nextAttempt = attemptNumber + 1,
                    errorType = errorType,
                    message = "立即重试"
                )
            }
            
            RetryAction.EXPONENTIAL_BACKOFF -> {
                val delayMs = calculateExponentialBackoffDelay(
                    attemptNumber = attemptNumber,
                    baseDelayMs = retryStrategy.baseDelayMs,
                    maxDelayMs = retryStrategy.maxDelayMs,
                    multiplier = retryStrategy.backoffMultiplier
                )
                
                ErrorHandlingResult.Retry(
                    delayMs = delayMs,
                    nextAttempt = attemptNumber + 1,
                    errorType = errorType,
                    message = "指数退避重试，延迟${delayMs}ms"
                )
            }
            
            RetryAction.FIXED_DELAY -> {
                ErrorHandlingResult.Retry(
                    delayMs = retryStrategy.baseDelayMs,
                    nextAttempt = attemptNumber + 1,
                    errorType = errorType,
                    message = "固定延迟重试，延迟${retryStrategy.baseDelayMs}ms"
                )
            }
            
            RetryAction.GIVE_UP -> {
                ErrorHandlingResult.GiveUp(
                    errorType = errorType,
                    message = "放弃重试: ${retryStrategy.reason}",
                    originalError = throwable
                )
            }
        }
    }
    
    /**
     * 错误分类
     */
    private fun classifyError(throwable: Throwable): ErrorType {
        return when (throwable) {
            // gRPC相关错误
            is StatusException -> classifyGrpcError(throwable.status)
            is StatusRuntimeException -> classifyGrpcError(throwable.status)
            
            // 网络连接错误
            is ConnectException -> ErrorType.NETWORK_INTERRUPTION
            is SocketTimeoutException -> ErrorType.NETWORK_TIMEOUT
            is UnknownHostException -> ErrorType.DNS_RESOLUTION_FAILED
            
            // SSL/TLS错误
            is SSLException -> when {
                throwable.message?.contains("certificate", ignoreCase = true) == true -> 
                    ErrorType.CERTIFICATE_ERROR
                throwable.message?.contains("handshake", ignoreCase = true) == true -> 
                    ErrorType.TLS_HANDSHAKE_FAILED
                else -> ErrorType.AUTHENTICATION_FAILURE
            }
            
            is CertificateException -> ErrorType.CERTIFICATE_ERROR
            
            // IO错误
            is IOException -> when {
                throwable.message?.contains("timeout", ignoreCase = true) == true -> 
                    ErrorType.NETWORK_TIMEOUT
                throwable.message?.contains("connection", ignoreCase = true) == true -> 
                    ErrorType.NETWORK_INTERRUPTION
                else -> ErrorType.IO_ERROR
            }
            
            // 其他错误
            is OutOfMemoryError -> ErrorType.RESOURCE_EXHAUSTION
            is SecurityException -> ErrorType.PERMISSION_DENIED
            
            else -> ErrorType.UNKNOWN_ERROR
        }
    }
    
    /**
     * 分类gRPC错误
     */
    private fun classifyGrpcError(status: Status): ErrorType {
        return when (status.code) {
            Status.Code.OK -> ErrorType.NO_ERROR
            
            // 认证和授权错误
            Status.Code.UNAUTHENTICATED -> ErrorType.AUTHENTICATION_FAILURE
            Status.Code.PERMISSION_DENIED -> ErrorType.PERMISSION_DENIED
            
            // 网络和连接错误
            Status.Code.UNAVAILABLE -> ErrorType.SERVER_UNAVAILABLE
            Status.Code.DEADLINE_EXCEEDED -> ErrorType.NETWORK_TIMEOUT
            Status.Code.CANCELLED -> ErrorType.OPERATION_CANCELLED
            
            // 客户端错误
            Status.Code.INVALID_ARGUMENT -> ErrorType.INVALID_REQUEST
            Status.Code.NOT_FOUND -> ErrorType.ENDPOINT_NOT_FOUND
            Status.Code.ALREADY_EXISTS -> ErrorType.RESOURCE_CONFLICT
            Status.Code.FAILED_PRECONDITION -> ErrorType.PRECONDITION_FAILED
            Status.Code.OUT_OF_RANGE -> ErrorType.INVALID_REQUEST
            
            // 服务器错误
            Status.Code.INTERNAL -> ErrorType.SERVER_ERROR
            Status.Code.UNIMPLEMENTED -> ErrorType.FEATURE_NOT_SUPPORTED
            Status.Code.DATA_LOSS -> ErrorType.DATA_CORRUPTION
            
            // 资源错误
            Status.Code.RESOURCE_EXHAUSTED -> ErrorType.RESOURCE_EXHAUSTION
            
            // 未知错误
            Status.Code.UNKNOWN -> ErrorType.UNKNOWN_ERROR
            
            else -> ErrorType.UNKNOWN_ERROR
        }
    }
    
    /**
     * 决定重试策略
     */
    private fun determineRetryStrategy(
        errorType: ErrorType,
        networkState: NetworkState,
        attemptNumber: Int
    ): RetryStrategy {
        
        // 检查是否已达到最大尝试次数
        val maxAttempts = getMaxAttemptsForError(errorType, networkState)
        if (attemptNumber >= maxAttempts) {
            return RetryStrategy(
                action = RetryAction.GIVE_UP,
                reason = "已达到最大重试次数 ($maxAttempts)"
            )
        }
        
        return when (errorType) {
            // 立即重试的错误
            ErrorType.OPERATION_CANCELLED,
            ErrorType.NETWORK_TIMEOUT -> RetryStrategy(
                action = RetryAction.IMMEDIATE_RETRY
            )
            
            // 指数退避的错误
            ErrorType.NETWORK_INTERRUPTION,
            ErrorType.SERVER_UNAVAILABLE,
            ErrorType.RESOURCE_EXHAUSTION -> RetryStrategy(
                action = RetryAction.EXPONENTIAL_BACKOFF,
                baseDelayMs = adjustDelayForNetwork(DEFAULT_BASE_DELAY_MS, networkState),
                maxDelayMs = DEFAULT_MAX_DELAY_MS,
                backoffMultiplier = DEFAULT_BACKOFF_MULTIPLIER
            )
            
            // 固定延迟重试的错误
            ErrorType.SERVER_ERROR,
            ErrorType.DNS_RESOLUTION_FAILED,
            ErrorType.TLS_HANDSHAKE_FAILED -> RetryStrategy(
                action = RetryAction.FIXED_DELAY,
                baseDelayMs = adjustDelayForNetwork(5000L, networkState)
            )
            
            // 不重试的错误
            ErrorType.AUTHENTICATION_FAILURE,
            ErrorType.PERMISSION_DENIED,
            ErrorType.CERTIFICATE_ERROR,
            ErrorType.INVALID_REQUEST,
            ErrorType.ENDPOINT_NOT_FOUND,
            ErrorType.FEATURE_NOT_SUPPORTED,
            ErrorType.DATA_CORRUPTION,
            ErrorType.PRECONDITION_FAILED -> RetryStrategy(
                action = RetryAction.GIVE_UP,
                reason = "错误类型不支持重试: $errorType"
            )
            
            // 其他错误使用保守策略
            else -> RetryStrategy(
                action = RetryAction.EXPONENTIAL_BACKOFF,
                baseDelayMs = adjustDelayForNetwork(DEFAULT_BASE_DELAY_MS * 2, networkState),
                maxDelayMs = DEFAULT_MAX_DELAY_MS,
                backoffMultiplier = 1.5
            )
        }
    }
    
    /**
     * 根据错误类型和网络状态获取最大重试次数
     */
    private fun getMaxAttemptsForError(errorType: ErrorType, networkState: NetworkState): Int {
        val baseAttempts = when (errorType) {
            ErrorType.NETWORK_INTERRUPTION,
            ErrorType.NETWORK_TIMEOUT,
            ErrorType.SERVER_UNAVAILABLE -> 8
            
            ErrorType.SERVER_ERROR,
            ErrorType.RESOURCE_EXHAUSTION -> 5
            
            ErrorType.DNS_RESOLUTION_FAILED,
            ErrorType.TLS_HANDSHAKE_FAILED -> 3
            
            ErrorType.OPERATION_CANCELLED -> 10
            
            else -> 3
        }
        
        // 根据网络质量调整重试次数
        return when (networkState) {
            NetworkState.CELLULAR_POOR,
            NetworkState.WIFI_POOR -> (baseAttempts * 1.5).toInt()
            
            NetworkState.CELLULAR_EXCELLENT,
            NetworkState.WIFI_EXCELLENT -> maxOf(baseAttempts - 1, 2)
            
            else -> baseAttempts
        }
    }
    
    /**
     * 根据网络状态调整延迟时间
     */
    private fun adjustDelayForNetwork(baseDelayMs: Long, networkState: NetworkState): Long {
        return when (networkState) {
            NetworkState.CELLULAR_POOR,
            NetworkState.WIFI_POOR -> (baseDelayMs * 2)
            
            NetworkState.CELLULAR_EXCELLENT,
            NetworkState.WIFI_EXCELLENT -> (baseDelayMs * 0.7).toLong()
            
            else -> baseDelayMs
        }
    }
    
    /**
     * 计算指数退避延迟
     */
    private fun calculateExponentialBackoffDelay(
        attemptNumber: Int,
        baseDelayMs: Long,
        maxDelayMs: Long,
        multiplier: Double
    ): Long {
        // 指数退避公式: baseDelay * multiplier^(attempt-1)
        val exponentialDelay = baseDelayMs * multiplier.pow(attemptNumber - 1)
        
        // 加入随机抖动以避免惊群效应
        val jitter = Random.nextDouble(-DEFAULT_JITTER_FACTOR, DEFAULT_JITTER_FACTOR)
        val delayWithJitter = exponentialDelay * (1 + jitter)
        
        // 限制在最大延迟范围内
        return min(delayWithJitter.toLong(), maxDelayMs)
    }
    
    /**
     * 执行带延迟的重试
     */
    suspend fun executeWithRetry(delayMs: Long) {
        if (delayMs > 0) {
            Log.d(TAG, "等待 ${delayMs}ms 后重试")
            delay(delayMs)
        }
    }
}

/**
 * 错误类型枚举
 */
enum class ErrorType {
    NO_ERROR,                   // 无错误
    
    // 网络相关错误
    NETWORK_INTERRUPTION,       // 网络中断
    NETWORK_TIMEOUT,           // 网络超时
    DNS_RESOLUTION_FAILED,     // DNS解析失败
    
    // 认证和授权错误
    AUTHENTICATION_FAILURE,     // 认证失败
    PERMISSION_DENIED,         // 权限拒绝
    CERTIFICATE_ERROR,         // 证书错误
    TLS_HANDSHAKE_FAILED,      // TLS握手失败
    
    // 服务器错误
    SERVER_ERROR,              // 服务器内部错误
    SERVER_UNAVAILABLE,        // 服务器不可用
    ENDPOINT_NOT_FOUND,        // 端点未找到
    FEATURE_NOT_SUPPORTED,     // 功能不支持
    
    // 客户端错误
    INVALID_REQUEST,           // 无效请求
    RESOURCE_CONFLICT,         // 资源冲突
    PRECONDITION_FAILED,       // 前置条件失败
    
    // 资源错误
    RESOURCE_EXHAUSTION,       // 资源耗尽
    DATA_CORRUPTION,           // 数据损坏
    
    // 操作错误
    OPERATION_CANCELLED,       // 操作取消
    IO_ERROR,                  // IO错误
    
    // 其他
    UNKNOWN_ERROR              // 未知错误
}

/**
 * 重试动作枚举
 */
enum class RetryAction {
    IMMEDIATE_RETRY,           // 立即重试
    EXPONENTIAL_BACKOFF,       // 指数退避
    FIXED_DELAY,               // 固定延迟
    GIVE_UP                    // 放弃重试
}

/**
 * 重试策略
 */
data class RetryStrategy(
    val action: RetryAction,
    val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    val backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER,
    val reason: String = ""
) {
    companion object {
        private const val DEFAULT_BASE_DELAY_MS = 1000L
        private const val DEFAULT_MAX_DELAY_MS = 30000L
        private const val DEFAULT_BACKOFF_MULTIPLIER = 2.0
    }
}

/**
 * 错误处理结果
 */
sealed class ErrorHandlingResult {
    /**
     * 可以重试
     */
    data class Retry(
        val delayMs: Long,
        val nextAttempt: Int,
        val errorType: ErrorType,
        val message: String
    ) : ErrorHandlingResult()
    
    /**
     * 放弃重试
     */
    data class GiveUp(
        val errorType: ErrorType,
        val message: String,
        val originalError: Throwable
    ) : ErrorHandlingResult()
}