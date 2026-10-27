package com.continuousauth.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.continuousauth.auth.AuthHistoryEntry
import com.continuousauth.auth.AuthHistoryRepository
import com.continuousauth.privacy.ConsentState
import com.continuousauth.settings.ProtectionMode
import com.continuousauth.ui.AuthDecision
import com.continuousauth.ui.AuthHistoryAppSummary
import com.continuousauth.ui.AuthHistoryBucket
import com.continuousauth.ui.AuthHistoryDateRange
import com.continuousauth.ui.AuthHistoryPeriod
import com.continuousauth.ui.AuthHistoryReport
import com.continuousauth.ui.ContinuousAuthUiState
import com.continuousauth.ui.MainViewModel
import com.continuousauth.ui.authHistoryAppName
import com.continuousauth.ui.authHistoryDateRange
import com.continuousauth.ui.buildAuthHistoryReport
import com.continuousauth.ui.compose.components.ExpandableSection
import com.continuousauth.ui.formatAuthHistoryTime
import com.continuousauth.ui.summarizeAuthHistoryApps
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Retains the original route entry while consent itself now lives in Settings. */
@Composable
fun PrivacySettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: MainViewModel,
    onNavigateSettings: () -> Unit = onNavigateBack,
    onReviewConsent: () -> Unit = onNavigateSettings,
    onEnableUsageAccess: () -> Unit = onNavigateSettings
) {
    val authUiState by viewModel.authUiState.collectAsStateWithLifecycle()
    val protectionMode by viewModel.protectionMode.collectAsStateWithLifecycle()
    val history by viewModel.authHistory.collectAsStateWithLifecycle()
    val consent by viewModel.consentState.observeAsState(ConsentState.UNKNOWN)
    val collectionRunning by viewModel.isCollectionRunning.observeAsState(false)
    val collectionStatus by viewModel.collectionStatus.observeAsState("STOPPED")
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasUsageAccess by remember { mutableStateOf(viewModel.isUsageStatsPermissionGranted()) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasUsageAccess = viewModel.isUsageStatsPermissionGranted()
                nowMs = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(60_000)
                nowMs = System.currentTimeMillis()
            }
        }
    }
    AuthenticationContent(
        authUiState = authUiState,
        protectionMode = protectionMode,
        history = history,
        consentGranted = consent == ConsentState.GRANTED,
        hasUsageAccess = hasUsageAccess,
        onStart = viewModel::startAuthentication,
        onStop = viewModel::stopAuthentication,
        onOpenSettings = onNavigateSettings,
        onReviewConsent = onReviewConsent,
        onEnableUsageAccess = onEnableUsageAccess,
        nowMs = nowMs,
        collectionActive = collectionRunning || collectionStatus == "STARTING" || collectionStatus == "STOPPING",
        isStopping = collectionStatus == "STOPPING"
    )
}

