package com.continuousauth.ui.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuousauth.sensor.SensorCollector
import com.continuousauth.utils.ForegroundAppDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.Locale
import javax.inject.Inject
import com.continuousauth.model.SensorType

/**
 * 传感器数据页面的ViewModel
 */
@HiltViewModel
class SensorDataViewModel @Inject constructor(
    private val sensorCollector: SensorCollector,
    private val foregroundAppDetector: ForegroundAppDetector
) : ViewModel() {
    
    data class SensorData(
        val x: Float = 0f,
        val y: Float = 0f,
        val z: Float = 0f,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    data class RecentApp(
        val packageName: String,
        val appName: String,
        val timestamp: Long
    )
    
    // 传感器数据流（使用asStateFlow确保只读）
    private val _accelerometerData = MutableStateFlow(SensorData())
    val accelerometerData: StateFlow<SensorData> = _accelerometerData.asStateFlow()
    
    private val _gyroscopeData = MutableStateFlow(SensorData())
    val gyroscopeData: StateFlow<SensorData> = _gyroscopeData.asStateFlow()
    
    private val _magnetometerData = MutableStateFlow(SensorData())
    val magnetometerData: StateFlow<SensorData> = _magnetometerData.asStateFlow()
    
    // 最近使用的应用列表（最多10个）
    private val _recentApps = MutableStateFlow<List<RecentApp>>(emptyList())
    val recentApps: StateFlow<List<RecentApp>> = _recentApps.asStateFlow()
    
    // 传感器状态
    private val _sensorsActive = MutableLiveData(false)
    val sensorsActive: LiveData<Boolean> = _sensorsActive
    
    // 任务管理
    private var sensorCollectionJob: Job? = null
    private var appDetectionJob: Job? = null
    
    // 图表数据缓存（用于图表显示）
    private val _chartAccelerometerData = MutableStateFlow(SensorData())
    val chartAccelerometerData: StateFlow<SensorData> = _chartAccelerometerData.asStateFlow()
    
    private val _chartGyroscopeData = MutableStateFlow(SensorData())
    val chartGyroscopeData: StateFlow<SensorData> = _chartGyroscopeData.asStateFlow()
    
    private val _chartMagnetometerData = MutableStateFlow(SensorData())
    val chartMagnetometerData: StateFlow<SensorData> = _chartMagnetometerData.asStateFlow()
    
    private var isChartActive = false
    
    /**
     * 开始收集传感器数据
     */
    fun startSensorCollection() {
        // 取消之前的任务
        stopSensorCollection()
        
        viewModelScope.launch {
            // 开始传感器数据收集
            sensorCollector.startCollection()
            _sensorsActive.value = true
            
            // 订阅传感器数据流（使用update确保线程安全）
            sensorCollectionJob = launch {
                sensorCollector.getSensorDataFlow().collect { sample ->
                    val sensorData = SensorData(
                        x = sample.x,
                        y = sample.y,
                        z = sample.z,
                        timestamp = System.currentTimeMillis() // 使用当前系统时间作为显示时间戳
                    )
                    
                    when (sample.type) {
                        SensorType.ACCELEROMETER -> {
                            _accelerometerData.update { sensorData }
                            if (isChartActive) {
                                _chartAccelerometerData.update { sensorData }
                            }
                        }
                        SensorType.GYROSCOPE -> {
                            _gyroscopeData.update { sensorData }
                            if (isChartActive) {
                                _chartGyroscopeData.update { sensorData }
                            }
                        }
                        SensorType.MAGNETOMETER -> {
                            _magnetometerData.update { sensorData }
                            if (isChartActive) {
                                _chartMagnetometerData.update { sensorData }
                            }
                        }
                    }
                }
            }
            
            // 定期获取前台应用（优化性能）
            appDetectionJob = launch {
                val recentAppsList = mutableListOf<RecentApp>()
                while (_sensorsActive.value == true) {
                    try {
                        val currentApp = foregroundAppDetector.getCurrentForegroundApp()
                        if (currentApp.isNotEmpty()) {
                            // 获取应用名称（从包名中提取）
                            val appName = getAppNameFromPackage(currentApp)
                            val app = RecentApp(
                                packageName = currentApp,
                                appName = appName,
                                timestamp = System.currentTimeMillis()
                            )
                            
                            // 如果是新应用或与最后一个不同，添加到列表
                            if (recentAppsList.isEmpty() || recentAppsList.last().packageName != currentApp) {
                                recentAppsList.add(app)
                                // 只保留最近10个
                                if (recentAppsList.size > 10) {
                                    recentAppsList.removeAt(0)
                                }
                                _recentApps.update { recentAppsList.toList() }
                            }
                        }
                    } catch (e: Exception) {
                        // 忽略错误，继续监测
                    }
                    delay(1000) // 每秒检查一次
                }
            }
        }
    }
    
    /**
     * 停止传感器数据收集
     */
    fun stopSensorCollection() {
        // 取消所有任务
        sensorCollectionJob?.cancel()
        appDetectionJob?.cancel()
        sensorCollectionJob = null
        appDetectionJob = null
        
        viewModelScope.launch {
            sensorCollector.stopCollection()
            _sensorsActive.value = false
        }
    }
    
    /**
     * 从包名获取应用名称
     */
    private fun getAppNameFromPackage(packageName: String): String {
        return when {
            packageName.contains("chrome") -> "Chrome"
            packageName.contains("whatsapp") -> "WhatsApp"
            packageName.contains("spotify") -> "Spotify"
            packageName.contains("instagram") -> "Instagram"
            packageName.contains("youtube") -> "YouTube"
            packageName.contains("facebook") -> "Facebook"
            packageName.contains("twitter") -> "Twitter"
            packageName.contains("telegram") -> "Telegram"
            packageName.contains("gmail") -> "Gmail"
            packageName.contains("maps") -> "Maps"
            packageName.contains("camera") -> "Camera"
            packageName.contains("gallery") -> "Gallery"
            packageName.contains("settings") -> "Settings"
            packageName.contains("phone") -> "Phone"
            packageName.contains("messages") -> "Messages"
            packageName.contains("calculator") -> "Calculator"
            packageName.contains("calendar") -> "Calendar"
            packageName.contains("clock") -> "Clock"
            else -> packageName.split(".").lastOrNull()?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } ?: packageName
        }
    }
    
    /**
     * 重置图表数据（当图表重新打开时调用）
     */
    fun resetChartData() {
        isChartActive = true
        _chartAccelerometerData.value = SensorData()
        _chartGyroscopeData.value = SensorData()
        _chartMagnetometerData.value = SensorData()
    }
    
    /**
     * 停止图表数据更新（当图表关闭时调用）
     */
    fun stopChartData() {
        isChartActive = false
    }
    
    override fun onCleared() {
        super.onCleared()
        stopSensorCollection()
    }
}