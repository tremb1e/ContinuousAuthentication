package com.continuousauth.observability

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 性能样本数据
 * 包含CPU和内存使用情况的快照
 */
data class PerformanceSample(
    val timestamp: Long,                    // 采样时间戳
    val memoryUsageMB: Long,               // 内存使用量（MB）
    val memoryAvailableMB: Long,           // 可用内存（MB）
    val memoryUsagePercent: Double,        // 内存使用百分比
    val cpuUsagePercent: Double,           // CPU使用百分比
    val heapUsageMB: Long,                 // 堆内存使用量（MB）
    val heapMaxMB: Long,                   // 最大堆内存（MB）
    val nativeMemoryMB: Long               // 原生内存使用量（MB）
)

/**
 * 性能统计信息
 * 包含一段时间内的性能数据统计
 */
data class PerformanceStats(
    val duration: Long,                     // 统计时间段（毫秒）
    val sampleCount: Int,                   // 样本数量
    
    // 内存统计
    val memoryUsageAvg: Double,            // 平均内存使用（MB）
    val memoryUsagePeak: Long,             // 峰值内存使用（MB）
    val memoryUsagePercentAvg: Double,     // 平均内存使用百分比
    val memoryUsagePercentPeak: Double,    // 峰值内存使用百分比
    
    // CPU统计
    val cpuUsageAvg: Double,               // 平均CPU使用百分比
    val cpuUsagePeak: Double,              // 峰值CPU使用百分比
    
    // 堆内存统计
    val heapUsageAvg: Double,              // 平均堆内存使用（MB）
    val heapUsagePeak: Long,               // 峰值堆内存使用（MB）
    val heapUtilization: Double,           // 堆内存利用率
    
    // 垃圾回收统计
    val gcCount: Int,                      // GC次数
    val gcTime: Long                       // GC时间（毫秒）
)

/**
 * 环形缓冲区
 * 用于高效存储固定数量的性能样本
 */
class RingBuffer<T>(private val capacity: Int) {
    private val buffer = arrayOfNulls<Any>(capacity)
    private var head = 0
    private var tail = 0
    private var size = 0
    
    /**
     * 添加元素到缓冲区
     */
    @Synchronized
    fun add(item: T) {
        @Suppress("UNCHECKED_CAST")
        buffer[tail] = item as Any
        tail = (tail + 1) % capacity
        
        if (size < capacity) {
            size++
        } else {
            head = (head + 1) % capacity
        }
    }
    
    /**
     * 获取所有元素
     */
    @Synchronized
    fun getAll(): List<T> {
        val result = mutableListOf<T>()
        var current = head
        
        repeat(size) {
            @Suppress("UNCHECKED_CAST")
            result.add(buffer[current] as T)
            current = (current + 1) % capacity
        }
        
        return result
    }
    
    /**
     * 获取最近的N个元素
     */
    @Synchronized
    fun getRecent(count: Int): List<T> {
        val actualCount = minOf(count, size)
        val result = mutableListOf<T>()
        
        var current = (tail - actualCount + capacity) % capacity
        repeat(actualCount) {
            @Suppress("UNCHECKED_CAST")
            result.add(buffer[current] as T)
            current = (current + 1) % capacity
        }
        
        return result
    }
    
    fun size(): Int = size
    
    @Synchronized
    fun clear() {
        head = 0
        tail = 0
        size = 0
    }
}

/**
 * 性能监控器接口
 * 定义了性能监控的基本功能
 */
interface PerformanceMonitor {
    /**
     * 启动性能监控
     * @param intervalMs 采样间隔（毫秒）
     */
    suspend fun startMonitoring(intervalMs: Long = 5000L)
    
    /**
     * 停止性能监控
     */
    suspend fun stopMonitoring()
    
    /**
     * 获取最近的性能样本
     * @param count 样本数量
     */
    fun getRecentSamples(count: Int = 60): List<PerformanceSample>
    
