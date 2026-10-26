package com.continuousauth.core

import android.app.KeyguardManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.continuousauth.buffer.InMemoryBuffer
import com.continuousauth.network.TransportState
import com.continuousauth.network.ServerEndpointNormalizer
import com.continuousauth.network.UploadManager
import com.continuousauth.privacy.PrivacyManager
import com.continuousauth.processing.SensorDataProcessor
import com.continuousauth.sensor.SensorCollector
import com.continuousauth.storage.FileQueueManager
import com.continuousauth.time.EnhancedTimeSync
import com.continuousauth.utils.Constant.UPLOAD_POLICY
import com.continuousauth.utils.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalTime
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
    private val fileQueueManager: FileQueueManager,
    private val sessionManager: SessionManager
) {
    companion object {
        private const val TAG = "SmartTransmissionManager"
        private const val SMART_TRANSMISSION_ENABLED_KEY = "smart_transmission_enabled"
        private const val SMART_TRANSMISSION_BATTERY_THRESHOLD = 60
    }

    // 管理器状态
    private var isRunning = AtomicBoolean(false)
    private var isPaused = AtomicBoolean(false)
    private val lifecycleMutex = Mutex()
    @Volatile private var allowBufferedUploadWhilePaused = false

    enum class CollectionStatus { STOPPED, STARTING, RUNNING, PAUSED, STOPPING, ERROR }

    data class CollectionState(
        val status: CollectionStatus = CollectionStatus.STOPPED,
        val errorMessage: String? = null
    )

    private val _collectionState = MutableStateFlow(CollectionState())
    val collectionState = _collectionState.asStateFlow()
    // 协程管理
    private val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(2) + SupervisorJob())
    private var collectionJob: Job? = null
    private var monitoringJob: Job? = null
    
    // 性能统计
    private val processedPackets = AtomicLong(0)
    private val failedPackets = AtomicLong(0)

    // 当前会话信息
    private var currentSessionId: String = ""
    private val smartTransmissionEnabled = AtomicBoolean(false)
    private val smartTransmissionUploadAllowed = AtomicBoolean(true)
    private val _smartUploadWindowOpen = MutableStateFlow(false)
    val smartUploadWindowOpen = _smartUploadWindowOpen.asStateFlow()
    private val batteryManager by lazy { context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager }
    private val keyguardManager by lazy { context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    private val powerManager by lazy { context.getSystemService(Context.POWER_SERVICE) as PowerManager }

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
    suspend fun start() = lifecycleMutex.withLock {
        if (isRunning.get()) {
            Log.w(TAG, "Already running")
            return@withLock
        }
        isRunning.set(true)
        _collectionState.value = CollectionState(CollectionStatus.STARTING)
        
        Log.i(TAG, "Starting SmartTransmissionManager")
        
        try {
            processedPackets.set(0)
            failedPackets.set(0)
            isPaused.set(false)
            allowBufferedUploadWhilePaused = false

            if (privacyManager.consentState.value != com.continuousauth.privacy.ConsentState.GRANTED) {
                throw IllegalStateException("用户未同意隐私协议，阻止采集")
            }

            val endpoint = resolveServerEndpoint()
            currentSessionId = sessionManager.getCurrentSessionId() ?: sessionManager.startNewSession()

            timeSync.startPeriodicSync()

            applySmartTransmissionPreferences()
            updateSmartTransmissionGate()

            if (!uploadManager.start(endpoint)) {
                throw IllegalStateException("连接服务器失败: $endpoint")
            }

            startSensorPipeline(currentSessionId)
            startMonitoring()
            _collectionState.value = CollectionState(CollectionStatus.RUNNING)

            Log.i(TAG, "SmartTransmissionManager started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SmartTransmissionManager", e)
            isRunning.set(false)
            withContext(NonCancellable) {
                stopComponents()
                sensorDataProcessor.discardPendingOutput()
                inMemoryBuffer.clear()
                sessionManager.endSession()
            }
            _collectionState.value = CollectionState(CollectionStatus.ERROR, e.message)
            throw e
        }
    }

    /**
     * 停止智能传输管理器
     */
    suspend fun stop(endSession: Boolean = true) = lifecycleMutex.withLock {
        if (!isRunning.getAndSet(false)) {
            Log.w(TAG, "Not running")
            if (endSession) {
                sensorDataProcessor.discardPendingOutput()
                inMemoryBuffer.clear()
                sessionManager.endSession()
            }
            _collectionState.value = CollectionState(CollectionStatus.STOPPED)
            return@withLock
        }
        _collectionState.value = CollectionState(CollectionStatus.STOPPING)
        
        Log.i(TAG, "Stopping SmartTransmissionManager")
        
        withContext(NonCancellable) {
            stopComponents()
            if (endSession) {
                sensorDataProcessor.discardPendingOutput()
                inMemoryBuffer.clear()
                sessionManager.endSession()
            }
        }
        isPaused.set(false)
        _smartUploadWindowOpen.value = false
        _collectionState.value = CollectionState(CollectionStatus.STOPPED)
        Log.i(TAG, "SmartTransmissionManager stopped successfully")
    }

    private suspend fun stopComponents() {
        // Stop producers first so the processor can persist the final partial batch.
        val shutdownSteps: List<suspend () -> Unit> = listOf(
            { sensorCollector.finishCollection() },
            { sensorDataProcessor.stopProcessing(graceful = true) },
            { stopCoreCoroutines() },
            { timeSync.stopPeriodicSync() },
            { uploadManager.stop() }
        )
        shutdownSteps.forEach { shutdown ->
            try {
                shutdown()
            } catch (e: Exception) {
                Log.e(TAG, "Error during component shutdown", e)
            }
        }
    }

    /**
     * 暂停数据采集（保持连接）
     */
    suspend fun pause(allowBufferedUpload: Boolean = false) = lifecycleMutex.withLock {
        if (!isRunning.get()) {
            return@withLock
        }
        allowBufferedUploadWhilePaused = allowBufferedUpload
        val alreadyPaused = isPaused.getAndSet(true)
        updatePauseUploadGate()
        if (alreadyPaused) return@withLock

        Log.i(TAG, "Pausing data collection")
        sensorCollector.stopCollection()
        _collectionState.value = CollectionState(CollectionStatus.PAUSED)
    }

    /**
     * 恢复数据采集
     */
    suspend fun resume() = lifecycleMutex.withLock {
        if (!isRunning.get() || !isPaused.get()) {
            return@withLock
        }
        
        Log.i(TAG, "Resuming data collection")
        sensorCollector.startCollection()
        isPaused.set(false)
        allowBufferedUploadWhilePaused = false
        updatePauseUploadGate()
        _collectionState.value = CollectionState(CollectionStatus.RUNNING)
    }

    /** Stop feeding an authentication session while retaining the user's collection choice. */
    suspend fun rotateSession() = lifecycleMutex.withLock {
        if (!isRunning.get()) {
            sessionManager.endSession()
            return@withLock
        }
        val wasPaused = isPaused.get()
        try {
            sensorCollector.finishCollection()
            sensorDataProcessor.stopProcessing(graceful = true)
            collectionJob?.cancelAndJoin()
            currentSessionId = sessionManager.startNewSession()
            startSensorPipeline(currentSessionId, startSensors = !wasPaused)
            _collectionState.value = CollectionState(
                if (wasPaused) CollectionStatus.PAUSED else CollectionStatus.RUNNING
            )
        } catch (e: Exception) {
            // A half-rotated pipeline must never keep generating data for the old auth session.
            isRunning.set(false)
            withContext(NonCancellable) {
                stopComponents()
                sessionManager.endSession()
            }
            _collectionState.value = CollectionState(CollectionStatus.ERROR, "认证已停止，数据采集需要重新开启")
            throw e
        }
    }

    private suspend fun stopCoreCoroutines() {
        collectionJob?.cancel()
        monitoringJob?.cancel()
        collectionJob?.join()
        monitoringJob?.join()
        collectionJob = null
        monitoringJob = null
    }

    private fun updatePauseUploadGate() {
        if (isPaused.get() && !(allowBufferedUploadWhilePaused && smartTransmissionEnabled.get())) {
            uploadManager.pauseUpload("collection_paused")
        } else {
            uploadManager.resumeUpload("collection_paused")
        }
    }

    /**
     * 启动传感器采集与加密流水线，将加密 DataPacket 推入内存上传队列。
     */
    private suspend fun startSensorPipeline(sessionId: String, startSensors: Boolean = true) {
        Log.d(TAG, "Starting sensor pipeline for session=$sessionId")
        try {
            val started = sensorDataProcessor.startProcessing(
                sensorCollector.getSensorDataFlow(),
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
                        processedPackets.incrementAndGet()
                        if (smartTransmissionEnabled.get() && !smartTransmissionUploadAllowed.get()) {
                            // 智能模式窗口未满足时仅保留磁盘副本，不进入实时上传队列。
                            return@collect
                        }
                        val enqueued = inMemoryBuffer.enqueue(packet)
                        if (!enqueued) {
                            failedPackets.incrementAndGet()
                        }
                    }
            }
            if (startSensors) sensorCollector.startCollection()
        } catch (e: Exception) {
            failedPackets.incrementAndGet()
            throw e
        }
    }

    private fun resolveServerEndpoint(): String {
        val prefs = context.getSharedPreferences("server_config", Context.MODE_PRIVATE)
        val port = if (prefs.contains("server_port")) prefs.getInt("server_port", 0) else null
        return ServerEndpointNormalizer.normalize(
            addressInput = prefs.getString("server_ip", null),
            portInput = port,
            schemeInput = prefs.getString("server_scheme", null)
        ).endpoint
    }

    /**
     * 启动监控
     */
    private fun startMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            while (isRunning.get()) {
                updateSmartTransmissionGate()
                delay(10000) // 每10秒监控一次
                ensureProcessingActive()
                
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

    internal suspend fun ensureProcessingActive(): Unit = lifecycleMutex.withLock {
        if (!isRunning.get() || isPaused.get() || sensorDataProcessor.getProcessingStatus().isProcessing) {
            return@withLock
        }
        try {
            Log.w(TAG, "Restarting an unexpectedly terminated sensor processor")
            val restarted = sensorDataProcessor.startProcessing(
                sensorCollector.getSensorDataFlow(), currentSessionId
            )
            check(restarted) { "传感器处理器恢复失败" }
            _collectionState.value = CollectionState(CollectionStatus.RUNNING)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedPackets.incrementAndGet()
            // ERROR is terminal for stop/deletion callers; this service is still collecting and retrying.
            _collectionState.value = CollectionState(
                CollectionStatus.RUNNING, "数据处理恢复失败，将自动重试: ${e.message}"
            )
            Log.e(TAG, "Unable to restart the sensor processor", e)
        }
    }

    fun setSmartTransmissionEnabled(enabled: Boolean) {
        val previous = smartTransmissionEnabled.getAndSet(enabled)
        if (!enabled) {
            smartTransmissionUploadAllowed.set(true)
            uploadManager.setSmartTransmissionUploadAllowed(true)
            _smartUploadWindowOpen.value = false
        }
        uploadManager.setSmartTransmissionEnabled(enabled)
        fileQueueManager.setSmartTransmissionMode(enabled)
        updatePauseUploadGate()
        if (enabled) {
            updateSmartTransmissionGate()
        }
        if (previous != enabled) {
            Log.i(TAG, "智能传输开关已更新: $enabled")
        }
    }

    fun isSmartTransmissionEnabled(): Boolean = smartTransmissionEnabled.get()

    private fun applySmartTransmissionPreferences() {
        val prefs = context.getSharedPreferences(UPLOAD_POLICY, Context.MODE_PRIVATE)
        uploadManager.setWifiOnlyMode(prefs.getBoolean("wifi_only", false))
        val enabled = prefs.getBoolean(SMART_TRANSMISSION_ENABLED_KEY, false)
        setSmartTransmissionEnabled(enabled)
    }

    private fun updateSmartTransmissionGate() {
        if (!smartTransmissionEnabled.get()) {
            smartTransmissionUploadAllowed.set(true)
            uploadManager.setSmartTransmissionUploadAllowed(true)
            _smartUploadWindowOpen.value = false
            return
        }

        val batteryLevel = getBatteryLevel()
        val isAfterMidnight = isAfterMidnight()
        val isLocked = isDeviceLocked()
        val allowUpload = batteryLevel > SMART_TRANSMISSION_BATTERY_THRESHOLD &&
            isAfterMidnight &&
            isLocked

        val previous = smartTransmissionUploadAllowed.getAndSet(allowUpload)
        _smartUploadWindowOpen.value = allowUpload
        uploadManager.setSmartTransmissionUploadAllowed(allowUpload)
        if (previous != allowUpload) {
            Log.i(
                TAG,
                "智能传输窗口变化: allowUpload=$allowUpload, battery=$batteryLevel, afterMidnight=$isAfterMidnight, locked=$isLocked"
            )
        }
    }

    private fun getBatteryLevel(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        } else {
            100
        }
    }

    private fun isAfterMidnight(): Boolean {
        val hour = LocalTime.now().hour
        return hour in 0..5
    }

    private fun isDeviceLocked(): Boolean {
        val keyguardLocked = keyguardManager.isKeyguardLocked
        val screenInteractive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }
        return keyguardLocked || !screenInteractive
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
            lastUploadTimestamp = uploadManager.getLastAckTimestamp()
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
