package com.continuousauth.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowForward
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.continuousauth.auth.AuthHistoryEntry
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
        nowMs = nowMs
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
    nowMs: Long = System.currentTimeMillis()
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
                    Text("认证与分析", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text("了解当前状态，回顾最近 7 天的认证记录", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "authentication_status", contentType = "status") {
                AuthenticationStatusCard(authUiState, protectionMode, consentGranted,
                    onStart, onStop, onOpenSettings, onReviewConsent)
            }
            if (authUiState.lastUpdateTime > 0 && authUiState.lastDecision == AuthDecision.ABNORMAL &&
                protectionMode != ProtectionMode.OFF) {
                item(key = "authentication_warning", contentType = "warning") { AuthenticationWarning(authUiState.lastUpdateTime, protectionMode) }
            }
            item(key = "authentication_history", contentType = "history") {
                AuthenticationHistoryCard(history, hasUsageAccess, nowMs, onEnableUsageAccess,
                    if (protectionMode == ProtectionMode.OFF) onOpenSettings else if (!consentGranted) onReviewConsent else onStart,
                    when {
                        protectionMode == ProtectionMode.OFF -> "前往防护设置"
                        !consentGranted -> "查看知情同意"
                        else -> "开始认证"
                    },
                    showStartAction = !authUiState.authActive && !authUiState.isStarting)
            }
            item(key = "authentication_help", contentType = "help") {
                ExpandableSection("authentication_help", "如何使用持续认证", "开始后，像平常一样使用手机即可", Icons.Filled.HelpOutline) {
                    HelpStep("1", "先了解并选择", "在设置中阅读知情同意，选择允许采集的数据和防护方式。")
                    HelpStep("2", "轻点开始认证", "应用会自动准备连接。准备完成后，继续正常使用手机，系统会持续判断使用习惯是否相符。")
                    HelpStep("3", "随时回来查看", "下方统计自动保存最近 7 天的结果。没有记录时，表示尚未收到结果，不能据此判断是否通过。")
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
    onReviewConsent: () -> Unit
) {
    val enabled = mode != ProtectionMode.OFF
    val running = state.authActive && enabled && consentGranted
    val preparing = state.isStarting && enabled && consentGranted
    val title = when {
        !consentGranted -> "先了解数据使用"
        !enabled -> "持续认证已关闭"
        preparing -> "正在准备认证"
        running -> "正在持续认证"
        else -> "准备好，开始认证"
    }
    val explanation = when {
        !consentGranted -> "阅读知情同意后，由你决定是否开启。你也可以先浏览应用。"
        !enabled -> "当前不会进行身份判断。选择防护方式后，就可以开始认证。"
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
            if (state.authMessage.isNotBlank() && enabled && consentGranted) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                    Text(state.authMessage, Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            val action: () -> Unit = when {
                !consentGranted -> onReviewConsent
                !enabled -> onOpenSettings
                preparing || running -> onStop
                else -> onStart
            }
            val label = when {
                !consentGranted -> "查看知情同意"
                !enabled -> "前往防护设置"
                preparing -> "取消准备"
                running -> "停止认证"
                else -> "开始认证"
            }
            Button(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("authentication_action")) {
                Icon(if (running || preparing) Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            Surface(onClick = onOpenSettings, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("防护方式：${protectionModeLabel(mode)}", style = MaterialTheme.typography.labelLarge)
                        Text(when (mode) {
                            ProtectionMode.OFF -> "关闭后，不启用持续认证"
                            ProtectionMode.MONITOR_ONLY -> "仅记录结果，不弹窗、不限制使用"
                            ProtectionMode.ALERT -> "认证不通过时，通过系统弹窗提醒"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.ArrowForward, contentDescription = "调整防护方式", modifier = Modifier.size(20.dp))
                }
            }
            if (running || preparing) {
                Text("停止认证后，数据采集仍按设置运行；可在设置中关闭采集。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun protectionModeLabel(mode: ProtectionMode): String = when (mode) {
    ProtectionMode.OFF -> "关闭"
    ProtectionMode.MONITOR_ONLY -> "仅监测"
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
            Text(if (mode == ProtectionMode.MONITOR_ONLY) "当前为仅监测模式，不会弹窗或限制使用。"
                else "当前为即时警示模式。系统弹窗需要在设置中允许显示在其他应用上层。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuthenticationHistoryCard(
    history: List<AuthHistoryEntry>,
    hasUsageAccess: Boolean,
    nowMs: Long,
    onEnableUsageAccess: () -> Unit,
    onStartAction: () -> Unit,
    startActionLabel: String,
    showStartAction: Boolean
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

    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("认证结果分析", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Text("结果自动保存在本机，保留最近 7 天。", style = MaterialTheme.typography.bodyMedium,
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
                Text(if (content == null) "记录较多时需要一点时间，请稍候。" else if (report.entries.isEmpty()) "这个时段没有收到认证结果，不代表全部通过。"
                    else "共收到 ${report.entries.size} 次结果 · ${report.entries.size - report.failureCount} 次通过",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AuthHistoryBarChart(report, selectedBucket?.startMs, nowMs) { start ->
                selectedBucketStart = if (selectedBucketStart == start) null else start
            }
            if (content != null && report.entries.isEmpty()) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (showStartAction) "开始认证后，结果会自动出现在这里。也可以切换日期，查看已有记录。"
                            else "认证结果到达后会自动更新。你也可以切换日期，查看已有记录。",
                            style = MaterialTheme.typography.bodyMedium)
                        if (showStartAction) {
                            OutlinedButton(onClick = onStartAction, modifier = Modifier.fillMaxWidth()) { Text(startActionLabel) }
                        }
                    }
                }
            }
            HorizontalDivider()
            if (selectedBucket != null) {
                Text("已选：${selectedBucket.description}", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.testTag("history_selected_bucket"))
                TextButton(onClick = { selectedBucketStart = null }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("查看整个时段")
                }
            }
            Text("不通过时正在使用的应用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("应用名称仅帮助你回顾当时的使用场景，不代表该应用存在问题。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun AuthHistoryBarChart(report: AuthHistoryReport, selectedStartMs: Long?, nowMs: Long, onSelect: (Long) -> Unit) {
    val scrollState = rememberScrollState()
    val maxFailures = report.buckets.maxOfOrNull { it.failureCount }?.coerceAtLeast(1) ?: 1
    val density = LocalDensity.current
    val minBarWidth = (if (report.isHourly) 52.dp else 48.dp) * density.fontScale.coerceAtLeast(1f)
    val lastRelevantIndex = report.buckets.indexOfLast { it.totalCount > 0 }.takeIf { it >= 0 }
        ?: report.buckets.indexOfLast { it.startMs <= nowMs }.coerceAtLeast(0)
    LaunchedEffect(report.buckets.firstOrNull()?.startMs, report.isHourly) {
        // Put recent hours into view on first opening; later results never move a user's scroll.
        if (report.isHourly) scrollState.scrollTo(with(density) { ((minBarWidth + 8.dp) * (lastRelevantIndex - 3).coerceAtLeast(0)).roundToPx() })
        else scrollState.scrollTo(0)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (report.isHourly) "每小时的不通过次数 · 左右滑动查看" else "每天的不通过次数 · 左右滑动查看",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("轻点柱条查看应用；“—”表示没有记录。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val barWidth = maxOf(minBarWidth, (maxWidth - 8.dp * (report.buckets.size - 1)) / report.buckets.size.coerceAtLeast(1))
            Row(Modifier.fillMaxWidth().horizontalScroll(scrollState).testTag("history_chart"),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                report.buckets.forEach { bucket ->
                    AuthHistoryBar(bucket, maxFailures, barWidth, bucket.startMs == selectedStartMs) { onSelect(bucket.startMs) }
                }
            }
        }
    }
}

@Composable
private fun AuthHistoryBar(bucket: AuthHistoryBucket, maxFailures: Int, barWidth: androidx.compose.ui.unit.Dp, selected: Boolean, onClick: () -> Unit) {
    val failureHeight = (100f * bucket.failureCount / maxFailures).dp
    val description = if (bucket.totalCount == 0) "${bucket.description}，没有认证记录"
        else "${bucket.description}，不通过 ${bucket.failureCount} 次，共 ${bucket.totalCount} 次结果"
    Column(
        modifier = Modifier.width(barWidth).clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClickLabel = "查看此时段的应用", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description; this.selected = selected }
            .testTag("history_bar_${bucket.startMs}").padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(if (bucket.totalCount == 0) "—" else bucket.failureCount.toString(),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Box(Modifier.height(104.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            if (bucket.failureCount > 0) {
                Box(Modifier.width(24.dp).height(failureHeight.coerceAtLeast(4.dp)).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)))
            }
        }
        Text(bucket.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDateRangeDialog(today: LocalDate, initial: AuthHistoryDateRange, onDismiss: () -> Unit, onConfirm: (AuthHistoryDateRange) -> Unit) {
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).widthIn(max = 800.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = {
                        val start = picker.selectedStartDateMillis ?: return@TextButton
                        val end = picker.selectedEndDateMillis ?: return@TextButton
                        onConfirm(AuthHistoryDateRange(Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate(),
                            Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()))
                    }, enabled = picker.selectedStartDateMillis != null && picker.selectedEndDateMillis != null) {
                        Text("完成")
                    }
                }
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
