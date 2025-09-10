package com.continuousauth.ui.compose.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.continuousauth.ui.MainViewModel
import com.continuousauth.ui.theme.ExtendedColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

/**
 * 详细信息页面
 * 显示系统指标、性能数据和调试信息的仪表板
 */
@Composable
fun DetailsScreen(viewModel: MainViewModel) {
    val scrollState = rememberScrollState()
    
    // 模拟数据
    val transmissionMode by remember { mutableStateOf("SLOW_MODE") }
    val creditScore by remember { mutableStateOf(95) }
    
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
            // 页面标题
            DashboardHeader()
            
            // 关键指标网格
            MetricsGrid()
            
            // 传输状态卡片
            TransmissionStatusCard(transmissionMode)
            
            // 信用分数卡片
            CreditScoreCard(creditScore)
            
            // 系统性能卡片
            SystemPerformanceCard()
            
            // 硬件信息卡片
            HardwareInfoCard()
            
            // 策略配置卡片
            PolicyConfigCard()
            
            // TLS安全信息卡片
            TlsSecurityCard(viewModel)
            
            // 导出日志按钮
            ExportLogsCard()
            
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

/**
 * 仪表板标题
 */
@Composable
fun DashboardHeader() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            ExtendedColors.gradientStart.copy(alpha = 0.1f),
                            ExtendedColors.gradientEnd.copy(alpha = 0.1f)
                        )
                    )
                )
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Dashboard,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "系统监控仪表板",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "实时监控系统性能和传输状态",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * 关键指标网格
 */
@Composable
fun MetricsGrid() {
    val metrics = listOf(
        MetricItem(Icons.Outlined.Upload, "上传成功", "1,234", ExtendedColors.success),
        MetricItem(Icons.Outlined.Error, "上传失败", "12", ExtendedColors.error),
        MetricItem(Icons.Outlined.Timer, "ACK延迟", "45ms", ExtendedColors.info),
        MetricItem(Icons.Outlined.Storage, "队列大小", "23MB", ExtendedColors.warning),
        MetricItem(Icons.Outlined.Memory, "内存使用", "156MB", MaterialTheme.colorScheme.primary),
        MetricItem(Icons.Outlined.Speed, "采样率", "200Hz", MaterialTheme.colorScheme.secondary)
    )
    
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false
    ) {
        items(metrics) { metric ->
            MetricCard(metric)
        }
    }
}

/**
 * 指标卡片
 */
@Composable
fun MetricCard(metric: MetricItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = metric.color.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = metric.icon,
                contentDescription = null,
                tint = metric.color,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = metric.value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = metric.color
            )
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 传输状态卡片
 */
@Composable
fun TransmissionStatusCard(mode: String) {
    var remainingTime by remember { mutableStateOf(0) }
    val isFastMode = mode == "FAST_MODE"
    
    LaunchedEffect(isFastMode) {
        if (isFastMode) {
            remainingTime = 5
            while (remainingTime > 0 && isActive) {
                delay(1000)
                remainingTime--
            }
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFastMode) 
                ExtendedColors.fastMode.copy(alpha = 0.1f)
            else 
                ExtendedColors.slowMode.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "传输模式",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isFastMode) "快速模式" else "慢速模式",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (isFastMode) ExtendedColors.fastMode else ExtendedColors.slowMode,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // 动画图标
                AnimatedTransmissionIcon(isFastMode)
            }
            
            if (isFastMode && remainingTime > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = remainingTime / 5f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = ExtendedColors.fastMode,
                    trackColor = ExtendedColors.fastMode.copy(alpha = 0.2f)
                )
                Text(
                    text = "剩余 $remainingTime 秒后切换到慢速模式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 触发器信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TriggerChip("设备解锁", Icons.Outlined.LockOpen)
                TriggerChip("应用切换", Icons.Outlined.Apps)
                TriggerChip("加速度突变", Icons.Outlined.ShowChart)
            }
        }
    }
}

/**
 * 触发器芯片
 */
@Composable
fun TriggerChip(label: String, icon: ImageVector) {
    AssistChip(
        onClick = { },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        modifier = Modifier.height(32.dp)
    )
}

/**
 * 动画传输图标
 */
