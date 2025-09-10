package com.continuousauth.versioning

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "version_settings")

/**
 * 版本管理器
 * 负责处理应用版本升级和数据模型版本迁移
 */
@Singleton
class VersionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // 当前版本常量
        const val CURRENT_APP_VERSION_CODE = 1
        const val CURRENT_APP_VERSION_NAME = "1.0.0"
        const val CURRENT_SCHEMA_VERSION = 1
        
        // DataStore keys
        private val STORED_APP_VERSION_CODE = intPreferencesKey("stored_app_version_code")
        private val STORED_APP_VERSION_NAME = stringPreferencesKey("stored_app_version_name")
        private val STORED_SCHEMA_VERSION = intPreferencesKey("stored_schema_version")
        private val LAST_UPGRADE_TIME = stringPreferencesKey("last_upgrade_time")
    }
    
    /**
     * 检查并执行必要的版本升级
     * @return 升级结果信息
     */
    suspend fun checkAndPerformUpgrade(): UpgradeResult {
        val preferences = context.dataStore.data.first()
        
        val storedAppVersionCode = preferences[STORED_APP_VERSION_CODE] ?: 0
        val storedAppVersionName = preferences[STORED_APP_VERSION_NAME] ?: "0.0.0"
        val storedSchemaVersion = preferences[STORED_SCHEMA_VERSION] ?: 0
        
        val upgrades = mutableListOf<String>()
        
        // 检查应用版本升级
        if (storedAppVersionCode < CURRENT_APP_VERSION_CODE) {
            upgrades.add("应用版本从 $storedAppVersionName (code: $storedAppVersionCode) 升级到 $CURRENT_APP_VERSION_NAME (code: $CURRENT_APP_VERSION_CODE)")
            performAppVersionUpgrade(storedAppVersionCode, CURRENT_APP_VERSION_CODE)
        }
        
        // 检查数据模型版本升级
        if (storedSchemaVersion < CURRENT_SCHEMA_VERSION) {
            upgrades.add("数据模型版本从 $storedSchemaVersion 升级到 $CURRENT_SCHEMA_VERSION")
            performSchemaVersionUpgrade(storedSchemaVersion, CURRENT_SCHEMA_VERSION)
        }
        
        // 更新存储的版本信息
        context.dataStore.edit { preferences ->
            preferences[STORED_APP_VERSION_CODE] = CURRENT_APP_VERSION_CODE
            preferences[STORED_APP_VERSION_NAME] = CURRENT_APP_VERSION_NAME
            preferences[STORED_SCHEMA_VERSION] = CURRENT_SCHEMA_VERSION
            preferences[LAST_UPGRADE_TIME] = System.currentTimeMillis().toString()
        }
        
        return UpgradeResult(
            upgrades = upgrades,
            wasFirstRun = storedAppVersionCode == 0,
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * 执行应用版本升级逻辑
     */
    private suspend fun performAppVersionUpgrade(fromVersion: Int, toVersion: Int) {
        when {
            fromVersion == 0 && toVersion == 1 -> {
                // 首次安装，无需特殊处理
            }
            fromVersion < toVersion -> {
                // 未来版本升级时的处理逻辑
                // 例如：清理过时的缓存文件、更新配置等
                performIncrementalAppUpgrade(fromVersion, toVersion)
            }
        }
    }
    
    /**
     * 执行数据模型版本升级逻辑
     */
    private suspend fun performSchemaVersionUpgrade(fromVersion: Int, toVersion: Int) {
        when {
            fromVersion == 0 && toVersion == 1 -> {
                // 首次安装，初始化数据库模式
            }
            fromVersion < toVersion -> {
                // 逐步升级数据模型
                for (version in (fromVersion + 1)..toVersion) {
                    upgradeSchemaToVersion(version)
                }
            }
        }
    }
    
    /**
     * 逐步执行应用升级
     */
    private suspend fun performIncrementalAppUpgrade(fromVersion: Int, toVersion: Int) {
        for (version in (fromVersion + 1)..toVersion) {
            when (version) {
                1 -> {
                    // v1.0.0 升级逻辑
                    // 可能包括：迁移旧的设置、清理无用文件等
                }
                // 未来版本的升级逻辑...
            }
        }
    }
    
    /**
     * 升级数据模式到指定版本
     */
    private suspend fun upgradeSchemaToVersion(version: Int) {
        when (version) {
            1 -> {
                // Schema v1: 初始版本
                // - DataPacket 结构
                // - 加密数据格式
                // - 传感器数据结构
            }
            // 未来的数据模型版本...
            // 2 -> {
            //     // Schema v2: 可能添加新字段或修改数据结构
            // }
        }
    }
    
    /**
     * 获取当前版本信息
     */
    suspend fun getCurrentVersionInfo(): VersionInfo {
        val preferences = context.dataStore.data.first()
        
        return VersionInfo(
            appVersionCode = CURRENT_APP_VERSION_CODE,
            appVersionName = CURRENT_APP_VERSION_NAME,
            schemaVersion = CURRENT_SCHEMA_VERSION,
            lastUpgradeTime = preferences[LAST_UPGRADE_TIME]?.toLongOrNull() ?: 0L
        )
    }
    
    /**
     * 检查是否需要升级
     */
    suspend fun needsUpgrade(): Boolean {
        val preferences = context.dataStore.data.first()
        
        val storedAppVersionCode = preferences[STORED_APP_VERSION_CODE] ?: 0
        val storedSchemaVersion = preferences[STORED_SCHEMA_VERSION] ?: 0
        
        return storedAppVersionCode < CURRENT_APP_VERSION_CODE || 
               storedSchemaVersion < CURRENT_SCHEMA_VERSION
    }
    
    /**
     * 获取数据包的schema版本信息
     * 用于在DataPacket中包含当前的schema版本
     */
    fun getDataPacketSchemaVersion(): Int = CURRENT_SCHEMA_VERSION
    
    /**
     * 验证数据包的schema版本兼容性
     */
    fun isDataPacketSchemaCompatible(packetSchemaVersion: Int): Boolean {
        // 目前只支持当前版本，未来可以支持向后兼容
        return packetSchemaVersion <= CURRENT_SCHEMA_VERSION
    }
}

/**
 * 升级结果数据类
 */
data class UpgradeResult(
    val upgrades: List<String>,
    val wasFirstRun: Boolean,
    val timestamp: Long
)

/**
 * 版本信息数据类
 */
data class VersionInfo(
    val appVersionCode: Int,
    val appVersionName: String,
    val schemaVersion: Int,
    val lastUpgradeTime: Long
)