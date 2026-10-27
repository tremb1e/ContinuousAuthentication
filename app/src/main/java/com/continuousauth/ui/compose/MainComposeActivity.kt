package com.continuousauth.ui.compose

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.outlined.Settings as SettingsOutlineIcon
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.continuousauth.R
import com.continuousauth.privacy.ConsentState
import com.continuousauth.ui.MainViewModel
import com.continuousauth.ui.compose.dialogs.PrivacyAgreementDialog
import com.continuousauth.ui.compose.screens.DetailedInfoScreen
import com.continuousauth.ui.compose.screens.PrivacySettingsScreen
import com.continuousauth.ui.compose.screens.SensorsScreen
import com.continuousauth.ui.compose.screens.ServerConfigScreen
import com.continuousauth.ui.dialogs.BatteryOptimizationDialog
import com.continuousauth.ui.dialogs.NotificationPermissionDialog
import com.continuousauth.ui.dialogs.UsageStatsPermissionDialog
import com.continuousauth.ui.theme.ContinuousAuthTheme
import com.continuousauth.ui.viewmodels.SensorDataViewModel
import com.continuousauth.utils.NotificationPermissionHelper
import com.continuousauth.utils.BackgroundExecutionHelper
import com.continuousauth.utils.UsageStatsHelper
import dagger.hilt.android.AndroidEntryPoint

/**
 * 现代化的 Compose 主活动
 * 使用 Material 3 设计和底部导航
 */
