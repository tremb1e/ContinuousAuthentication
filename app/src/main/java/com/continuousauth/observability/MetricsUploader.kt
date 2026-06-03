package com.continuousauth.observability

import android.util.Log
import com.continuousauth.proto.MetricsReport
import com.continuousauth.proto.MetricsResponse
import com.continuousauth.proto.SensorDataServiceGrpc
import com.continuousauth.crypto.EnvelopeCryptoBox
import io.grpc.ManagedChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * 指标上报器
 * 负责定期将聚合后的可观测性指标上报到服务器
 * 注意：仅上报聚合指标，不包含敏感数据
 * 
 * 该类为预留接口实现，当前默认关闭
 * 可通过 enableMetricsReporting() 方法启用
 */
@Singleton
class MetricsUploader @Inject constructor(
    private val metricsCollector: MetricsCollectorImpl,
    private val performanceMonitor: PerformanceMonitorImpl,
    private val envelopeCryptoBox: EnvelopeCryptoBox
) {
    
    companion object {
        private const val TAG = "MetricsUploader"
        
        // 上报配置
        private const val DEFAULT_REPORT_INTERVAL_MS = 60000L  // 默认每分钟上报一次
        private const val MIN_REPORT_INTERVAL_MS = 10000L      // 最小上报间隔10秒
        private const val MAX_RETRY_ATTEMPTS = 3               // 最大重试次数
        private const val RETRY_DELAY_MS = 5000L               // 重试延迟
    }
    
    // 状态管理
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    
    private var reportingJob: Job? = null
    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // gRPC通道和客户端（需要在启用时初始化）
    private var channel: ManagedChannel? = null
    private var serviceStub: SensorDataServiceGrpc.SensorDataServiceStub? = null
    
    // 上报统计
    private var totalReportsSent = 0L
    private var totalReportsSuccess = 0L
    private var totalReportsFailed = 0L
    private var lastReportTime = 0L
    
    /**
     * 启用指标上报
     * @param serverEndpoint 服务器端点
     * @param intervalMs 上报间隔（毫秒）
     */
    suspend fun enableMetricsReporting(
        serverEndpoint: String,
        intervalMs: Long = DEFAULT_REPORT_INTERVAL_MS
    ) {
        if (_enabled.value) {
            Log.w(TAG, "指标上报已启用")
            return
        }
        
        val actualInterval = max(intervalMs, MIN_REPORT_INTERVAL_MS)
        
        try {
            // TODO: 初始化gRPC通道
            // channel = ManagedChannelBuilder.forTarget(serverEndpoint)
            //     .usePlaintext() // 或使用TLS
            //     .build()
            // serviceStub = SensorDataServiceGrpc.newStub(channel)
            
            _enabled.value = true
            
            // 启动定期上报任务
            reportingJob = uploadScope.launch {
                while (isActive && _enabled.value) {
                    try {
                        uploadMetrics()
                    } catch (e: Exception) {
                        Log.e(TAG, "上报指标失败", e)
                    }
                    delay(actualInterval)
                }
            }
            
            Log.i(TAG, "指标上报已启用，间隔: ${actualInterval}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "启用指标上报失败", e)
            _enabled.value = false
        }
    }
    
    /**
     * 禁用指标上报
     */
    suspend fun disableMetricsReporting() {
        if (!_enabled.value) {
            return
        }
        
        _enabled.value = false
        reportingJob?.cancel()
        reportingJob = null
        
        // 关闭gRPC通道
        channel?.shutdown()
        channel = null
        serviceStub = null
        
        Log.i(TAG, "指标上报已禁用")
    }
    
    /**
     * 上传指标到服务器
     */
    private suspend fun uploadMetrics() = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()
        val reportPeriod = if (lastReportTime > 0) {
            currentTime - lastReportTime
        } else {
            DEFAULT_REPORT_INTERVAL_MS
        }
        
        // 构建指标报告
        val report = buildMetricsReport(currentTime, reportPeriod)
        
        // 重试逻辑
        var retryCount = 0
        var success = false
        
        while (retryCount < MAX_RETRY_ATTEMPTS && !success) {
            try {
                // TODO: 实际发送到服务器
                // val response = serviceStub?.reportMetrics(report)?.await()
                
                // 模拟发送（当前仅记录日志）
                Log.d(TAG, "准备上报指标: " +
                    "batches=${report.batchesProcessed}, " +
                    "uploads_success=${report.uploadsSuccess}, " +
                    "uploads_failed=${report.uploadsFailed}, " +
                    "success_rate=${report.uploadSuccessRate}%")
                
                // 模拟成功
                success = true
                totalReportsSuccess++
                lastReportTime = currentTime
                
                Log.i(TAG, "指标上报成功")
                
            } catch (e: Exception) {
                retryCount++
                totalReportsFailed++
                
                if (retryCount < MAX_RETRY_ATTEMPTS) {
                    Log.w(TAG, "指标上报失败，重试 $retryCount/$MAX_RETRY_ATTEMPTS", e)
                    delay(RETRY_DELAY_MS)
                } else {
                    Log.e(TAG, "指标上报最终失败", e)
                }
            }
        }
        
        totalReportsSent++
    }
    
    /**
     * 构建指标报告
     * 注意：仅包含聚合指标，不包含敏感数据
     */
    private fun buildMetricsReport(timestamp: Long, periodMs: Long): MetricsReport {
        // 获取指标快照
        val metricsSnapshot = metricsCollector.getSnapshot()
        val performanceStats = performanceMonitor.getPerformanceStats(periodMs)
        
        // 获取与数据包一致的设备标识 HMAC（非明文）
        val deviceIdHash = envelopeCryptoBox.getDeviceIdHash()
        
        return MetricsReport.newBuilder().apply {
            this.deviceIdHash = deviceIdHash
            this.timestampMs = timestamp
            this.reportingPeriodMs = periodMs
            
            // 批处理和上传指标
            batchesProcessed = metricsSnapshot.counters[MetricType.BATCHES_PROCESSED] ?: 0
            uploadsSuccess = metricsSnapshot.counters[MetricType.UPLOADS_SUCCESS] ?: 0
            uploadsFailed = (metricsSnapshot.counters[MetricType.UPLOADS_FAILED_NETWORK] ?: 0) +
                    (metricsSnapshot.counters[MetricType.UPLOADS_FAILED_AUTH] ?: 0) +
                    (metricsSnapshot.counters[MetricType.UPLOADS_FAILED_SERVER] ?: 0) +
                    (metricsSnapshot.counters[MetricType.UPLOADS_FAILED_TIMEOUT] ?: 0)
            
            // 传感器和异常指标
            sensorSamplesCollected = metricsSnapshot.counters[MetricType.SENSOR_SAMPLES_COLLECTED] ?: 0
            anomaliesDetected = metricsSnapshot.counters[MetricType.ANOMALIES_DETECTED] ?: 0
            
            // 性能指标
            avgUploadLatencyMs = metricsSnapshot.summary.averageUploadLatency
            avgCpuUsagePercent = performanceStats.cpuUsageAvg
            peakMemoryUsageMb = performanceStats.memoryUsagePeak
            
            // 传输统计 (不再需要模式切换)
            // modeSwitchesToFast = 0
            // modeSwitchesToSlow = 0
            // fastModeTotalTimeMs = 0
            
            // 网络统计（proto中未定义这些字段，暂时注释）
            // networkBytesSent = metricsSnapshot.counters[MetricType.NETWORK_BYTES_SENT] ?: 0
            // networkBytesReceived = metricsSnapshot.counters[MetricType.NETWORK_BYTES_RECEIVED] ?: 0
            
            // 上传成功率
            uploadSuccessRate = metricsSnapshot.summary.currentUploadSuccessRate
        }.build()
    }
    
    /**
     * 获取上报统计信息
     */
    fun getUploadStats(): MetricsUploadStats {
        return MetricsUploadStats(
            enabled = _enabled.value,
            totalReportsSent = totalReportsSent,
            totalReportsSuccess = totalReportsSuccess,
            totalReportsFailed = totalReportsFailed,
            lastReportTime = lastReportTime,
            successRate = if (totalReportsSent > 0) {
                (totalReportsSuccess.toDouble() / totalReportsSent * 100)
            } else 0.0
        )
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        uploadScope.launch {
            disableMetricsReporting()
        }
        uploadScope.cancel()
    }
}

/**
 * 指标上报统计信息
 */
data class MetricsUploadStats(
    val enabled: Boolean,
    val totalReportsSent: Long,
    val totalReportsSuccess: Long,
    val totalReportsFailed: Long,
    val lastReportTime: Long,
    val successRate: Double
)

// 辅助扩展函数
private fun max(a: Long, b: Long): Long = if (a > b) a else b
