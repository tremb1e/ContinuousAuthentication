package com.continuousauth.detection

/**
 * 异常触发器类型
 * 定义了不同类型的异常检测事件
 */
sealed class AnomalyTrigger {
    /**
     * 设备解锁事件
     * 当用户解锁设备时触发
     */
    object DeviceUnlocked : AnomalyTrigger()
    
    /**
     * 加速度计数据突变事件
     * 当加速度计检测到异常数据变化时触发
     * @param magnitude 当前检测到的加速度幅度
     * @param threshold 触发阈值
     * @param deviation 偏差程度
     */
    data class AccelerometerSpike(
        val magnitude: Float,
        val threshold: Float,
        val deviation: Float
    ) : AnomalyTrigger()
    
    /**
     * 敏感应用进入事件
     * 当检测到用户进入敏感应用时触发
     * @param packageName 敏感应用包名
     * @param appName 应用名称（如果可用）
     */
    data class SensitiveAppEntered(
        val packageName: String,
        val appName: String? = null
    ) : AnomalyTrigger()
    
    /**
     * 手动触发事件
     * 用户或系统手动触发快速模式
     */
    object MANUAL : AnomalyTrigger()
}

/**
 * 异常检测监听器
 * 用于接收异常检测事件的回调
 */
interface OnAnomalyListener {
    /**
     * 当检测到异常时调用
     * @param trigger 异常触发器，包含具体的异常类型和数据
     */
    fun onAnomalyDetected(trigger: AnomalyTrigger)
    
    /**
     * 当异常状态结束时调用（可选）
     * @param trigger 结束的异常触发器
     */
    fun onAnomalyCleared(trigger: AnomalyTrigger) {}
}

/**
 * 异常检测器接口
 * 定义了异常检测模块的基本功能
 */
interface AnomalyDetector {
    /**
     * 启动异常检测
     */
    suspend fun startDetection()
    
    /**
     * 停止异常检测
     */
    suspend fun stopDetection()
    
    /**
     * 设置异常监听器
     * @param listener 异常监听器
     */
    fun setOnAnomalyListener(listener: OnAnomalyListener?)
    
    /**
     * 更新检测策略
     * 允许服务器动态更新内部阈值和配置
     * @param config 新的检测配置
     */
    suspend fun updatePolicy(config: DetectionPolicy)
    
    /**
     * 处理传感器数据（用于加速度计突变检测）
     * @param x X轴加速度
     * @param y Y轴加速度
     * @param z Z轴加速度
     * @param timestamp 时间戳
     */
    fun processSensorData(x: Float, y: Float, z: Float, timestamp: Long)
    
    /**
     * 获取当前检测状态
     */
    fun isDetecting(): Boolean
}

/**
 * 检测策略配置
 * 包含异常检测的各种阈值和参数
 */
data class DetectionPolicy(
    // 加速度计突变检测参数
    val accelerometerSpikeThreshold: Float = 3.0f,        // 突变检测阈值（倍数）
    val accelerometerWindowSize: Int = 20,                // 滑动窗口大小
    val accelerometerCooldownMs: Long = 2000L,           // 冷却期（毫秒）
    
    // 敏感应用列表
    val sensitiveApps: Set<String> = emptySet(),         // 敏感应用包名列表
    val appCheckIntervalMs: Long = 1000L,                // 应用检查间隔（毫秒）
    
    // 设备解锁检测
    val deviceUnlockEnabled: Boolean = true,             // 是否启用设备解锁检测
    val deviceUnlockCooldownMs: Long = 5000L,           // 设备解锁冷却期
    
    // 全局设置
    val enabled: Boolean = true,                         // 是否启用异常检测
    val debugMode: Boolean = false                       // 调试模式
)