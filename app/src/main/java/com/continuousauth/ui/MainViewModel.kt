package com.continuousauth.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuousauth.core.SmartTransmissionManager
import com.continuousauth.monitor.MemoryMonitor
import com.continuousauth.monitor.SystemMonitor
import com.continuousauth.network.ConnectionStatus
import com.continuousauth.network.NetworkEnvironmentDetector
import com.continuousauth.network.NetworkState
import com.continuousauth.network.ServerEndpointNormalizer
import com.continuousauth.network.TransportMode
import com.continuousauth.network.UploadManager
import com.continuousauth.network.UploadStatus
import com.continuousauth.observability.MetricsCollectorImpl
import com.continuousauth.observability.PerformanceMonitorImpl
import com.continuousauth.pool.SensorEventPool
import com.continuousauth.utils.SessionManager
import com.continuousauth.crypto.EnvelopeCryptoBox
import com.continuousauth.network.TlsSecurityManager
import com.continuousauth.network.TlsConfigInfo
import com.continuousauth.storage.FileQueueManager
import com.continuousauth.storage.QueueStats
import com.continuousauth.privacy.PrivacyManager
import com.continuousauth.privacy.ConsentState
import com.continuousauth.privacy.DeletionState
import com.continuousauth.service.DataCollectionService
import com.continuousauth.auth.AuthHistoryRepository
import com.continuousauth.auth.AuthenticationManager
import com.continuousauth.settings.AppSettingsRepository
import com.continuousauth.settings.ProtectionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.security.KeyStore
import javax.inject.Inject
import androidx.core.app.NotificationManagerCompat
import android.os.Process
import com.continuousauth.utils.Constant.UPLOAD_POLICY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContinuousAuthUiState(
    val modelVersion: String = "",
    val lastScore: Float = 0f,
    val lastThreshold: Float = 0f,
    val lastWindowId: Long = 0L,
    val scoreValid: Boolean = false,
    val lastDecision: AuthDecision = AuthDecision.UNKNOWN,
    val lastDecisionMessage: String = "",
    val lastUpdateTime: Long = 0L,
    val serverLatencyMs: Long? = null,
    val serverTimestampMs: Long? = null,
    val authActive: Boolean = false,
    val authMessage: String = "",
    val windowSizeSec: Float = 0f,
    val decisionTimeSec: Float = 0f,
    val authFailure: Boolean = false,
    val isStarting: Boolean = false,
    val authAnalysis: AuthAnalysisState = AuthAnalysisState()
)

