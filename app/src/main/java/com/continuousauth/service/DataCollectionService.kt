package com.continuousauth.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.continuousauth.R
import com.continuousauth.core.SmartTransmissionManager
import com.continuousauth.privacy.PrivacyManager
import com.continuousauth.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import android.util.Log
import javax.inject.Inject

/**
 * 数据采集前台服务
 * 负责在后台持续采集传感器数据
 * 
 * 重要：此服务仅在用户明确同意隐私协议后才能启动
 */
@AndroidEntryPoint
class DataCollectionService : Service() {
    
    companion object {
        private const val TAG = "DataCollectionService"
        private const val NOTIFICATION_CHANNEL_ID = "continuous_auth_service"
        private const val NOTIFICATION_ID = 1001
        
        // Intent actions
        const val ACTION_START_COLLECTION = "com.continuousauth.START_COLLECTION"
        const val ACTION_STOP_COLLECTION = "com.continuousauth.STOP_COLLECTION"
        const val ACTION_PAUSE_COLLECTION = "com.continuousauth.PAUSE_COLLECTION"
        const val ACTION_RESUME_COLLECTION = "com.continuousauth.RESUME_COLLECTION"
        const val PREFS_NAME = "app_prefs"
        const val COLLECTION_REQUESTED_KEY = "collection_requested"
    }
    
    @Inject
    lateinit var privacyManager: PrivacyManager
    
    @Inject
    lateinit var smartTransmissionManager: SmartTransmissionManager
    
    // 协程作用域
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 电源锁，保持设备部分唤醒
    private var wakeLock: PowerManager.WakeLock? = null
    
    // 服务状态
    private var isCollecting = false
    private var isPaused = false
    
    // 广播接收器，监听系统事件
    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            when (intent?.action) {
                Intent.ACTION_BATTERY_LOW -> {
                    Log.w(TAG, "电池电量低，暂停数据采集")
                    pauseCollection()
                }
                Intent.ACTION_BATTERY_OKAY -> {
                    Log.i(TAG, "电池电量恢复，恢复数据采集")
                    resumeCollection()
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    Log.i(TAG, "充电器已连接")
                    updateCollectionStrategy(isCharging = true)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    Log.i(TAG, "充电器已断开")
                    updateCollectionStrategy(isCharging = false)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "屏幕关闭，暂停数据采集和上传")
                    pauseCollection()
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (isDeviceUnlocked()) {
                        Log.i(TAG, "屏幕打开且设备已解锁，恢复数据采集和上传")
                        resumeCollection()
                    } else {
                        Log.i(TAG, "屏幕打开但设备仍锁定，等待解锁后恢复")
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.i(TAG, "设备已解锁，恢复数据采集和上传")
                    resumeCollection()
                }
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务创建")
        
        // 检查隐私同意状态
        if (!checkPrivacyConsent()) {
            Log.w(TAG, "用户未同意隐私协议，服务将不启动")
            stopSelf()
            return
        }
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 注册系统事件监听
        registerSystemEventReceivers()
        
        // 获取电源锁
        acquireWakeLock()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务启动命令: ${intent?.action}")
        
        // 再次检查隐私同意状态
        if (!checkPrivacyConsent()) {
            Log.w(TAG, "用户未同意隐私协议，服务停止")
            stopSelf()
            return START_NOT_STICKY
        }
        
        try {
            // 创建前台通知
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败", e)
            stopSelf()
            return START_NOT_STICKY
        }
        
        // 处理不同的命令
        when (intent?.action) {
            ACTION_START_COLLECTION -> startCollection()
            ACTION_STOP_COLLECTION -> stopCollection()
            ACTION_PAUSE_COLLECTION -> pauseCollection()
            ACTION_RESUME_COLLECTION -> resumeCollection()
            else -> {
                // 默认启动采集
                if (!isCollecting) {
                    startCollection()
                }
            }
        }
        
        // 避免系统在后台强行重启导致前台服务启动被拦截，使用 NOT_STICKY。
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务销毁")
        
        // 停止数据采集
        stopCollection()
        
        // 取消协程作用域
        serviceScope.cancel()
        
        // 注销广播接收器
        unregisterSystemEventReceivers()
        
        // 释放电源锁
        releaseWakeLock()
    }
    
