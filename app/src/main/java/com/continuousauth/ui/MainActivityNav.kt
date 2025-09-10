package com.continuousauth.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.continuousauth.R
import com.continuousauth.databinding.ActivityMainNavBinding
import com.continuousauth.ui.dialogs.PrivacyAgreementDialog
import dagger.hilt.android.AndroidEntryPoint

/**
 * 主活动 - 使用底部导航架构
 * 管理三个主要页面的切换和导航
 */
@AndroidEntryPoint
class MainActivityNav : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainNavBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化ViewBinding
        binding = ActivityMainNavBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 设置导航
        setupNavigation()
        
        // 检查首次启动
        checkFirstLaunch()
    }
    
    /**
     * 设置导航组件
     */
    private fun setupNavigation() {
        // 获取NavController
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        
        // 设置底部导航栏
        binding.bottomNavigation.setupWithNavController(navController)
        
        // 配置ActionBar
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_sensor_data,
                R.id.navigation_server_config,
                R.id.navigation_detailed_info
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
    }
    
    /**
     * 检查首次启动
     */
    private fun checkFirstLaunch() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("is_first_launch", true)
        
        if (isFirstLaunch) {
            showPrivacyAgreement()
        }
    }
    
    /**
     * 显示隐私协议
     */
    private fun showPrivacyAgreement() {
        val dialog = PrivacyAgreementDialog { accepted ->
            if (accepted) {
                // 用户接受隐私协议
                getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("is_first_launch", false)
                    .apply()
            } else {
                // 用户拒绝隐私协议，关闭应用
                finish()
            }
        }
        dialog.show(supportFragmentManager, "privacy_agreement")
    }
}