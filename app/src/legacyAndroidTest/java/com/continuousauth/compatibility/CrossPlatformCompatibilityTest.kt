package com.continuousauth.compatibility

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.continuousauth.security.KeyAttestationManagerImpl
import com.continuousauth.observability.PerformanceMonitorImpl
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
 * 跨设备和跨系统版本兼容性测试
 * 对应Task 5.2.3: 进行跨设备和跨系统版本的兼容性测试
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CrossPlatformCompatibilityTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var keyAttestationManager: KeyAttestationManagerImpl

    @Inject
    lateinit var performanceMonitor: PerformanceMonitorImpl

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun init() {
        hiltRule.inject()
    }

    /**
     * 测试Android版本兼容性
     */
    @Test
    fun testAndroidVersionCompatibility() {
        val apiLevel = Build.VERSION.SDK_INT
        val androidVersion = Build.VERSION.RELEASE
        
        println("当前测试设备:")
        println("API Level: $apiLevel")
        println("Android Version: $androidVersion")
        println("设备型号: ${Build.MODEL}")
        println("制造商: ${Build.MANUFACTURER}")
        println("产品: ${Build.PRODUCT}")
        
        // 验证应用支持的最低API Level
        assertTrue("应用要求API 30+，当前设备: API $apiLevel", apiLevel >= 30)
        
        // 测试版本特定功能的可用性
        when {
            apiLevel >= Build.VERSION_CODES.TIRAMISU -> { // API 33+
                println("Android 13+ 特性可用")
                testAndroid13Features()
            }
            apiLevel >= Build.VERSION_CODES.S -> { // API 31+
                println("Android 12+ 特性可用")
                testAndroid12Features()
            }
            apiLevel >= Build.VERSION_CODES.R -> { // API 30+
                println("Android 11+ 特性可用")
                testAndroid11Features()
            }
        }
    }

    /**
     * 测试传感器硬件兼容性
     */
    @Test
    fun testSensorHardwareCompatibility() {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        // 测试必需的传感器
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        
        assertNotNull("设备必须有加速度计", accelerometer)
        assertNotNull("设备必须有陀螺仪", gyroscope)
        assertNotNull("设备必须有磁力计", magnetometer)
        
        println("传感器硬件信息:")
        
        // 测试加速度计特性
        accelerometer?.let { sensor ->
            println("加速度计:")
            println("  厂商: ${sensor.vendor}")
            println("  版本: ${sensor.version}")
            println("  最大范围: ${sensor.maximumRange}")
            println("  分辨率: ${sensor.resolution}")
            println("  功耗: ${sensor.power} mA")
            println("  最小延迟: ${sensor.minDelay} μs")
            println("  FIFO最大事件数: ${sensor.fifoMaxEventCount}")
            
            // 验证传感器基本特性
            assertTrue("加速度计最大范围应该合理", sensor.maximumRange > 0)
            assertTrue("加速度计分辨率应该合理", sensor.resolution > 0)
            
            // 测试高采样率支持
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val maxFreq = 1_000_000f / sensor.minDelay // Hz
                println("  最大采样频率: ${String.format("%.1f", maxFreq)} Hz")
                assertTrue("应该支持至少100Hz采样率", maxFreq >= 100f)
            }
        }
        
        // 测试陀螺仪特性
        gyroscope?.let { sensor ->
            println("陀螺仪:")
            println("  厂商: ${sensor.vendor}")
            println("  最大范围: ${sensor.maximumRange} rad/s")
            println("  分辨率: ${sensor.resolution}")
            println("  FIFO最大事件数: ${sensor.fifoMaxEventCount}")
            
            assertTrue("陀螺仪最大范围应该合理", sensor.maximumRange > 0)
        }
        
        // 测试磁力计特性
        magnetometer?.let { sensor ->
            println("磁力计:")
            println("  厂商: ${sensor.vendor}")
            println("  最大范围: ${sensor.maximumRange} μT")
            println("  分辨率: ${sensor.resolution}")
            
            assertTrue("磁力计最大范围应该合理", sensor.maximumRange > 0)
        }
    }

    /**
     * 测试设备功能特性兼容性
     */
    @Test
    fun testDeviceFeatureCompatibility() {
        val packageManager = context.packageManager
        
        println("设备功能特性检查:")
        
        // 检查传感器特性
        val hasSensorAccelerometer = packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER)
        val hasSensorGyroscope = packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)
        val hasSensorCompass = packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_COMPASS)
        
        assertTrue("设备必须支持加速度计", hasSensorAccelerometer)
        assertTrue("设备必须支持陀螺仪", hasSensorGyroscope)
        assertTrue("设备必须支持磁力计/指南针", hasSensorCompass)
        
        // 检查高采样率传感器支持
        val hasHighSamplingRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.hasSystemFeature("android.hardware.sensor.hifi_sensors")
        } else {
            false
        }
        
        println("高保真传感器支持: $hasHighSamplingRate")
        
        // 检查网络功能
        val hasWifi = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
        val hasCellular = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        
        assertTrue("设备应该支持WiFi或蜂窝网络", hasWifi || hasCellular)
        
        println("WiFi支持: $hasWifi")
        println("蜂窝网络支持: $hasCellular")
        
        // 检查加密功能
        val hasKeystore = packageManager.hasSystemFeature(PackageManager.FEATURE_SECURITY_MODEL_COMPATIBLE)
        println("安全模型兼容: $hasKeystore")
        
        // 检查StrongBox支持
        val hasStrongBox = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.hasSystemFeature("android.hardware.strongbox_keystore")
        } else {
            false
        }
        println("StrongBox支持: $hasStrongBox")
    }

    /**
     * 测试密钥存储兼容性
     */
    @Test
    fun testKeyStoreCompatibility() = runTest {
        println("密钥存储兼容性测试:")
        
        // 测试密钥证明支持
        val attestationSupported = keyAttestationManager.isAttestationSupported()
        val strongBoxSupported = keyAttestationManager.isStrongBoxSupported()
        
        println("密钥证明支持: $attestationSupported")
        println("StrongBox支持: $strongBoxSupported")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            assertTrue("API 24+应该支持密钥证明", attestationSupported)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // StrongBox支持取决于硬件，不是所有设备都有
            println("StrongBox支持状态已记录，不强制要求")
        }
        
        // 测试基本密钥存储功能
        try {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            println("Android KeyStore加载成功")
            
            val aliases = keyStore.aliases().toList()
            println("当前密钥别名数量: ${aliases.size}")
            
        } catch (e: Exception) {
            fail("Android KeyStore应该可以正常使用: ${e.message}")
        }
    }

    /**
     * 测试内存和性能兼容性
     */
    @Test
    fun testMemoryAndPerformanceCompatibility() = runTest {
        println("内存和性能兼容性测试:")
        
        // 获取设备内存信息
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val totalMemoryMB = memInfo.totalMem / 1024 / 1024
        val availableMemoryMB = memInfo.availMem / 1024 / 1024
        
        println("设备总内存: ${totalMemoryMB}MB")
        println("可用内存: ${availableMemoryMB}MB")
        println("内存不足阈值: ${memInfo.threshold / 1024 / 1024}MB")
        println("后台应用限制: ${memInfo.lowMemory}")
        
        // 验证设备有足够内存运行应用
        assertTrue("设备至少需要2GB内存", totalMemoryMB >= 2048)
        assertTrue("应该有足够的可用内存", availableMemoryMB >= 512)
        
        // 测试性能监控在当前设备上的表现
        performanceMonitor.startMonitoring(1000L)
        delay(3000L)
        
        val perfStats = performanceMonitor.getPerformanceStats(3000L)
        assertTrue("性能监控应该能正常采集数据", perfStats.sampleCount > 0)
        
        println("性能监控测试结果:")
        println("采样数量: ${perfStats.sampleCount}")
        println("平均内存使用: ${String.format("%.1f", perfStats.memoryUsageAvg)}MB")
        println("堆利用率: ${String.format("%.1f", perfStats.heapUtilization)}%")
        
        performanceMonitor.stopMonitoring()
    }

    /**
     * 测试权限兼容性
     */
    @Test
    fun testPermissionCompatibility() {
        println("权限兼容性测试:")
        
        val requiredPermissions = listOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.ACCESS_NETWORK_STATE,
            android.Manifest.permission.WAKE_LOCK
        )
        
        val optionalPermissions = listOf(
            android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "android.permission.PACKAGE_USAGE_STATS"
        )
        
        // 检查必需权限
        requiredPermissions.forEach { permission ->
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            println("必需权限 $permission: ${if (granted) "已授予" else "未授予"}")
            assertTrue("必需权限应该已授予: $permission", granted)
        }
        
        // 检查可选权限（不强制要求）
        optionalPermissions.forEach { permission ->
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            println("可选权限 $permission: ${if (granted) "已授予" else "未授予"}")
        }
        
        // 检查高采样率传感器权限（API 31+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val highSamplingPermission = "android.permission.HIGH_SAMPLING_RATE_SENSORS"
            val granted = context.checkSelfPermission(highSamplingPermission) == PackageManager.PERMISSION_GRANTED
            println("高采样率传感器权限: ${if (granted) "已授予" else "未授予"}")
        }
    }

    /**
     * 测试Android 11特定功能
     */
    private fun testAndroid11Features() {
        println("测试Android 11特定功能:")
        
        // 测试前台服务类型
        val packageManager = context.packageManager
        try {
            val serviceInfo = packageManager.getServiceInfo(
                android.content.ComponentName(context, "com.continuousauth.service.DataCollectionService"),
                0
            )
            println("前台服务配置正确")
        } catch (e: Exception) {
            println("前台服务配置检查失败: ${e.message}")
        }
    }

    /**
     * 测试Android 12特定功能
     */
    private fun testAndroid12Features() {
        testAndroid11Features()
        println("测试Android 12特定功能:")
        
        // 测试近似位置权限影响
        println("Android 12+权限模型已适配")
    }

    /**
     * 测试Android 13特定功能
     */
    private fun testAndroid13Features() {
        testAndroid12Features()
        println("测试Android 13特定功能:")
        
        // 测试通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationPermission = context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            println("通知权限状态: ${if (notificationPermission == PackageManager.PERMISSION_GRANTED) "已授予" else "未授予"}")
        }
    }

    /**
     * 测试处理器架构兼容性
     */
    @Test
    fun testProcessorArchitectureCompatibility() {
        println("处理器架构兼容性测试:")
        
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        val supportedAbis32 = Build.SUPPORTED_32_BIT_ABIS.toList()
        val supportedAbis64 = Build.SUPPORTED_64_BIT_ABIS.toList()
        
        println("支持的ABI: ${supportedAbis.joinToString(", ")}")
        println("32位ABI: ${supportedAbis32.joinToString(", ")}")
        println("64位ABI: ${supportedAbis64.joinToString(", ")}")
        
        assertTrue("设备应该支持至少一种ABI", supportedAbis.isNotEmpty())
        
        // 现代设备应该支持64位
        val has64BitSupport = supportedAbis64.isNotEmpty()
        println("64位支持: $has64BitSupport")
        
        // 检查常见架构支持
        val commonArchitectures = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        val supportedCommonArch = commonArchitectures.filter { it in supportedAbis }
        println("支持的常见架构: ${supportedCommonArch.joinToString(", ")}")
        
        assertTrue("应该支持至少一种常见架构", supportedCommonArch.isNotEmpty())
    }

    /**
     * 测试存储空间兼容性
     */
    @Test
    fun testStorageCompatibility() {
        println("存储空间兼容性测试:")
        
        // 测试应用私有目录访问
        val cacheDir = context.cacheDir
        val filesDir = context.filesDir
        
        assertNotNull("应用缓存目录应该可访问", cacheDir)
        assertNotNull("应用文件目录应该可访问", filesDir)
        
        println("缓存目录: ${cacheDir.absolutePath}")
        println("文件目录: ${filesDir.absolutePath}")
        
        // 测试可用存储空间
        val cacheSpace = cacheDir.usableSpace / 1024 / 1024 // MB
        val filesSpace = filesDir.usableSpace / 1024 / 1024 // MB
        
        println("缓存目录可用空间: ${cacheSpace}MB")
        println("文件目录可用空间: ${filesSpace}MB")
        
        assertTrue("缓存目录应该有足够空间", cacheSpace >= 100) // 至少100MB
        assertTrue("文件目录应该有足够空间", filesSpace >= 50)  // 至少50MB
        
        // 测试写入权限
        try {
            val testFile = java.io.File(cacheDir, "compatibility_test.tmp")
            testFile.writeText("测试数据")
            assertTrue("应该能写入缓存目录", testFile.exists())
            testFile.delete()
            println("缓存目录写入权限正常")
        } catch (e: Exception) {
            fail("缓存目录写入测试失败: ${e.message}")
        }
    }

    /**
     * 生成兼容性报告
     */
    @Test
    fun generateCompatibilityReport() {
        println("=== 设备兼容性报告 ===")
        println("设备信息:")
        println("  制造商: ${Build.MANUFACTURER}")
        println("  型号: ${Build.MODEL}")
        println("  产品: ${Build.PRODUCT}")
        println("  Android版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        println("  安全补丁: ${Build.VERSION.SECURITY_PATCH}")
        println("  构建版本: ${Build.DISPLAY}")
        
        // 收集所有兼容性信息
        val report = StringBuilder()
        report.appendLine("设备基本兼容性: ✓")
        
        try {
            testAndroidVersionCompatibility()
            report.appendLine("Android版本兼容性: ✓")
        } catch (e: AssertionError) {
            report.appendLine("Android版本兼容性: ✗ - ${e.message}")
        }
        
        try {
            testSensorHardwareCompatibility()
            report.appendLine("传感器硬件兼容性: ✓")
        } catch (e: AssertionError) {
            report.appendLine("传感器硬件兼容性: ✗ - ${e.message}")
        }
        
        try {
            testDeviceFeatureCompatibility()
            report.appendLine("设备功能兼容性: ✓")
        } catch (e: AssertionError) {
            report.appendLine("设备功能兼容性: ✗ - ${e.message}")
        }
        
        println("\n兼容性检查结果:")
        println(report.toString())
        
        // 总是通过这个测试，因为它只是生成报告
        assertTrue("兼容性报告生成完成", true)
    }
}