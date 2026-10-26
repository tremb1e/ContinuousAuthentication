package com.continuousauth.sensor

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.SystemClock
import com.continuousauth.buffer.RingBuffer
import com.continuousauth.pool.SensorEventPool
import com.continuousauth.utils.ForegroundAppDetector
import com.continuousauth.settings.AppSettingsRepository
import com.continuousauth.settings.SensorCollectionSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, manifest = Config.NONE)
class SensorCollectorCaptureTest {
    @Test
    fun fifoCapableSensorsAreRegisteredForImmediateDelivery() = runBlocking {
        val detector: ForegroundAppDetector = mockk(relaxed = true)
        val (collector, sensor, sensorManager) = createCollector(detector)
        try {
            collector.startCollection()

            verify(exactly = 1) {
                sensorManager.registerListener(collector, sensor, 10_000, 0, any())
            }
        } finally {
            collector.finishCollection()
        }
    }

    @Test
    fun configuredRateIsNotReportedAsMeasuredBeforeAnySampleArrives() = runBlocking {
        val (collector) = createCollector(mockk(relaxed = true))
        try {
            collector.startCollection()

            val info = collector.getSensorInfo()
            assertEquals(100f, info.accelerometerCurrentRate, 0f)
            assertEquals(0f, info.accelerometerActualRate, 0f)
            assertEquals(0f, info.gyroscopeActualRate, 0f)
            assertEquals(0f, info.magnetometerActualRate, 0f)
        } finally {
            collector.finishCollection()
        }
    }

    @Test
    fun interruptedCallbacksExpireMeasuredRateAndRecoveryBuildsAFreshWindow() = runBlocking {
        val (collector, sensor) = createCollector(mockk(relaxed = true))
        val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(3).apply { this.sensor = sensor }
        fun emit() {
            event.timestamp = SystemClock.elapsedRealtimeNanos()
            collector.onSensorChanged(event)
        }
        try {
            collector.startCollection()
            emit()
            repeat(100) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(10))
                emit()
            }
            assertEquals(100f, collector.getSensorInfo().accelerometerActualRate, 0.001f)

            ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
            assertEquals(0f, collector.getSensorInfo().accelerometerActualRate, 0f)

            emit()
            assertEquals(0f, collector.getSensorInfo().accelerometerActualRate, 0f)
            repeat(100) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(10))
                emit()
            }
            assertEquals(100f, collector.getSensorInfo().accelerometerActualRate, 0.001f)
        } finally {
            collector.finishCollection()
        }
        assertEquals(0f, collector.getSensorInfo().accelerometerActualRate, 0f)
    }

    @Test
    fun recycledAndroidEventCannotChangeQueuedSampleAndStopDrainsAttribution() = runBlocking {
        val attributionEntered = CompletableDeferred<Unit>()
        val allowAttribution = CompletableDeferred<Unit>()
        val detector: ForegroundAppDetector = mockk()
        coEvery { detector.getForegroundAppAt(any()) } coAnswers {
            attributionEntered.complete(Unit)
            allowAttribution.await()
            "com.example.thirdparty"
        }
        val (collector, sensor) = createCollector(detector)
        val samples = collector.getSensorDataFlow()
        val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(3)
        event.sensor = sensor
        event.timestamp = 100_000_000L
        event.accuracy = 3
        event.values[0] = 1f
        event.values[1] = 2f
        event.values[2] = 3f

        withTimeout(10_000) {
            collector.startCollection()
            collector.onSensorChanged(event)
            attributionEntered.await()
            event.timestamp = 200_000_000L
            event.accuracy = 0
            event.values.fill(9f)
            allowAttribution.complete(Unit)
            collector.finishCollection()
            val sample = samples.toList().single()
            assertEquals(100_000_000L, sample.eventTimestampNs)
            assertEquals(1f, sample.x, 0f)
            assertEquals(2f, sample.y, 0f)
            assertEquals(3f, sample.z, 0f)
            assertEquals(3, sample.accuracy)
            assertEquals("com.example.thirdparty", sample.foregroundApp)
            coVerify(exactly = 1) { detector.getForegroundAppAt(100_000_000L) }
        }
    }

    @Test
    fun cancelledPauseStillDrainsBeforeFinalShutdownAndCollectorCanRestart() = runBlocking {
        val attributionEntered = CompletableDeferred<Unit>()
        val allowAttribution = CompletableDeferred<Unit>()
        val detector: ForegroundAppDetector = mockk()
        coEvery { detector.getForegroundAppAt(any()) } coAnswers {
            attributionEntered.complete(Unit)
            allowAttribution.await()
            "com.example.thirdparty"
        }
        val (collector, sensor) = createCollector(detector)
        val samples = collector.getSensorDataFlow()
        val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(3).apply {
                this.sensor = sensor
                timestamp = 100_000_000L
                values.fill(1f)
            }
        withTimeout(10_000) {
            collector.startCollection()
            collector.onSensorChanged(event)
            attributionEntered.await()
            val pause = launch { collector.stopCollection() }
            yield()
            assertFalse(collector.isCollecting())
            pause.cancelAndJoin()

            val finish = async { collector.finishCollection() }
            yield()
            assertFalse("Shutdown must wait for accepted events even after a cancelled pause", finish.isCompleted)
            allowAttribution.complete(Unit)
            finish.await()
            assertEquals(1, samples.toList().size)

            val nextSession = collector.getSensorDataFlow()
            collector.startCollection()
            event.timestamp = 200_000_000L
            collector.onSensorChanged(event)
            collector.finishCollection()
            assertEquals(200_000_000L, nextSession.toList().single().eventTimestampNs)
        }
    }

    @Test
    fun disabledSensorRejectsCallbacksImmediatelyEvenBeforeListenerIsUnregistered() = runBlocking {
        val choices = MutableStateFlow(SensorCollectionSettings())
        val detector: ForegroundAppDetector = mockk(relaxed = true)
        val (collector, sensor) = createCollector(detector, choices)
        val samples = collector.getSensorDataFlow()
        val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(3).apply {
                this.sensor = sensor
                timestamp = 100_000_000L
            }
        collector.startCollection()
        choices.value = choices.value.copy(accelerometerEnabled = false)
        collector.onSensorChanged(event)
        collector.finishCollection()
        assertEquals(emptyList<com.continuousauth.model.SensorSample>(), samples.toList())
        coVerify(exactly = 0) { detector.getForegroundAppAt(any()) }
        assertEquals(0f, collector.getSensorInfo().accelerometerActualRate, 0f)
    }

    private fun createCollector(
        detector: ForegroundAppDetector,
        choices: MutableStateFlow<SensorCollectionSettings> = MutableStateFlow(SensorCollectionSettings())
    ): Triple<SensorCollectorImpl, Sensor, SensorManager> {
        val sensor: Sensor = mockk(relaxed = true)
        every { sensor.type } returns Sensor.TYPE_ACCELEROMETER
        every { sensor.minDelay } returns 10_000
        every { sensor.fifoMaxEventCount } returns 512
        val sensorManager: SensorManager = mockk(relaxed = true)
        every { sensorManager.getDefaultSensor(any()) } returns null
        every { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) } returns sensor
        every { sensorManager.registerListener(any(), any(), any(), any(), any()) } returns true
        val context: Context = mockk()
        every { context.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager
        val settings: AppSettingsRepository = mockk()
        every { settings.sensorSettings } returns choices
        return Triple(
            SensorCollectorImpl(context, detector, mockk<RingBuffer>(relaxed = true), SensorEventPool(), settings),
            sensor,
            sensorManager
        )
    }
}
