package com.continuousauth.crypto

import kotlinx.coroutines.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 密钥轮换管理器
 * 使用ScheduledExecutorService安排每日定时任务，自动轮换密钥以提升前向安全性
 */
@Singleton
class KeyRotationManager @Inject constructor(
    private val cryptoBox: CryptoBox
) {
    
    companion object {
        private const val ROTATION_INTERVAL_HOURS = 24L
        private const val INITIAL_DELAY_MINUTES = 5L // 启动后5分钟进行首次检查
        private const val TAG = "KeyRotationManager"
    }
    
    private var scheduledExecutor: ScheduledExecutorService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isRunning = false
    
    /**
     * 启动自动密钥轮换
     * 每日凌晨自动执行密钥轮换
     */
    fun startAutoRotation() {
        if (isRunning) {
            android.util.Log.w(TAG, "密钥轮换已在运行中")
            return
        }
        
        scheduledExecutor = ScheduledThreadPoolExecutor(1).apply {
            // 设置线程工厂，使用守护线程
            threadFactory = java.util.concurrent.ThreadFactory { runnable ->
                Thread(runnable, "KeyRotation").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
        }
        
        // 安排定期任务：初始延迟5分钟，然后每24小时执行一次
        scheduledExecutor?.scheduleWithFixedDelay(
            { performRotationCheck() },
            INITIAL_DELAY_MINUTES,
            ROTATION_INTERVAL_HOURS * 60, // 转换为分钟
            TimeUnit.MINUTES
        )
        
        isRunning = true
        android.util.Log.i(TAG, "密钥自动轮换已启动 - 间隔: ${ROTATION_INTERVAL_HOURS}小时")
    }
    
    /**
     * 停止自动密钥轮换
     */
    fun stopAutoRotation() {
        if (!isRunning) {
            return
        }
        
        scheduledExecutor?.shutdown()
        try {
            if (scheduledExecutor?.awaitTermination(5, TimeUnit.SECONDS) == false) {
                scheduledExecutor?.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduledExecutor?.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        scheduledExecutor = null
        isRunning = false
        
        android.util.Log.i(TAG, "密钥自动轮换已停止")
    }
    
    /**
     * 手动触发密钥轮换
     */
    suspend fun rotateNow(): Boolean {
        return withContext(Dispatchers.IO) {
            performKeyRotation()
        }
    }
    
    /**
     * 获取轮换状态
     */
    fun getRotationStatus(): RotationStatus {
        val keyInfo = cryptoBox.getKeyInfo()
        return RotationStatus(
            isAutoRotationEnabled = isRunning,
            lastRotationTime = keyInfo.keyCreationTime,
            rotationCount = keyInfo.keyRotationCount,
            nextRotationTime = calculateNextRotationTime(keyInfo.keyCreationTime)
        )
    }
    
    /**
     * 执行轮换检查（由定时任务调用）
     */
    private fun performRotationCheck() {
        scope.launch {
            try {
                val keyInfo = cryptoBox.getKeyInfo()
                val currentTime = System.currentTimeMillis()
                val timeSinceLastRotation = currentTime - keyInfo.keyCreationTime
                
                // 如果距离上次轮换超过24小时，执行轮换
                if (timeSinceLastRotation >= TimeUnit.HOURS.toMillis(ROTATION_INTERVAL_HOURS)) {
                    android.util.Log.i(TAG, "触发定时密钥轮换")
                    performKeyRotation()
                } else {
                    android.util.Log.d(TAG, "密钥轮换检查完成 - 无需轮换")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "密钥轮换检查失败", e)
            }
        }
    }
    
    /**
     * 执行实际的密钥轮换
     */
    private suspend fun performKeyRotation(): Boolean {
        return try {
            android.util.Log.i(TAG, "开始执行密钥轮换")
            
            val success = cryptoBox.rotateSessionKey()
            
            if (success) {
                val keyInfo = cryptoBox.getKeyInfo()
                android.util.Log.i(TAG, "密钥轮换成功 - 轮换次数: ${keyInfo.keyRotationCount}")
                
                // 可以在这里添加轮换成功的回调或通知
                onRotationSuccess(keyInfo.keyRotationCount)
                
                true
            } else {
                android.util.Log.e(TAG, "密钥轮换失败")
                
                // 可以在这里添加轮换失败的回调或通知
                onRotationFailure("CryptoBox轮换失败")
                
                false
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "密钥轮换异常", e)
            onRotationFailure("轮换异常: ${e.message}")
            false
        }
    }
    
    /**
     * 计算下次轮换时间
     */
    private fun calculateNextRotationTime(lastRotationTime: Long): Long {
        return lastRotationTime + TimeUnit.HOURS.toMillis(ROTATION_INTERVAL_HOURS)
    }
    
    /**
     * 轮换成功回调
     */
    private fun onRotationSuccess(rotationCount: Long) {
        // 这里可以添加成功通知、日志记录或其他操作
        android.util.Log.i(TAG, "密钥轮换成功回调 - 总轮换次数: $rotationCount")
    }
    
    /**
     * 轮换失败回调
     */
    private fun onRotationFailure(reason: String) {
        // 这里可以添加失败通知、重试逻辑或其他操作
        android.util.Log.w(TAG, "密钥轮换失败回调 - 原因: $reason")
    }
}

/**
 * 轮换状态数据类
 */
data class RotationStatus(
    val isAutoRotationEnabled: Boolean,    // 是否启用自动轮换
    val lastRotationTime: Long,           // 最后轮换时间
    val rotationCount: Long,              // 轮换次数
    val nextRotationTime: Long            // 下次轮换时间
)