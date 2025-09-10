package com.continuousauth.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.continuousauth.R
import com.continuousauth.databinding.ActivityMainBinding
import com.continuousauth.network.ConnectionStatus
import com.continuousauth.network.NetworkState
import com.continuousauth.ui.dialogs.PrivacyAgreementDialog
import com.continuousauth.ui.dialogs.BatteryOptimizationDialog
import com.continuousauth.ui.dialogs.UsageStatsPermissionDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 主活动
 * 负责显示应用主界面和协调各个功能模块
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val PRIVACY_AGREEMENT_SHOWN = "privacy_agreement_shown"
        private const val BATTERY_OPTIMIZATION_REQUESTED = "battery_optimization_requested"
    }
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    
    // 权限请求器
    private val usageStatsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 检查USAGE_STATS权限是否已授予
        viewModel.checkUsageStatsPermission()
    }
    
    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 记录用户已处理电池优化请求
        getSharedPreferences("app_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean(BATTERY_OPTIMIZATION_REQUESTED, true)
            .apply()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 设置ActionBar标题
        supportActionBar?.title = getString(R.string.app_name)
        
        // 初始化ViewModel
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        
        // 设置UI事件监听器
        setupUIListeners()
        
        // 观察ViewModel数据变化
        observeViewModelData()
        
        // 检查并显示首次启动流程
        checkFirstLaunchFlow()
        
        Log.i(TAG, "MainActivity已初始化")
    }
    
    override fun onResume() {
        super.onResume()
        // 刷新状态信息
        viewModel.refreshStatus()
    }
    
    /**
     * 设置UI事件监听器
     */
    private fun setupUIListeners() {
        // 开始/停止采集按钮
        binding.btnToggleCollection.setOnClickListener {
            if (viewModel.isCollectionRunning.value == true) {
                viewModel.stopCollection()
            } else {
                viewModel.startCollection()
            }
        }
        
        // 图表显示/隐藏按钮
        binding.btnToggleChart.setOnClickListener {
            val isVisible = binding.chartContainer.visibility == View.VISIBLE
            if (isVisible) {
                binding.chartContainer.visibility = View.GONE
                binding.btnToggleChart.text = getString(R.string.show_chart)
            } else {
                binding.chartContainer.visibility = View.VISIBLE
                binding.btnToggleChart.text = getString(R.string.hide_chart)
                viewModel.startVisualization()
            }
        }
    }
    
    /**
     * 观察ViewModel数据变化
     */
    private fun observeViewModelData() {
        // 采集状态
        viewModel.collectionStatus.observe(this) { status ->
            updateCollectionStatus(status)
        }
        
        // 采集运行状态
        viewModel.isCollectionRunning.observe(this) { isRunning ->
            updateCollectionButton(isRunning)
        }
        
        // 传感器状态
        viewModel.sensorStatus.observe(this) { sensorStatus ->
            updateSensorStatus(sensorStatus)
        }
        
        // 网络状态
        viewModel.networkState.observe(this) { networkState ->
            updateNetworkStatus(networkState)
        }
        
        // 连接状态
        viewModel.connectionStatus.observe(this) { connectionStatus ->
            updateConnectionStatus(connectionStatus)
        }
        
        // 传输统计
        viewModel.transmissionStats.observe(this) { stats ->
            updateTransmissionStats(stats)
        }
        
        // 调试信息显示状态
        viewModel.debugModeEnabled.observe(this) { debugEnabled ->
            binding.cardDebugInfo.visibility = if (debugEnabled) View.VISIBLE else View.GONE
        }
        
        // 可视化启用状态
        viewModel.visualizationEnabled.observe(this) { visualizationEnabled ->
            binding.cardVisualization.visibility = if (visualizationEnabled) View.VISIBLE else View.GONE
        }
        
        // 错误消息
        viewModel.errorMessage.observe(this) { errorMessage ->
            if (!errorMessage.isNullOrEmpty()) {
                // 显示错误对话框或Snackbar
                showErrorMessage(errorMessage)
            }
        }
        
        // 调试信息
        viewModel.debugInfo.observe(this) { debugInfo ->
            binding.tvDebugInfo.text = debugInfo
        }
    }
    
    /**
     * 更新采集状态显示
     */
    private fun updateCollectionStatus(status: String) {
        binding.tvCollectionStatus.text = when (status) {
            "RUNNING" -> getString(R.string.status_running)
            "STOPPED" -> getString(R.string.status_stopped)
            "PAUSED" -> getString(R.string.status_paused)
            "ERROR" -> getString(R.string.status_error)
            else -> status
        }
        
        // 根据状态更改文字颜色
        val colorRes = when (status) {
            "RUNNING" -> R.color.status_running
            "STOPPED" -> R.color.status_stopped
            "PAUSED" -> R.color.status_paused
            "ERROR" -> R.color.status_error
            else -> R.color.secondary_text
        }
        binding.tvCollectionStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }
    
    /**
     * 更新采集按钮状态
     */
    private fun updateCollectionButton(isRunning: Boolean) {
        if (isRunning) {
            binding.btnToggleCollection.text = getString(R.string.stop_collection)
            binding.btnToggleCollection.icon = ContextCompat.getDrawable(this, R.drawable.ic_stop)
        } else {
            binding.btnToggleCollection.text = getString(R.string.start_collection)
            binding.btnToggleCollection.icon = ContextCompat.getDrawable(this, R.drawable.ic_play_arrow)
        }
    }
    
    /**
     * 更新传感器状态显示
     */
    private fun updateSensorStatus(sensorStatus: Map<String, Boolean>) {
        // 更新加速度计状态
        val accelerometerActive = sensorStatus["accelerometer"] == true
        binding.tvAccelerometerStatus.text = if (accelerometerActive) 
            getString(R.string.sensor_active) else getString(R.string.sensor_inactive)
        binding.tvAccelerometerStatus.setTextColor(
            ContextCompat.getColor(this, 
                if (accelerometerActive) R.color.sensor_active else R.color.sensor_inactive
            )
        )
        
        // 更新陀螺仪状态
        val gyroscopeActive = sensorStatus["gyroscope"] == true
        binding.tvGyroscopeStatus.text = if (gyroscopeActive) 
            getString(R.string.sensor_active) else getString(R.string.sensor_inactive)
        binding.tvGyroscopeStatus.setTextColor(
            ContextCompat.getColor(this, 
                if (gyroscopeActive) R.color.sensor_active else R.color.sensor_inactive
            )
        )
        
        // 更新磁力计状态
        val magnetometerActive = sensorStatus["magnetometer"] == true
        binding.tvMagnetometerStatus.text = if (magnetometerActive) 
            getString(R.string.sensor_active) else getString(R.string.sensor_inactive)
        binding.tvMagnetometerStatus.setTextColor(
            ContextCompat.getColor(this, 
                if (magnetometerActive) R.color.sensor_active else R.color.sensor_inactive
            )
        )
    }
    
    /**
     * 更新网络状态显示
     */
    private fun updateNetworkStatus(networkState: NetworkState) {
        val qualityText = when (networkState) {
            NetworkState.WIFI_EXCELLENT -> getString(R.string.wifi_excellent)
            NetworkState.WIFI_GOOD -> getString(R.string.wifi_good)
            NetworkState.WIFI_POOR -> getString(R.string.wifi_poor)
            NetworkState.CELLULAR_EXCELLENT -> getString(R.string.cellular_excellent)
            NetworkState.CELLULAR_GOOD -> getString(R.string.cellular_good)
            NetworkState.CELLULAR_POOR -> getString(R.string.cellular_poor)
            NetworkState.DISCONNECTED -> getString(R.string.disconnected)
            else -> "-"
        }
        
        binding.tvNetworkQuality.text = qualityText
        
        // 根据网络质量设置颜色
        val colorRes = when (networkState) {
            NetworkState.WIFI_EXCELLENT, NetworkState.CELLULAR_EXCELLENT -> R.color.network_excellent
            NetworkState.WIFI_GOOD, NetworkState.CELLULAR_GOOD -> R.color.network_good
            NetworkState.WIFI_POOR, NetworkState.CELLULAR_POOR -> R.color.network_poor
            NetworkState.DISCONNECTED -> R.color.network_disconnected
            else -> R.color.secondary_text
        }
        binding.tvNetworkQuality.setTextColor(ContextCompat.getColor(this, colorRes))
    }
    
    /**
     * 更新连接状态显示
     */
    private fun updateConnectionStatus(connectionStatus: ConnectionStatus) {
        val statusText = when (connectionStatus) {
            ConnectionStatus.CONNECTED -> getString(R.string.connected)
            ConnectionStatus.DISCONNECTED -> getString(R.string.disconnected)
            ConnectionStatus.CONNECTING -> getString(R.string.connecting)
            ConnectionStatus.RECONNECTING -> getString(R.string.reconnecting)
            else -> connectionStatus.name
        }
        
        binding.tvConnectionStatus.text = statusText
        
        // 根据连接状态设置颜色
        val colorRes = when (connectionStatus) {
            ConnectionStatus.CONNECTED -> R.color.status_running
            ConnectionStatus.DISCONNECTED -> R.color.status_error
            ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> R.color.status_paused
            else -> R.color.secondary_text
        }
        binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }
    
    /**
     * 更新传输统计显示
     */
    private fun updateTransmissionStats(stats: TransmissionStats) {
        binding.tvTransmissionMode.text = if (stats.isFastMode) 
            getString(R.string.fast_mode) else getString(R.string.slow_mode)
        binding.tvPacketsSent.text = stats.packetsSent.toString()
        binding.tvPacketsPending.text = stats.packetsPending.toString()
    }
    
    /**
     * 检查首次启动流程
     */
    private fun checkFirstLaunchFlow() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        
        // 检查是否已显示隐私协议
        if (!prefs.getBoolean(PRIVACY_AGREEMENT_SHOWN, false)) {
            showPrivacyAgreement()
        } else {
            // 检查权限
            checkPermissionsAndGuidance()
        }
    }
    
    /**
     * 显示隐私协议
     */
    private fun showPrivacyAgreement() {
        val dialog = PrivacyAgreementDialog { accepted ->
            if (accepted) {
                // 用户接受隐私协议
                getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean(PRIVACY_AGREEMENT_SHOWN, true)
                    .apply()
                
                // 通知PrivacyManager记录用户同意
                viewModel.grantPrivacyConsent()
                
                checkPermissionsAndGuidance()
            } else {
                // 用户拒绝隐私协议，关闭应用
                finish()
            }
        }
        dialog.show(supportFragmentManager, "privacy_agreement")
    }
    
    /**
     * 检查权限和引导流程
     */
    private fun checkPermissionsAndGuidance() {
        lifecycleScope.launch {
            // 检查Usage Stats权限
            if (!viewModel.hasUsageStatsPermission()) {
                showUsageStatsPermissionDialog()
            } else {
                checkBatteryOptimization()
            }
        }
    }
    
    /**
     * 显示Usage Stats权限对话框
     */
    private fun showUsageStatsPermissionDialog() {
        val dialog = UsageStatsPermissionDialog { granted ->
            if (granted) {
                // 跳转到设置页面
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                usageStatsPermissionLauncher.launch(intent)
            } else {
                // 继续检查电池优化
                checkBatteryOptimization()
            }
        }
        dialog.show(supportFragmentManager, "usage_stats_permission")
    }
    
    /**
     * 检查电池优化设置
     */
    private fun checkBatteryOptimization() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        
        // 检查是否已请求过电池优化
        if (!prefs.getBoolean(BATTERY_OPTIMIZATION_REQUESTED, false)) {
            showBatteryOptimizationDialog()
        }
    }
    
    /**
     * 显示电池优化对话框
     */
    private fun showBatteryOptimizationDialog() {
        val dialog = BatteryOptimizationDialog { disableOptimization ->
            if (disableOptimization && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 跳转到电池优化设置
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                batteryOptimizationLauncher.launch(intent)
            } else {
                // 用户跳过或系统不支持
                getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean(BATTERY_OPTIMIZATION_REQUESTED, true)
                    .apply()
            }
        }
        dialog.show(supportFragmentManager, "battery_optimization")
    }
    
    /**
     * 显示错误消息
     */
    private fun showErrorMessage(message: String) {
        // 这里可以使用Snackbar或AlertDialog
        Log.e(TAG, "Error: $message")
        // TODO: 实现用户友好的错误显示
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                // 打开设置页面
                // TODO: 实现设置Activity
                true
            }
            R.id.action_debug -> {
                // 切换调试模式
                viewModel.toggleDebugMode()
                true
            }
            R.id.action_visualization -> {
                // 切换可视化模式
                viewModel.toggleVisualization()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "MainActivity销毁")
    }
}

/**
 * 传输统计数据类
 */
data class TransmissionStats(
    val isFastMode: Boolean = false,
    val packetsSent: Long = 0L,
    val packetsPending: Int = 0,
    val lastAckLatency: Long? = null
)