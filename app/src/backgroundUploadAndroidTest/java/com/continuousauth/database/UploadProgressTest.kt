package com.continuousauth.database

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.continuousauth.storage.FileQueueManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises Android's SQLite transactions and existing installation upgrades. */
@RunWith(AndroidJUnit4::class)
class UploadProgressTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: ContinuousAuthDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "upload-progress-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun repeatedConcurrentAcksAndLateSendCompletionCountOnlyOnce() = runBlocking {
        val dao = openDatabase().batchMetadataDao()
        dao.insert(packet("first", 17L))

        val results = coroutineScope {
            List(20) { async { dao.acknowledgePacket("first", 1_200L, 1_500L) } }.awaitAll()
        }
        assertEquals(1, results.count { it })
        dao.updateUploadStatus("first", BatchStatus.UPLOADED, 1_600L)
        dao.updateStatus("first", BatchStatus.FAILED)
        dao.updateStatus("first", BatchStatus.CORRUPT)
        dao.updateStatus("first", BatchStatus.DISCARDED)
        assertEquals(BatchStatus.ACKNOWLEDGED, dao.getById("first")!!.status)
        assertFalse(dao.acknowledgePacket("first", 1_200L, 1_700L))
        assertEquals(UploadProgress(acknowledgedPackets = 1, lastAckTimestamp = 1_500, lastPacketSeqNo = 17),
            dao.getUploadProgress())

        dao.deleteById("first")
        assertFalse(dao.acknowledgePacket("first", 1_200L, 2_000L))
        assertFalse(dao.acknowledgePacket("unknown", 1_200L, 2_000L))
        assertEquals(1L, dao.getUploadProgress()!!.acknowledgedPackets)
    }

    @Test
    fun progressSurvivesPacketDeletionAndDatabaseReopening() = runBlocking {
        val dao = openDatabase().batchMetadataDao()
        dao.insert(packet("first", 17L))
        dao.insert(packet("second", 10L))
        dao.acknowledgePacket("first", 1_200, 1_500)
        dao.acknowledgePacket("second", 1_100, 1_400)
        dao.deleteById("first")
        dao.deleteById("second")
        database!!.close()

        val reopened = openDatabase().batchMetadataDao()
        assertEquals(0, reopened.getTotalCount())
        assertEquals(UploadProgress(acknowledgedPackets = 2, lastAckTimestamp = 1_500, lastPacketSeqNo = 17),
            reopened.getUploadProgress())
        assertFalse(reopened.acknowledgePacket("first", 1_200, 2_000))
    }

    @Test
    fun failedCounterWriteRollsBackAcknowledgementAndCanBeRetried() = runBlocking {
        val db = openDatabase()
        val dao = db.batchMetadataDao()
        dao.insert(packet("retry-after-error", 20))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_progress BEFORE UPDATE ON upload_progress " +
                "BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END"
        )

        assertTrue(runCatching { dao.acknowledgePacket("retry-after-error", 1_200, 1_500) }.isFailure)
        assertEquals(BatchStatus.UPLOADED, dao.getById("retry-after-error")!!.status)
        assertNull(dao.getUploadProgress())

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_progress")
        assertTrue(dao.acknowledgePacket("retry-after-error", 1_200, 1_600))
        assertEquals(1L, dao.getUploadProgress()!!.acknowledgedPackets)
    }

    @Test
    fun queueClearPreservesProgressAndPrivacyResetRemovesIt() = runBlocking {
        val dao = openDatabase().batchMetadataDao()
        val storageRoot = File(context.cacheDir, "queue-test-${UUID.randomUUID()}")
        val storageContext = object : ContextWrapper(context) {
            override fun getCacheDir(): File = File(storageRoot, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(storageRoot, "no-backup").apply { mkdirs() }
        }
        val files = FileQueueManager(storageContext, dao)
        try {
            dao.insert(packet("first", 17L))
            assertTrue(files.updateAckStatus("first", 1_200))
            assertEquals(1L, files.uploadProgress.value.acknowledgedPackets)
            files.clearQueue()
            assertEquals(1L, dao.getUploadProgress()!!.acknowledgedPackets)
            files.resetUploadProgress()
            assertNull(dao.getUploadProgress())
            assertEquals(UploadProgress(), files.uploadProgress.value)
        } finally {
            files.cleanup()
            storageRoot.deleteRecursively()
        }
    }

    @Test
    fun migrationFromVersionTwoPreservesUnsentPackets() = runBlocking {
        verifyLegacyMigration(2)
    }

    @Test
    fun migrationFromVersionOnePreservesUnsentPackets() = runBlocking {
        verifyLegacyMigration(1)
    }

    private suspend fun verifyLegacyMigration(version: Int) {
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS `batch_metadata` (`packetId` TEXT NOT NULL, " +
                    "`filePath` TEXT NOT NULL, `status` TEXT NOT NULL, `createdTime` INTEGER NOT NULL, " +
                    "`uploadTime` INTEGER, `ackTime` INTEGER, `fileSize` INTEGER NOT NULL, " +
                    "`sampleCount` INTEGER NOT NULL, `transmissionMode` TEXT NOT NULL, `ntpOffset` INTEGER, " +
                    "`baseWallMs` INTEGER NOT NULL, `deviceUptimeNs` INTEGER NOT NULL, " +
                    "`retryCount` INTEGER NOT NULL, `lastError` TEXT, `sequenceNumber` INTEGER, " +
                    "`sessionId` TEXT, `deviceId` TEXT NOT NULL, `sha256` TEXT, PRIMARY KEY(`packetId`))"
            )
            if (version == 1) {
                old.execSQL("ALTER TABLE batch_metadata ADD COLUMN userId TEXT")
            }
            old.execSQL(
                "INSERT INTO batch_metadata (packetId, filePath, status, createdTime, fileSize, " +
                    "sampleCount, transmissionMode, baseWallMs, deviceUptimeNs, retryCount, " +
                    "sequenceNumber, deviceId) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("pre-upgrade", "/queue/old.dat", "UPLOADED", 1_000L, 512L, 300,
                    "STANDARD", 1_000L, 1_000_000L, 2, 91L, "test-device")
            )
            old.version = version
        }

        val dao = openDatabase().batchMetadataDao()
        val retained = dao.getById("pre-upgrade")!!
        assertEquals("/queue/old.dat", retained.filePath)
        assertEquals(BatchStatus.UPLOADED, retained.status)
        assertEquals(2, retained.retryCount)
        assertNull(dao.getUploadProgress())
        assertTrue(dao.acknowledgePacket("pre-upgrade", 2_000, 2_100))
        assertEquals(1L, dao.getUploadProgress()!!.acknowledgedPackets)
        assertEquals(91L, dao.getUploadProgress()!!.lastPacketSeqNo)
    }

    private fun openDatabase(): ContinuousAuthDatabase = Room.databaseBuilder(
        context, ContinuousAuthDatabase::class.java, databaseName
    ).addMigrations(ContinuousAuthDatabase.MIGRATION_1_2, ContinuousAuthDatabase.MIGRATION_2_3)
        .build().also { database = it }

    private fun packet(id: String, sequence: Long) = BatchMetadata(
        packetId = id, filePath = "/queue/$id.dat", status = BatchStatus.UPLOADED,
        createdTime = 1_000, fileSize = 512, sampleCount = 300, transmissionMode = "STANDARD",
        baseWallMs = 1_000, deviceUptimeNs = 1_000_000, deviceId = "test-device",
        sequenceNumber = sequence
    )
}
