package com.continuousauth.detection

import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AnomalyDetectorLifecycleRegressionTest {
    private val usage: UsageStatsManager = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true) {
        every { getSystemService(Context.USAGE_STATS_SERVICE) } returns usage
    }

    @Test
    fun cleanupUnregistersImmediatelyEvenWithQueuedDispatcher() = runTest {
        val receiver = slot<BroadcastReceiver>()
        every { context.registerReceiver(capture(receiver), any()) } returns null
        val detector = AnomalyDetectorImpl(context)
        detector.overrideDispatcherForTests(StandardTestDispatcher(testScheduler))
        val listener = mockk<OnAnomalyListener>(relaxed = true)
        detector.setOnAnomalyListener(listener)
        detector.startDetection()
        detector.cleanup()
        assertFalse(detector.isDetecting())
        verify(exactly = 1) { context.unregisterReceiver(receiver.captured) }
        receiver.captured.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))
        verify(exactly = 0) { listener.onAnomalyDetected(any()) }
    }

    @Test
    fun disabledPolicySuppressesLateUnlockAndStopsForegroundPolling() = runTest {
        val receiver = slot<BroadcastReceiver>()
        every { context.registerReceiver(capture(receiver), any()) } returns null
        val detector = AnomalyDetectorImpl(context)
        detector.overrideDispatcherForTests(StandardTestDispatcher(testScheduler))
        val listener = mockk<OnAnomalyListener>(relaxed = true)
        detector.setOnAnomalyListener(listener)
        try {
            every { usage.queryEvents(any(), any()) } throws SecurityException("No usage access")
            detector.updatePolicy(DetectionPolicy(sensitiveApps = setOf("test.app")))
            detector.startDetection()
            runCurrent()
            verify(exactly = 1) { usage.queryEvents(any(), any()) }
            detector.updatePolicy(DetectionPolicy(enabled = false, sensitiveApps = setOf("test.app")))
            receiver.captured.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))
            advanceTimeBy(5_000)
            runCurrent()
            verify(exactly = 1) { context.unregisterReceiver(receiver.captured) }
            verify(exactly = 0) { listener.onAnomalyDetected(any()) }
            verify(exactly = 1) { usage.queryEvents(any(), any()) }
        } finally { detector.cleanup() }
    }

    @Test
    fun addingSensitiveAppsAfterStartActuallyStartsPolling() = runTest {
        val detector = AnomalyDetectorImpl(context)
        detector.overrideDispatcherForTests(StandardTestDispatcher(testScheduler))
        every { usage.queryEvents(any(), any()) } throws SecurityException("No usage access")
        try {
            detector.startDetection()
            runCurrent()
            verify(exactly = 0) { usage.queryEvents(any(), any()) }
            detector.updatePolicy(DetectionPolicy(sensitiveApps = setOf("test.app")))
            runCurrent()
            verify(exactly = 1) { usage.queryEvents(any(), any()) }
        } finally { detector.cleanup() }
    }

    @Test
    fun restartingRequiresAFreshBaselineAndInvalidSamplesDoNotPoisonIt() = runTest {
        val detector = AnomalyDetectorImpl(context)
        detector.overrideDispatcherForTests(StandardTestDispatcher(testScheduler))
        val listener = mockk<OnAnomalyListener>(relaxed = true)
        detector.setOnAnomalyListener(listener)
        try {
            detector.startDetection()
            repeat(60) { detector.processSensorData(0f, 0f, 9.8f, it * 10_000_000L) }
            detector.stopDetection()
            detector.startDetection()
            detector.processSensorData(20f, 20f, 20f, 1_000_000_000L)
            verify(exactly = 0) { listener.onAnomalyDetected(any()) }
            repeat(100) { detector.processSensorData(0f, 0f, 9.8f, 3_000_000_000L + it * 10_000_000L) }
            detector.processSensorData(Float.NaN, 0f, 0f, 4_500_000_000L)
            detector.processSensorData(Float.POSITIVE_INFINITY, 0f, 0f, 4_600_000_000L)
            detector.processSensorData(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, 4_700_000_000L)
            detector.processSensorData(20f, 20f, 20f, 5_000_000_000L)
            verify(exactly = 1) { listener.onAnomalyDetected(match {
                it is AnomalyTrigger.AccelerometerSpike && it.magnitude.isFinite() && it.threshold.isFinite()
            }) }
        } finally { detector.cleanup() }
    }
}
