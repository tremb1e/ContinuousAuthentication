package com.continuousauth.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.continuousauth.buffer.RingBuffer
import com.continuousauth.model.SensorSample
import com.continuousauth.model.SensorType
import com.continuousauth.pool.SensorEventPool
import com.continuousauth.utils.ForegroundAppDetector
import com.continuousauth.settings.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 传感器数据采集器实现类
 * 使用SensorManager和专用的Coroutine Dispatcher注册传感器监听器
 * 支持自动检测并应用硬件最大采样率
 */
@Singleton
class SensorCollectorImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val foregroundAppDetector: ForegroundAppDetector,
    private val ringBuffer: RingBuffer,
    private val sensorEventPool: SensorEventPool,
    private val settingsRepository: AppSettingsRepository
) : SensorCollector, SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensorThread = HandlerThread("CA-SensorThread").apply { start() }
    private val sensorHandler by lazy { Handler(sensorThread.looper) }
    
    // 使用单线程的Dispatcher专门处理传感器数据
    private val sensorDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val sensorScope = CoroutineScope(SupervisorJob() + sensorDispatcher)
    
    // 传感器实例
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    
    // 数据流通道
    private val sampleStreams = SensorSampleStreams()
    private sealed interface PendingSample {
        data class Captured(val sample: SensorSample) : PendingSample
        data class Drain(val completed: CompletableDeferred<Unit>) : PendingSample
    }
    private val pendingSamples = Channel<PendingSample>(Channel.UNLIMITED)
    private val callbackLock = Any()
    
    // 状态管理
    private val isCollecting = AtomicBoolean(false)
    private val collectionMutex = Mutex()
    
    // 序列号生成器
    private val sequenceNumber = AtomicLong(0L)
    
    // 目标采样率 100Hz（10ms），三类传感器保持一致，硬件达标时强制使用
    private val TARGET_SAMPLING_RATE_HZ = 100
    private val TARGET_SAMPLING_PERIOD_US = 1_000_000 / TARGET_SAMPLING_RATE_HZ
    private var accelerometerSamplingPeriodUs = TARGET_SAMPLING_PERIOD_US
    private var gyroscopeSamplingPeriodUs = TARGET_SAMPLING_PERIOD_US
    private var magnetometerSamplingPeriodUs = TARGET_SAMPLING_PERIOD_US
    private val maxReportLatencyUs = 0
    private val sampleFreshnessNs = 3_000_000_000L

    private data class RateTracker(
        var windowStartNs: Long = 0L,
        var windowCount: Int = 0,
        var lastRateHz: Float = 0f,
        var lastCallbackElapsedNs: Long? = null
    )

    private val rateTrackers = mapOf(
        SensorType.ACCELEROMETER to RateTracker(),
        SensorType.GYROSCOPE to RateTracker(),
        SensorType.MAGNETOMETER to RateTracker()
    )

    init {
        var appliedSettings = settingsRepository.sensorSettings.value
        sensorScope.launch {
            settingsRepository.sensorSettings.collect { current ->
                val changed = current != appliedSettings
                appliedSettings = current
                if (changed && isCollecting.get()) {
                    runCatching { reRegisterSensors() }
                        .onFailure { Log.e("SensorCollector", "应用传感器开关失败", it) }
                }
            }
        }
        sensorScope.launch {
            for (pending in pendingSamples) {
                when (pending) {
                    is PendingSample.Captured -> {
                        val captured = pending.sample
                        val foregroundApp = try {
                            foregroundAppDetector.getForegroundAppAt(captured.eventTimestampNs)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("SensorCollector", "获取前台应用失败", e)
                            ""
                        }
                        sampleStreams.publish(captured.copy(foregroundApp = foregroundApp))
                    }
                    is PendingSample.Drain -> pending.completed.complete(Unit)
                }
            }
        }
    }
    
    override suspend fun startCollection() {
        collectionMutex.withLock {
            if (isCollecting.get()) {
                return@withLock
            }
            
            // 检测并应用硬件最大采样率
            detectOptimalSamplingRate()
            resetRateTrackers()
            isCollecting.set(true)
            
            // 注册传感器监听器（使用固定采样率）
            val registrationResults = listOf(
                registerSensorIfAvailable(accelerometer, "加速度计", accelerometerSamplingPeriodUs),
                registerSensorIfAvailable(gyroscope, "陀螺仪", gyroscopeSamplingPeriodUs), 
                registerSensorIfAvailable(magnetometer, "磁力计", magnetometerSamplingPeriodUs)
            )
            
            if (registrationResults.any { it }) {
                Log.i("SensorCollector", "传感器采集已启动（目标100Hz）")
            } else {
                isCollecting.set(false)
                throw IllegalStateException("没有可用的传感器")
            }
        }
    }
    
    override suspend fun stopCollection() {
        collectionMutex.withLock {
            val wasCollecting = synchronized(callbackLock) {
                isCollecting.getAndSet(false)
            }
            if (wasCollecting) {
                sensorManager.unregisterListener(this)
            }
            // A cancelled pause may already have cleared the flag while accepted events
            // are still awaiting attribution. Always drain before completing the stream.
            val drained = CompletableDeferred<Unit>()
            pendingSamples.send(PendingSample.Drain(drained))
            drained.await()
            resetRateTrackers()
            
            // 清空环形缓冲区
            ringBuffer.clear()
            
            Log.i("SensorCollector", "传感器采集已停止")
        }
    }

    override suspend fun finishCollection() {
        stopCollection()
        sampleStreams.finishProcessing()
    }
    
    override fun getSensorDataFlow(): Flow<SensorSample> {
        return sampleStreams.processingFlow
    }

    override fun getPreviewDataFlow(): Flow<SensorSample> = sampleStreams.previewFlow
    
    override fun isCollecting(): Boolean {
        return isCollecting.get()
    }
    
    override fun getSensorInfo(): SensorInfo {
        // 计算硬件最大采样率 (Hz) = 1000000.0 / minDelay (微秒)
        fun calculateMaxRate(sensor: Sensor?): Float {
            return sensor?.minDelay?.let { minDelay ->
                if (minDelay > 0) {
                    1000000.0f / minDelay
                } else {
                    0f
                }
            } ?: 0f
        }
        
        // 返回传感器信息，包含固定的采样率
        val accCurrent = if (accelerometer != null) calculateCurrentRate(accelerometerSamplingPeriodUs) else 0f
        val gyrCurrent = if (gyroscope != null) calculateCurrentRate(gyroscopeSamplingPeriodUs) else 0f
        val magCurrent = if (magnetometer != null) calculateCurrentRate(magnetometerSamplingPeriodUs) else 0f
        val accActual = getActualRate(SensorType.ACCELEROMETER)
        val gyrActual = getActualRate(SensorType.GYROSCOPE)
        val magActual = getActualRate(SensorType.MAGNETOMETER)

        return SensorInfo(
            accelerometerMaxDelay = accelerometer?.maxDelay ?: 0,
            gyroscopeMaxDelay = gyroscope?.maxDelay ?: 0,
            magnetometerMaxDelay = magnetometer?.maxDelay ?: 0,
            accelerometerMaxRange = accelerometer?.maximumRange ?: 0f,
            gyroscopeMaxRange = gyroscope?.maximumRange ?: 0f,
            magnetometerMaxRange = magnetometer?.maximumRange ?: 0f,
            accelerometerFifoSize = accelerometer?.fifoMaxEventCount ?: 0,
            gyroscopeFifoSize = gyroscope?.fifoMaxEventCount ?: 0,
            magnetometerFifoSize = magnetometer?.fifoMaxEventCount ?: 0,
            accelerometerMaxRate = calculateMaxRate(accelerometer),
            gyroscopeMaxRate = calculateMaxRate(gyroscope),
            magnetometerMaxRate = calculateMaxRate(magnetometer),
            // 当前有效采样率（根据硬件能力可能 <=100Hz）
            accelerometerCurrentRate = accCurrent,
            gyroscopeCurrentRate = gyrCurrent,
            magnetometerCurrentRate = magCurrent,
            accelerometerActualRate = accActual,
            gyroscopeActualRate = gyrActual,
            magnetometerActualRate = magActual
        )
    }
    
    override fun onSensorChanged(event: SensorEvent) = synchronized(callbackLock) {
        if (!isCollecting.get()) return

        val sensorType = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> SensorType.ACCELEROMETER
            Sensor.TYPE_GYROSCOPE -> SensorType.GYROSCOPE
            Sensor.TYPE_MAGNETIC_FIELD -> SensorType.MAGNETOMETER
            else -> return
        }
        if (!isSensorEnabled(sensorType)) return

        updateRate(sensorType, event.timestamp)

        // Android reuses SensorEvent and its values array immediately after this callback.
        val pooledSample = sensorEventPool.acquire()
        val capturedSample = try {
            pooledSample.setSensorData(
                type = sensorType,
                eventTimestampNs = event.timestamp,
                x = event.values[0],
                y = event.values[1],
                z = event.values[2],
                accuracy = event.accuracy,
                seqNo = sequenceNumber.incrementAndGet(),
                foregroundApp = ""
            )
            pooledSample.toSensorSample()
        } finally {
            pooledSample.release()
        }

        check(pendingSamples.trySend(PendingSample.Captured(capturedSample)).isSuccess)
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 记录精度变化
        sensor?.let {
            Log.d("SensorCollector", "传感器 ${it.name} 精度变更为: $accuracy")
        }
    }
    
    /**
     * 检测并应用最优采样率
     * 三类传感器目标 100Hz，硬件达标时强制 100Hz，不因省电或锁屏降级
     */
    private fun detectOptimalSamplingRate() {
        accelerometerSamplingPeriodUs = samplingPeriodFor(accelerometer, TARGET_SAMPLING_PERIOD_US)
        gyroscopeSamplingPeriodUs = samplingPeriodFor(gyroscope, TARGET_SAMPLING_PERIOD_US)
        magnetometerSamplingPeriodUs = samplingPeriodFor(magnetometer, TARGET_SAMPLING_PERIOD_US)

        // The processor owns the one-second batching deadline; hardware delivery stays immediate.
        Log.i(
            "SensorCollector",
            "采样率配置: 加速度计${calculateCurrentRate(accelerometerSamplingPeriodUs)}Hz, " +
                "陀螺仪${calculateCurrentRate(gyroscopeSamplingPeriodUs)}Hz, " +
                "磁力计${calculateCurrentRate(magnetometerSamplingPeriodUs)}Hz; 实时报告"
        )
    }
    
    /**
     * 注册单个传感器
     * @param sensor 传感器实例
     * @param sensorName 传感器名称（用于日志）
     * @param samplingPeriodUs 采样周期（微秒）
     */
    private fun registerSensorIfAvailable(sensor: Sensor?, sensorName: String, samplingPeriodUs: Int): Boolean {
        return sensor?.let {
            val enabled = when (it.type) {
                Sensor.TYPE_ACCELEROMETER -> settingsRepository.sensorSettings.value.accelerometerEnabled
                Sensor.TYPE_GYROSCOPE -> settingsRepository.sensorSettings.value.gyroscopeEnabled
                Sensor.TYPE_MAGNETIC_FIELD -> settingsRepository.sensorSettings.value.magnetometerEnabled
                else -> false
            }
            if (!enabled) return false
            val success = sensorManager.registerListener(
                this,
                it,
                samplingPeriodUs,
                maxReportLatencyUs,
                sensorHandler
            )
            
            if (success) {
                val samplingRateHz = 1000000.0f / samplingPeriodUs
                Log.i("SensorCollector",
                    "$sensorName 注册成功 - 采样率: ${samplingRateHz}Hz, 采样周期: ${samplingPeriodUs}us, 最大延迟: ${maxReportLatencyUs}us")
            } else {
                Log.w("SensorCollector", "$sensorName 注册失败")
            }
            
            success
        } ?: run {
            Log.w("SensorCollector", "$sensorName 不可用")
            false
        }
    }

    private fun calculateCurrentRate(periodUs: Int): Float {
        return if (periodUs > 0) 1_000_000f / periodUs else 0f
    }

    private fun samplingPeriodFor(sensor: Sensor?, requestedUs: Int): Int =
        maxOf(requestedUs, sensor?.minDelay ?: 0)

    private fun resetRateTrackers() {
        rateTrackers.values.forEach { tracker ->
            synchronized(tracker) {
                tracker.windowStartNs = 0L
                tracker.windowCount = 0
                tracker.lastRateHz = 0f
                tracker.lastCallbackElapsedNs = null
            }
        }
    }

    private fun updateRate(sensorType: SensorType, timestampNs: Long) {
        val tracker = rateTrackers[sensorType] ?: return
        synchronized(tracker) {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val previousCallbackNs = tracker.lastCallbackElapsedNs
            tracker.lastCallbackElapsedNs = nowNs
            if (previousCallbackNs == null || nowNs - previousCallbackNs >= sampleFreshnessNs) {
                tracker.windowStartNs = timestampNs
                tracker.windowCount = 0
                tracker.lastRateHz = 0f
                return
            }
            tracker.windowCount += 1
            val elapsedNs = timestampNs - tracker.windowStartNs
            if (elapsedNs >= 1_000_000_000L) {
                tracker.lastRateHz = tracker.windowCount / (elapsedNs / 1_000_000_000f)
                tracker.windowStartNs = timestampNs
                tracker.windowCount = 0
            }
        }
    }

    private fun getActualRate(sensorType: SensorType): Float {
        if (!isCollecting.get() || !isSensorEnabled(sensorType)) return 0f
        val tracker = rateTrackers[sensorType] ?: return 0f
        return synchronized(tracker) {
            val lastCallbackNs = tracker.lastCallbackElapsedNs ?: return@synchronized 0f
            if (SystemClock.elapsedRealtimeNanos() - lastCallbackNs !in 0 until sampleFreshnessNs) {
                0f
            } else {
                tracker.lastRateHz
            }
        }
    }
    
    /**
     * 调整采样率
     */
    override fun adjustSamplingRate(multiplier: Float) {
        Log.i("SensorCollector", "调整采样率倍数: $multiplier")
        // TODO: 实现采样率调整逻辑
        // 验证倍数范围
        if (multiplier <= 0f) {
            Log.e("SensorCollector", "无效的采样率倍数: $multiplier，必须大于0")
            return
        }

        // 计算新的采样周期（微秒）
        val newPeriodUs = (TARGET_SAMPLING_PERIOD_US / multiplier).toInt()

        // 验证硬件限制
        val minPeriodUs = getMinSamplingPeriodUs()
        val maxPeriodUs = 1_000_000 // 最低1Hz

        // 应用边界检查
        val clampedPeriodUs = newPeriodUs.coerceIn(minPeriodUs, maxPeriodUs)

        if (clampedPeriodUs != newPeriodUs) {
            Log.w("SensorCollector",
                "采样率超出硬件限制，从${newPeriodUs}us调整到${clampedPeriodUs}us")
        }

        // 如果采样周期没有变化，无需重新注册
        if (accelerometerSamplingPeriodUs == clampedPeriodUs &&
            gyroscopeSamplingPeriodUs == clampedPeriodUs &&
            magnetometerSamplingPeriodUs == clampedPeriodUs) {
            Log.i("SensorCollector", "采样率未变化，无需调整")
            return
        }

        // 更新采样周期
        val previousPeriodUs = accelerometerSamplingPeriodUs
        accelerometerSamplingPeriodUs = clampedPeriodUs
        gyroscopeSamplingPeriodUs = clampedPeriodUs
        magnetometerSamplingPeriodUs = clampedPeriodUs

        Log.i("SensorCollector",
            "采样周期从${previousPeriodUs}us调整到${clampedPeriodUs}us，采样率: ${calculateCurrentRate(clampedPeriodUs)}Hz")

        // 如果正在采集，重新注册传感器
        if (isCollecting.get()) {
            sensorScope.launch {
                try {
                    reRegisterSensors()
                } catch (e: Exception) {
                    Log.e("SensorCollector", "重新注册传感器失败", e)
                    // 恢复之前的采样率
                    accelerometerSamplingPeriodUs = previousPeriodUs
                    gyroscopeSamplingPeriodUs = previousPeriodUs
                    magnetometerSamplingPeriodUs = previousPeriodUs
                }
            }
        }
    }
    /**
     * 获取传感器支持的最小采样周期（微秒）
     */
    private fun getMinSamplingPeriodUs(): Int {
        val minDelays = listOfNotNull(
            accelerometer?.minDelay,
            gyroscope?.minDelay,
            magnetometer?.minDelay
        ).filter { it > 0 }

        return minDelays.minOrNull() ?: TARGET_SAMPLING_PERIOD_US
    }

    /**
     * 重新注册所有传感器（使用新的采样率）
     */
    private suspend fun reRegisterSensors() = collectionMutex.withLock {
        if (!isCollecting.get()) return@withLock

        Log.i("SensorCollector", "重新注册传感器，应用新的采样率")

        // 先取消注册所有传感器
        sensorManager.unregisterListener(this)
        resetRateTrackers()
        if (!settingsRepository.sensorSettings.value.anyEnabled) return@withLock

        // 重新检测最优采样率配置
        detectOptimalSamplingRate()

        // 重新注册传感器
        val registrationResults = listOf(
            registerSensorIfAvailable(accelerometer, "加速度计", accelerometerSamplingPeriodUs),
            registerSensorIfAvailable(gyroscope, "陀螺仪", gyroscopeSamplingPeriodUs),
            registerSensorIfAvailable(magnetometer, "磁力计", magnetometerSamplingPeriodUs)
        )

        if (registrationResults.any { it }) {
            Log.i("SensorCollector",
                "传感器重新注册成功 - " +
                        "加速度计: ${calculateCurrentRate(accelerometerSamplingPeriodUs)}Hz, " +
                        "陀螺仪: ${calculateCurrentRate(gyroscopeSamplingPeriodUs)}Hz, " +
                        "磁力计: ${calculateCurrentRate(magnetometerSamplingPeriodUs)}Hz")
        } else {
            Log.e("SensorCollector", "传感器重新注册失败，恢复之前的采样率")
            throw IllegalStateException("传感器重新注册失败")
        }
    }

    private fun isSensorEnabled(type: SensorType): Boolean = when (type) {
        SensorType.ACCELEROMETER -> settingsRepository.sensorSettings.value.accelerometerEnabled
        SensorType.GYROSCOPE -> settingsRepository.sensorSettings.value.gyroscopeEnabled
        SensorType.MAGNETOMETER -> settingsRepository.sensorSettings.value.magnetometerEnabled
    }
}
