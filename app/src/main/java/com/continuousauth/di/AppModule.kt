package com.continuousauth.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * 应用程序级别的依赖注入模块
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    /**
     * 提供全局协程作用域
     * 使用SupervisorJob确保子协程失败不会影响其他协程
     */
    @Provides
    @Singleton
    fun provideApplicationCoroutineScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}