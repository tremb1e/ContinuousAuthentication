package com.continuousauth.performance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.continuousauth.observability.PerformanceMonitorImpl
import com.continuousauth.core.SmartTransmissionManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * 性能监控和分析测试
 * 测试应用在各种负载下的CPU和内存使用情况
 * 对应Task 5.2.2: 使用Android Profiler和Battery Historian分析应用功耗
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PerformanceAnalysisTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var performanceMonitor: PerformanceMonitorImpl

    @Inject
    lateinit var smartTransmissionManager: SmartTransmissionManager

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun init() {
        hiltRule.inject()
    }

    /**
     * 测试基线性能监控
     */
    @Test
    fun testBaselinePerformanceMonitoring() = runTest {
        // 启动性能监控
        performanceMonitor.startMonitoring(1000L) // 每秒采样

        // 等待几秒收集基线数据
        delay(5000L)

        // 获取性能统计
        val stats = performanceMonitor.getPerformanceStats(5000L)
        
        // 验证基本指标
        assertTrue("应该有采样数据", stats.sampleCount > 0)
        assertTrue("内存使用应该大于0", stats.memoryUsageAvg > 0.0)
        assertTrue("CPU使用率应该大于等于0", stats.cpuUsageAvg >= 0.0)
        assertTrue("堆内存使用应该大于0", stats.heapUsageAvg > 0.0)
        assertTrue("堆利用率应该在合理范围内", stats.heapUtilization >= 0.0 && stats.heapUtilization <= 100.0)

        // 验证峰值不会异常高
        assertTrue("峰值内存使用不应该超过合理阈值", stats.memoryUsagePeak < 500L) // 500MB阈值
        assertTrue("峰值CPU使用率不应该超过80%", stats.cpuUsagePeak <= 80.0)

        performanceMonitor.stopMonitoring()
    }

    /**
     * 测试高负载下的性能表现
     */
    @Test
    fun testHighLoadPerformance() = runTest {
        performanceMonitor.startMonitoring(500L) // 更频繁的采样

        // 启动智能传输管理器产生负载
        smartTransmissionManager.start()

        // 模拟大量传感器数据处理
        val startTime = System.currentTimeMillis()
        repeat(1000) { index ->
            // 生成变化的传感器数据
            val x = (index % 10) * 0.1f + Math.random().toFloat() * 0.5f
            val y = (index % 8) * 0.12f + Math.random().toFloat() * 0.3f
            val z = 9.8f + Math.random().toFloat() * 0.2f
            
            smartTransmissionManager.processSensorData(x, y, z, System.nanoTime() + index * 10000000L)
            
            // 每100个样本暂停一下，避免过度消耗
            if (index % 100 == 0) {
                delay(10L)
            }
        }

        val processingTime = System.currentTimeMillis() - startTime
        
        // 等待性能数据收集
        delay(2000L)

        val stats = performanceMonitor.getPerformanceStats(10000L)
        
        // 验证高负载下的性能表现
        assertTrue("高负载下应该有足够的采样数据", stats.sampleCount > 5)
        
        // 内存使用应该在合理范围内，即使在高负载下
        assertTrue("高负载下峰值内存不应超过1GB", stats.memoryUsagePeak < 1000L)
        
        // CPU使用率可能较高，但不应该持续100%
        assertTrue("平均CPU使用率应该低于95%", stats.cpuUsageAvg < 95.0)
        
        // 处理1000个样本应该在合理时间内完成
        assertTrue("处理时间应该在合理范围内", processingTime < 30000L) // 30秒内

        println("高负载性能测试结果:")
        println("处理时间: ${processingTime}ms")
        println("平均内存使用: ${String.format("%.1f", stats.memoryUsageAvg)}MB")
        println("峰值内存使用: ${stats.memoryUsagePeak}MB")
        println("平均CPU使用: ${String.format("%.1f", stats.cpuUsageAvg)}%")
        println("峰值CPU使用: ${String.format("%.1f", stats.cpuUsagePeak)}%")

        smartTransmissionManager.stop()
        performanceMonitor.stopMonitoring()
    }

    /**
     * 测试内存压力下的表现
     */
    @Test
    fun testMemoryPressureHandling() = runTest {
        performanceMonitor.startMonitoring(1000L)
        
        val initialStats = performanceMonitor.getPerformanceStats(1000L)
        val initialMemory = performanceMonitor.getCurrentSnapshot().memoryUsageMB

        // 创建一些内存压力（谨慎使用，避免OOM）
        val memoryConsumers = mutableListOf<ByteArray>()
        
        try {
            // 逐步分配内存块，观察性能变化
            repeat(20) { // 限制循环次数避免OOM
                val memoryBlock = ByteArray(1024 * 1024 * 5) // 5MB块
                memoryConsumers.add(memoryBlock)
                
                delay(100L) // 短暂等待
                
                val currentSnapshot = performanceMonitor.getCurrentSnapshot()
                
                // 验证内存使用量增加
                assertTrue(
                    "内存使用应该随分配增加",
                    currentSnapshot.memoryUsageMB >= initialMemory
                )
                
                // 如果内存使用过高，提前退出避免OOM
                if (currentSnapshot.memoryUsageMB > 200L) {
                    break
                }
            }
            
            delay(2000L) // 等待采样
            
            val pressureStats = performanceMonitor.getPerformanceStats(5000L)
            
            // 验证在内存压力下的表现
            assertTrue("内存压力下峰值内存应该增加", 
                pressureStats.memoryUsagePeak > initialStats.memoryUsagePeak)
            
            println("内存压力测试结果:")
            println("初始内存: ${initialMemory}MB")
            println("压力下峰值内存: ${pressureStats.memoryUsagePeak}MB")
            println("堆利用率: ${String.format("%.1f", pressureStats.heapUtilization)}%")
            
        } finally {
            // 清理内存，帮助GC
            memoryConsumers.clear()
            System.gc()
            delay(1000L) // 等待GC
        }

        performanceMonitor.stopMonitoring()
    }

    /**
     * 测试长时间运行的稳定性
     */
    @Test
    fun testLongRunningStability() = runTest {
        performanceMonitor.startMonitoring(2000L) // 较长的采样间隔

        smartTransmissionManager.start()

        val startTime = System.currentTimeMillis()
        val targetDuration = 30000L // 30秒长期测试（实际生产中应该是12小时）

        var iterationCount = 0
        
        while (System.currentTimeMillis() - startTime < targetDuration) {
            // 模拟正常的传感器数据流
            val x = Math.sin(iterationCount * 0.1) * 0.5f
            val y = Math.cos(iterationCount * 0.1) * 0.3f
            val z = 9.8f + Math.sin(iterationCount * 0.05) * 0.1f
            
            smartTransmissionManager.processSensorData(x.toFloat(), y.toFloat(), z, System.nanoTime())
            
            iterationCount++
            delay(50L) // 模拟20Hz采样率
        }

        val finalStats = performanceMonitor.getPerformanceStats(targetDuration)
        
        // 验证长期运行的稳定性
        assertTrue("长期运行应该有足够的采样数据", finalStats.sampleCount > 10)
        
        // 内存使用应该相对稳定，没有明显泄漏
        val memoryGrowth = finalStats.memoryUsagePeak - finalStats.memoryUsageAvg
        assertTrue("内存增长应该在合理范围内", memoryGrowth < 100L) // 100MB增长阈值
        
        // CPU使用应该在合理范围内
        assertTrue("长期运行平均CPU使用应该合理", finalStats.cpuUsageAvg < 50.0)
        
        println("长期稳定性测试结果:")
        println("运行时长: ${targetDuration / 1000}秒")
        println("处理样本数: $iterationCount")
        println("平均内存: ${String.format("%.1f", finalStats.memoryUsageAvg)}MB")
        println("峰值内存: ${finalStats.memoryUsagePeak}MB")
        println("内存变化: ${String.format("%.1f", memoryGrowth)}MB")
        println("平均CPU: ${String.format("%.1f", finalStats.cpuUsageAvg)}%")

        smartTransmissionManager.stop()
        performanceMonitor.stopMonitoring()
    }

    /**
     * 测试性能监控器自身的开销
     */
    @Test
    fun testPerformanceMonitorOverhead() = runTest {
        // 测试不启用监控时的基线性能
        val baselineStart = System.currentTimeMillis()
        
        repeat(1000) {
            // 执行一些基本操作
            val x = Math.random().toFloat()
            val y = Math.random().toFloat()
            val z = 9.8f + Math.random().toFloat() * 0.1f
            
            // 不进行任何监控，纯粹的计算
            val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble())
        }
        
        val baselineTime = System.currentTimeMillis() - baselineStart

        // 测试启用监控时的性能
        performanceMonitor.startMonitoring(100L) // 高频监控测试开销
        
        val monitoredStart = System.currentTimeMillis()
        
        repeat(1000) {
            val x = Math.random().toFloat()
            val y = Math.random().toFloat()
            val z = 9.8f + Math.random().toFloat() * 0.1f
            
            // 同样的计算，但在监控环境下
            val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble())
            
            // 获取性能快照（模拟监控开销）
            if (it % 100 == 0) {
                performanceMonitor.getCurrentSnapshot()
            }
        }
        
        val monitoredTime = System.currentTimeMillis() - monitoredStart
        
        performanceMonitor.stopMonitoring()

        // 计算监控开销
        val overhead = monitoredTime - baselineTime
        val overheadPercent = (overhead.toDouble() / baselineTime) * 100.0

        println("性能监控开销测试:")
        println("基线时间: ${baselineTime}ms")
        println("监控时间: ${monitoredTime}ms")
        println("开销: ${overhead}ms (${String.format("%.1f", overheadPercent)}%)")

        // 验证监控开销在可接受范围内
        assertTrue("性能监控开销应该小于50%", overheadPercent < 50.0)
    }

    /**
     * 测试环形缓冲区的效率
     */
    @Test
    fun testRingBufferPerformance() = runTest {
        performanceMonitor.startMonitoring(100L) // 高频采样测试缓冲区

        // 运行足够长时间以填满缓冲区
        delay(15000L) // 15秒，应该产生150个样本

        val samples = performanceMonitor.getRecentSamples(100)
        
        // 验证环形缓冲区正常工作
        assertTrue("应该获取到样本数据", samples.isNotEmpty())
        assertTrue("样本数量不应超过请求数量", samples.size <= 100)
        
        // 验证样本时间顺序
        if (samples.size > 1) {
            for (i in 1 until samples.size) {
                assertTrue("样本时间戳应该是递增的", 
                    samples[i].timestamp >= samples[i-1].timestamp)
            }
        }

        performanceMonitor.stopMonitoring()
    }

    /**
     * 测试多种负载模式下的性能特征
     */
    @Test
    fun testVariousLoadPatterns() = runTest {
        performanceMonitor.startMonitoring(500L)
        smartTransmissionManager.start()

        // 模式1: 突发负载
        println("测试突发负载模式...")
        repeat(5) {
            // 快速处理大量数据
            repeat(200) { index ->
                smartTransmissionManager.processSensorData(
                    Math.random().toFloat(),
                    Math.random().toFloat(),
                    9.8f + Math.random().toFloat() * 0.1f,
                    System.nanoTime()
                )
            }
            delay(1000L) // 然后休息
        }

        val burstStats = performanceMonitor.getPerformanceStats(10000L)
        println("突发负载 - 峰值CPU: ${String.format("%.1f", burstStats.cpuUsagePeak)}%, 峰值内存: ${burstStats.memoryUsagePeak}MB")

        // 模式2: 持续稳定负载
        println("测试持续稳定负载模式...")
        repeat(500) { index ->
            smartTransmissionManager.processSensorData(
                Math.sin(index * 0.1).toFloat(),
                Math.cos(index * 0.1).toFloat(),
                9.8f,
                System.nanoTime()
            )
            delay(20L) // 稳定的50Hz
        }

        val steadyStats = performanceMonitor.getPerformanceStats(15000L)
        println("稳定负载 - 平均CPU: ${String.format("%.1f", steadyStats.cpuUsageAvg)}%, 平均内存: ${String.format("%.1f", steadyStats.memoryUsageAvg)}MB")

        // 验证不同负载模式下的性能合理性
        assertTrue("突发负载峰值CPU应该高于稳定负载平均CPU", 
            burstStats.cpuUsagePeak > steadyStats.cpuUsageAvg)

        smartTransmissionManager.stop()
        performanceMonitor.stopMonitoring()
    }
}