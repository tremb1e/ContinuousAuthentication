package com.continuousauth.observability

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 指标类型枚举
 * 定义了应用中收集的各种指标类型
 */
enum class MetricType {
    // 批处理指标
    BATCHES_PROCESSED,              // 已处理批次数
    BATCHES_CREATED,                // 已创建批次数
    BATCHES_ENCRYPTED,              // 已加密批次数
    
    // 上传指标
    UPLOADS_SUCCESS,                // 成功上传数
    UPLOADS_FAILED_NETWORK,         // 网络错误导致的上传失败数
    UPLOADS_FAILED_AUTH,            // 认证错误导致的上传失败数
    UPLOADS_FAILED_SERVER,          // 服务器错误导致的上传失败数
    UPLOADS_FAILED_TIMEOUT,         // 超时导致的上传失败数
    UPLOADS_RETRY,                  // 重试上传数
    
    // 传感器指标
    SENSOR_SAMPLES_COLLECTED,       // 传感器样本收集数
    SENSOR_SAMPLES_ACCELEROMETER,   // 加速度计样本数
    SENSOR_SAMPLES_GYROSCOPE,       // 陀螺仪样本数  
    SENSOR_SAMPLES_MAGNETOMETER,    // 磁力计样本数
    
    // 异常检测指标
    ANOMALIES_DETECTED,             // 异常检测数
    ANOMALIES_DEVICE_UNLOCK,        // 设备解锁异常数
    ANOMALIES_ACCELEROMETER_SPIKE,  // 加速度计突变异常数
    ANOMALIES_SENSITIVE_APP,        // 敏感应用异常数
    
    // 传输模式指标
    MODE_SWITCHES_TO_FAST,          // 切换到快速模式次数
    MODE_SWITCHES_TO_SLOW,          // 切换到慢速模式次数
    FAST_MODE_TOTAL_TIME,           // 快速模式总时间（毫秒）
    
    // 性能指标
    MEMORY_USAGE_PEAK,              // 峰值内存使用（字节）
    MEMORY_GC_COUNT,                // GC次数
    CPU_USAGE_PEAK,                 // 峰值CPU使用率（百分比）
    
    // 网络指标
    NETWORK_BYTES_SENT,             // 发送字节数
    NETWORK_BYTES_RECEIVED,         // 接收字节数
    NETWORK_CONNECTIONS_CREATED,    // 创建的连接数
    NETWORK_CONNECTIONS_FAILED,     // 连接失败数
    
    // 延迟指标
    UPLOAD_LATENCY_MS,              // 上传延迟（毫秒）
    ENCRYPTION_LATENCY_MS,          // 加密延迟（毫秒）
    PROCESSING_LATENCY_MS           // 处理延迟（毫秒）
}

/**
 * 指标值类型
 * 支持计数器和时序值两种类型
 */
sealed class MetricValue {
    /**
     * 计数器类型指标
     * 用于累计计数（如错误数、成功数等）
     */
    data class Counter(val value: Long) : MetricValue()
    
    /**
     * 时序值类型指标
     * 用于记录带时间戳的数值（如延迟、使用率等）
     */
    data class TimedValue(val value: Double, val timestamp: Long) : MetricValue()
}

/**
 * 指标快照
 * 包含某个时间点的所有指标数据
 */
data class MetricsSnapshot(
    val timestamp: Long,
    val counters: Map<MetricType, Long>,
    val timedValues: Map<MetricType, List<Pair<Double, Long>>>, // 值和时间戳对
    val summary: MetricsSummary
)

/**
 * 指标摘要
 * 提供关键指标的汇总信息
 */
data class MetricsSummary(
    val totalBatchesProcessed: Long,
    val totalUploadsSuccess: Long,
    val totalUploadsFailed: Long,
    val totalAnomaliesDetected: Long,
    val currentUploadSuccessRate: Double,        // 上传成功率
    val averageUploadLatency: Double,            // 平均上传延迟
    val peakMemoryUsage: Long,                   // 峰值内存使用
    val peakCpuUsage: Double                     // 峰值CPU使用率
)

/**
 * 指标收集器接口
 * 定义了指标收集的基本功能
 */
interface MetricsCollector {
    /**
     * 增加计数器指标
     * @param type 指标类型
     * @param increment 增加值（默认为1）
     */
    fun incrementCounter(type: MetricType, increment: Long = 1L)
    
