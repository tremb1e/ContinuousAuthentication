package com.continuousauth.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.continuousauth.buffer.RingBuffer
import com.continuousauth.model.SensorSample
import com.continuousauth.model.SensorType
import com.continuousauth.pool.SensorEventPool
import com.continuousauth.utils.ForegroundAppDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    
    // 使用单线程的Dispatcher专门处理传感器数据
    private val sensorDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val sensorScope = CoroutineScope(SupervisorJob() + sensorDispatcher)
    
    // 传感器实例
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    
    // 数据流通道
    private val sensorDataChannel = Channel<SensorSample>(Channel.UNLIMITED)
    
    // 窗口化批处理协程（1秒窗口）
    private var windowBatchingJob: Job? = null
    
    // 当前窗口的样本缓存
    private val currentWindowSamples = mutableListOf<SensorSample>()
    private val windowMutex = Mutex()
    
    // 状态管理
    private val isCollecting = AtomicBoolean(false)
    private val collectionMutex = Mutex()
    
    // 序列号生成器
    private val sequenceNumber = AtomicLong(0L)
    
    // 硬件采样率配置（微秒）
    // 根据要求：加速度计和陀螺仪200Hz（5000微秒），磁力计100Hz（10000微秒）
    private val ACCELEROMETER_SAMPLING_PERIOD_US = 5000  // 200Hz
    private val GYROSCOPE_SAMPLING_PERIOD_US = 5000     // 200Hz
    private val MAGNETOMETER_SAMPLING_PERIOD_US = 10000  // 100Hz
    private var maxReportLatencyUs: Int = 0
    
    override suspend fun startCollection() {
        collectionMutex.withLock {
            if (isCollecting.get()) {
                return@withLock
            }
            
            // 检测并应用硬件最大采样率
            detectOptimalSamplingRate()
            
            // 注册传感器监听器（使用固定采样率）
            val registrationResults = listOf(
                registerSensorIfAvailable(accelerometer, "加速度计", ACCELEROMETER_SAMPLING_PERIOD_US),
                registerSensorIfAvailable(gyroscope, "陀螺仪", GYROSCOPE_SAMPLING_PERIOD_US), 
                registerSensorIfAvailable(magnetometer, "磁力计", MAGNETOMETER_SAMPLING_PERIOD_US)
            )
            
            if (registrationResults.any { it }) {
                isCollecting.set(true)
                
                // 启动窗口化批处理协程（1秒窗口）
                startWindowBatching()
                
                android.util.Log.i("SensorCollector", "传感器采集已启动（1秒窗口批处理）")
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
            
            // 停止窗口化批处理
            windowBatchingJob?.cancel()
            windowBatchingJob = null
            
            // 清空环形缓冲区
            ringBuffer.clear()
            
            android.util.Log.i("SensorCollector", "传感器采集已停止")
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
            // 添加固定的采样率信息
            accelerometerCurrentRate = 200f,  // 固定200Hz
            gyroscopeCurrentRate = 200f,      // 固定200Hz  
            magnetometerCurrentRate = 100f    // 固定100Hz
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
        
        // 使用协程在专用线程上处理传感器数据
        sensorScope.launch {
            // 获取当前前台应用
            val currentForegroundApp = try {
                foregroundAppDetector.getCurrentForegroundApp()
            } catch (e: Exception) {
                android.util.Log.e("SensorCollector", "获取前台应用失败", e)
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
            
            // 添加到当前窗口缓存
            windowMutex.withLock {
                currentWindowSamples.add(sample)
            }
            
            // 释放对象回池中
            pooledSample.release()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 记录精度变化
        sensor?.let {
            android.util.Log.d("SensorCollector", "传感器 ${it.name} 精度变更为: $accuracy")
        }
    }
    
    /**
     * 检测并应用最优采样率
     * 使用固定采样率：加速度计和陀螺仪200Hz，磁力计100Hz
     */
    private fun detectOptimalSamplingRate() {
        // 根据FIFO大小动态设置maxReportLatencyUs以充分利用硬件FIFO队列
        val minFifoSize = minOf(
            accelerometer?.fifoMaxEventCount ?: Int.MAX_VALUE,
            gyroscope?.fifoMaxEventCount ?: Int.MAX_VALUE,
            magnetometer?.fifoMaxEventCount ?: Int.MAX_VALUE
        )
        
        if (minFifoSize > 0 && minFifoSize != Int.MAX_VALUE) {
            // 根据FIFO大小和采样率计算合适的延迟
            // 使用200Hz作为基准（5ms间隔），FIFO可以缓存的时间
            val samplingIntervalMs = 5 // 200Hz
            maxReportLatencyUs = (minFifoSize * samplingIntervalMs * 1000).coerceAtMost(1000000) // 最大1秒
            
            android.util.Log.i("SensorCollector", 
                "检测到最小FIFO大小: $minFifoSize, 设置maxReportLatencyUs: ${maxReportLatencyUs}us")
            android.util.Log.i("SensorCollector", 
                "采样率配置: 加速度计200Hz, 陀螺仪200Hz, 磁力计100Hz")
        } else {
            maxReportLatencyUs = 0 // 实时报告
            android.util.Log.i("SensorCollector", "传感器不支持批处理，使用实时模式")
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
                maxReportLatencyUs
            )
            
            if (success) {
                val samplingRateHz = 1000000.0f / samplingPeriodUs
                android.util.Log.i("SensorCollector", 
                    "$sensorName 注册成功 - 采样率: ${samplingRateHz}Hz, 采样周期: ${samplingPeriodUs}us, 最大延迟: ${maxReportLatencyUs}us")
            } else {
                android.util.Log.w("SensorCollector", "$sensorName 注册失败")
            }
            
            success
        } ?: run {
            android.util.Log.w("SensorCollector", "$sensorName 不可用")
            false
        }
    }
    
    /**
     * 启动窗口化批处理协程
     * 每1秒将窗口内的样本打包发送
     */
    private fun startWindowBatching() {
        windowBatchingJob = sensorScope.launch {
            while (isCollecting.get()) {
                try {
                    // 等待1秒窗口
                    delay(1000)
                    
                    // 收集当前窗口的样本
                    val windowSamples = windowMutex.withLock {
                        val samples = currentWindowSamples.toList()
                        currentWindowSamples.clear()
                        samples
                    }
                    
                    if (windowSamples.isNotEmpty()) {
                        android.util.Log.d("SensorCollector", 
                            "窗口批处理: 收集 ${windowSamples.size} 个样本")
                        
                        // 将样本批量发送到数据流进行后续处理
                        windowSamples.forEach { sample ->
                            sensorDataChannel.trySend(sample)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SensorCollector", "窗口批处理异常", e)
                    delay(1000) // 出错后延迟1秒再试
                }
            }
        }
    }
    
    /**
     * 调整采样率
     */
    override fun adjustSamplingRate(multiplier: Float) {
        android.util.Log.i("SensorCollector", "调整采样率倍数: $multiplier")
        // TODO: 实现采样率调整逻辑
    }
}