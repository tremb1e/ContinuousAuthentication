package com.continuousauth.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.continuousauth.databinding.FragmentSensorDataBinding
import com.continuousauth.ui.adapters.RecentAppsAdapter
import com.continuousauth.ui.chart.ChartManager
import com.continuousauth.ui.chart.SensorChartView
import javax.inject.Inject
import com.continuousauth.ui.viewmodels.SensorDataViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 传感器数据页面Fragment
 * 显示实时传感器数据、可折叠图表和最近使用的应用列表
 */
@AndroidEntryPoint
class SensorDataFragment : Fragment() {

    private var _binding: FragmentSensorDataBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: SensorDataViewModel by viewModels()
    
    @Inject
    lateinit var chartManager: ChartManager
    
    private lateinit var accelerometerChart: SensorChartView
    private lateinit var gyroscopeChart: SensorChartView
    private lateinit var magnetometerChart: SensorChartView
    private lateinit var recentAppsAdapter: RecentAppsAdapter
    
    private val dateTimeFormatter = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSensorDataBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupViews()
        observeViewModel()
        
        // 开始收集传感器数据
        viewModel.startSensorCollection()
    }
    
    private fun setupViews() {
        // 初始化三个图表视图
        accelerometerChart = SensorChartView(requireContext())
        gyroscopeChart = SensorChartView(requireContext())
        magnetometerChart = SensorChartView(requireContext())
        
        // 设置不同的颜色方案
        accelerometerChart.setColorScheme(
            android.graphics.Color.RED,     // X轴 - 红色
            android.graphics.Color.GREEN,   // Y轴 - 绿色  
            android.graphics.Color.BLUE     // Z轴 - 蓝色
        )
        
        gyroscopeChart.setColorScheme(
            android.graphics.Color.rgb(255, 140, 0),    // X轴 - 橙色
            android.graphics.Color.rgb(0, 191, 255),    // Y轴 - 深天蓝
            android.graphics.Color.rgb(148, 0, 211)     // Z轴 - 紫色
        )
        
        magnetometerChart.setColorScheme(
            android.graphics.Color.rgb(255, 20, 147),   // X轴 - 深粉色
            android.graphics.Color.rgb(34, 139, 34),    // Y轴 - 森林绿
            android.graphics.Color.rgb(70, 130, 180)    // Z轴 - 钢蓝色
        )
        
        // 添加到各自的容器并设置点击监听（支持详细查看）
        binding.accelerometerChartContainer.addView(accelerometerChart)
        binding.gyroscopeChartContainer.addView(gyroscopeChart)
        binding.magnetometerChartContainer.addView(magnetometerChart)
        
        // 为每个图表设置交互功能
        accelerometerChart.enableInteractiveMode()
        gyroscopeChart.enableInteractiveMode()
        magnetometerChart.enableInteractiveMode()
        
        // 默认隐藏图表
        binding.chartContainer.visibility = View.GONE
        binding.tvChartToggle.text = getString(com.continuousauth.R.string.show_chart)
        
        // 设置图表展开/折叠点击监听
        binding.layoutChartHeader.setOnClickListener {
            toggleChartVisibility()
        }
        
        // 设置最近应用列表
        recentAppsAdapter = RecentAppsAdapter()
        binding.rvRecentApps.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = recentAppsAdapter
            setHasFixedSize(true)
        }
    }
    
    private fun observeViewModel() {
        // 观察加速度计数据
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.accelerometerData.collectLatest { data ->
                val currentTime = System.currentTimeMillis()
                binding.tvAccelerometerData.text = 
                    "X: %.3f\nY: %.3f\nZ: %.3f".format(data.x, data.y, data.z)
                binding.tvAccelerometerTimestamp.text = 
                    "Time: ${dateTimeFormatter.format(java.util.Date(currentTime))}"
                
                // 更新图表
                if (binding.chartContainer.visibility == View.VISIBLE) {
                    accelerometerChart.addDataPoint(data.x, data.y, data.z, currentTime)
                }
            }
        }
        
        // 观察陀螺仪数据
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.gyroscopeData.collectLatest { data ->
                val currentTime = System.currentTimeMillis()
                binding.tvGyroscopeData.text = 
                    "X: %.3f\nY: %.3f\nZ: %.3f".format(data.x, data.y, data.z)
                binding.tvGyroscopeTimestamp.text = 
                    "Time: ${dateTimeFormatter.format(java.util.Date(currentTime))}"
                
                // 更新图表
                if (binding.chartContainer.visibility == View.VISIBLE) {
                    gyroscopeChart.addDataPoint(data.x, data.y, data.z, currentTime)
                }
            }
        }
        
        // 观察磁力计数据
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.magnetometerData.collectLatest { data ->
                val currentTime = System.currentTimeMillis()
                binding.tvMagnetometerData.text = 
                    "X: %.3f\nY: %.3f\nZ: %.3f".format(data.x, data.y, data.z)
                binding.tvMagnetometerTimestamp.text = 
                    "Time: ${dateTimeFormatter.format(java.util.Date(currentTime))}"
                
                // 更新图表
                if (binding.chartContainer.visibility == View.VISIBLE) {
                    magnetometerChart.addDataPoint(data.x, data.y, data.z, currentTime)
                }
            }
        }
        
        // 观察最近应用列表 - 实时刷新
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recentApps.collectLatest { apps ->
                // 确保只显示最近10个应用
                val recentTenApps = apps.take(10)
                recentAppsAdapter.submitList(recentTenApps)
            }
        }
        
        // 观察传感器状态
        viewModel.sensorsActive.observe(viewLifecycleOwner) { isActive ->
            updateSensorStatus(isActive)
        }
    }
    
    private fun toggleChartVisibility() {
        if (binding.chartContainer.visibility == View.VISIBLE) {
            // 折叠图表
            binding.chartContainer.visibility = View.GONE
            binding.tvChartToggle.text = getString(com.continuousauth.R.string.show_chart)
            binding.ivChartToggle.rotation = 0f
            
            // 停止图表更新
            accelerometerChart.pauseUpdates()
            gyroscopeChart.pauseUpdates()
            magnetometerChart.pauseUpdates()
        } else {
            // 展开图表
            binding.chartContainer.visibility = View.VISIBLE
            binding.tvChartToggle.text = getString(com.continuousauth.R.string.hide_chart)
            binding.ivChartToggle.rotation = 180f
            
            // 开始图表更新
            accelerometerChart.resumeUpdates()
            gyroscopeChart.resumeUpdates()
            magnetometerChart.resumeUpdates()
        }
    }
    
    private fun updateSensorStatus(isActive: Boolean) {
        val statusText = if (isActive) {
            getString(com.continuousauth.R.string.sensor_active)
        } else {
            getString(com.continuousauth.R.string.sensor_inactive)
        }
        
        val statusColor = if (isActive) {
            requireContext().getColor(com.continuousauth.R.color.sensor_active)
        } else {
            requireContext().getColor(com.continuousauth.R.color.sensor_inactive)
        }
        
        binding.viewStatusIndicator.setBackgroundColor(statusColor)
        binding.tvSensorStatus.text = statusText
        binding.tvSensorStatus.setTextColor(statusColor)
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        
        // 清理图表资源
        accelerometerChart.cleanup()
        gyroscopeChart.cleanup()
        magnetometerChart.cleanup()
        
        _binding = null
    }
}