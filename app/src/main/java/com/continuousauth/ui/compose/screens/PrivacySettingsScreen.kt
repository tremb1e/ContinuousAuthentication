package com.continuousauth.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.continuousauth.network.ConnectionStatus
import com.continuousauth.ui.AuthDecision
import com.continuousauth.ui.ContinuousAuthUiState
import com.continuousauth.ui.MainViewModel
import com.continuousauth.ui.theme.ExtendedColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 持续认证页面
 * 展示服务端推理结果与实时状态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: MainViewModel
) {
    val authUiState by viewModel.authUiState.collectAsStateWithLifecycle()
    val isEncryptedUploading by viewModel.isEncryptedUploading.observeAsState(false)
    val connectionStatus by viewModel.connectionStatus.observeAsState(ConnectionStatus.DISCONNECTED)
    val sessionId by viewModel.sessionId.observeAsState("")

    val scrollState = rememberScrollState()
    val history = remember { mutableStateListOf<AuthDecisionHistory>() }
    LaunchedEffect(authUiState.lastUpdateTime) {
        if (authUiState.lastUpdateTime > 0) {
            history.add(
                0,
                AuthDecisionHistory(
                    decision = authUiState.lastDecision,
                    message = authUiState.lastDecisionMessage,
                    timestamp = authUiState.lastUpdateTime
                )
            )
            if (history.size > 5) history.removeLast()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ContinuousAuthHero(authUiState)

        AuthControlCard(
            authUiState = authUiState,
            isEncryptedUploading = isEncryptedUploading,
            connectionStatus = connectionStatus,
            onStartAuth = { viewModel.startAuthentication() }
        )

        RealTimeDecisionCard(authUiState = authUiState)

        PipelineStatusCard(
            isEncryptedUploading = isEncryptedUploading,
            connectionStatus = connectionStatus,
            sessionId = sessionId,
            authUiState = authUiState
        )

        HistoryCard(history = history)

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private data class AuthDecisionHistory(
    val decision: AuthDecision,
    val message: String,
    val timestamp: Long
)

@Composable
private fun ContinuousAuthHero(
    authUiState: ContinuousAuthUiState
) {
    val statusColor = when (authUiState.lastDecision) {
        AuthDecision.NORMAL -> ExtendedColors.success
        AuthDecision.ABNORMAL -> MaterialTheme.colorScheme.error
        AuthDecision.UNKNOWN -> MaterialTheme.colorScheme.secondary
    }
    val gradient = Brush.linearGradient(
        colors = listOf(
            statusColor.copy(alpha = 0.18f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(20.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(28.dp)
                )
                Column {
                    Text(
                        text = "持续认证",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "服务端推理 · 端侧实时防护",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            DecisionBadge(
                decision = authUiState.lastDecision,
                hasResult = authUiState.lastUpdateTime > 0
            )

            Spacer(modifier = Modifier.height(12.dp))
            val modelLabel = authUiState.modelVersion.ifBlank { "待同步" }
            val windowLabel = if (authUiState.windowSizeSec > 0f) {
                String.format(Locale.getDefault(), "%.1fs", authUiState.windowSizeSec)
            } else {
                "--"
            }
            val decisionLabel = if (authUiState.decisionTimeSec > 0f) {
                String.format(Locale.getDefault(), "%.1fs", authUiState.decisionTimeSec)
            } else {
                "--"
            }
            Text(
                text = "模型版本: $modelLabel · w=$windowLabel · T=$decisionLabel",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AuthControlCard(
    authUiState: ContinuousAuthUiState,
    isEncryptedUploading: Boolean,
    connectionStatus: ConnectionStatus,
    onStartAuth: () -> Unit
) {
    val canStart = connectionStatus == ConnectionStatus.CONNECTED && isEncryptedUploading
    val statusLabel = if (authUiState.authActive) "认证中" else "未启动"
    val statusColor = if (authUiState.authActive) ExtendedColors.success else MaterialTheme.colorScheme.secondary
    val actionLabel = if (authUiState.authActive) "重新认证" else "开始认证"
    val windowLabel = if (authUiState.windowSizeSec > 0f) {
        String.format(Locale.getDefault(), "%.1fs", authUiState.windowSizeSec)
    } else {
        "--"
    }
    val decisionLabel = if (authUiState.decisionTimeSec > 0f) {
        String.format(Locale.getDefault(), "%.1fs", authUiState.decisionTimeSec)
    } else {
        "--"
    }
    val helperText = when {
        authUiState.authMessage.isNotBlank() -> authUiState.authMessage
        !canStart -> "请先连接服务器并开启加密上传"
        else -> "点击开始认证，服务端将开始推理"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "认证控制",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                StatusPill(text = statusLabel, color = statusColor)
            }

            Text(
                text = "窗口 $windowLabel · 决策 $decisionLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val connectionColor = when (connectionStatus) {
                    ConnectionStatus.CONNECTED -> ExtendedColors.success
                    ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.error
                }
                StatusChip(
                    icon = Icons.Outlined.Wifi,
                    label = if (connectionStatus == ConnectionStatus.CONNECTED) "已连接" else "未连接",
                    color = connectionColor
                )
                StatusChip(
                    icon = Icons.Filled.Bolt,
                    label = if (isEncryptedUploading) "加密上传中" else "未上传",
                    color = if (isEncryptedUploading) ExtendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusChip(
                    icon = Icons.Filled.Security,
                    label = if (authUiState.authActive) "认证中" else "未认证",
                    color = statusColor
                )
            }

            Button(
                onClick = onStartAuth,
                enabled = canStart,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(actionLabel)
            }

            if (helperText.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = helperText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RealTimeDecisionCard(
    authUiState: ContinuousAuthUiState
) {
    val hasResult = authUiState.lastUpdateTime > 0
    val statusColor = when (authUiState.lastDecision) {
        AuthDecision.NORMAL -> ExtendedColors.success
        AuthDecision.ABNORMAL -> MaterialTheme.colorScheme.error
        AuthDecision.UNKNOWN -> MaterialTheme.colorScheme.secondary
    }
    val decisionLabel = when (authUiState.lastDecision) {
        AuthDecision.NORMAL -> "认证通过"
        AuthDecision.ABNORMAL -> "认证不通过"
        AuthDecision.UNKNOWN -> "等待推理结果"
    }
    val decisionMessage = authUiState.lastDecisionMessage.ifBlank {
        if (hasResult) "--" else "等待推理结果"
    }
    val messageColor = if (authUiState.lastDecision == AuthDecision.ABNORMAL) {
        statusColor
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val latencyLabel = authUiState.serverLatencyMs?.let { "${it}ms" } ?: "--"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Insights,
                        contentDescription = null,
                        tint = statusColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "实时推理结果",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "延迟 $latencyLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = decisionLabel,
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = statusColor
                    )
                    Text(
                        text = decisionMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = messageColor
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "上次更新: ${formatTime(authUiState.lastUpdateTime)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun StatusChip(
    icon: ImageVector,
    label: String,
    color: Color
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
    }
}

@Composable
private fun PipelineStatusCard(
    isEncryptedUploading: Boolean,
    connectionStatus: ConnectionStatus,
    sessionId: String?,
    authUiState: ContinuousAuthUiState
) {
    val connectionText = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> "服务端已连通"
        ConnectionStatus.CONNECTING -> "正在连接"
        ConnectionStatus.RECONNECTING -> "重连中"
        else -> "未连接"
    }
    val connectionColor = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> ExtendedColors.success
        ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.SignalCellularAlt, contentDescription = null, tint = connectionColor)
                Text(
                    text = "推理通路状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            StatusRow(
                icon = Icons.Outlined.Wifi,
                label = "传输通道",
                value = connectionText,
                valueColor = connectionColor
            )

            StatusRow(
                icon = Icons.Filled.Bolt,
                label = "加密上传",
                value = if (isEncryptedUploading) "运行中" else "未启动",
                valueColor = if (isEncryptedUploading) ExtendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant
            )

            StatusRow(
                icon = Icons.Filled.CheckCircle,
                label = "认证状态",
                value = if (authUiState.authActive) "运行中" else "未启动",
                valueColor = if (authUiState.authActive) ExtendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant
            )

            StatusRow(
                icon = Icons.Filled.Insights,
                label = "推理延迟",
                value = authUiState.serverLatencyMs?.let { "${it}ms" } ?: "--",
                valueColor = MaterialTheme.colorScheme.onSurface
            )

            StatusRow(
                icon = Icons.Filled.Insights,
                label = "服务端时间",
                value = authUiState.serverTimestampMs?.let { formatTime(it) } ?: "--",
                valueColor = MaterialTheme.colorScheme.onSurfaceVariant
            )

            StatusRow(
                icon = Icons.Filled.CheckCircle,
                label = "模型版本",
                value = authUiState.modelVersion.ifBlank { "待同步" },
                valueColor = MaterialTheme.colorScheme.onSurface
            )

            sessionId?.takeIf { it.isNotBlank() }?.let {
                StatusRow(
                    icon = Icons.Filled.History,
                    label = "会话ID",
                    value = it.take(8) + "...",
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            authUiState.authMessage.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(history: List<AuthDecisionHistory>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "近期判定",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (history.isEmpty()) {
                Text(
                    text = "等待推理结果，暂无历史记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                history.forEach { item ->
                    DecisionHistoryRow(item)
                }
            }
        }
    }
}

@Composable
private fun DecisionBadge(
    decision: AuthDecision,
    hasResult: Boolean
) {
    val (label, color, icon) = when (decision) {
        AuthDecision.NORMAL -> Triple("认证通过", ExtendedColors.success, Icons.Filled.CheckCircle)
        AuthDecision.ABNORMAL -> Triple("认证不通过", MaterialTheme.colorScheme.error, Icons.Filled.ErrorOutline)
        AuthDecision.UNKNOWN -> Triple("待判定", MaterialTheme.colorScheme.secondary, Icons.Filled.Insights)
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = color)
            Column {
                Text(text = label, color = color, fontWeight = FontWeight.Bold)
                Text(
                    text = if (hasResult) "服务端已返回结果" else "等待推理结果",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun DecisionHistoryRow(item: AuthDecisionHistory) {
    val color = when (item.decision) {
        AuthDecision.NORMAL -> ExtendedColors.success
        AuthDecision.ABNORMAL -> MaterialTheme.colorScheme.error
        AuthDecision.UNKNOWN -> MaterialTheme.colorScheme.secondary
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = when (item.decision) {
                    AuthDecision.NORMAL -> "认证通过"
                    AuthDecision.ABNORMAL -> "认证不通过"
                    AuthDecision.UNKNOWN -> "待判定"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = formatTime(item.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = item.message.ifBlank { "--" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

private fun formatTime(timestamp: Long): String {
    if (timestamp == 0L) return "--"
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(timestamp))
}
