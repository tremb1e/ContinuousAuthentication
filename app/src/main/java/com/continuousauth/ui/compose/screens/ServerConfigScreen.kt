package com.continuousauth.ui.compose.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.continuousauth.auth.AuthHistoryRepository
import com.continuousauth.network.ServerEndpointNormalizer
import com.continuousauth.privacy.ConsentState
import com.continuousauth.privacy.DeletionState
import com.continuousauth.settings.ProtectionMode
import com.continuousauth.storage.FileQueueManager
import com.continuousauth.ui.MainViewModel
import com.continuousauth.ui.compose.components.ExpandableSection
import com.continuousauth.ui.compose.components.PrivacyNoticeContent
import com.continuousauth.utils.BackgroundExecutionHelper
import java.net.URI

/** All preferences live here; each section starts collapsed. */
@Composable
fun ServerConfigScreen(
    viewModel: MainViewModel,
    onRequestConsent: () -> Unit,
    protectionRequest: Int = 0
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    var handledProtectionRequest by rememberSaveable { mutableIntStateOf(0) }
    val consent by viewModel.consentState.observeAsState(ConsentState.UNKNOWN)
    val deletion by viewModel.deletionState.observeAsState(DeletionState.IDLE)
    val protectionMode by viewModel.protectionMode.collectAsStateWithLifecycle()
    val sensors by viewModel.sensorCollectionSettings.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.uploadPolicyWiFiOnly.observeAsState(false)
    val initialSmartTransmission = remember(viewModel) { viewModel.getSmartTransmissionEnabled() }
    val smartTransmission by viewModel.smartTransmissionEnabled.observeAsState(initialSmartTransmission)
    val isUploading by viewModel.isEncryptedUploading.observeAsState(false)
    val collectionStatus by viewModel.collectionStatus.observeAsState("")
    val serverTestResult by viewModel.serverTestResult.observeAsState(null)
    val lifecycleOwner = LocalLifecycleOwner.current
    var systemAccess by remember { mutableStateOf(readSystemAccess(context, viewModel)) }
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    val deleting = deletion == DeletionState.IN_PROGRESS
    val collectionChanging = collectionStatus == "STARTING" || collectionStatus == "STOPPING"
    val canEditServer = !isUploading && !collectionChanging
    val savedServer = remember(viewModel) { viewModel.getServerConfig() }
    var serverAddress by rememberSaveable { mutableStateOf("${savedServer.scheme}://${formatServerHost(savedServer.host)}") }
    var serverPort by rememberSaveable { mutableStateOf(savedServer.port.toString()) }
    var serverValidation by rememberSaveable { mutableStateOf<String?>(null) }
    var serverFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    val testing = serverTestResult?.startsWith("正在测试") == true
    DisposableEffect(context, lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) systemAccess = readSystemAccess(context, viewModel)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(serverTestResult) {
        serverTestResult?.let { serverFeedback = it }
    }

    LaunchedEffect(protectionRequest) {
        if (protectionRequest > handledProtectionRequest) {
            listState.scrollToItem(2)
            handledProtectionRequest = protectionRequest
        }
    }

    fun saveServer(test: Boolean) {
        serverValidation = validateServerSettings(serverAddress, serverPort)
        if (serverValidation != null) return
        focusManager.clearFocus()
        viewModel.saveServerConfig(serverAddress.trim(), serverPort.toInt())
        val normalized = viewModel.getServerConfig()
        serverAddress = "${normalized.scheme}://${formatServerHost(normalized.host)}"
        serverPort = normalized.port.toString()
        serverFeedback = "服务器设置已保存"
        if (test) viewModel.testServerConnection(serverAddress, serverPort)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 800.dp).fillMaxSize().imePadding().testTag("settings_screen"),
            state = listState,
            contentPadding = PaddingValues(16.dp, 20.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "settings_header", contentType = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("按需调整，安心使用", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("点击栏目展开设置。你的选择会自动保留；服务器地址需点击保存。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item(key = "consent", contentType = "settings") {
                ExpandableSection("consent", "数据采集与知情同意", consentLabel(consent), Icons.Default.PrivacyTip) {
                    CollectionConsentSettings(
                        consent = consent,
                        sensorsEnabled = sensors.allEnabled,
                        deletion = deletion,
                        onSensorsChanged = viewModel::setAllSensorsEnabled,
                        onReviewConsent = onRequestConsent,
                        onWithdrawConsent = { confirmation = "withdraw" },
                        onRetryServerDeletion = viewModel::retryPendingDeletionRequests
                    )
                }
            }

            item(key = "protection", contentType = "settings") {
                ExpandableSection("protection", "防护设置", protectionSummary(protectionMode, systemAccess.overlayAllowed), Icons.Default.Shield, expandRequest = protectionRequest) {
                    HelpText("先选择提醒方式，再到“偷用防护”点击启用认证。这里的设置不会自动启用认证。")
                    Column(Modifier.selectableGroup()) {
                        ProtectionChoice(ProtectionMode.MONITOR_ONLY, protectionMode, "仅监测（默认）", "启用认证后，仅记录结果，不弹窗提醒。", !deleting, viewModel::setProtectionMode)
                        ProtectionChoice(ProtectionMode.ALERT, protectionMode, "即时警示", "启用认证后，认证不通过时通过系统弹窗提醒。", !deleting, viewModel::setProtectionMode)
                    }
                    if (protectionMode == ProtectionMode.ALERT) {
                        Text(if (systemAccess.overlayAllowed) "已允许系统弹窗" else "尚未允许系统弹窗，目前无法在其他应用上方提醒你。", style = MaterialTheme.typography.bodyMedium, color = if (systemAccess.overlayAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        OutlinedButton(
                            onClick = { openSystemSettings(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("overlay_permission")
                        ) { Text(if (systemAccess.overlayAllowed) "管理系统弹窗权限" else "允许在其他应用上方显示") }
                    }
                }
            }

            item(key = "background", contentType = "settings") {
                ExpandableSection("background", "后台运行与权限", if (systemAccess.backgroundRestricted) "后台受限制，可能影响持续使用" else "查看电池优化与系统权限", Icons.Default.BatterySaver) {
                    HelpText("想在切换应用后继续使用，可检查以下系统设置。不同手机的选项名称可能略有不同。")
                    InfoRow("电池优化", if (systemAccess.batteryExempt) "已允许不受电池优化限制" else "正在接受系统电池优化")
                    OutlinedButton(onClick = {
                        if (!BackgroundExecutionHelper.openBatterySettings(context) { context.startActivity(it) }) showSettingsError(context)
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("打开电池优化设置") }
                    InfoRow("后台使用", if (systemAccess.backgroundRestricted) "受到系统限制" else "未检测到系统限制")
                    OutlinedButton(onClick = {
                        if (!BackgroundExecutionHelper.openAppSettings(context) { context.startActivity(it) }) showSettingsError(context)
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看应用后台设置") }
                    HorizontalDivider()
                    InfoRow("应用使用情况权限", if (systemAccess.usageAllowed) "已允许" else "未允许")
                    HelpText("用于记录认证发生时你正在使用哪个应用。")
                    OutlinedButton(onClick = { openSystemSettings(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("管理应用使用情况权限") }
                    InfoRow("通知权限", if (systemAccess.notificationsAllowed) "已允许" else "未允许")
                    OutlinedButton(onClick = { openSystemSettings(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("管理运行状态通知") }
                }
            }

            item(key = "transmission", contentType = "settings") {
                ExpandableSection("transmission", "传输策略", if (wifiOnly) "仅通过 Wi-Fi 上传" else "Wi-Fi 和移动网络均可上传（默认）", Icons.Default.Wifi) {
                    PreferenceSwitch("仅通过 Wi-Fi 上传", "默认关闭。打开后，在没有 Wi-Fi 时暂停上传，认证结果可能延后。", wifiOnly, "wifi_only", !deleting, viewModel::setUploadPolicyWiFiOnly)
                    PreferenceSwitch("智能传输", "仅在凌晨、锁屏且电量高于 60% 时上传，其余时间先保存在本机（最多 3 GB）。开启后可能无法及时得到认证结果。", smartTransmission, "smart_transmission", !deleting, viewModel::setSmartTransmissionEnabled)
                }
            }

            item(key = "server", contentType = "settings") {
                ExpandableSection("server", "服务器设置", "设置认证服务的连接地址", Icons.Default.Cloud) {
                    HelpText("使用提供给你的服务器地址。一般无需更改；更换前请先停止采集和认证。")
                    OutlinedTextField(
                        value = serverAddress,
                        onValueChange = { serverAddress = it; serverValidation = null; serverFeedback = null },
                        label = { Text("服务器地址") },
                        placeholder = { Text("https://${ServerEndpointNormalizer.DEFAULT_HOST}") },
                        modifier = Modifier.fillMaxWidth().testTag("server_address"),
                        singleLine = true,
                        enabled = canEditServer && !testing,
                        isError = serverValidation != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                    )
                    OutlinedTextField(
                        value = serverPort,
                        onValueChange = { serverPort = it; serverValidation = null; serverFeedback = null },
                        label = { Text("端口") },
                        supportingText = { Text("通常为 443；以服务提供方给出的值为准") },
                        modifier = Modifier.fillMaxWidth().testTag("server_port"),
                        singleLine = true,
                        enabled = canEditServer && !testing,
                        isError = serverValidation != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
                    )
                    serverValidation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    if (serverAddress.startsWith("http://", ignoreCase = true)) HelpText("当前填写的是 HTTP 地址，连接本身不加密。若服务器支持，请使用 HTTPS。")
                    Button(onClick = { saveServer(false) }, enabled = canEditServer && !testing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保存服务器设置") }
                    OutlinedButton(onClick = { saveServer(true) }, enabled = canEditServer && !testing && !deleting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("test_server")) { Text(if (testing) "正在测试连接…" else "保存并测试连接") }
                    TextButton(onClick = {
                        serverAddress = "https://${ServerEndpointNormalizer.DEFAULT_HOST}"
                        serverPort = ServerEndpointNormalizer.DEFAULT_HTTPS_PORT.toString()
                        serverValidation = null
                        serverFeedback = "已填入默认地址，请点击保存"
                    }, enabled = canEditServer && !testing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("恢复默认地址") }
                    serverFeedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (!canEditServer) HelpText("停止采集与认证后，可以修改或测试服务器。")
                }
            }

            item(key = "storage", contentType = "settings") {
                ExpandableSection("storage", "数据与存储", "认证记录 ${AuthHistoryRepository.RETENTION_DAYS} 天 · 传感器缓存 ${FileQueueManager.RETENTION_DAYS} 天", Icons.Default.Storage) {
                    val queueStats by viewModel.fileQueueStats.observeAsState(null)
                    InfoRow("认证记录保存期限", "固定 ${AuthHistoryRepository.RETENTION_DAYS} 天")
                    InfoRow("传感器缓存保存期限", "固定 ${FileQueueManager.RETENTION_DAYS} 天")
                    HelpText("过期数据会自动清理，保存期限无需设置。传感器缓存上传确认后会提前删除，空间不足时也可能清理较早的数据。")
                    HorizontalDivider()
                    InfoRow("待上传数据", "${queueStats?.pendingPackets ?: 0} 个数据包")
                    InfoRow("本机缓存占用", formatStorageBytes(queueStats?.totalSizeBytes ?: 0L))
                    HelpText("清理缓存会删除本机等待上传的传感器数据，无法恢复。")
                    OutlinedButton(onClick = { confirmation = "clear_queue" }, enabled = !isUploading && !collectionChanging && !deleting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("clear_queue")) { Text("清理本机传感器缓存") }
                    if (isUploading || collectionChanging) HelpText("请先停止采集与上传，再清理缓存。")
                }
            }
        }
    }

    confirmation?.let { action ->
        val title = if (action == "withdraw") "撤回同意并删除数据？" else "清理本机传感器缓存？"
        val message = when (action) {
            "withdraw" -> "采集、上传和认证会停止，本应用内的传感器数据和认证记录将被删除，本机删除无法恢复。当前服务器尚未提供自动删除接口，已上传的数据需联系服务器管理方删除，这里会显示为待处理。你手动导出的副本需自行删除。"
            else -> "这会删除本机待上传的传感器数据，删除后无法恢复。最近 ${AuthHistoryRepository.RETENTION_DAYS} 天的认证结果仍可查看。"
        }
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    when (action) {
                        "withdraw" -> viewModel.withdrawConsentAndDeleteData()
                        "clear_queue" -> viewModel.clearFileQueue()
                    }
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } }
        )
    }
}

@Composable
internal fun CollectionConsentSettings(
    consent: ConsentState,
    sensorsEnabled: Boolean,
    deletion: DeletionState,
    onSensorsChanged: (Boolean) -> Unit,
    onReviewConsent: () -> Unit,
    onWithdrawConsent: () -> Unit,
    onRetryServerDeletion: () -> Unit
) {
    val deleting = deletion == DeletionState.IN_PROGRESS
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PreferenceSwitch(
            "采集传感器数据",
            "统一控制加速度计、陀螺仪和磁力计。关闭后会停止采集与认证。",
            sensorsEnabled,
            "sensor_collection",
            !deleting,
            onSensorsChanged
        )
        if (!sensorsEnabled) HelpText("传感器采集已关闭。启用认证前，请先打开此开关。")
        Text(consentLabel(consent), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (consent == ConsentState.GRANTED) {
            OutlinedButton(onClick = onWithdrawConsent, enabled = !deleting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("withdraw_consent")) {
                Text(if (deleting) "正在停止采集并删除…" else "撤回同意并删除数据")
            }
        } else {
            Button(onClick = onReviewConsent, enabled = !deleting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review_consent")) {
                Text("查看并决定是否同意")
            }
        }
        DeletionFeedback(deletion)
        if (deletion == DeletionState.PARTIAL_SUCCESS) {
            OutlinedButton(onClick = onRetryServerDeletion, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重试服务器删除请求") }
        }
        if (deletion == DeletionState.FAILED) {
            OutlinedButton(onClick = onWithdrawConsent, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重试撤回和删除") }
        }
        HorizontalDivider()
        PrivacyNoticeContent()
    }
}

@Composable
internal fun PreferenceSwitch(title: String, description: String, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun ProtectionChoice(mode: ProtectionMode, selected: ProtectionMode, title: String, description: String, enabled: Boolean = true, onSelect: (ProtectionMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("protection_${mode.name.lowercase()}").selectable(selected = mode == selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(mode) }).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected = mode == selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HelpText(message: String) { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
private fun DeletionFeedback(state: DeletionState) {
    val message = when (state) {
        DeletionState.IN_PROGRESS -> "正在停止采集并删除数据，请稍候…"
        DeletionState.SUCCESS -> "数据删除已完成"
        DeletionState.PARTIAL_SUCCESS -> "本应用内的数据已删除。服务器删除仍待处理：当前服务端尚未提供自动删除接口，请联系服务器管理方处理。"
        DeletionState.FAILED -> "数据删除未完成，请重试。"
        DeletionState.IDLE -> return
    }
    Text(message, style = MaterialTheme.typography.bodyMedium, color = if (state == DeletionState.FAILED || state == DeletionState.PARTIAL_SUCCESS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}

private fun consentLabel(state: ConsentState): String = when (state) {
    ConsentState.GRANTED -> "已同意数据采集与使用"
    ConsentState.WITHDRAWN -> "已撤回同意，采集已停止"
    ConsentState.NOT_GRANTED -> "尚未同意，采集不会开始"
    ConsentState.UNKNOWN -> "正在确认同意状态"
}

private fun protectionSummary(mode: ProtectionMode, overlayAllowed: Boolean): String = when (mode) {
    ProtectionMode.OFF, ProtectionMode.MONITOR_ONLY -> "仅监测：记录结果，不弹窗"
    ProtectionMode.ALERT -> if (overlayAllowed) "即时警示：不通过时弹窗提醒" else "即时警示：尚需允许系统弹窗"
}

private data class SystemAccess(val overlayAllowed: Boolean, val batteryExempt: Boolean, val backgroundRestricted: Boolean, val usageAllowed: Boolean, val notificationsAllowed: Boolean)
private fun readSystemAccess(context: Context, viewModel: MainViewModel) = SystemAccess(
    overlayAllowed = Settings.canDrawOverlays(context),
    batteryExempt = BackgroundExecutionHelper.isBatteryOptimizationExempt(context),
    backgroundRestricted = BackgroundExecutionHelper.isBackgroundRestricted(context),
    usageAllowed = viewModel.isUsageStatsPermissionGranted(),
    notificationsAllowed = viewModel.isPostNotificationsPermissionGranted()
)

private fun openSystemSettings(context: Context, intent: Intent) {
    val opened = runCatching { context.startActivity(intent); true }.getOrDefault(false)
    if (!opened && !BackgroundExecutionHelper.openAppSettings(context) { context.startActivity(it) }) showSettingsError(context)
}
private fun showSettingsError(context: Context) { Toast.makeText(context, "暂时无法打开系统设置，请在手机设置中找到本应用", Toast.LENGTH_LONG).show() }

internal fun validateServerSettings(address: String, port: String): String? {
    val raw = address.trim()
    if (raw.isBlank()) return "请输入服务器地址"
    if (raw.any { it.isWhitespace() }) return "服务器地址不能包含空格"
    val parseTarget = if (raw.contains("://")) raw else "https://$raw"
    val uri = runCatching { URI(parseTarget) }.getOrNull() ?: return "服务器地址格式不正确"
    if (uri.scheme.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null || (!uri.path.isNullOrEmpty() && uri.path != "/")) return "请输入主机地址，例如 https://ca.macrz.com，不要包含页面路径"
    if (uri.port != -1) return "请把端口填写在下方“端口”中"
    val parsedPort = port.toIntOrNull()
    if (parsedPort == null || parsedPort !in 1..65535) return "端口应为 1 至 65535 之间的整数"
    return null
}

private fun formatStorageBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun formatServerHost(host: String): String =
    if (':' in host && !host.startsWith("[")) "[$host]" else host
