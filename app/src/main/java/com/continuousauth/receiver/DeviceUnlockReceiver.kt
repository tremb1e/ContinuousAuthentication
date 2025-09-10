package com.continuousauth.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 设备解锁广播接收器
 * 监听设备解锁和屏幕打开事件
 * 
 * 重要：任何数据采集操作都必须先检查用户是否已同意隐私协议
 */
class DeviceUnlockReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "DeviceUnlockReceiver"
        private const val PREFS_NAME = "app_prefs"
        private const val PRIVACY_AGREEMENT_KEY = "privacy_agreement_shown"
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        
        // 首先检查隐私协议是否已同意
        if (!checkPrivacyConsent(context)) {
            Log.w(TAG, "用户未同意隐私协议，忽略解锁事件")
            return
        }
        
        when (intent.action) {
            Intent.ACTION_USER_PRESENT -> {
                Log.d(TAG, "设备解锁事件")
                handleDeviceUnlock(context)
            }
            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "屏幕打开事件")
                handleScreenOn(context)
            }
        }
    }
    
    /**
     * 检查用户是否已同意隐私协议
     */
    private fun checkPrivacyConsent(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasAgreed = prefs.getBoolean(PRIVACY_AGREEMENT_KEY, false)
        
        if (!hasAgreed) {
            Log.w(TAG, "隐私协议未同意，阻止任何数据采集相关操作")
        }
        
        return hasAgreed
    }
    
    /**
     * 处理设备解锁事件
     */
    private fun handleDeviceUnlock(context: Context) {
        // TODO: 实现设备解锁后的逻辑
        // 例如：触发异常检测、切换到快速模式等
        // 当前为空实现，避免编译错误
        
        Log.i(TAG, "设备解锁，可以触发相关操作")
    }
    
    /**
     * 处理屏幕打开事件
     */
    private fun handleScreenOn(context: Context) {
        // TODO: 实现屏幕打开后的逻辑
        // 当前为空实现，避免编译错误
        
        Log.i(TAG, "屏幕打开，准备监听解锁事件")
    }
}