package com.continuousauth.detection

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * 轻量级端侧异常检测器实现
 * 负责检测设备解锁、加速度计突变和敏感应用进入等异常事件
 */
@Singleton
class AnomalyDetectorImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : AnomalyDetector {
    
    companion object {
        private const val TAG = "AnomalyDetector"
    }
    
    // 检测状态
    private var isDetecting = false
    private var detectionJob: Job? = null
    private var detectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // 监听器
    private var anomalyListener: OnAnomalyListener? = null
    
    // 当前检测策略
    private var currentPolicy = DetectionPolicy()
    
    // 设备解锁监听
    private var unlockReceiver: BroadcastReceiver? = null
    private var lastUnlockTime = 0L
    private var timeProvider: () -> Long = { System.currentTimeMillis() }
    
    // 加速度计数据处理 - 使用滑动窗口实现
    private val accelerometerData = ConcurrentLinkedQueue<AccelerometerSample>()
    private var lastAccelerometerTrigger = 0L
    
    // 短时窗口统计 (Epic 4.1.1 要求)
    private val shortTermWindow = mutableListOf<Float>()
    private val longTermWindow = mutableListOf<Float>()
    private var adaptiveThreshold = 2.5f // 自适应阈值，初始值为2.5个标准差
    
    // 百分位法参数
    private val percentileThreshold = 95 // 第95百分位作为异常阈值
    
    // 冷却期与逐步退火参数 (Epic 4.1.1 要求)
    private var cooldownEndTimeNs = 0L
    private var cooldownCount = 0
    private val maxCooldownCount = 5 // 最大冷却次数
    private val cooldownBackoffFactor = 1.5 // 退火因子
    
    // 前台应用监控
    private var lastForegroundApp: String? = null
    private var usageStatsManager: UsageStatsManager? = null
    
    init {
        // 初始化系统服务
        usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }
    
    override suspend fun startDetection() {
        if (isDetecting) return
        
        Log.i(TAG, "启动异常检测")
        isDetecting = true
        
        // 注册设备解锁监听
        if (currentPolicy.deviceUnlockEnabled) {
            registerUnlockReceiver()
        }
        
        // 启动前台应用监控
        startForegroundAppMonitoring()
        
        Log.i(TAG, "异常检测已启动")
    }
    
    override suspend fun stopDetection() {
        if (!isDetecting) return
        
        Log.i(TAG, "停止异常检测")
        isDetecting = false
        
        // 停止监控任务
        detectionJob?.cancel()
        detectionJob = null
        
        // 取消注册设备解锁监听
        unregisterUnlockReceiver()
        
        // 清理数据
        accelerometerData.clear()
        
        Log.i(TAG, "异常检测已停止")
    }

