package com.continuousauth.ui.viewmodels

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuousauth.network.Uploader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 服务器配置页面的ViewModel
 */
@HiltViewModel
class ServerConfigViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploader: Uploader
) : ViewModel() {
    
    enum class ConnectionStatus {
        CONNECTED,
        DISCONNECTED,
        CONNECTING,
        ERROR
    }
    
    data class ServerConfig(
        val ip: String = "",
        val port: Int = 50051  // 默认使用 gRPC 常用端口
    )
    
    // 设备ID
    private val _deviceId = MutableLiveData<String>()
    val deviceId: LiveData<String> = _deviceId
    
    // 服务器配置
    private val _serverConfig = MutableLiveData<ServerConfig>()
    val serverConfig: LiveData<ServerConfig> = _serverConfig
    
    // 连接状态
    private val _connectionStatus = MutableLiveData(ConnectionStatus.DISCONNECTED)
    val connectionStatus: LiveData<ConnectionStatus> = _connectionStatus
    
    // 上传状态
    private val _isUploading = MutableLiveData(false)
    val isUploading: LiveData<Boolean> = _isUploading
    
    // 错误消息
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage
    
    // 成功消息
    private val _successMessage = MutableLiveData<String?>()
    val successMessage: LiveData<String?> = _successMessage
    
    init {
        loadDeviceId()
    }
    
    /**
     * 加载设备ID
     */
    @SuppressLint("HardwareIds")
    private fun loadDeviceId() {
        _deviceId.value = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
    }
    
    /**
     * 加载保存的服务器配置
     */
    fun loadServerConfig() {
        viewModelScope.launch {
            // 从SharedPreferences或DataStore加载配置
            val prefs = context.getSharedPreferences("server_config", Context.MODE_PRIVATE)
            val ip = prefs.getString("server_ip", "") ?: ""  // 默认为空字符串
            val port = prefs.getInt("server_port", 50051)  // 默认指向 gRPC 端口
            
            _serverConfig.value = ServerConfig(ip, port)
        }
    }
    
    /**
     * 保存服务器配置
     */
    fun saveServerConfig(ip: String, port: Int) {
        viewModelScope.launch {
            _serverConfig.value = ServerConfig(ip, port)
            
            // 保存到SharedPreferences
            val prefs = context.getSharedPreferences("server_config", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("server_ip", ip)
                .putInt("server_port", port)
                .apply()
        }
    }
    
    /**
     * 测试服务器连接
     */
    suspend fun testConnection() {
        withContext(Dispatchers.IO) {
            try {
                _connectionStatus.postValue(ConnectionStatus.CONNECTING)
                
                val config = _serverConfig.value ?: return@withContext
                val endpoint = "${config.ip}:${config.port}"
                
                // 尝试连接并立即断开以测试连接
                val result = uploader.connect(endpoint)
                
                if (result) {
                    _connectionStatus.postValue(ConnectionStatus.CONNECTED)
                    // 使用字符串资源ID，支持国际化
                    _successMessage.postValue(context.getString(com.continuousauth.R.string.connection_test_success))
                    // 测试后断开连接
                    uploader.disconnect()
                    _connectionStatus.postValue(ConnectionStatus.DISCONNECTED)
                } else {
                    _connectionStatus.postValue(ConnectionStatus.ERROR)
                    _errorMessage.postValue(context.getString(com.continuousauth.R.string.connection_test_failed))
                }
            } catch (e: Exception) {
                _connectionStatus.postValue(ConnectionStatus.ERROR)
                _errorMessage.postValue(context.getString(com.continuousauth.R.string.connection_test_error, e.message))
            }
        }
    }
    
    /**
     * 开始上传
     */
    suspend fun startUpload() {
        withContext(Dispatchers.IO) {
            try {
                val config = _serverConfig.value ?: return@withContext
                
                // 连接到服务器
                _connectionStatus.postValue(ConnectionStatus.CONNECTING)
                val endpoint = "${config.ip}:${config.port}"
                val connected = uploader.connect(endpoint)
                
                if (connected) {
                    _connectionStatus.postValue(ConnectionStatus.CONNECTED)
                    _isUploading.postValue(true)
                    _successMessage.postValue(context.getString(com.continuousauth.R.string.upload_started))
                } else {
                    _connectionStatus.postValue(ConnectionStatus.ERROR)
                    _errorMessage.postValue(context.getString(com.continuousauth.R.string.connection_failed))
                }
            } catch (e: Exception) {
                _connectionStatus.postValue(ConnectionStatus.ERROR)
                _errorMessage.postValue(context.getString(com.continuousauth.R.string.upload_start_failed, e.message))
                _isUploading.postValue(false)
            }
        }
    }
    
    /**
     * 停止上传
     */
    suspend fun stopUpload() {
        withContext(Dispatchers.IO) {
            try {
                // 断开连接
                uploader.disconnect()
                
                _isUploading.postValue(false)
                _connectionStatus.postValue(ConnectionStatus.DISCONNECTED)
                _successMessage.postValue(context.getString(com.continuousauth.R.string.upload_stopped))
            } catch (e: Exception) {
                _errorMessage.postValue(context.getString(com.continuousauth.R.string.upload_stop_failed, e.message))
            }
        }
    }
    
    /**
     * 清除消息
     */
    fun clearMessages() {
        _errorMessage.value = null
        _successMessage.value = null
    }
}
