package com.continuousauth.sensor;

import android.content.Context;
import com.continuousauth.buffer.RingBuffer;
import com.continuousauth.pool.SensorEventPool;
import com.continuousauth.utils.ForegroundAppDetector;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation"
})
public final class SensorCollectorImpl_Factory implements Factory<SensorCollectorImpl> {
  private final Provider<Context> contextProvider;

  private final Provider<ForegroundAppDetector> foregroundAppDetectorProvider;

  private final Provider<RingBuffer> ringBufferProvider;

  private final Provider<SensorEventPool> sensorEventPoolProvider;

  public SensorCollectorImpl_Factory(Provider<Context> contextProvider,
      Provider<ForegroundAppDetector> foregroundAppDetectorProvider,
      Provider<RingBuffer> ringBufferProvider, Provider<SensorEventPool> sensorEventPoolProvider) {
    this.contextProvider = contextProvider;
    this.foregroundAppDetectorProvider = foregroundAppDetectorProvider;
    this.ringBufferProvider = ringBufferProvider;
    this.sensorEventPoolProvider = sensorEventPoolProvider;
  }

  @Override
  public SensorCollectorImpl get() {
    return newInstance(contextProvider.get(), foregroundAppDetectorProvider.get(), ringBufferProvider.get(), sensorEventPoolProvider.get());
  }

  public static SensorCollectorImpl_Factory create(Provider<Context> contextProvider,
      Provider<ForegroundAppDetector> foregroundAppDetectorProvider,
      Provider<RingBuffer> ringBufferProvider, Provider<SensorEventPool> sensorEventPoolProvider) {
    return new SensorCollectorImpl_Factory(contextProvider, foregroundAppDetectorProvider, ringBufferProvider, sensorEventPoolProvider);
  }

  public static SensorCollectorImpl newInstance(Context context,
      ForegroundAppDetector foregroundAppDetector, RingBuffer ringBuffer,
      SensorEventPool sensorEventPool) {
    return new SensorCollectorImpl(context, foregroundAppDetector, ringBuffer, sensorEventPool);
  }
}
