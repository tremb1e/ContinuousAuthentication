package com.continuousauth.ui.viewmodels

import android.app.Application
import android.os.SystemClock
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import androidx.lifecycle.ViewModelStore
import com.continuousauth.model.SensorSample
import com.continuousauth.model.SensorType
import com.continuousauth.sensor.SensorCollector
import com.continuousauth.sensor.SensorSampleStreams
import com.continuousauth.utils.ForegroundAppDetector
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class SensorDataViewModelTest {
    private lateinit var sensorCollector: SensorCollector
    private lateinit var detector: ForegroundAppDetector
    private lateinit var streams: SensorSampleStreams
    private lateinit var history: MutableStateFlow<List<ForegroundAppDetector.RecentApp>>
    private lateinit var viewModel: SensorDataViewModel
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        sensorCollector = mockk(relaxed = true)
        detector = mockk(relaxed = true)
        streams = SensorSampleStreams()
        history = MutableStateFlow(emptyList())
        every { sensorCollector.getPreviewDataFlow() } returns streams.previewFlow
        every { sensorCollector.getSensorDataFlow() } returns streams.processingFlow
        every { detector.recentApps } returns history
        every { detector.getAppName(any()) } answers { firstArg() }
        viewModel = SensorDataViewModel(sensorCollector, detector)
        viewModelStore.put("sensors", viewModel)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun previewReceivesSamplesWithoutStealingFromProcessingQueue() = runTest {
        val processed = mutableListOf<Long>()
        val processing = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            streams.processingFlow.collect { processed += it.seqNo }
        }
        viewModel.startSensorCollection()
        repeat(20) { streams.publish(sample(it + 1L)) }

        awaitValue(viewModel.accelerometerData) { it.x == 20f }
        assertEquals((1L..20L).toList(), processed)
        assertEquals(20f, viewModel.accelerometerData.value.x, 0f)
        assertTrue(viewModel.sensorsActive.value == true)
        viewModel.stopSensorCollection()
        streams.publish(sample(21))

        assertEquals((1L..21L).toList(), processed)
        assertEquals(20f, viewModel.accelerometerData.value.x, 0f)
        coVerify(exactly = 0) { sensorCollector.startCollection() }
        coVerify(exactly = 0) { sensorCollector.stopCollection() }
        verify(exactly = 0) { sensorCollector.getSensorDataFlow() }
        processing.cancel()
    }

    @Test
    fun reopeningPreviewDoesNotRestartOrStopServiceCollection() = runTest {
        viewModel.startSensorCollection()
        viewModel.startSensorCollection()
        streams.publish(sample(1))
        awaitValue(viewModel.accelerometerData) { it.x == 1f }
        verify(exactly = 1) { sensorCollector.getPreviewDataFlow() }
        viewModel.stopSensorCollection()
        viewModel.startSensorCollection()
        streams.publish(sample(2))
        awaitValue(viewModel.accelerometerData) { it.x == 2f }

        verify(exactly = 2) { sensorCollector.getPreviewDataFlow() }
        coVerify(exactly = 0) { sensorCollector.startCollection() }
        coVerify(exactly = 0) { sensorCollector.stopCollection() }
    }

    @Test
    fun appsObservedInBackgroundReplayWhenSensorScreenReturns() = runTest {
        history.value = listOf(ForegroundAppDetector.RecentApp("collector.app", 100))
        viewModel.startSensorCollection()
        viewModel.stopSensorCollection()
        history.value = history.value + ForegroundAppDetector.RecentApp("third.party", 200)

        viewModel.startSensorCollection()
        awaitValue(viewModel.recentApps) { it.size == 2 }
        assertEquals(listOf("collector.app", "third.party"), viewModel.recentApps.value.map { it.packageName })
        assertEquals(200L, viewModel.recentApps.value.last().timestamp)
        coVerify(exactly = 0) { detector.getCurrentForegroundApp() }
    }

    @Test
    fun recreatedViewModelReplaysServiceHistoryWithoutPolling() = runTest {
        viewModelStore.clear()
        history.value = listOf(ForegroundAppDetector.RecentApp("third.party", 200))
        viewModel = SensorDataViewModel(sensorCollector, detector)
        viewModelStore.put("replacement", viewModel)
        viewModel.startSensorCollection()
        awaitValue(viewModel.recentApps) { it.size == 1 }

        assertEquals("third.party", viewModel.recentApps.value.single().packageName)
        coVerify(exactly = 0) { detector.getCurrentForegroundApp() }
        coVerify(exactly = 0) { sensorCollector.stopCollection() }
    }

    @Test
    fun clearingViewModelStopsOnlyItsObservers() = runTest {
        viewModel.startSensorCollection()
        streams.publish(sample(1))
        awaitValue(viewModel.accelerometerData) { it.x == 1f }
        viewModelStore.clear()
        streams.publish(sample(2))
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(viewModel.sensorsActive.value == true)
        assertEquals(1f, viewModel.accelerometerData.value.x, 0f)
        coVerify(exactly = 0) { sensorCollector.stopCollection() }
    }

    @Test
    fun displayedTimestampUsesMeasurementTime() = runTest {
        viewModel.startSensorCollection()
        val now = System.currentTimeMillis()
        streams.publish(sample(1).copy(eventTimestampNs = SystemClock.elapsedRealtimeNanos() - 750_000_000))
        awaitValue(viewModel.accelerometerData) { it.x == 1f }

        assertTrue(viewModel.accelerometerData.value.timestamp in (now - 850)..(now - 650))
    }

    @Test
    fun noSampleIsShownAsWaitingRatherThanAFabricatedCurrentReading() {
        assertEquals(0L, viewModel.accelerometerData.value.timestamp)
        assertEquals(0L, viewModel.chartAccelerometerData.value.timestamp)
    }

    @Test
    fun stoppingPreviewKeepsTheFullProcessingQueueAvailableForAllSensorTypes() = runTest {
        val types = mutableListOf<SensorType>()
        val processing = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            streams.processingFlow.collect { types += it.type }
        }
        viewModel.startSensorCollection()
        viewModel.resetChartData()
        SensorType.entries.forEachIndexed { index, type -> streams.publish(sample(index + 1L).copy(type = type)) }
        awaitValue(viewModel.magnetometerData) { it.x == 3f }
        awaitValue(viewModel.chartGyroscopeData) { it.x == 2f }
        viewModel.stopSensorCollection()
        SensorType.entries.forEachIndexed { index, type -> streams.publish(sample(index + 4L).copy(type = type)) }
        assertEquals(SensorType.entries + SensorType.entries, types)
        assertEquals(3f, viewModel.magnetometerData.value.x, 0f)
        processing.cancel()
    }

    private suspend fun <T> awaitValue(flow: StateFlow<T>, predicate: (T) -> Boolean) {
        // Preview and PackageManager work deliberately run off the UI thread. Await the
        // observable result instead of relying on an unconfined Main dispatcher completing it.
        withContext(Dispatchers.Default) { withTimeout(5_000) { flow.first(predicate) } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun sample(seqNo: Long) = SensorSample(
        SensorType.ACCELEROMETER, SystemClock.elapsedRealtimeNanos(),
        seqNo.toFloat(), 2f, 3f, 3, seqNo, "third.party"
    )
}
