package com.continuousauth.core

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.PowerManager
import com.continuousauth.buffer.InMemoryBuffer
import com.continuousauth.core.SmartTransmissionManager.CollectionStatus
import com.continuousauth.network.UploadManager
import com.continuousauth.privacy.ConsentState
import com.continuousauth.privacy.PrivacyManager
import com.continuousauth.processing.SensorDataProcessor
import com.continuousauth.processing.ProcessingStatus
import com.continuousauth.proto.DataPacket
import com.continuousauth.sensor.SensorCollector
import com.continuousauth.storage.FileQueueManager
import com.continuousauth.time.EnhancedTimeSync
import com.continuousauth.utils.SessionManager
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, manifest = Config.NONE)
class SmartTransmissionManagerTest {
    private val privacy: PrivacyManager = mockk(relaxed = true)
    private val sensor: SensorCollector = mockk(relaxed = true)
    private val processor: SensorDataProcessor = mockk(relaxed = true)
    private val upload: UploadManager = mockk(relaxed = true)
    private val timeSync: EnhancedTimeSync = mockk(relaxed = true)
    private val sessions: SessionManager = mockk(relaxed = true)
    private val buffer: InMemoryBuffer = mockk(relaxed = true)
    private lateinit var preferences: SharedPreferences
    private lateinit var manager: SmartTransmissionManager

    @Before
    fun setUp() {
        val context: Context = mockk()
        preferences = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { preferences.getString(any(), any()) } returns null
        every { privacy.consentState } returns MutableStateFlow(ConsentState.GRANTED)
        every { context.getSystemService(Context.BATTERY_SERVICE) } returns mockk<BatteryManager>(relaxed = true)
        every { context.getSystemService(Context.KEYGUARD_SERVICE) } returns mockk<KeyguardManager>(relaxed = true)
        every { context.getSystemService(Context.POWER_SERVICE) } returns mockk<PowerManager>(relaxed = true)
        every { sessions.getCurrentSessionId() } returns "session-1"
        every { sensor.getSensorDataFlow() } returns emptyFlow()
        every { processor.getEncryptedPacketFlow() } returns emptyFlow()
        every { processor.getProcessingStatus() } returns ProcessingStatus(true, 0, "session-1")
        coEvery { processor.startProcessing(any(), any()) } returns true
        coEvery { upload.start(any()) } returns true
        manager = SmartTransmissionManager(
            context, sensor, timeSync, privacy, processor, upload,
            buffer, mockk<FileQueueManager>(relaxed = true), sessions
        )
    }

    @After
    fun tearDown() = runBlocking { manager.stop() }

    @Test
    fun expiredDataIsCleanedBeforeCollectionAndUploadResume() = runBlocking {
        every { privacy.shouldPerformRetentionCleanup() } returns true
        manager.start()
        coVerifyOrder {
            privacy.performRetentionCleanup()
            upload.start(any())
            sensor.startCollection()
        }
    }

