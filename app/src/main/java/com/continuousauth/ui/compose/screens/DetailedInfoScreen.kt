package com.continuousauth.ui.compose.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.continuousauth.R
import com.continuousauth.monitor.SystemMonitor
import com.continuousauth.privacy.ConsentState
import com.continuousauth.privacy.DeletionState
import com.continuousauth.ui.MainViewModel
import com.continuousauth.ui.compose.components.LineChart
import com.continuousauth.ui.viewmodels.DetailedInfoViewModel
import kotlinx.coroutines.launch
import java.text.DecimalFormat

/**
 * 详细信息页面
 * 展示系统状态、性能指标、传输信息等详细数据
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailedInfoScreen(
    onNavigateBack: () -> Unit,
    mainViewModel: MainViewModel,
    viewModel: DetailedInfoViewModel = hiltViewModel()
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    
    // 收集状态
    val transmissionStatus by viewModel.transmissionStatus.collectAsStateWithLifecycle()
    val grpcStatus by viewModel.grpcStatus.collectAsStateWithLifecycle()
    val bufferStats by viewModel.bufferStats.collectAsStateWithLifecycle()
    val sensorInfo by viewModel.sensorInfo.collectAsStateWithLifecycle()
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val serverPolicy by viewModel.serverPolicy.collectAsStateWithLifecycle()
    val timeSyncStatus by viewModel.timeSyncStatus.collectAsStateWithLifecycle()
    val performanceMetrics by viewModel.performanceMetrics.collectAsStateWithLifecycle()
    val cpuHistory by viewModel.cpuHistory.collectAsStateWithLifecycle()
    val memoryHistory by viewModel.memoryHistory.collectAsStateWithLifecycle()
    val latencyHistory by viewModel.latencyHistory.collectAsStateWithLifecycle()
    val consentState by mainViewModel.consentState.observeAsState(initial = ConsentState.UNKNOWN)
    val deletionState by mainViewModel.deletionState.observeAsState(initial = DeletionState.IDLE)
    val wifiOnly by mainViewModel.uploadPolicyWiFiOnly.observeAsState(initial = false)
    var dataRetentionDays by remember { mutableIntStateOf(30) }

    LaunchedEffect(Unit) {
        dataRetentionDays = mainViewModel.getDataRetentionDays()
    }
    
    val decimalFormat = remember { DecimalFormat("#.##") }
    // 添加控制NTP时间同步卡片显示的变量
    var showNtpSyncCard by remember { mutableStateOf(true) }
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // gRPC连接状态卡片
            item {
                InfoCard(
                    title = "gRPC 状态",
                    icon = Icons.Default.Cloud
                ) {
                    InfoRow("连接端点", grpcStatus.endpoint.ifEmpty { "未连接" })
                    InfoRow("连接状态", 
                        when (grpcStatus.connectionState) {
                            SystemMonitor.ConnectionState.CONNECTED -> "已连接"
                            SystemMonitor.ConnectionState.CONNECTING -> "连接中..."
                            SystemMonitor.ConnectionState.DISCONNECTED -> "未连接"
                            SystemMonitor.ConnectionState.TRANSIENT_FAILURE -> "连接失败"
                        },
                        textColor = when (grpcStatus.connectionState) {
                            SystemMonitor.ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                            SystemMonitor.ConnectionState.CONNECTING -> Color(0xFFFF9800)
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                    InfoRow("ACK延迟", 
                        if (grpcStatus.lastAckLatencyMs > 0) "${grpcStatus.lastAckLatencyMs} ms" else "N/A"
                    )
                    InfoRow("发送总数", "${grpcStatus.totalPacketsSent}")
                    InfoRow("已确认数", "${grpcStatus.totalPacketsAcknowledged}")
                }
            }
            
            // 缓冲区统计卡片
            item {
                InfoCard(
                    title = "缓冲区统计",
                    icon = Icons.Default.Storage
                ) {
                    InfoRow("内存样本数", "${bufferStats.memorySamples}")
                    InfoRow("磁盘队列包数", "${bufferStats.packetsInDiskQueue}")
                    InfoRow("已发送数", "${bufferStats.totalSentCount}")
                    InfoRow("失败数", "${bufferStats.totalFailedCount}")
                    InfoRow("丢弃数", "${bufferStats.totalDiscardedCount}")
                    InfoRow("磁盘队列大小", "${decimalFormat.format(bufferStats.diskQueueSizeMB)} MB")
                }
            }
            // 条件显示NTP时间同步卡片
            if (showNtpSyncCard) {
                // NTP时间同步卡片
                item {
                    InfoCard(
                        title = "NTP时间同步",
                        icon = Icons.Default.Schedule
                    ) {
                        InfoRow("同步状态",
                            timeSyncStatus.syncStatus,
                            textColor = when (timeSyncStatus.syncStatus) {
                                "SUCCESS" -> Color(0xFF4CAF50)
                                "SYNCING" -> Color(0xFFFF9800)
                                "ERROR" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                        InfoRow("同步有效",
                            if (timeSyncStatus.isNtpSyncValid) "是" else "否",
                            textColor = if (timeSyncStatus.isNtpSyncValid)
                                Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                        )
                        InfoRow("NTP偏移量", "${timeSyncStatus.ntpOffsetMs} ms")
                        InfoRow("同步精度", "${timeSyncStatus.syncAccuracyMs} ms")
                        if (timeSyncStatus.lastSyncTime > 0) {
                            val timeSinceSync = (System.currentTimeMillis() - timeSyncStatus.lastSyncTime) / 1000
                            InfoRow("上次同步", "${timeSinceSync} 秒前")
                        }
                    }
                }
            }

            
            // 传感器信息卡片
            item {
                InfoCard(
                    title = "传感器信息",
                    icon = Icons.Default.Sensors
                ) {
                    sensorInfo.forEach { (sensorType, info) ->
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = sensorType,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            InfoRow("硬件最大采样率", "${decimalFormat.format(info.hardwareMaxSamplingRateHz)} Hz")
                            InfoRow("当前采样率", "${decimalFormat.format(info.currentSamplingRateHz)} Hz")
                            InfoRow("实际采样率", "${decimalFormat.format(info.actualSamplingRateHz)} Hz")
                            InfoRow("FIFO大小", "${info.fifoMaxEventCount}")
                            InfoRow("供应商", info.vendor)
                            InfoRow("功耗", "${decimalFormat.format(info.power)} mA")
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
            
            // 设备与密钥库信息卡片
            item {
                InfoCard(
                    title = "设备与密钥库",
                    icon = Icons.Default.Security
                ) {
                    InfoRow("设备型号", deviceInfo.deviceModel)
                    InfoRow("制造商", deviceInfo.deviceManufacturer)
                    InfoRow("Android版本", deviceInfo.androidVersion)
                    InfoRow("Keystore Provider", deviceInfo.keystoreProvider)
                    InfoRow("StrongBox支持", 
                        if (deviceInfo.strongBoxSupported) "是" else "否",
                        textColor = if (deviceInfo.strongBoxSupported) 
                            Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                    )
                    InfoRow("当前密钥版本", deviceInfo.currentKeyVersion)
                    InfoRow("密钥算法", deviceInfo.keyAlgorithm)
                    InfoRow("密钥轮换计划", 
                        if (deviceInfo.keyRotationScheduled) "已计划" else "未计划"
                    )
                }
            }
            
            // 加密状态卡片
            item {
                InfoCard(
                    title = "加密状态",
                    icon = Icons.Default.Lock
                ) {
                    val encryptionStatus = viewModel.encryptionStatus.collectAsStateWithLifecycle().value
                    
                    InfoRow(
                        "加密方案",
                        encryptionStatus.encryptionAlgorithm.ifBlank { "未知" }
                    )
                    InfoRow(
                        "密钥管理",
                        encryptionStatus.keyProvider.ifBlank { "未知" }
                    )
                    InfoRow("安全锁状态", 
                        if (encryptionStatus.isSecurityLocked) "已锁定" else "正常",
                        textColor = if (encryptionStatus.isSecurityLocked) 
                            MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
                    )
                    if (encryptionStatus.consecutiveFailures > 0) {
                        InfoRow("连续失败次数", 
                            "${encryptionStatus.consecutiveFailures}/${encryptionStatus.maxFailuresThreshold}",
                            textColor = if (encryptionStatus.consecutiveFailures > encryptionStatus.maxFailuresThreshold / 2) 
                                Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    InfoRow("服务器公钥", 
                        if (encryptionStatus.hasServerPublicKey) "已配置" else "未配置",
                        textColor = if (encryptionStatus.hasServerPublicKey) 
                            Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                    )
                    InfoRow(
                        "DEK密钥ID",
                        if (encryptionStatus.currentDekKeyId.isNotBlank()) encryptionStatus.currentDekKeyId else "未生成"
                    )
                    InfoRow("包序列号", encryptionStatus.packetSequenceNumber.toString())
                    InfoRow("密钥轮换次数", deviceInfo.keyRotationCount.toString())
                }
            }
            
            // 隐私与传输策略
            item {
                ConsentStatusCard(
                    consentState = consentState,
                    onGrantConsent = { mainViewModel.grantPrivacyConsent() }
                )
            }

            item {
                DataRetentionCard(
                    retentionDays = dataRetentionDays,
                    onRetentionDaysChange = { days ->
                        dataRetentionDays = days
                        mainViewModel.setDataRetentionDays(days)
                    }
                )
            }

            item {
                TransmissionPolicyCard(
                    wifiOnly = wifiOnly,
                    onToggleWifiOnly = { isChecked ->
                        mainViewModel.setUploadPolicyWiFiOnly(isChecked)
                    }
                )
            }
            
            // 服务器策略卡片
            item {
                InfoCard(
                    title = "服务器策略",
                    icon = Icons.Default.Policy
                ) {
                    InfoRow("策略版本", serverPolicy.version)
                    InfoRow("快速模式时长", "${serverPolicy.fastModeDurationSeconds} 秒")
                    InfoRow("异常阈值", decimalFormat.format(serverPolicy.anomalyThreshold))
                    InfoRow("传输策略", serverPolicy.transmissionStrategy)
                    
                    // 采样率信息
                    Text(
                        text = "采样率配置:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    serverPolicy.samplingRates.forEach { (sensor, rate) ->
                        InfoRow("  $sensor", "${decimalFormat.format(rate)} Hz")
                    }
                    
                    // JSON展示（可展开）
                    var showJson by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { showJson = !showJson },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(if (showJson) "隐藏策略JSON" else "显示策略JSON")
                    }
                    
                    AnimatedVisibility(visible = showJson) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = serverPolicy.policyJson,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }
            
            // 性能图表卡片
            item {
                InfoCard(
                    title = "性能监控",
                    icon = Icons.Default.Analytics
                ) {
                    // CPU使用率
                    Text(
                        text = "本App CPU使用率 (${decimalFormat.format(performanceMetrics.cpuUsagePercent)}%)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (cpuHistory.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .padding(vertical = 8.dp)
                        ) {
                            LineChart(
                                data = cpuHistory,
                                modifier = Modifier.fillMaxSize(),
                                lineColor = MaterialTheme.colorScheme.primary,
                                maxValue = 100f
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 内存使用率
                    Text(
                        text = "本App内存使用 (${performanceMetrics.memoryUsedMB}MB / ${performanceMetrics.memoryTotalMB}MB)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (memoryHistory.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .padding(vertical = 8.dp)
                        ) {
                            LineChart(
                                data = memoryHistory,
                                modifier = Modifier.fillMaxSize(),
                                lineColor = MaterialTheme.colorScheme.secondary,
                                maxValue = 100f
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 上传延迟
                    Text(
                        text = "平均上传延迟 (${performanceMetrics.averageUploadLatencyMs} ms)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (latencyHistory.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .padding(vertical = 8.dp)
                        ) {
                            LineChart(
                                data = latencyHistory.map { it.toFloat() },
                                modifier = Modifier.fillMaxSize(),
                                lineColor = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    
                    // 其他性能指标
                    InfoRow("电池电量", "${performanceMetrics.batteryLevel}%")
                    InfoRow("设备温度", "${decimalFormat.format(performanceMetrics.temperatureCelsius)}°C")
                }
            }
            
            // 手动操作卡片
            item {
                InfoCard(
                    title = "手动操作",
                    icon = Icons.Default.TouchApp
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 触发快速模式
                        OutlinedButton(
                            onClick = { 
                                scope.launch {
                                    viewModel.triggerFastMode()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Speed, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("触发快速模式")
                        }
                        
                        // 清空本地队列
                        OutlinedButton(
                            onClick = { 
                                scope.launch {
                                    viewModel.clearLocalQueue()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("清空本地队列")
                        }
                        
                        // 导出pending数据
                        OutlinedButton(
                            onClick = { 
                                scope.launch {
                                    viewModel.exportPendingData()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导出待处理数据")
                        }
                        
                        // 强制密钥轮换
                        OutlinedButton(
                            onClick = { 
                                scope.launch {
                                    viewModel.forceKeyRotation()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.VpnKey, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("强制密钥轮换")
                        }
                        
                        // 更新服务器公钥
                        OutlinedButton(
                            onClick = { 
                                scope.launch {
                                    viewModel.updateServerPublicKey()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PublishedWithChanges, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("更新服务器公钥")
                        }
                        
                        // 导出调试日志
                        OutlinedButton(
                            onClick = { 
                                scope.launch {
                                    viewModel.exportDebugLog()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导出调试日志")
                        }
                    }
                }
            }
        }

        if (deletionState == DeletionState.IN_PROGRESS) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.padding(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("正在删除数据...", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/**
 * 信息卡片组件
 */
