package com.continuousauth.storage

import android.app.Application
import android.content.Context
import com.continuousauth.database.BatchMetadata
import com.continuousauth.database.BatchMetadataDao
import com.continuousauth.database.BatchStatus
import com.continuousauth.database.StatusCount
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class FileQueueManagerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var cacheDir: File
    private lateinit var durableDir: File
    private lateinit var manager: FileQueueManager
    private val rows = ConcurrentHashMap<String, BatchMetadata>()

    @Before
    fun setUp() {
        cacheDir = temporaryFolder.newFolder("cache")
        durableDir = temporaryFolder.newFolder("no_backup")
        val context = mockk<Context>()
        every { context.cacheDir } returns cacheDir
        every { context.noBackupFilesDir } returns durableDir
        val dao = mockk<BatchMetadataDao>(relaxed = true)
        coEvery { dao.insert(any()) } answers {
            val row = firstArg<BatchMetadata>()
            rows[row.packetId] = row
            Unit
        }
        coEvery { dao.getById(any()) } answers { rows[firstArg<String>()] }
        coEvery { dao.getPendingBatches(any()) } answers {
            val statuses = firstArg<List<BatchStatus>>()
            rows.values.filter { it.status in statuses }.sortedBy { it.createdTime }
        }
        coEvery { dao.getStatusCounts() } answers {
            rows.values.groupingBy { it.status }.eachCount().map { StatusCount(it.key, it.value) }
        }
        coEvery { dao.updateStatus(any(), any()) } answers {
            val id = firstArg<String>()
            rows.computeIfPresent(id) { _, row -> row.copy(status = secondArg()) }
            Unit
        }
        coEvery { dao.deleteById(any()) } answers { rows.remove(firstArg<String>()); Unit }
        coEvery { dao.clearAll() } answers { rows.clear() }
        manager = FileQueueManager(context, dao)
    }

    @After
    fun tearDown() {
        manager.cleanup()
    }

    @Test
    fun newPacketsSurviveCacheEvictionAndStoreVerifiedMetadata() = runBlocking {
        val data = byteArrayOf(1, 3, 5, 7)
        val saved = manager.saveDataPacket("durable", data, metadata("durable")).getOrThrow()

        assertEquals(File(durableDir, "data_queue/durable.dat").canonicalFile, saved.canonicalFile)
        assertFalse(File(durableDir, "data_queue/durable.tmp").exists())
        assertEquals(saved.absolutePath, rows.getValue("durable").filePath)
        assertEquals(data.size.toLong(), rows.getValue("durable").fileSize)
        assertEquals(sha256(data), rows.getValue("durable").sha256)
        cacheDir.deleteRecursively()

        assertArrayEquals(data, manager.readDataPacket("durable").getOrThrow())
    }

    @Test
    fun preexistingCacheMetadataRemainsReadable() = runBlocking {
        val data = byteArrayOf(2, 4, 6)
        val legacy = File(cacheDir, "data_queue/legacy.dat")
        legacy.parentFile!!.mkdirs()
        legacy.writeBytes(data)
        rows["legacy"] = metadata("legacy").copy(
            filePath = legacy.absolutePath, fileSize = data.size.toLong(), sha256 = sha256(data)
        )

        assertArrayEquals(data, manager.readDataPacket("legacy").getOrThrow())
        assertEquals(legacy.absolutePath, rows.getValue("legacy").filePath)
        assertEquals(1, manager.getQueueSize())
    }

    @Test
    fun mixedLegacyAndDurableFilesAreCountedAndClearedTogether() = runBlocking {
        val legacy = File(cacheDir, "data_queue/legacy.dat")
        legacy.parentFile!!.mkdirs()
        legacy.writeBytes(byteArrayOf(1, 2, 3))
        rows["legacy"] = metadata("legacy").copy(filePath = legacy.absolutePath, fileSize = 3)
        val durable = manager.saveDataPacket("new", byteArrayOf(4, 5), metadata("new")).getOrThrow()

        val stats = manager.getQueueStatistics()
        assertEquals(5L, stats.totalSizeBytes)
        assertEquals(2, stats.totalPackets)
        assertEquals(2, stats.pendingPackets)
        assertEquals(2, manager.queueStats.value.fileCount)
        assertEquals(5L, manager.queueStats.value.totalSizeBytes)
        manager.clearQueue()

        assertFalse(legacy.exists())
        assertFalse(durable.exists())
        assertTrue(rows.isEmpty())
        assertEquals(0L, manager.getQueueStatistics().totalSizeBytes)
    }

    @Test
    fun staleUploadedPacketsAreMergedInCreationOrderWithPendingAndFailed() = runBlocking {
        val now = System.currentTimeMillis()
        rows["pending"] = metadata("pending", createdTime = now - 1_000)
        rows["failed"] = metadata("failed", createdTime = now - 2_000, status = BatchStatus.FAILED)
        rows["old-upload"] = metadata("old-upload", createdTime = now - 3_000, status = BatchStatus.UPLOADED)
            .copy(uploadTime = now - 60_000)
        rows["fresh-upload"] = metadata("fresh-upload", createdTime = now - 5_000, status = BatchStatus.UPLOADED)
            .copy(uploadTime = now)
        rows["acknowledged"] = metadata("acknowledged", createdTime = now - 6_000, status = BatchStatus.ACKNOWLEDGED)

        assertEquals(listOf("old-upload", "failed", "pending"),
            manager.getPendingPackets(retryUploadedAfterMs = 30_000).map { it.packetId })
        assertEquals(listOf("failed", "pending"), manager.getPendingPackets().map { it.packetId })
    }

    @Test
    fun uploadedPacketWithoutUploadTimestampCanBeRecovered() = runBlocking {
        val now = System.currentTimeMillis()
        rows["unconfirmed"] = metadata("unconfirmed", createdTime = now - 2_000, status = BatchStatus.UPLOADED)
        rows["pending"] = metadata("pending", createdTime = now - 1_000)

        assertEquals(listOf("unconfirmed", "pending"),
            manager.getPendingPackets(retryUploadedAfterMs = 30_000).map { it.packetId })
    }

    @Test
    fun missingAndCorruptFilesAreRemovedFromRetryCandidates() = runBlocking {
        val missing = File(cacheDir, "data_queue/evicted.dat")
        rows["evicted"] = metadata("evicted").copy(filePath = missing.absolutePath)
        manager.saveDataPacket("tampered", byteArrayOf(1, 2, 3), metadata("tampered")).getOrThrow()
            .writeBytes(byteArrayOf(9, 8, 7))
        manager.saveDataPacket("healthy", byteArrayOf(4), metadata("healthy")).getOrThrow()

        assertTrue(manager.readDataPacket("evicted").isFailure)
        assertTrue(manager.readDataPacket("tampered").isFailure)
        assertEquals(BatchStatus.CORRUPT, rows.getValue("evicted").status)
        assertEquals(BatchStatus.CORRUPT, rows.getValue("tampered").status)
        assertEquals(listOf("healthy"), manager.getPendingPackets().map { it.packetId })
        assertEquals(2, manager.queueStats.value.corruptedPackets)
    }

    @Test
    fun interruptedUploadingAndUnacknowledgedPacketsAreIncludedWhenClearingPendingData() = runBlocking {
        manager.saveDataPacket("interrupted", byteArrayOf(1), metadata("interrupted", status = BatchStatus.UPLOADING)).getOrThrow()
        manager.saveDataPacket("waiting-ack", byteArrayOf(2), metadata("waiting-ack", status = BatchStatus.UPLOADED)).getOrThrow()
        assertEquals(listOf("interrupted"), manager.getPendingPackets().map { it.packetId })
        assertEquals(2, manager.getQueueSize())
        assertEquals(2, manager.getQueueStatistics().pendingPackets)

        manager.clearPendingQueue()

        assertEquals(BatchStatus.DISCARDED, rows.getValue("interrupted").status)
        assertEquals(BatchStatus.DISCARDED, rows.getValue("waiting-ack").status)
        assertEquals(0, manager.getQueueSize())
        assertEquals(0L, manager.queueStats.value.totalSizeBytes)
    }

    @Test
    fun failedDeletionCannotReportSuccessOrEraseRecoveryMetadata() = runBlocking {
        val undeletable = File(durableDir, "data_queue/blocked.dat").apply { mkdirs() }
        File(undeletable, "child").writeText("still present")
        rows["blocked"] = metadata("blocked").copy(filePath = undeletable.absolutePath)
        assertFalse(manager.deleteDataPacket("blocked"))
        assertTrue(rows.containsKey("blocked"))

        val result = runCatching { manager.clearQueue() }
        assertTrue(result.isFailure)
        assertTrue(rows.containsKey("blocked"))
        assertTrue(undeletable.exists())
    }

    @Test
    fun bulkDeletionKeepsOnlyFilesThatCouldNotBeDeletedAndCanBeRetried() = runBlocking {
        manager.saveDataPacket("removable", byteArrayOf(1), metadata("removable")).getOrThrow()
        val blocked = File(durableDir, "data_queue/blocked.dat").apply { mkdirs() }
        val child = File(blocked, "child").apply { writeText("still present") }
        rows["blocked"] = metadata("blocked").copy(filePath = blocked.absolutePath)

        assertFalse(manager.deleteDataPackets(listOf("removable", "blocked", "already-deleted")))
        assertEquals(setOf("blocked"), rows.keys)
        assertTrue(child.delete())
        assertTrue(manager.deleteDataPackets(listOf("blocked")))
        assertTrue(rows.isEmpty())
        assertEquals(0, manager.queueStats.value.fileCount)
    }

    @Test
    fun fixedSevenDayRetentionCleansAllPacketStatesAndInterruptedWriteFiles() = runBlocking {
        val now = System.currentTimeMillis() + 60_000
        val cutoff = now - 7 * 24 * 60 * 60 * 1_000L
        val expiredFiles = BatchStatus.entries.map { status ->
            val id = "expired-${status.name}"
            val file = File(durableDir, "data_queue/$id.dat").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            rows[id] = metadata(id, cutoff - 1, status).copy(filePath = file.absolutePath)
            file
        }
        val boundary = File(cacheDir, "data_queue/boundary.dat").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(2))
        }
        rows["boundary"] = metadata("boundary", cutoff).copy(filePath = boundary.absolutePath)
        val orphan = File(durableDir, "data_queue/interrupted.tmp").apply {
            writeBytes(byteArrayOf(3))
            assertTrue(setLastModified(cutoff - 1))
        }
        val recent = manager.saveDataPacket("recent", byteArrayOf(4), metadata("recent")).getOrThrow()

        assertTrue(manager.cleanupExpiredPackets(now))

        assertEquals(setOf("boundary", "recent"), rows.keys)
        expiredFiles.forEach { assertFalse(it.exists()) }
        assertFalse(orphan.exists())
        assertTrue(boundary.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun expiredUndeletablePacketsCannotBeReadOrRetriedAndKeepRecoveryMetadata() = runBlocking {
        val now = System.currentTimeMillis()
        val blocked = File(durableDir, "data_queue/expired-blocked.dat").apply { mkdirs() }
        val child = File(blocked, "child").apply { writeText("still present") }
        rows["expired-blocked"] = metadata("expired-blocked", now - FileQueueManager.RETENTION_MS - 1)
            .copy(filePath = blocked.absolutePath)

        assertFalse(manager.cleanupExpiredPackets(now))
        assertTrue(manager.getPendingPackets().isEmpty())
        assertTrue(manager.readDataPacket("expired-blocked").isFailure)
        assertTrue(rows.containsKey("expired-blocked"))
        assertTrue(child.delete())
        assertTrue(manager.cleanupExpiredPackets(now))
        assertFalse(rows.containsKey("expired-blocked"))
    }

    @Test
    fun alreadyExpiredPacketsCannotBeWrittenBackToTheQueue() = runBlocking {
        val expired = metadata("expired", System.currentTimeMillis() - FileQueueManager.RETENTION_MS - 1)
        assertTrue(manager.saveDataPacket("expired", byteArrayOf(1), expired).isFailure)
        assertFalse(rows.containsKey("expired"))
        assertFalse(File(durableDir, "data_queue/expired.dat").exists())
    }

    private fun metadata(
        id: String,
        createdTime: Long = System.currentTimeMillis(),
        status: BatchStatus = BatchStatus.PENDING
    ) = BatchMetadata(
        packetId = id, filePath = "", status = status, createdTime = createdTime,
        fileSize = 0, sampleCount = 3, transmissionMode = "FAST", baseWallMs = 1_000,
        deviceUptimeNs = 1_000_000_000, deviceId = "test-device"
    )

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it) }
}
