package com.continuousauth.network

import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络环境检测器
 * 检测当前网络类型和质量，用于调整传输策略
 */
@Singleton
class NetworkEnvironmentDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "NetworkEnvironmentDetector"
        private const val PING_TIMEOUT_MS = 3000
        private const val GOOD_LATENCY_THRESHOLD_MS = 100
        private const val POOR_LATENCY_THRESHOLD_MS = 500
    }
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    
    private val detectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // 网络状态变化流
    private val _networkStateFlow = MutableSharedFlow<NetworkState>(replay = 1)
    val networkStateFlow: SharedFlow<NetworkState> = _networkStateFlow.asSharedFlow()
    
    // 当前网络状态
    private var currentNetworkState = NetworkState.UNKNOWN
    
    // 网络监听器
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    init {
        startNetworkMonitoring()
    }
    
    /**
     * 开始监控网络状态
     */
    private fun startNetworkMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerNetworkCallback()
        }
        
        // 初始检测
        detectorScope.launch {
            updateNetworkState()
        }
    }
    
    /**
     * 注册网络状态回调 (Android N+)
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun registerNetworkCallback() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "网络可用: $network")
                detectorScope.launch {
                    updateNetworkState()
                }
            }
            
            override fun onLost(network: Network) {
                Log.d(TAG, "网络丢失: $network")
                detectorScope.launch {
                    updateNetworkState()
                }
            }
            
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                Log.d(TAG, "网络能力变化: $network")
                detectorScope.launch {
                    updateNetworkState()
                }
            }
        }
        
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "注册网络回调失败", e)
        }
    }
    
    /**
     * 更新网络状态
     */
    private suspend fun updateNetworkState() {
        val newState = detectNetworkState()
        
        if (newState != currentNetworkState) {
            currentNetworkState = newState
            Log.i(TAG, "网络状态变化: $newState")
            _networkStateFlow.emit(newState)
        }
    }
    
    /**
     * 检测当前网络状态
     */
    private suspend fun detectNetworkState(): NetworkState {
        return try {
            when {
                !isNetworkAvailable() -> NetworkState.DISCONNECTED
                isWifiConnected() -> detectWifiQuality()
                isCellularConnected() -> detectCellularQuality()
                else -> NetworkState.UNKNOWN
            }
        } catch (e: Exception) {
            Log.e(TAG, "检测网络状态异常", e)
            NetworkState.UNKNOWN
        }
    }
    
    /**
     * 检测WiFi网络质量
     */
    private suspend fun detectWifiQuality(): NetworkState {
        return try {
            val wifiInfo = wifiManager.connectionInfo
            val rssi = wifiInfo.rssi
            val linkSpeed = wifiInfo.linkSpeed
            
            Log.d(TAG, "WiFi信息 - RSSI: $rssi, 链路速度: ${linkSpeed}Mbps")
            
            // 基于信号强度和链路速度判断WiFi质量
            when {
                rssi > -50 && linkSpeed >= 100 -> NetworkState.WIFI_EXCELLENT
                rssi > -70 && linkSpeed >= 50 -> NetworkState.WIFI_GOOD
                else -> NetworkState.WIFI_POOR
            }
        } catch (e: Exception) {
            Log.e(TAG, "检测WiFi质量异常", e)
            NetworkState.WIFI_UNKNOWN
        }
    }
    
    /**
     * 检测蜂窝网络质量
     */
    private suspend fun detectCellularQuality(): NetworkState {
        return try {
            val networkType = getNetworkType()
            val signalStrength = getCellularSignalStrength()
            
            Log.d(TAG, "蜂窝网络 - 类型: $networkType, 信号强度: $signalStrength")
            
            when (networkType) {
                // 5G网络
                "5G" -> if (signalStrength >= -85) NetworkState.CELLULAR_EXCELLENT else NetworkState.CELLULAR_GOOD
                
                // 4G网络
                "LTE", "4G" -> when {
                    signalStrength >= -85 -> NetworkState.CELLULAR_EXCELLENT
                    signalStrength >= -105 -> NetworkState.CELLULAR_GOOD
                    else -> NetworkState.CELLULAR_POOR
                }
                
                // 3G网络
                "3G", "HSPA", "HSUPA", "HSDPA" -> when {
                    signalStrength >= -90 -> NetworkState.CELLULAR_GOOD
                    else -> NetworkState.CELLULAR_POOR
                }
                
                // 2G网络
                "2G", "GSM", "EDGE", "GPRS" -> NetworkState.CELLULAR_POOR
                
                else -> NetworkState.CELLULAR_UNKNOWN
            }
        } catch (e: Exception) {
            Log.e(TAG, "检测蜂窝网络质量异常", e)
            NetworkState.CELLULAR_UNKNOWN
        }
    }
    
    /**
     * 检查网络是否可用
     */
    private fun isNetworkAvailable(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        
        return networkCapabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: false
    }
    
    
    /**
     * 检查是否连接到蜂窝网络
     */
    private fun isCellularConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        
        return networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
    }
    
    /**
     * 获取网络类型字符串
     */
    private fun getNetworkType(): String {
        return try {
            @Suppress("DEPRECATION")
            when (telephonyManager.networkType) {
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
                
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_EHRPD,
                TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
                
                TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (telephonyManager.networkType == TelephonyManager.NETWORK_TYPE_NR) {
                            "5G"
                        } else {
                            "UNKNOWN"
                        }
                    } else {
                        "UNKNOWN"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取网络类型异常", e)
            "UNKNOWN"
        }
    }
    
    /**
     * 获取蜂窝网络信号强度 (dBm)
     */
    private fun getCellularSignalStrength(): Int {
        return try {
            val signalStrength = telephonyManager.signalStrength
            signalStrength?.let {
                // 使用反射获取信号强度，因为不同Android版本API有差异
                val method = it.javaClass.getMethod("getDbm")
                method.invoke(it) as? Int ?: -999
            } ?: -999
        } catch (e: Exception) {
            Log.w(TAG, "获取信号强度异常，使用估算值", e)
            // 返回中等信号强度作为默认值
            -95
        }
    }
    
    /**
     * 获取当前网络状态
     */
    fun getCurrentNetworkState(): NetworkState {
        return currentNetworkState
    }
    
    /**
     * 检查是否为WiFi网络
     */
    fun isWifiConnected(): Boolean {
        return when (currentNetworkState) {
            NetworkState.WIFI_EXCELLENT,
            NetworkState.WIFI_GOOD,
            NetworkState.WIFI_POOR,
            NetworkState.WIFI_UNKNOWN -> true
            else -> false
        }
    }
    
    /**
     * 检查网络是否可用（根据传输策略）
     * @param wifiOnly 是否仅允许WiFi
     */
    fun isNetworkAvailableForUpload(wifiOnly: Boolean): Boolean {
        return if (wifiOnly) {
            isWifiConnected()
        } else {
            currentNetworkState != NetworkState.DISCONNECTED && 
            currentNetworkState != NetworkState.UNKNOWN
        }
    }
    
    /**
     * 获取网络传输配置建议
     */
    fun getTransmissionConfig(): TransmissionConfig {
        return when (currentNetworkState) {
            NetworkState.WIFI_EXCELLENT -> TransmissionConfig(
                maxConcurrentUploads = 4,
                uploadTimeoutMs = 10000,
                retryDelayMs = 1000,
                maxRetryAttempts = 3
            )
            
            NetworkState.WIFI_GOOD -> TransmissionConfig(
                maxConcurrentUploads = 3,
                uploadTimeoutMs = 15000,
                retryDelayMs = 2000,
                maxRetryAttempts = 3
            )
            
            NetworkState.CELLULAR_EXCELLENT -> TransmissionConfig(
                maxConcurrentUploads = 2,
                uploadTimeoutMs = 15000,
                retryDelayMs = 2000,
                maxRetryAttempts = 4
            )
            
            NetworkState.CELLULAR_GOOD -> TransmissionConfig(
                maxConcurrentUploads = 1,
                uploadTimeoutMs = 20000,
                retryDelayMs = 3000,
                maxRetryAttempts = 5
            )
            
            NetworkState.CELLULAR_POOR,
            NetworkState.WIFI_POOR -> TransmissionConfig(
                maxConcurrentUploads = 1,
                uploadTimeoutMs = 30000,
                retryDelayMs = 5000,
                maxRetryAttempts = 8
            )
            
            NetworkState.DISCONNECTED -> TransmissionConfig(
                maxConcurrentUploads = 0,
                uploadTimeoutMs = 0,
                retryDelayMs = 10000,
                maxRetryAttempts = 0
            )
            
            else -> TransmissionConfig() // 默认配置
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "取消网络监听异常", e)
            }
        }
        detectorScope.cancel()
    }
}

