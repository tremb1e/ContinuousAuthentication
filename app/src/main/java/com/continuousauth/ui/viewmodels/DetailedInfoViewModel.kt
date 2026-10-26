package com.continuousauth.ui.viewmodels

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuousauth.monitor.SystemMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * 详细信息页面的ViewModel
 * 使用SystemMonitor提供真实的系统监控数据
 */
@HiltViewModel
class DetailedInfoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val systemMonitor: SystemMonitor
) : ViewModel() {
    private val _maintenanceMessage = MutableStateFlow<String?>(null)
    val maintenanceMessage = _maintenanceMessage.asStateFlow()
    
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
    
    /**
     * 清空本地队列
     */
    suspend fun clearLocalQueue() {
        try {
            systemMonitor.clearLocalQueue()
            showToast("本地队列已清空")
        } catch (e: Exception) {
            showToast("清空队列失败: ${e.message}")
        }
    }
    
    /**
     * 导出待处理数据
     */
    suspend fun preparePendingDataExport(): File? = withContext(Dispatchers.IO) {
        var sourceDirectory: File? = null
        var archive: File? = null
        try {
            val path = systemMonitor.exportPendingData()
            val directory = File(path)
            if (!directory.isDirectory) {
                showFeedback(if (path.startsWith("导出失败")) path else "目前没有待上传的数据可导出")
                return@withContext null
            }
            sourceDirectory = directory
            val files = directory.listFiles()?.filter { it.isFile }.orEmpty()
            if (files.isEmpty()) {
                showFeedback("目前没有可导出的待上传文件")
                return@withContext null
            }
            val output = File(exportDirectory(), "sensor_data_${System.currentTimeMillis()}.zip")
            archive = output
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                files.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            showFeedback("已准备好数据文件，请选择保存位置")
            output
        } catch (e: CancellationException) {
            archive?.delete()
            throw e
        } catch (e: Exception) {
            archive?.delete()
            showFeedback("准备导出失败：${e.message ?: "请稍后重试"}")
            null
        } finally {
            sourceDirectory?.deleteRecursively()
        }
    }
    
    /**
     * 强制密钥轮换
     */
    suspend fun forceKeyRotation() {
        try {
            val updated = systemMonitor.forceKeyRotation()
            showFeedback(if (updated) "密钥维护标记已更新（实际加密密钥未改变）" else "密钥维护标记未能更新，请重试")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showFeedback("密钥维护标记更新失败：${e.message}")
        }
    }
    
    /**
     * 导出调试日志
     */
    suspend fun prepareDebugLogExport(): File? = withContext(Dispatchers.IO) {
        var temporary: File? = null
        try {
            val path = systemMonitor.exportDebugLog()
            val source = File(path)
            check(source.isFile) { "日志文件未能生成" }
            val output = File(exportDirectory(), source.name)
            temporary = output
            try {
                source.copyTo(output, overwrite = true)
            } finally {
                source.delete()
            }
            showFeedback("已准备好日志文件，请选择保存位置")
            output
        } catch (e: CancellationException) {
            temporary?.delete()
            throw e
        } catch (e: Exception) {
            temporary?.delete()
            showFeedback("准备日志失败：${e.message ?: "请稍后重试"}")
            null
        }
    }

    /** Write to the location chosen by the user through Android's system file picker. */
    suspend fun savePreparedExport(path: String, destination: Uri) = withContext(Dispatchers.IO) {
        val source = File(path)
        try {
            check(source.isFile) { "临时文件已失效，请重新导出" }
            val output = checkNotNull(context.contentResolver.openOutputStream(destination, "w")) { "无法写入选择的位置" }
            output.use { sink -> source.inputStream().use { it.copyTo(sink) } }
            showFeedback("文件已保存到你选择的位置。手动导出的副本需自行保管和删除。")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showFeedback("保存失败：${e.message ?: "请重新选择保存位置"}")
        } finally {
            source.delete()
        }
    }

    suspend fun cancelPreparedExport(path: String?) = withContext(Dispatchers.IO) {
        path?.let { File(it).delete() }
        showFeedback("已取消导出")
    }

    private fun showFeedback(message: String) {
        _maintenanceMessage.value = message
        showToast(message)
    }

    private fun exportDirectory(): File = File(context.cacheDir, EXPORT_CACHE_DIR).apply {
        check(isDirectory || mkdirs()) { "无法创建临时导出目录" }
        // A process killed while the system file picker is open can leave an unused copy.
        listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000L }?.forEach { it.delete() }
    }

    companion object { const val EXPORT_CACHE_DIR = "maintenance_exports" }
    
    /**
     * 显示Toast消息
     */
    private fun showToast(message: String) {
        viewModelScope.launch {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    
}
