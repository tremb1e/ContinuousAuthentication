package com.continuousauth.ui.compose

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.with
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
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
import com.continuousauth.utils.NotificationPermissionLauncher
import com.continuousauth.utils.UsageStatsPermissionLauncher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

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
    private lateinit var notificationPermissionLauncher: NotificationPermissionLauncher
    // 权限请求器
    private val usageStatsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 检查USAGE_STATS权限是否已授予
        viewModel.checkUsageStatsPermission()
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isSkipPermissionCheck=true
        // 记录用户已处理电池优化请求
        getSharedPreferences("app_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean(BATTERY_OPTIMIZATION_REQUESTED, true)
            .apply()
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isSkipPermissionCheck=true
        checkBatteryOptimization()
        Log.e(TAG, "notificationLauncher result: $result")
    }

    private var isSkipPermissionCheck : Boolean =false
    // 声明启动器
    private lateinit var usageStatsLauncher: UsageStatsPermissionLauncher

    companion object {
        const val PREFS_NAME = "app_prefs"
        const val PRIVACY_AGREEMENT_SHOWN = "privacy_agreement_shown"
        private const val BATTERY_OPTIMIZATION_REQUESTED = "battery_optimization_requested"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ContinuousAuthTheme {
                MainApp(viewModel, sensorViewModel)
            }
        }

        // 初始化启动器
//        try {
            usageStatsLauncher = UsageStatsPermissionLauncher.create(this)
            notificationPermissionLauncher= NotificationPermissionLauncher.create(this)