    /**
     * 检查用户是否已同意隐私协议
     */
    private fun checkPrivacyConsent(): Boolean {
        // 从SharedPreferences检查隐私协议状态
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val hasAgreed = prefs.getBoolean("privacy_agreement_shown", false)
        
        if (!hasAgreed) {
            Log.w(TAG, "隐私协议未同意，阻止服务启动")
        }
        
        return hasAgreed
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "持续认证数据采集服务"
                setShowBadge(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
    
    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        // 创建点击通知跳转到主界面的Intent
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 创建停止服务的Action
        val stopIntent = Intent(this, DataCollectionService::class.java).apply {
            action = ACTION_STOP_COLLECTION
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 创建暂停/恢复的Action
        val pauseResumeIntent = Intent(this, DataCollectionService::class.java).apply {
            action = if (isPaused) ACTION_RESUME_COLLECTION else ACTION_PAUSE_COLLECTION
        }
        val pauseResumePendingIntent = PendingIntent.getService(
            this, 2, pauseResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = when {
            isPaused -> "数据采集已暂停"
            isCollecting -> "数据采集运行中"
            else -> "数据采集服务就绪"
        }
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,  // Use system drawable
                if (isPaused) "恢复" else "暂停",
                pauseResumePendingIntent
            )
            .addAction(
                android.R.drawable.ic_delete,  // Use system drawable
                "停止",
                stopPendingIntent
            )
            .build()
    }
    
    /**
     * 启动数据采集
     */
    private fun startCollection() {
        if (isCollecting) {
            Log.w(TAG, "数据采集已在运行中")
            return
        }
        
        Log.i(TAG, "启动数据采集")
        setCollectionRequested(true)
        isCollecting = true
        isPaused = false
        
        // 在协程中启动智能传输管理器
        serviceScope.launch {
            try {
                smartTransmissionManager.start()
                updateNotification()
                Log.i(TAG, "数据采集启动成功")
            } catch (e: Exception) {
                Log.e(TAG, "启动数据采集失败", e)
                setCollectionRequested(false)
                isCollecting = false
                updateNotification()
            }
        }
    }
    
    /**
     * 停止数据采集
     */
    private fun stopCollection() {
        setCollectionRequested(false)
        if (!isCollecting) {
            Log.w(TAG, "数据采集未运行")
            return
        }
        
        Log.i(TAG, "停止数据采集")
        isCollecting = false
        isPaused = false
        
        serviceScope.launch {
            try {
                smartTransmissionManager.stop()
                updateNotification()
                Log.i(TAG, "数据采集停止成功")
                
                // 停止服务
                stopForeground(true)
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "停止数据采集失败", e)
            }
        }
    }
    
    /**
     * 暂停数据采集
     */
    private fun pauseCollection() {
        if (!isCollecting || isPaused) {
            return
        }
        
        Log.i(TAG, "暂停数据采集")
        isPaused = true
        
        serviceScope.launch {
            try {
                smartTransmissionManager.pause()
                updateNotification()
            } catch (e: Exception) {
                Log.e(TAG, "暂停数据采集失败", e)
            }
        }
    }
    
    /**
     * 恢复数据采集
     */
    private fun resumeCollection() {
        if (!isCollecting) {
            if (isCollectionRequested()) {
                Log.i(TAG, "收到恢复命令但服务未采集，按用户已启动状态重新开始采集")
                startCollection()
            }
            return
        }
        if (!isPaused) {
            return
        }
        
        Log.i(TAG, "恢复数据采集")
        isPaused = false
        
        serviceScope.launch {
            try {
                smartTransmissionManager.resume()
                updateNotification()
            } catch (e: Exception) {
                Log.e(TAG, "恢复数据采集失败", e)
            }
        }
    }
    
    /**
     * 更新通知
     */
    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * 注册系统事件接收器
     */
    private fun registerSystemEventReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(systemEventReceiver, filter)
    }
    
    /**
     * 注销系统事件接收器
     */
    private fun unregisterSystemEventReceivers() {
        try {
            unregisterReceiver(systemEventReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "注销广播接收器失败", e)
        }
    }
    
    /**
     * 获取电源锁
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:DataCollectionWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 最多保持10分钟
        }
        Log.d(TAG, "电源锁已获取")
    }
    
    /**
     * 释放电源锁
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "电源锁已释放")
            }
        }
        wakeLock = null
    }
    
    /**
     * 更新采集策略
     */
    private fun updateCollectionStrategy(isCharging: Boolean) {
        serviceScope.launch {
            // 按规格保持1秒批次发送以节能，充电状态仅用于日志与后续扩展
            smartTransmissionManager.updatePolicy(
                batchInterval = 1000L,
                compressionEnabled = true
            )
            Log.i(TAG, "保持1秒批次发送策略，isCharging=$isCharging")
        }
    }

    private fun isDeviceUnlocked(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            !keyguardManager.isDeviceLocked
        } else {
            @Suppress("DEPRECATION")
            !keyguardManager.isKeyguardLocked
        }
    }

    private fun setCollectionRequested(requested: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(COLLECTION_REQUESTED_KEY, requested)
            .apply()
    }

    private fun isCollectionRequested(): Boolean {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(COLLECTION_REQUESTED_KEY, false)
    }
}