/** State-driven content also used by the on-device UI regression suite. */
@Composable
fun AuthenticationContent(
    authUiState: ContinuousAuthUiState,
    protectionMode: ProtectionMode,
    history: List<AuthHistoryEntry>,
    consentGranted: Boolean,
    hasUsageAccess: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onReviewConsent: () -> Unit,
    onEnableUsageAccess: () -> Unit,
    nowMs: Long = System.currentTimeMillis(),
    collectionActive: Boolean = false,
    isStopping: Boolean = false
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 800.dp).fillMaxSize().align(Alignment.TopCenter)
                .testTag("authentication_screen"),
            contentPadding = PaddingValues(16.dp, 20.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "authentication_header", contentType = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("偷用防护", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text("持续身份认证技术，使用三种传感器数据持续建模使用中的用户身份并进行认证", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "authentication_status", contentType = "status") {
                AuthenticationStatusCard(authUiState, protectionMode, consentGranted,
                    onStart, onStop, onOpenSettings, onReviewConsent, collectionActive, isStopping)
            }
            if (authUiState.lastUpdateTime > 0 && authUiState.lastDecision == AuthDecision.ABNORMAL) {
                item(key = "authentication_warning", contentType = "warning") { AuthenticationWarning(authUiState.lastUpdateTime, protectionMode) }
            }
            item(key = "authentication_history", contentType = "history") {
                ExpandableSection("authentication_history", "认证结果分析", "查看认证次数、发生时间和使用中的应用", Icons.Filled.BarChart) {
                    AuthenticationHistoryContent(history, hasUsageAccess, nowMs, onEnableUsageAccess)
                }
            }
            item(key = "authentication_help", contentType = "help") {
                ExpandableSection("authentication_help", "如何使用持续认证", "启用后，像平常一样使用手机即可", Icons.Filled.HelpOutline) {
                    HelpStep("1", "先了解并选择", "在设置中阅读知情同意，选择允许采集的数据和防护方式。")
                    HelpStep("2", "轻点启用认证", "应用会自动准备连接。准备完成后，继续正常使用手机，系统会持续判断使用习惯是否相符。")
                    HelpStep("3", "随时回来查看", "在认证结果分析中查看记录。关闭认证后，认证、数据采集和上传都会停止。")
                    HorizontalDivider()
                    Text("偶尔不通过可能与姿势、场景变化或数据不足有关，不代表一定有人冒用。仅监测模式不会弹窗或限制手机使用。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AuthenticationStatusCard(
    state: ContinuousAuthUiState,
    mode: ProtectionMode,
    consentGranted: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onReviewConsent: () -> Unit,
    collectionActive: Boolean,
    isStopping: Boolean
) {
    val running = state.authActive && consentGranted
    val preparing = state.isStarting && consentGranted
    val title = when {
        !consentGranted -> "先了解数据使用"
        isStopping -> "正在关闭认证"
        preparing -> "正在准备认证"
        running -> "正在持续认证"
        else -> "偷用防护启用开关"
    }
    val explanation = when {
        !consentGranted -> "阅读知情同意后，由你决定是否开启。你也可以先浏览应用。"
        isStopping -> "正在停止认证、数据采集和上传，请稍候。"
        preparing -> "正在连接并准备所需数据，请稍候。你可以随时取消。"
        running -> "继续像平常一样使用手机，认证结果会自动记录在下方。"
        else -> "根据你使用手机时的动作习惯，持续判断是否仍由你本人使用。"
    }
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                MaterialTheme.colorScheme.surface))).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Filled.Security, null, Modifier.padding(12.dp).size(28.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            }
            Text(explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (preparing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("authentication_preparing"))
            }
            if (running && state.lastUpdateTime > 0 && state.lastDecision == AuthDecision.NORMAL) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("最近一次认证通过", fontWeight = FontWeight.SemiBold)
                        Text(formatAuthHistoryTime(state.lastUpdateTime), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (running && (state.lastUpdateTime == 0L || state.lastDecision == AuthDecision.UNKNOWN)) {
                Text("尚未收到认证结果，请继续正常使用手机。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.authMessage.isNotBlank() && consentGranted) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                    Text(state.authMessage, Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(onClick = if (consentGranted) onStart else onReviewConsent,
                enabled = !preparing && !running && !isStopping,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("authentication_action")) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (consentGranted) "启用认证" else "查看知情同意",
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = onStop,
                enabled = !isStopping && (state.authActive || state.isStarting || collectionActive),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("authentication_stop")) {
                Icon(Icons.Filled.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text(if (isStopping) "正在关闭…" else "关闭认证", style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center)
            }
            Surface(onClick = onOpenSettings, modifier = Modifier.testTag("authentication_protection"),
                shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("防护方式：${protectionModeLabel(mode)}", style = MaterialTheme.typography.labelLarge)
                        Text(when (mode) {
                            ProtectionMode.OFF, ProtectionMode.MONITOR_ONLY -> "仅记录结果，不弹窗、不限制使用"
                            ProtectionMode.ALERT -> "认证不通过时，通过系统弹窗提醒"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.ArrowForward, contentDescription = "调整防护方式", modifier = Modifier.size(20.dp))
                }
            }
            if (running || preparing || collectionActive) {
                Text("关闭认证将同时停止数据采集和上传。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun protectionModeLabel(mode: ProtectionMode): String = when (mode) {
    ProtectionMode.OFF, ProtectionMode.MONITOR_ONLY -> "仅监测"
    ProtectionMode.ALERT -> "即时警示"
}

@Composable
private fun AuthenticationWarning(timestampMs: Long, mode: ProtectionMode) {
    Card(modifier = Modifier.fillMaxWidth().testTag("authentication_warning"), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text("最近一次认证未通过", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
            }
            Text(formatAuthHistoryTime(timestampMs), style = MaterialTheme.typography.labelMedium)
            Text("使用动作与平时不太一致。若是你本人，继续正常使用即可；这次结果已记录。",
                style = MaterialTheme.typography.bodyMedium)
            Text(if (mode != ProtectionMode.ALERT) "当前为仅监测模式，不会弹窗或限制使用。"
                else "当前为即时警示模式。系统弹窗需要在设置中允许显示在其他应用上层。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuthenticationHistoryContent(
    history: List<AuthHistoryEntry>,
    hasUsageAccess: Boolean,
    nowMs: Long,
    onEnableUsageAccess: () -> Unit
) {
    val zoneId = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
    var periodName by rememberSaveable { mutableStateOf(AuthHistoryPeriod.TODAY.name) }
    var customStartDay by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var customEndDay by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var selectedBucketStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var eventPage by rememberSaveable { mutableStateOf(0) }
    var appPage by rememberSaveable { mutableStateOf(0) }
    val period = AuthHistoryPeriod.valueOf(periodName)
    val range = authHistoryDateRange(period, today, LocalDate.ofEpochDay(customStartDay), LocalDate.ofEpochDay(customEndDay))
    val emptyReport = remember(range, zoneId) { buildAuthHistoryReport(emptyList(), range, nowMs, zoneId) }
    // A week of results can contain many thousands of entries. Sorting, filtering and
    // grouping must not compete with scrolling on the Android main thread.
    val request = remember(history, range, nowMs, zoneId, selectedBucketStart) {
        HistoryCardRequest(history, range, nowMs, zoneId, selectedBucketStart)
    }
    val content by produceState<HistoryCardContent?>(null, request) {
        // Retain the previous result until its replacement is ready. Clearing the card
        // on every incoming result changes its height and pulls the scroll position.
        value = withContext(Dispatchers.Default) {
            val report = buildAuthHistoryReport(history, range, nowMs, zoneId)
            val selected = report.buckets.firstOrNull { it.startMs == selectedBucketStart }
            val entries = if (selected == null) report.entries else report.entries.filter {
                it.timestampMs >= selected.startMs && it.timestampMs < selected.endExclusiveMs
            }
            HistoryCardContent(request, report, selected, entries, entries.filter { !it.accepted }, summarizeAuthHistoryApps(entries))
        }
    }
    val loading = content?.request != request
    val report = content?.report ?: emptyReport
    val selectedBucket = content?.selectedBucket
    val selectedEntries = content?.selectedEntries.orEmpty()
    val failures = content?.failures.orEmpty()
    val failureCount = failures.size
    val appSummary = content?.appSummary.orEmpty()
    val visibleAppPage = appPage.coerceAtMost((appSummary.size - 1).coerceAtLeast(0) / 10)
    val stackAppCount = LocalDensity.current.fontScale > 1.3f
    LaunchedEffect(range, selectedBucketStart) { eventPage = 0; appPage = 0 }

    if (showDatePicker) {
        HistoryDateRangeDialog(today, range, onDismiss = { showDatePicker = false }) { selectedRange ->
            customStartDay = selectedRange.start.toEpochDay()
            customEndDay = selectedRange.endInclusive.toEpochDay()
            periodName = AuthHistoryPeriod.CUSTOM.name
            selectedBucketStart = null
            showDatePicker = false
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("结果自动保存在本机，保留最近 ${AuthHistoryRepository.RETENTION_DAYS} 天。", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AuthHistoryPeriod.entries.forEach { option ->
                FilterChip(selected = period == option, onClick = {
                    if (option == AuthHistoryPeriod.CUSTOM) showDatePicker = true else {
                        periodName = option.name
                        selectedBucketStart = null
                    }
                }, label = { Text(option.label) },
                    leadingIcon = if (option == AuthHistoryPeriod.CUSTOM) {
                        { Icon(Icons.Filled.DateRange, null, Modifier.size(18.dp)) }
                    } else null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("history_period_${option.name}"))
            }
        }
        Text(formatDateRange(content?.request?.range ?: range), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("history_date_range"))
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxSize().testTag("history_loading"))
        }
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(if (content == null) "正在整理记录…" else if (report.entries.isEmpty()) "暂无认证记录" else "${report.failureCount} 次不通过",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("history_total"))
            if (content == null || report.entries.isNotEmpty()) {
                Text(if (content == null) "记录较多时需要一点时间，请稍候。"
                    else "共收到 ${report.entries.size} 次结果 · ${report.entries.size - report.failureCount} 次通过",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AuthHistoryBarChart(report, selectedBucket?.startMs) { start ->
            selectedBucketStart = if (selectedBucketStart == start) null else start
        }
        HorizontalDivider()
        if (selectedBucket != null) {
            Text("已选：${selectedBucket.description}", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, modifier = Modifier.testTag("history_selected_bucket"))
            Text(if (selectedBucket.totalCount == 0) "暂无认证记录"
                else "${selectedBucket.failureCount} 次不通过 · 共 ${selectedBucket.totalCount} 次结果",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { selectedBucketStart = null }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("查看整个时段")
            }
        }
        Text("不通过时正在使用的应用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        when {
            selectedEntries.isEmpty() -> Text("所选时段暂无记录。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            failureCount == 0 -> Text("已记录 ${selectedEntries.size} 次结果，未出现不通过。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> appSummary.drop(visibleAppPage * 10).take(10).forEach { app ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Icon(Icons.Filled.Apps, null, Modifier.padding(10.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(app.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            if (stackAppCount) Text("${app.failureCount} 次不通过", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("最近：${formatAuthHistoryTime(app.latestTimestampMs)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!stackAppCount) Text("${app.failureCount} 次", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(progress = app.failureCount.toFloat() / failureCount.coerceAtLeast(1),
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(10.dp)),
                        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
        if (appSummary.size > 10) {
            HistoryPageControls(visibleAppPage, appSummary.size, 10,
                onPrevious = { appPage = (visibleAppPage - 1).coerceAtLeast(0) }, onNext = { appPage = visibleAppPage + 1 })
        }
        if (!hasUsageAccess || appSummary.any { it.packageName.isEmpty() }) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (!hasUsageAccess) "允许查看使用情况后，后续结果可显示当时使用的应用。未授权不影响查看认证次数。"
                        else "部分记录未能获取应用信息，因此显示为“未识别的应用”。历史缺失信息无法补回。",
                        style = MaterialTheme.typography.bodyMedium)
                    if (!hasUsageAccess) {
                        TextButton(onClick = onEnableUsageAccess) { Text("了解并开启应用识别") }
                    }
                }
            }
        }
        if (failures.isNotEmpty()) {
            ExpandableSection("failure_events", "查看发生时间", "${failures.size} 条不通过记录，按时间从近到远", Icons.Filled.History) {
                val visiblePage = eventPage.coerceAtMost((failures.size - 1) / 20)
                failures.drop(visiblePage * 20).take(20).forEach { entry ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(formatAuthHistoryTime(entry.timestampMs), style = MaterialTheme.typography.labelLarge)
                        Text(authHistoryAppName(entry), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (failures.size > 20) {
                    HistoryPageControls(visiblePage, failures.size, 20,
                        onPrevious = { eventPage = (visiblePage - 1).coerceAtLeast(0) }, onNext = { eventPage = visiblePage + 1 })
                }
            }
        }
    }
}

private data class HistoryCardRequest(
    val history: List<AuthHistoryEntry>,
    val range: AuthHistoryDateRange,
    val nowMs: Long,
    val zoneId: ZoneId,
    val selectedBucketStart: Long?
)

private data class HistoryCardContent(
    val request: HistoryCardRequest,
    val report: AuthHistoryReport,
    val selectedBucket: AuthHistoryBucket?,
    val selectedEntries: List<AuthHistoryEntry>,
    val failures: List<AuthHistoryEntry>,
    val appSummary: List<AuthHistoryAppSummary>
)

/** Bounded pages avoid retaining an ever-growing Column after repeated “show more” taps. */
@Composable
private fun HistoryPageControls(page: Int, total: Int, pageSize: Int, onPrevious: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("第 ${page + 1} 页，共 ${(total + pageSize - 1) / pageSize} 页", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onPrevious, enabled = page > 0) { Text("上一页") }
            TextButton(onClick = onNext, enabled = (page + 1) * pageSize < total) { Text("下一页") }
        }
    }
}

@Composable
private fun AuthHistoryBarChart(report: AuthHistoryReport, selectedStartMs: Long?, onSelect: (Long) -> Unit) {
    val maxFailures = report.buckets.maxOfOrNull { it.failureCount }?.coerceAtLeast(1) ?: 1
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("轻点柱条查看该时段的记录", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val showEachLabel = !report.isHourly && maxWidth / report.buckets.size.coerceAtLeast(1) >= 36.dp * fontScale
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("不通过次数 · 最高 ${report.buckets.maxOfOrNull { it.failureCount } ?: 0} 次",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Every real hour/day shares the available width, including 23/25-hour DST days.
                // Bucket totals remain available by tapping or reading its accessibility label.
                Row(Modifier.fillMaxWidth().testTag("history_chart"), verticalAlignment = Alignment.Bottom) {
                    report.buckets.forEach { bucket ->
                        AuthHistoryBar(bucket, maxFailures, bucket.startMs == selectedStartMs,
                            showCount = showEachLabel, modifier = Modifier.weight(1f)) { onSelect(bucket.startMs) }
                    }
                }
                if (showEachLabel) {
                    Row(Modifier.fillMaxWidth()) {
                        report.buckets.forEach { bucket ->
                            Text(bucket.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    val labels = if (report.isHourly) (0..3).mapNotNull { quarter ->
                        report.buckets.getOrNull(report.buckets.size * quarter / 4)?.label
                            ?.substringBefore(":")?.toIntOrNull()?.let { "${it}时" }
                    } + "24时"
                    else listOfNotNull(report.buckets.firstOrNull()?.label,
                        report.buckets.getOrNull(report.buckets.size / 2)?.label, report.buckets.lastOrNull()?.label)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        labels.forEach { label ->
                            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthHistoryBar(bucket: AuthHistoryBucket, maxFailures: Int, selected: Boolean,
    showCount: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val failureHeight = (100f * bucket.failureCount / maxFailures).dp
    val description = if (bucket.totalCount == 0) "${bucket.description}，没有认证记录"
        else "${bucket.description}，不通过 ${bucket.failureCount} 次，共 ${bucket.totalCount} 次结果"
    Column(
        modifier = modifier.clip(RoundedCornerShape(4.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClickLabel = "查看此时段的应用", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description; this.selected = selected }
            .testTag("history_bar_${bucket.startMs}").padding(horizontal = 1.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showCount) Text(if (bucket.totalCount == 0) "—" else bucket.failureCount.toString(),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.height(104.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().height(if (bucket.totalCount > 0) 3.dp else 1.dp)
                .background(if (bucket.totalCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
            if (bucket.failureCount > 0) {
                Box(Modifier.fillMaxWidth(0.72f).height(failureHeight.coerceAtLeast(4.dp)).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDateRangeDialog(today: LocalDate, initial: AuthHistoryDateRange, onDismiss: () -> Unit, onConfirm: (AuthHistoryDateRange) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            HistoryDateRangeContent(today, initial, onDismiss, onConfirm,
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
        }
    }
}

/** The bounded content also exercises short windows without changing device settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryDateRangeContent(
    today: LocalDate,
    initial: AuthHistoryDateRange,
    onDismiss: () -> Unit,
    onConfirm: (AuthHistoryDateRange) -> Unit,
    modifier: Modifier = Modifier
) {
    val firstDay = today.minusDays(6)
    val selectableDates = remember(firstDay, today) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                return date >= firstDay && date <= today
            }
            override fun isSelectableYear(year: Int): Boolean = year in firstDay.year..today.year
        }
    }
    val picker = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        initialSelectedEndDateMillis = initial.endInclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = firstDay.year..today.year,
        selectableDates = selectableDates
    )
    BoxWithConstraints(modifier.testTag("history_date_picker")) {
        val compact = maxHeight < 480.dp
        Column(Modifier.widthIn(max = 800.dp).fillMaxSize().align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("history_date_cancel")) { Text("取消") }
                if (compact) {
                    val format = remember { DateTimeFormatter.ofPattern("M/d") }
                    val start = picker.selectedStartDateMillis?.let {
                        format.format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC))
                    } ?: "开始日期"
                    val end = picker.selectedEndDateMillis?.let {
                        format.format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC))
                    } ?: "结束日期"
                    Text("$start — $end", modifier = Modifier.weight(1f).testTag("history_date_selection"),
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = {
                    val start = picker.selectedStartDateMillis ?: return@TextButton
                    val end = picker.selectedEndDateMillis ?: return@TextButton
                    onConfirm(AuthHistoryDateRange(Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate(),
                        Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()))
                }, enabled = picker.selectedStartDateMillis != null && picker.selectedEndDateMillis != null,
                    modifier = Modifier.testTag("history_date_confirm")) {
                    Text("完成")
                }
            }
            if (compact) {
                // Keep the calendar scrollable in short windows; the selected range
                // already appears beside the fixed confirmation controls above it.
                DateRangePicker(state = picker, modifier = Modifier.weight(1f), title = null,
                    headline = null, showModeToggle = false)
            } else {
                Text("选择最近 7 天内的日期", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                Text("按手机本地时间统计，包含开始和结束当天。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                DateRangePicker(state = picker, modifier = Modifier.weight(1f), title = null, showModeToggle = true)
            }
        }
    }
}

private fun formatDateRange(range: AuthHistoryDateRange): String {
    val format = DateTimeFormatter.ofPattern("yyyy年M月d日")
    return if (range.start == range.endInclusive) format.format(range.start)
    else "${format.format(range.start)} — ${format.format(range.endInclusive)}"
}

@Composable
private fun HelpStep(number: String, title: String, message: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Text(number, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
