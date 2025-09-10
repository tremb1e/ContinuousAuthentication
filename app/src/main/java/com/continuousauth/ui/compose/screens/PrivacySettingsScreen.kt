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
    var showWithdrawDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var dataRetentionDays by remember { mutableIntStateOf(30) }
    
    // 初始化时获取数据保留期限
    LaunchedEffect(Unit) {
        dataRetentionDays = viewModel.getDataRetentionDays()
    }
    
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
                // 同意状态卡片
                ConsentStatusCard(
                    consentState = consentState,
                    onGrantConsent = { viewModel.grantPrivacyConsent() }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 数据管理卡片
                DataManagementCard(
                    onWithdrawConsent = { showWithdrawDialog = true },
                    onViewPrivacyPolicy = { showPrivacyPolicy = true },
                    isDeleting = deletionState == DeletionState.IN_PROGRESS
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 数据保留设置卡片
                DataRetentionCard(
                    retentionDays = dataRetentionDays,
                    onRetentionDaysChange = { days ->
                        dataRetentionDays = days
                        viewModel.setDataRetentionDays(days)
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 传输策略卡片
                TransmissionPolicyCard(viewModel = viewModel)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 加密状态卡片
                EncryptionStatusCard()
            }
            
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

/**
 * 加密状态卡片
 */
@Composable
private fun EncryptionStatusCard() {
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
            
            PrivacyInfoRow("加密算法", "AES-256-GCM with StreamingAEAD")
            PrivacyInfoRow("密钥管理", "Android Keystore (Tink)")
            PrivacyInfoRow("传输安全", "TLS 1.3 + 证书固定")
            PrivacyInfoRow("数据压缩", "GZIP (先压缩后加密)")
        }
    }
}

/**
 * 信息行
 */
@Composable
private fun PrivacyInfoRow(label: String, value: String) {
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
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
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