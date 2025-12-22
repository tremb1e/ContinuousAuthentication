package com.continuousauth.sensor

import com.continuousauth.model.SensorSample
import kotlinx.coroutines.flow.Flow

/**
 * 传感器数据采集器接口
 */
interface SensorCollector {
    
    /**
     * 开始采集传感器数据
     */
    suspend fun startCollection()
    
    /**
     * 停止采集传感器数据
     */
    suspend fun stopCollection()
    
    /**
     * 获取传感器数据流
     */
    fun getSensorDataFlow(): Flow<SensorSample>
    
    /**
     * 检查是否正在采集
     */
    fun isCollecting(): Boolean
    
    /**
     * 获取传感器信息
     */
    fun getSensorInfo(): SensorInfo
    
    /**
     * 调整采样率
     */
    fun adjustSamplingRate(multiplier: Float)
}

/**
 * 传感器信息数据类
 */
data class SensorInfo(
    val accelerometerMaxDelay: Int,   // 加速度计最大延迟
    val gyroscopeMaxDelay: Int,       // 陀螺仪最大延迟
    val magnetometerMaxDelay: Int,    // 磁力计最大延迟
    val accelerometerMaxRange: Float, // 加速度计最大量程
    val gyroscopeMaxRange: Float,     // 陀螺仪最大量程  
    val magnetometerMaxRange: Float,  // 磁力计最大量程
    val accelerometerFifoSize: Int,   // 加速度计FIFO大小
    val gyroscopeFifoSize: Int,       // 陀螺仪FIFO大小
    val magnetometerFifoSize: Int,    // 磁力计FIFO大小
    val accelerometerMaxRate: Float = 0f,  // 加速度计最大采样率 (Hz)
    val gyroscopeMaxRate: Float = 0f,      // 陀螺仪最大采样率 (Hz)
    val magnetometerMaxRate: Float = 0f,   // 磁力计最大采样率 (Hz)
    val accelerometerCurrentRate: Float = 100f,  // 加速度计当前采样率 (Hz)
    val gyroscopeCurrentRate: Float = 100f,      // 陀螺仪当前采样率 (Hz)
    val magnetometerCurrentRate: Float = 100f,   // 磁力计当前采样率 (Hz)
    val accelerometerActualRate: Float = 0f,     // 加速度计实际采样率 (Hz)
    val gyroscopeActualRate: Float = 0f,         // 陀螺仪实际采样率 (Hz)
    val magnetometerActualRate: Float = 0f       // 磁力计实际采样率 (Hz)
)
