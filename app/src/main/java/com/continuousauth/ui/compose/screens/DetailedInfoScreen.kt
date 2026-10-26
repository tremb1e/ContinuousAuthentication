package com.continuousauth.ui.compose.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.continuousauth.monitor.SystemMonitor
import com.continuousauth.network.ConnectionStatus
import com.continuousauth.network.TransportMode
import com.continuousauth.ui.AuthDecision
import com.continuousauth.ui.MainViewModel
import com.continuousauth.ui.compose.components.ExpandableSection
import com.continuousauth.ui.compose.components.LineChart
import com.continuousauth.ui.viewmodels.DetailedInfoViewModel
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Optional technical information is grouped and hidden until requested. */
@Composable
fun DetailedInfoScreen(
    onNavigateBack: () -> Unit,
    mainViewModel: MainViewModel,
    viewModel: DetailedInfoViewModel = hiltViewModel()
) {
    val transmission by viewModel.transmissionStatus.collectAsStateWithLifecycle()
    val grpc by viewModel.grpcStatus.collectAsStateWithLifecycle()
    val buffer by viewModel.bufferStats.collectAsStateWithLifecycle()
    val sensors by viewModel.sensorInfo.collectAsStateWithLifecycle()
    val device by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val policy by viewModel.serverPolicy.collectAsStateWithLifecycle()
    val timeSync by viewModel.timeSyncStatus.collectAsStateWithLifecycle()
    val performance by viewModel.performanceMetrics.collectAsStateWithLifecycle()
    val cpuHistory by viewModel.cpuHistory.collectAsStateWithLifecycle()
    val memoryHistory by viewModel.memoryHistory.collectAsStateWithLifecycle()
    val latencyHistory by viewModel.latencyHistory.collectAsStateWithLifecycle()
    val encryption by viewModel.encryptionStatus.collectAsStateWithLifecycle()
    val auth by mainViewModel.authUiState.collectAsStateWithLifecycle()
    val connection by mainViewModel.connectionStatus.observeAsState(ConnectionStatus.DISCONNECTED)
    val uploading by mainViewModel.isEncryptedUploading.observeAsState(false)
    val sessionId by mainViewModel.sessionId.observeAsState(null)
    val sessionStart by mainViewModel.sessionStartTime.observeAsState(0L)
    val sessionDuration by mainViewModel.sessionDuration.observeAsState("00:00")
    val uploadIdentity by mainViewModel.uploadIdentity.observeAsState("")
    val debugInfo by mainViewModel.debugInfo.observeAsState("")
    val decimal = remember { DecimalFormat("0.##") }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("details_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("需要时，再了解更多", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("这里保留运行细节，方便查看或排查问题。日常使用无需调整这些信息。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))

        ExpandableSection("auth_details", "认证运行信息", if (auth.authActive) "认证已开始" else "认证未运行", Icons.Default.VerifiedUser) {
            DetailNote("这些是服务器返回的技术信息。请以首页的结果和提示了解当前状态。")
            InfoRow("认证状态", if (auth.authActive) "正在认证" else "未运行")
            InfoRow("最近一次结果", when (auth.lastDecision) { AuthDecision.NORMAL -> "通过"; AuthDecision.ABNORMAL -> "不通过"; AuthDecision.UNKNOWN -> "尚无结果" })
            InfoRow("结果时间", formatDetailTimestamp(auth.lastUpdateTime))
            InfoRow("认证模型版本", auth.modelVersion.ifBlank { "尚未提供" })
            InfoRow("判断分数", if (auth.scoreValid) decimal.format(auth.lastScore) else "尚无有效结果")
            InfoRow("判断阈值", if (auth.scoreValid) decimal.format(auth.lastThreshold) else "尚无有效结果")
            InfoRow("数据窗口编号", if (auth.lastUpdateTime > 0) auth.lastWindowId.toString() else "尚无结果")
            InfoRow("数据窗口长度", if (auth.windowSizeSec > 0) "${decimal.format(auth.windowSizeSec)} 秒" else "尚未提供")
            InfoRow("判断周期", if (auth.decisionTimeSec > 0) "${decimal.format(auth.decisionTimeSec)} 秒" else "尚未提供")
            InfoRow("服务器处理延迟", auth.serverLatencyMs?.let { "$it 毫秒" } ?: "尚未提供")
            InfoRow("服务器结果时间", auth.serverTimestampMs?.let(::formatDetailTimestamp) ?: "尚未提供")
            if (auth.authMessage.isNotBlank()) InfoRow("认证服务说明", auth.authMessage)
            if (auth.lastDecisionMessage.isNotBlank()) InfoRow("最近结果说明", auth.lastDecisionMessage)
        }

        ExpandableSection("connection_details", "连接与传输", connectionLabel(connection), Icons.Default.Cloud) {
            InfoRow("连接状态", connectionLabel(connection))
            InfoRow("采集与上传", if (uploading) "运行中" else "已停止")
            InfoRow("连接地址", grpc.endpoint.ifBlank { "尚未连接" })
            InfoRow("上传连接状态", when (grpc.connectionState) {
                SystemMonitor.ConnectionState.CONNECTED -> "已连接"
                SystemMonitor.ConnectionState.CONNECTING -> "正在连接"
                SystemMonitor.ConnectionState.DISCONNECTED -> "未连接"
                SystemMonitor.ConnectionState.TRANSIENT_FAILURE -> "暂时连接失败"
            })
            InfoRow("服务器确认延迟（ACK）", if (grpc.lastAckLatencyMs > 0) "${grpc.lastAckLatencyMs} 毫秒" else "暂无数据")
            InfoRow("累计发送数据包", grpc.totalPacketsSent.toString())
            InfoRow("服务器已确认数据包", grpc.totalPacketsAcknowledged.toString())
            InfoRow("待上传数据包", transmission.uploadQueueSize.toString())
            InfoRow("上次上传时间", formatDetailTimestamp(transmission.lastUploadTime))
            InfoRow("数据压缩方式", transmission.compression.ifBlank { "尚未提供" })
            InfoRow("传输连接安全", transportLabel(transmission), textColor = if (transmission.transportLastAttemptMs > 0 && transmission.transportMode == TransportMode.HTTP) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            transmission.transportError?.takeIf { it.isNotBlank() }?.let { InfoRow("最近连接问题", it) }
            if (transmission.transportLastAttemptMs > 0 && transmission.transportMode == TransportMode.HTTP) {
                DetailNote(if (transmission.transportDowngraded) "加密连接不可用，当前已降为 HTTP 连接。传输连接本身不加密。" else "当前使用 HTTP 连接，传输连接本身不加密。")
            }
        }

        ExpandableSection("buffer_details", "缓存与数据队列", "查看待处理数据的数量", Icons.Default.Storage) {
            InfoRow("内存中暂存的样本", buffer.memorySamples.toString())
            InfoRow("磁盘中暂存的数据包", buffer.packetsInDiskQueue.toString())
            InfoRow("已发送", buffer.totalSentCount.toString())
            InfoRow("上传失败", buffer.totalFailedCount.toString())
            InfoRow("已丢弃", buffer.totalDiscardedCount.toString())
            InfoRow("磁盘队列占用", "${decimal.format(buffer.diskQueueSizeMB)} MB")
            DetailNote("缓存清理、导出和保存期限可以在“设置”中管理。")
        }

        ExpandableSection("time_details", "时间同步", syncLabel(timeSync.syncStatus), Icons.Default.Schedule) {
            DetailNote("时间同步帮助手机和服务器对齐记录的发生时间。")
            InfoRow("同步状态", syncLabel(timeSync.syncStatus))
            InfoRow("同步结果是否仍有效", if (timeSync.isNtpSyncValid) "有效" else "当前无有效同步")
            InfoRow("时间偏差（NTP）", "${timeSync.ntpOffsetMs} 毫秒")
            InfoRow("同步精度", "${timeSync.syncAccuracyMs} 毫秒")
            InfoRow("上次同步", formatDetailTimestamp(timeSync.lastSyncTime))
        }

        ExpandableSection("sensor_details", "传感器硬件", "查看手机传感器的能力和采样情况", Icons.Default.Sensors) {
            if (sensors.isEmpty()) DetailNote("尚未获取传感器信息。")
            sensors.forEach { (name, info) ->
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                InfoRow("硬件最大采样率", "${decimal.format(info.hardwareMaxSamplingRateHz)} Hz")
                InfoRow("设定采样率", "${decimal.format(info.currentSamplingRateHz)} Hz")
                InfoRow("实际采样率", "${decimal.format(info.actualSamplingRateHz)} Hz")
                InfoRow("硬件缓存容量（FIFO）", info.fifoMaxEventCount.toString())
                InfoRow("预留缓存容量", info.fifoReservedEventCount.toString())
                InfoRow("供应商", info.vendor.ifBlank { "未提供" })
                InfoRow("预计功耗", "${decimal.format(info.power)} mA")
                HorizontalDivider()
            }
        }

        ExpandableSection("device_security", "设备与数据加密", "设备信息、加密状态和密钥信息", Icons.Default.Lock) {
            InfoRow("设备型号", device.deviceModel)
            InfoRow("制造商", device.deviceManufacturer)
            InfoRow("Android 版本", device.androidVersion)
            HorizontalDivider()
            InfoRow("加密准备状态", if (encryption.isInitialized) "已准备" else "尚未准备")
            InfoRow("加密方式", encryption.encryptionAlgorithm.ifBlank { "尚未提供" })
            InfoRow("密钥管理方式", encryption.keyProvider.ifBlank { "尚未提供" })
            InfoRow("加密流程保护", if (encryption.isSecurityLocked) "已暂停加密，需要排查" else "未锁定")
            InfoRow("连续加密失败次数", "${encryption.consecutiveFailures}/${encryption.maxFailuresThreshold}")
            InfoRow("服务器公钥", if (encryption.hasServerPublicKey) "已配置" else "未配置")
            InfoRow("数据密钥标识（DEK）", encryption.currentDekKeyId.ifBlank { "尚未生成" })
            InfoRow("数据包序列号", encryption.packetSequenceNumber.toString())
            HorizontalDivider()
            InfoRow("系统密钥库（Keystore）", device.keystoreProvider.ifBlank { "尚未提供" })
            InfoRow("硬件密钥库支持（StrongBox）", if (device.strongBoxSupported) "支持" else "不支持")
            InfoRow("密钥版本", device.currentKeyVersion.ifBlank { "尚未提供" })
            InfoRow("密钥算法", device.keyAlgorithm.ifBlank { "尚未提供" })
            InfoRow("密钥更新计划", if (device.keyRotationScheduled) "已安排" else "未安排")
            InfoRow("密钥维护计数", device.keyRotationCount.toString())
        }

        ExpandableSection("server_policy", "服务器配置详情", if (policy.lastUpdated > 0) "已获取服务器策略" else "尚未同步服务器策略", Icons.Default.Policy) {
            InfoRow("策略标识", policy.policyId.ifBlank { "尚未分配" })
            InfoRow("策略版本", policy.policyVersion.ifBlank { "尚未同步" })
            InfoRow("更新时间", formatDetailTimestamp(policy.lastUpdated))
            InfoRow("批处理间隔", "${policy.batchIntervalMs} 毫秒")
            InfoRow("单个数据包大小上限", "${decimal.format(policy.maxPayloadSizeBytes / (1024f * 1024f))} MB")
            InfoRow("上传速率上限", "${decimal.format(policy.uploadRateLimit)} 包/秒")
            InfoRow("压缩方式", policy.compressionAlgorithm.ifBlank { "尚未提供" })
            InfoRow("批次触发阈值", policy.batchSizeThreshold.toString())
            InfoRow("服务器启用的传感器", policy.enabledSensors.joinToString().ifBlank { "使用默认设置" })
            Text("服务器采样率", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (policy.samplingRates.isEmpty()) DetailNote("服务器尚未提供单独的采样率配置。")
            else policy.samplingRates.forEach { (sensor, rate) -> InfoRow(sensor, "$rate Hz") }
            InfoRow("异常检测", if (policy.anomalyEnabled) "启用" else "未启用")
            if (policy.anomalyEnabled) {
                InfoRow("阈值倍数", decimal.format(policy.anomalyThresholdMultiplier))
                InfoRow("检测窗口", "${policy.anomalyWindowSizeSec} 秒")
                InfoRow("触发间隔", "${policy.anomalyCooldownSec} 秒")
            }
            ExpandableSection("policy_json", "原始策略内容", "供维护人员核对", Icons.Default.Code) {
                SelectionContainer { Text(policy.policyJson, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }

        ExpandableSection("performance", "运行与耗电", "处理器、内存、网络延迟和电量", Icons.Default.Analytics) {
            InfoRow("本应用处理器占用", "${decimal.format(performance.cpuUsagePercent)}%")
            DetailChart(cpuHistory, MaterialTheme.colorScheme.primary, 100f)
            InfoRow("本应用堆内存", "${performance.memoryUsedMB} / ${performance.memoryTotalMB} MB")
            DetailChart(memoryHistory, MaterialTheme.colorScheme.secondary, 100f)
            InfoRow("平均上传延迟", "${performance.averageUploadLatencyMs} 毫秒")
            val latency = latencyHistory.map { it.toFloat() }
            DetailChart(latency, MaterialTheme.colorScheme.tertiary, maxOf(1f, latency.maxOrNull() ?: 1f))
            InfoRow("电池电量", "${performance.batteryLevel}%")
            InfoRow("设备温度", "${decimal.format(performance.temperatureCelsius)} °C")
        }

        ExpandableSection("identifiers", "运行标识", "联系维护人员时可提供这些信息", Icons.Default.Fingerprint) {
            InfoRow("上传标识", uploadIdentity.ifBlank { "尚未生成" })
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(uploadIdentity))
                Toast.makeText(context, "上传标识已复制", Toast.LENGTH_SHORT).show()
            }, enabled = uploadIdentity.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("复制上传标识") }
            InfoRow("本次会话标识", sessionId?.takeIf { it.isNotBlank() } ?: "尚未开始")
            InfoRow("会话开始时间", formatDetailTimestamp(sessionStart))
            InfoRow("会话持续时间", sessionDuration)
        }

        ExpandableSection("manual_analysis", "手动统计（高级）", "保留原有的临时分段统计", Icons.Default.Timer) {
            val analysis = auth.authAnalysis
            DetailNote("用于观察一次手动选定的运行片段。关闭应用进程后不会保留；首页的最近 7 天认证记录会自动保存，不需要在这里开始统计。")
            InfoRow("统计状态", if (analysis.isRunning) "正在统计" else "未在统计")
            InfoRow("累计时间", formatDetailDuration(analysis.elapsedMs))
            InfoRow("通过次数", analysis.passCount.toString())
            InfoRow("不通过次数", analysis.failCount.toString())
            InfoRow("通过比例", if (analysis.totalCount > 0) "${decimal.format(analysis.passRatePercent)}%" else "尚无结果")
            OutlinedButton(
                onClick = { if (analysis.isRunning) mainViewModel.stopAuthAnalysis() else mainViewModel.startAuthAnalysis() },
                enabled = analysis.isRunning || auth.authActive,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text(if (analysis.isRunning) "结束本段统计" else "开始本段统计") }
            if (!auth.authActive && !analysis.isRunning) DetailNote("请先在“认证与分析”中开始认证。")
            if (analysis.logs.isEmpty()) DetailNote("暂无已结束的统计片段。")
            analysis.logs.forEach { log ->
                HorizontalDivider()
                Text(formatDetailTimestamp(log.startTimeMs), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                InfoRow("统计时长", formatDetailDuration(log.durationMs))
                InfoRow("通过 / 不通过", "${log.passCount} / ${log.failCount}")
                InfoRow("通过比例", if (log.passCount + log.failCount > 0) "${decimal.format(log.passRatePercent)}%" else "尚无结果")
            }
        }

        ExpandableSection("debug_details", "诊断信息", "供维护人员排查问题", Icons.Default.BugReport) {
            SelectionContainer { Text(debugInfo.ifBlank { "暂无诊断信息。可在设置的“维护工具”中打开调试信息。" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Kept as a compact optional information container for callers outside this screen. */
@Composable
fun InfoCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    ExpandableSection(sectionKey = title, title = title, summary = "点击查看", icon = icon, content = content)
}

/** Full values remain readable, including identifiers and large-font layouts. */
@Composable
fun InfoRow(label: String, value: String, textColor: Color = MaterialTheme.colorScheme.onSurface) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (maxWidth < 320.dp || fontScale > 1.25f) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = textColor)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = textColor, textAlign = TextAlign.End, modifier = Modifier.weight(1.5f))
            }
        }
    }
}

