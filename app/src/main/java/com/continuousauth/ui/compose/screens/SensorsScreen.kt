package com.continuousauth.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.continuousauth.ui.MainViewModel
import com.continuousauth.ui.viewmodels.SensorDataViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.sample
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Only visible cards collect display data; the service retains the full sensor stream. */
@Composable
fun SensorsScreen(viewModel: MainViewModel, sensorViewModel: SensorDataViewModel) {
    val chartsExpanded by viewModel.visualizationEnabled.observeAsState(false)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(sensorViewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> sensorViewModel.startSensorCollection()
                Lifecycle.Event.ON_STOP -> sensorViewModel.stopSensorCollection()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            sensorViewModel.startSensorCollection()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorViewModel.stopSensorCollection()
        }
    }
    LaunchedEffect(chartsExpanded) {
        if (chartsExpanded) sensorViewModel.resetChartData() else sensorViewModel.stopChartData()
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 800.dp).fillMaxSize().align(Alignment.TopCenter).testTag("sensors_screen"),
            contentPadding = PaddingValues(16.dp, 20.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "sensor_header", contentType = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("了解手机的感知", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("这些变化帮助识别使用习惯。日常使用无需读懂数值。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "sensor_status", contentType = "status") { SensorOverview(viewModel) }
            item(key = "accelerometer", contentType = "sensor") {
                SensorReading("加速度计", "感知手机移动、拿起和放下", "m/s²", "accelerometer", viewModel, sensorViewModel.accelerometerData)
            }
            item(key = "gyroscope", contentType = "sensor") {
                SensorReading("陀螺仪", "感知手机转动和握持方向变化", "rad/s", "gyroscope", viewModel, sensorViewModel.gyroscopeData)
            }
            item(key = "magnetometer", contentType = "sensor") {
                SensorReading("磁力计", "感知手机周围的磁场变化", "μT", "magnetometer", viewModel, sensorViewModel.magnetometerData)
            }
            item(key = "chart_toggle", contentType = "action") {
                OutlinedCard(onClick = viewModel::toggleVisualization, modifier = Modifier.fillMaxWidth().testTag("sensor_chart_toggle")) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("数据可视化", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(if (chartsExpanded) "查看最近的变化，点击收起" else "想了解变化时，再展开曲线", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if (chartsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (chartsExpanded) "收起曲线" else "展开曲线")
                    }
                }
            }
            if (chartsExpanded) {
                item(key = "chart_accelerometer", contentType = "chart") {
                    SensorChartCard("加速度计", "accelerometer", sensorViewModel.chartAccelerometerData)
                }
                item(key = "chart_gyroscope", contentType = "chart") {
                    SensorChartCard("陀螺仪", "gyroscope", sensorViewModel.chartGyroscopeData)
                }
                item(key = "chart_magnetometer", contentType = "chart") {
                    SensorChartCard("磁力计", "magnetometer", sensorViewModel.chartMagnetometerData)
                }
            }
            item(key = "recent_apps", contentType = "apps") { RecentAppsCard(sensorViewModel) }
        }
    }
}

