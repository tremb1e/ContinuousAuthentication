package com.continuousauth.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.continuousauth.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 通知权限对话框
 * 引导用户授予通知权限，用于显示前台服务通知
 */
class NotificationPermissionDialog(
    private val onResult: (grantPermission: Boolean) -> Unit
) : DialogFragment() {

    companion object {
        private const val TAG = "NotificationPermissionDialog"
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.notification_permission_title))
            .setMessage(getString(R.string.notification_permission_message))
            .setIcon(R.drawable.ic_notification)
            .setPositiveButton(getString(R.string.grant_permission)) { dialog, _ ->
                dialog.dismiss()
                onResult(true)
            }
            .setNegativeButton(getString(R.string.skip)) { dialog, _ ->
                dialog.dismiss()
                onResult(false)
            }
            .setCancelable(true)
            .create()
    }

    override fun onStart() {
        super.onStart()

        // 设置对话框样式
        dialog?.window?.let {
            val attributes = it.attributes
            attributes.width = (resources.displayMetrics.widthPixels * 0.9).toInt()
            it.attributes = attributes
        }
    }
}