//            checkPermissionsAndGuidance()
//        } catch (e: Exception) {
//            // 处理权限启动器创建或使用过程中可能出现的异常
//            runOnUiThread {
//                Toast.makeText(
//                    this@MainComposeActivity,
//                    "权限初始化失败，请重试",
//                    Toast.LENGTH_SHORT
//                ).show()
//            }
//        }
    }


    private fun onUsageStatsPermissionGranted() {
        Toast.makeText(this, "当前前台应用: 权限已授予", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "当前前台应用: 权限已授予 - ${System.currentTimeMillis()}")
    }


    /**
     * 主应用组件
     */
    @Composable
    fun MainApp(viewModel: MainViewModel, sensorViewModel: SensorDataViewModel) {
        val navController = rememberNavController()
        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
        val context = LocalContext.current

        // 检查隐私协议状态
        val prefs =
            context.getSharedPreferences(MainComposeActivity.PREFS_NAME, Context.MODE_PRIVATE)
        var showPrivacyDialog by remember {
            mutableStateOf(!prefs.getBoolean(MainComposeActivity.PRIVACY_AGREEMENT_SHOWN, false))
        }

        // 观察隐私同意状态
        val consentStateLiveData = viewModel.consentState.observeAsState()
        val consentState = consentStateLiveData.value ?: ConsentState.UNKNOWN

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
                    viewModel.startDataCollectionService()
                    showPrivacyDialog = false
                },
                onDecline = {
                    // 用户拒绝隐私协议，退出应用
                    (context as? ComponentActivity)?.finish()
                }
            )
        }else{
            if (!isSkipPermissionCheck)
                checkPermissionsAndGuidance()
        }

        // 如果用户未同意隐私协议，显示提示界面
        if (consentState != ConsentState.GRANTED && !showPrivacyDialog) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.padding(32.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "请先同意隐私协议",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { showPrivacyDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("查看隐私协议")
                        }
                    }
                }
            }
        } else {
            // 只有在用户同意隐私协议后才显示主界面
            Scaffold(
                bottomBar = {
                    AnimatedBottomBar(
                        currentRoute = currentRoute,
                        onNavigate = { route ->
                            navController.navigate(route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                },
                containerColor = MaterialTheme.colorScheme.background
            ) { paddingValues ->
                NavHost(
                    navController = navController,
                    startDestination = Screen.Sensors.route,
                    modifier = Modifier.padding(paddingValues),
                    enterTransition = {
                        fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                            initialOffsetX = { it },
                            animationSpec = tween(300)
                        )
                    },
                    exitTransition = {
                        fadeOut(animationSpec = tween(300)) + slideOutHorizontally(
                            targetOffsetX = { -it },
                            animationSpec = tween(300)
                        )
                    }
                ) {
                    composable(Screen.Sensors.route) {
                        SensorsScreen(viewModel, sensorViewModel)
                    }
                    composable(Screen.Server.route) {
                        ServerConfigScreen(viewModel)
                    }
                    composable(Screen.Details.route) {
                        DetailedInfoScreen(
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable(Screen.Privacy.route) {
                        PrivacySettingsScreen(
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        } // 结束 else 语句
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
        val screens = listOf(Screen.Sensors, Screen.Server, Screen.Details, Screen.Privacy)

        NavigationBar(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                ),
            containerColor = Color.Transparent,
            tonalElevation = 8.dp
        ) {
            screens.forEach { screen ->
                val selected = currentRoute == screen.route
                val screenTitle = getScreenTitle(context, screen)
                val animatedWeight by animateFloatAsState(
                    targetValue = if (selected) 1.5f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "weight"
                )

                NavigationBarItem(
                    selected = selected,
                    onClick = { onNavigate(screen.route) },
                    icon = {
                        AnimatedContent(
                            targetState = selected,
                            transitionSpec = {
                                scaleIn(animationSpec = tween(200)) with scaleOut(
                                    animationSpec = tween(
                                        200
                                    )
                                )
                            },
                            label = "icon"
                        ) { isSelected ->
                            Icon(
                                imageVector = if (isSelected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screenTitle,
                                modifier = Modifier.size(if (isSelected) 28.dp else 24.dp)
                            )
                        }
                    },
                    label = {
                        AnimatedVisibility(
                            visible = selected,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Text(
                                text = screenTitle,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.weight(animatedWeight)
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
            Screen.Sensors -> context.getString(R.string.nav_sensor_data)
            Screen.Server -> context.getString(R.string.nav_server_config)
            Screen.Details -> context.getString(R.string.nav_detailed_info)
            Screen.Privacy -> "持续认证"
        }
    }
//    private fun checkPermissionsAndGuidance() {
//        lifecycleScope.launch {
//            if (NotificationPermissionHelper.shouldRequestNotificationPermission(this@MainComposeActivity)) {
//                showNotificationPermissionDialog()
//            }else if (UsageStatsHelper.hasUsageStatsPermission(this@MainComposeActivity)) {
//                onUsageStatsPermissionGranted()
//            }else{
//                // 使用现代化的启动器请求权限
//                usageStatsLauncher.requestUsageStatsPermission(
//                    context = this@MainComposeActivity,
//                    onGranted = {
//                        runOnUiThread {
//                            onUsageStatsPermissionGranted()
//                        }
//                    },
//                    onDenied = {
//                        runOnUiThread {
//                            Toast.makeText(
//                                this@MainComposeActivity,
//                                "您拒绝了使用情况访问权限",
//                                Toast.LENGTH_SHORT
//                            ).show()
//                        }
//                    }
//                )
//            }
//        }
//    }

    // 添加显示通知权限对话框的方法
    private fun showNotificationPermissionDialog() {
        val dialog = NotificationPermissionDialog { grantPermission ->
            if (grantPermission) {
                notificationPermissionLauncher.requestNotificationPermission(
                    this,
                    onGranted = {
                        val mIntent= Intent()
                        mIntent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                        mIntent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        notificationLauncher.launch(mIntent)
                    },
                    onDenied = {
                        isSkipPermissionCheck=true
                        showUsageStatsPermissionDialog()
                    }
                )
            } else {
                // 继续检查其他权限
                checkPermissionsAndGuidance()
            }
        }
        dialog.show(supportFragmentManager, "notification_permission")
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
            selectedIcon = Icons.Filled.CloudQueue,
            unselectedIcon = Icons.Outlined.CloudQueue
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
        lifecycleScope.launch {
//            showNotificationPermissionDialog()
            if (NotificationPermissionHelper.shouldRequestNotificationPermission(this@MainComposeActivity)){
                if (!viewModel.hasPostNotificationsPermission()) {
                    showNotificationPermissionDialog()
                }
            }
            // 检查Usage Stats权限
            else if (!viewModel.hasUsageStatsPermission()) {
                showUsageStatsPermissionDialog()
            } else {
                checkBatteryOptimization()
            }
        }
    }

    /**
     * 显示Usage Stats权限对话框
     */
    private fun showUsageStatsPermissionDialog() {
        val dialog = UsageStatsPermissionDialog { granted ->
            if (granted) {
                // 跳转到设置页面
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                usageStatsPermissionLauncher.launch(intent)
            } else {
                // 继续检查电池优化
                isSkipPermissionCheck=true
                checkBatteryOptimization()
            }
        }
        dialog.show(supportFragmentManager, "usage_stats_permission")
    }

    /**
     * 检查电池优化设置
     */
    private fun checkBatteryOptimization() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

        // 检查是否已请求过电池优化
        if (!prefs.getBoolean(BATTERY_OPTIMIZATION_REQUESTED, false)) {
            showBatteryOptimizationDialog()
        }
    }

    /**
     * 显示电池优化对话框
     */
    private fun showBatteryOptimizationDialog() {
        val dialog = BatteryOptimizationDialog { disableOptimization ->
            if (disableOptimization && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 跳转到电池优化设置
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                batteryOptimizationLauncher.launch(intent)
            } else {
                // 用户跳过或系统不支持
                getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean(BATTERY_OPTIMIZATION_REQUESTED, true)
                    .apply()
                isSkipPermissionCheck=true
            }
        }
        dialog.show(supportFragmentManager, "battery_optimization")
    }
}