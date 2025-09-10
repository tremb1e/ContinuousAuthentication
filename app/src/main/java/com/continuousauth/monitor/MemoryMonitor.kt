package com.continuousauth.monitor

import android.util.Log
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内存监控器
 * 监控应用堆内存使用率，当内存使用超过阈值时主动建议GC
 */
@Singleton
class MemoryMonitor @Inject constructor() {
    
    companion object {
        private const val DEFAULT_THRESHOLD = 0.8 // 默认阈值80%
        private const val CRITICAL_THRESHOLD = 0.9 // 危险阈值90%
        private const val MONITORING_INTERVAL_MS = 5000L // 监控间隔5秒
        private const val HISTORY_SIZE = 60 // 保留最近1分钟的历史数据（每5秒一次）
        private const val GC_COOLDOWN_MS = 30000L // GC冷却时间30秒
        private const val TAG = "MemoryMonitor"
    }
    
    // 监控状态
    private val isMonitoring = AtomicBoolean(false)
    private val lastGcTime = AtomicLong(0L)
    
    // 历史数据（环形缓冲区）
    private val memoryHistory = ConcurrentLinkedQueue<MemorySnapshot>()
    
    // 协程作用域
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitoringJob: Job? = null
    
    // 配置
    private var warningThreshold = DEFAULT_THRESHOLD
    private var criticalThreshold = CRITICAL_THRESHOLD
    
    // 回调
    private var onMemoryWarning: ((MemoryStatus) -> Unit)? = null
    private var onMemoryCritical: ((MemoryStatus) -> Unit)? = null
    
    /**
     * 开始内存监控
     */
    fun startMonitoring(
        warningThreshold: Double = DEFAULT_THRESHOLD,
        criticalThreshold: Double = CRITICAL_THRESHOLD
    ) {
        if (isMonitoring.get()) {
            Log.w(TAG, "内存监控已在运行中")
            return
        }
        
        this.warningThreshold = warningThreshold
        this.criticalThreshold = criticalThreshold
        
        monitoringJob = monitoringScope.launch {
            performMonitoring()
        }
        
        isMonitoring.set(true)
        Log.i(TAG, "内存监控已启动 - 警告阈值: ${(warningThreshold * 100).toInt()}%, 危险阈值: ${(criticalThreshold * 100).toInt()}%")
    }
    
    /**
     * 停止内存监控
     */
    fun stopMonitoring() {
        if (!isMonitoring.get()) {
            return
        }
        
        monitoringJob?.cancel()
        isMonitoring.set(false)
        
        Log.i(TAG, "内存监控已停止")
    }
    
    /**
     * 设置内存警告回调
     */
    fun setOnMemoryWarning(callback: (MemoryStatus) -> Unit) {
        onMemoryWarning = callback
    }
    
    /**
     * 设置内存危险回调
     */
    fun setOnMemoryCritical(callback: (MemoryStatus) -> Unit) {
        onMemoryCritical = callback
    }
    
