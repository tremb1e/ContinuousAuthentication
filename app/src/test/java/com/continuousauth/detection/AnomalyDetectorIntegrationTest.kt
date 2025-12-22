package com.continuousauth.detection

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 异常检测器集成单元测试
 * 测试与系统服务的集成和复杂场景
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AnomalyDetectorIntegrationTest {

    private lateinit var mockContext: Context
    private lateinit var mockUsageStatsManager: UsageStatsManager
    private lateinit var anomalyDetector: AnomalyDetectorImpl
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope
    private lateinit var capturedReceiver: BroadcastReceiver
    private var fakeNowMs: Long = 0L

    @Before
    fun setup() {
        testDispatcher = UnconfinedTestDispatcher()
        testScope = TestScope(testDispatcher)
        Dispatchers.setMain(testDispatcher)
        
        // 创建模拟对象
        mockContext = mockk(relaxed = true)
        mockUsageStatsManager = mockk(relaxed = true)
        
        // 配置Context模拟
        every { mockContext.getSystemService(Context.USAGE_STATS_SERVICE) } returns mockUsageStatsManager
        
        // 捕获注册的广播接收器
        every { mockContext.registerReceiver(any(), any()) } answers {
            capturedReceiver = firstArg()
            null
        }
        every { mockContext.unregisterReceiver(any()) } just Runs
        
        anomalyDetector = AnomalyDetectorImpl(mockContext)
        anomalyDetector.overrideDispatcherForTests(testDispatcher)
        fakeNowMs = 1_000_000L
        anomalyDetector.overrideTimeProviderForTests { fakeNowMs }
    }

    @After
    fun tearDown() {
        runBlocking { anomalyDetector.stopDetection() }
        anomalyDetector.cleanup()
        Dispatchers.resetMain()
    }

    /**
     * 测试设备解锁事件的完整流程
     */
    @Test
    fun testDeviceUnlockCompleteFlow() = testScope.runTest {
        var anomalyDetected = false
        var detectedTrigger: AnomalyTrigger? = null
        
        val listener = object : OnAnomalyListener {
            override fun onAnomalyDetected(trigger: AnomalyTrigger) {
                anomalyDetected = true
                detectedTrigger = trigger
            }
        }
        
        anomalyDetector.setOnAnomalyListener(listener)
        
        // 启动检测（这会注册广播接收器）
        anomalyDetector.startDetection()
        
        runCurrent()
        
        // 验证广播接收器已注册
        verify { mockContext.registerReceiver(any(), any()) }
        
        // 模拟设备解锁广播
        val unlockIntent = Intent(Intent.ACTION_USER_PRESENT)
        capturedReceiver.onReceive(mockContext, unlockIntent)
        
        runCurrent()
        
        // 验证异常被检测到
        assertTrue("设备解锁异常应该被检测到", anomalyDetected)
        assertTrue("检测到的异常应该是设备解锁", detectedTrigger is AnomalyTrigger.DeviceUnlocked)
        
        anomalyDetector.stopDetection()
        
        // 验证广播接收器已取消注册
        verify { mockContext.unregisterReceiver(any()) }
    }

    /**
     * 测试设备解锁的冷却期机制
     */
    @Test
    fun testDeviceUnlockCooldown() = testScope.runTest {
        var anomalyCount = 0
        
        val listener = object : OnAnomalyListener {
            override fun onAnomalyDetected(trigger: AnomalyTrigger) {
                if (trigger is AnomalyTrigger.DeviceUnlocked) {
                    anomalyCount++
                }
            }
        }
        
        // 设置短冷却期便于测试
        val testPolicy = DetectionPolicy(deviceUnlockCooldownMs = 100L)
        anomalyDetector.updatePolicy(testPolicy)
        anomalyDetector.setOnAnomalyListener(listener)
        anomalyDetector.startDetection()
        
        runCurrent()
        
        // 第一次解锁
        val unlockIntent = Intent(Intent.ACTION_USER_PRESENT)
        capturedReceiver.onReceive(mockContext, unlockIntent)
        
        runCurrent()
        assertEquals("应该检测到第一次解锁", 1, anomalyCount)
        
        // 立即再次解锁（应该被冷却期阻止）
        capturedReceiver.onReceive(mockContext, unlockIntent)
        
        runCurrent()
        assertEquals("冷却期内的解锁应该被忽略", 1, anomalyCount)
        
        // 等待冷却期结束
        fakeNowMs += 150L
        runCurrent()
        
        // 冷却期后再次解锁
        capturedReceiver.onReceive(mockContext, unlockIntent)
        
        runCurrent()
        assertEquals("冷却期后应该检测到第二次解锁", 2, anomalyCount)
        
        anomalyDetector.stopDetection()
    }

    /**
     * 测试前台应用监控功能
     */
    @Test
    @Config(sdk = [28])
    fun testForegroundAppMonitoring() = testScope.runTest {
        var anomalyDetected = false
        var detectedTrigger: AnomalyTrigger? = null
        
        val listener = object : OnAnomalyListener {
            override fun onAnomalyDetected(trigger: AnomalyTrigger) {
                anomalyDetected = true
                detectedTrigger = trigger
            }
        }
        
        // 配置包含敏感应用的策略
        val testPolicy = DetectionPolicy(
            sensitiveApps = setOf("com.sensitive.app", "com.banking.app"),
            appCheckIntervalMs = 50L // 快速检查间隔便于测试
        )
        
        // 模拟UsageStats
        val mockUsageStats = mockk<UsageStats>()
        every { mockUsageStats.packageName } returns "com.sensitive.app"
        every { mockUsageStats.lastTimeUsed } answers { fakeNowMs }
        every { mockUsageStats.totalTimeInForeground } returns 1000L
        
        every {
            mockUsageStatsManager.queryUsageStats(
                any(),
                any(),
                any()
            )
        } returns listOf(mockUsageStats)
        
        anomalyDetector.updatePolicy(testPolicy)
        anomalyDetector.setOnAnomalyListener(listener)
        anomalyDetector.startDetection()
        
        // 等待前台应用检查周期
        fakeNowMs += 100L
        advanceTimeBy(100L)
        runCurrent()
        
        // 验证敏感应用异常被检测到
        assertTrue("敏感应用进入异常应该被检测到", anomalyDetected)
        assertTrue("检测到的异常应该是敏感应用进入", detectedTrigger is AnomalyTrigger.SensitiveAppEntered)
        
        val appTrigger = detectedTrigger as AnomalyTrigger.SensitiveAppEntered
        assertEquals("检测到的包名应该正确", "com.sensitive.app", appTrigger.packageName)
        
        anomalyDetector.stopDetection()
    }

    /**
     * 测试前台应用监控的异常处理
     */
    @Test
    @Config(sdk = [28])
    fun testForegroundAppMonitoringErrorHandling() = testScope.runTest {
        var anomalyDetected = false
        
        val listener = object : OnAnomalyListener {
            override fun onAnomalyDetected(trigger: AnomalyTrigger) {
                anomalyDetected = true
            }
        }
        
        // 配置包含敏感应用的策略
        val testPolicy = DetectionPolicy(
            sensitiveApps = setOf("com.sensitive.app"),
            appCheckIntervalMs = 50L
        )
        
        // 模拟UsageStatsManager抛出异常
        every { 
            mockUsageStatsManager.queryUsageStats(any(), any(), any())
        } throws SecurityException("权限不足")
        
        anomalyDetector.updatePolicy(testPolicy)
        anomalyDetector.setOnAnomalyListener(listener)
        anomalyDetector.startDetection()
        
        // 等待前台应用检查周期
        fakeNowMs += 100L
        advanceTimeBy(100L)
        runCurrent()
        
        // 异常处理应该防止崩溃，不应该检测到异常
        assertFalse("异常情况下不应该误报异常", anomalyDetected)
        
        anomalyDetector.stopDetection()
    }

    /**
     * 测试多种异常类型的并发检测
     */
    @Test
    fun testConcurrentAnomalyDetection() = testScope.runTest {
        val detectedTriggers = mutableListOf<AnomalyTrigger>()
        
        val listener = object : OnAnomalyListener {
            override fun onAnomalyDetected(trigger: AnomalyTrigger) {
                detectedTriggers.add(trigger)
            }
        }
        
        // 配置支持多种检测的策略
        val testPolicy = DetectionPolicy(
            enabled = true,
            deviceUnlockEnabled = true,
            sensitiveApps = setOf("com.test.app"),
            appCheckIntervalMs = 50L,
            accelerometerCooldownMs = 100L
        )
        
        anomalyDetector.updatePolicy(testPolicy)
        anomalyDetector.setOnAnomalyListener(listener)
        anomalyDetector.startDetection()
        
        runCurrent()
        
        // 1. 触发加速度计异常
        repeat(60) { index ->
            anomalyDetector.processSensorData(0.1f, 0.2f, 9.8f, System.nanoTime() + index * 1000000L)
        }
        anomalyDetector.processSensorData(10.0f, 8.0f, 15.0f, System.nanoTime())
        
        // 2. 触发设备解锁异常
        val unlockIntent = Intent(Intent.ACTION_USER_PRESENT)
        capturedReceiver.onReceive(mockContext, unlockIntent)
        
        runCurrent()
        
        // 验证检测到多种异常
        assertTrue("应该检测到至少2种异常", detectedTriggers.size >= 2)
        
        val hasAccelerometerAnomaly = detectedTriggers.any { it is AnomalyTrigger.AccelerometerSpike }
        val hasDeviceUnlockAnomaly = detectedTriggers.any { it is AnomalyTrigger.DeviceUnlocked }
        
        assertTrue("应该检测到加速度计异常", hasAccelerometerAnomaly)
        assertTrue("应该检测到设备解锁异常", hasDeviceUnlockAnomaly)
        
        anomalyDetector.stopDetection()
    }

    /**
     * 测试在策略禁用时的行为
     */
    @Test
    fun testDisabledPolicyBehavior() = testScope.runTest {
        var anomalyDetected = false
        
        val listener = object : OnAnomalyListener {
            override fun onAnomalyDetected(trigger: AnomalyTrigger) {
                anomalyDetected = true
            }
        }
        
        // 配置禁用策略
        val disabledPolicy = DetectionPolicy(
            enabled = false,
            deviceUnlockEnabled = false
        )
        
        anomalyDetector.updatePolicy(disabledPolicy)
        anomalyDetector.setOnAnomalyListener(listener)
        anomalyDetector.startDetection()
        
        // 即使启动了检测，但策略禁用时不应该注册广播接收器
        verify(exactly = 0) { mockContext.registerReceiver(any(), any()) }
        
        // 尝试处理传感器数据
        repeat(15) { index ->
            anomalyDetector.processSensorData(0.1f, 0.2f, 9.8f, System.nanoTime() + index * 1000000L)
        }
        anomalyDetector.processSensorData(20.0f, 20.0f, 20.0f, System.nanoTime())
        
        runCurrent()
        
        // 禁用状态下不应该检测到任何异常
        assertFalse("禁用策略下不应该检测到异常", anomalyDetected)
        
        anomalyDetector.stopDetection()
    }

    /**
     * 测试广播接收器的异常处理
     */
    @Test
    fun testBroadcastReceiverErrorHandling() = testScope.runTest {
        var anomalyDetected = false
        
        val listener = object : OnAnomalyListener {
            override fun onAnomalyDetected(trigger: AnomalyTrigger) {
                anomalyDetected = true
            }
        }
        
        anomalyDetector.setOnAnomalyListener(listener)
        anomalyDetector.startDetection()
        
        runCurrent()
        
        // 发送错误的Intent（null）
        capturedReceiver.onReceive(mockContext, null)
        
        // 发送错误的action
        val wrongIntent = Intent("wrong.action")
        capturedReceiver.onReceive(mockContext, wrongIntent)
        
        runCurrent()
        
        // 错误的广播不应该触发异常检测
        assertFalse("错误的广播不应该触发异常", anomalyDetected)
        
        // 正确的广播应该能正常工作
        val correctIntent = Intent(Intent.ACTION_USER_PRESENT)
        capturedReceiver.onReceive(mockContext, correctIntent)
        
        runCurrent()
        
        assertTrue("正确的广播应该触发异常", anomalyDetected)
        
        anomalyDetector.stopDetection()
    }

    /**
     * 测试资源清理
     */
    @Test
    fun testResourceCleanup() = testScope.runTest {
        anomalyDetector.startDetection()
        
        runCurrent()
        
        // 验证资源已分配
        verify { mockContext.registerReceiver(any(), any()) }
        
        // 调用清理方法
        anomalyDetector.cleanup()
        
        runCurrent()
        
        // 验证检测已停止
        assertFalse("清理后检测应该停止", anomalyDetector.isDetecting())
        
        // 验证广播接收器已取消注册
        verify { mockContext.unregisterReceiver(any()) }
    }

    /**
     * 测试大量传感器数据的性能
     */
    @Test
    fun testHighVolumeDataProcessing() = testScope.runTest {
        var anomalyCount = 0
        
        val listener = object : OnAnomalyListener {
            override fun onAnomalyDetected(trigger: AnomalyTrigger) {
                if (trigger is AnomalyTrigger.AccelerometerSpike) {
                    anomalyCount++
                }
            }
        }
        
        // 配置较宽松的策略以便检测
        val testPolicy = DetectionPolicy(
            accelerometerSpikeThreshold = 2.0f,
            accelerometerCooldownMs = 50L
        )
        
        anomalyDetector.updatePolicy(testPolicy)
        anomalyDetector.setOnAnomalyListener(listener)
        anomalyDetector.startDetection()
        
        // 发送大量数据点
        val startTime = System.currentTimeMillis()
        
        // 建立基线
        repeat(100) { index ->
            anomalyDetector.processSensorData(
                x = 0.1f + (index % 10) * 0.01f,
                y = 0.2f + (index % 10) * 0.01f,
                z = 9.8f + (index % 10) * 0.01f,
                timestamp = System.nanoTime() + index * 1000000L
            )
        }
        
        // 插入一些突变
        repeat(5) { spikeIndex ->
            fakeNowMs += 60L
            advanceTimeBy(60L) // 等待冷却期
            runCurrent()
            anomalyDetector.processSensorData(
                x = 10.0f + spikeIndex,
                y = 10.0f + spikeIndex,
                z = 10.0f + spikeIndex,
                timestamp = System.nanoTime() + (100 + spikeIndex) * 1000000L
            )
        }

        runCurrent()
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        // 验证性能和正确性
        assertTrue("处理大量数据应该在合理时间内完成", duration < 1000L) // 1秒内
        assertTrue("应该检测到一些异常", anomalyCount > 0)
        assertTrue("不应该有过多误报", anomalyCount <= 5)
        
        anomalyDetector.stopDetection()
    }
}