    /**
     * 设置计数器指标的值
     * @param type 指标类型
     * @param value 设置的值
     */
    fun setCounter(type: MetricType, value: Long)
    
    /**
     * 记录时序值指标
     * @param type 指标类型
     * @param value 指标值
     * @param timestamp 时间戳（可选，默认为当前时间）
     */
    fun recordValue(type: MetricType, value: Double, timestamp: Long = System.currentTimeMillis())
    
    /**
     * 获取计数器值
     * @param type 指标类型
     * @return 计数器值
     */
    fun getCounter(type: MetricType): Long
    
    /**
     * 获取最近的时序值
     * @param type 指标类型
     * @param count 获取的数量（默认为1，即最新值）
     * @return 时序值列表
     */
    fun getRecentValues(type: MetricType, count: Int = 1): List<Pair<Double, Long>>
    
    /**
     * 获取指标快照
     * @return 当前所有指标的快照
     */
    fun getSnapshot(): MetricsSnapshot
    
    /**
     * 清除所有指标
     */
    fun clearAll()
    
    /**
     * 清除指定类型的指标
     * @param type 要清除的指标类型
     */
    fun clear(type: MetricType)
}

/**
 * 指标收集器实现
 * 使用ConcurrentHashMap收集结构化指标
 */
@Singleton
class MetricsCollectorImpl @Inject constructor() : MetricsCollector {
    
    companion object {
        private const val TAG = "MetricsCollector"
        private const val MAX_TIMED_VALUES_PER_METRIC = 1000 // 每个指标最多保存的时序值数量
    }
    
    // 计数器存储
    private val counters = ConcurrentHashMap<MetricType, AtomicLong>()
    
    // 时序值存储（每个指标类型对应一个队列）
    private val timedValues = ConcurrentHashMap<MetricType, MutableList<Pair<Double, Long>>>()
    
    // 指标收集开始时间
    private val startTime = System.currentTimeMillis()
    
    override fun incrementCounter(type: MetricType, increment: Long) {
        counters.computeIfAbsent(type) { AtomicLong(0) }.addAndGet(increment)
        
        Log.v(TAG, "计数器指标更新: $type += $increment")
    }
    
    override fun setCounter(type: MetricType, value: Long) {
        counters.computeIfAbsent(type) { AtomicLong(0) }.set(value)
        
        Log.v(TAG, "计数器指标设置: $type = $value")
    }
    
    override fun recordValue(type: MetricType, value: Double, timestamp: Long) {
        val valuesList = timedValues.computeIfAbsent(type) { 
            mutableListOf<Pair<Double, Long>>().apply {
                // 使用同步集合确保线程安全
            }
        }
        
        synchronized(valuesList) {
            valuesList.add(Pair(value, timestamp))
            
            // 保持队列大小在限制范围内
            if (valuesList.size > MAX_TIMED_VALUES_PER_METRIC) {
                valuesList.removeAt(0) // 移除最旧的值
            }
        }
        
        Log.v(TAG, "时序指标记录: $type = $value @ $timestamp")
    }
    
    override fun getCounter(type: MetricType): Long {
        return counters[type]?.get() ?: 0L
    }
    
    override fun getRecentValues(type: MetricType, count: Int): List<Pair<Double, Long>> {
        val valuesList = timedValues[type] ?: return emptyList()
        
        synchronized(valuesList) {
            val size = valuesList.size
            val startIndex = maxOf(0, size - count)
            return valuesList.subList(startIndex, size).toList()
        }
    }
    
    override fun getSnapshot(): MetricsSnapshot {
        val currentTime = System.currentTimeMillis()
        
        // 获取所有计数器快照
        val counterSnapshot = counters.mapValues { it.value.get() }
        
        // 获取所有时序值快照
        val timedSnapshot = mutableMapOf<MetricType, List<Pair<Double, Long>>>()
        for ((type, valuesList) in timedValues) {
            synchronized(valuesList) {
                timedSnapshot[type] = valuesList.toList()
            }
        }
        
        // 计算摘要
        val summary = calculateSummary(counterSnapshot, timedSnapshot)
        
        return MetricsSnapshot(
            timestamp = currentTime,
            counters = counterSnapshot,
            timedValues = timedSnapshot,
            summary = summary
        )
    }
    