    /**
     * 获取当前内存状态
     */
    fun getCurrentMemoryStatus(): MemoryStatus {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val usageRatio = usedMemory.toDouble() / maxMemory
        
        return MemoryStatus(
            maxMemoryBytes = maxMemory,
            totalMemoryBytes = totalMemory,
            usedMemoryBytes = usedMemory,
            freeMemoryBytes = freeMemory,
            usageRatio = usageRatio,
            isWarning = usageRatio >= warningThreshold,
            isCritical = usageRatio >= criticalThreshold,
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * 获取内存历史数据
     */
    fun getMemoryHistory(): List<MemorySnapshot> {
        return memoryHistory.toList()
    }
    
    /**
     * 手动触发GC建议
     */
    fun suggestGC(): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastGc = lastGcTime.get()
        
        if (currentTime - lastGc < GC_COOLDOWN_MS) {
            Log.d(TAG, "GC冷却中，跳过建议 - 距离上次: ${currentTime - lastGc}ms")
            return false
        }
        
        Log.i(TAG, "建议执行GC")
        System.gc()
        lastGcTime.set(currentTime)
        
        return true
    }
    
    /**
     * 获取内存信息
     */
    fun getMemoryInfo(): MemoryInfo {
        val status = getCurrentMemoryStatus()
        return MemoryInfo(
            usedMemoryMB = (status.usedMemoryBytes / (1024 * 1024)).toInt(),
            totalMemoryMB = (status.maxMemoryBytes / (1024 * 1024)).toInt(),
            memoryUsagePercent = (status.usageRatio * 100).toFloat()
        )
    }
    
    /**
     * 获取监控统计信息
     */
    fun getMonitoringStats(): MonitoringStats {
        val history = memoryHistory.toList()
        
        if (history.isEmpty()) {
            return MonitoringStats(
                isActive = isMonitoring.get(),
                samplesCount = 0,
                averageUsageRatio = 0.0,
                peakUsageRatio = 0.0,
                warningCount = 0,
                criticalCount = 0
            )
        }
        
        val averageUsage = history.map { it.usageRatio }.average()
        val peakUsage = history.maxByOrNull { it.usageRatio }?.usageRatio ?: 0.0
        val warningCount = history.count { it.isWarning }
        val criticalCount = history.count { it.isCritical }
        
        return MonitoringStats(
            isActive = isMonitoring.get(),
            samplesCount = history.size,
            averageUsageRatio = averageUsage,
            peakUsageRatio = peakUsage,
            warningCount = warningCount,
            criticalCount = criticalCount
        )
    }
    
    /**
     * 执行监控循环
     */
    private suspend fun performMonitoring() {
        while (coroutineContext.isActive) {
            try {
                val memoryStatus = getCurrentMemoryStatus()
                
                // 添加到历史记录
                addToHistory(memoryStatus)
                
                // 检查阈值并触发回调
                when {
                    memoryStatus.isCritical -> {
                        Log.w(TAG, "内存使用达到危险水平: ${(memoryStatus.usageRatio * 100).toInt()}%")
                        onMemoryCritical?.invoke(memoryStatus)
                        
                        // 自动建议GC
                        suggestGC()
                    }
                    memoryStatus.isWarning -> {
                        Log.i(TAG, "内存使用达到警告水平: ${(memoryStatus.usageRatio * 100).toInt()}%")
                        onMemoryWarning?.invoke(memoryStatus)
                    }
                }
                
                delay(MONITORING_INTERVAL_MS)
                
            } catch (e: Exception) {
                Log.e(TAG, "内存监控异常", e)
                delay(MONITORING_INTERVAL_MS)
            }
        }
    }
    
    /**
     * 添加内存快照到历史记录
     */
    private fun addToHistory(status: MemoryStatus) {
        val snapshot = MemorySnapshot(
            usageRatio = status.usageRatio,
            usedMemoryMB = status.usedMemoryBytes / (1024 * 1024),
            isWarning = status.isWarning,
            isCritical = status.isCritical,
            timestamp = status.timestamp
        )
        
        memoryHistory.offer(snapshot)
        
        // 保持历史记录大小
        while (memoryHistory.size > HISTORY_SIZE) {
            memoryHistory.poll()
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stopMonitoring()
        monitoringScope.cancel()
    }
}

/**
 * 内存状态信息
 */
data class MemoryStatus(
    val maxMemoryBytes: Long,       // 最大可用内存
    val totalMemoryBytes: Long,     // 当前分配内存
    val usedMemoryBytes: Long,      // 已使用内存
    val freeMemoryBytes: Long,      // 空闲内存
    val usageRatio: Double,         // 使用率
    val isWarning: Boolean,         // 是否达到警告阈值
    val isCritical: Boolean,        // 是否达到危险阈值
    val timestamp: Long             // 时间戳
)

/**
 * 内存快照（用于历史记录）
 */
data class MemorySnapshot(
    val usageRatio: Double,         // 使用率
    val usedMemoryMB: Long,         // 已使用内存(MB)
    val isWarning: Boolean,         // 是否警告
    val isCritical: Boolean,        // 是否危险
    val timestamp: Long             // 时间戳
)

/**
 * 监控统计信息
 */
data class MonitoringStats(
    val isActive: Boolean,          // 是否正在监控
    val samplesCount: Int,          // 样本数量
    val averageUsageRatio: Double,  // 平均使用率
    val peakUsageRatio: Double,     // 峰值使用率
    val warningCount: Int,          // 警告次数
    val criticalCount: Int          // 危险次数
)

/**
 * 内存信息
 */
data class MemoryInfo(
    val usedMemoryMB: Int,          // 已使用内存(MB)
    val totalMemoryMB: Int,         // 总内存(MB)
    val memoryUsagePercent: Float   // 内存使用百分比
)