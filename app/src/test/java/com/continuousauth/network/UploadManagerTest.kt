package com.continuousauth.network

import android.app.Application
import com.continuousauth.buffer.InMemoryBuffer
import com.continuousauth.database.BatchMetadata
import com.continuousauth.database.BatchStatus
import com.continuousauth.database.UploadProgress
import com.continuousauth.policy.PolicyManager
import com.continuousauth.proto.Ack
import com.continuousauth.proto.DataPacket
import com.continuousauth.proto.ServerDirective
import com.continuousauth.storage.FileQueueManager
import com.continuousauth.storage.QueueStats
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class UploadManagerTest {
    @Test
    fun offlineStartupKeepsQueueAndUploadsAfterOfflineModeIsDisabled() = runTest {
        withManager {
            addPacket("offline-packet")
            manager.setOfflineMode(true)
            assertTrue(manager.start(ENDPOINT))
            advanceTimeBy(5_000)
            runCurrent()

            assertTrue(manager.getUploadStatus().isRunning)
            assertEquals(0, connectAttempts)
            assertTrue(sent.isEmpty())
            assertEquals(1, memory.size)
            assertTrue(disk.containsKey("offline-packet"))

            manager.setOfflineMode(false)
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(1, connectAttempts)
            assertTrue(sent.contains("offline-packet"))
        }
    }

    @Test
    fun unavailableServerDoesNotStopStartupOrRetriesAfterThreeFailures() = runTest {
        withManager {
            failuresBeforeConnect = 5
            addPacket("queued-offline")
            assertTrue(manager.start(ENDPOINT))
            runCurrent()

            assertEquals(1, connectAttempts)
            assertTrue(manager.getUploadStatus().isRunning)
            assertTrue(sent.isEmpty())
            advanceTimeBy(49_999)
            runCurrent()
            assertEquals(5, connectAttempts)
            assertTrue(sent.isEmpty())

            advanceTimeBy(5_001)
            runCurrent()

            assertEquals(6, connectAttempts)
            assertTrue(connected)
            assertEquals(listOf("queued-offline"), sent)
        }
    }

    @Test
    fun wifiOnlyRecoversAndStopsSendingAsTransportChangesWithoutUi() = runTest {
        withManager {
            wifi = false
            manager.setWifiOnlyMode(true)
            addPacket("waiting-for-wifi")
            manager.start(ENDPOINT)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(0, connectAttempts)
            assertTrue(sent.isEmpty())

            wifi = true
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(1, connectAttempts)
            assertEquals(listOf("waiting-for-wifi"), sent)

            wifi = false
            addPacket("second-wifi-packet")
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(listOf("waiting-for-wifi"), sent)
            assertEquals(1, memory.size)

            wifi = true
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf("waiting-for-wifi", "second-wifi-packet"), sent)
        }
    }

    @Test
    fun diskBacklogDrainsWhileLivePacketsKeepArrivingAndCopiesAreDeduplicated() = runTest {
        withManager {
            connected = true
            val oldIds = (1..120).map { "old-$it" }
            oldIds.forEach { addPacket(it, inMemory = false) }
            val liveIds = (1..150).map { "live-$it" }
            liveIds.take(50).forEach { addPacket(it) }
            manager.start(ENDPOINT)
            runCurrent()
            assertEquals(50, sent.count { it.startsWith("old-") })

            liveIds.drop(50).chunked(50).forEach { liveBatch ->
                liveBatch.forEach { addPacket(it) }
                advanceTimeBy(1_000)
                runCurrent()
            }

            assertEquals((oldIds + liveIds).toSet(), sent.toSet())
            assertEquals(270, sent.size)
            assertEquals(270, sent.distinct().size)
        }
    }

    @Test
    fun allPauseReasonsMustBeClearedBeforeQueuedPacketsResume() = runTest {
        withManager {
            connected = true
            manager.start(ENDPOINT)
            runCurrent()
            manager.pauseUpload("screen-lock")
            manager.pauseUpload("manual")
            addPacket("paused-packet")
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(sent.isEmpty())

            manager.resumeUpload("screen-lock")
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(sent.isEmpty())
            assertTrue(manager.getUploadStatus().isPaused)
            assertEquals(1, memory.size)

            manager.resumeUpload("manual")
            runCurrent()

            assertFalse(manager.getUploadStatus().isPaused)
            assertEquals(listOf("paused-packet"), sent)
        }
    }

    @Test
    fun smartTransmissionWindowGatesQueueDirectSendAndRetry() = runTest {
        withManager {
            connected = true
            manager.setSmartTransmissionEnabled(true)
            manager.setSmartTransmissionUploadAllowed(false)
            val packet = addPacket("outside-window")
            manager.start(ENDPOINT)
            advanceTimeBy(5_000)
            runCurrent()

            assertTrue(sent.isEmpty())
            assertEquals(1, memory.size)
            assertFalse(manager.sendDataPacket(packet))
            assertFalse(manager.retryPacket(packet).success)
            coVerify(exactly = 0) { uploader.sendDataPacket(any()) }

            manager.setSmartTransmissionUploadAllowed(true)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(listOf("outside-window"), sent)
        }
    }

    @Test
    fun directSendAndRetryRespectStoppedPausedOfflineAndWifiGates() = runTest {
        withManager {
            connected = true
            val packet = packet("manual-packet")
            assertFalse(manager.sendDataPacket(packet))
            assertFalse(manager.retryPacket(packet).success)
            manager.start(ENDPOINT)
            runCurrent()

            manager.pauseUpload()
            assertFalse(manager.sendDataPacket(packet))
            assertFalse(manager.retryPacket(packet).success)
            manager.resumeUpload()

            manager.setOfflineMode(true)
            assertFalse(manager.sendDataPacket(packet))
            assertFalse(manager.retryPacket(packet).success)
            manager.setOfflineMode(false)

            manager.setWifiOnlyMode(true)
            wifi = false
            assertFalse(manager.sendDataPacket(packet))
            assertFalse(manager.retryPacket(packet).success)
            coVerify(exactly = 0) { uploader.sendDataPacket(any()) }

            wifi = true
            assertTrue(manager.sendDataPacket(packet))
            assertEquals(listOf("manual-packet"), sent)
        }
    }

    @Test
    fun unacknowledgedDiskPacketIsRetriedAndDeletedOnlyAfterAck() = runTest {
        withManager {
            connected = true
            addPacket("awaiting-ack")
            manager.start(ENDPOINT)
            runCurrent()
            assertEquals(listOf("awaiting-ack"), sent)
            assertEquals(0L, manager.getUploadStatus().uploadedPackets)
            assertTrue(disk.containsKey("awaiting-ack"))

            advanceTimeBy(9_999)
            runCurrent()
            assertEquals(1, sent.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("awaiting-ack", "awaiting-ack"), sent)
            assertEquals(0L, manager.getUploadStatus().uploadedPackets)

            directives.send(ServerDirective.newBuilder().setAck(
                Ack.newBuilder().setPacketId("awaiting-ack").setSuccess(true).setCreationServerTs(123)
            ).build())
            runCurrent()
            assertFalse(disk.containsKey("awaiting-ack"))
            assertEquals(1L, manager.getUploadStatus().uploadedPackets)
            assertTrue(manager.getLastAckTimestamp() > 0L)
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(2, sent.size)
            coVerifyOrder {
                files.updateAckStatus("awaiting-ack", 123)
                files.deleteDataPacket("awaiting-ack")
            }
        }
    }

    @Test
    fun duplicateAcknowledgementCountsOnceAndFailedAcknowledgementDoesNotCount() = runTest {
        withManager {
            connected = true
            addPacket("already-on-server")
            addPacket("rejected")
            manager.start(ENDPOINT)
            runCurrent()

            val duplicate = ServerDirective.newBuilder().setAck(
                Ack.newBuilder().setPacketId("already-on-server").setErrorCode("DUPLICATE")
            ).build()
            directives.send(duplicate)
            directives.send(duplicate)
            directives.send(ServerDirective.newBuilder().setAck(
                Ack.newBuilder().setPacketId("rejected").setErrorCode("SERVER_ERROR")
            ).build())
            runCurrent()

            assertEquals(1L, manager.getUploadStatus().uploadedPackets)
            assertFalse(disk.containsKey("already-on-server"))
            assertTrue(disk.containsKey("rejected"))
        }
    }

    @Test
    fun acknowledgementPersistenceFailureRetainsPacketForRetry() = runTest {
        withManager {
            connected = true
            addPacket("durable-retry")
            manager.start(ENDPOINT)
            runCurrent()
            coEvery { files.updateAckStatus(any(), any()) } throws IllegalStateException("Database unavailable")

            directives.send(ServerDirective.newBuilder().setAck(
                Ack.newBuilder().setPacketId("durable-retry").setSuccess(true)
            ).build())
            runCurrent()

            assertEquals(0L, manager.getUploadStatus().uploadedPackets)
            assertTrue(disk.containsKey("durable-retry"))
            coVerify(exactly = 0) { files.deleteDataPacket("durable-retry") }
        }
    }

    @Test
    fun stopCancelsPendingConnectionAndDoesNotReconnectLater() = runTest {
        withManager {
            coEvery { uploader.connect(any()) } coAnswers { awaitCancellation() }
            manager.start(ENDPOINT)
            runCurrent()

            withTimeout(1_000) { manager.stop() }
            advanceTimeBy(60_000)
            runCurrent()

            assertFalse(manager.getUploadStatus().isRunning)
            coVerify(exactly = 1) { uploader.connect(ENDPOINT) }
            coVerify(exactly = 1) { uploader.disconnect() }
            coVerify(exactly = 0) { uploader.sendDataPacket(any()) }
        }
    }

    @Test
    fun failedSendRetainsMemoryFallbackWhenDiskPersistenceWasUnavailable() = runTest {
        withManager {
            connected = true
            addPacket("memory-only", onDisk = false)
            coEvery { uploader.sendDataPacket(any()) } returns false
            manager.start(ENDPOINT)
            runCurrent()

            assertEquals(listOf("memory-only"), memory.map { it.packetId })

            coEvery { uploader.sendDataPacket(any()) } returns true
            advanceTimeBy(1_000)
            runCurrent()

            assertTrue(memory.isEmpty())
            coVerify(exactly = 2) { uploader.sendDataPacket(any()) }
        }
    }

    @Test
    fun pausedInFlightSendRetainsMemoryFallbackForResume() = runTest {
        withManager {
            connected = true
            addPacket("interrupted-packet", onDisk = false)
            coEvery { uploader.sendDataPacket(any()) } coAnswers { awaitCancellation() }
            manager.start(ENDPOINT)
            runCurrent()

            manager.pauseUpload()
            runCurrent()
            assertEquals(listOf("interrupted-packet"), memory.map { it.packetId })

            coEvery { uploader.sendDataPacket(any()) } returns true
            manager.resumeUpload()
            runCurrent()

            assertTrue(memory.isEmpty())
            coVerify(exactly = 2) { uploader.sendDataPacket(any()) }
        }
    }

    @Test
    fun pausedDiskReadDoesNotDiscardMemoryFallback() = runTest {
        withManager {
            connected = true
            addPacket("waiting-for-disk-query", onDisk = false)
            coEvery { files.getPendingPackets(any()) } coAnswers { awaitCancellation() }
            manager.start(ENDPOINT)
            runCurrent()

            manager.pauseUpload()
            runCurrent()

            assertEquals(listOf("waiting-for-disk-query"), memory.map { it.packetId })
            coVerify(exactly = 0) { uploader.sendDataPacket(any()) }
        }
    }

    private suspend fun TestScope.withManager(block: suspend Fixture.() -> Unit) {
        val fixture = Fixture(this)
        try {
            fixture.block()
        } finally {
            fixture.manager.stop()
            fixture.directives.close()
        }
    }

    private class Fixture(scope: TestScope) {
        val uploader = mockk<Uploader>(relaxed = true)
        val files = mockk<FileQueueManager>(relaxed = true)
        val memory = ArrayDeque<DataPacket>()
        val disk = linkedMapOf<String, DataPacket>()
        val sent = mutableListOf<String>()
        val directives = Channel<ServerDirective>(Channel.UNLIMITED)
        val progress = MutableStateFlow(UploadProgress())
        var connected = false
        var wifi = true
        var failuresBeforeConnect = 0
        var connectAttempts = 0
        private val uploadTimes = mutableMapOf<String, Long>()
        private val buffer = mockk<InMemoryBuffer>(relaxed = true)
        private val detector = mockk<NetworkEnvironmentDetector>()
        val manager: UploadManager

        init {
            every { uploader.getConnectionStatus() } answers {
                ConnectionStatusDetail(if (connected) "CONNECTED" else "DISCONNECTED", ENDPOINT, 0)
            }
            every { uploader.getConnectionStats() } answers {
                ConnectionStats(if (connected) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED)
            }
            every { uploader.getServerDirectiveFlow() } returns directives.receiveAsFlow()
            coEvery { uploader.connect(any()) } answers {
                connectAttempts++
                connected = connectAttempts > failuresBeforeConnect
                connected
            }
            coEvery { uploader.disconnect() } answers { connected = false }
            coEvery { uploader.sendDataPacket(any()) } answers {
                sent += firstArg<DataPacket>().packetId
                true
            }
            every { detector.isWifiConnected() } answers { wifi }
            every { buffer.getSize() } answers { memory.size }
            coEvery { buffer.dequeue(any()) } answers {
                val requested = firstArg<Int>().let { if (it < 0) memory.size else it }
                List(minOf(requested, memory.size)) { memory.removeFirst() }
            }
            coEvery { buffer.enqueue(any()) } answers {
                memory.addLast(firstArg())
                true
            }
            every { files.queueStats } returns MutableStateFlow(QueueStats())
            every { files.uploadProgress } returns progress
            coEvery { files.updateAckStatus(any(), any()) } answers {
                val packet = disk[firstArg<String>()]
                if (packet == null) {
                    false
                } else {
                    progress.value = progress.value.copy(
                        acknowledgedPackets = progress.value.acknowledgedPackets + 1,
                        lastAckTimestamp = System.currentTimeMillis(),
                        lastPacketSeqNo = packet.packetSeqNo
                    )
                    true
                }
            }
            coEvery { files.getPendingPackets(any()) } answers {
                val ackRetryMillis = firstArg<Long>()
                disk.values.filter { packet ->
                    uploadTimes[packet.packetId]?.let { scope.testScheduler.currentTime - it >= ackRetryMillis } ?: true
                }.mapIndexed { index, packet -> metadata(packet, index.toLong()) }
            }
            coEvery { files.readDataPacket(any()) } answers {
                val packet = disk[firstArg<String>()]
                if (packet == null) Result.failure(IllegalStateException("No disk copy"))
                else Result.success(packet.toByteArray())
            }
            coEvery { files.updateUploadStatus(any(), any(), any()) } answers {
                uploadTimes[firstArg()] = scope.testScheduler.currentTime
                0
            }
            coEvery { files.deleteDataPacket(any()) } answers {
                disk.remove(firstArg<String>()) != null
            }
            manager = UploadManager(
                uploader, buffer, mockk<PolicyManager>(relaxed = true), files, detector,
                StandardTestDispatcher(scope.testScheduler)
            )
        }

        fun addPacket(id: String, inMemory: Boolean = true, onDisk: Boolean = true): DataPacket {
            val packet = packet(id)
            if (inMemory) memory.addLast(packet)
            if (onDisk) disk[id] = packet
            return packet
        }
    }

    companion object {
        private const val ENDPOINT = "http://localhost:50051"

        private fun packet(id: String): DataPacket = DataPacket.newBuilder()
            .setPacketId(id)
            .setDeviceIdHash("test-device")
            .build()

        private fun metadata(packet: DataPacket, createdTime: Long) = BatchMetadata(
            packetId = packet.packetId,
            filePath = packet.packetId,
            status = BatchStatus.PENDING,
            createdTime = createdTime,
            fileSize = packet.serializedSize.toLong(),
            sampleCount = 1,
            transmissionMode = "STANDARD",
            baseWallMs = 0,
            deviceUptimeNs = 0,
            deviceId = packet.deviceIdHash
        )
    }
}
