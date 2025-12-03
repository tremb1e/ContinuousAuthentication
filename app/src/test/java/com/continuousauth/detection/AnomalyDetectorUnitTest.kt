package com.continuousauth.detection

import android.app.usage.UsageStatsManager
import android.content.Context
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sqrt

/**
 * 异常检测器单元测试
 * 测试AnomalyDetectorImpl的核心逻辑和边界条件
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AnomalyDetectorUnitTest {

    private lateinit var mockContext: Context
    private lateinit var mockUsageStatsManager: UsageStatsManager
    private lateinit var anomalyDetector: AnomalyDetectorImpl
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope

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
        every { mockContext.registerReceiver(any(), any()) } returns null
        every { mockContext.unregisterReceiver(any()) } just Runs
        
        anomalyDetector = AnomalyDetectorImpl(mockContext)
        anomalyDetector.overrideDispatcherForTests(testDispatcher)
    }

    @After
    fun tearDown() {
        runBlocking { anomalyDetector.stopDetection() }
        anomalyDetector.cleanup()
        Dispatchers.resetMain()
    }

    /**
     * 测试异常检测器初始状态
     */
    @Test
    fun testInitialState() {
        assertFalse("初始状态应该未开始检测", anomalyDetector.isDetecting())
    }

    /**
     * 测试启动和停止检测
     */
    @Test
    fun testStartStopDetection() = testScope.runTest {
        // 测试启动
        anomalyDetector.startDetection()
        assertTrue("启动后应该处于检测状态", anomalyDetector.isDetecting())
        
        // 验证广播接收器注册
        verify { mockContext.registerReceiver(any(), any()) }
        
        // 测试停止
        anomalyDetector.stopDetection()
        assertFalse("停止后应该不在检测状态", anomalyDetector.isDetecting())
        
        // 验证广播接收器取消注册
        verify { mockContext.unregisterReceiver(any()) }
    }

    /**
     * 测试重复启动和停止
     */
    @Test
    fun testRepeatedStartStop() = testScope.runTest {
        // 重复启动应该安全
        anomalyDetector.startDetection()
        anomalyDetector.startDetection()
        assertTrue("重复启动后应该处于检测状态", anomalyDetector.isDetecting())
        
        // 重复停止应该安全
        anomalyDetector.stopDetection()
        anomalyDetector.stopDetection()
        assertFalse("重复停止后应该不在检测状态", anomalyDetector.isDetecting())
    }

    /**
     * 测试策略更新
     */
    @Test
    fun testPolicyUpdate() = testScope.runTest {
        val initialPolicy = DetectionPolicy(
            accelerometerSpikeThreshold = 2.0f,
            sensitiveApps = setOf("com.test.app1")
        )
        
        anomalyDetector.updatePolicy(initialPolicy)
        
        val updatedPolicy = DetectionPolicy(
            accelerometerSpikeThreshold = 5.0f,
            sensitiveApps = setOf("com.test.app1", "com.test.app2"),
            deviceUnlockEnabled = false
        )
        
        anomalyDetector.updatePolicy(updatedPolicy)
        
        // 策略更新应该成功（无异常抛出）
        assertTrue("策略更新应该成功", true)
    }

    /**
     * 测试策略更新时的检测器重新配置
     */
    @Test
    fun testPolicyUpdateWhileDetecting() = testScope.runTest {
        // 启动检测
        anomalyDetector.startDetection()
        assertTrue("应该处于检测状态", anomalyDetector.isDetecting())
        
        // 更新策略 - 禁用设备解锁检测
        val updatedPolicy = DetectionPolicy(deviceUnlockEnabled = false)
        anomalyDetector.updatePolicy(updatedPolicy)
        
        // 应该仍在检测
        assertTrue("策略更新后应该仍在检测", anomalyDetector.isDetecting())
        
        anomalyDetector.stopDetection()
    }

    /**
     * 测试加速度计数据处理 - 正常数据
     */
    @Test
    fun testAccelerometerDataProcessing_NormalData() = testScope.runTest {
        var anomalyDetected = false
        val listener = object : OnAnomalyListener {
            override fun onAnomalyDetected(trigger: AnomalyTrigger) {
                anomalyDetected = true
            }
        }
        
        anomalyDetector.setOnAnomalyListener(listener)
        anomalyDetector.startDetection()
        
        // 发送正常的加速度计数据
        repeat(10) { index ->
            val x = 0.1f + index * 0.01f
            val y = 0.2f + index * 0.01f
            val z = 9.8f + index * 0.01f
            anomalyDetector.processSensorData(x, y, z, System.nanoTime() + index * 1000000L)
        }
        
        advanceUntilIdle()
        
        // 正常数据不应该触发异常
        assertFalse("正常数据不应该触发异常", anomalyDetected)
        
        anomalyDetector.stopDetection()
    }

    /**
     * 测试加速度计数据处理 - 突变数据
     */
    @Test
    fun testAccelerometerDataProcessing_SpikeData() = testScope.runTest {
        var anomalyDetected = false
        var detectedTrigger: AnomalyTrigger? = null
        
        val listener = object : OnAnomalyListener {
            override fun onAnomalyDetected(trigger: AnomalyTrigger) {
                anomalyDetected = true
                detectedTrigger = trigger
            }
        }
        
        anomalyDetector.setOnAnomalyListener(listener)
        anomalyDetector.startDetection()
        
        // 建立正常基线
        repeat(60) { index ->
            val x = 0.1f
            val y = 0.2f
            val z = 9.8f
            anomalyDetector.processSensorData(x, y, z, System.nanoTime() + index * 1000000L)
        }
        
        // 发送突变数据
        val spikeX = 10.0f
        val spikeY = 8.0f
        val spikeZ = 15.0f
        anomalyDetector.processSensorData(spikeX, spikeY, spikeZ, System.nanoTime() + 16 * 1000000L)
        
        advanceUntilIdle()
        
        // 突变数据应该触发异常
        assertTrue("突变数据应该触发异常", anomalyDetected)
        assertTrue("应该检测到加速度计突变", detectedTrigger is AnomalyTrigger.AccelerometerSpike)
        
        val spike = detectedTrigger as AnomalyTrigger.AccelerometerSpike
        val expectedMagnitude = sqrt(spikeX * spikeX + spikeY * spikeY + spikeZ * spikeZ)
        assertEquals("突变幅度应该正确计算", expectedMagnitude, spike.magnitude, 0.01f)
        
        anomalyDetector.stopDetection()
    }

    /**
     * 测试加速度计冷却期机制
     */
    @Test
    fun testAccelerometerCooldownPeriod() = testScope.runTest {
        var anomalyCount = 0
        
        val listener = object : OnAnomalyListener {
            override fun onAnomalyDetected(trigger: AnomalyTrigger) {
                if (trigger is AnomalyTrigger.AccelerometerSpike) {
                    anomalyCount++
                }
            }
        }
        
        // 设置较短的冷却期便于测试
        val testPolicy = DetectionPolicy(
            accelerometerCooldownMs = 100L,
            accelerometerSpikeThreshold = 2.0f
        )
        
        anomalyDetector.updatePolicy(testPolicy)
        anomalyDetector.setOnAnomalyListener(listener)
        anomalyDetector.startDetection()
        
        // 建立基线
        repeat(60) { index ->
            anomalyDetector.processSensorData(0.1f, 0.2f, 9.8f, System.nanoTime() + index * 1000000L)
        }
        
        val baseTime = System.nanoTime()
        
        // 快速连续发送突变数据（应该被冷却期限制）
        anomalyDetector.processSensorData(10.0f, 8.0f, 15.0f, baseTime + 16 * 1000000L)
        anomalyDetector.processSensorData(10.0f, 8.0f, 15.0f, baseTime + 17 * 1000000L) // 冷却期内
        anomalyDetector.processSensorData(10.0f, 8.0f, 15.0f, baseTime + 18 * 1000000L) // 冷却期内
        
        advanceUntilIdle()
        
        // 应该只触发一次异常（因为冷却期）
        assertEquals("冷却期内应该只触发一次异常", 1, anomalyCount)
        
        // 等待冷却期结束后再次发送，使用显式时间戳确保跨越冷却窗口
        val postCooldownTs = baseTime + TimeUnit.MILLISECONDS.toNanos(200)
        anomalyDetector.processSensorData(10.0f, 8.0f, 15.0f, postCooldownTs)
        
        advanceUntilIdle()
        
        // 现在应该触发第二次异常
        assertEquals("冷却期后应该能再次触发异常", 2, anomalyCount)
        
        anomalyDetector.stopDetection()
    }

    /**
     * 测试监听器设置和回调
     */
    @Test
    fun testAnomalyListenerCallback() = testScope.runTest {
        var callbackCount = 0
        var lastTrigger: AnomalyTrigger? = null
        
        val listener = object : OnAnomalyListener {
            override fun onAnomalyDetected(trigger: AnomalyTrigger) {
                callbackCount++
                lastTrigger = trigger
            }
            
            override fun onAnomalyCleared(trigger: AnomalyTrigger) {
                // 测试可选方法
            }
        }
        
        // 设置监听器
        anomalyDetector.setOnAnomalyListener(listener)
        anomalyDetector.startDetection()
        
        // 建立基线并发送突变数据
        repeat(60) { index ->
            anomalyDetector.processSensorData(0.1f, 0.2f, 9.8f, System.nanoTime() + index * 1000000L)
        }
        anomalyDetector.processSensorData(10.0f, 8.0f, 15.0f, System.nanoTime() + 16 * 1000000L)
        
        advanceUntilIdle()
        
        assertEquals("应该调用一次回调", 1, callbackCount)
        assertTrue("回调参数应该是加速度计突变", lastTrigger is AnomalyTrigger.AccelerometerSpike)
        
        // 清除监听器
        anomalyDetector.setOnAnomalyListener(null)
        
        // 再次发送突变数据
        delay(200L) // 等待冷却期
        anomalyDetector.processSensorData(10.0f, 8.0f, 15.0f, System.nanoTime())
        
        advanceUntilIdle()
        
        // 回调次数不应该增加
        assertEquals("清除监听器后不应该有新的回调", 1, callbackCount)
        
        anomalyDetector.stopDetection()
    }

    /**
     * 测试不同的检测策略配置
     */
    @Test
    fun testDifferentPolicyConfigurations() = testScope.runTest {
        // 测试禁用所有检测
        val disabledPolicy = DetectionPolicy(enabled = false)
        anomalyDetector.updatePolicy(disabledPolicy)
        
        var anomalyDetected = false
        val listener = object : OnAnomalyListener {
            override fun onAnomalyDetected(trigger: AnomalyTrigger) {
                anomalyDetected = true
            }
        }
        
        anomalyDetector.setOnAnomalyListener(listener)
        anomalyDetector.startDetection()
        
        // 发送明显的突变数据
        repeat(15) { index ->
            anomalyDetector.processSensorData(0.1f, 0.2f, 9.8f, System.nanoTime() + index * 1000000L)
        }
        anomalyDetector.processSensorData(20.0f, 20.0f, 20.0f, System.nanoTime())
        
        advanceUntilIdle()
        
        // 禁用状态下不应该检测到异常
        assertFalse("禁用状态下不应该检测到异常", anomalyDetected)
        
        anomalyDetector.stopDetection()
    }

    /**
     * 测试极端情况 - 空数据
     */
    @Test
    fun testEdgeCase_EmptyData() = testScope.runTest {
        var anomalyDetected = false
        val listener = object : OnAnomalyListener {
            override fun onAnomalyDetected(trigger: AnomalyTrigger) {
                anomalyDetected = true
            }
        }
        
        anomalyDetector.setOnAnomalyListener(listener)
        anomalyDetector.startDetection()
        
        // 只发送很少的数据点（不足以建立基线）
        anomalyDetector.processSensorData(10.0f, 10.0f, 10.0f, System.nanoTime())
        anomalyDetector.processSensorData(20.0f, 20.0f, 20.0f, System.nanoTime() + 1000000L)
        
        advanceUntilIdle()
        
        // 数据不足时不应该触发异常
        assertFalse("数据不足时不应该触发异常", anomalyDetected)
        
        anomalyDetector.stopDetection()
    }

    /**
     * 测试极端情况 - 零幅度数据
     */
    @Test
    fun testEdgeCase_ZeroMagnitudeData() = testScope.runTest {
        var anomalyDetected = false
        val listener = object : OnAnomalyListener {
            override fun onAnomalyDetected(trigger: AnomalyTrigger) {
                anomalyDetected = true
            }
        }
        
        anomalyDetector.setOnAnomalyListener(listener)
        anomalyDetector.startDetection()
        
        // 发送零幅度数据建立基线
        repeat(20) { index ->
            anomalyDetector.processSensorData(0.0f, 0.0f, 0.0f, System.nanoTime() + index * 1000000L)
        }
        
        // 发送轻微变化
        anomalyDetector.processSensorData(0.1f, 0.1f, 0.1f, System.nanoTime() + 21 * 1000000L)
        
        advanceUntilIdle()
        
        // 零基线的轻微变化不应该触发异常（由于stdDev检查）
        assertFalse("零基线的轻微变化不应该触发异常", anomalyDetected)
        
        anomalyDetector.stopDetection()
    }

    /**
     * 测试多种异常类型的触发器
     */
    @Test
    fun testMultipleAnomalyTypes() {
        // 测试设备解锁触发器
        val deviceUnlock = AnomalyTrigger.DeviceUnlocked
        assertTrue("设备解锁触发器应该是正确类型", deviceUnlock is AnomalyTrigger.DeviceUnlocked)
        
        // 测试加速度计突变触发器
        val accelerometerSpike = AnomalyTrigger.AccelerometerSpike(
            magnitude = 15.0f,
            threshold = 10.0f,
            deviation = 2.5f
        )
        assertTrue("加速度计突变触发器应该是正确类型", accelerometerSpike is AnomalyTrigger.AccelerometerSpike)
        assertEquals("突变幅度应该正确", 15.0f, accelerometerSpike.magnitude, 0.01f)
        assertEquals("阈值应该正确", 10.0f, accelerometerSpike.threshold, 0.01f)
        assertEquals("偏差应该正确", 2.5f, accelerometerSpike.deviation, 0.01f)
        
        // 测试敏感应用触发器
        val sensitiveApp = AnomalyTrigger.SensitiveAppEntered(
            packageName = "com.test.app",
            appName = "测试应用"
        )
        assertTrue("敏感应用触发器应该是正确类型", sensitiveApp is AnomalyTrigger.SensitiveAppEntered)
        assertEquals("包名应该正确", "com.test.app", sensitiveApp.packageName)
        assertEquals("应用名应该正确", "测试应用", sensitiveApp.appName)
    }

    /**
     * 测试检测策略的默认值
     */
    @Test
    fun testDetectionPolicyDefaults() {
        val defaultPolicy = DetectionPolicy()
        
        assertEquals("默认加速度计阈值应该正确", 3.0f, defaultPolicy.accelerometerSpikeThreshold, 0.01f)
        assertEquals("默认窗口大小应该正确", 20, defaultPolicy.accelerometerWindowSize)
        assertEquals("默认加速度计冷却期应该正确", 2000L, defaultPolicy.accelerometerCooldownMs)
        assertTrue("默认应该启用异常检测", defaultPolicy.enabled)
        assertTrue("默认应该启用设备解锁检测", defaultPolicy.deviceUnlockEnabled)
        assertFalse("默认应该关闭调试模式", defaultPolicy.debugMode)
        assertTrue("默认敏感应用列表应该为空", defaultPolicy.sensitiveApps.isEmpty())
    }

    /**
     * 测试检测策略的自定义值
     */
    @Test
    fun testDetectionPolicyCustomValues() {
        val customPolicy = DetectionPolicy(
            accelerometerSpikeThreshold = 5.0f,
            accelerometerWindowSize = 30,
            accelerometerCooldownMs = 1000L,
            sensitiveApps = setOf("app1", "app2", "app3"),
            appCheckIntervalMs = 2000L,
            deviceUnlockEnabled = false,
            deviceUnlockCooldownMs = 3000L,
            enabled = true,
            debugMode = true
        )
        
        assertEquals("自定义加速度计阈值应该正确", 5.0f, customPolicy.accelerometerSpikeThreshold, 0.01f)
        assertEquals("自定义窗口大小应该正确", 30, customPolicy.accelerometerWindowSize)
        assertEquals("自定义加速度计冷却期应该正确", 1000L, customPolicy.accelerometerCooldownMs)
        assertEquals("自定义敏感应用数量应该正确", 3, customPolicy.sensitiveApps.size)
        assertTrue("自定义敏感应用列表应该包含app1", customPolicy.sensitiveApps.contains("app1"))
        assertEquals("自定义应用检查间隔应该正确", 2000L, customPolicy.appCheckIntervalMs)
        assertFalse("自定义设备解锁检测应该禁用", customPolicy.deviceUnlockEnabled)
        assertEquals("自定义设备解锁冷却期应该正确", 3000L, customPolicy.deviceUnlockCooldownMs)
        assertTrue("自定义异常检测应该启用", customPolicy.enabled)
        assertTrue("自定义调试模式应该启用", customPolicy.debugMode)
    }
}
