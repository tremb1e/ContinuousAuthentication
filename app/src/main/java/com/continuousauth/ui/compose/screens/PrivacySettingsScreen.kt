package com.continuousauth.ui.compose.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.continuousauth.network.ConnectionStatus
import com.continuousauth.ui.AuthDecision
import com.continuousauth.ui.ContinuousAuthUiState
import com.continuousauth.ui.MainViewModel
import com.continuousauth.ui.theme.ExtendedColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 持续认证页面
 * 展示服务端推理结果、阈值控制与实时状态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val authUiState by viewModel.authUiState.collectAsStateWithLifecycle()
    val isEncryptedUploading by viewModel.isEncryptedUploading.observeAsState(false)
    val connectionStatus by viewModel.connectionStatus.observeAsState(ConnectionStatus.DISCONNECTED)
    val sessionId by viewModel.sessionId.observeAsState("")

    var threshold by remember { mutableStateOf(authUiState.thresholdPercent) }
    LaunchedEffect(authUiState.thresholdPercent) {
        threshold = authUiState.thresholdPercent
    }

    val scrollState = rememberScrollState()
    val history = remember { mutableStateListOf<AuthDecisionHistory>() }
    LaunchedEffect(authUiState.lastUpdateTime) {
        if (authUiState.lastUpdateTime > 0) {
            history.add(
                0,
                AuthDecisionHistory(
                    decision = authUiState.lastDecision,
                    score = authUiState.lastScore,
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
        ContinuousAuthHero(authUiState, threshold)

        RealTimeDecisionCard(
            authUiState = authUiState,
            threshold = threshold,
            onManualRefresh = {
                viewModel.updateAuthResult(
                    score = Random.nextFloat(),
                    modelVersion = authUiState.modelVersion,
                    latencyMs = (60..180).random().toLong()
                )
            }
        )

        ThresholdCard(
            threshold = threshold,
            onThresholdChange = {
                threshold = it
                viewModel.setAuthThreshold(it)
            }
        )

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
    val score: Float,
    val timestamp: Long
)

@Composable
private fun ContinuousAuthHero(
    authUiState: ContinuousAuthUiState,
    threshold: Int
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
                score = authUiState.lastScore,
                threshold = threshold
            )

            Spacer(modifier = Modifier.height(12.dp))
            val modelLabel = authUiState.modelVersion.ifBlank { "待同步" }
            Text(
                text = "阈值: ${threshold}% · 模型版本: $modelLabel",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RealTimeDecisionCard(
    authUiState: ContinuousAuthUiState,
    threshold: Int,
    onManualRefresh: () -> Unit
) {
    val scorePercent = (authUiState.lastScore * 100).coerceIn(0f, 100f)
    val statusColor = when (authUiState.lastDecision) {
        AuthDecision.NORMAL -> ExtendedColors.success
        AuthDecision.ABNORMAL -> MaterialTheme.colorScheme.error
        AuthDecision.UNKNOWN -> MaterialTheme.colorScheme.secondary
    }
    val subtitle = when (authUiState.lastDecision) {
        AuthDecision.NORMAL -> "身份匹配，保持会话"
        AuthDecision.ABNORMAL -> "存在异常，需二次校验"
        AuthDecision.UNKNOWN -> "等待推理结果"
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
                TextButton(onClick = onManualRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("刷新结果")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${scorePercent.roundToInt()}%",
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = statusColor
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "阈值 $threshold%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "上次更新: ${formatTime(authUiState.lastUpdateTime)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LinearProgressIndicator(
                progress = { scorePercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = statusColor
            )
        }
    }
}

@Composable
private fun ThresholdCard(
    threshold: Int,
    onThresholdChange: (Int) -> Unit
) {
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
                    imageVector = Icons.Filled.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "阈值控制（百分比）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "手动调整通过阈值，分数高于阈值判定为正常。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Slider(
                value = threshold.toFloat(),
                onValueChange = { onThresholdChange(it.roundToInt()) },
                valueRange = 40f..100f,
                steps = 12
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("40%", style = MaterialTheme.typography.labelSmall)
                Text("$threshold%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("100%", style = MaterialTheme.typography.labelSmall)
            }
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
    score: Float,
    threshold: Int
) {
    val (label, color, icon) = when (decision) {
        AuthDecision.NORMAL -> Triple("正常", ExtendedColors.success, Icons.Filled.CheckCircle)
        AuthDecision.ABNORMAL -> Triple("异常", MaterialTheme.colorScheme.error, Icons.Filled.ErrorOutline)
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
                    text = "分数 ${(score * 100).roundToInt()}% / 阈值 $threshold%",
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
                    AuthDecision.NORMAL -> "正常"
                    AuthDecision.ABNORMAL -> "异常"
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
            text = "${(item.score * 100).roundToInt()}%",
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
