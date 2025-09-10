package com.continuousauth.ui.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.continuousauth.R
import com.continuousauth.databinding.FragmentServerConfigBinding
import com.continuousauth.ui.viewmodels.ServerConfigViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 服务器配置页面Fragment
 * 管理服务器连接设置和数据上传控制
 */
@AndroidEntryPoint
class ServerConfigFragment : Fragment() {

    private var _binding: FragmentServerConfigBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: ServerConfigViewModel by viewModels()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentServerConfigBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupViews()
        observeViewModel()
        
        // 加载保存的配置
        viewModel.loadServerConfig()
    }
    
    private fun setupViews() {
        // 设备ID复制功能
        binding.layoutDeviceId.setOnClickListener {
            copyDeviceIdToClipboard()
        }
        
        // 服务器连接测试按钮
        binding.btnTestConnection.setOnClickListener {
            testServerConnection()
        }
        
        // 开始/停止上传按钮
        binding.btnToggleUpload.setOnClickListener {
            toggleUpload()
        }
        
        // 输入框监听
        binding.etServerIp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveServerConfig()
            }
        }
        
        binding.etServerPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveServerConfig()
            }
        }
    }
    
    private fun observeViewModel() {
        // 观察设备ID
        viewModel.deviceId.observe(viewLifecycleOwner) { deviceId ->
            binding.tvDeviceId.text = deviceId
        }
        
        // 观察服务器配置
        viewModel.serverConfig.observe(viewLifecycleOwner) { config ->
            if (binding.etServerIp.text.toString() != config.ip) {
                binding.etServerIp.setText(config.ip)
            }
            if (binding.etServerPort.text.toString() != config.port.toString()) {
                binding.etServerPort.setText(config.port.toString())
            }
        }
        
        // 观察连接状态
        viewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            updateConnectionStatus(status)
        }
        
        // 观察上传状态
        viewModel.isUploading.observe(viewLifecycleOwner) { isUploading ->
            updateUploadButton(isUploading)
        }
        
        // 观察错误消息
        viewModel.errorMessage.observe(viewLifecycleOwner) { message ->
            message?.let {
                showError(it)
            }
        }
        
        // 观察成功消息
        viewModel.successMessage.observe(viewLifecycleOwner) { message ->
            message?.let {
                showSuccess(it)
            }
        }
    }
    
    private fun copyDeviceIdToClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Device ID", binding.tvDeviceId.text)
        clipboard.setPrimaryClip(clip)
        
        Toast.makeText(requireContext(), getString(R.string.device_id_copied), Toast.LENGTH_SHORT).show()
    }
    
    private fun testServerConnection() {
        // 先保存配置
        saveServerConfig()
        
        // 显示进度
        binding.progressConnection.visibility = View.VISIBLE
        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = getString(R.string.testing_connection)
        
        // 测试连接
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.testConnection()
            
            // 隐藏进度
            binding.progressConnection.visibility = View.GONE
            binding.btnTestConnection.isEnabled = true
            binding.btnTestConnection.text = getString(R.string.test_connection)
        }
    }
    
    private fun toggleUpload() {
        if (viewModel.isUploading.value == true) {
            // 停止上传前确认
            showStopUploadConfirmation()
        } else {
            // 开始上传前检查配置
            if (validateServerConfig()) {
                startUpload()
            }
        }
    }
    
    private fun showStopUploadConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.stop_upload_title))
            .setMessage(getString(R.string.stop_upload_message))
            .setPositiveButton(getString(R.string.stop)) { _, _ ->
                stopUpload()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    private fun startUpload() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.startUpload()
        }
    }
    
    private fun stopUpload() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.stopUpload()
        }
    }
    
    private fun saveServerConfig() {
        val ip = binding.etServerIp.text.toString().trim()
        val portText = binding.etServerPort.text.toString().trim()
        
        if (ip.isNotEmpty() && portText.isNotEmpty()) {
            try {
                val port = portText.toInt()
                viewModel.saveServerConfig(ip, port)
            } catch (e: NumberFormatException) {
                showError(getString(R.string.invalid_port))
            }
        }
    }
    
    private fun validateServerConfig(): Boolean {
        val ip = binding.etServerIp.text.toString().trim()
        val portText = binding.etServerPort.text.toString().trim()
        
        if (ip.isEmpty()) {
            binding.tilServerIp.error = getString(R.string.error_empty_ip)
            return false
        }
        
        if (portText.isEmpty()) {
            binding.tilServerPort.error = getString(R.string.error_empty_port)
            return false
        }
        
        try {
            val port = portText.toInt()
            if (port < 1 || port > 65535) {
                binding.tilServerPort.error = getString(R.string.error_invalid_port_range)
                return false
            }
        } catch (e: NumberFormatException) {
            binding.tilServerPort.error = getString(R.string.error_invalid_port)
            return false
        }
        
        // 清除错误提示
        binding.tilServerIp.error = null
        binding.tilServerPort.error = null
        
        return true
    }
    
    private fun updateConnectionStatus(status: ServerConfigViewModel.ConnectionStatus) {
        val (text, color) = when (status) {
            ServerConfigViewModel.ConnectionStatus.CONNECTED -> {
                Pair(getString(R.string.connected), R.color.status_connected)
            }
            ServerConfigViewModel.ConnectionStatus.DISCONNECTED -> {
                Pair(getString(R.string.disconnected), R.color.status_disconnected)
            }
            ServerConfigViewModel.ConnectionStatus.CONNECTING -> {
                Pair(getString(R.string.connecting), R.color.status_connecting)
            }
            ServerConfigViewModel.ConnectionStatus.ERROR -> {
                Pair(getString(R.string.connection_error), R.color.status_error)
            }
        }
        
        binding.tvConnectionStatus.text = text
        binding.tvConnectionStatus.setTextColor(requireContext().getColor(color))
        binding.viewConnectionIndicator.setBackgroundColor(requireContext().getColor(color))
    }
    
    private fun updateUploadButton(isUploading: Boolean) {
        if (isUploading) {
            binding.btnToggleUpload.text = getString(R.string.stop_upload)
            binding.btnToggleUpload.setIconResource(R.drawable.ic_stop)
            binding.btnToggleUpload.setBackgroundColor(requireContext().getColor(R.color.button_stop))
        } else {
            binding.btnToggleUpload.text = getString(R.string.start_upload)
            binding.btnToggleUpload.setIconResource(R.drawable.ic_play_arrow)
            binding.btnToggleUpload.setBackgroundColor(requireContext().getColor(R.color.button_start))
        }
    }
    
    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }
    
    private fun showSuccess(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // 保存最终配置
        saveServerConfig()
        _binding = null
    }
}