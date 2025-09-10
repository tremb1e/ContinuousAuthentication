package com.continuousauth.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuousauth.monitor.MemoryMonitor
import com.continuousauth.network.ConnectionStatus
import com.continuousauth.network.NetworkEnvironmentDetector
import com.continuousauth.network.NetworkState
import com.continuousauth.network.UploadManager
import com.continuousauth.observability.MetricsCollectorImpl
import com.continuousauth.observability.PerformanceMonitorImpl
import com.continuousauth.pool.SensorEventPool
import com.continuousauth.utils.UserIdManager
import com.continuousauth.network.ServerConnectionTester
import com.continuousauth.network.TlsSecurityManager
import com.continuousauth.network.TlsConfigInfo
import com.continuousauth.storage.FileQueueManager
import com.continuousauth.storage.QueueStats
import com.continuousauth.privacy.PrivacyManager
import com.continuousauth.privacy.ConsentState
import com.continuousauth.privacy.DeletionState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.KeyStore
import javax.inject.Inject

/**
 * 主界面ViewModel
 * 管理UI状态和业务逻辑
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadManager: UploadManager,
    private val networkEnvironmentDetector: NetworkEnvironmentDetector,
    private val metricsCollector: MetricsCollectorImpl,
    private val performanceMonitor: PerformanceMonitorImpl,
    private val memoryMonitor: MemoryMonitor,
    private val sensorEventPool: SensorEventPool,
    private val userIdManager: UserIdManager,
    private val serverConnectionTester: ServerConnectionTester,
    private val fileQueueManager: FileQueueManager,
    private val tlsSecurityManager: TlsSecurityManager,
    private val privacyManager: PrivacyManager
) : ViewModel() {
    
    companion object {
        private const val TAG = "MainViewModel"
    }
    
    // 采集状态
    private val _collectionStatus = MutableLiveData<String>()
    val collectionStatus: LiveData<String> = _collectionStatus
    
    // 采集运行状态
    private val _isCollectionRunning = MutableLiveData<Boolean>()
    val isCollectionRunning: LiveData<Boolean> = _isCollectionRunning
    
    // 加密上传状态 - true表示"加密上传中"，false表示"未加密上传"
    private val _isEncryptedUploading = MutableLiveData<Boolean>()
    val isEncryptedUploading: LiveData<Boolean> = _isEncryptedUploading
    
    // 传感器状态
    private val _sensorStatus = MutableLiveData<Map<String, Boolean>>()
    val sensorStatus: LiveData<Map<String, Boolean>> = _sensorStatus
    
    // 网络状态
    private val _networkState = MutableLiveData<NetworkState>()
    val networkState: LiveData<NetworkState> = _networkState
    
    // 连接状态
    private val _connectionStatus = MutableLiveData<ConnectionStatus>()
    val connectionStatus: LiveData<ConnectionStatus> = _connectionStatus
    
    // 传输统计
    private val _transmissionStats = MutableLiveData<TransmissionStats>()
    val transmissionStats: LiveData<TransmissionStats> = _transmissionStats
    
    // 调试模式启用状态
    private val _debugModeEnabled = MutableLiveData<Boolean>()
    val debugModeEnabled: LiveData<Boolean> = _debugModeEnabled
    
    // 可视化启用状态
    private val _visualizationEnabled = MutableLiveData<Boolean>()
    val visualizationEnabled: LiveData<Boolean> = _visualizationEnabled
    
    // 错误消息
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage
    
    // 调试信息
    private val _debugInfo = MutableLiveData<String>()
    val debugInfo: LiveData<String> = _debugInfo
    
    // 用户ID
    private val _userId = MutableLiveData<String>()
    val userId: LiveData<String> = _userId
    
    // 文件队列统计
    private val _fileQueueStats = MutableLiveData<QueueStats>()
    val fileQueueStats: LiveData<QueueStats> = _fileQueueStats
    
    // 会话信息
    private val _sessionId = MutableLiveData<String?>()
    val sessionId: LiveData<String?> = _sessionId
    
    private val _sessionStartTime = MutableLiveData<Long>()
    val sessionStartTime: LiveData<Long> = _sessionStartTime
    
    private val _sessionDuration = MutableLiveData<String>()
    val sessionDuration: LiveData<String> = _sessionDuration
    
    // 服务器测试结果
    private val _serverTestResult = MutableLiveData<String?>()
    val serverTestResult: LiveData<String?> = _serverTestResult
    
    // TLS配置信息
    private val _tlsConfigInfo = MutableLiveData<TlsConfigInfo?>()
    val tlsConfigInfo: LiveData<TlsConfigInfo?> = _tlsConfigInfo
    
    // 隐私与同意状态
    private val _consentState = MutableLiveData<ConsentState>()
    val consentState: LiveData<ConsentState> = _consentState
    
    private val _deletionState = MutableLiveData<DeletionState>()
    val deletionState: LiveData<DeletionState> = _deletionState
    
    // 上传策略状态
    private val _uploadPolicyWiFiOnly = MutableLiveData<Boolean>()
    val uploadPolicyWiFiOnly: LiveData<Boolean> = _uploadPolicyWiFiOnly
    
    init {
        // 初始化状态
        _collectionStatus.value = "STOPPED"
        _isCollectionRunning.value = false
        _isEncryptedUploading.value = false  // 初始状态为"未加密上传"
        _sensorStatus.value = mapOf(
            "accelerometer" to false,
            "gyroscope" to false,
            "magnetometer" to false
        )
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _transmissionStats.value = TransmissionStats()
        _debugModeEnabled.value = false
        _visualizationEnabled.value = false
        
        // 初始化用户ID
        _userId.value = userIdManager.getUserId()
        
        // 初始化上传策略设置
        initializeUploadPolicy()
        
        // 开始监控网络状态
        startNetworkMonitoring()
        
        // 启动性能监控
        startPerformanceMonitoring()
        
        // 启动内存监控
        startMemoryMonitoring()
        
        // 初始状态刷新
        refreshStatus()
        
        // 刷新TLS配置信息
        refreshTlsConfig()
        
        // 初始化隐私状态
        checkPrivacyConsent()
    }
    
    /**
     * 开始监控网络状态
     */
    private fun startNetworkMonitoring() {
        viewModelScope.launch {
            try {
                networkEnvironmentDetector.networkStateFlow.collect { networkState ->
                    _networkState.value = networkState
                    updateDebugInfo()
                    
                    // 网络状态变化时重新应用上传策略
                    val wifiOnly = _uploadPolicyWiFiOnly.value ?: false
                    if (wifiOnly) {
                        applyUploadPolicy(wifiOnly)
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "网络监控启动失败: ${e.message}"
            }
        }
    }
    
    /**
     * 启动性能监控
     */
    private fun startPerformanceMonitoring() {
        viewModelScope.launch {
            try {
                performanceMonitor.startMonitoring(5000L) // 每5秒采样一次
            } catch (e: Exception) {
                _errorMessage.value = "性能监控启动失败: ${e.message}"
            }
        }
    }
    
    /**
     * 启动内存监控
     */
    private fun startMemoryMonitoring() {
        viewModelScope.launch {
            try {
                memoryMonitor.startMonitoring()
                
                // 设置内存警告回调
                memoryMonitor.setOnMemoryWarning { memoryStatus ->
                    Log.w(TAG, "内存使用警告: ${(memoryStatus.usageRatio * 100).toInt()}%")
                    // 可以在这里触发一些优化措施
                }
                
                // 设置内存危险回调
                memoryMonitor.setOnMemoryCritical { memoryStatus ->
                    Log.e(TAG, "内存使用危险: ${(memoryStatus.usageRatio * 100).toInt()}%")
                    // 自动触发GC
                    memoryMonitor.suggestGC()
                }
            } catch (e: Exception) {
                _errorMessage.value = "内存监控启动失败: ${e.message}"
            }
        }
    }
    
    /**
     * 开始加密数据上传
     */
    fun startEncryptedUpload() {
        viewModelScope.launch {
            try {
                // 检查隐私协议是否已同意
                if (privacyManager.consentState.value != ConsentState.GRANTED) {
                    _errorMessage.value = "请先同意隐私协议才能开始数据采集"
                    Log.w(TAG, "用户尚未同意隐私协议，阻止数据采集启动")
                    return@launch
                }
                
                _collectionStatus.value = "STARTING"
                
                // 开始新的会话
                val sessionId = userIdManager.startNewSession()
                _sessionId.value = sessionId
                _sessionStartTime.value = userIdManager.getSessionStartTime()
                
                // 智能传输管理器已移除，使用窗口化批处理策略
                
                // TODO: 启动传感器采集
                // sensorCollector.startCollection()
                
                // TODO: 启动上传管理器
                // val success = uploadManager.start("server_endpoint")
                
                // 模拟启动成功
                val success = true
                
                if (success) {
                    _collectionStatus.value = "RUNNING"
                    _isCollectionRunning.value = true
                    _isEncryptedUploading.value = true  // 启动后进入"加密上传中"状态
                    updateSensorStatus(running = true)
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    
                    // 开始更新会话时长
                    startSessionDurationUpdate()
                } else {
                    _collectionStatus.value = "ERROR"
                    _errorMessage.value = "启动加密数据上传失败"
                    userIdManager.endSession()
                }
                
                updateDebugInfo()
                
            } catch (e: Exception) {
                _collectionStatus.value = "ERROR"
                _errorMessage.value = "启动加密数据上传异常: ${e.message}"
                userIdManager.endSession()
            }
        }
    }
    
    /**
     * 开始数据采集（保留兼容性）
     */
    fun startCollection() {
        startEncryptedUpload()
    }
    
    /**
     * 记录用户同意隐私协议
     */
    fun grantPrivacyConsent() {
        privacyManager.grantConsent()
        checkPrivacyConsent()
    }
    
    /**
     * 初始化上传策略设置
     */
    private fun initializeUploadPolicy() {
        val prefs = context.getSharedPreferences("upload_policy", Context.MODE_PRIVATE)
        val wifiOnly = prefs.getBoolean("wifi_only", false) // 默认不限制
        _uploadPolicyWiFiOnly.value = wifiOnly
        
        // 应用策略到上传管理器
        applyUploadPolicy(wifiOnly)
        
        Log.d(TAG, "上传策略初始化: ${if (wifiOnly) "仅WiFi" else "不限制"}")
    }
    
    /**
     * 设置上传策略
     */
    fun setUploadPolicyWiFiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            try {
                // 保存到SharedPreferences
                val prefs = context.getSharedPreferences("upload_policy", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("wifi_only", wifiOnly).apply()
                
                // 更新LiveData
                _uploadPolicyWiFiOnly.value = wifiOnly
                
                // 应用策略
                applyUploadPolicy(wifiOnly)
                
                Log.i(TAG, "上传策略已更新: ${if (wifiOnly) "仅WiFi" else "不限制"}")
                
            } catch (e: Exception) {
                Log.e(TAG, "设置上传策略失败", e)
                _errorMessage.value = "设置上传策略失败: ${e.message}"
            }
        }
    }
    
    /**
     * 获取当前上传策略
     */
    fun getUploadPolicyWiFiOnly(): Boolean {
        return _uploadPolicyWiFiOnly.value ?: false
    }
    
    /**
     * 应用上传策略到上传管理器
     */
    private fun applyUploadPolicy(wifiOnly: Boolean) {
        viewModelScope.launch {
            try {
                // 检查当前网络状态
                val currentNetworkState = _networkState.value ?: NetworkState.UNKNOWN
                
                // 如果设置为仅WiFi，且当前不是WiFi网络，暂停上传
                if (wifiOnly && !isWiFiNetwork(currentNetworkState)) {
                    // 如果正在上传，暂停上传
                    if (_isCollectionRunning.value == true) {
                        Log.i(TAG, "当前非WiFi网络，暂停数据上传")
                        // TODO: 调用uploadManager.pauseUpload()
                    }
                } else {
                    // 恢复上传（如果之前被暂停）
                    if (_isCollectionRunning.value == true) {
                        Log.i(TAG, "网络条件满足，恢复数据上传")
                        // TODO: 调用uploadManager.resumeUpload()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "应用上传策略失败", e)
            }
        }
    }
    
    /**
     * 判断是否为WiFi网络
     */
    private fun isWiFiNetwork(networkState: NetworkState): Boolean {
        return networkState == NetworkState.WIFI_EXCELLENT ||
               networkState == NetworkState.WIFI_GOOD ||
               networkState == NetworkState.WIFI_POOR
    }
    
    /**
     * 停止加密数据上传
     */
    fun stopEncryptedUpload() {
        viewModelScope.launch {
            try {
                _collectionStatus.value = "STOPPING"
                
                // 停止智能传输管理器
                // 停止智能传输管理器（已移除）
                
                // TODO: 停止传感器采集
                // sensorCollector.stopCollection()
                
                // TODO: 停止上传管理器
                // uploadManager.stop()
                
                // 结束会话
                userIdManager.endSession()
                _sessionId.value = null
                _sessionStartTime.value = 0
                _sessionDuration.value = "00:00"
                
                _collectionStatus.value = "STOPPED"
                _isCollectionRunning.value = false
                _isEncryptedUploading.value = false  // 停止后回到"未加密上传"状态
                updateSensorStatus(running = false)
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                
                updateDebugInfo()
                
            } catch (e: Exception) {
                _collectionStatus.value = "ERROR"
                _errorMessage.value = "停止加密数据上传异常: ${e.message}"
            }
        }
    }
    
    /**
     * 停止数据采集（保留兼容性）
     */
    fun stopCollection() {
        stopEncryptedUpload()
    }
    
    /**
     * 更新传感器状态
     */
    private fun updateSensorStatus(running: Boolean) {
        _sensorStatus.value = mapOf(
            "accelerometer" to running,
            "gyroscope" to running,
            "magnetometer" to running
        )
    }
    
    /**
     * 刷新状态信息
     */
    fun refreshStatus() {
        viewModelScope.launch {
            try {
                // 更新网络状态
                _networkState.value = networkEnvironmentDetector.getCurrentNetworkState()
                
                // 获取上传状态
                val uploadStatus = uploadManager.getUploadStatus()
                _connectionStatus.value = uploadStatus.connectionStatus
                
                // 更新文件队列统计
                uploadStatus.fileQueueStats?.let {
                    _fileQueueStats.value = it
                }
                
                // 更新传输统计
                updateTransmissionStats()
                
                // 更新调试信息
                updateDebugInfo()
                
            } catch (e: Exception) {
                _errorMessage.value = "状态刷新失败: ${e.message}"
            }
        }
    }
    
    /**
     * 刷新TLS配置信息
     */
    fun refreshTlsConfig() {
        viewModelScope.launch {
            try {
                val configInfo = tlsSecurityManager.getTlsConfigInfo()
                _tlsConfigInfo.value = configInfo
            } catch (e: Exception) {
                // 忽略错误，TLS信息只是展示用
            }
        }
    }
    
    /**
     * 更新传输统计
     */
    private fun updateTransmissionStats() {
        // TODO: 从实际模块获取统计数据
        val stats = TransmissionStats(
            isFastMode = false, // TODO: 从TransmissionController获取
            packetsSent = 0L,   // TODO: 从UploadManager获取
            packetsPending = 0,  // TODO: 从缓冲区获取
            lastAckLatency = null // TODO: 从ConnectionStats获取
        )
        _transmissionStats.value = stats
    }
    
    /**
     * 切换调试模式
     */
    fun toggleDebugMode() {
        val currentMode = _debugModeEnabled.value ?: false
        _debugModeEnabled.value = !currentMode
        
        if (!currentMode) {
            updateDebugInfo()
        }
    }
    
    /**
     * 切换可视化模式
     */
    fun toggleVisualization() {
        val currentMode = _visualizationEnabled.value ?: false
        _visualizationEnabled.value = !currentMode
    }
    
    /**
     * 启动可视化
     */
    fun startVisualization() {
        // TODO: 启动实时数据可视化
        // chartManager.startVisualization()
    }
    
    /**
     * 更新调试信息
     */
    private fun updateDebugInfo() {
        if (_debugModeEnabled.value != true) return
        
        val debugText = buildString {
            appendLine("=== 智能传输状态 ===")
            appendLine("管理器状态: ${if (_isCollectionRunning.value == true) "运行中" else "已停止"}")
            appendLine("传输策略: 1秒窗口批处理")
            appendLine("批处理间隔: 1000ms")
            appendLine()
            
            appendLine("=== gRPC 连接状态 ===")
            appendLine("连接端点: grpc://localhost:8080") // TODO: 从配置获取
            appendLine("连接状态: ${_connectionStatus.value}")
            val stats = _transmissionStats.value
            stats?.lastAckLatency?.let {
                appendLine("最近ACK延迟: ${it}ms")
            }
            appendLine()
            
            appendLine("=== 缓冲与传输统计 ===")
            val metricsSnapshot = metricsCollector.getSnapshot()
            appendLine("内存样本数: 0") // TODO: 从实际缓冲区获取
            appendLine("磁盘队列批次数: ${metricsSnapshot.counters[com.continuousauth.observability.MetricType.BATCHES_PROCESSED] ?: 0}")
            appendLine("已发送数据包: ${metricsSnapshot.counters[com.continuousauth.observability.MetricType.UPLOADS_SUCCESS] ?: 0}")
            appendLine("上传失败数: ${(metricsSnapshot.counters[com.continuousauth.observability.MetricType.UPLOADS_FAILED_NETWORK] ?: 0) + 
                (metricsSnapshot.counters[com.continuousauth.observability.MetricType.UPLOADS_FAILED_SERVER] ?: 0)}")
            appendLine("已丢弃数据包: 0") // TODO: 实现丢弃计数
            appendLine("上传成功率: ${"%.1f".format(metricsSnapshot.summary.currentUploadSuccessRate)}%")
            appendLine()
            
            appendLine("=== 传感器信息 ===")
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            
            accelerometer?.let {
                appendLine("加速度计: ${it.vendor}")
                appendLine("  最大采样率: ${1_000_000 / it.minDelay}Hz")
                appendLine("  FIFO最大事件数: ${it.fifoMaxEventCount}")
            }
            gyroscope?.let {
                appendLine("陀螺仪: ${it.vendor}")
                appendLine("  最大采样率: ${1_000_000 / it.minDelay}Hz")
                appendLine("  FIFO最大事件数: ${it.fifoMaxEventCount}")
            }
            magnetometer?.let {
                appendLine("磁力计: ${it.vendor}")
                appendLine("  最大采样率: ${1_000_000 / it.minDelay}Hz")
                appendLine("  FIFO最大事件数: ${it.fifoMaxEventCount}")
            }
            appendLine()
            
            appendLine("=== 设备与密钥库 ===")
            appendLine("设备型号: ${Build.MODEL}")
            appendLine("Android版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("制造商: ${Build.MANUFACTURER}")
            appendLine("产品: ${Build.PRODUCT}")
            
            try {
                val keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                appendLine("Keystore Provider: AndroidKeyStore")
                appendLine("支持StrongBox: ${Build.VERSION.SDK_INT >= Build.VERSION_CODES.P}")
                appendLine("密钥别名数量: ${keyStore.aliases().toList().size}")
            } catch (e: Exception) {
                appendLine("Keystore Provider: 获取失败")
                appendLine("支持StrongBox: 未知")
            }
            appendLine()
            
            appendLine("=== 异常检测策略 ===")
            appendLine("异常检测: 已禁用（规范中已移除）")
            appendLine()
            
            appendLine("=== 性能监控 ===")
            val performanceStats = performanceMonitor.getPerformanceStats(60000L)
            appendLine("采样数量: ${performanceStats.sampleCount}")
            appendLine("当前内存: ${performanceStats.memoryUsageAvg.toInt()}MB")
            appendLine("峰值内存: ${performanceStats.memoryUsagePeak}MB")
            appendLine("平均CPU: ${"%.1f".format(performanceStats.cpuUsageAvg)}%")
            appendLine("峰值CPU: ${"%.1f".format(performanceStats.cpuUsagePeak)}%")
            appendLine("堆利用率: ${"%.1f".format(performanceStats.heapUtilization)}%")
            appendLine()
            
            appendLine("=== 内存监控器 ===")
            val memoryStatus = memoryMonitor.getCurrentMemoryStatus()
            val memoryStats = memoryMonitor.getMonitoringStats()
            appendLine("内存使用: ${(memoryStatus.usedMemoryBytes / (1024 * 1024))}MB / ${(memoryStatus.maxMemoryBytes / (1024 * 1024))}MB")
            appendLine("内存利用率: ${"%.1f".format(memoryStatus.usageRatio * 100)}%")
            appendLine("状态: ${when {
                memoryStatus.isCritical -> "危险"
                memoryStatus.isWarning -> "警告"
                else -> "正常"
            }}")
            appendLine("监控样本数: ${memoryStats.samplesCount}")
            appendLine("平均使用率: ${"%.1f".format(memoryStats.averageUsageRatio * 100)}%")
            appendLine("峰值使用率: ${"%.1f".format(memoryStats.peakUsageRatio * 100)}%")
            appendLine("警告次数: ${memoryStats.warningCount}")
            appendLine("危险次数: ${memoryStats.criticalCount}")
            appendLine()
            
            appendLine("=== 对象池状态 ===")
            val poolStatus = sensorEventPool.getPoolStatus()
            appendLine("当前池大小: ${poolStatus.currentSize}/${poolStatus.maxSize}")
            appendLine("对象获取总数: ${poolStatus.totalAcquired}")
            appendLine("对象释放总数: ${poolStatus.totalReleased}")
            appendLine("新创建对象数: ${poolStatus.totalCreated}")
            appendLine("对象池命中率: ${"%.1f".format(poolStatus.hitRate * 100)}%")
            appendLine()
            
            appendLine("=== 网络环境 ===")
            appendLine("网络类型: ${_networkState.value}")
            val networkConfig = networkEnvironmentDetector.getTransmissionConfig()
            appendLine("最大并发上传: ${networkConfig.maxConcurrentUploads}")
            appendLine("上传超时: ${networkConfig.uploadTimeoutMs}ms")
            appendLine("重试延迟: ${networkConfig.retryDelayMs}ms")
            appendLine("最大重试次数: ${networkConfig.maxRetryAttempts}")
            appendLine()
            
            appendLine("=== 指标摘要 ===")
            appendLine("运行时长: ${metricsCollector.getUptime() / 1000}秒")
            appendLine("异常检测数: ${metricsSnapshot.counters[com.continuousauth.observability.MetricType.ANOMALIES_DETECTED] ?: 0}")
            appendLine("模式切换(快→慢): ${metricsSnapshot.counters[com.continuousauth.observability.MetricType.MODE_SWITCHES_TO_SLOW] ?: 0}")
            appendLine("模式切换(慢→快): ${metricsSnapshot.counters[com.continuousauth.observability.MetricType.MODE_SWITCHES_TO_FAST] ?: 0}")
            appendLine("快速模式总时间: ${(metricsSnapshot.counters[com.continuousauth.observability.MetricType.FAST_MODE_TOTAL_TIME] ?: 0) / 1000}秒")
        }
        
        _debugInfo.value = debugText
    }
    
    /**
     * 检查Usage Stats权限
     */
    suspend fun hasUsageStatsPermission(): Boolean {
        // TODO: 实现实际的权限检查
        return false
    }
    
    /**
     * 检查Usage Stats权限
     */
    fun checkUsageStatsPermission() {
        viewModelScope.launch {
            val hasPermission = hasUsageStatsPermission()
            // TODO: 更新权限状态
            updateDebugInfo()
        }
    }
    
    /**
     * 测试服务器连接
     */
    fun testServerConnection(serverIp: String, serverPort: String) {
        viewModelScope.launch {
            try {
                _serverTestResult.value = "正在测试服务器连接..."
                
                val port = serverPort.toIntOrNull() ?: 50051
                val result = serverConnectionTester.testServerConnection(
                    serverIp = serverIp,
                    serverPort = port,
                    testGrpc = true
                )
                
                _serverTestResult.value = serverConnectionTester.getTestResultDescription(result)
                
                // 根据测试结果更新连接状态
                if (result.isReachable) {
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                } else {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
                
                // 3秒后清除测试结果
                delay(3000)
                _serverTestResult.value = null
                
            } catch (e: Exception) {
                _serverTestResult.value = "测试失败: ${e.message}"
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                
                delay(3000)
                _serverTestResult.value = null
            }
        }
    }
    
    /**
     * 开始更新会话时长
     */
    private fun startSessionDurationUpdate() {
        viewModelScope.launch {
            while (_isEncryptedUploading.value == true) {
                _sessionDuration.value = userIdManager.getFormattedSessionDuration()
                delay(1000) // 每秒更新一次
            }
        }
    }
    
    /**
     * 清除错误消息
     */
    fun clearErrorMessage() {
        _errorMessage.value = null
    }
    
    /**
     * 清空文件队列
     */
    fun clearFileQueue() {
        viewModelScope.launch {
            try {
                fileQueueManager.clearQueue()
                // 刷新文件队列统计
                val stats = fileQueueManager.getQueueStatistics()
                _fileQueueStats.value = QueueStats(
                    totalPackets = stats.totalPackets,
                    pendingPackets = stats.pendingPackets,
                    uploadedPackets = stats.uploadedPackets,
                    corruptedPackets = stats.corruptedPackets,
                    totalSizeBytes = stats.totalSizeBytes
                )
                Log.i(TAG, "文件队列已清空")
            } catch (e: Exception) {
                Log.e(TAG, "清空文件队列失败", e)
                _errorMessage.value = "清空文件队列失败: ${e.message}"
            }
        }
    }
    
    // ==================== 隐私与合规功能 ====================
    
    /**
     * 检查隐私同意状态
     */
    fun checkPrivacyConsent() {
        privacyManager.checkConsentStatus()
        viewModelScope.launch {
            privacyManager.consentState.collect { state ->
                _consentState.value = state
                
                // 如果用户未同意或已撤回，停止采集
                if (state == ConsentState.NOT_GRANTED || state == ConsentState.WITHDRAWN) {
                    stopCollection()
                }
            }
        }
        
        viewModelScope.launch {
            privacyManager.deletionState.collect { state ->
                _deletionState.value = state
            }
        }
    }
    
    // Removed duplicate function - already defined at line 312
    
    /**
     * 撤回同意并删除数据
     * 符合 claude.md 第5节要求
     */
    fun withdrawConsentAndDeleteData() {
        viewModelScope.launch {
            try {
                // 先停止采集
                stopCollection()
                
                // 显示删除进度
                _collectionStatus.value = "正在删除数据..."
                
                // 执行撤回同意和数据删除
                val result = privacyManager.withdrawConsentAndDeleteData()
                
                if (result.isSuccess) {
                    _collectionStatus.value = "数据已删除"
                    _errorMessage.value = null
                    
                    // 清除会话信息
                    _sessionId.value = null
                    _sessionStartTime.value = 0
                    _sessionDuration.value = "00:00"
                    
                    Log.i(TAG, "用户撤回同意，所有数据已删除")
                } else {
                    _errorMessage.value = "数据删除失败: ${result.exceptionOrNull()?.message}"
                    Log.e(TAG, "撤回同意失败", result.exceptionOrNull())
                }
            } catch (e: Exception) {
                _errorMessage.value = "撤回同意失败: ${e.message}"
                Log.e(TAG, "撤回同意过程中发生错误", e)
            }
        }
    }
    
    /**
     * 获取数据保留期限
     */
    fun getDataRetentionDays(): Int {
        return privacyManager.getDataRetentionDays()
    }
    
    /**
     * 设置数据保留期限
     */
    fun setDataRetentionDays(days: Int) {
        privacyManager.setDataRetentionDays(days)
    }
    
    /**
     * 执行数据保留策略清理
     */
    fun performRetentionCleanup() {
        viewModelScope.launch {
            if (privacyManager.shouldPerformRetentionCleanup()) {
                privacyManager.performRetentionCleanup()
                Log.i(TAG, "执行数据保留策略清理")
            }
        }
    }
    
    /**
     * 重试待处理的删除请求
     */
    fun retryPendingDeletionRequests() {
        viewModelScope.launch {
            privacyManager.retryPendingDeletionRequests()
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // 清理资源
        networkEnvironmentDetector.cleanup()
        // 清理智能传输管理器（已移除）
        performanceMonitor.cleanup()
        memoryMonitor.cleanup()
        viewModelScope.launch {
            performanceMonitor.stopMonitoring()
            memoryMonitor.stopMonitoring()
        }
    }
}