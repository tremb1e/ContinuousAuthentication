package com.continuousauth.performance

import android.util.Log
import com.continuousauth.buffer.InMemoryBuffer
import com.continuousauth.compression.CompressionManager
import com.continuousauth.network.UploadManager
import com.continuousauth.sensor.SensorCollector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 动态性能优化器
 * 根据性能报告自动调整系统参数
 * 符合 claude.md Epic 4.1 要求
 */
@Singleton
class DynamicOptimizer @Inject constructor(
    private val sensorCollector: SensorCollector,
    private val compressionManager: CompressionManager,
    private val inMemoryBuffer: InMemoryBuffer,
    private val uploadManager: UploadManager
) {
    
    companion object {
        private const val TAG = "DynamicOptimizer"
        
        // 性能阈值
        private const val MEMORY_PRESSURE_THRESHOLD = 0.8f  // 80% 内存使用率
        private const val BATTERY_DRAIN_THRESHOLD = 5f      // 5% 每小时
        private const val LATENCY_THRESHOLD = 1000f         // 1秒延迟
        private const val CPU_USAGE_THRESHOLD = 0.7f        // 70% CPU使用率
    }
    
    /**
     * 性能报告数据类
     */
    data class PerformanceReport(
        val avgLatency: Float,          // 平均延迟（毫秒）
        val memoryUsage: Float,          // 内存使用率（0-1）
        val batteryDrain: Float,         // 电池消耗率（%/小时）
        val cpuUsage: Float,             // CPU使用率（0-1）
        val networkThroughput: Float,   // 网络吞吐量（KB/s）
        val errorRate: Float,            // 错误率（0-1）
        val queueSize: Int,              // 队列大小
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * 优化配置
     */
    data class OptimizationConfig(
        var samplingRateMultiplier: Float = 1.0f,
        var compressionLevel: CompressionManager.CompressionType = CompressionManager.CompressionType.GZIP,
        var bufferSizeMultiplier: Float = 1.0f,
        var batchingEnabled: Boolean = true,
        var wifiOnlyMode: Boolean = false,
        var localCachingEnabled: Boolean = false
    )
    
    private var currentConfig = OptimizationConfig()
    private var lastOptimizationTime = 0L
    private val OPTIMIZATION_COOLDOWN = 60_000L // 1分钟冷却期
    
    /**
     * 根据性能报告优化系统
     */
    fun optimizeBasedOnPerformance(report: PerformanceReport) {
        val now = System.currentTimeMillis()
        
        // 检查冷却期
        if (now - lastOptimizationTime < OPTIMIZATION_COOLDOWN) {
            Log.v(TAG, "优化冷却期中，跳过本次优化")
            return
        }
        
        Log.i(TAG, "开始性能优化 - 内存:${report.memoryUsage}, 电池:${report.batteryDrain}, 延迟:${report.avgLatency}")
        
        var optimizationApplied = false
        
        // 内存压力优化
        if (report.memoryUsage > MEMORY_PRESSURE_THRESHOLD) {
            handleMemoryPressure(report)
            optimizationApplied = true
        }
        
        // 电池消耗优化
        if (report.batteryDrain > BATTERY_DRAIN_THRESHOLD) {
            handleBatteryDrain(report)
            optimizationApplied = true
        }
        
        // 网络延迟优化
        if (report.avgLatency > LATENCY_THRESHOLD) {
            handleHighLatency(report)
            optimizationApplied = true
        }
        
        // CPU使用率优化
        if (report.cpuUsage > CPU_USAGE_THRESHOLD) {
            handleHighCpuUsage(report)
            optimizationApplied = true
        }
        
        // 错误率优化
        if (report.errorRate > 0.1f) {
            handleHighErrorRate(report)
            optimizationApplied = true
        }
        
        // 如果系统表现良好，尝试提升性能
        if (!optimizationApplied && isPerformanceGood(report)) {
            tryImprovePerformance(report)
        }
        
        if (optimizationApplied) {
            lastOptimizationTime = now
            applyOptimizations()
        }
    }
    
    /**
     * 处理内存压力
     */
    private fun handleMemoryPressure(report: PerformanceReport) {
        Log.w(TAG, "检测到内存压力: ${report.memoryUsage * 100}%")
        
        // 减少缓冲区大小
        currentConfig.bufferSizeMultiplier = maxOf(0.5f, currentConfig.bufferSizeMultiplier * 0.8f)
        
        // 增加压缩级别
        if (currentConfig.compressionLevel == CompressionManager.CompressionType.NONE) {
            currentConfig.compressionLevel = CompressionManager.CompressionType.GZIP
        }
        
        // 启用本地缓存以减少内存队列
        currentConfig.localCachingEnabled = true
        
        // 降低采样率
        if (report.memoryUsage > 0.9f) {
            currentConfig.samplingRateMultiplier = maxOf(0.5f, currentConfig.samplingRateMultiplier * 0.8f)
        }
    }
    
    /**
     * 处理电池消耗
     */
    private fun handleBatteryDrain(report: PerformanceReport) {
        Log.w(TAG, "检测到高电池消耗: ${report.batteryDrain}%/小时")
        
        // 降低采样率
        currentConfig.samplingRateMultiplier = maxOf(0.5f, currentConfig.samplingRateMultiplier * 0.9f)
        
        // 启用批处理模式
        currentConfig.batchingEnabled = true
        
        // 考虑切换到仅WiFi模式
        if (report.batteryDrain > BATTERY_DRAIN_THRESHOLD * 1.5f) {
            currentConfig.wifiOnlyMode = true
        }
    }
    
    /**
     * 处理高延迟
     */
    private fun handleHighLatency(report: PerformanceReport) {
        Log.w(TAG, "检测到高延迟: ${report.avgLatency}ms")
        
        // 切换到仅WiFi上传
        currentConfig.wifiOnlyMode = true
        
        // 启用本地缓存
        currentConfig.localCachingEnabled = true
        
        // 增加批处理大小
        currentConfig.batchingEnabled = true
        
        // 如果延迟极高，考虑降低数据产生速率
        if (report.avgLatency > LATENCY_THRESHOLD * 2) {
            currentConfig.samplingRateMultiplier = maxOf(0.7f, currentConfig.samplingRateMultiplier * 0.95f)
        }
    }
    
    /**
     * 处理高CPU使用率
     */
    private fun handleHighCpuUsage(report: PerformanceReport) {
        Log.w(TAG, "检测到高CPU使用率: ${report.cpuUsage * 100}%")
        
        // 降低采样率
        currentConfig.samplingRateMultiplier = maxOf(0.6f, currentConfig.samplingRateMultiplier * 0.85f)
        
        // 减少压缩（如果使用高CPU的压缩算法）
        if (currentConfig.compressionLevel == CompressionManager.CompressionType.GZIP) {
            // 未来可以切换到 LZ4 等更快的算法
            Log.i(TAG, "考虑切换到更快的压缩算法")
        }
    }
    
    /**
     * 处理高错误率
     */
    private fun handleHighErrorRate(report: PerformanceReport) {
        Log.w(TAG, "检测到高错误率: ${report.errorRate * 100}%")
        
        // 启用本地缓存以避免数据丢失
        currentConfig.localCachingEnabled = true
        
        // 减少数据产生速率以降低系统压力
        currentConfig.samplingRateMultiplier = maxOf(0.7f, currentConfig.samplingRateMultiplier * 0.9f)
        
        // 启用批处理以减少网络请求次数
        currentConfig.batchingEnabled = true
    }
    
    /**
     * 检查性能是否良好
     */
    private fun isPerformanceGood(report: PerformanceReport): Boolean {
        return report.memoryUsage < 0.5f &&
               report.batteryDrain < 2f &&
               report.avgLatency < 500f &&
               report.cpuUsage < 0.4f &&
               report.errorRate < 0.01f
    }
    
    /**
     * 尝试提升性能
     */
    private fun tryImprovePerformance(report: PerformanceReport) {
        Log.i(TAG, "系统性能良好，尝试提升性能")
        
        // 逐步提高采样率
        if (currentConfig.samplingRateMultiplier < 1.0f) {
            currentConfig.samplingRateMultiplier = minOf(1.0f, currentConfig.samplingRateMultiplier * 1.1f)
        }
        
        // 增加缓冲区大小
        if (currentConfig.bufferSizeMultiplier < 1.0f) {
            currentConfig.bufferSizeMultiplier = minOf(1.0f, currentConfig.bufferSizeMultiplier * 1.2f)
        }
        
        // 如果网络状况良好，可以关闭仅WiFi模式
        if (report.avgLatency < 200f && currentConfig.wifiOnlyMode) {
            currentConfig.wifiOnlyMode = false
        }
    }
    
    /**
     * 应用优化配置
     */
    private fun applyOptimizations() {
        Log.i(TAG, "应用优化配置: $currentConfig")
        
        // 应用采样率调整
        sensorCollector.adjustSamplingRate(currentConfig.samplingRateMultiplier)
        
        // 应用缓冲区调整
        inMemoryBuffer.adjustBufferSize(currentConfig.bufferSizeMultiplier)
        
        // 应用传输策略
        uploadManager.setWifiOnlyMode(currentConfig.wifiOnlyMode)
        uploadManager.setLocalCachingEnabled(currentConfig.localCachingEnabled)
        
        // 通知其他组件配置变更
        notifyConfigurationChange()
    }
    
    /**
     * 通知配置变更
     */
    private fun notifyConfigurationChange() {
        // 这里可以使用事件总线或回调通知其他组件
        Log.d(TAG, "配置已更新并通知相关组件")
    }
    
    /**
     * 获取当前优化配置
     */
    fun getCurrentConfig(): OptimizationConfig {
        return currentConfig.copy()
    }
    
    /**
     * 重置优化配置
     */
    fun resetOptimizations() {
        Log.i(TAG, "重置优化配置")
        currentConfig = OptimizationConfig()
        applyOptimizations()
    }
    
    /**
     * 手动设置优化配置
     */
    fun setOptimizationConfig(config: OptimizationConfig) {
        Log.i(TAG, "手动设置优化配置: $config")
        currentConfig = config
        applyOptimizations()
    }
}