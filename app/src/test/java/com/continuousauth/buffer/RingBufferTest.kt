package com.continuousauth.buffer

import android.app.Application
import com.continuousauth.model.SensorSample
import com.continuousauth.model.SensorType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, manifest = Config.NONE)
class RingBufferTest {
    @Test
    fun resizeChangesTheCapacityAndAlignsItToWholeSamples() = runTest {
        val buffer = RingBuffer()
        assertTrue(buffer.resize(96_017))
        assertEquals(96_000, buffer.getStatus().capacity)
        repeat(999) { buffer.write(sample(it)) }
        assertFalse(buffer.isFull())
        buffer.write(sample(999))
        assertTrue(buffer.isFull())
        assertEquals(1_000, buffer.getStatus().sampleCount)
        assertEquals(0L, buffer.getStatus().overflowCount)
    }

    @Test
    fun overflowDropsExactlyTheOldestSampleAndWrapsWithoutCorruption() = runTest {
        val buffer = RingBuffer()
        buffer.resize(96_000)
        repeat(1_005) { assertTrue(buffer.write(sample(it))) }
        val samples = buffer.read(2_000)
        assertEquals((5..1_004).map { it.toLong() }, samples.map { it.seqNo })
        assertEquals(5L, buffer.getStatus().overflowCount)
        assertEquals("com.example.app", samples.last().foregroundApp)
        assertTrue(buffer.isEmpty())
    }

    private fun sample(index: Int) = SensorSample(
        SensorType.ACCELEROMETER, index.toLong(), 1f, 2f, 3f, 3, index.toLong(), "com.example.app"
    )
}
