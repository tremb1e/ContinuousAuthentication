package com.continuousauth.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.continuousauth.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 电池优化对话框
 * 引导用户关闭应用的电池优化，确保后台数据采集稳定运行
 */
class BatteryOptimizationDialog(
    private val onResult: (disableOptimization: Boolean) -> Unit
) : DialogFragment() {
    
    companion object {
        private const val TAG = "BatteryOptimizationDialog"
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        
        return MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.battery_optimization_title))
            .setMessage(getBatteryOptimizationText())
            .setIcon(R.drawable.ic_battery_optimization)
            .setPositiveButton(getString(R.string.disable_optimization)) { dialog, _ ->
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
    
    /**
     * 获取电池优化说明文本
     */
    private fun getBatteryOptimizationText(): String {
        return buildString {
            appendLine(getString(R.string.battery_optimization_explanation))
            appendLine()
            appendLine(getString(R.string.battery_optimization_benefits))
            appendLine()
            appendLine(getString(R.string.battery_optimization_how_to))
        }
    }
    
    override fun onStart() {
        super.onStart()
        
        // 设置对话框样式
        dialog?.window?.let { window ->
            val attributes = window.attributes
            attributes.width = (resources.displayMetrics.widthPixels * 0.9).toInt()
            window.attributes = attributes
        }
    }
}