/**
 * 网络状态枚举
 */
enum class NetworkState {
    UNKNOWN,                    // 未知状态
    DISCONNECTED,              // 网络断开
    
    // WiFi网络
    WIFI_EXCELLENT,            // WiFi优秀 (RSSI > -50, 链路速度 >= 100Mbps)
    WIFI_GOOD,                 // WiFi良好 (RSSI > -70, 链路速度 >= 50Mbps)
    WIFI_POOR,                 // WiFi差 (其他WiFi情况)
    WIFI_UNKNOWN,              // WiFi未知质量
    
    // 蜂窝网络
    CELLULAR_EXCELLENT,        // 蜂窝优秀 (5G或高质量4G)
    CELLULAR_GOOD,             // 蜂窝良好 (中等质量4G/3G)
    CELLULAR_POOR,             // 蜂窝差 (弱信号4G或3G/2G)
    CELLULAR_UNKNOWN           // 蜂窝未知质量
}

/**
 * 传输配置建议
 */
data class TransmissionConfig(
    val maxConcurrentUploads: Int = 2,      // 最大并发上传数
    val uploadTimeoutMs: Long = 15000,       // 上传超时时间(毫秒)
    val retryDelayMs: Long = 3000,          // 重试延迟(毫秒)
    val maxRetryAttempts: Int = 5           // 最大重试次数
)