enum class AuthDecision { NORMAL, ABNORMAL, UNKNOWN }

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
    private val sessionManager: SessionManager,
    private val fileQueueManager: FileQueueManager,
    private val tlsSecurityManager: TlsSecurityManager,
    private val privacyManager: PrivacyManager,
    private val systemMonitor: SystemMonitor,
    private val smartTransmissionManager: SmartTransmissionManager,
    private val envelopeCryptoBox: EnvelopeCryptoBox,
    private val settingsRepository: AppSettingsRepository,
    private val authHistoryRepository: AuthHistoryRepository,
    private val authenticationManager: AuthenticationManager
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
    
    private val _uploadIdentity = MutableLiveData<String>()
    val uploadIdentity: LiveData<String> = _uploadIdentity
    private val _deviceIdHash = MutableLiveData<String>()
    val deviceIdHash: LiveData<String> = _deviceIdHash
    
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

    // 上传统计更新任务
    private var debugInfoJob: Job? = null
    private var uploadStatsJob: Job? = null
    private var sessionDurationJob: Job? = null
    private var authAnalysisTimerJob: Job? = null
    private var authStartJob: Job? = null
    private var consentObservationJob: Job? = null
    private var deletionObservationJob: Job? = null
    private var isDeletingData = false

    // 服务器测试结果
    private val _serverTestResult = MutableLiveData<String?>()
    val serverTestResult: LiveData<String?> = _serverTestResult

    // server config persistence
    private val serverPrefs by lazy {
        context.getSharedPreferences("server_config", Context.MODE_PRIVATE)
    }

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
    private val _smartTransmissionEnabled = MutableLiveData<Boolean>()
    val smartTransmissionEnabled: LiveData<Boolean> = _smartTransmissionEnabled

    // 传输状态
    val transmissionStatus: StateFlow<SystemMonitor.TransmissionStatus> =
        systemMonitor.transmissionStatus.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SystemMonitor.TransmissionStatus()
        )
    // 时间同步状态
    val timeSyncStatus: StateFlow<SystemMonitor.TimeSyncStatus> =
        systemMonitor.timeSyncStatus.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SystemMonitor.TimeSyncStatus()
        )
    private val _authUiState = MutableStateFlow(ContinuousAuthUiState())
    val authUiState: StateFlow<ContinuousAuthUiState> = _authUiState
    val protectionMode = settingsRepository.protectionMode
    val sensorCollectionSettings = settingsRepository.sensorSettings
    val authHistory = authHistoryRepository.entries
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
        
        initializeUploadIdentity()
        
        // 初始化上传策略设置
        initializeUploadPolicy()
        
        // 开始监控网络状态
        startNetworkMonitoring()
        
        // 启动性能监控
        startPerformanceMonitoring()
        
        // 启动内存监控
        startMemoryMonitoring()

        // 启动系统监控，确保状态流可用
        systemMonitor.startMonitoring()

        // 初始状态刷新
        refreshStatus()
        
        // 刷新TLS配置信息
        refreshTlsConfig()
        
        // 初始化隐私状态
        checkPrivacyConsent()
        initializeAuthUiState()

        // 监听服务端认证结果
        startAuthResultCollection()
        viewModelScope.launch {
            sensorCollectionSettings.collect {
                updateSensorStatus(smartTransmissionManager.collectionState.value.status == SmartTransmissionManager.CollectionStatus.RUNNING)
            }
        }
        observeCollectionState()
        viewModelScope.launch {
            fileQueueManager.uploadProgress.collect {
                refreshUploadStats()
            }
        }
    }

    private fun observeCollectionState() {
        viewModelScope.launch {
            smartTransmissionManager.collectionState.collect { state ->
                val running = state.status == SmartTransmissionManager.CollectionStatus.RUNNING
                val active = running || state.status == SmartTransmissionManager.CollectionStatus.PAUSED
                _collectionStatus.value = state.status.name
                _isCollectionRunning.value = active
                _isEncryptedUploading.value = active
                updateSensorStatus(running)
                if (active) {
                    _authUiState.value = _authUiState.value.copy(authActive = authenticationManager.active.value)
                    _sessionId.value = sessionManager.getCurrentSessionId()
                    _sessionStartTime.value = sessionManager.getSessionStartTime()
                    startSessionDurationUpdate()
                    startUploadStatsUpdates()
                } else if (state.status == SmartTransmissionManager.CollectionStatus.STOPPED ||
                    state.status == SmartTransmissionManager.CollectionStatus.ERROR) {
                    uploadStatsJob?.cancel()
                    sessionDurationJob?.cancel()
                    _sessionId.value = null
                    _sessionStartTime.value = 0L
                    _sessionDuration.value = "00:00"
                    _authUiState.value = _authUiState.value.copy(authActive = false)
                    refreshUploadStats()
                }
                state.errorMessage?.let { _errorMessage.value = it }
                _connectionStatus.value = uploadManager.getUploadStatus().connectionStatus
            }
        }
    }

    private fun startAuthResultCollection() {
        viewModelScope.launch {
            authenticationManager.latestResult.collect { result ->
                if (result == null) return@collect
                try {
                    val latency = System.currentTimeMillis() - result.serverTimestampMs
                    updateAuthResult(
                        score = result.normalizedScore,
                        threshold = result.threshold,
                        windowId = result.windowId,
                        modelVersion = result.modelVersion,
                        latencyMs = latency.coerceAtLeast(0),
                        accepted = result.accept,
                        interrupt = result.interrupt,
                        windowSizeSec = result.windowSizeSec,
                        serverTimestampMs = result.serverTimestampMs,
                        message = result.message
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "处理认证结果异常", error)
                }
            }
        }
        viewModelScope.launch {
            authenticationManager.active.collect { active ->
                _authUiState.value = _authUiState.value.copy(
                    authActive = active,
                    authFailure = active && _authUiState.value.authFailure
                )
            }
        }
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
                }
            } catch (e: Exception) {
                _errorMessage.value = "内存监控启动失败: ${e.message}"
            }
        }
    }
    
    data class ServerConfig(val host: String, val port: Int, val scheme: String)

    /**
     * 读取已保存的服务器配置，若没有则返回默认值。
     */
    fun getServerConfig(): ServerConfig {
        val savedPort = if (serverPrefs.contains("server_port")) {
            serverPrefs.getInt("server_port", 0)
        } else {
            null
        }
        val normalized = ServerEndpointNormalizer.normalize(
            addressInput = serverPrefs.getString("server_ip", null),
            portInput = savedPort,
            schemeInput = serverPrefs.getString("server_scheme", null)
        )
        return ServerConfig(
            host = normalized.host,
            port = normalized.port,
            scheme = normalized.scheme
        )
    }

    /**
     * 解析并保存服务器配置，支持输入带协议或端口的地址。
     */
    fun saveServerConfig(ipInput: String, portInput: Int?) {
        // 允许输入 http://host:port 或 https://host:port
        val trimmed = ipInput.trim()
        val normalized = ServerEndpointNormalizer.normalize(trimmed, portInput)
        val previous = getServerConfig()

        serverPrefs.edit()
            .putString("server_ip", normalized.host)
            .putInt("server_port", normalized.port)
            .putString("server_scheme", normalized.scheme)
            .apply()
        if (previous != ServerConfig(normalized.host, normalized.port, normalized.scheme)) {
            val wasAuthenticating = authenticationManager.active.value || authStartJob?.isActive == true
            authStartJob?.cancel()
            authenticationManager.stopAuthentication()
            if (wasAuthenticating) {
                _authUiState.value = ContinuousAuthUiState(authMessage = "服务器设置已更新，请重新开始认证")
            }
            uploadManager.updateServerEndpoint(normalized.endpoint)
        }
    }

    /**
     * 开始加密数据上传
     */
    fun startEncryptedUpload() {
        viewModelScope.launch {
            try {
                if (isDeletingData) return@launch
                if (_isEncryptedUploading.value == true || _collectionStatus.value == "STARTING" ||
                    _collectionStatus.value == "STOPPING") {
                    Log.w(TAG, "加密上传已在运行中")
                    return@launch
                }

                if (!sensorCollectionSettings.value.anyEnabled) {
                    _errorMessage.value = "请先到设置允许至少一种传感器采集"
                    return@launch
                }
                // 检查隐私协议是否已同意
                if (privacyManager.consentState.value != ConsentState.GRANTED) {
                    _errorMessage.value = "请先同意隐私协议才能开始数据采集"
                    Log.w(TAG, "用户尚未同意隐私协议，阻止数据采集启动")
                    return@launch
                }
                
                _collectionStatus.value = "STARTING"
                _connectionStatus.value = ConnectionStatus.CONNECTING

                // 读取服务器配置，确保上传器使用用户输入的地址
                val serverConfig = getServerConfig()
                if (serverConfig.host.isBlank() || serverConfig.port <= 0) {
                    _collectionStatus.value = "ERROR"
                    _errorMessage.value = "服务器配置无效，请检查IP和端口"
                    return@launch
                }

                // 确保前台服务运行，避免切后台被系统限制网络/传感器
                startForegroundCollectionService()
                
            } catch (e: Exception) {
                _collectionStatus.value = "ERROR"
                Log.e(TAG, "启动数据采集失败", e)
                _errorMessage.value = "暂时无法开始数据采集，请检查应用权限和设置后重试"
            }
        }
    }

    /**
     * 开始认证会话
     */
    fun startAuthentication() {
        if (authStartJob?.isActive == true) return
        if (authenticationManager.active.value) {
            val status = smartTransmissionManager.collectionState.value.status
            if (status != SmartTransmissionManager.CollectionStatus.STOPPED &&
                status != SmartTransmissionManager.CollectionStatus.ERROR) return
            // A force-stopped process can leave a persisted auth session without a service.
            // An explicit new start must recover instead of silently ignoring the button.
            authenticationManager.stopAuthentication()
            sessionManager.endSession()
        }
        if (privacyManager.consentState.value != ConsentState.GRANTED) {
            _errorMessage.value = "请先到设置阅读并同意数据使用说明"
            return
        }
        if (protectionMode.value == ProtectionMode.OFF) {
            _errorMessage.value = "防护已关闭，请先到设置选择仅监测或即时警示"
            return
        }
        if (!sensorCollectionSettings.value.anyEnabled) {
            _errorMessage.value = "请先到设置允许至少一种传感器采集，再开始认证"
            return
        }
        if (isDeletingData) return
        _authUiState.value = _authUiState.value.copy(isStarting = true, authMessage = "正在准备认证，请稍候")
        authStartJob = viewModelScope.launch {
            try {
                val previousConnectionError = uploadManager.getUploadStatus().connectionStats.lastErrorTimestamp
                val initialCollectionState = smartTransmissionManager.collectionState.value
                val startsNewCollection = _isEncryptedUploading.value != true && _collectionStatus.value != "STARTING"
                if (startsNewCollection) {
                    _collectionStatus.value = "STARTING"
                    startForegroundCollectionService()
                } else if (smartTransmissionManager.collectionState.value.status == SmartTransmissionManager.CollectionStatus.PAUSED) {
                    context.startService(Intent(context, DataCollectionService::class.java)
                        .setAction(DataCollectionService.ACTION_RESUME_COLLECTION))
                }
                withTimeout(30_000L) {
                    val state = smartTransmissionManager.collectionState
                        .dropWhile { startsNewCollection && it == initialCollectionState }
                        .first {
                            it.status == SmartTransmissionManager.CollectionStatus.RUNNING ||
                                it.status == SmartTransmissionManager.CollectionStatus.PAUSED ||
                                it.status == SmartTransmissionManager.CollectionStatus.ERROR
                        }
                    check(state.status != SmartTransmissionManager.CollectionStatus.ERROR) {
                        "数据采集未能启动，请检查设置后重试"
                    }
                    while (uploadManager.getUploadStatus().connectionStatus != ConnectionStatus.CONNECTED) {
                        val status = uploadManager.getUploadStatus()
                        if (status.connectionStatus == ConnectionStatus.ERROR &&
                            status.connectionStats.lastErrorTimestamp != previousConnectionError) {
                            throw AuthenticationConnectionException(status.connectionStats.lastErrorMessage
                                ?: "暂时无法连接认证服务器，请稍后重试")
                        }
                        delay(250)
                    }
                }
                if (protectionMode.value == ProtectionMode.OFF || privacyManager.consentState.value != ConsentState.GRANTED) return@launch
                check(envelopeCryptoBox.initialize()) { "无法准备安全存储" }
                val deviceIdHash = envelopeCryptoBox.getDeviceIdHash()
                val currentSession = sessionManager.getCurrentSessionId()
                check(!currentSession.isNullOrBlank()) { "数据采集尚未准备好，请稍后再试" }
                val response = authenticationManager.startAuthentication(deviceIdHash, currentSession)
                if (response == null) {
                    showAuthenticationStartError(uploadManager.getUploadStatus().connectionStats.lastErrorMessage
                        ?: "暂时无法开始认证，请稍后重试")
                    return@launch
                }
                if (!response.accepted) {
                    showAuthenticationStartError(formatAuthStartMessage(response.message))
                    return@launch
                }
                _sessionId.value = currentSession
                _authUiState.value = ContinuousAuthUiState(
                    modelVersion = response.modelVersion,
                    authActive = true,
                    authMessage = "认证已开始，正常使用手机即可",
                    windowSizeSec = response.windowSizeSec,
                    decisionTimeSec = response.decisionTimeSec,
                    authAnalysis = _authUiState.value.authAnalysis
                )
            } catch (e: AuthenticationConnectionException) {
                showAuthenticationStartError(e.message ?: "暂时无法连接认证服务器，请稍后重试")
            } catch (e: TimeoutCancellationException) {
                showAuthenticationStartError(when {
                    getSmartTransmissionEnabled() && !smartTransmissionManager.smartUploadWindowOpen.value ->
                        "当前智能传输正在等待允许上传的时段。请在设置中关闭智能传输后重试，以便及时获得认证结果"
                    getUploadPolicyWiFiOnly() ->
                        "尚未连上服务器。你已选择仅 Wi-Fi 上传，请连接 Wi-Fi 或检查服务器设置后重试"
                    else -> "尚未连上服务器，请检查网络和服务器设置后重试。采集的数据会暂存在本机"
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "开始认证失败", e)
                showAuthenticationStartError("认证暂时无法开始，请检查设置后再试")
            } finally {
                _authUiState.value = _authUiState.value.copy(isStarting = false)
            }
        }
    }

    private class AuthenticationConnectionException(message: String) : Exception(message)

    private fun showAuthenticationStartError(message: String) {
        _errorMessage.value = message
        _authUiState.value = _authUiState.value.copy(authActive = false, authMessage = message, authFailure = false)
    }

    fun stopAuthentication() {
        val needsNewSession = authenticationManager.active.value || authStartJob?.isActive == true
        authStartJob?.cancel()
        authenticationManager.stopAuthentication()
        _authUiState.value = ContinuousAuthUiState(
            authMessage = "认证已停止，数据采集仍按你的设置运行",
            authAnalysis = _authUiState.value.authAnalysis
        )
        if (needsNewSession && (_isCollectionRunning.value == true || _collectionStatus.value == "STARTING")) {
            try {
                context.startService(Intent(context, DataCollectionService::class.java)
                    .setAction(DataCollectionService.ACTION_RESET_AUTHENTICATION))
            } catch (e: Exception) {
                Log.e(TAG, "结束服务端认证会话失败", e)
                stopEncryptedUpload()
            }
        } else if (needsNewSession) {
            sessionManager.endSession()
        }
    }

    fun setProtectionMode(mode: ProtectionMode) {
        if (mode == ProtectionMode.OFF) stopAuthentication()
        settingsRepository.setProtectionMode(mode)
    }

    fun setSensorCollectionEnabled(sensorKey: String, enabled: Boolean) {
        settingsRepository.setSensorEnabled(sensorKey, enabled)
        if (!sensorCollectionSettings.value.anyEnabled &&
            (_isCollectionRunning.value == true || _collectionStatus.value == "STARTING")) {
            stopEncryptedUpload()
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
     * 初始化持续认证UI状态
     */
    private fun initializeAuthUiState() {
        _authUiState.value = _authUiState.value.copy(
            modelVersion = "",
            lastDecision = AuthDecision.UNKNOWN,
            lastDecisionMessage = "",
            lastScore = 0f,
            lastThreshold = 0f,
            lastWindowId = 0L,
            lastUpdateTime = 0L,
            serverLatencyMs = null,
            serverTimestampMs = null,
            windowSizeSec = 0f,
            decisionTimeSec = 0f,
            authFailure = false,
            scoreValid = false
        )
    }

    /**
     * 初始化上传标识（HMAC，服务端路径使用）
     */
    private fun initializeUploadIdentity() {
        viewModelScope.launch {
            try {
                envelopeCryptoBox.initialize()
                val deviceHash = envelopeCryptoBox.getDeviceIdHash()
                _uploadIdentity.postValue(deviceHash)
                _deviceIdHash.postValue(deviceHash)
            } catch (e: Exception) {
                Log.w(TAG, "初始化上传标识失败: ${e.message}")
            }
        }
    }

    /**
     * 更新服务端推理结果
     */
    fun updateAuthResult(
        score: Float,
        threshold: Float,
        windowId: Long,
        modelVersion: String? = null,
        latencyMs: Long? = null,
        accepted: Boolean,
        interrupt: Boolean = false,
        windowSizeSec: Float? = null,
        serverTimestampMs: Long? = null,
        message: String? = null
    ) {
        if (protectionMode.value == ProtectionMode.OFF || !authenticationManager.active.value) return
        val isValidScore = score.isFinite()
        val clampedScore = if (isValidScore) score.coerceIn(0f, 1f) else 0f
        val isValidThreshold = threshold.isFinite()
        val clampedThreshold = if (isValidThreshold) threshold.coerceIn(0f, 1f) else 0f
        val current = _authUiState.value
        val acceptedDecision = accepted && !interrupt
        val decision = if (acceptedDecision) AuthDecision.NORMAL else AuthDecision.ABNORMAL
        val now = System.currentTimeMillis()
        val decisionMessage = message ?: current.lastDecisionMessage
        _authUiState.value = current.copy(
            lastScore = clampedScore,
            lastThreshold = clampedThreshold,
            lastWindowId = windowId,
            scoreValid = isValidScore,
            lastDecision = decision,
            lastDecisionMessage = decisionMessage,
            lastUpdateTime = now,
            modelVersion = modelVersion ?: current.modelVersion,
            serverLatencyMs = latencyMs ?: current.serverLatencyMs,
            serverTimestampMs = serverTimestampMs ?: current.serverTimestampMs,
            windowSizeSec = windowSizeSec ?: current.windowSizeSec,
            authFailure = !acceptedDecision,
            authActive = true,
            authAnalysis = current.authAnalysis.recordResult(acceptedDecision, now)
        )
    }

    fun startAuthAnalysis() {
        val now = System.currentTimeMillis()
        authAnalysisTimerJob?.cancel()
        _authUiState.value = _authUiState.value.copy(
            authAnalysis = _authUiState.value.authAnalysis.start(now)
        )
        startAuthAnalysisTimer()
    }

    fun stopAuthAnalysis() {
        authAnalysisTimerJob?.cancel()
        val now = System.currentTimeMillis()
        _authUiState.value = _authUiState.value.copy(
            authAnalysis = _authUiState.value.authAnalysis.stop(now)
        )
    }

    private fun startAuthAnalysisTimer() {
        authAnalysisTimerJob = viewModelScope.launch {
            while (_authUiState.value.authAnalysis.isRunning) {
                val now = System.currentTimeMillis()
                val current = _authUiState.value
                _authUiState.value = current.copy(
                    authAnalysis = current.authAnalysis.tick(now)
                )
                delay(1000L)
            }
        }
    }

    private fun formatAuthStartMessage(rawMessage: String?): String {
        val message = rawMessage?.trim().orEmpty()
        if (message.isBlank()) return "还在了解你的使用习惯，请正常使用手机后再试"

        val dataMatch = Regex("data_insufficient:\\s*([0-9.]+)MB/([0-9.]+)MB", RegexOption.IGNORE_CASE)
            .find(message)
        if (dataMatch != null) {
            val uploaded = dataMatch.groupValues[1]
            val required = dataMatch.groupValues[2]
            return "还在了解你的使用习惯（已积累 ${uploaded}MB / 需要 ${required}MB），请继续正常使用手机后再试"
        }

        return when {
            message.startsWith("training_in_progress") -> "正在学习你的使用习惯，请稍后再试"
            message.startsWith("training_failed") -> "暂时无法完成使用习惯分析，请稍后重试或联系服务器管理者"
            message.startsWith("model_not_ready") || message.startsWith("data_insufficient") ->
                "还在了解你的使用习惯，请继续正常使用手机后再试"
            else -> "服务暂时未准备好，请稍后再试或检查服务器设置"
        }
    }

    /**
     * 初始化上传策略设置
     */
    private fun initializeUploadPolicy() {
        val prefs = context.getSharedPreferences(UPLOAD_POLICY, Context.MODE_PRIVATE)
        val wifiOnly = prefs.getBoolean("wifi_only", false) // 默认不限制
        val smartEnabled = prefs.getBoolean("smart_transmission_enabled", false)
        _uploadPolicyWiFiOnly.value = wifiOnly
        _smartTransmissionEnabled.value = smartEnabled
        
        // 应用策略到上传管理器
        applyUploadPolicy(wifiOnly)
        smartTransmissionManager.setSmartTransmissionEnabled(smartEnabled)
        
        Log.d(
            TAG,
            "上传策略初始化: ${if (wifiOnly) "仅WiFi" else "不限制"}, 智能传输=${if (smartEnabled) "开启" else "关闭"}"
        )
    }
    
    /**
     * 设置上传策略
     */
    fun setUploadPolicyWiFiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            try {
                // 保存到SharedPreferences
                val prefs = context.getSharedPreferences(UPLOAD_POLICY, Context.MODE_PRIVATE)
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

    fun setSmartTransmissionEnabled(enabled: Boolean) {
        val prefs = context.getSharedPreferences(UPLOAD_POLICY, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("smart_transmission_enabled", enabled).apply()
        _smartTransmissionEnabled.value = enabled
        smartTransmissionManager.setSmartTransmissionEnabled(enabled)
        Log.i(TAG, "智能传输已${if (enabled) "开启" else "关闭"}")
    }

    fun getSmartTransmissionEnabled(): Boolean {
        return _smartTransmissionEnabled.value ?: false
    }
    
    /**
     * 应用上传策略到上传管理器
     */
    private fun applyUploadPolicy(wifiOnly: Boolean) {
        uploadManager.setWifiOnlyMode(wifiOnly)
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
        authStartJob?.cancel()
        authenticationManager.stopAuthentication()
        viewModelScope.launch {
            try {
                _collectionStatus.value = "STOPPING"
                
                stopCollectionAndAwait()
                _collectionStatus.value = smartTransmissionManager.collectionState.value.status.name
                _authUiState.value = ContinuousAuthUiState(
                    authAnalysis = _authUiState.value.authAnalysis
                )

                updateDebugInfo()
                
            } catch (e: Exception) {
                _collectionStatus.value = "ERROR"
                Log.e(TAG, "停止数据采集失败", e)
                _errorMessage.value = "数据采集尚未完全停止，请稍后重试"
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
            "accelerometer" to (running && sensorCollectionSettings.value.accelerometerEnabled),
            "gyroscope" to (running && sensorCollectionSettings.value.gyroscopeEnabled),
            "magnetometer" to (running && sensorCollectionSettings.value.magnetometerEnabled)
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

                refreshUploadStats()
                
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
                val configInfo = withContext(Dispatchers.IO) { tlsSecurityManager.getTlsConfigInfo() }
                _tlsConfigInfo.value = configInfo
            } catch (e: Exception) {
                // 忽略错误，TLS信息只是展示用
            }
        }
    }
    
    /**
     * 更新传输统计
     */
    private fun updateTransmissionStats(status: UploadStatus? = null) {
        val uploadStatus = status ?: uploadManager.getUploadStatus()
        val pending = maxOf(uploadStatus.bufferedPackets,
            uploadStatus.fileQueueStats?.pendingPackets ?: 0)

        _transmissionStats.value = TransmissionStats(
            isFastMode = false,
            packetsSent = uploadStatus.uploadedPackets,
            packetsPending = pending,
            lastAckLatency = uploadStatus.connectionStats.lastAckLatency
        )
    }

    private fun refreshUploadStats() {
        val status = uploadManager.getUploadStatus()
        _connectionStatus.value = status.connectionStatus
        updateTransmissionStats(status)
        _fileQueueStats.value = status.fileQueueStats ?: QueueStats(
            totalPackets = status.bufferedPackets,
            pendingPackets = status.bufferedPackets
        )
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
        if (_debugModeEnabled.value != true || debugInfoJob?.isActive == true) return
        debugInfoJob = viewModelScope.launch {
            try {
                val debugText = withContext(Dispatchers.IO) { buildDebugInfo() }
                if (_debugModeEnabled.value == true) _debugInfo.value = debugText
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "无法刷新诊断信息", e)
            }
        }
    }
        
    private suspend fun buildDebugInfo(): String = buildString {
        appendLine("=== 智能传输状态 ===")
        appendLine("管理器状态: ${if (_isCollectionRunning.value == true) "运行中" else "已停止"}")
        appendLine("传输策略: 1秒窗口批处理")
        appendLine("批处理间隔: 1000ms")
        appendLine()
            
        appendLine("=== gRPC 连接状态 ===")
        val cfg = getServerConfig()
        val transport = uploadManager.getTransportState()
        appendLine("连接端点: ${cfg.scheme}://${cfg.host}:${cfg.port}")
        appendLine("连接状态: ${_connectionStatus.value}")
        val modeLabel = if (transport.mode == TransportMode.HTTPS) {
            val detail = listOfNotNull(
                transport.tlsVersion,
                transport.negotiatedProtocol
            ).filter { it.isNotBlank() }.joinToString(" / ")
            if (detail.isBlank()) "TLS" else "TLS $detail"
        } else {
            "h2c 明文" + if (transport.downgradedToCleartext) "（TLS不可用/失败）" else ""
        }
        appendLine("传输通道: $modeLabel")
        val stats = _transmissionStats.value
        stats?.lastAckLatency?.let { appendLine("最近ACK延迟: ${it}ms") }
        appendLine()
            
        appendLine("=== 缓冲与传输统计 ===")
        val metricsSnapshot = metricsCollector.getSnapshot()
        // 获取内存缓冲区状态
        val uploadStatus = uploadManager.getUploadStatus()
        val memorySamples = uploadStatus.bufferedPackets

        // 获取磁盘队列统计
        val queueStats = fileQueueManager.getQueueStatistics()

        appendLine("内存待发包数: $memorySamples")
        appendLine("磁盘队列包数: ${queueStats.pendingPackets}")
        appendLine("服务器已确认数据包: ${uploadStatus.uploadedPackets}")
        appendLine("上传失败数: ${(metricsSnapshot.counters[com.continuousauth.observability.MetricType.UPLOADS_FAILED_NETWORK] ?: 0) +
            (metricsSnapshot.counters[com.continuousauth.observability.MetricType.UPLOADS_FAILED_SERVER] ?: 0)}")
        appendLine("磁盘已丢弃数据包: ${queueStats.totalDiscarded}")
        appendLine("上传成功率: ${"%.1f".format(metricsSnapshot.summary.currentUploadSuccessRate)}%")
        appendLine()
            
        appendLine("=== 传感器信息 ===")
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            
        accelerometer?.let {
            appendLine("加速度计: ${it.vendor}")
            appendLine("  最大采样率: ${if (it.minDelay > 0) 1_000_000 / it.minDelay else 0}Hz")
            appendLine("  FIFO最大事件数: ${it.fifoMaxEventCount}")
        }
        gyroscope?.let {
            appendLine("陀螺仪: ${it.vendor}")
            appendLine("  最大采样率: ${if (it.minDelay > 0) 1_000_000 / it.minDelay else 0}Hz")
            appendLine("  FIFO最大事件数: ${it.fifoMaxEventCount}")
        }
        magnetometer?.let {
            appendLine("磁力计: ${it.vendor}")
            appendLine("  最大采样率: ${if (it.minDelay > 0) 1_000_000 / it.minDelay else 0}Hz")
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
    
    /**
     * 检查Usage Stats权限
     */
    suspend fun hasUsageStatsPermission(): Boolean {
        return isUsageStatsPermissionGranted()
    }
    /**
     * 检查PostNotificationsPermission权限
     */
    fun hasPostNotificationsPermission(): Boolean {
        return isPostNotificationsPermissionGranted()
    }

    /**
     * 检查Usage Stats权限
     */
    fun checkUsageStatsPermission() {
        viewModelScope.launch {
            val hasPermission = hasUsageStatsPermission()
            updateDebugInfo()
        }
    }

    fun startDataCollectionService() = startEncryptedUpload()
    /**
     * 检查 Usage Stats 权限是否已授予
     */
    fun isUsageStatsPermissionGranted(): Boolean {
        return try {
            val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOpsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 检查 POST_NOTIFICATIONS 权限是否已授予
     */
    fun isPostNotificationsPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13 及以上需要运行时权限
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    /**
     * 检查两个权限的状态
     * @return Pair<Boolean, Boolean> (UsageStats权限, 通知权限)
     */
    fun checkPermissions(): Pair<Boolean, Boolean> {
        val usageStatsGranted = isUsageStatsPermissionGranted()
        val notificationsGranted = isPostNotificationsPermissionGranted()
        return Pair(usageStatsGranted, notificationsGranted)
    }
    /**
     * 测试服务器连接：仅做一次性的 gRPC 连通性探测，不启动采集/上传服务。
     * 复用上传器的连接建立逻辑，避免与正式上传分叉。
     */
    fun testServerConnection(serverIp: String, serverPort: String) {
        viewModelScope.launch {
            try {
                _serverTestResult.value = "正在测试服务器连接..."
                val endpoint = ServerEndpointNormalizer.normalize(serverIp, serverPort.toIntOrNull())
                val result = uploadManager.testServerConnection(
                    serverHost = endpoint.host,
                    serverPort = endpoint.port,
                    useTls = endpoint.useTls,
                    testGrpc = true
                )
                _serverTestResult.value = uploadManager.getTestResultDescription(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _serverTestResult.value = "无法完成连接测试，请检查服务器地址后重试"
            }
        }
    }
    
    /**
     * 开始更新会话时长
     */
    private fun startSessionDurationUpdate() {
        sessionDurationJob?.cancel()
        sessionDurationJob = viewModelScope.launch {
            while (_isEncryptedUploading.value == true) {
                _sessionId.value = sessionManager.getCurrentSessionId()
                _sessionStartTime.value = sessionManager.getSessionStartTime()
                _sessionDuration.value = sessionManager.getFormattedSessionDuration()
                delay(1000) // 每秒更新一次
            }
        }
    }

    /**
     * 启动上传统计刷新，驱动UI显示真实的发送/队列数量。
     */
    private fun startUploadStatsUpdates() {
        uploadStatsJob?.cancel()
        uploadStatsJob = viewModelScope.launch {
            while (_isEncryptedUploading.value == true) {
                refreshUploadStats()

                delay(1000)
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
        if (consentObservationJob?.isActive != true) {
            consentObservationJob = viewModelScope.launch {
                privacyManager.consentState.collect { state ->
                    _consentState.value = state
                    if (state == ConsentState.NOT_GRANTED || state == ConsentState.WITHDRAWN) {
                        try {
                            stopCollectionAndAwait()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _errorMessage.value = "停止数据采集失败: ${e.message}"
                        }
                    }
                }
            }
        }

        if (deletionObservationJob?.isActive != true) {
            deletionObservationJob = viewModelScope.launch {
                privacyManager.deletionState.collect { state ->
                    _deletionState.value = state
                }
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
            if (isDeletingData) return@launch
            isDeletingData = true
            try {
                // Deletion must wait until the final encrypted batch is persisted and uploads stop.
                stopCollectionAndAwait()
                
                // 显示删除进度
                _collectionStatus.value = "正在删除数据..."
                
                // 执行撤回同意和数据删除
                val result = privacyManager.withdrawConsentAndDeleteData()
                
                if (result.isSuccess) {
                    initializeUploadPolicy()
                    val remotePending = privacyManager.deletionState.value == DeletionState.PARTIAL_SUCCESS
                    _collectionStatus.value = if (remotePending) "本地数据已删除，服务端删除尚未完成" else "数据已删除"
                    _errorMessage.value = if (remotePending) "本地数据已删除，服务端删除尚未完成" else null
                    
                    // 清除会话信息
                    _sessionId.value = null
                    _sessionStartTime.value = 0
                    _sessionDuration.value = "00:00"
                    sessionManager.clearAllData()
                    initializeUploadIdentity()

                    Log.i(TAG, "用户撤回同意，所有数据已删除")
                } else {
                    _errorMessage.value = "数据删除失败: ${result.exceptionOrNull()?.message}"
                    Log.e(TAG, "撤回同意失败", result.exceptionOrNull())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "撤回同意失败: ${e.message}"
                Log.e(TAG, "撤回同意过程中发生错误", e)
            } finally {
                isDeletingData = false
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
        // 单例监控器由应用持有，销毁界面不能中断后台服务。
        uploadStatsJob?.cancel()
        sessionDurationJob?.cancel()
        authAnalysisTimerJob?.cancel()
    }

    /**
     * 启动数据采集前台服务，避免后台被系统限制网络/传感器。
     */
    private fun startForegroundCollectionService() {
        val prefs = context.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE)
        check(prefs.edit().putBoolean(DataCollectionService.COLLECTION_REQUESTED_KEY, true).commit()) {
            "无法保存采集启动状态"
        }
        try {
            val intent = Intent(context, DataCollectionService::class.java).apply {
                action = DataCollectionService.ACTION_START_COLLECTION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            prefs.edit().putBoolean(DataCollectionService.COLLECTION_REQUESTED_KEY, false).apply()
            Log.e(TAG, "启动前台采集服务失败", e)
            throw e
        }
    }

    /**
     * 停止数据采集前台服务。
     */
    private fun stopForegroundCollectionService() {
        try {
            val prefs = context.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE)
            val wasRequested = prefs.getBoolean(DataCollectionService.COLLECTION_REQUESTED_KEY, false)
            check(prefs.edit().putBoolean(DataCollectionService.COLLECTION_REQUESTED_KEY, false).commit()) {
                "无法保存采集停止状态"
            }
            val state = smartTransmissionManager.collectionState.value.status
            if (!wasRequested && (state == SmartTransmissionManager.CollectionStatus.STOPPED ||
                    state == SmartTransmissionManager.CollectionStatus.ERROR)) return
            val intent = Intent(context, DataCollectionService::class.java).apply {
                action = DataCollectionService.ACTION_STOP_COLLECTION
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "停止前台采集服务失败", e)
            throw e
        }
    }

    private suspend fun stopCollectionAndAwait() {
        authStartJob?.cancel()
        authenticationManager.stopAuthentication()
        stopForegroundCollectionService()
        try {
            withTimeout(30_000L) {
                smartTransmissionManager.collectionState.first {
                    it.status == SmartTransmissionManager.CollectionStatus.STOPPED ||
                        it.status == SmartTransmissionManager.CollectionStatus.ERROR
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("采集停止超时，未执行后续删除操作", e)
        }
    }
}
