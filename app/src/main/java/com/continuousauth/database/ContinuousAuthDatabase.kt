package com.continuousauth.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room数据库主类
 */
@Database(
    entities = [BatchMetadata::class, UploadProgress::class],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ContinuousAuthDatabase : RoomDatabase() {
    
    abstract fun batchMetadataDao(): BatchMetadataDao
    
    companion object {
        private const val DATABASE_NAME = "continuous_auth_db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // SQLite versions on older supported devices cannot drop a column in place.
                database.execSQL(
                    "CREATE TABLE `batch_metadata_new` (`packetId` TEXT NOT NULL, " +
                        "`filePath` TEXT NOT NULL, `status` TEXT NOT NULL, `createdTime` INTEGER NOT NULL, " +
                        "`uploadTime` INTEGER, `ackTime` INTEGER, `fileSize` INTEGER NOT NULL, " +
                        "`sampleCount` INTEGER NOT NULL, `transmissionMode` TEXT NOT NULL, `ntpOffset` INTEGER, " +
                        "`baseWallMs` INTEGER NOT NULL, `deviceUptimeNs` INTEGER NOT NULL, " +
                        "`retryCount` INTEGER NOT NULL, `lastError` TEXT, `sequenceNumber` INTEGER, " +
                        "`sessionId` TEXT, `deviceId` TEXT NOT NULL, `sha256` TEXT, PRIMARY KEY(`packetId`))"
                )
                val columns = "`packetId`, `filePath`, `status`, `createdTime`, `uploadTime`, `ackTime`, " +
                    "`fileSize`, `sampleCount`, `transmissionMode`, `ntpOffset`, `baseWallMs`, " +
                    "`deviceUptimeNs`, `retryCount`, `lastError`, `sequenceNumber`, `sessionId`, `deviceId`, `sha256`"
                database.execSQL("INSERT INTO `batch_metadata_new` ($columns) SELECT $columns FROM `batch_metadata`")
                database.execSQL("DROP TABLE `batch_metadata`")
                database.execSQL("ALTER TABLE `batch_metadata_new` RENAME TO `batch_metadata`")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `upload_progress` (`id` INTEGER NOT NULL, " +
                        "`acknowledgedPackets` INTEGER NOT NULL, `lastAckTimestamp` INTEGER NOT NULL, " +
                        "`lastPacketSeqNo` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }
        
        @Volatile
        private var INSTANCE: ContinuousAuthDatabase? = null
        
        fun getInstance(context: Context): ContinuousAuthDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ContinuousAuthDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * 类型转换器
 */
class Converters {
    @androidx.room.TypeConverter
    fun fromBatchStatus(status: BatchStatus): String {
        return status.name
    }
    
    @androidx.room.TypeConverter
    fun toBatchStatus(status: String): BatchStatus {
        return BatchStatus.valueOf(status)
    }
}
