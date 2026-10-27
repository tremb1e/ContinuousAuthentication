package com.continuousauth.observability

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, manifest = Config.NONE)
class PerformanceMonitorTest {
    @Test
    fun cleanupSynchronouslyStopsReportingRunningState() = runBlocking {
        val context = mockk<Context>()
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns mockk<ActivityManager>(relaxed = true)
        val monitor = PerformanceMonitorImpl(context)
        monitor.startMonitoring(5_000L)
        assertTrue(monitor.getMonitoringStateFlow().value)
        monitor.cleanup()
        assertFalse(monitor.getMonitoringStateFlow().value)
    }

    @Test
    fun sampleWindowKeepsOrderAcrossWrapAndClear() {
        val buffer = RingBuffer<Int>(3)
        (1..5).forEach(buffer::add)
        assertEquals(listOf(3, 4, 5), buffer.getAll())
        assertEquals(listOf(4, 5), buffer.getRecent(2))
        assertTrue(buffer.getRecent(-1).isEmpty())
        buffer.clear()
        assertEquals(0, buffer.size())
        assertTrue(buffer.getAll().isEmpty())
        buffer.add(6)
        assertEquals(listOf(6), buffer.getRecent(3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroCapacityIsRejectedBeforeAnInvalidWrite() {
        RingBuffer<Int>(0)
    }
}
