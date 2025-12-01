package com.continuousauth.ui.compose.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.continuousauth.privacy.ConsentState
import com.continuousauth.privacy.DeletionState
import com.continuousauth.ui.MainViewModel
import kotlinx.coroutines.launch

/**
 * 隐私设置界面
 * 符合 claude.md 第5节要求：隐私与合规功能
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 观察隐私相关状态
    val consentState by viewModel.consentState.observeAsState(initial = ConsentState.UNKNOWN)
    val deletionState by viewModel.deletionState.observeAsState(initial = DeletionState.IDLE)

    // 本地状态
    var dataRetentionDays by remember { mutableIntStateOf(30) }

    // 初始化时获取数据保留期限
    LaunchedEffect(Unit) {
        dataRetentionDays = viewModel.getDataRetentionDays()
    }

    // 修改：将卡片显示控制变量初始化为true，确保内容可见
    var showDataRetentionCard by remember { mutableStateOf(true) }
    var showTransmissionPolicyCard by remember { mutableStateOf(true) }
    var showConsentStatusCard by remember { mutableStateOf(true) }

    // 移除Scaffold和顶部应用栏
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 条件显示同意状态卡片
            if (showConsentStatusCard) {
                ConsentStatusCard(
                    consentState = consentState,
                    onGrantConsent = { viewModel.grantPrivacyConsent() }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            // 条件显示数据保留设置卡片
            if (showDataRetentionCard) {
                // 数据保留设置卡片
                DataRetentionCard(
                    retentionDays = dataRetentionDays,
                    onRetentionDaysChange = { days ->
                        dataRetentionDays = days
                        viewModel.setDataRetentionDays(days)
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
            // 条件显示传输策略卡片
            if (showTransmissionPolicyCard) {
                TransmissionPolicyCard(viewModel = viewModel)

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        // 删除进度指示器
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
 * 同意状态卡片
 */
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

/**
 * 数据保留设置卡片
 */
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
                steps = 29  // 30个步骤，对应30天间隔
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

/**
 * 传输策略卡片
 */
@Composable
private fun TransmissionPolicyCard(viewModel: MainViewModel) {
    // 观察上传策略状态
    val wifiOnly by viewModel.uploadPolicyWiFiOnly.observeAsState(initial = false)

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
                    onCheckedChange = { isChecked ->
                        viewModel.setUploadPolicyWiFiOnly(isChecked)
                    }
                )
            }
        }
    }
}




