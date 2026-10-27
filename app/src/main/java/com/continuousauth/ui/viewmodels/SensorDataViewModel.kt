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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import android.os.SystemClock
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
        val timestamp: Long = 0L
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
    
    @Volatile private var isChartActive = false
    
    /**
     * 订阅服务提供的预览；采集生命周期只由前台服务管理。
     */
    fun startSensorCollection() {
        if (sensorCollectionJob?.isActive == true) return

        sensorCollectionJob = viewModelScope.launch(Dispatchers.Default) {
                var hasSample = false
                sensorCollector.getPreviewDataFlow().collect { sample ->
                    if (!hasSample) {
                        _sensorsActive.postValue(true)
                        hasSample = true
                    }
                    val sensorData = SensorData(
                        x = sample.x,
                        y = sample.y,
                        z = sample.z,
                        timestamp = System.currentTimeMillis() +
                            (sample.eventTimestampNs - SystemClock.elapsedRealtimeNanos()) / 1_000_000
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
        appDetectionJob = viewModelScope.launch {
            foregroundAppDetector.recentApps.map { apps ->
                val knownNames = _recentApps.value.associate { it.packageName to it.appName }
                apps.map { app ->
                    val name = knownNames[app.packageName] ?: runCatching {
                        foregroundAppDetector.getAppName(app.packageName)
                    }.getOrDefault(app.packageName)
                    RecentApp(app.packageName, name, app.timestamp)
                }
            }.flowOn(Dispatchers.IO).collect { apps -> _recentApps.value = apps }
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
        
        _sensorsActive.postValue(false)
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
