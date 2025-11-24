package com.continuousauth.utils
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

object NotificationPermissionHelper {

    /**
     * 检查是否已授予通知权限
     * Android 13 (API 33)及以上版本需要POST_NOTIFICATIONS权限
     */
    fun hasNotificationPermission(context: Context): Boolean {
        // Android 13以下版本不需要显式请求通知权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否需要请求通知权限
     * - Android 13及以上版本且未授予权限时需要请求
     */
    fun shouldRequestNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasNotificationPermission(context)
    }
}
/**
 * 通知权限启动器
 * 用于处理通知权限的请求和结果回调
 */
class NotificationPermissionLauncher private constructor() {

    // Activity Result Launcher
    private lateinit var notificationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>

    // 回调函数
    private var onPermissionGranted: (() -> Unit)? = null
    private var onPermissionDenied: (() -> Unit)? = null

    companion object {
        /**
         * 在Activity中创建启动器
         */
        fun create(activity: FragmentActivity): NotificationPermissionLauncher {
            return NotificationPermissionLauncher().apply {
                initLauncher(activity)
            }
        }

        /**
         * 在Fragment中创建启动器
         */
        fun create(fragment: Fragment): NotificationPermissionLauncher {
            return NotificationPermissionLauncher().apply {
                initLauncher(fragment)
            }
        }
    }

    private fun initLauncher(owner: Any) {
        val contract = ActivityResultContracts.RequestPermission()

        notificationPermissionLauncher = when (owner) {
            is FragmentActivity -> owner.registerForActivityResult(contract) {
                handlePermissionResult(it)
            }
            is Fragment -> owner.registerForActivityResult(contract) {
                handlePermissionResult(it)
            }
            else -> throw IllegalArgumentException("Unsupported owner type")
        }
    }

    private fun handlePermissionResult(isGranted: Boolean) {
        if (isGranted) {
            onPermissionGranted?.invoke()
        } else {
            onPermissionDenied?.invoke()
        }
    }

    /**
     * 请求通知权限
     */
    fun requestNotificationPermission(
        context: Context,
        onGranted: (() -> Unit)? = null,
        onDenied: (() -> Unit)? = null
    ) {
        // 只有在Android 13及以上版本且需要权限时才请求
        if (!NotificationPermissionHelper.shouldRequestNotificationPermission(context)) {
            onGranted?.invoke()
            return
        }

        this.onPermissionGranted = onGranted
        this.onPermissionDenied = onDenied

        try {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            e.printStackTrace()
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