@Composable
private fun SensorOverview(viewModel: MainViewModel) {
    val status by viewModel.sensorStatus.observeAsState(emptyMap())
    val running = status.values.any { it }
    val uploading by viewModel.isEncryptedUploading.observeAsState(false)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Sensors, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (running) "正在感知手机动作" else "当前未采集数据", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (running) "数值会随手机动作变化，这是正常现象。" else "在“偷用防护”启用认证后，这里会显示实时数据。",
                    style = MaterialTheme.typography.bodyMedium)
                Text(if (uploading) "采集与上传服务已开启" else "采集与上传服务未开启", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A 10 Hz preview is enough to read; collection and upload retain every original sample. */
@OptIn(FlowPreview::class)
@Composable
private fun StateFlow<SensorDataViewModel.SensorData>.displayState(): State<SensorDataViewModel.SensorData> {
    val display = remember(this) { sample(100).flowOn(Dispatchers.Default) }
    val initialSample = remember(this) { value }
    return display.collectAsStateWithLifecycle(initialValue = initialSample)
}

@Composable
private fun SensorReading(
    name: String,
    description: String,
    unit: String,
    key: String,
    viewModel: MainViewModel,
    samples: StateFlow<SensorDataViewModel.SensorData>
) {
    val status by viewModel.sensorStatus.observeAsState(emptyMap())
    val sample by samples.displayState()
    val active = status[key] == true
    val number = remember { DecimalFormat("0.00") }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Card(modifier = Modifier.fillMaxWidth().testTag("sensor_reading_$key"), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(8.dp).background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (active) "采集中" else "未采集", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("X" to sample.x, "Y" to sample.y, "Z" to sample.z).forEach { (axis, value) ->
                    Surface(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(axis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (sample.timestamp > 0 && value.isFinite()) number.format(value) else "—", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Text(if (sample.timestamp > 0) "${clock.format(Date(sample.timestamp))} · $unit${if (!active) " · 上次记录" else ""}" else "等待数据 · $unit",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SensorChartCard(title: String, key: String, samples: StateFlow<SensorDataViewModel.SensorData>) {
    val window = remember { SensorChartWindow() }
    var points by remember { mutableStateOf<List<SensorDataViewModel.SensorData>>(emptyList()) }
    var paused by remember { mutableStateOf(false) }
    if (!paused) {
        val sample by samples.displayState()
        LaunchedEffect(sample) {
            if (window.add(sample)) points = window.snapshot()
        }
    }
    Card(modifier = Modifier.fillMaxWidth().testTag("sensor_chart_$key"), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("仅显示最近 120 个预览点，完整采集数据不受影响。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SensorTrendChart(points, Modifier.fillMaxWidth().height(160.dp))
            TextButton(onClick = { paused = !paused }, modifier = Modifier.align(Alignment.End)) {
                Text(if (paused) "继续更新" else "暂停查看")
            }
        }
    }
}

/** Paths are rebuilt only for new preview points or size changes, never on every scroll frame. */
@Composable
internal fun SensorTrendChart(points: List<SensorDataViewModel.SensorData>, modifier: Modifier = Modifier) {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            colors.forEachIndexed { index, color -> Text(listOf("X", "Y", "Z")[index], color = color, style = MaterialTheme.typography.labelSmall) }
        }
        Box(Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = "三个方向的动作变化曲线，${points.size} 个预览点" }
            .drawWithCache {
                val paths = List(3) { Path() }
                val values = points.flatMap { listOf(it.x, it.y, it.z) }
                val low = values.minOrNull() ?: 0f
                val high = values.maxOrNull() ?: 1f
                val span = (high.toDouble() - low.toDouble()).coerceAtLeast(0.01)
                val firstTime = points.firstOrNull()?.timestamp ?: 0L
                val duration = ((points.lastOrNull()?.timestamp ?: firstTime) - firstTime).coerceAtLeast(1L)
                points.forEachIndexed { index, point ->
                    val x = if (points.size == 1) size.width / 2 else size.width * ((point.timestamp - firstTime).toDouble() / duration).toFloat()
                    listOf(point.x, point.y, point.z).forEachIndexed { axis, value ->
                        val y = size.height * (0.95f - 0.9f * ((value.toDouble() - low) / span).toFloat())
                        if (index == 0) paths[axis].moveTo(x, y) else paths[axis].lineTo(x, y)
                    }
                }
                val stroke = Stroke(width = 2.dp.toPx())
                onDrawBehind {
                    repeat(5) { index ->
                        val y = size.height * index / 4
                        drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    paths.forEachIndexed { index, path -> drawPath(path, colors[index], style = stroke) }
                }
            }, contentAlignment = Alignment.Center) {
            if (points.size < 2) Text("等待动作数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecentAppsCard(viewModel: SensorDataViewModel) {
    val recentApps by viewModel.recentApps.collectAsStateWithLifecycle()
    val apps = remember(recentApps) { recentApps.sortedByDescending { it.timestamp }.distinctBy { it.packageName }.take(10) }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("最近使用的应用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("帮助回顾认证发生时的使用场景。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (apps.isEmpty()) {
                Text("暂无记录。开始采集并允许应用使用情况权限后，这里会显示最近使用的应用。", style = MaterialTheme.typography.bodyMedium)
            } else {
                // At most ten rows: avoid a second vertical scroller fighting the page gesture.
                apps.forEach { app ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Apps, null, tint = MaterialTheme.colorScheme.primary)
                        Text(app.appName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(clock.format(Date(app.timestamp)), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