    /**
     * 仅用于测试：覆盖内部协程调度器，便于虚拟时间控制。
     */
    @VisibleForTesting
    internal fun overrideDispatcherForTests(dispatcher: CoroutineDispatcher) {
        detectionJob?.cancel()
        detectionJob = null
        detectorScope.cancel()
        detectorScope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @VisibleForTesting
    internal fun overrideTimeProviderForTests(provider: () -> Long) {
        timeProvider = provider
    }
    
    override fun setOnAnomalyListener(listener: OnAnomalyListener?) {
        this.anomalyListener = listener
    }
    
    override suspend fun updatePolicy(config: DetectionPolicy) {
        Log.d(TAG, "更新检测策略")
        val oldPolicy = currentPolicy
        currentPolicy = config
        
        // 如果检测正在运行，需要重新应用新策略
        if (isDetecting) {
            // 重新注册解锁监听（如果设置发生变化）
            if (oldPolicy.deviceUnlockEnabled != config.deviceUnlockEnabled) {
                if (config.deviceUnlockEnabled) {
                    registerUnlockReceiver()
                } else {
                    unregisterUnlockReceiver()
                }
            }
        }
    }
    
    override fun processSensorData(x: Float, y: Float, z: Float, timestamp: Long) {
        if (!isDetecting || !currentPolicy.enabled) return
        
        // 计算加速度幅度
        val magnitude = sqrt(x * x + y * y + z * z)
        val sample = AccelerometerSample(magnitude, timestamp)
        
        // 添加到数据队列
        accelerometerData.offer(sample)
        
        // 保持窗口大小
        while (accelerometerData.size > currentPolicy.accelerometerWindowSize) {
            accelerometerData.poll()
        }
        
        // 检测突变
        checkAccelerometerSpike(magnitude, timestamp)
    }
    
    override fun isDetecting(): Boolean = isDetecting
    
    /**
     * 注册设备解锁监听器
     */
    private fun registerUnlockReceiver() {
        if (unlockReceiver != null) return
        
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    handleDeviceUnlock()
                }
            }
        }
        
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        context.registerReceiver(unlockReceiver, filter)
        Log.d(TAG, "设备解锁监听器已注册")
    }
    
    /**
     * 取消注册设备解锁监听器
     */
    private fun unregisterUnlockReceiver() {
        unlockReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "取消注册设备解锁监听器失败", e)
            }
            unlockReceiver = null
            Log.d(TAG, "设备解锁监听器已取消注册")
        }
    }
    
    /**
     * 处理设备解锁事件
     */
    private fun handleDeviceUnlock() {
        val currentTime = timeProvider()
        
        // 检查冷却期
        if (currentTime - lastUnlockTime < currentPolicy.deviceUnlockCooldownMs) {
            return
        }
        
        lastUnlockTime = currentTime
        
        if (currentPolicy.debugMode) {
            Log.d(TAG, "检测到设备解锁事件")
        }
        
        // 触发异常回调
        anomalyListener?.onAnomalyDetected(AnomalyTrigger.DeviceUnlocked)
    }
    
    /**
     * 启动前台应用监控
     */
    private fun startForegroundAppMonitoring() {
        if (currentPolicy.sensitiveApps.isEmpty()) {
            return
        }
        
        detectionJob = detectorScope.launch {
            while (isActive && isDetecting) {
                try {
                    checkForegroundApp()
                } catch (e: Exception) {
                    Log.w(TAG, "前台应用检查异常", e)
                }
                delay(currentPolicy.appCheckIntervalMs)
            }
        }
        
        Log.d(TAG, "前台应用监控已启动")
    }
    
    /**
     * 检查当前前台应用
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    private fun checkForegroundApp() {
        val usageStats = usageStatsManager ?: return
        
        try {
            val currentTime = timeProvider()
            // 扩大查询时间窗口到5分钟，确保能获取到数据
            val startTime = currentTime - 5 * 60 * 1000L
            
            // 根据Android版本使用不同的方法
            val currentForegroundApp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android Q及以上版本使用queryEvents方法
                getForegroundAppUsingEvents(usageStats, startTime, currentTime)
            } else {
                // 旧版本使用queryUsageStats方法
                getForegroundAppUsingStats(usageStats, startTime, currentTime)
            }
            
            if (currentForegroundApp != null && 
                currentForegroundApp != lastForegroundApp &&
                currentPolicy.sensitiveApps.contains(currentForegroundApp)) {
                
                handleSensitiveAppEntered(currentForegroundApp)
            }
            
            lastForegroundApp = currentForegroundApp
            
        } catch (e: Exception) {
            Log.w(TAG, "获取前台应用失败", e)
        }
    }
    
    /**
     * 使用UsageEvents获取前台应用（Android Q及以上）
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getForegroundAppUsingEvents(
        usageStats: UsageStatsManager,
        startTime: Long,
        endTime: Long
    ): String? {
        val events = usageStats.queryEvents(startTime, endTime)
        val event = android.app.usage.UsageEvents.Event()
        
        var lastForegroundPackage: String? = null
        var lastForegroundTime = 0L
        
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            
            // 查找ACTIVITY_RESUMED事件（应用进入前台）
            if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                
                if (event.timeStamp > lastForegroundTime) {
                    lastForegroundTime = event.timeStamp
                    lastForegroundPackage = event.packageName
                }
            }
        }
        
        return lastForegroundPackage
    }
    
    /**
     * 使用UsageStats获取前台应用（旧版本Android）
     */
    private fun getForegroundAppUsingStats(
        usageStats: UsageStatsManager,
        startTime: Long,
        endTime: Long
    ): String? {
        val stats = usageStats.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            startTime,
            endTime
        )
        
        // 找到最近使用的应用
        return stats?.filter { 
            it.totalTimeInForeground > 0 && 
            it.lastTimeUsed >= startTime 
        }?.maxByOrNull { it.lastTimeUsed }?.packageName
    }
    
    /**
     * 处理敏感应用进入事件
     */
    private fun handleSensitiveAppEntered(packageName: String) {
        if (currentPolicy.debugMode) {
            Log.d(TAG, "检测到敏感应用进入: $packageName")
        }
        
        // 触发异常回调
        val trigger = AnomalyTrigger.SensitiveAppEntered(packageName)
        anomalyListener?.onAnomalyDetected(trigger)
    }
    
    /**
     * 检查加速度计突变 - 使用短时窗口均值/方差和百分位法
     * Epic 4.1.1: 实现自适应阈值和逐步退火机制
     */
    private fun checkAccelerometerSpike(magnitude: Float, timestamp: Long) {
        // 更新滑动窗口
        updateSlidingWindows(magnitude)
        
        if (shortTermWindow.size < 10 || longTermWindow.size < 50) {
            return // 数据不足，跳过检测
        }
        
        // 检查冷却期（使用逐步退火）
        if (isInCooldownPeriod(timestamp)) {
            return
        }
        
        // 使用两种方法进行异常检测
        val isAnomalyByStats = checkByStatisticalMethod(magnitude)
        val isAnomalyByPercentile = checkByPercentileMethod(magnitude)
        
        // 任一方法检测到异常则触发
        if (isAnomalyByStats || isAnomalyByPercentile) {
            handleAnomalyDetection(magnitude, timestamp)
        } else {
            // 未检测到异常，逐步降低冷却计数
            if (cooldownCount > 0 && System.nanoTime() > cooldownEndTimeNs) {
                cooldownCount--
            }
        }
    }
    
    /**
     * 更新滑动窗口
     */
    private fun updateSlidingWindows(magnitude: Float) {
        // 更新短时窗口（最近20个样本）
        shortTermWindow.add(magnitude)
        if (shortTermWindow.size > 20) {
            shortTermWindow.removeAt(0)
        }
        
        // 更新长时窗口（最近100个样本）
        longTermWindow.add(magnitude)
        if (longTermWindow.size > 100) {
            longTermWindow.removeAt(0)
        }
    }
    
    /**
     * 基于统计方法检测异常（均值/方差法）
     */
    private fun checkByStatisticalMethod(magnitude: Float): Boolean {
        // 使用长时窗口计算基线统计
        val baselineMean = longTermWindow.average().toFloat()
        val baselineVariance = longTermWindow.map { (it - baselineMean) * (it - baselineMean) }.average().toFloat()
        val baselineStdDev = sqrt(baselineVariance)
        
        // 使用短时窗口计算当前统计
        val currentMean = shortTermWindow.average().toFloat()
        val currentVariance = shortTermWindow.map { (it - currentMean) * (it - currentMean) }.average().toFloat()
        val currentStdDev = sqrt(currentVariance)
        
        // 自适应调整阈值
        adaptiveThreshold = when {
            cooldownCount > 3 -> 3.5f // 频繁触发时提高阈值
            cooldownCount > 1 -> 3.0f
            else -> 2.5f // 正常阈值
        }
        
        // 检测短时窗口相对于长时窗口的突变
        val deviation = if (baselineStdDev > 0.1f) {
            (magnitude - baselineMean) / baselineStdDev
        } else {
            0f
        }
        
        return deviation > adaptiveThreshold && currentStdDev > baselineStdDev * 1.5f
    }
    
    /**
     * 基于百分位法检测异常
     */
    private fun checkByPercentileMethod(magnitude: Float): Boolean {
        // 对长时窗口排序计算百分位
        val sortedValues = longTermWindow.sorted()
        val percentileIndex = (sortedValues.size * percentileThreshold / 100).coerceIn(0, sortedValues.size - 1)
        val percentileValue = sortedValues[percentileIndex]
        
        // 计算动态阈值（考虑冷却期状态）
        val dynamicMultiplier = if (cooldownCount > 0) {
            1.2f + (cooldownCount * 0.1f) // 冷却期时提高阈值
        } else {
            1.1f
        }
        
        return magnitude > percentileValue * dynamicMultiplier
    }
    
    /**
     * 检查是否在冷却期（逐步退火机制）
     */
    private fun isInCooldownPeriod(timestamp: Long): Boolean {
        if (cooldownEndTimeNs > timestamp) {
            if (currentPolicy.debugMode) {
                Log.d(TAG, "处于冷却期，剩余时间: ${(cooldownEndTimeNs - timestamp) / 1_000_000}ms")
            }
            return true
        }
        return false
    }
    
    /**
     * 处理异常检测结果
     */
    private fun handleAnomalyDetection(magnitude: Float, timestamp: Long) {
        lastAccelerometerTrigger = timestamp
        
        // 更新冷却期（逐步退火）
        cooldownCount = (cooldownCount + 1).coerceAtMost(maxCooldownCount)
        val cooldownDurationNs = (currentPolicy.accelerometerCooldownMs * 
                               Math.pow(cooldownBackoffFactor.toDouble(), cooldownCount.toDouble())).toLong() * 1_000_000L
        cooldownEndTimeNs = timestamp + cooldownDurationNs
        
        // 计算统计信息用于日志
        val mean = longTermWindow.average().toFloat()
        val stdDev = sqrt(longTermWindow.map { (it - mean) * (it - mean) }.average().toFloat())
        val threshold = mean + adaptiveThreshold * stdDev
        val deviation = if (stdDev > 0) (magnitude - mean) / stdDev else 0f
        
        if (currentPolicy.debugMode) {
            Log.d(TAG, "检测到加速度计突变: magnitude=$magnitude, threshold=$threshold, " +
                      "deviation=$deviation, cooldown=${cooldownDurationNs / 1_000_000_000.0}秒")
        }
        
        // 触发异常回调
        val trigger = AnomalyTrigger.AccelerometerSpike(magnitude, threshold, deviation)
        anomalyListener?.onAnomalyDetected(trigger)
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        detectorScope.launch {
            stopDetection()
        }
        detectorScope.cancel()
    }
}

/**
 * 加速度计数据样本
 */
private data class AccelerometerSample(
    val magnitude: Float,
    val timestamp: Long
)
