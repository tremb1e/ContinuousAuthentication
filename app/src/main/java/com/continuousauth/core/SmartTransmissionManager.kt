package com.continuousauth.core

import android.util.Log
import com.continuousauth.model.SensorSample
import com.continuousauth.network.LiteServerUploader
import com.continuousauth.privacy.PrivacyManager
import com.continuousauth.processing.LitePacketBuilder
import com.continuousauth.sensor.SensorCollector
import com.continuousauth.time.EnhancedTimeSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 智能传输管理器 - 整个数据流的核心协调器
 * 负责协调从传感器采集到服务器传输的完整数据流
 */
@Singleton
class SmartTransmissionManager @Inject constructor(
    private val sensorCollector: SensorCollector,
    private val timeSync: EnhancedTimeSync,
    private val privacyManager: PrivacyManager,
    private val litePacketBuilder: LitePacketBuilder,
    private val liteServerUploader: LiteServerUploader
) {
    companion object {
        private const val TAG = "SmartTransmissionManager"
        private const val DEFAULT_BATCH_INTERVAL_MS = 1000L // 默认1秒窗口
        private const val SESSION_RENEWAL_INTERVAL_MS = 3600000L // 1小时
        private const val MEMORY_THRESHOLD = 0.8f
        private const val LOW_BATTERY_LEVEL = 20
    }

    // 管理器状态
    private var isRunning = AtomicBoolean(false)
    private var isPaused = AtomicBoolean(false)
    private val packetSequenceNumber = AtomicLong(0)
    
    // 配置参数（可动态调整）
    private var batchIntervalMs = DEFAULT_BATCH_INTERVAL_MS
    
    // 协程管理
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectionJob: Job? = null
    private var monitoringJob: Job? = null
    private var sessionRenewalJob: Job? = null
    
    // 性能统计
    private val processedPackets = AtomicLong(0)
    private val uploadedPackets = AtomicLong(0)
    private val failedPackets = AtomicLong(0)
    private val lastUploadTimestamp = AtomicLong(0)

    // 当前会话信息
    private var currentSessionId: String = ""
    private var lastSessionRenewalTime = 0L

    /**
     * 轻量链路上传状态快照
     */
    data class LiteUploadSnapshot(
        val isRunning: Boolean,
        val processedPackets: Long,
        val uploadedPackets: Long,
        val failedPackets: Long,
        val lastUploadTimestamp: Long
    )

    /**
     * 启动智能传输管理器
     */
    suspend fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Already running")
            return
        }
        
        Log.i(TAG, "Starting SmartTransmissionManager")
        
        try {
            batchIntervalMs = DEFAULT_BATCH_INTERVAL_MS
            // 1. 初始化各个组件
            initializeComponents()

            // 重置统计数据
            processedPackets.set(0)
            uploadedPackets.set(0)
            failedPackets.set(0)
            lastUploadTimestamp.set(0)

            // 2. 启动时间同步
            timeSync.startPeriodicSync()
            
            // 3. 生成新会话
            renewSession()
            
            // 4. 启动核心协程
            startCoreCoroutines()
            
            // 5. 启动监控
            startMonitoring()
            
            Log.i(TAG, "SmartTransmissionManager started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SmartTransmissionManager", e)
            isRunning.set(false)
            throw e
        }
    }

    /**
     * 停止智能传输管理器
     */
    suspend fun stop() {
        if (!isRunning.getAndSet(false)) {
            Log.w(TAG, "Not running")
            return
        }
        
        Log.i(TAG, "Stopping SmartTransmissionManager")
        
        try {
            // 1. 停止所有协程
            stopCoreCoroutines()

            // 2. 停止组件
            sensorCollector.stopCollection()
            timeSync.stopPeriodicSync()
            
            // 3. 上报最终指标
            uploadFinalMetrics()
            
            Log.i(TAG, "SmartTransmissionManager stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
    }

    /**
     * 暂停数据采集（保持连接）
     */
    suspend fun pause() {
        if (!isRunning.get() || isPaused.getAndSet(true)) {
            return
        }
        
        Log.i(TAG, "Pausing data collection")
        // SensorCollector doesn't have pause method - stop and restart when needed
        sensorCollector.stopCollection()
    }

    /**
     * 恢复数据采集
     */
    suspend fun resume() {
        if (!isRunning.get() || !isPaused.getAndSet(false)) {
            return
        }
        
        Log.i(TAG, "Resuming data collection")
        // SensorCollector doesn't have resume method - start collection again
        sensorCollector.startCollection()
    }

    /**
     * 初始化各个组件
     */
    private suspend fun initializeComponents() {
        // 当前 lite 链路无需额外初始化
    }

    /**
     * 启动核心协程
     */
    private fun startCoreCoroutines() {
        // 1. 数据采集协程
        collectionJob = scope.launch {
            startDataCollection()
        }

        // 2. 会话续期协程
        sessionRenewalJob = scope.launch {
            startSessionRenewal()
        }
    }

    /**
     * 停止核心协程
     */
    private fun stopCoreCoroutines() {
        collectionJob?.cancel()
        sessionRenewalJob?.cancel()
        monitoringJob?.cancel()
        
        // 等待协程结束
        runBlocking {
            collectionJob?.join()
            sessionRenewalJob?.join()
            monitoringJob?.join()
        }
    }

    /**
     * 数据采集协程
     */
    private suspend fun startDataCollection() {
        Log.d(TAG, "Starting data collection coroutine")
        
        try {
            sensorCollector.startCollection()
            
            // Process sensor data from the flow
            sensorCollector.getSensorDataFlow()
                .buffer()
                .catch { e ->
                    if (e is CancellationException) {
                        Log.i(TAG, "Sensor collection cancelled")
                    } else {
                        Log.e(TAG, "Error in sensor collection", e)
                        handleCollectionError(e)
                    }
                }
                .chunked(batchIntervalMs)
                .collect { samples ->
                    if (!isPaused.get() && samples.isNotEmpty()) {
                        processSensorBatch(samples)
                    }
                }
        } catch (e: Exception) {
            if (e is CancellationException) {
                Log.i(TAG, "Sensor collection cancelled")
            } else {
                Log.e(TAG, "Failed to start sensor collection", e)
                handleCollectionError(e)
            }
        }
    }

    /**
     * 处理 1 秒窗口的传感器样本，构建 server-lite 兼容的加密包并上传。
     */
    private suspend fun processSensorBatch(samples: List<SensorSample>) {
        try {
            if (privacyManager.consentState.value != com.continuousauth.privacy.ConsentState.GRANTED) {
                Log.w(TAG, "No user consent, skipping batch")
                return
            }

            val packetSeqNo = packetSequenceNumber.incrementAndGet()
            val encryptedPacket = litePacketBuilder.buildEncryptedPacket(
                samples = samples,
                sessionId = currentSessionId,
                packetSeqNo = packetSeqNo
            )

            if (encryptedPacket == null) {
                failedPackets.incrementAndGet()
                Log.e(TAG, "Failed to build encrypted packet for seq $packetSeqNo")
                return
            }

            val success = liteServerUploader.upload(encryptedPacket)
            if (success) {
                uploadedPackets.incrementAndGet()
                lastUploadTimestamp.set(System.currentTimeMillis())
            } else {
                failedPackets.incrementAndGet()
            }

            processedPackets.incrementAndGet()
        } catch (e: Exception) {
            failedPackets.incrementAndGet()
            Log.e(TAG, "Error processing sensor batch", e)
        }
    }

    /**
     * 会话续期协程
     */
    private suspend fun startSessionRenewal() {
        while (isRunning.get()) {
            delay(SESSION_RENEWAL_INTERVAL_MS)
            renewSession()
        }
    }


    /**
     * 续期会话
     */
    private suspend fun renewSession() {
        val now = System.currentTimeMillis()
        if (now - lastSessionRenewalTime < SESSION_RENEWAL_INTERVAL_MS / 2) {
            // 避免频繁续期
            return
        }
        
        Log.d(TAG, "Renewing session")
        
        // 1. 生成新会话ID
        currentSessionId = generateSessionId()
        packetSequenceNumber.set(0)

        // 2. 更新时间戳
        lastSessionRenewalTime = now
        
        Log.i(TAG, "Session renewed: $currentSessionId")
    }

    /**
     * 生成会话ID
     */
    private fun generateSessionId(): String {
        return "session_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    }

    /**
     * 启动监控
     */
    private fun startMonitoring() {
        monitoringJob = scope.launch {
            while (isRunning.get()) {
                delay(10000) // 每10秒监控一次
                
                val stats = TransmissionStats(
                    processedPackets = processedPackets.get(),
                    uploadedPackets = uploadedPackets.get(),
                    failedPackets = failedPackets.get(),
                    memoryUsage = 0.5f, // Placeholder - SystemMonitor methods are private
                    cpuUsage = 0.1f, // Placeholder - SystemMonitor methods are private
                    batteryLevel = 80, // Placeholder - SystemMonitor methods are private
                    isRunning = isRunning.get(),
                    isPaused = isPaused.get()
                )
                
                Log.d(TAG, "Transmission stats: $stats")
                
                // 动态优化
                optimizeBasedOnPerformance(stats)
            }
        }
    }

    /**
     * 基于性能动态优化
     */
    private fun optimizeBasedOnPerformance(stats: TransmissionStats) {
        // 内存压力优化
        if (stats.memoryUsage > MEMORY_THRESHOLD || stats.batteryLevel < LOW_BATTERY_LEVEL) {
            Log.w(TAG, "保持1秒批次，不因资源/电量调整 (memory=${stats.memoryUsage}, battery=${stats.batteryLevel}%)")
        }

        val failureRate = if (stats.processedPackets > 0) {
            stats.failedPackets.toFloat() / stats.processedPackets
        } else 0f

        if (failureRate > 0.1f) {
            Log.w(TAG, "High failure rate: ${failureRate * 100}%")
        }
    }

    /**
     * 上传最终指标
     */
    private suspend fun uploadFinalMetrics() {
        val metrics = mapOf(
            "total_processed" to processedPackets.get(),
            "total_uploaded" to uploadedPackets.get(),
            "total_failed" to failedPackets.get(),
            "session_id" to currentSessionId
        )
        
        // MetricsUploader.uploadMetrics is private - use placeholder
        Log.d(TAG, "Would upload final metrics: $metrics")
    }

    /**
     * 处理采集错误
     */
    private suspend fun handleCollectionError(error: Throwable) {
        if (error is CancellationException) {
            Log.i(TAG, "Collection cancelled")
            return
        }
        Log.e(TAG, "Collection error", error)
        
        // 根据错误类型决定是否重试
        when (error) {
            is SecurityException -> {
                // 权限问题，暂停采集
                pause()
            }
            is OutOfMemoryError -> {
                // 内存不足，短暂等待再重启采集
                delay(2000)
            }
            else -> {
                // 其他错误，延迟后重试
                delay(5000)
                if (isRunning.get()) {
                    collectionJob = scope.launch {
                        startDataCollection()
                    }
                }
            }
        }
    }

    /**
     * 更新传输策略
     */
    fun updatePolicy(
        batchInterval: Long? = null,
        compressionEnabled: Boolean? = null // ignored in lite mode, kept for call-site compatibility
    ) {
        batchInterval?.let { 
            if (it != DEFAULT_BATCH_INTERVAL_MS) {
                Log.w(TAG, "Batch interval override ($it ms) ignored, 固定1秒发送满足100Hz窗口聚合")
            }
            this.batchIntervalMs = DEFAULT_BATCH_INTERVAL_MS
            Log.i(TAG, "Updated batch interval: ${DEFAULT_BATCH_INTERVAL_MS} ms")
        }
    }

    /**
     * 获取传输统计信息
     */
    fun getStats(): TransmissionStats {
        return TransmissionStats(
            processedPackets = processedPackets.get(),
            uploadedPackets = uploadedPackets.get(),
            failedPackets = failedPackets.get(),
            memoryUsage = 0.5f, // Placeholder - SystemMonitor methods are private
            cpuUsage = 0.1f, // Placeholder - SystemMonitor methods are private
            batteryLevel = 80, // Placeholder - SystemMonitor methods are private
            isRunning = isRunning.get(),
            isPaused = isPaused.get()
        )
    }

    /**
     * 将传感器流按窗口聚合。
     */
    private fun <T> Flow<T>.chunked(windowMillis: Long): Flow<List<T>> = channelFlow {
        val buffer = mutableListOf<T>()
        val mutex = Mutex()

        val collector = launch {
            collect { item ->
                mutex.withLock { buffer.add(item) }
            }
        }

        val flusher = launch {
            while (isActive) {
                delay(windowMillis)
                mutex.withLock {
                    if (buffer.isNotEmpty()) {
                        send(buffer.toList())
                        buffer.clear()
                    }
                }
            }
        }

        awaitClose {
            collector.cancel()
            flusher.cancel()
            val remaining = if (mutex.tryLock()) {
                val copy = buffer.toList()
                buffer.clear()
                mutex.unlock()
                copy
            } else {
                emptyList()
            }
            if (remaining.isNotEmpty()) trySend(remaining)
        }
    }

    /**
     * 传输统计数据类
     */
    data class TransmissionStats(
        val processedPackets: Long,
        val uploadedPackets: Long,
        val failedPackets: Long,
        val memoryUsage: Float,
        val cpuUsage: Float,
        val batteryLevel: Int,
        val isRunning: Boolean,
        val isPaused: Boolean
    )

    /**
     * 提供当前上传状态快照，供UI/监控使用。
     */
    fun getUploadSnapshot(): LiteUploadSnapshot {
        return LiteUploadSnapshot(
            isRunning = isRunning.get(),
            processedPackets = processedPackets.get(),
            uploadedPackets = uploadedPackets.get(),
            failedPackets = failedPackets.get(),
            lastUploadTimestamp = lastUploadTimestamp.get()
        )
    }

    /**
     * 公开当前运行状态，方便上层组件无需重复读取原子变量。
     */
    fun isRunningNow(): Boolean = isRunning.get()
}
