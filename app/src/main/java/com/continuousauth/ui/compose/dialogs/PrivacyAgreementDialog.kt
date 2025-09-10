package com.continuousauth.ui.compose.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.continuousauth.R

/**
 * Compose 版本的隐私协议对话框
 * 首次启动时显示，用户必须同意才能继续使用应用
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyAgreementDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Dialog(
        onDismissRequest = { /* 不允许点击外部关闭 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f), // 占屏幕高度的90%
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 标题部分
                Icon(
                    imageVector = Icons.Filled.PrivacyTip,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = stringResource(R.string.privacy_agreement_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = stringResource(R.string.privacy_welcome),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 隐私协议内容（可滚动）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 数据采集
                    PrivacySection(
                        title = stringResource(R.string.privacy_data_collection_title),
                        content = stringResource(R.string.privacy_data_collection_content)
                    )
                    
                    // 数据用途
                    PrivacySection(
                        title = stringResource(R.string.privacy_data_usage_title),
                        content = stringResource(R.string.privacy_data_usage_content)
                    )
                    
                    // 数据存储
                    PrivacySection(
                        title = stringResource(R.string.privacy_data_storage_title),
                        content = stringResource(R.string.privacy_data_storage_content)
                    )
                    
                    // 数据共享
                    PrivacySection(
                        title = stringResource(R.string.privacy_data_sharing_title),
                        content = stringResource(R.string.privacy_data_sharing_content)
                    )
                    
                    // 用户权利
                    PrivacySection(
                        title = stringResource(R.string.privacy_user_rights_title),
                        content = stringResource(R.string.privacy_user_rights_content)
                    )
                    
                    // 联系方式
                    PrivacySection(
                        title = stringResource(R.string.privacy_contact_title),
                        content = stringResource(R.string.privacy_contact_content)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 按钮部分
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDecline,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.disagree))
                    }
                    
                    Button(
                        onClick = onAccept,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(stringResource(R.string.agree))
                    }
                }
            }
        }
    }
}

/**
 * 隐私协议的单个章节
 */
@Composable
private fun PrivacySection(
    title: String,
    content: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Justify
        )
    }
}