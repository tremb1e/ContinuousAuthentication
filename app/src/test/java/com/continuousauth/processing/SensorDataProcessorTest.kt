package com.continuousauth.processing

import android.app.Application
import android.content.Context
import com.continuousauth.chunking.ChunkingManager
import com.continuousauth.compression.CompressionManager
import com.continuousauth.crypto.AADBuilder
import com.continuousauth.crypto.CryptoBox
import com.continuousauth.crypto.EnvelopeCryptoBox
import com.continuousauth.data.DataPacketBuilder
import com.continuousauth.database.BatchMetadata
import com.continuousauth.database.BatchStatus
import com.continuousauth.model.SensorSample
import com.continuousauth.model.SensorType
import com.continuousauth.proto.DataPacket
import com.continuousauth.proto.SerializedSensorBatch
import com.continuousauth.storage.FileQueueManager
import com.continuousauth.time.EnhancedTimeSync
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class SensorDataProcessorTest {
    @Test
    fun serializedCompressedPersistedTailPreservesSamplesAndMatchesEncryptionPacketId() = runBlocking {
        val fixture = Fixture(this)
        val startedAt = System.currentTimeMillis()
        val samples = listOf(
            sample(1, SensorType.ACCELEROMETER, "collector.app"),
            sample(2, SensorType.GYROSCOPE, "third.party.video"),
            sample(3, SensorType.MAGNETOMETER, "third.party.chat"),
            sample(4, SensorType.ACCELEROMETER, "")
        )
        try {
            assertTrue(fixture.processor.startProcessing(samples.asFlow(), "background-session"))
            withTimeout(10_000) { fixture.processor.stopProcessing(graceful = true) }
            val packet = withTimeout(1_000) { fixture.processor.getEncryptedPacketFlow().first() }

            assertEquals(samples, fixture.decodeSamples(packet))
            assertEquals("background-session", fixture.decode(packet).sessionId)
            assertEquals(100L, packet.packetSeqNo)
            assertEquals("test-device-hash", packet.deviceIdHash)
            assertEquals("LZ4", packet.metadata.compression)
            assertEquals("test-key", packet.dekKeyId)
            assertTrue(packet.baseWallMs in startedAt..System.currentTimeMillis())
            assertFalse(packet.hasNtpOffsetMs())
            assertArrayEquals(
                MessageDigest.getInstance("SHA-256").digest(packet.encryptedSensorPayload.toByteArray()),
                packet.sha256.toByteArray()
            )

            val encrypted = fixture.encrypted.single()
            assertTrue(encrypted.aad.contains("01_packet_id=${packet.packetId}|"))
            assertTrue(encrypted.aad.contains("03_packet_seq_no=${packet.packetSeqNo}|"))
            assertTrue(encrypted.aad.contains("app_version=${packet.metadata.appVersion}|"))
            assertArrayEquals(encrypted.payload, packet.encryptedSensorPayload.toByteArray())
            val saved = fixture.saved.single()
            assertEquals(packet.packetId, saved.id)
            assertArrayEquals(packet.toByteArray(), saved.bytes)
            assertEquals(samples.size, saved.metadata.sampleCount)
            assertEquals(packet.packetSeqNo, saved.metadata.sequenceNumber)
            assertEquals("background-session", saved.metadata.sessionId)
            assertEquals(BatchStatus.PENDING, saved.metadata.status)
            assertFalse(fixture.processor.getProcessingStatus().isProcessing)
            assertEquals(4L, fixture.processor.getProcessingStatus().processedSampleCount)
        } finally {
            fixture.processor.stopProcessing()
        }
    }

    @Test
    fun gracefulStopDrainsClosedSensorQueueAndNextSessionUsesNewMetadata() = runBlocking {
        val fixture = Fixture(this)
        val source = Channel<SensorSample>(Channel.UNLIMITED)
        val firstSamples = (1L..300L).map { sample(it, SensorType.ACCELEROMETER, "third.party.first") }
        firstSamples.forEach { source.send(it) }
        source.close()
        try {
            assertTrue(fixture.processor.startProcessing(source.receiveAsFlow(), "first-session"))
            withTimeout(10_000) { fixture.processor.stopProcessing(graceful = true) }
            val first = withTimeout(1_000) { fixture.processor.getEncryptedPacketFlow().first() }
            assertEquals(firstSamples, fixture.decodeSamples(first))
            assertEquals("first-session", fixture.decode(first).sessionId)

            val secondSamples = listOf(sample(301, SensorType.GYROSCOPE, "third.party.second"))
            assertTrue(fixture.processor.startProcessing(secondSamples.asFlow(), "second-session"))
            withTimeout(10_000) { fixture.processor.stopProcessing(graceful = true) }
            val second = withTimeout(1_000) { fixture.processor.getEncryptedPacketFlow().first() }

            assertEquals(secondSamples, fixture.decodeSamples(second))
            assertEquals("second-session", fixture.decode(second).sessionId)
            assertEquals(first.packetSeqNo + 1, second.packetSeqNo)
            assertFalse(first.packetId == second.packetId)
            assertEquals(listOf("first-session", "second-session"), fixture.saved.map { it.metadata.sessionId })
            assertEquals(301L, fixture.processor.getProcessingStatus().processedSampleCount)
        } finally {
            fixture.processor.stopProcessing()
        }
    }

    @Test
    fun sourceFailureClearsRunningStateAndAllowsProcessingToRestart() = runBlocking {
        val fixture = Fixture(this)
        val beforeFailure = sample(1, SensorType.ACCELEROMETER, "third.party.first")
        val afterFailure = sample(2, SensorType.GYROSCOPE, "third.party.second")
        try {
            assertTrue(fixture.processor.startProcessing(flow {
                emit(beforeFailure)
                throw IllegalStateException("Sensor source stopped unexpectedly")
            }, "failed-source"))
            withTimeout(10_000) {
                while (fixture.processor.getProcessingStatus().isProcessing) delay(10)
            }
            val first = withTimeout(1_000) { fixture.processor.getEncryptedPacketFlow().first() }
            assertEquals(listOf(beforeFailure), fixture.decodeSamples(first))
            assertFalse(fixture.processor.getProcessingStatus().isProcessing)

            assertTrue(fixture.processor.startProcessing(listOf(afterFailure).asFlow(), "recovered-source"))
            withTimeout(10_000) { fixture.processor.stopProcessing(graceful = true) }
            val second = withTimeout(1_000) { fixture.processor.getEncryptedPacketFlow().first() }
            assertEquals(listOf(afterFailure), fixture.decodeSamples(second))
            assertEquals("recovered-source", fixture.decode(second).sessionId)
            assertFalse(fixture.processor.getProcessingStatus().isProcessing)
        } finally {
            fixture.processor.stopProcessing()
        }
    }

    private class Fixture(scope: CoroutineScope) {
        val saved: MutableList<SavedPacket> = Collections.synchronizedList(mutableListOf())
        val encrypted: MutableList<EncryptionCall> = Collections.synchronizedList(mutableListOf())
        private val compression = CompressionManager()
        val processor: SensorDataProcessor

        init {
            val context: Context = RuntimeEnvironment.getApplication()
            val crypto = mockk<CryptoBox>()
            val envelope = mockk<EnvelopeCryptoBox>()
            val time = EnhancedTimeSync(scope)
            val queue = mockk<FileQueueManager>()
            val sequence = AtomicLong(100)
            coEvery { crypto.initialize() } returns true
            coEvery { crypto.encrypt(any(), any()) } answers {
                val payload = firstArg<ByteArray>().copyOf()
                encrypted += EncryptionCall(payload, secondArg<ByteArray>().toString(Charsets.UTF_8))
                payload
            }
            every { envelope.getDeviceIdHash() } returns "test-device-hash"
            every { envelope.getDekKeyId() } returns "test-key"
            every { envelope.getNextPacketSeqNo() } answers { sequence.getAndIncrement() }
            coEvery { queue.saveDataPacket(any(), any(), any()) } answers {
                val id = firstArg<String>()
                saved += SavedPacket(id, secondArg<ByteArray>().copyOf(), thirdArg())
                Result.success(File(context.cacheDir, "$id.dat"))
            }
            processor = SensorDataProcessor(
                crypto,
                envelope,
                AADBuilder(context, envelope),
                DataPacketBuilder(context, time, envelope),
                compression,
                ChunkingManager(),
                queue
            )
        }

        fun decode(packet: DataPacket): SerializedSensorBatch {
            val bytes = compression.decompress(packet.encryptedSensorPayload.toByteArray())!!
            assertEquals(bytes.size, packet.metadata.uncompressedSizeBytes)
            return SerializedSensorBatch.parseFrom(bytes)
        }

        fun decodeSamples(packet: DataPacket): List<SensorSample> = decode(packet).samplesList.map {
            SensorSample(
                type = SensorType.valueOf(it.type.name),
                eventTimestampNs = it.eventTimestampNs,
                x = it.x,
                y = it.y,
                z = it.z,
                accuracy = it.accuracy,
                seqNo = it.seqNo,
                foregroundApp = it.foregroundAppName
            )
        }
    }

    private data class SavedPacket(val id: String, val bytes: ByteArray, val metadata: BatchMetadata)
    private data class EncryptionCall(val payload: ByteArray, val aad: String)

    companion object {
        private fun sample(sequence: Long, type: SensorType, foregroundApp: String) = SensorSample(
            type = type,
            eventTimestampNs = 1_234_000_000L + sequence * 10_000_000L,
            x = sequence / 10f,
            y = -sequence / 20f,
            z = sequence / 30f,
            accuracy = 3,
            seqNo = sequence,
            foregroundApp = foregroundApp
        )
    }
}