    /**
     * 获取性能统计信息
     * @param durationMs 统计时间段（毫秒）
     */
    fun getPerformanceStats(durationMs: Long = 60000L): PerformanceStats
    
    /**
     * 获取当前性能快照
     */
    suspend fun getCurrentSnapshot(): PerformanceSample
    
    /**
     * 获取性能监控状态流
     */
    fun getMonitoringStateFlow(): StateFlow<Boolean>
    
    /**
     * 清除历史数据
     */
    fun clearHistory()
}

/**
 * 性能监控器实现
 * 使用协程定期在后台采集应用的CPU和内存使用率
 */
@Singleton
class PerformanceMonitorImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PerformanceMonitor {
    
    companion object {
        private const val TAG = "PerformanceMonitor"
        private const val BUFFER_CAPACITY = 720 // 可存储12分钟的数据（每5秒一个样本）
    }
    
    // 环形缓冲区存储性能样本
    private val performanceBuffer = RingBuffer<PerformanceSample>(BUFFER_CAPACITY)
    
    // 监控状态
    private val _monitoringStateFlow = MutableStateFlow(false)
    override fun getMonitoringStateFlow(): StateFlow<Boolean> = _monitoringStateFlow.asStateFlow()
    
    // 协程作用域和任务
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoringJob: Job? = null
    
    // 系统服务
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    // CPU使用率计算相关
    private var lastCpuTime = 0L
    private var lastAppCpuTime = 0L
    
    
    override suspend fun startMonitoring(intervalMs: Long) {
        if (_monitoringStateFlow.value) {
            Log.w(TAG, "性能监控已在运行中")
            return
        }
        
        Log.i(TAG, "启动性能监控，采样间隔: ${intervalMs}ms")
        _monitoringStateFlow.value = true
        
        // 初始化CPU时间基线
        initializeCpuBaseline()
        
        monitoringJob = monitorScope.launch {
            while (isActive && _monitoringStateFlow.value) {
                try {
                    val sample = collectPerformanceSample()
                    performanceBuffer.add(sample)
                    
                    Log.v(TAG, "性能采样: 内存=${sample.memoryUsageMB}MB, CPU=${String.format("%.1f", sample.cpuUsagePercent)}%")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "性能采样失败", e)
                }
                
                delay(intervalMs)
            }
        }
        
