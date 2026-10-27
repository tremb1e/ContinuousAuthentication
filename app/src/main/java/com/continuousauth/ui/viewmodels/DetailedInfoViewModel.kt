package com.continuousauth.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuousauth.monitor.SystemMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 详细信息页面的ViewModel
 * 使用SystemMonitor提供真实的系统监控数据
 */
@HiltViewModel
class DetailedInfoViewModel @Inject constructor(
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
}
