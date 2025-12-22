package com.continuousauth.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.continuousauth.buffer.RingBuffer
import com.continuousauth.model.SensorSample
import com.continuousauth.model.SensorType
import com.continuousauth.pool.SensorEventPool
import com.continuousauth.utils.ForegroundAppDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val sensorEventPool: SensorEventPool
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
    private val sensorDataChannel = Channel<SensorSample>(Channel.UNLIMITED)
    
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
    private var maxReportLatencyUs: Int = 0

    private data class RateTracker(
        var windowStartNs: Long = 0L,
        var windowCount: Int = 0,
        @Volatile var lastRateHz: Float = 0f
    )

    private val rateTrackers = mapOf(
        SensorType.ACCELEROMETER to RateTracker(),
        SensorType.GYROSCOPE to RateTracker(),
        SensorType.MAGNETOMETER to RateTracker()
    )
    
    override suspend fun startCollection() {
        collectionMutex.withLock {
            if (isCollecting.get()) {
                return@withLock
            }
            
            // 检测并应用硬件最大采样率
            detectOptimalSamplingRate()
            
            // 注册传感器监听器（使用固定采样率）
            val registrationResults = listOf(
                registerSensorIfAvailable(accelerometer, "加速度计", accelerometerSamplingPeriodUs),
                registerSensorIfAvailable(gyroscope, "陀螺仪", gyroscopeSamplingPeriodUs), 
                registerSensorIfAvailable(magnetometer, "磁力计", magnetometerSamplingPeriodUs)
            )
            
            if (registrationResults.any { it }) {
                isCollecting.set(true)
                Log.i("SensorCollector", "传感器采集已启动（目标100Hz）")
            } else {
                throw IllegalStateException("没有可用的传感器")
            }
        }
    }
    
    override suspend fun stopCollection() {
        collectionMutex.withLock {
            if (!isCollecting.get()) {
                return@withLock
            }
            
            sensorManager.unregisterListener(this)
            isCollecting.set(false)
            
            // 清空环形缓冲区
            ringBuffer.clear()
            
            Log.i("SensorCollector", "传感器采集已停止")
        }
    }
    
    override fun getSensorDataFlow(): Flow<SensorSample> {
        return sensorDataChannel.receiveAsFlow()
    }
    
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
        val accCurrent = calculateCurrentRate(accelerometerSamplingPeriodUs)
        val gyrCurrent = calculateCurrentRate(gyroscopeSamplingPeriodUs)
        val magCurrent = calculateCurrentRate(magnetometerSamplingPeriodUs)
        val accActual = getActualRate(SensorType.ACCELEROMETER, accCurrent)
        val gyrActual = getActualRate(SensorType.GYROSCOPE, gyrCurrent)
        val magActual = getActualRate(SensorType.MAGNETOMETER, magCurrent)

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
    
    override fun onSensorChanged(event: SensorEvent) {
        if (!isCollecting.get()) return

        val sensorType = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> SensorType.ACCELEROMETER
            Sensor.TYPE_GYROSCOPE -> SensorType.GYROSCOPE
            Sensor.TYPE_MAGNETIC_FIELD -> SensorType.MAGNETOMETER
            else -> return
        }

        updateRate(sensorType, event.timestamp)
        
        // 使用协程在专用线程上处理传感器数据
        sensorScope.launch {
            // 获取当前前台应用
            val currentForegroundApp = try {
                foregroundAppDetector.getCurrentForegroundApp()
            } catch (e: Exception) {
                Log.e("SensorCollector", "获取前台应用失败", e)
                ""
            }
            
            // 使用对象池获取包装对象，减少GC压力
            val pooledSample = sensorEventPool.acquire()
            pooledSample.setSensorData(
                type = sensorType,
                eventTimestampNs = event.timestamp,
                x = event.values[0],
                y = event.values[1],
                z = event.values[2],
                accuracy = event.accuracy,
                seqNo = sequenceNumber.incrementAndGet(),
                foregroundApp = currentForegroundApp
            )
            
            val sample = pooledSample.toSensorSample()
            
            sensorDataChannel.trySend(sample)
            
            // 释放对象回池中
            pooledSample.release()
        }
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
        accelerometerSamplingPeriodUs = TARGET_SAMPLING_PERIOD_US
        gyroscopeSamplingPeriodUs = TARGET_SAMPLING_PERIOD_US
        magnetometerSamplingPeriodUs = TARGET_SAMPLING_PERIOD_US

        // 根据FIFO大小动态设置maxReportLatencyUs以充分利用硬件FIFO队列
        val fifoSizes = listOfNotNull(
            accelerometer?.fifoMaxEventCount,
            gyroscope?.fifoMaxEventCount,
            magnetometer?.fifoMaxEventCount
        ).filter { it > 0 }
        val minFifoSize = fifoSizes.minOrNull() ?: 0
        val minSamplingPeriodUs = TARGET_SAMPLING_PERIOD_US
        
        if (minFifoSize > 0) {
            // 根据FIFO大小和采样周期计算合适的延迟，最长不超过1秒
            maxReportLatencyUs = (minFifoSize * minSamplingPeriodUs)
                .coerceAtMost(1_000_000)
            
            Log.i(
                "SensorCollector", 
                "检测到最小FIFO大小: $minFifoSize, 设置maxReportLatencyUs: ${maxReportLatencyUs}us"
            )
            
            Log.i(
                "SensorCollector", 
                "采样率配置: 加速度计${calculateCurrentRate(accelerometerSamplingPeriodUs)}Hz, " +
                "陀螺仪${calculateCurrentRate(gyroscopeSamplingPeriodUs)}Hz, " +
                "磁力计${calculateCurrentRate(magnetometerSamplingPeriodUs)}Hz"
            )
        } else {
            maxReportLatencyUs = 0 // 实时报告
            Log.i("SensorCollector", "传感器不支持批处理，使用实时模式")
        }
    }
    
    /**
     * 注册单个传感器
     * @param sensor 传感器实例
     * @param sensorName 传感器名称（用于日志）
     * @param samplingPeriodUs 采样周期（微秒）
     */
    private fun registerSensorIfAvailable(sensor: Sensor?, sensorName: String, samplingPeriodUs: Int): Boolean {
        return sensor?.let {
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

    private fun updateRate(sensorType: SensorType, timestampNs: Long) {
        val tracker = rateTrackers[sensorType] ?: return
        synchronized(tracker) {
            if (tracker.windowStartNs == 0L) {
                tracker.windowStartNs = timestampNs
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

    private fun getActualRate(sensorType: SensorType, fallback: Float): Float {
        val tracker = rateTrackers[sensorType] ?: return fallback
        val rate = tracker.lastRateHz
        return if (rate > 0f) rate else fallback
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
}