        Log.i(TAG, "性能监控已启动")
    }
    
    override suspend fun stopMonitoring() {
        if (!_monitoringStateFlow.value) {
            return
        }
        
        Log.i(TAG, "停止性能监控")
        _monitoringStateFlow.value = false
        
        monitoringJob?.cancel()
        monitoringJob = null
        
        Log.i(TAG, "性能监控已停止")
    }
    
    override fun getRecentSamples(count: Int): List<PerformanceSample> {
        return performanceBuffer.getRecent(count)
    }
    
    override fun getPerformanceStats(durationMs: Long): PerformanceStats {
        val samples = performanceBuffer.getAll()
        if (samples.isEmpty()) {
            return createEmptyStats()
        }
        
        val currentTime = System.currentTimeMillis()
        val startTime = currentTime - durationMs
        
        // 过滤指定时间段内的样本
        val filteredSamples = samples.filter { it.timestamp >= startTime }
        
        if (filteredSamples.isEmpty()) {
            return createEmptyStats()
        }
        
        return calculateStats(filteredSamples, durationMs)
    }
    
    override suspend fun getCurrentSnapshot(): PerformanceSample {
        return collectPerformanceSample()
    }
    
    override fun clearHistory() {
        performanceBuffer.clear()
        Log.i(TAG, "性能监控历史数据已清除")
    }
    
    /**
     * 收集性能样本
     */
    private suspend fun collectPerformanceSample(): PerformanceSample = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        
        // 获取内存信息
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        val heapMax = runtime.maxMemory()
        
        // 获取进程内存信息
        val processMemInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(processMemInfo)
        
        val memoryUsageMB = processMemInfo.totalPss / 1024L  // PSS内存（KB转MB）
        val memoryAvailableMB = memInfo.availMem / 1024L / 1024L
        val totalMemoryMB = memInfo.totalMem / 1024L / 1024L
        val memoryUsagePercent = (totalMemoryMB - memoryAvailableMB).toDouble() / totalMemoryMB * 100.0
        
        val heapUsageMB = heapUsed / 1024L / 1024L
        val heapMaxMB = heapMax / 1024L / 1024L
        val nativeMemoryMB = processMemInfo.nativePss / 1024L
        
        // 获取CPU使用率
        val cpuUsagePercent = calculateCpuUsage()
        
        PerformanceSample(
            timestamp = timestamp,
            memoryUsageMB = memoryUsageMB,
            memoryAvailableMB = memoryAvailableMB,
            memoryUsagePercent = memoryUsagePercent,
            cpuUsagePercent = cpuUsagePercent,
            heapUsageMB = heapUsageMB,
            heapMaxMB = heapMaxMB,
            nativeMemoryMB = nativeMemoryMB
        )
    }
    
    /**
     * 初始化CPU时间基线
     */
    private fun initializeCpuBaseline() {
        try {
            lastCpuTime = getTotalCpuTime()
            lastAppCpuTime = getProcessCpuTime()
        } catch (e: Exception) {
            Log.w(TAG, "初始化CPU基线失败", e)
            lastCpuTime = 0L
            lastAppCpuTime = 0L
        }
    }
    
    /**
     * 计算CPU使用率
     */
    private fun calculateCpuUsage(): Double {
        return try {
            val currentCpuTime = getTotalCpuTime()
            val currentAppCpuTime = getProcessCpuTime()
            
            if (lastCpuTime == 0L || lastAppCpuTime == 0L) {
                initializeCpuBaseline()
                return 0.0
            }
            
            val totalCpuDelta = currentCpuTime - lastCpuTime
            val appCpuDelta = currentAppCpuTime - lastAppCpuTime
            
            val cpuUsage = if (totalCpuDelta > 0) {
                (appCpuDelta.toDouble() / totalCpuDelta * 100.0).coerceIn(0.0, 100.0)
            } else {
                0.0
            }
            
            lastCpuTime = currentCpuTime
            lastAppCpuTime = currentAppCpuTime
            
            cpuUsage
        } catch (e: Exception) {
            Log.w(TAG, "计算CPU使用率失败", e)
            0.0
        }
    }
    
    /**
     * 获取系统总CPU时间
     */
    private fun getTotalCpuTime(): Long {
        return try {
            val file = RandomAccessFile("/proc/stat", "r")
            val line = file.readLine()
            file.close()
            
            // 解析第一行：cpu user nice system idle iowait irq softirq
            val tokens = line.split("\\s+".toRegex())
            var totalTime = 0L
            for (i in 1 until minOf(tokens.size, 8)) {
                totalTime += tokens[i].toLongOrNull() ?: 0L
            }
            totalTime
        } catch (e: Exception) {
            Log.w(TAG, "获取总CPU时间失败", e)
            0L
        }
    }
    
    /**
     * 获取进程CPU时间
     */
    private fun getProcessCpuTime(): Long {
        return try {
            val pid = Process.myPid()
            val file = RandomAccessFile("/proc/$pid/stat", "r")
            val line = file.readLine()
            file.close()
            
            // 解析stat文件，utime和stime分别在第14和15个字段
            val tokens = line.split("\\s+".toRegex())
            if (tokens.size >= 15) {
                val utime = tokens[13].toLongOrNull() ?: 0L
                val stime = tokens[14].toLongOrNull() ?: 0L
                utime + stime
            } else {
                0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取进程CPU时间失败", e)
            0L
        }
    }
    
    /**
     * 计算性能统计
     */
    private fun calculateStats(samples: List<PerformanceSample>, duration: Long): PerformanceStats {
        if (samples.isEmpty()) return createEmptyStats()
        
        // 计算各项统计值
        val memoryUsages = samples.map { it.memoryUsageMB.toDouble() }
        val memoryUsagePercents = samples.map { it.memoryUsagePercent }
        val cpuUsages = samples.map { it.cpuUsagePercent }
        val heapUsages = samples.map { it.heapUsageMB.toDouble() }
        
        return PerformanceStats(
            duration = duration,
            sampleCount = samples.size,
            
            memoryUsageAvg = memoryUsages.average(),
            memoryUsagePeak = samples.maxOfOrNull { it.memoryUsageMB } ?: 0L,
            memoryUsagePercentAvg = memoryUsagePercents.average(),
            memoryUsagePercentPeak = memoryUsagePercents.maxOrNull() ?: 0.0,
            
            cpuUsageAvg = cpuUsages.average(),
            cpuUsagePeak = cpuUsages.maxOrNull() ?: 0.0,
            
            heapUsageAvg = heapUsages.average(),
            heapUsagePeak = samples.maxOfOrNull { it.heapUsageMB } ?: 0L,
            heapUtilization = samples.lastOrNull()?.let { 
                if (it.heapMaxMB > 0) it.heapUsageMB.toDouble() / it.heapMaxMB * 100.0 else 0.0 
            } ?: 0.0,
            
            gcCount = 0, // 预留字段，当前未实现
            gcTime = 0L  // 预留字段，当前未实现
        )
    }
    
    /**
     * 创建空统计信息
     */
    private fun createEmptyStats(): PerformanceStats {
        return PerformanceStats(
            duration = 0L,
            sampleCount = 0,
            memoryUsageAvg = 0.0,
            memoryUsagePeak = 0L,
            memoryUsagePercentAvg = 0.0,
            memoryUsagePercentPeak = 0.0,
            cpuUsageAvg = 0.0,
            cpuUsagePeak = 0.0,
            heapUsageAvg = 0.0,
            heapUsagePeak = 0L,
            heapUtilization = 0.0,
            gcCount = 0,
            gcTime = 0L
        )
    }
    
    /**
     * 获取格式化的性能报告
     */
    fun getFormattedReport(durationMs: Long = 60000L): String {
        val stats = getPerformanceStats(durationMs)
        val recent = getRecentSamples(1).firstOrNull()
        
        val sb = StringBuilder()
        sb.appendLine("=== 性能监控报告 ===")
        sb.appendLine("监控时长: ${durationMs / 1000}秒")
        sb.appendLine("样本数量: ${stats.sampleCount}")
        sb.appendLine("")
        
        sb.appendLine("内存使用:")
        sb.appendLine("  当前: ${recent?.memoryUsageMB ?: 0}MB")
        sb.appendLine("  平均: ${"%.1f".format(stats.memoryUsageAvg)}MB")
        sb.appendLine("  峰值: ${stats.memoryUsagePeak}MB")
        sb.appendLine("  系统使用率: ${"%.1f".format(stats.memoryUsagePercentPeak)}%")
        sb.appendLine("")
        
        sb.appendLine("CPU使用:")
        sb.appendLine("  当前: ${"%.1f".format(recent?.cpuUsagePercent ?: 0.0)}%")
        sb.appendLine("  平均: ${"%.1f".format(stats.cpuUsageAvg)}%")
        sb.appendLine("  峰值: ${"%.1f".format(stats.cpuUsagePeak)}%")
        sb.appendLine("")
        
        sb.appendLine("堆内存:")
        sb.appendLine("  当前: ${recent?.heapUsageMB ?: 0}MB")
        sb.appendLine("  平均: ${"%.1f".format(stats.heapUsageAvg)}MB")
        sb.appendLine("  峰值: ${stats.heapUsagePeak}MB")
        sb.appendLine("  最大: ${recent?.heapMaxMB ?: 0}MB")
        sb.appendLine("  利用率: ${"%.1f".format(stats.heapUtilization)}%")
        
        return sb.toString()
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        monitorScope.launch {
            stopMonitoring()
        }
        monitorScope.cancel()
    }
}