    @Test
    fun sessionKeystoreAndSensorStartupRunOffTheCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        every { sessions.getCurrentSessionId() } answers {
            assertTrue("Session preferences must not block the service thread", Thread.currentThread() !== caller)
            "session-1"
        }
        coEvery { sensor.startCollection() } coAnswers {
            assertTrue("Sensor registration must not block the service thread", Thread.currentThread() !== caller)
        }
        manager.start()
        assertEquals(CollectionStatus.RUNNING, manager.collectionState.value.status)
    }

    @Test
    fun offlineUploadManagerCanStartCollectionAndStopDrainsBeforeEndingSession() = runBlocking {
        manager.start()
        assertEquals(CollectionStatus.RUNNING, manager.collectionState.value.status)
        assertTrue(manager.isRunningNow())
        coVerify(exactly = 1) { sensor.startCollection() }

        manager.stop()
        assertEquals(CollectionStatus.STOPPED, manager.collectionState.value.status)
        assertFalse(manager.isRunningNow())
        coVerifyOrder {
            sensor.finishCollection()
            processor.stopProcessing(graceful = true)
            upload.stop()
            sessions.endSession()
        }
    }

    @Test
    fun failedStartupCleansEveryStartedComponentAndReportsError() = runBlocking {
        coEvery { processor.startProcessing(any(), any()) } returns false
        try {
            manager.start()
            fail("Processor startup failure must propagate")
        } catch (_: IllegalStateException) {
        }
        assertEquals(CollectionStatus.ERROR, manager.collectionState.value.status)
        assertFalse(manager.isRunningNow())
        coVerify { sensor.finishCollection() }
        coVerify { upload.stop() }
        verify { timeSync.stopPeriodicSync() }
        verify { sessions.endSession() }
    }

    @Test
    fun stoppingBlocksUploadsWhileAcceptedSensorSamplesAreStillDraining() = runBlocking {
        val drainEntered = CompletableDeferred<Unit>()
        val finishDrain = CompletableDeferred<Unit>()
        coEvery { sensor.finishCollection() } coAnswers {
            drainEntered.complete(Unit)
            finishDrain.await()
        }
        manager.start()

        val stop = async { manager.stop() }
        try {
            withTimeout(10_000) { drainEntered.await() }
            assertEquals(CollectionStatus.STOPPING, manager.collectionState.value.status)
            verify(exactly = 1) { upload.pauseUpload("collection_stopping") }
            coVerify(exactly = 0) { upload.stop() }
        } finally {
            finishDrain.complete(Unit)
            withTimeout(10_000) { stop.await() }
        }
        assertEquals(CollectionStatus.STOPPED, manager.collectionState.value.status)
        coVerify(exactly = 1) { upload.stop() }
    }

    @Test
    fun concurrentStopWaitsForStartupAndLeavesNoRunningSensors() = runBlocking {
        val startupEntered = CompletableDeferred<Unit>()
        val finishStartup = CompletableDeferred<Unit>()
        coEvery { upload.start(any()) } coAnswers {
            startupEntered.complete(Unit)
            finishStartup.await()
            true
        }
        val start = async { manager.start() }
        startupEntered.await()
        val stop = async { manager.stop() }
        yield()
        finishStartup.complete(Unit)
        start.await()
        stop.await()
        assertEquals(CollectionStatus.STOPPED, manager.collectionState.value.status)
        coVerifyOrder { sensor.startCollection(); sensor.finishCollection() }
    }

    @Test
    fun unexpectedServiceDestructionPreservesSessionForRestart() = runBlocking {
        manager.start()
        manager.stop(endSession = false)
        verify(exactly = 0) { sessions.endSession() }
        manager.start()
        assertEquals(CollectionStatus.RUNNING, manager.collectionState.value.status)
        coVerify(exactly = 2) { sensor.startCollection() }
    }

    @Test
    fun screenPauseAllowsSmartNightUploadButManualPauseStillBlocksIt() = runBlocking {
        manager.start()
        manager.setSmartTransmissionEnabled(true)
        clearMocks(upload, answers = false)
        manager.pause(allowBufferedUpload = true)
        assertEquals(CollectionStatus.PAUSED, manager.collectionState.value.status)
        verify { upload.resumeUpload("collection_paused") }
        coVerify(exactly = 1) { sensor.stopCollection() }

        manager.pause(allowBufferedUpload = false)
        verify { upload.pauseUpload("collection_paused") }
        manager.resume()
        assertEquals(CollectionStatus.RUNNING, manager.collectionState.value.status)
        coVerify(exactly = 2) { sensor.startCollection() }
    }

    @Test
    fun snapshotUsesAcknowledgementTime() {
        every { upload.getLastAckTimestamp() } returns 123456L
        assertEquals(123456L, manager.getUploadSnapshot().lastUploadTimestamp)
    }

    @Test
    fun persistedWifiPolicyIsRestoredBeforeUploadStartsWithoutAnActivity() = runBlocking {
        every { preferences.getBoolean("wifi_only", false) } returns true
        manager.start()
        coVerifyOrder {
            upload.setWifiOnlyMode(true)
            upload.start(any())
        }
    }

    @Test
    fun terminatedProcessorRestartsInSameSessionAndContinuesForwardingPackets() = runBlocking {
        val output = Channel<DataPacket>(Channel.UNLIMITED)
        val uploaded = Channel<DataPacket>(Channel.UNLIMITED)
        var active = true
        var starts = 0
        every { processor.getEncryptedPacketFlow() } returns output.receiveAsFlow()
        every { processor.getProcessingStatus() } answers { ProcessingStatus(active, 0, "session-1") }
        coEvery { processor.startProcessing(any(), "session-1") } coAnswers {
            active = true
            starts++
            output.send(DataPacket.newBuilder().setPacketId("packet-$starts").build())
            true
        }
        coEvery { buffer.enqueue(any()) } coAnswers {
            uploaded.send(firstArg())
            true
        }
        manager.start()
        assertEquals("packet-1", withTimeout(10_000) { uploaded.receive() }.packetId)

        active = false
        manager.ensureProcessingActive()

        assertEquals("packet-2", withTimeout(10_000) { uploaded.receive() }.packetId)
        assertEquals(CollectionStatus.RUNNING, manager.collectionState.value.status)
        coVerify(exactly = 2) { processor.startProcessing(any(), "session-1") }
        coVerify(exactly = 1) { sensor.startCollection() }
        verify(exactly = 0) { sessions.endSession() }
    }

    @Test
    fun processorRecoveryCannotRestartPausedOrStoppedCollection() = runBlocking {
        manager.start()
        every { processor.getProcessingStatus() } returns ProcessingStatus(false, 0, "session-1")
        manager.pause()

        manager.ensureProcessingActive()
        assertEquals(CollectionStatus.PAUSED, manager.collectionState.value.status)
        manager.stop()
        manager.ensureProcessingActive()

        assertEquals(CollectionStatus.STOPPED, manager.collectionState.value.status)
        coVerify(exactly = 1) { processor.startProcessing(any(), any()) }
    }

    @Test
    fun failedProcessorRecoveryRemainsActiveAndCanRetry() = runBlocking {
        manager.start()
        every { processor.getProcessingStatus() } returns ProcessingStatus(false, 0, "session-1")
        coEvery { processor.startProcessing(any(), any()) } returns false

        manager.ensureProcessingActive()
        assertEquals(CollectionStatus.RUNNING, manager.collectionState.value.status)
        assertTrue(manager.isRunningNow())
        assertFalse(manager.collectionState.value.errorMessage.isNullOrBlank())

        coEvery { processor.startProcessing(any(), any()) } returns true
        manager.ensureProcessingActive()
        assertEquals(CollectionStatus.RUNNING, manager.collectionState.value.status)
        assertTrue(manager.collectionState.value.errorMessage.isNullOrBlank())
        coVerify(exactly = 1) { sensor.startCollection() }
    }

    @Test
    fun endingAuthenticationFlushesOldSessionAndContinuesCollectionWithANewSession() = runBlocking {
        manager.start()
        every { sessions.startNewSession() } returns "session-2"
        clearMocks(sensor, processor, upload, answers = false)

        manager.rotateSession()

        assertEquals(CollectionStatus.RUNNING, manager.collectionState.value.status)
        coVerifyOrder {
            sensor.finishCollection()
            processor.stopProcessing(graceful = true)
            sessions.startNewSession()
            processor.startProcessing(any(), "session-2")
            sensor.startCollection()
        }
        coVerify(exactly = 0) { upload.stop() }
        coVerify(exactly = 0) { upload.startAuthentication(any(), any()) }
    }

    @Test
    fun endingAuthenticationWhileLockedDoesNotRestartSensorsOrLosePauseState() = runBlocking {
        manager.start()
        manager.pause(allowBufferedUpload = false)
        every { sessions.startNewSession() } returns "session-2"
        clearMocks(sensor, answers = false)

        manager.rotateSession()

        assertEquals(CollectionStatus.PAUSED, manager.collectionState.value.status)
        coVerify(exactly = 0) { sensor.startCollection() }
        manager.resume()
        coVerify(exactly = 1) { sensor.startCollection() }
    }

    @Test
    fun endingAuthenticationWhileStoppedCannotStartCollection() = runBlocking {
        manager.rotateSession()
        assertEquals(CollectionStatus.STOPPED, manager.collectionState.value.status)
        coVerify(exactly = 0) { sensor.startCollection() }
        coVerify(exactly = 0) { upload.start(any()) }
        verify(exactly = 1) { sessions.endSession() }
    }
}
