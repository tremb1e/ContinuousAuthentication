package com.continuousauth.di

import com.continuousauth.sensor.SensorCollector
import com.continuousauth.sensor.SensorCollectorImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 传感器模块依赖注入配置
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SensorModule {
    
    @Binds
    @Singleton
    abstract fun bindSensorCollector(
        sensorCollectorImpl: SensorCollectorImpl
    ): SensorCollector
}