@Composable
private fun DetailNote(message: String) { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
private fun DetailChart(data: List<Float>, color: Color, maxValue: Float) {
    if (data.size >= 2) LineChart(data = data, modifier = Modifier.fillMaxWidth().height(96.dp), lineColor = color, maxValue = maxValue.coerceAtLeast(1f))
    else Text("正在等待足够的趋势数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun formatDetailTimestamp(timestamp: Long): String = if (timestamp <= 0) "暂无记录" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
private fun formatDetailDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0) / 1000
    return "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分 ${seconds % 60} 秒"
}
private fun connectionLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.CONNECTED -> "已连接"
    ConnectionStatus.CONNECTING -> "正在连接"
    ConnectionStatus.RECONNECTING -> "正在重新连接"
    ConnectionStatus.DISCONNECTED -> "未连接"
    else -> "连接状态暂不可用"
}
private fun syncLabel(status: String): String = when (status) {
    "SUCCESS" -> "已同步"
    "SYNCING" -> "正在同步"
    "ERROR" -> "同步未成功"
    "IDLE" -> "尚未同步"
    else -> status
}
private fun transportLabel(status: SystemMonitor.TransmissionStatus): String {
    if (status.transportLastAttemptMs == 0L) return "尚未检测到上传连接"
    return when (status.transportMode) {
        TransportMode.HTTP -> "HTTP（连接未加密）"
        TransportMode.HTTPS -> listOfNotNull("HTTPS", status.transportTlsVersion, status.transportNegotiatedProtocol).filter { it.isNotBlank() }.joinToString(" · ")
    }
}