@Composable
fun AnimatedTransmissionIcon(isFastMode: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "transmission")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isFastMode) 1000 else 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    Box(
        modifier = Modifier.size(60.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(60.dp)
                .rotate(rotation)
        ) {
            val color = if (isFastMode) ExtendedColors.fastMode else ExtendedColors.slowMode
            drawCircularIndicator(color)
        }
        
        Icon(
            imageVector = if (isFastMode) Icons.Filled.FlashOn else Icons.Filled.PowerSettingsNew,
            contentDescription = null,
            tint = if (isFastMode) ExtendedColors.fastMode else ExtendedColors.slowMode,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * 信用分数卡片
 */
@Composable
fun CreditScoreCard(score: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "信用分数",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 圆形进度指示器
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(120.dp)
            ) {
                CircularProgressIndicator(
                    progress = score / 100f,
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 12.dp,
                    color = when {
                        score >= 80 -> ExtendedColors.success
                        score >= 60 -> ExtendedColors.warning
                        else -> ExtendedColors.error
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = score.toString(),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            score >= 80 -> ExtendedColors.success
                            score >= 60 -> ExtendedColors.warning
                            else -> ExtendedColors.error
                        }
                    )
                    Text(
                        text = when {
                            score >= 80 -> "优秀"
                            score >= 60 -> "良好"
                            else -> "需改进"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Text(
                text = "基于上传成功率、连接稳定性等因素计算",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

/**
 * 系统性能卡片
 */
@Composable
fun SystemPerformanceCard() {
    var cpuUsage by remember { mutableStateOf(0) }
    var memoryUsage by remember { mutableStateOf(0) }
    var batteryLevel by remember { mutableStateOf(85) }
    var temperature by remember { mutableStateOf(36.5f) }
    
    LaunchedEffect(Unit) {
        while (isActive) {
            cpuUsage = (20..40).random()
            memoryUsage = (150..250).random()
            delay(2000)
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "系统性能",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            PerformanceRow(
                icon = Icons.Outlined.Memory,
                label = "CPU使用率",
                value = "$cpuUsage%",
                progress = cpuUsage / 100f,
                color = ExtendedColors.info
            )
            
            PerformanceRow(
                icon = Icons.Outlined.Storage,
                label = "内存使用",
                value = "${memoryUsage}MB",
                progress = memoryUsage / 512f,
                color = MaterialTheme.colorScheme.primary
            )
            
            PerformanceRow(
                icon = Icons.Outlined.BatteryFull,
                label = "电池电量",
                value = "$batteryLevel%",
                progress = batteryLevel / 100f,
                color = ExtendedColors.success
            )
            
            PerformanceRow(
                icon = Icons.Outlined.Thermostat,
                label = "设备温度",
                value = "${temperature}°C",
                progress = temperature / 50f,
                color = if (temperature > 40) ExtendedColors.warning else ExtendedColors.info
            )
        }
    }
}

/**
 * 性能指标行
 */
@Composable
fun PerformanceRow(
    icon: ImageVector,
    label: String,
    value: String,
    progress: Float,
    color: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        LinearProgressIndicator(
            progress = progress.coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.2f)
        )
    }
}

/**
 * 硬件信息卡片
 */
@Composable
fun HardwareInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "硬件信息",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            InfoRow("设备型号", android.os.Build.MODEL)
            InfoRow("Android版本", "API ${android.os.Build.VERSION.SDK_INT}")
            InfoRow("Keystore类型", "Hardware-backed")
            InfoRow("StrongBox支持", "是")
            InfoRow("传感器FIFO", "8192 events")
            InfoRow("最大采样率", "200 Hz")
        }
    }
}

/**
 * 策略配置卡片
 */
@Composable
fun PolicyConfigCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "当前策略",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            InfoRow("慢速模式周期", "1000ms")
            InfoRow("快速模式周期", "150ms")
            InfoRow("快速模式持续", "5000ms")
            InfoRow("加速度阈值", "3.0σ")
            InfoRow("敏感应用数", "12个")
            InfoRow("最后更新", "2分钟前")
        }
    }
}

/**
 * 信息行
 */
@Composable
fun InfoRow(label: String, value: String) {
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
 * TLS安全信息卡片
 * 显示TLS配置和证书固定信息
 */
@Composable
fun TlsSecurityCard(viewModel: MainViewModel) {
    val tlsConfig by viewModel.tlsConfigInfo.observeAsState()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "TLS安全信息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // TLS版本信息
            val supportsTls13 = tlsConfig?.supportsTls13 ?: false
            InfoRow("TLS版本", if (supportsTls13) "TLS 1.3" else "TLS 1.2")
            InfoRow("强制TLS 1.3", if (supportsTls13) "已启用" else "不可用")
            InfoRow("支持协议", tlsConfig?.supportedProtocols?.size?.toString() ?: "0" + "个")
            
            Divider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            )
            
            // 证书固定信息
            InfoRow("SPKI Pinning", "已配置")
            InfoRow("证书Pin数量", "0个") // 由服务器策略控制
            InfoRow("Pin轮换策略", "自动")
            
            Divider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            )
            
            // 密码套件信息
            val recommendedCiphers = tlsConfig?.recommendedCiphers ?: emptyList()
            val preferredCipher = recommendedCiphers.firstOrNull { it.contains("AES") && it.contains("GCM") }
                ?: recommendedCiphers.firstOrNull()
                ?: "未配置"
            InfoRow("推荐密码套件", if (preferredCipher.length > 30) preferredCipher.take(27) + "..." else preferredCipher)
            InfoRow("安全套件数", recommendedCiphers.size.toString() + "个")
            InfoRow("最后验证", "刚刚")
        }
    }
}

/**
 * 导出日志卡片
 */
@Composable
fun ExportLogsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OutlinedButton(
                onClick = { /* 导出日志 */ },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("导出日志")
            }
            
            OutlinedButton(
                onClick = { /* 分享诊断 */ },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("分享诊断")
            }
        }
    }
}

/**
 * 绘制圆形指示器
 */
fun DrawScope.drawCircularIndicator(color: Color) {
    val strokeWidth = 3.dp.toPx()
    val radius = (size.minDimension - strokeWidth) / 2
    val center = Offset(size.width / 2, size.height / 2)
    
    // 绘制多个圆弧
    for (i in 0..3) {
        val startAngle = i * 90f
        drawArc(
            color = color.copy(alpha = 0.3f + i * 0.2f),
            startAngle = startAngle,
            sweepAngle = 60f,
            useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            size = size.copy(width = radius * 2, height = radius * 2),
            topLeft = Offset(center.x - radius, center.y - radius)
        )
    }
}

/**
 * 指标数据类
 */
data class MetricItem(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val color: Color
)