    override fun clearAll() {
        counters.clear()
        timedValues.clear()
        Log.i(TAG, "所有指标已清除")
    }
    
    override fun clear(type: MetricType) {
        counters.remove(type)
        timedValues.remove(type)
        Log.i(TAG, "指标已清除: $type")
    }
    
    /**
     * 计算指标摘要
     */
    private fun calculateSummary(
        counters: Map<MetricType, Long>,
        timedValues: Map<MetricType, List<Pair<Double, Long>>>
    ): MetricsSummary {
        
        val totalBatchesProcessed = counters[MetricType.BATCHES_PROCESSED] ?: 0L
        val totalUploadsSuccess = counters[MetricType.UPLOADS_SUCCESS] ?: 0L
        val totalUploadsFailed = (counters[MetricType.UPLOADS_FAILED_NETWORK] ?: 0L) +
                (counters[MetricType.UPLOADS_FAILED_AUTH] ?: 0L) +
                (counters[MetricType.UPLOADS_FAILED_SERVER] ?: 0L) +
                (counters[MetricType.UPLOADS_FAILED_TIMEOUT] ?: 0L)
        
        val totalAnomaliesDetected = counters[MetricType.ANOMALIES_DETECTED] ?: 0L
        
        // 计算上传成功率
        val totalUploads = totalUploadsSuccess + totalUploadsFailed
        val uploadSuccessRate = if (totalUploads > 0) {
            totalUploadsSuccess.toDouble() / totalUploads * 100.0
        } else 0.0
        
        // 计算平均上传延迟
        val uploadLatencies = timedValues[MetricType.UPLOAD_LATENCY_MS] ?: emptyList()
        val averageUploadLatency = if (uploadLatencies.isNotEmpty()) {
            uploadLatencies.map { it.first }.average()
        } else 0.0
        
        // 获取峰值内存使用
        val memoryUsageValues = timedValues[MetricType.MEMORY_USAGE_PEAK] ?: emptyList()
        val peakMemoryUsage = memoryUsageValues.maxOfOrNull { it.first }?.toLong() ?: 0L
        
        // 获取峰值CPU使用率
        val cpuUsageValues = timedValues[MetricType.CPU_USAGE_PEAK] ?: emptyList()
        val peakCpuUsage = cpuUsageValues.maxOfOrNull { it.first } ?: 0.0
        
        return MetricsSummary(
            totalBatchesProcessed = totalBatchesProcessed,
            totalUploadsSuccess = totalUploadsSuccess,
            totalUploadsFailed = totalUploadsFailed,
            totalAnomaliesDetected = totalAnomaliesDetected,
            currentUploadSuccessRate = uploadSuccessRate,
            averageUploadLatency = averageUploadLatency,
            peakMemoryUsage = peakMemoryUsage,
            peakCpuUsage = peakCpuUsage
        )
    }
    
    /**
     * 获取运行时长（毫秒）
     */
    fun getUptime(): Long {
        return System.currentTimeMillis() - startTime
    }
    
    /**
     * 获取格式化的指标字符串（用于调试显示）
     */
    fun getFormattedMetrics(): String {
        val snapshot = getSnapshot()
        val sb = StringBuilder()
        
        sb.appendLine("=== 指标摘要 ===")
        sb.appendLine("运行时长: ${getUptime() / 1000}s")
        sb.appendLine("已处理批次: ${snapshot.summary.totalBatchesProcessed}")
        sb.appendLine("上传成功: ${snapshot.summary.totalUploadsSuccess}")
        sb.appendLine("上传失败: ${snapshot.summary.totalUploadsFailed}")
        sb.appendLine("成功率: ${"%.1f".format(snapshot.summary.currentUploadSuccessRate)}%")
        sb.appendLine("平均延迟: ${"%.1f".format(snapshot.summary.averageUploadLatency)}ms")
        sb.appendLine("异常检测: ${snapshot.summary.totalAnomaliesDetected}")
        sb.appendLine("峰值内存: ${snapshot.summary.peakMemoryUsage / 1024 / 1024}MB")
        sb.appendLine("峰值CPU: ${"%.1f".format(snapshot.summary.peakCpuUsage)}%")
        
        return sb.toString()
    }
}