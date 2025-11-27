package com.continuousauth

import android.app.Application
import android.util.Log
import com.continuousauth.startup.MMKVStartUp
import com.continuousauth.time.EnhancedTimeSync
import com.rousetime.android_startup.StartupManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 持续认证应用主类
 * 使用Hilt进行依赖注入，初始化NTP时间同步等核心服务
 */
@HiltAndroidApp
class ContinuousAuthApplication : Application() {
    
    @Inject
    lateinit var timeSync: EnhancedTimeSync
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        
        Log.d("ContinuousAuth", "应用初始化开始")
        
        // 初始化时间同步服务
        initializeTimeSync()
        val builder = StartupManager.Builder()
        builder.addStartup(MMKVStartUp())
            .build(this)
            .start()
        Log.d("ContinuousAuth", "应用初始化完成")
    }
    
    /**
     * 初始化NTP时间同步
     * 根据claude.md规范：程序启动时，自动同步NTP一次，并每隔1h同步一次
     */
    private fun initializeTimeSync() {
        applicationScope.launch {
            try {
                // 启动时自动同步NTP一次
                val syncSuccess = timeSync.syncTime()
                if (syncSuccess) {
                    Log.i("ContinuousAuth", "启动时NTP同步成功")
                } else {
                    Log.w("ContinuousAuth", "启动时NTP同步失败")
                }
                
                // 开始周期性同步（每小时一次）
                timeSync.startPeriodicSync()
                Log.i("ContinuousAuth", "NTP周期性同步已启动")
                
            } catch (e: Exception) {
                Log.e("ContinuousAuth", "NTP时间同步初始化失败", e)
            }
        }
    }
}