package com.continuousauth.di

import android.content.Context
import com.continuousauth.database.BatchMetadataDao
import com.continuousauth.database.ContinuousAuthDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据库模块依赖注入配置
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    /**
     * 提供数据库实例
     */
    @Provides
    @Singleton
    fun provideContinuousAuthDatabase(
        @ApplicationContext context: Context
    ): ContinuousAuthDatabase {
        return ContinuousAuthDatabase.getInstance(context)
    }
    
    /**
     * 提供BatchMetadataDao
     */
    @Provides
    @Singleton
    fun provideBatchMetadataDao(
        database: ContinuousAuthDatabase
    ): BatchMetadataDao {
        return database.batchMetadataDao()
    }
}