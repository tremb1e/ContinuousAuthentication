package com.continuousauth.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.continuousauth.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 隐私协议对话框
 * 首次启动时显示，用户必须同意才能继续使用应用
 */
class PrivacyAgreementDialog(
    private val onResult: (accepted: Boolean) -> Unit
) : DialogFragment() {
    
    companion object {
        private const val TAG = "PrivacyAgreementDialog"
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        
        return MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.privacy_agreement_title))
            .setMessage(getPrivacyAgreementText())
            .setPositiveButton(getString(R.string.agree)) { dialog, _ ->
                dialog.dismiss()
                onResult(true)
            }
            .setNegativeButton(getString(R.string.disagree)) { dialog, _ ->
                dialog.dismiss()
                onResult(false)
            }
            .setCancelable(false) // 用户必须明确选择
            .create()
    }
    
    /**
     * 获取隐私协议文本
     * 根据Task 3.2.2的要求，必须明确说明数据采集、用途、存储、共享策略
     */
    private fun getPrivacyAgreementText(): String {
        return buildString {
            appendLine(getString(R.string.privacy_welcome))
            appendLine()
            
            appendLine(getString(R.string.privacy_data_collection_title))
            appendLine(getString(R.string.privacy_data_collection_content))
            appendLine()
            
            appendLine(getString(R.string.privacy_data_usage_title))
            appendLine(getString(R.string.privacy_data_usage_content))
            appendLine()
            
            appendLine(getString(R.string.privacy_data_storage_title))
            appendLine(getString(R.string.privacy_data_storage_content))
            appendLine()
            
            appendLine(getString(R.string.privacy_data_sharing_title))
            appendLine(getString(R.string.privacy_data_sharing_content))
            appendLine()
            
            appendLine(getString(R.string.privacy_user_rights_title))
            appendLine(getString(R.string.privacy_user_rights_content))
            appendLine()
            
            appendLine(getString(R.string.privacy_contact_title))
            appendLine(getString(R.string.privacy_contact_content))
        }
    }
    
    override fun onStart() {
        super.onStart()
        
        // 设置对话框消息文本支持滚动
        dialog?.findViewById<android.widget.TextView>(android.R.id.message)?.apply {
            movementMethod = ScrollingMovementMethod()
            maxHeight = resources.displayMetrics.heightPixels / 2 // 最大高度为屏幕高度的一半
        }
    }
}