@Composable
fun InfoCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            content()
        }
    }
}

/**
 * 信息行组件
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.5f),
            textAlign = TextAlign.End

        )
    }
}

@Composable
private fun ConsentStatusCard(
    consentState: ConsentState,
    onGrantConsent: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (consentState) {
                ConsentState.GRANTED -> Color(0xFF4CAF50).copy(alpha = 0.1f)
                ConsentState.WITHDRAWN -> Color(0xFFFF5252).copy(alpha = 0.1f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (consentState) {
                        ConsentState.GRANTED -> Icons.Default.CheckCircle
                        ConsentState.WITHDRAWN -> Icons.Default.Cancel
                        else -> Icons.Default.Info
                    },
                    contentDescription = null,
                    tint = when (consentState) {
                        ConsentState.GRANTED -> Color(0xFF4CAF50)
                        ConsentState.WITHDRAWN -> Color(0xFFFF5252)
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "同意状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when (consentState) {
                    ConsentState.GRANTED -> "您已同意数据收集与使用"
                    ConsentState.WITHDRAWN -> "您已撤回同意，数据已删除"
                    ConsentState.NOT_GRANTED -> "您尚未同意数据收集"
                    else -> "同意状态未知"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (consentState == ConsentState.NOT_GRANTED) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onGrantConsent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("同意并开始使用")
                }
            }
        }
    }
}

@Composable
private fun DataRetentionCard(
    retentionDays: Int,
    onRetentionDaysChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "数据保留期限",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "本地缓存数据将在 $retentionDays 天后自动删除",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Slider(
                value = retentionDays.toFloat(),
                onValueChange = { onRetentionDaysChange(it.toInt()) },
                valueRange = 1f..365f,
                steps = 29
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("1天", style = MaterialTheme.typography.bodySmall)
                Text("$retentionDays 天", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("365天", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TransmissionPolicyCard(
    wifiOnly: Boolean,
    onToggleWifiOnly: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "传输策略",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "仅通过 Wi-Fi 上传",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (wifiOnly) "启用（仅在WiFi下上传）" else "禁用（使用所有网络）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = onToggleWifiOnly
                )
            }
        }
    }
}