@OptIn(ExperimentalAnimationApi::class)
@AndroidEntryPoint
class MainComposeActivity : FragmentActivity() {
    private val TAG = "MainComposeActivity"
    private val viewModel: MainViewModel by viewModels()
    private val sensorViewModel: SensorDataViewModel by viewModels()
    private val permissionGuidance = PermissionGuidanceCoordinator()
    private var consentAllowsGuidance = false
    private var openAuthenticationRequest by mutableStateOf(0)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        completePermissionGuidance(PermissionGuidanceStep.NOTIFICATIONS)
    }
    private val usageStatsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        completePermissionGuidance(PermissionGuidanceStep.USAGE_STATS)
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        finishBatteryOptimizationGuidance()
    }

    companion object {
        const val PREFS_NAME = "app_prefs"
        const val PRIVACY_AGREEMENT_SHOWN = "privacy_agreement_shown"
        private const val OFFERED_GUIDANCE = "offered_permission_guidance"
        private const val ACTIVE_GUIDANCE = "active_permission_guidance"
        private const val NOTIFICATION_DIALOG_TAG = "notification_permission"
        private const val USAGE_DIALOG_TAG = "usage_stats_permission"
        private const val BATTERY_DIALOG_TAG = "battery_optimization"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        savedInstanceState?.let { state ->
            val offered = state.getStringArrayList(OFFERED_GUIDANCE).orEmpty().mapNotNull { name ->
                PermissionGuidanceStep.entries.find { it.name == name }
            }.toSet()
            val active = PermissionGuidanceStep.entries.find { it.name == state.getString(ACTIVE_GUIDANCE) }
            permissionGuidance.restore(offered, active)
        }

        setContent {
            ContinuousAuthTheme {
                MainApp(viewModel, sensorViewModel)
            }
        }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_authentication", false)) openAuthenticationRequest++
    }

    override fun onPostResume() {
        super.onPostResume()
        attachPermissionDialogListeners()
        checkPermissionsAndGuidance()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(OFFERED_GUIDANCE, ArrayList(permissionGuidance.offeredSteps.map { it.name }))
        outState.putString(ACTIVE_GUIDANCE, permissionGuidance.activeStep?.name)
        super.onSaveInstanceState(outState)
    }

    /**
     * 主应用组件
     */
    @Composable
    fun MainApp(viewModel: MainViewModel, sensorViewModel: SensorDataViewModel) {
        val navController = rememberNavController()
        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
        var protectionRequest by rememberSaveable { mutableStateOf(0) }
        LaunchedEffect(openAuthenticationRequest) {
            if (openAuthenticationRequest > 0) {
                navController.navigateToTab(Screen.Privacy.route)
            }
        }
        val context = LocalContext.current

        // 检查隐私协议状态
        val prefs = remember(context) {
            context.getSharedPreferences(MainComposeActivity.PREFS_NAME, Context.MODE_PRIVATE)
        }
        var showPrivacyDialog by rememberSaveable {
            mutableStateOf(!prefs.getBoolean(MainComposeActivity.PRIVACY_AGREEMENT_SHOWN, false))
        }

        // 观察隐私同意状态
        val consentStateLiveData = viewModel.consentState.observeAsState()
        val consentState = consentStateLiveData.value ?: ConsentState.UNKNOWN
        LaunchedEffect(consentState, showPrivacyDialog) {
            consentAllowsGuidance = consentState == ConsentState.GRANTED && !showPrivacyDialog
            checkPermissionsAndGuidance()
        }

        // 如果需要显示隐私协议对话框
        if (showPrivacyDialog) {
            PrivacyAgreementDialog(
                onAccept = {
                    // 用户接受隐私协议
                    prefs.edit()
                        .putBoolean(MainComposeActivity.PRIVACY_AGREEMENT_SHOWN, true)
                        .apply()

                    // 通知ViewModel记录用户同意
                    viewModel.grantPrivacyConsent()
                    showPrivacyDialog = false
                },
                onDecline = {
                    // Declining leaves settings available without enabling collection.
                    prefs.edit().putBoolean(PRIVACY_AGREEMENT_SHOWN, true).apply()
                    showPrivacyDialog = false
                }
            )
        }

        val snackbarHostState = remember { SnackbarHostState() }
        val errorMessage by viewModel.errorMessage.observeAsState()
        LaunchedEffect(errorMessage) {
            errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                snackbarHostState.showSnackbar(
                    message = message, actionLabel = "知道了", duration = SnackbarDuration.Short
                )
                viewModel.clearErrorMessage()
            }
        }
        // Browsing and reviewing consent stay available before consent is granted.
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    AnimatedBottomBar(
                        currentRoute = currentRoute,
                        onNavigate = { navController.navigateToTab(it) }
                    )
                },
                containerColor = MaterialTheme.colorScheme.background
            ) { paddingValues ->
                NavHost(
                    navController = navController,
                    startDestination = Screen.Privacy.route,
                    modifier = Modifier.padding(paddingValues).consumeWindowInsets(paddingValues),
                    enterTransition = {
                        fadeIn(animationSpec = tween(160))
                    },
                    exitTransition = {
                        fadeOut(animationSpec = tween(120))
                    }
                ) {
                    composable(Screen.Sensors.route) {
                        SensorsScreen(viewModel, sensorViewModel)
                    }
                    composable(Screen.Server.route) {
                        ServerConfigScreen(viewModel, onRequestConsent = { showPrivacyDialog = true },
                            protectionRequest = protectionRequest)
                    }
                    composable(Screen.Details.route) {
                        DetailedInfoScreen(
                            onNavigateBack = { navController.popBackStack() },
                            mainViewModel = viewModel
                        )
                    }

                    composable(Screen.Privacy.route) {
                        PrivacySettingsScreen(
                            onNavigateBack = { navController.popBackStack() },
                            viewModel = viewModel,
                            onNavigateSettings = {
                                protectionRequest++
                                navController.navigateToTab(Screen.Server.route)
                            },
                            onReviewConsent = { showPrivacyDialog = true },
                            onEnableUsageAccess = { navController.navigateToTab(Screen.Server.route) }
                        )
                    }
                }
            }
    }

    /**
     * 动画底部导航栏
     */
    @OptIn(ExperimentalAnimationApi::class)
    @Composable
    fun AnimatedBottomBar(
        currentRoute: String?,
        onNavigate: (String) -> Unit
    ) {
        val context = LocalContext.current
        val screens = listOf(Screen.Privacy, Screen.Server, Screen.Sensors, Screen.Details)
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            screens.forEach { screen ->
                val selected = currentRoute == screen.route
                val screenTitle = getScreenTitle(context, screen)
                NavigationBarItem(
                    modifier = Modifier.testTag("navigation_${screen.route}"),
                    selected = selected,
                    onClick = { onNavigate(screen.route) },
                    icon = {
                        Icon(
                            imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                            contentDescription = null
                        )
                    },
                    label = { Text(screenTitle, style = MaterialTheme.typography.labelSmall) },
                    alwaysShowLabel = true
                )
            }
        }
    }

    /**
     * 屏幕导航定义
     */
    @Composable
    fun getScreenTitle(context: Context, screen: Screen): String {
        return when (screen) {
            Screen.Sensors -> "传感器数据"
            Screen.Server -> "设置"
            Screen.Details -> "详细信息"
            Screen.Privacy -> "偷用防护"
        }
    }

    /** Use the same saved tab stacks for shortcuts and the bottom navigation. */
    private fun NavHostController.navigateToTab(route: String) {
        if (currentDestination?.route == route) return
        // Home already remains at the bottom of the active stack. Restoring a saved
        // stack under its ID can otherwise reopen Settings after the shortcut is used.
        if (route == Screen.Privacy.route && popBackStack(route, inclusive = false, saveState = true)) return
        navigate(route) {
            popUpTo(graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = route != Screen.Privacy.route
        }
    }

    private fun showNotificationPermissionDialog() {
        NotificationPermissionDialog(::onNotificationGuidanceResult)
            .show(supportFragmentManager, NOTIFICATION_DIALOG_TAG)
    }

    private fun onNotificationGuidanceResult(granted: Boolean) {
        if (granted) {
            try {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to request notification permission", e)
                completePermissionGuidance(PermissionGuidanceStep.NOTIFICATIONS)
            }
        } else {
            completePermissionGuidance(PermissionGuidanceStep.NOTIFICATIONS)
        }
    }
    sealed class Screen(
        val route: String,
        val selectedIcon: ImageVector,
        val unselectedIcon: ImageVector
    ) {
        object Sensors : Screen(
            route = "sensors",
            selectedIcon = Icons.Filled.Sensors,
            unselectedIcon = Icons.Outlined.Sensors
        )

        object Server : Screen(
            route = "server",
            selectedIcon = Icons.Filled.SettingsIcon,
            unselectedIcon = Icons.Outlined.SettingsOutlineIcon
        )

        object Details : Screen(
            route = "details",
            selectedIcon = Icons.Filled.Dashboard,
            unselectedIcon = Icons.Outlined.Dashboard
        )

        object Privacy : Screen(
            route = "privacy",
            selectedIcon = Icons.Filled.Security,
            unselectedIcon = Icons.Outlined.Security
        )
    }

    /**
     * 检查权限和引导流程
     */
    private fun checkPermissionsAndGuidance() {
        if (!consentAllowsGuidance || isFinishing || isDestroyed || supportFragmentManager.isStateSaved ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        when (permissionGuidance.nextStep(
            needsNotifications = NotificationPermissionHelper.shouldRequestNotificationPermission(this),
            needsUsageStats = !UsageStatsHelper.hasUsageStatsPermission(this),
            needsBatteryOptimization = !BackgroundExecutionHelper.isBatteryOptimizationExempt(this)
        )) {
            PermissionGuidanceStep.NOTIFICATIONS -> showNotificationPermissionDialog()
            PermissionGuidanceStep.USAGE_STATS -> showUsageStatsPermissionDialog()
            PermissionGuidanceStep.BATTERY_OPTIMIZATION -> showBatteryOptimizationDialog()
            null -> Unit
        }
    }

    /**
     * 显示Usage Stats权限对话框
     */
    private fun showUsageStatsPermissionDialog() {
        UsageStatsPermissionDialog(::onUsageStatsGuidanceResult)
            .show(supportFragmentManager, USAGE_DIALOG_TAG)
    }

    private fun onUsageStatsGuidanceResult(granted: Boolean) {
        if (granted) {
            try {
                usageStatsPermissionLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to open usage access settings", e)
                UsageStatsHelper.showErrorToast(this)
                completePermissionGuidance(PermissionGuidanceStep.USAGE_STATS)
            }
        } else {
            completePermissionGuidance(PermissionGuidanceStep.USAGE_STATS)
        }
    }

    /**
     * 显示电池优化对话框
     */
    private fun showBatteryOptimizationDialog() {
        BatteryOptimizationDialog(::onBatteryGuidanceResult)
            .show(supportFragmentManager, BATTERY_DIALOG_TAG)
    }

    private fun onBatteryGuidanceResult(disableOptimization: Boolean) {
        if (disableOptimization) {
            try {
                val opened = BackgroundExecutionHelper.openBatterySettings(this) {
                    batteryOptimizationLauncher.launch(it)
                }
                if (!opened) finishBatteryOptimizationGuidance()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to open battery optimization settings", e)
                finishBatteryOptimizationGuidance()
            }
        } else {
            finishBatteryOptimizationGuidance()
        }
    }

    private fun finishBatteryOptimizationGuidance() {
        completePermissionGuidance(PermissionGuidanceStep.BATTERY_OPTIMIZATION)
    }

    private fun completePermissionGuidance(step: PermissionGuidanceStep) {
        permissionGuidance.complete(step)
        // Let the previous DialogFragment dismiss before advancing to the next one.
        window.decorView.post { checkPermissionsAndGuidance() }
    }

    private fun attachPermissionDialogListeners() {
        (supportFragmentManager.findFragmentByTag(NOTIFICATION_DIALOG_TAG) as? NotificationPermissionDialog)
            ?.setOnResultListener(::onNotificationGuidanceResult)
        (supportFragmentManager.findFragmentByTag(USAGE_DIALOG_TAG) as? UsageStatsPermissionDialog)
            ?.setOnResultListener(::onUsageStatsGuidanceResult)
        (supportFragmentManager.findFragmentByTag(BATTERY_DIALOG_TAG) as? BatteryOptimizationDialog)
            ?.setOnResultListener(::onBatteryGuidanceResult)
    }
}
