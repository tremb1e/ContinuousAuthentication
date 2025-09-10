package com.continuousauth.pool

import com.continuousauth.model.SensorSample
import com.continuousauth.model.SensorType
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 传感器事件对象池
 * 避免高频数据采集时频繁创建对象和引发GC压力
 */
@Singleton
class SensorEventPool @Inject constructor() {
    
    companion object {
        private const val DEFAULT_POOL_SIZE = 1000
        private const val MAX_POOL_SIZE = 5000
        private const val TAG = "SensorEventPool"
    }
    
    // 对象池队列
    private val pool = ConcurrentLinkedQueue<PooledSensorSample>()
    private val currentPoolSize = AtomicInteger(0)
    
    // 统计信息
    private val totalAcquired = AtomicLong(0L)
    private val totalReleased = AtomicLong(0L)
    private val totalCreated = AtomicLong(0L)
    
    init {
        // 预填充对象池
        prefillPool()
    }
    
    /**
     * 从对象池获取一个传感器样本包装对象
     */
    fun acquire(): PooledSensorSample {
        val pooledSample = pool.poll()
        
        return if (pooledSample != null) {
            currentPoolSize.decrementAndGet()
            totalAcquired.incrementAndGet()
            pooledSample.reset() // 重置状态
            pooledSample
        } else {
            // 池中无可用对象，创建新对象
            totalCreated.incrementAndGet()
            totalAcquired.incrementAndGet()
            android.util.Log.v(TAG, "池中无可用对象，创建新对象")
            PooledSensorSample(this)
        }
    }
    
    /**
     * 将对象释放回对象池
     */
    fun release(pooledSample: PooledSensorSample) {
        if (currentPoolSize.get() < MAX_POOL_SIZE) {
            pooledSample.reset()
            pool.offer(pooledSample)
            currentPoolSize.incrementAndGet()
            totalReleased.incrementAndGet()
        } else {
            // 池已满，让对象被GC回收
            totalReleased.incrementAndGet()
            android.util.Log.v(TAG, "对象池已满，对象将被GC回收")
        }
    }
    
    /**
     * 获取对象池状态
     */
    fun getPoolStatus(): PoolStatus {
        return PoolStatus(
            currentSize = currentPoolSize.get(),
            maxSize = MAX_POOL_SIZE,
            totalAcquired = totalAcquired.get(),
            totalReleased = totalReleased.get(),
            totalCreated = totalCreated.get(),
            hitRate = if (totalAcquired.get() > 0) {
                (totalAcquired.get() - totalCreated.get()).toDouble() / totalAcquired.get()
            } else 0.0
        )
    }
    
    /**
     * 预填充对象池
     */
    private fun prefillPool() {
        repeat(DEFAULT_POOL_SIZE) {
            pool.offer(PooledSensorSample(this))
            currentPoolSize.incrementAndGet()
        }
        android.util.Log.i(TAG, "对象池预填充完成 - 初始大小: $DEFAULT_POOL_SIZE")
    }
}

/**
 * 池化的传感器样本包装类
 */
class PooledSensorSample(private val pool: SensorEventPool) {
    
    private var type: SensorType = SensorType.ACCELEROMETER
    private var eventTimestampNs: Long = 0L
    private var x: Float = 0f
    private var y: Float = 0f
    private var z: Float = 0f
    private var accuracy: Int = 0
    private var seqNo: Long = 0L
    private var foregroundApp: String = ""
    
    /**
     * 设置传感器数据
     */
    fun setSensorData(
        type: SensorType,
        eventTimestampNs: Long,
        x: Float,
        y: Float,
        z: Float,
        accuracy: Int,
        seqNo: Long,
        foregroundApp: String
    ) {
        this.type = type
        this.eventTimestampNs = eventTimestampNs
        this.x = x
        this.y = y
        this.z = z
        this.accuracy = accuracy
        this.seqNo = seqNo
        this.foregroundApp = foregroundApp
    }
    
    /**
     * 转换为SensorSample
     */
    fun toSensorSample(): SensorSample {
        return SensorSample(
            type = type,
            eventTimestampNs = eventTimestampNs,
            x = x,
            y = y,
            z = z,
            accuracy = accuracy,
            seqNo = seqNo,
            foregroundApp = foregroundApp
        )
    }
    
    /**
     * 重置对象状态
     */
    fun reset() {
        type = SensorType.ACCELEROMETER
        eventTimestampNs = 0L
        x = 0f
        y = 0f
        z = 0f
        accuracy = 0
        seqNo = 0L
        foregroundApp = ""
    }
    
    /**
     * 释放对象回池中（使用try-with-resources模式）
     */
    fun release() {
        pool.release(this)
    }
}

/**
 * 对象池状态信息
 */
data class PoolStatus(
    val currentSize: Int,       // 当前池大小
    val maxSize: Int,          // 最大池大小
    val totalAcquired: Long,   // 总获取次数
    val totalReleased: Long,   // 总释放次数
    val totalCreated: Long,    // 总创建次数
    val hitRate: Double        // 命中率（从池中获取的比例）
)