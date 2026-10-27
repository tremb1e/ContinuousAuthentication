package com.continuousauth.versioning

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * 版本管理器测试
 * 测试应用版本升级和数据模型迁移功能
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class VersionManagerTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var versionManager: VersionManager

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun init() {
        hiltRule.inject()
    }

    /**
     * 测试首次启动升级
     */
    @Test
    fun testFirstRunUpgrade() = runTest {
        // 清理之前的数据
        clearVersionPreferences()
        
        // 执行升级
        val result = versionManager.checkAndPerformUpgrade()
        
        // 验证首次运行
        assertTrue("应该识别为首次运行", result.wasFirstRun)
        assertTrue("应该有升级信息", result.upgrades.isNotEmpty())
        
        // 验证版本信息更新
        val versionInfo = versionManager.getCurrentVersionInfo()
        assertEquals("应用版本代码应该更新", VersionManager.CURRENT_APP_VERSION_CODE, versionInfo.appVersionCode)
        assertEquals("应用版本名称应该更新", VersionManager.CURRENT_APP_VERSION_NAME, versionInfo.appVersionName)
        assertEquals("Schema版本应该更新", VersionManager.CURRENT_SCHEMA_VERSION, versionInfo.schemaVersion)
    }

    /**
     * 测试重复升级不会重复执行
     */
    @Test
    fun testNoUpgradeWhenVersionsMatch() = runTest {
        // 首先执行一次升级
        versionManager.checkAndPerformUpgrade()
        
        // 再次执行升级
        val result = versionManager.checkAndPerformUpgrade()
        
        // 验证不应该有升级
        assertFalse("不应该识别为首次运行", result.wasFirstRun)
        assertTrue("不应该有升级信息", result.upgrades.isEmpty())
    }

    /**
     * 测试检查是否需要升级
     */
    @Test
    fun testNeedsUpgradeCheck() = runTest {
        // 清理之前的数据
        clearVersionPreferences()
        
        // 检查是否需要升级
        assertTrue("新安装应该需要升级", versionManager.needsUpgrade())
        
        // 执行升级
        versionManager.checkAndPerformUpgrade()
        
        // 再次检查
        assertFalse("升级后不应该需要再次升级", versionManager.needsUpgrade())
    }

    /**
     * 测试数据包Schema版本兼容性
     */
    @Test
    fun testDataPacketSchemaCompatibility() {
        val currentVersion = versionManager.getDataPacketSchemaVersion()
        
        // 当前版本应该兼容
        assertTrue("当前版本应该兼容", 
            versionManager.isDataPacketSchemaCompatible(currentVersion))
        
        // 低版本应该兼容
        assertTrue("低版本应该兼容", 
            versionManager.isDataPacketSchemaCompatible(currentVersion - 1))
        
        // 高版本不应该兼容
        assertFalse("高版本不应该兼容", 
            versionManager.isDataPacketSchemaCompatible(currentVersion + 1))
    }

    /**
     * 测试获取版本信息
     */
    @Test
    fun testGetVersionInfo() = runTest {
        // 执行升级以确保版本信息存在
        versionManager.checkAndPerformUpgrade()
        
        val versionInfo = versionManager.getCurrentVersionInfo()
        
        // 验证版本信息
        assertEquals("应用版本代码应该正确", 
            VersionManager.CURRENT_APP_VERSION_CODE, versionInfo.appVersionCode)
        assertEquals("应用版本名称应该正确", 
            VersionManager.CURRENT_APP_VERSION_NAME, versionInfo.appVersionName)
        assertEquals("Schema版本应该正确", 
            VersionManager.CURRENT_SCHEMA_VERSION, versionInfo.schemaVersion)
        assertTrue("应该有升级时间", versionInfo.lastUpgradeTime > 0)
    }

    /**
     * 测试模拟的版本升级情景
     */
    @Test
    fun testSimulatedVersionUpgrade() = runTest {
        // 模拟旧版本状态
        setMockOldVersion(appVersionCode = 0, schemaVersion = 0)
        
        // 执行升级
        val result = versionManager.checkAndPerformUpgrade()
        
        // 验证升级结果
        assertTrue("应该有应用版本升级", 
            result.upgrades.any { it.contains("应用版本") })
        assertTrue("应该有Schema版本升级", 
            result.upgrades.any { it.contains("数据模型版本") })
    }

    /**
     * 测试升级结果的时间戳
     */
    @Test
    fun testUpgradeTimestamp() = runTest {
        clearVersionPreferences()
        
        val beforeTime = System.currentTimeMillis()
        val result = versionManager.checkAndPerformUpgrade()
        val afterTime = System.currentTimeMillis()
        
        // 验证时间戳在合理范围内
        assertTrue("升级时间戳应该在执行时间范围内", 
            result.timestamp >= beforeTime && result.timestamp <= afterTime)
        
        // 验证版本信息中的时间戳
        val versionInfo = versionManager.getCurrentVersionInfo()
        assertEquals("版本信息中的时间戳应该与升级结果一致", 
            result.timestamp, versionInfo.lastUpgradeTime)
    }

    /**
     * 清理版本首选项数据
     */
    private suspend fun clearVersionPreferences() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    /**
     * 设置模拟的旧版本数据
     */
    private suspend fun setMockOldVersion(appVersionCode: Int, schemaVersion: Int) {
        val STORED_APP_VERSION_CODE = intPreferencesKey("stored_app_version_code")
        val STORED_APP_VERSION_NAME = stringPreferencesKey("stored_app_version_name")
        val STORED_SCHEMA_VERSION = intPreferencesKey("stored_schema_version")
        
        context.dataStore.edit { preferences ->
            preferences[STORED_APP_VERSION_CODE] = appVersionCode
            preferences[STORED_APP_VERSION_NAME] = "0.9.0"
            preferences[STORED_SCHEMA_VERSION] = schemaVersion
        }
    }

    /**
     * 测试Schema版本获取
     */
    @Test
    fun testGetDataPacketSchemaVersion() {
        val schemaVersion = versionManager.getDataPacketSchemaVersion()
        
        assertEquals("Schema版本应该与常量一致", 
            VersionManager.CURRENT_SCHEMA_VERSION, schemaVersion)
        assertTrue("Schema版本应该大于0", schemaVersion > 0)
    }
}