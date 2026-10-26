package com.continuousauth.sensor

import com.continuousauth.model.SensorSample
import com.continuousauth.model.SensorType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SensorSampleStreamsTest {
    @Test
    fun previewObserversCannotStealUploadSamples() = runTest {
        val streams = SensorSampleStreams()
        val uploaded = mutableListOf<SensorSample>()
        val firstPreview = mutableListOf<SensorSample>()
        val secondPreview = mutableListOf<SensorSample>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) { streams.processingFlow.toList(uploaded) }
        backgroundScope.launch(dispatcher) { streams.previewFlow.toList(firstPreview) }
        backgroundScope.launch(dispatcher) { streams.previewFlow.toList(secondPreview) }

        val expected = (1L..30L).map(::sample)
        expected.forEach(streams::publish)

        assertEquals(expected, uploaded)
        assertEquals(expected, firstPreview)
        assertEquals(expected, secondPreview)
    }

    @Test
    fun slowPreviewDoesNotBackPressureOrDropProcessingSamples() = runTest {
        val streams = SensorSampleStreams()
        val processingFlow = streams.processingFlow
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            streams.previewFlow.collect { delay(10_000) }
        }

        val expected = (1L..3_000L).map(::sample)
        expected.forEach(streams::publish)
        streams.finishProcessing()

        assertEquals(expected, processingFlow.toList())
    }

    @Test
    fun closingPreviewLeavesProcessingActive() = runTest {
        val streams = SensorSampleStreams()
        val uploaded = mutableListOf<SensorSample>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            streams.processingFlow.toList(uploaded)
        }
        val preview = backgroundScope.launch {
            streams.previewFlow.collect {}
        }
        runCurrent()
        streams.publish(sample(1))
        preview.cancel()
        streams.publish(sample(2))
        runCurrent()

        assertEquals(listOf(sample(1), sample(2)), uploaded)
    }

    @Test
    fun finishDrainsQueuedSamplesAndNextSessionGetsNewFlow() = runTest {
        val streams = SensorSampleStreams()
        val firstSession = streams.processingFlow
        streams.publish(sample(1))
        streams.publish(sample(2))
        streams.finishProcessing()
        assertEquals(listOf(sample(1), sample(2)), firstSession.toList())

        val nextSession = streams.processingFlow
        streams.publish(sample(3))
        streams.finishProcessing()
        assertEquals(listOf(sample(3)), nextSession.toList())
    }

    private fun sample(sequence: Long) = SensorSample(
        type = SensorType.ACCELEROMETER,
        eventTimestampNs = sequence * 10_000_000L,
        x = sequence.toFloat(),
        y = 2f,
        z = 3f,
        accuracy = 3,
        seqNo = sequence,
        foregroundApp = "com.example.thirdparty"
    )
}
