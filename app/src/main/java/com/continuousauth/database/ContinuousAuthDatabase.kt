package com.continuousauth.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room数据库主类
 */
@Database(
    entities = [BatchMetadata::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ContinuousAuthDatabase : RoomDatabase() {
    
    abstract fun batchMetadataDao(): BatchMetadataDao
    
    companion object {
        private const val DATABASE_NAME = "continuous_auth_db"
        
        @Volatile
        private var INSTANCE: ContinuousAuthDatabase? = null
        
        fun getInstance(context: Context): ContinuousAuthDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ContinuousAuthDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
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
