package com.continuousauth.utils

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 前台应用检测器
 * 使用UsageStatsManager监控前台应用变化
 */
@Singleton
class ForegroundAppDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val packageManager = context.packageManager
    
    private var currentForegroundApp = ""
    private var lastForegroundTimestamp = 0L
    private val mutex = Mutex()
    private var hasLoggedPermissionWarning = false
    
    /**
     * 获取当前前台应用包名
     * 如果没有权限或获取失败，返回空字符串
     */
    suspend fun getCurrentForegroundApp(): String = mutex.withLock {
        try {
            usageStatsManager?.let { statsManager ->
                val currentTime = System.currentTimeMillis()
                // 扩大查询时间窗口到60秒，确保能捕获到前台应用事件
                val windowStart = currentTime - 60000
                val events = statsManager.queryEvents(windowStart, currentTime)
                
                var lastForegroundPackage: String? = null
                var lastForegroundTime = 0L
                val event = UsageEvents.Event()
                
                // 遍历所有事件
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    
                    // 检查多种前台事件类型以提高检测准确性
                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED,
                        UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                            // 记录最新的前台应用
                            if (event.timeStamp > lastForegroundTime) {
                                lastForegroundTime = event.timeStamp
                                lastForegroundPackage = event.packageName
                            }
                        }
                        UsageEvents.Event.ACTIVITY_PAUSED,
                        UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                            // 如果是当前缓存的应用移到后台，清除缓存
                            if (event.packageName == currentForegroundApp && 
                                event.timeStamp > lastForegroundTime) {
                                // 不立即清除，等待新的前台应用
                                lastForegroundTime = event.timeStamp
                            }
                        }
                    }
                }
                
                // 如果找到前台应用，更新缓存并返回
                lastForegroundPackage?.let { packageName ->
                    currentForegroundApp = packageName
                    lastForegroundTimestamp = lastForegroundTime
                    android.util.Log.d("ForegroundAppDetector", "检测到前台应用: $packageName")
                    return currentForegroundApp
                }

                // 若事件流中未命中，退回到 UsageStats 取最近一次使用的应用
                val usageStats = statsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    windowStart,
                    currentTime
                )
                usageStats?.maxByOrNull { it.lastTimeUsed }?.let { stat ->
                    if (stat.lastTimeUsed > lastForegroundTimestamp) {
                        currentForegroundApp = stat.packageName
                        lastForegroundTimestamp = stat.lastTimeUsed
                        android.util.Log.d("ForegroundAppDetector", "使用Stats回退检测前台应用: ${stat.packageName}")
                        return currentForegroundApp
                    }
                }
                
                // 如果没有找到新的前台应用，返回缓存的值
                if (currentForegroundApp.isNotEmpty()) {
                    return currentForegroundApp
                }
            }
        } catch (e: SecurityException) {
            // 没有PACKAGE_USAGE_STATS权限
            if (!hasLoggedPermissionWarning) {
                android.util.Log.w("ForegroundAppDetector", "缺少PACKAGE_USAGE_STATS权限，前台应用信息将为空")
                hasLoggedPermissionWarning = true
            }
            currentForegroundApp = "permission_missing"
        } catch (e: Exception) {
            android.util.Log.e("ForegroundAppDetector", "获取前台应用失败", e)
        }
        
        return currentForegroundApp // 返回缓存的值而不是空字符串
    }
    
    /**
     * 检查是否有使用统计权限
     */
    fun hasUsageStatsPermission(): Boolean {
        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val currentTime = System.currentTimeMillis()
            val queryUsageStats = usageStatsManager?.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                currentTime - 1000 * 60 * 60,
                currentTime
            )
            
            queryUsageStats?.isNotEmpty() ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取应用名称（用于调试显示）
     */
    fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
