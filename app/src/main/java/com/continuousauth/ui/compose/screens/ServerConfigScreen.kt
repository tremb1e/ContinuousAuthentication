package com.continuousauth.ui.compose.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import com.continuousauth.R
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.continuousauth.ui.MainViewModel
import com.continuousauth.ui.theme.ExtendedColors
import com.continuousauth.network.ConnectionStatus
import com.continuousauth.network.TransportMode
import com.continuousauth.privacy.ConsentState
import com.continuousauth.privacy.DeletionState
import com.continuousauth.storage.QueueStats
import com.continuousauth.utils.Constant
import com.continuousauth.utils.SpUtils
import com.continuousauth.monitor.SystemMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 服务器配置页面
 * 美观的服务器设置和连接管理界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConfigScreen(viewModel: MainViewModel) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    
    // 观察 ViewModel 状态
    val connectionStatus by viewModel.connectionStatus.observeAsState(ConnectionStatus.DISCONNECTED)
    val isCollectionRunning by viewModel.isCollectionRunning.observeAsState(false)
    val isEncryptedUploading by viewModel.isEncryptedUploading.observeAsState(false)
    val userId by viewModel.userId.observeAsState("")
    val sessionId by viewModel.sessionId.observeAsState(null)
    val sessionStartTime by viewModel.sessionStartTime.observeAsState(0L)
    val sessionDuration by viewModel.sessionDuration.observeAsState("00:00")
    val serverTestResult by viewModel.serverTestResult.observeAsState(null)
    val transmissionStats by viewModel.transmissionStats.observeAsState(null)
    val fileQueueStats by viewModel.fileQueueStats.observeAsState(null)
    
    // 本地状态
    var serverIp by remember {
        mutableStateOf(SpUtils.decodeString(Constant.SERVER_IP, "192.168.1.100"))
    }
    var serverPort by remember {
        mutableStateOf(SpUtils.decodeString(Constant.SERVER_PORT, "50051"))
    }
    var isTestingConnection by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val saved = viewModel.getServerConfig()
        val displayHost = if (saved.scheme == "https") "https://${saved.host}" else saved.host
        serverIp = displayHost
        serverPort = saved.port.toString()
    }

    // 观察隐私相关状态
    val consentState by viewModel.consentState.observeAsState(initial = ConsentState.UNKNOWN)
    val deletionState by viewModel.deletionState.observeAsState(initial = DeletionState.IDLE)
    // 本地状态
    var showWithdrawDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var dataRetentionDays by remember { mutableIntStateOf(30) }

    val transmissionStatus by viewModel.transmissionStatus.collectAsStateWithLifecycle()
    val timeSyncStatus by viewModel.timeSyncStatus.collectAsStateWithLifecycle()
    // 添加控制NTP时间同步卡片显示的变量
    var showNtpSyncCard by remember { mutableStateOf(true) }
    // 添加控制传输状态卡片显示的变量
    var showTransmissionStatusCard by remember { mutableStateOf(true) }
    // 撤回同意确认对话框
    if (showWithdrawDialog) {
        WithdrawConsentDialog(
            onConfirm = {
                scope.launch {
                    viewModel.withdrawConsentAndDeleteData()
                }
                showWithdrawDialog = false
            },
            onDismiss = { showWithdrawDialog = false }
        )
    }

    // 隐私政策对话框
    if (showPrivacyPolicy) {
        PrivacyPolicyDialog(
            onDismiss = { showPrivacyPolicy = false }
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 连接状态卡片
            ConnectionStatusCard(connectionStatus, serverIp, serverPort)
            
            // ID 信息卡片（仅显示用户ID）
            IdentificationCard(
                userId = userId,
                onCopyUserId = {
                    clipboardManager.setText(AnnotatedString(userId))
                }
            )
            
            // 服务器配置卡片
            ServerSettingsCard(
                serverIp = serverIp,
                serverPort = serverPort,
                onIpChange = { serverIp = it },
                onPortChange = { serverPort = it },
                isTestingConnection = isTestingConnection,
                serverTestResult = serverTestResult,
                onTestConnection = {
                    isTestingConnection = true
                    val parsedPort = serverPort.toIntOrNull()
                    viewModel.saveServerConfig(serverIp, parsedPort)
                    viewModel.testServerConnection(serverIp, serverPort)
                    scope.launch {
                        delay(3500) // 等待测试完成
                        isTestingConnection = false
                    }
                }
            )
            
            // 加密数据上传控制卡片
            EncryptedUploadControlCard(
                isEncryptedUploading = isEncryptedUploading,
                connectionStatus = connectionStatus,
                sessionId = sessionId,
                sessionStartTime = sessionStartTime,
                sessionDuration = sessionDuration,
                transmissionStats = transmissionStats,
                fileQueueStats = fileQueueStats,
                onToggleUpload = {
                    if (isEncryptedUploading) {
                        viewModel.stopEncryptedUpload()
                    } else {
                        val parsedPort = serverPort.toIntOrNull()
                        viewModel.saveServerConfig(serverIp, parsedPort)
                        viewModel.startEncryptedUpload()
                    }
                }
            )
            
            // 文件队列统计卡片
            fileQueueStats?.let { stats ->
                FileQueueCard(
                    queueStats = stats,
                    onClearQueue = { viewModel.clearFileQueue() }
                )
            }
            // 数据管理卡片
            DataManagementCard(
                onWithdrawConsent = { showWithdrawDialog = true },
                onViewPrivacyPolicy = { showPrivacyPolicy = true },
                isDeleting = deletionState == DeletionState.IN_PROGRESS
            )
            // 加密状态卡片
            EncryptionStatusCard(transmissionStatus)
            if(showTransmissionStatusCard){
                // 传输状态卡片
                InfoCard(
                    title = "传输状态",
                    icon = Icons.AutoMirrored.Filled.Send
                ) {
                    InfoRow("压缩算法", transmissionStatus.compression)
                    InfoRow("连接状态",
                        if (transmissionStatus.isConnected) "已连接" else "未连接",
                        textColor = if (transmissionStatus.isConnected)
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    InfoRow("上传队列", "${transmissionStatus.uploadQueueSize} 个数据包")
                    val transportDisplay = transportDisplay(transmissionStatus)
                    InfoRow(
                        "传输通道",
                        transportDisplay.first,
                        textColor = transportDisplay.second
                    )
                    if (!transmissionStatus.transportError.isNullOrBlank()) {
                        Text(
                            text = transmissionStatus.transportError ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // 条件显示NTP时间同步卡片
            if (showNtpSyncCard) {
                // NTP时间同步卡片
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
    }
}

/**
 * 加密状态卡片
 */
@Composable
private fun EncryptionStatusCard(transmissionStatus: SystemMonitor.TransmissionStatus) {
    val (transportLabel, transportColor) = transportDisplay(transmissionStatus)
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "加密状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            PrivacyInfoRow("加密算法", "AES-256-GCM (固定派生密钥)")
            PrivacyInfoRow("密钥管理", "SHA-256(\"Continuous_Authentication\") 派生对称密钥")
            PrivacyInfoRow("传输安全", transportLabel, textColor = transportColor)
            PrivacyInfoRow("数据压缩", "LZ4 帧压缩（先压缩后加密）")
            if (transmissionStatus.transportMode == TransportMode.HTTP &&
                transmissionStatus.transportLastAttemptMs > 0
            ) {
                val note = when {
                    transmissionStatus.transportPreferredScheme.lowercase() == "http" ->
                        "当前按服务器配置使用HTTP，链路为明文"
                    transmissionStatus.transportDowngraded ->
                        "TLS不可用或握手失败，已降级为HTTP明文"
                    else -> "当前通道为HTTP明文"
                }
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
/**
 * 信息行
 */
@Composable
private fun PrivacyInfoRow(
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = textColor,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun transportDisplay(
    transmissionStatus: SystemMonitor.TransmissionStatus
): Pair<String, Color> {
    val defaultColor = MaterialTheme.colorScheme.onSurface
    if (transmissionStatus.transportLastAttemptMs == 0L) {
        return "未检测到上传" to defaultColor
    }

    return when (transmissionStatus.transportMode) {
        TransportMode.HTTP -> {
            val reason = when {
                transmissionStatus.transportPreferredScheme.lowercase() == "http" -> "（按配置使用HTTP）"
                transmissionStatus.transportDowngraded -> "（TLS不可用/失败）"
                else -> ""
            }
            "HTTP 明文$reason" to MaterialTheme.colorScheme.error
        }

        TransportMode.HTTPS -> {
            val version = transmissionStatus.transportTlsVersion
                ?: if (transmissionStatus.transportTlsCapable) "TLS 1.2+" else "TLS"
            val protocol = transmissionStatus.transportNegotiatedProtocol
            val detail = listOfNotNull(version, protocol)
                .filter { it.isNotBlank() }
                .joinToString(" / ")
            val label = if (detail.isBlank()) "TLS" else "TLS $detail"
            label to MaterialTheme.colorScheme.primary
        }
    }
}
/**
 * 连接状态卡片
 */
@Composable
fun ConnectionStatusCard(
    connectionStatus: ConnectionStatus,
    serverIp: String,
    serverPort: String
) {
    val statusColor = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> ExtendedColors.success
        ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> ExtendedColors.warning
        ConnectionStatus.DISCONNECTED -> ExtendedColors.error
        else -> Color.Gray
    }
    
    val context = LocalContext.current
    val statusText = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> context.getString(R.string.connected)
        ConnectionStatus.CONNECTING -> context.getString(R.string.connecting)
        ConnectionStatus.RECONNECTING -> context.getString(R.string.reconnecting)
        ConnectionStatus.DISCONNECTED -> context.getString(R.string.disconnected)
        else -> context.getString(R.string.not_available)
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.1f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            statusColor.copy(alpha = 0.05f),
                            statusColor.copy(alpha = 0.15f)
                        )
                    )
                )
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 动画连接图标
                AnimatedConnectionIcon(
                    isConnected = connectionStatus == ConnectionStatus.CONNECTED,
                    color = statusColor
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
                
                if (connectionStatus == ConnectionStatus.CONNECTED) {
                    Text(
                        text = "$serverIp:$serverPort",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * 动画连接图标
 */
@Composable
fun AnimatedConnectionIcon(
    isConnected: Boolean,
    color: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "connection")
    
    if (isConnected) {
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        
        Box(contentAlignment = Alignment.Center) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .size(60.dp + (index * 20).dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.1f - index * 0.03f))
                )
            }
            
            Icon(
                imageVector = Icons.Filled.CloudDone,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = color
            )
        }
    } else {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = color
        )
    }
}

/**
 * 身份信息卡片（仅显示用户ID）
 */
@Composable
fun IdentificationCard(
    userId: String,
    onCopyUserId: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.identity_label),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            // 用户ID
            IdRow(
                icon = Icons.Outlined.Person,
                label = stringResource(R.string.user_id_label),
                value = userId,
                onCopy = onCopyUserId
            )
        }
    }
}

