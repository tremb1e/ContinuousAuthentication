package com.continuousauth.utils
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

object UsageStatsHelper {

    /**
     * 检查是否已授予使用情况访问权限
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val packageName = context.packageName
        val uid = Process.myUid()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                uid,
                packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } else {
            @Suppress("DEPRECATION")
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                uid,
                packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /**
     * 引导用户前往设置页面开启权限（通用版本）
     */
    fun requestUsageStatsPermission(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            showPermissionGuideToast(context)
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorToast(context)
        }
    }

    fun showPermissionGuideToast(context: Context) {
        Toast.makeText(
            context,
            "🔍 请在设置页面中找到「${getAppName(context)}」\n然后打开右侧的开关",
            Toast.LENGTH_LONG
        ).show()
    }

    fun showErrorToast(context: Context) {
        Toast.makeText(
            context,
            "无法自动打开设置，请手动前往：设置 → 应用 → 特殊应用权限 → 使用情况访问",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun getAppName(context: Context): String {
        return try {
            val packageManager = context.packageManager
            val applicationInfo = packageManager.getApplicationInfo(context.packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            "本应用"
        }
    }
}

/**
 * 使用 registerForActivityResult 的扩展函数
 */
class UsageStatsPermissionLauncher private constructor() {

    // Activity Result Launcher
    private lateinit var usageStatsLauncher: androidx.activity.result.ActivityResultLauncher<Intent>

    // 回调函数
    private var onPermissionGranted: (() -> Unit)? = null
    private var onPermissionDenied: (() -> Unit)? = null

    companion object {
        /**
         * 在Activity中创建启动器
         */
        fun create(activity: FragmentActivity): UsageStatsPermissionLauncher {
            return UsageStatsPermissionLauncher().apply {
                initLauncher(activity)
            }
        }

        /**
         * 在Fragment中创建启动器
         */
        fun create(fragment: Fragment): UsageStatsPermissionLauncher {
            return UsageStatsPermissionLauncher().apply {
                initLauncher(fragment)
            }
        }
    }

    private fun initLauncher(owner: Any) {
        val contract = ActivityResultContracts.StartActivityForResult()

        usageStatsLauncher = when (owner) {
            is FragmentActivity -> owner.registerForActivityResult(contract) { result ->
                handleActivityResult(owner, result.resultCode)
            }
            is Fragment -> owner.registerForActivityResult(contract) { result ->
                handleActivityResult(owner.requireContext(), result.resultCode)
            }
            else -> throw IllegalArgumentException("Unsupported owner type")
        }
    }

    private fun handleActivityResult(context: Context, resultCode: Int) {
        if (UsageStatsHelper.hasUsageStatsPermission(context)) {
            onPermissionGranted?.invoke()
        } else {
            onPermissionDenied?.invoke()
        }
    }

    /**
     * 请求权限
     */
    fun requestUsageStatsPermission(
        context: Context,
        onGranted: (() -> Unit)? = null,
        onDenied: (() -> Unit)? = null
    ) {
        this.onPermissionGranted = onGranted
        this.onPermissionDenied = onDenied

        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            usageStatsLauncher.launch(intent)
            UsageStatsHelper.showPermissionGuideToast(context)
        } catch (e: Exception) {
            e.printStackTrace()
            UsageStatsHelper.showErrorToast(context)
            onDenied?.invoke()
        }
    }

    /**
     * 清理资源
     */
    fun dispose() {
        onPermissionGranted = null
        onPermissionDenied = null
    }
}