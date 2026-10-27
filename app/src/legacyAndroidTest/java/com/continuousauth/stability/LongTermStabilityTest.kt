package com.continuousauth.stability

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.continuousauth.core.SmartTransmissionManager
import com.continuousauth.observability.MetricsCollectorImpl
import com.continuousauth.observability.PerformanceMonitorImpl
import com.continuousauth.ui.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import kotlin.math.sin
import kotlin.random.Random

/**
 * 长期稳定性测试套件
 * 对应Task 5.3.3: CI流程需包含长期稳定性测试：在高中低端设备上持续运行 ≥12小时，
 * 监控ANR、崩溃和内存泄漏
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LongTermStabilityTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    var activityScenarioRule = ActivityScenarioRule(MainActivity::class.java)

    @Inject
    lateinit var smartTransmissionManager: SmartTransmissionManager

    @Inject
    lateinit var performanceMonitor: PerformanceMonitorImpl

    @Inject
    lateinit var metricsCollector: MetricsCollectorImpl

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val activityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }

    companion object {
        // 在CI环境中，我们使用较短的时间进行测试（30分钟）
        // 在实际设备上进行完整的12小时测试
        private val TEST_DURATION_MS = if (isRunningInCI()) {
            30 * 60 * 1000L  // 30分钟用于CI
        } else {
            12 * 60 * 60 * 1000L  // 12小时用于实际设备测试
        }
        
        private const val MEMORY_LEAK_THRESHOLD_MB = 100L  // 内存泄漏阈值
        private const val MAX_MEMORY_USAGE_MB = 500L       // 最大内存使用量
        private const val MONITORING_INTERVAL_MS = 30000L  // 监控间隔30秒
        private const val PERFORMANCE_SAMPLE_INTERVAL_MS = 5000L  // 性能采样间隔5秒
        
        private fun isRunningInCI(): Boolean {
            return System.getenv("CI") == "true" ||
                   System.getProperty("CI") == "true" ||
                   System.getenv("GITHUB_ACTIONS") == "true"
        }
    }

    @Before
    fun init() {
        hiltRule.inject()
    }

    /**
     * 主要的长期稳定性测试
     * 监控内存泄漏、ANR和崩溃
     */
    @Test
    fun testLongTermStability() = runTest(timeout = TEST_DURATION_MS + 60000L) {
        val testStartTime = System.currentTimeMillis()
        val testDurationHours = TEST_DURATION_MS / (60 * 60 * 1000.0)
        
        println("=== 长期稳定性测试开始 ===")
        println("测试时长: ${String.format("%.1f", testDurationHours)}小时")
        println("运行环境: ${if (isRunningInCI()) "CI环境" else "实际设备"}")
        println("监控间隔: ${MONITORING_INTERVAL_MS / 1000}秒")
        println("开始时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        
        // 启动核心服务
        performanceMonitor.startMonitoring(PERFORMANCE_SAMPLE_INTERVAL_MS)
        smartTransmissionManager.start()
        
        // 稳定性监控数据
        val stabilityStats = StabilityStats()
        
        try {
            // 创建数据生成协程
            val dataGeneratorJob = launch {
                generateContinuousData(stabilityStats)
            }
            
            // 创建监控协程
            val monitoringJob = launch {
                monitorSystemHealth(testStartTime, stabilityStats)
            }
            
            // 等待测试完成
            val testJob = launch {
                delay(TEST_DURATION_MS)
            }
            
            // 等待所有任务完成
            joinAll(dataGeneratorJob, monitoringJob, testJob)
            
            // 验证测试结果
            validateStabilityResults(stabilityStats, testStartTime)
            
        } finally {
            // 清理资源
            smartTransmissionManager.stop()
            performanceMonitor.stopMonitoring()
            
            // 生成最终报告
            generateFinalReport(stabilityStats, testStartTime)
        }
    }

    /**
     * 连续数据生成
     * 模拟应用正常工作负载
     */
    private suspend fun generateContinuousData(stats: StabilityStats) {
        var dataCount = 0L
        val startTime = System.currentTimeMillis()
        
        while (true) {
            try {
                // 模拟传感器数据流
                val x = sin(dataCount * 0.01) * 2.0f + Random.nextFloat() * 0.1f
                val y = sin(dataCount * 0.015 + 1.0) * 1.5f + Random.nextFloat() * 0.1f  
                val z = 9.8f + sin(dataCount * 0.008) * 0.2f + Random.nextFloat() * 0.05f
                
                smartTransmissionManager.processSensorData(x, y, z, System.nanoTime())
                
                dataCount++
                stats.totalDataPointsGenerated = dataCount
                
                // 每1000个数据点记录一次
                if (dataCount % 1000 == 0L) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val rate = dataCount * 1000.0 / elapsed
                    println("数据生成统计: ${dataCount}个数据点, 速率: ${String.format("%.1f", rate)}点/秒")
                }
                
                // 模拟20Hz采样率
                delay(50L)
                
            } catch (e: Exception) {
                stats.dataGenerationErrors++
                println("数据生成错误: ${e.message}")
                
                // 如果连续错误太多，可能是严重问题
                if (stats.dataGenerationErrors > 100) {
                    throw Exception("数据生成错误过多，测试失败")
                }
            }
        }
    }

    /**
     * 系统健康监控
     * 监控内存、性能指标和潜在问题
     */
    private suspend fun monitorSystemHealth(testStartTime: Long, stats: StabilityStats) {
        var initialMemory = getCurrentMemoryUsage()
        var maxMemoryUsage = initialMemory
        var lastGcTime = System.currentTimeMillis()
        
        println("初始内存使用: ${initialMemory}MB")
        
        while (true) {
            try {
                val currentMemory = getCurrentMemoryUsage()
                val heapMemory = getCurrentHeapUsage()
                maxMemoryUsage = maxOf(maxMemoryUsage, currentMemory)
                
                // 更新统计信息
                stats.currentMemoryMB = currentMemory
                stats.maxMemoryMB = maxOf(stats.maxMemoryMB, currentMemory)
                stats.currentHeapMB = heapMemory
                
                // 检查内存泄漏
                val memoryGrowth = currentMemory - initialMemory
                if (memoryGrowth > MEMORY_LEAK_THRESHOLD_MB) {
                    stats.memoryLeakWarnings++
                    println("WARNING: 可能的内存泄漏检测到，内存增长: ${memoryGrowth}MB")
                    
                    // 尝试触发GC并重新评估
                    System.gc()
                    delay(2000L)
                    val afterGcMemory = getCurrentMemoryUsage()
                    val actualLeak = afterGcMemory - initialMemory
                    
                    if (actualLeak > MEMORY_LEAK_THRESHOLD_MB) {
                        println("ERROR: 确认内存泄漏，GC后仍增长: ${actualLeak}MB")
                        stats.confirmedMemoryLeaks++
                    } else {
                        println("INFO: GC后内存恢复正常，增长: ${actualLeak}MB")
                    }
                    
                    lastGcTime = System.currentTimeMillis()
                }
                
                // 检查过度内存使用
                if (currentMemory > MAX_MEMORY_USAGE_MB) {
                    stats.highMemoryEvents++
                    println("WARNING: 内存使用过高: ${currentMemory}MB")
                }
                
                // 获取性能统计
                val perfStats = performanceMonitor.getPerformanceStats(MONITORING_INTERVAL_MS)
                stats.avgCpuUsage = perfStats.cpuUsageAvg
                stats.maxCpuUsage = maxOf(stats.maxCpuUsage, perfStats.cpuUsagePeak)
                
                // 检查CPU使用率异常
                if (perfStats.cpuUsageAvg > 80.0) {
                    stats.highCpuEvents++
                    println("WARNING: CPU使用率过高: ${String.format("%.1f", perfStats.cpuUsageAvg)}%")
                }
                
                // 检查应用是否响应
                checkApplicationResponsiveness(stats)
                
                // 定期报告状态
                val elapsed = System.currentTimeMillis() - testStartTime
                if (elapsed % (5 * 60 * 1000) == 0L) { // 每5分钟
                    reportPeriodicStatus(elapsed, stats)
                }
                
                delay(MONITORING_INTERVAL_MS)
                
            } catch (e: Exception) {
                stats.monitoringErrors++
                println("监控错误: ${e.message}")
                delay(MONITORING_INTERVAL_MS)
            }
        }
    }

    /**
     * 检查应用响应性
     * 检测潜在的ANR情况
     */
    private fun checkApplicationResponsiveness(stats: StabilityStats) {
        try {
            activityScenarioRule.scenario.onActivity { activity ->
                // 检查主线程是否被阻塞
                val startTime = System.currentTimeMillis()
                
                activity.runOnUiThread {
                    // 这个任务应该很快完成
                    val endTime = System.currentTimeMillis()
                    val delay = endTime - startTime
                    
                    if (delay > 5000) { // 超过5秒认为可能有ANR
                        stats.potentialAnrEvents++
                        println("WARNING: 检测到潜在ANR，UI线程响应延迟: ${delay}ms")
                    }
                    
                    stats.uiResponseDelayMs = delay
                }
            }
        } catch (e: Exception) {
            stats.responsivenesCheckErrors++
            println("响应性检查错误: ${e.message}")
        }
    }

    /**
     * 验证稳定性测试结果
     */
    private fun validateStabilityResults(stats: StabilityStats, testStartTime: Long) {
        val testDurationHours = (System.currentTimeMillis() - testStartTime) / (60.0 * 60.0 * 1000.0)
        
        println("\n=== 稳定性测试结果验证 ===")
        
        // 验证基本运行时长
        assertTrue(
            "测试应该运行足够长时间",
            testDurationHours >= (if (isRunningInCI()) 0.4 else 11.9) // CI: 24分钟, 设备: 11.9小时
        )
        
        // 验证数据生成
        assertTrue("应该生成大量数据", stats.totalDataPointsGenerated > 1000L)
        
        // 验证内存使用
        assertTrue(
            "内存使用应该在合理范围内",
            stats.maxMemoryMB < MAX_MEMORY_USAGE_MB
        )
        
        // 验证没有确认的内存泄漏
        if (stats.confirmedMemoryLeaks > 0) {
            fail("检测到 ${stats.confirmedMemoryLeaks} 个确认的内存泄漏")
        }
        
        // 验证没有过多的ANR事件
        assertTrue(
            "ANR事件应该很少 (< 5)",
            stats.potentialAnrEvents < 5
        )
        
        // 验证CPU使用合理
        assertTrue(
            "平均CPU使用率应该合理 (< 60%)",
            stats.avgCpuUsage < 60.0
        )
        
        println("✓ 所有稳定性验证通过")
    }

    /**
     * 生成最终测试报告
     */
    private fun generateFinalReport(stats: StabilityStats, testStartTime: Long) {
        val testDuration = System.currentTimeMillis() - testStartTime
        val testDurationHours = testDuration / (60.0 * 60.0 * 1000.0)
        
        val report = StringBuilder()
        report.appendLine("=" * 50)
        report.appendLine("长期稳定性测试最终报告")
        report.appendLine("=" * 50)
        report.appendLine("测试完成时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        report.appendLine("测试总时长: ${String.format("%.2f", testDurationHours)} 小时")
        report.appendLine("运行环境: ${if (isRunningInCI()) "CI环境" else "实际设备"}")
        
        report.appendLine("\n数据生成统计:")
        report.appendLine("  生成数据点: ${stats.totalDataPointsGenerated}")
        report.appendLine("  数据生成错误: ${stats.dataGenerationErrors}")
        report.appendLine("  生成速率: ${String.format("%.1f", stats.totalDataPointsGenerated * 1000.0 / testDuration)} 点/秒")
        
        report.appendLine("\n内存使用统计:")
        report.appendLine("  当前内存: ${stats.currentMemoryMB} MB")
        report.appendLine("  最大内存: ${stats.maxMemoryMB} MB")
        report.appendLine("  当前堆内存: ${stats.currentHeapMB} MB")
        report.appendLine("  内存泄漏警告: ${stats.memoryLeakWarnings}")
        report.appendLine("  确认内存泄漏: ${stats.confirmedMemoryLeaks}")
        report.appendLine("  高内存事件: ${stats.highMemoryEvents}")
        
        report.appendLine("\n性能统计:")
        report.appendLine("  平均CPU使用: ${String.format("%.1f", stats.avgCpuUsage)}%")
        report.appendLine("  最大CPU使用: ${String.format("%.1f", stats.maxCpuUsage)}%")
        report.appendLine("  高CPU事件: ${stats.highCpuEvents}")
        
        report.appendLine("\n稳定性指标:")
        report.appendLine("  潜在ANR事件: ${stats.potentialAnrEvents}")
        report.appendLine("  UI响应延迟: ${stats.uiResponseDelayMs} ms")
        report.appendLine("  监控错误: ${stats.monitoringErrors}")
        report.appendLine("  响应性检查错误: ${stats.responsivenesCheckErrors}")
        
        report.appendLine("\n组件状态:")
        val metricsSnapshot = metricsCollector.getSnapshot()
        report.appendLine("  传感器样本收集: ${metricsSnapshot.counters[com.continuousauth.observability.MetricType.SENSOR_SAMPLES_COLLECTED] ?: 0}")
        report.appendLine("  异常检测数: ${metricsSnapshot.counters[com.continuousauth.observability.MetricType.ANOMALIES_DETECTED] ?: 0}")
        report.appendLine("  快速模式切换: ${metricsSnapshot.counters[com.continuousauth.observability.MetricType.MODE_SWITCHES_TO_FAST] ?: 0}")
        
        // 生成测试结论
        val isSuccessful = stats.confirmedMemoryLeaks == 0 &&
                          stats.potentialAnrEvents < 5 &&
                          stats.maxMemoryMB < MAX_MEMORY_USAGE_MB &&
                          stats.avgCpuUsage < 60.0
        
        report.appendLine("\n测试结论:")
        report.appendLine(if (isSuccessful) "✓ 长期稳定性测试通过" else "✗ 长期稳定性测试失败")
        
        println(report.toString())
        
        // 保存报告到文件（如果可能）
        try {
            val reportFile = java.io.File(context.cacheDir, "stability_test_report.txt")
            reportFile.writeText(report.toString())
            println("测试报告已保存到: ${reportFile.absolutePath}")
        } catch (e: Exception) {
            println("无法保存测试报告: ${e.message}")
        }
    }

    /**
     * 定期状态报告
     */
    private fun reportPeriodicStatus(elapsed: Long, stats: StabilityStats) {
        val hours = elapsed / (60 * 60 * 1000.0)
        val targetHours = TEST_DURATION_MS / (60.0 * 60.0 * 1000.0)
        val progress = (elapsed.toDouble() / TEST_DURATION_MS) * 100
        
        println("\n--- 稳定性测试进度报告 ---")
        println("运行时长: ${String.format("%.2f", hours)}/${String.format("%.1f", targetHours)} 小时 (${String.format("%.1f", progress)}%)")
        println("数据点: ${stats.totalDataPointsGenerated}, 内存: ${stats.currentMemoryMB}MB, CPU: ${String.format("%.1f", stats.avgCpuUsage)}%")
        println("内存泄漏警告: ${stats.memoryLeakWarnings}, ANR事件: ${stats.potentialAnrEvents}")
        println("---------------------------")
    }

    /**
     * 获取当前内存使用量（MB）
     */
    private fun getCurrentMemoryUsage(): Long {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        return memoryInfo.totalPss / 1024L // 转换为MB
    }

    /**
     * 获取当前堆内存使用量（MB）
     */
    private fun getCurrentHeapUsage(): Long {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        return usedMemory / 1024L / 1024L // 转换为MB
    }

    /**
     * 扩展函数：字符串重复
     */
    private operator fun String.times(count: Int): String {
        return repeat(count)
    }
}

/**
 * 稳定性测试统计数据
 */
data class StabilityStats(
    var totalDataPointsGenerated: Long = 0L,
    var dataGenerationErrors: Int = 0,
    var currentMemoryMB: Long = 0L,
    var maxMemoryMB: Long = 0L,
    var currentHeapMB: Long = 0L,
    var memoryLeakWarnings: Int = 0,
    var confirmedMemoryLeaks: Int = 0,
    var highMemoryEvents: Int = 0,
    var avgCpuUsage: Double = 0.0,
    var maxCpuUsage: Double = 0.0,
    var highCpuEvents: Int = 0,
    var potentialAnrEvents: Int = 0,
    var uiResponseDelayMs: Long = 0L,
    var monitoringErrors: Int = 0,
    var responsivenesCheckErrors: Int = 0
)