/**
 * ID 行组件
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun IdRow(
    icon: ImageVector,
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    var copied by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        
        IconButton(
            onClick = {
                onCopy()
                copied = true
            }
        ) {
            AnimatedContent(
                targetState = copied,
                transitionSpec = {
                    scaleIn() + fadeIn() with scaleOut() + fadeOut()
                },
                label = "copy"
            ) { isCopied ->
                Icon(
                    imageVector = if (isCopied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.copy),
                    tint = if (isCopied) ExtendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        LaunchedEffect(copied) {
            if (copied) {
                delay(2000)
                copied = false
            }
        }
    }
}

/**
 * 服务器设置卡片
 */
@Composable
fun ServerSettingsCard(
    serverIp: String,
    serverPort: String,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    isTestingConnection: Boolean,
    serverTestResult: String?,
    onTestConnection: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.server_settings),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            // IP 地址输入
            OutlinedTextField(
                value = serverIp,
                onValueChange = onIpChange,
                label = { Text(stringResource(R.string.server_ip_label)) },
                leadingIcon = {
                    Icon(Icons.Outlined.Computer, contentDescription = null)
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            
            // 端口输入
            OutlinedTextField(
                value = serverPort,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.port_label)) },
                leadingIcon = {
                    Icon(Icons.Outlined.Router, contentDescription = null)
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            
            // 测试连接按钮
            Button(
                onClick = onTestConnection,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isTestingConnection,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                if (isTestingConnection) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.NetworkCheck,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isTestingConnection) stringResource(R.string.testing_server) else stringResource(R.string.detect_server),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            
            // 显示测试结果
            serverTestResult?.let { result ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.startsWith("✓")) 
                            ExtendedColors.success.copy(alpha = 0.1f)
                        else 
                            ExtendedColors.error.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                        color = if (result.startsWith("✓")) 
                            ExtendedColors.success
                        else 
                            ExtendedColors.error
                    )
                }
            }
        }
    }
}

