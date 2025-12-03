package com.continuousauth.core

import android.content.Context
import android.util.Log
import com.continuousauth.buffer.InMemoryBuffer
import com.continuousauth.network.TransportState
import com.continuousauth.network.UploadManager
import com.continuousauth.privacy.PrivacyManager
import com.continuousauth.processing.SensorDataProcessor
import com.continuousauth.sensor.SensorCollector
import com.continuousauth.time.EnhancedTimeSync
import com.continuousauth.utils.UserIdManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    @ApplicationContext private val context: Context,
    private val sensorCollector: SensorCollector,
    private val timeSync: EnhancedTimeSync,
    private val privacyManager: PrivacyManager,
    private val sensorDataProcessor: SensorDataProcessor,
    private val uploadManager: UploadManager,
    private val inMemoryBuffer: InMemoryBuffer,
    private val userIdManager: UserIdManager
) {
    companion object {
        private const val TAG = "SmartTransmissionManager"
    }

    // 管理器状态
    private var isRunning = AtomicBoolean(false)
    private var isPaused = AtomicBoolean(false)
    // 协程管理
    private val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(2) + SupervisorJob())
    private var collectionJob: Job? = null
    private var monitoringJob: Job? = null
    
    // 性能统计
    private val processedPackets = AtomicLong(0)
    private val failedPackets = AtomicLong(0)
    private val lastUploadTimestamp = AtomicLong(0)

    // 当前会话信息
    private var currentSessionId: String = ""

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
            processedPackets.set(0)
            failedPackets.set(0)
            lastUploadTimestamp.set(0)
            isPaused.set(false)

            if (privacyManager.consentState.value != com.continuousauth.privacy.ConsentState.GRANTED) {
                throw IllegalStateException("用户未同意隐私协议，阻止采集")
            }

            val endpoint = resolveServerEndpoint()
            val userId = userIdManager.getUserId()
            currentSessionId = userIdManager.getCurrentSessionId() ?: userIdManager.startNewSession()

            timeSync.startPeriodicSync()

            if (!uploadManager.start(endpoint)) {
                throw IllegalStateException("连接服务器失败: $endpoint")
            }

            startSensorPipeline(userId, currentSessionId)
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
            sensorDataProcessor.stopProcessing()
            sensorCollector.stopCollection()
            timeSync.stopPeriodicSync()
            uploadManager.stop()
            
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

    private fun stopCoreCoroutines() {
        collectionJob?.cancel()
        monitoringJob?.cancel()
        
        // 等待协程结束
        runBlocking {
            collectionJob?.join()
            monitoringJob?.join()
        }
    }

    /**
     * 启动传感器采集与加密流水线，将加密 DataPacket 推入内存上传队列。
     */
    private suspend fun startSensorPipeline(userId: String, sessionId: String) {
        Log.d(TAG, "Starting sensor pipeline for session=$sessionId")
        try {
            sensorCollector.startCollection()
            val started = sensorDataProcessor.startProcessing(
                sensorCollector.getSensorDataFlow(),
                userId = userId,
                sessionId = sessionId
            )
            if (!started) {
                throw IllegalStateException("传感器处理器启动失败")
            }

            collectionJob = scope.launch {
                sensorDataProcessor.getEncryptedPacketFlow()
                    .catch { e ->
                        if (e is CancellationException) {
                            Log.i(TAG, "Sensor pipeline cancelled")
                        } else {
                            failedPackets.incrementAndGet()
                            Log.e(TAG, "Sensor pipeline error", e)
                        }
                    }
                    .collect { packet ->
                        if (isPaused.get()) return@collect
                        processedPackets.incrementAndGet()
                        val enqueued = inMemoryBuffer.enqueue(packet)
                        if (!enqueued) {
                            failedPackets.incrementAndGet()
                        } else {
                            lastUploadTimestamp.set(System.currentTimeMillis())
                        }
                    }
            }
        } catch (e: Exception) {
            failedPackets.incrementAndGet()
            throw e
        }
    }

    private fun resolveServerEndpoint(): String {
        val prefs = context.getSharedPreferences("server_config", Context.MODE_PRIVATE)
        val host = prefs.getString("server_ip", "10.0.2.2").orEmpty().ifBlank { "10.0.2.2" }
        val port = prefs.getInt("server_port", 50051).takeIf { it > 0 } ?: 50051
        val scheme = prefs.getString("server_scheme", "https")
            ?.lowercase()
            ?.takeIf { it == "http" || it == "https" } ?: "https"
        return "$scheme://$host:$port"
    }

    /**
     * 启动监控
     */
    private fun startMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            while (isRunning.get()) {
                delay(10000) // 每10秒监控一次
                
                val stats = TransmissionStats(
                    processedPackets = processedPackets.get(),
                    uploadedPackets = uploadManager.getUploadStatus().uploadedPackets,
                    failedPackets = failedPackets.get(),
                    memoryUsage = 0.5f, // Placeholder - SystemMonitor methods are private
                    cpuUsage = 0.1f, // Placeholder - SystemMonitor methods are private
                    batteryLevel = 80, // Placeholder - SystemMonitor methods are private
                    isRunning = isRunning.get(),
                    isPaused = isPaused.get()
                )
                
                Log.d(TAG, "Transmission stats: $stats")
            }
        }
    }

    /**
     * 处理采集错误
     */
    /**
     * 更新传输策略
     */
    fun updatePolicy(
        batchInterval: Long? = null,
        compressionEnabled: Boolean? = null // ignored in lite mode, kept for call-site compatibility
    ) {
        batchInterval?.let {
            Log.i(TAG, "Batch interval update ignored in gRPC streaming mode (fixed 1s batching handled by processor)")
        }
    }

    /**
     * 获取传输统计信息
     */
    fun getStats(): TransmissionStats {
        val uploadStatus = uploadManager.getUploadStatus()
        return TransmissionStats(
            processedPackets = processedPackets.get(),
            uploadedPackets = uploadStatus.uploadedPackets,
            failedPackets = failedPackets.get(),
            memoryUsage = 0.5f, // Placeholder - SystemMonitor methods are private
            cpuUsage = 0.1f, // Placeholder - SystemMonitor methods are private
            batteryLevel = 80, // Placeholder - SystemMonitor methods are private
            isRunning = isRunning.get(),
            isPaused = isPaused.get()
        )
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
        val uploadStatus = uploadManager.getUploadStatus()
        return LiteUploadSnapshot(
            isRunning = isRunning.get(),
            processedPackets = processedPackets.get(),
            uploadedPackets = uploadStatus.uploadedPackets,
            failedPackets = failedPackets.get(),
            lastUploadTimestamp = lastUploadTimestamp.get()
        )
    }

    /**
     * 获取最近一次上传时的传输通道信息（用于UI展示安全性）。
     */
    fun getTransportState(): TransportState = uploadManager.getTransportState()

    /**
     * 公开当前运行状态，方便上层组件无需重复读取原子变量。
     */
    fun isRunningNow(): Boolean = isRunning.get()
}
