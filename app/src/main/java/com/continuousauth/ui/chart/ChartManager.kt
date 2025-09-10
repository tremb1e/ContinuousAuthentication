package com.continuousauth.ui.chart

import android.util.Log
import com.continuousauth.sensor.SensorCollector
import com.continuousauth.model.SensorType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 图表管理器
 * 负责管理传感器数据的可视化显示
 * 确保FIFO批次数据全部显示
 */
@Singleton
class ChartManager @Inject constructor(
    private val sensorCollector: SensorCollector
) {
    
    companion object {
        private const val TAG = "ChartManager"
        private const val UPDATE_INTERVAL_MS = 100L // 图表更新间隔
    }
    
    private var chartView: SensorChartView? = null
    private var isVisualizationRunning = false
    private var visualizationJob: Job? = null
    private val chartScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    /**
     * 绑定图表视图
     */
    fun bindChartView(chartView: SensorChartView) {
        this.chartView = chartView
        Log.d(TAG, "图表视图已绑定")
    }
    
    /**
     * 解绑图表视图
     */
    fun unbindChartView() {
        this.chartView = null
        Log.d(TAG, "图表视图已解绑")
    }
    
    /**
     * 开始可视化
     * 从SensorCollector获取实际传感器数据流
     * 确保FIFO批次的全部数据都被显示
     */
    fun startVisualization() {
        if (isVisualizationRunning) {
            Log.w(TAG, "可视化已在运行中")
            return
        }
        
        isVisualizationRunning = true
        Log.i(TAG, "开始数据可视化 - 连接到实际传感器数据流")
        
        visualizationJob = chartScope.launch {
            try {
                // 获取传感器数据流
                val sensorDataFlow = sensorCollector.getSensorDataFlow()
                
                // 创建批处理缓冲区，确保FIFO批次数据不丢失
                val batchBuffer = mutableListOf<com.continuousauth.model.SensorSample>()
                var lastBatchTime = System.currentTimeMillis()
                
                // 收集数据并批量更新图表
                sensorDataFlow
                    .takeWhile { isVisualizationRunning }
                    .collect { sample ->
                        // 将样本添加到批处理缓冲区
                        batchBuffer.add(sample)
                        
                        val currentTime = System.currentTimeMillis()
                        
                        // 每100ms或缓冲区达到一定大小时批量更新图表
                        if (currentTime - lastBatchTime >= UPDATE_INTERVAL_MS || batchBuffer.size >= 50) {
                            // 批量处理所有缓冲的数据，确保FIFO数据全部显示
                            processBatchedSamples(batchBuffer.toList())
                            batchBuffer.clear()
                            lastBatchTime = currentTime
                        }
                    }
                    
            } catch (e: CancellationException) {
                Log.i(TAG, "可视化任务已取消")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "可视化数据流异常", e)
                // 如果数据流失败，回退到模拟数据
                fallbackToMockData()
            }
        }
    }
    
    /**
     * 批量处理传感器样本
     * 确保FIFO批次的全部数据都被添加到图表
     */
    private fun processBatchedSamples(samples: List<com.continuousauth.model.SensorSample>) {
        if (chartView == null || samples.isEmpty()) return
        
        Log.d(TAG, "批量处理 ${samples.size} 个传感器样本")
        
        // 按传感器类型分组
        val groupedSamples = samples.groupBy { it.type }
        
        // 批量添加每种传感器的数据
        groupedSamples.forEach { (type, typeSamples) ->
            typeSamples.forEach { sample ->
                when (type) {
                    SensorType.ACCELEROMETER -> {
                        chartView?.addAccelerometerData(sample.x, sample.y, sample.z)
                    }
                    SensorType.GYROSCOPE -> {
                        chartView?.addGyroscopeData(sample.x, sample.y, sample.z)
                    }
                    SensorType.MAGNETOMETER -> {
                        chartView?.addMagnetometerData(sample.x, sample.y, sample.z)
                    }
                }
            }
        }
    }
    
    /**
     * 回退到模拟数据（当实际传感器数据不可用时）
     */
    private suspend fun fallbackToMockData() {
        Log.w(TAG, "回退到模拟数据模式")
        
        while (isVisualizationRunning && visualizationJob?.isActive == true) {
            try {
                updateChartWithMockData()
                delay(UPDATE_INTERVAL_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "模拟数据更新异常", e)
                delay(1000L)
            }
        }
    }
    
    /**
     * 停止可视化
     */
    fun stopVisualization() {
        if (!isVisualizationRunning) {
            return
        }
        
        isVisualizationRunning = false
        visualizationJob?.cancel()
        Log.i(TAG, "停止数据可视化")
    }
    
    /**
     * 清除图表数据
     */
    fun clearChart() {
        chartView?.clearData()
        Log.d(TAG, "图表数据已清除")
    }
    
    /**
     * 添加传感器数据到图表
     */
    fun addSensorData(sensorType: String, x: Float, y: Float, z: Float) {
        chartView?.let { chart ->
            when (sensorType) {
                "accelerometer" -> chart.addAccelerometerData(x, y, z)
                "gyroscope" -> chart.addGyroscopeData(x, y, z)
                "magnetometer" -> chart.addMagnetometerData(x, y, z)
                else -> Log.w(TAG, "未知的传感器类型: $sensorType")
            }
        }
    }
    
    /**
     * 获取图表统计信息
     */
    fun getChartStats(): ChartStats? {
        return chartView?.getDataStats()
    }
    
    /**
     * 是否正在运行可视化
     */
    fun isVisualizationRunning(): Boolean {
        return isVisualizationRunning
    }
    
    /**
     * 使用模拟数据更新图表（用于测试和演示）
     */
    private fun updateChartWithMockData() {
        if (chartView == null) return
        
        val time = System.currentTimeMillis()
        val factor = (time / 1000.0) % (2 * Math.PI)
        
        // 生成模拟的加速度计数据
        val accX = (Math.sin(factor) * 5).toFloat()
        val accY = (Math.cos(factor) * 3).toFloat()
        val accZ = (Math.sin(factor * 2) * 2).toFloat()
        addSensorData("accelerometer", accX, accY, accZ)
        
        // 生成模拟的陀螺仪数据
        val gyroX = (Math.cos(factor * 1.5) * 2).toFloat()
        val gyroY = (Math.sin(factor * 1.2) * 1.5).toFloat()
        val gyroZ = (Math.cos(factor * 0.8) * 1).toFloat()
        addSensorData("gyroscope", gyroX, gyroY, gyroZ)
        
        // 生成模拟的磁力计数据
        val magX = (Math.sin(factor * 0.5) * 10).toFloat()
        val magY = (Math.cos(factor * 0.7) * 8).toFloat()
        val magZ = (Math.sin(factor * 0.3) * 6).toFloat()
        addSensorData("magnetometer", magX, magY, magZ)
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stopVisualization()
        chartScope.cancel()
        chartView = null
        Log.d(TAG, "图表管理器已清理")
    }
}