/**
 * 加密数据上传控制卡片
 */
@Composable
fun EncryptedUploadControlCard(
    isEncryptedUploading: Boolean,
    connectionStatus: ConnectionStatus,
    sessionId: String?,
    sessionStartTime: Long,
    sessionDuration: String,
    transmissionStats: com.continuousauth.ui.TransmissionStats?,
    fileQueueStats: QueueStats?,
    onToggleUpload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEncryptedUploading) 
                ExtendedColors.success.copy(alpha = 0.1f)
            else 
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                //加密数据上传
                Text(
                    text = stringResource(R.string.encrypted_data_upload),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // 会话信息和上传状态
            if (isEncryptedUploading && sessionId != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.current_session_info),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        
                        // Session ID
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Session ID:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = sessionId.take(8) + "...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        
                        // 开始时间
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.start_time_label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (sessionStartTime > 0) {
                                    val dateFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                    dateFormat.format(java.util.Date(sessionStartTime))
                                } else "--:--:--",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        
                        // 已采集时长
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.collection_duration_label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = sessionDuration,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = ExtendedColors.success
                            )
                        }
                        
                        // 分隔线
                        Divider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        
                        // 传输状态信息
                        Text(
                            text = "加密上传状态",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        // 实时上传统计
                        transmissionStats?.let { stats ->
                            // 已发送数据包
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "已发送:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${stats.packetsSent} 个数据包",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = ExtendedColors.success
                                )
                            }
                            
                            // 待发送数据包
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "待发送:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${stats.packetsPending} 个数据包",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (stats.packetsPending > 0) ExtendedColors.warning else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            // 最近ACK延迟
                            stats.lastAckLatency?.let { latency ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "响应延迟:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "$latency ms",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            
                            // 快速模式状态
                            if (stats.isFastMode) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = ExtendedColors.warning.copy(alpha = 0.2f)
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Speed,
                                            contentDescription = null,
                                            tint = ExtendedColors.warning,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "快速模式已启用",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = ExtendedColors.warning
                                        )
                                    }
                                }
                            }
                        }
                        
                        // 文件队列状态
                        fileQueueStats?.let { queueStats ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "待上传队列:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${queueStats.pendingPackets}/${queueStats.totalPackets} (${formatBytes(queueStats.totalSizeBytes)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            // 上传进度条
                            if (queueStats.totalPackets > 0) {
                                val progress = queueStats.uploadedPackets.toFloat() / queueStats.totalPackets
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = ExtendedColors.success,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                        
                        // 加密状态提示
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = ExtendedColors.success,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "数据已加密传输",
                                style = MaterialTheme.typography.labelSmall,
                                color = ExtendedColors.success,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            
            // 开始/停止按钮组
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 开始按钮
                Button(
                    onClick = {
                        if (!isEncryptedUploading) {
                            onToggleUpload()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    enabled = !isEncryptedUploading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ExtendedColors.success
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.start_button),
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 16.sp
                    )
                }
                
                // 停止按钮
                Button(
                    onClick = {
                        if (isEncryptedUploading) {
                            onToggleUpload()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    enabled = isEncryptedUploading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ExtendedColors.error
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.stop_button),
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

/**
 * 状态芯片
 */
@Composable
fun StatusChip(
    icon: ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

/**
 * 文件队列统计卡片
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileQueueCard(
    queueStats: QueueStats,
    onClearQueue: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = stringResource(R.string. file_queue),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.file_queue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                // 清空按钮
                if (queueStats.totalPackets > 0) {
                    TextButton(
                        onClick = onClearQueue,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.clear_queue),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.clear_queue))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 统计信息网格
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                //总数据包
                StatusChip(
                    icon = Icons.Default.Inventory,
                    label = stringResource(R.string.total_packets),
                    value = queueStats.totalPackets.toString(),
                    color = MaterialTheme.colorScheme.primary
                )
                //待上传
                StatusChip(
                    icon = Icons.Default.Schedule,
                    label = stringResource(R.string.pending_upload),
                    value = queueStats.pendingPackets.toString(),
                    color = if (queueStats.pendingPackets > 0) ExtendedColors.warning else Color.Gray
                )
                //已上传
                StatusChip(
                    icon = Icons.Default.CloudDone,
                    label = stringResource(R.string.uploaded),
                    value = queueStats.uploadedPackets.toString(),
                    color = ExtendedColors.success
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 存储空间信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatusChip(
                    icon = Icons.Default.Storage,
                    label = stringResource(R.string.queue_size_label),
                    value = formatBytes(queueStats.totalSizeBytes),
                    color = MaterialTheme.colorScheme.secondary
                )
                StatusChip(
                    icon = Icons.Default.BrokenImage,
                    label = stringResource(R.string.corrupted_packets),
                    value = queueStats.corruptedPackets.toString(),
                    color = if (queueStats.corruptedPackets > 0) ExtendedColors.error else Color.Gray
                )
            }
            
            // 进度条
            if (queueStats.totalPackets > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                
                val progress = if (queueStats.totalPackets > 0) {
                    queueStats.uploadedPackets.toFloat() / queueStats.totalPackets
                } else 0f
                
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = ExtendedColors.success,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                
                Text(
                    text = stringResource(R.string.upload_progress_format, (progress * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
/**
 * 数据管理卡片
 */
@Composable
private fun DataManagementCard(
    onWithdrawConsent: () -> Unit,
    onViewPrivacyPolicy: () -> Unit,
    isDeleting: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "数据管理",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 撤回同意按钮
            OutlinedButton(
                onClick = onWithdrawConsent,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isDeleting,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFFF5252)
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("撤回同意并删除所有数据")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 查看隐私政策按钮
            OutlinedButton(
                onClick = onViewPrivacyPolicy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Policy, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("查看隐私政策")
            }
        }
    }
}

/**
 * 隐私政策对话框
 */
@Composable
private fun PrivacyPolicyDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("隐私政策")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = """
                        数据收集与使用说明
                        
                        1. 数据收集类型
                        • 传感器数据：加速度计、陀螺仪、磁力计
                        • 设备信息：设备型号、系统版本
                        • 应用使用情况：前台应用信息（已加密）
                        
                        2. 数据用途
                        • 用于持续身份认证研究
                        • 改进认证算法准确性
                        • 学术研究与分析
                        
                        3. 数据保护
                        • 所有数据均采用 AES-256-GCM 加密
                        • 使用 HMAC 保护敏感标识符
                        • 本地缓存自动清理
                        • 服务器端安全存储
                        
                        4. 数据共享
                        • 不与第三方共享原始数据
                        • 仅分享聚合统计信息
                        • 严格遵守数据最小化原则
                        
                        5. 用户权利
                        • 您可以随时撤回同意
                        • 撤回后将删除所有相关数据
                        • 支持数据导出请求
                        • 支持数据修正请求
                        
                        6. 数据保留
                        • 本地缓存：用户可设置1-365天
                        • 服务器端：遵循研究协议要求
                        • 自动清理过期数据
                        
                        7. 联系方式
                        邮箱：privacy@continuousauth.com
                        
                        最后更新：2024年1月
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
/**
 * 撤回同意确认对话框
 */
@Composable
private fun WithdrawConsentDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFF5252)
            )
        },
        title = {
            Text("确认撤回同意")
        },
        text = {
            Column {
                Text(
                    text = "撤回同意将会：",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("• 立即停止所有数据采集")
                Text("• 删除所有本地缓存数据")
                Text("• 向服务器发送删除请求")
                Text("• 清除所有个人信息")
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "此操作无法撤销！",
                    color = Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFFFF5252)
                )
            ) {
                Text("确认删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
/**
 * 格式化字节数为可读字符串
 */
private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
