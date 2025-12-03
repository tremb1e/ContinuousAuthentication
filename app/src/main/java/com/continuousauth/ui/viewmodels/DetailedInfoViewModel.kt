package com.continuousauth.ui.viewmodels

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuousauth.monitor.SystemMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 详细信息页面的ViewModel
 * 使用SystemMonitor提供真实的系统监控数据
 */
@HiltViewModel
class DetailedInfoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val systemMonitor: SystemMonitor
) : ViewModel() {
    
    // 传输状态
    val transmissionStatus: StateFlow<SystemMonitor.TransmissionStatus> = 
        systemMonitor.transmissionStatus.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SystemMonitor.TransmissionStatus()
        )
    
    // gRPC状态
    val grpcStatus: StateFlow<SystemMonitor.GrpcConnectionStatus> = 
        systemMonitor.grpcStatus.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SystemMonitor.GrpcConnectionStatus()
        )
    
    // 缓冲区统计
    val bufferStats: StateFlow<SystemMonitor.BufferStatistics> = 
        systemMonitor.bufferStats.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SystemMonitor.BufferStatistics()
        )
    
    // 传感器信息
    val sensorInfo: StateFlow<Map<String, SystemMonitor.SensorDetailedInfo>> = 
        systemMonitor.sensorInfoMap.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )
    
    // 设备和密钥信息
    val deviceInfo: StateFlow<SystemMonitor.DeviceKeyInfo> = 
        systemMonitor.deviceKeyInfo.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SystemMonitor.DeviceKeyInfo()
        )
    
    // 服务器策略
    val serverPolicy: StateFlow<SystemMonitor.ServerPolicy> = 
        systemMonitor.serverPolicy.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SystemMonitor.ServerPolicy()
        )
    
    // 时间同步状态
    val timeSyncStatus: StateFlow<SystemMonitor.TimeSyncStatus> = 
        systemMonitor.timeSyncStatus.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SystemMonitor.TimeSyncStatus()
        )
    
    // 性能指标
    val performanceMetrics: StateFlow<SystemMonitor.PerformanceMetrics> = 
        systemMonitor.performanceMetrics.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SystemMonitor.PerformanceMetrics()
        )
    
    // 性能历史数据
    val cpuHistory: StateFlow<List<Float>> = 
        systemMonitor.cpuHistory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    val memoryHistory: StateFlow<List<Float>> = 
        systemMonitor.memoryHistory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    val latencyHistory: StateFlow<List<Long>> = 
        systemMonitor.latencyHistory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    // 加密状态
    val encryptionStatus: StateFlow<SystemMonitor.EncryptionStatus> = 
        systemMonitor.encryptionStatus.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SystemMonitor.EncryptionStatus()
        )
    
    init {
        // 启动系统监控
        systemMonitor.startMonitoring()
    }
    
    /**
     * 触发快速模式（规范中已移除）
     */
    suspend fun triggerFastMode() {
        // 快速模式已从规范中移除，此功能已禁用
        showToast("快速模式功能已移除")
    }
    
    /**
     * 清空本地队列
     */
    suspend fun clearLocalQueue() {
        try {
            systemMonitor.clearLocalQueue()
            showToast("本地队列已清空")
        } catch (e: Exception) {
            showToast("清空队列失败: ${e.message}")
        }
    }
    
    /**
     * 导出待处理数据
     */
    suspend fun exportPendingData() {
        try {
            val path = systemMonitor.exportPendingData()
            showToast("数据已导出到: $path")
        } catch (e: Exception) {
            showToast("导出数据失败: ${e.message}")
        }
    }
    
    /**
     * 强制密钥轮换
     */
    suspend fun forceKeyRotation() {
        try {
            systemMonitor.forceKeyRotation()
            showToast("密钥轮换成功")
        } catch (e: Exception) {
            showToast("密钥轮换失败: ${e.message}")
        }
    }
    
    /**
     * 更新服务器公钥
     */
    suspend fun updateServerPublicKey() {
        try {
            // 这里应该通过某种方式获取新的公钥数据
            // 暂时使用模拟数据
            val mockKeyData = "MOCK_PUBLIC_KEY_DATA".toByteArray()
            systemMonitor.updateServerPublicKey(mockKeyData)
            showToast("服务器公钥已更新")
        } catch (e: Exception) {
            showToast("更新公钥失败: ${e.message}")
        }
    }
    
    /**
     * 导出调试日志
     */
    suspend fun exportDebugLog() {
        try {
            val path = systemMonitor.exportDebugLog()
            showToast("调试日志已导出到: $path")
        } catch (e: Exception) {
            showToast("导出日志失败: ${e.message}")
        }
    }
    
    /**
     * 显示Toast消息
     */
    private fun showToast(message: String) {
        viewModelScope.launch {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // 停止监控
        systemMonitor.stopMonitoring()
    }
}
