package com.continuousauth.performance;

import com.continuousauth.buffer.InMemoryBuffer;
import com.continuousauth.compression.CompressionManager;
import com.continuousauth.network.UploadManager;
import com.continuousauth.sensor.SensorCollector;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata
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
public final class DynamicOptimizer_Factory implements Factory<DynamicOptimizer> {
  private final Provider<SensorCollector> sensorCollectorProvider;

  private final Provider<CompressionManager> compressionManagerProvider;

  private final Provider<InMemoryBuffer> inMemoryBufferProvider;

  private final Provider<UploadManager> uploadManagerProvider;

  public DynamicOptimizer_Factory(Provider<SensorCollector> sensorCollectorProvider,
      Provider<CompressionManager> compressionManagerProvider,
      Provider<InMemoryBuffer> inMemoryBufferProvider,
      Provider<UploadManager> uploadManagerProvider) {
    this.sensorCollectorProvider = sensorCollectorProvider;
    this.compressionManagerProvider = compressionManagerProvider;
    this.inMemoryBufferProvider = inMemoryBufferProvider;
    this.uploadManagerProvider = uploadManagerProvider;
  }

  @Override
  public DynamicOptimizer get() {
    return newInstance(sensorCollectorProvider.get(), compressionManagerProvider.get(), inMemoryBufferProvider.get(), uploadManagerProvider.get());
  }

  public static DynamicOptimizer_Factory create(Provider<SensorCollector> sensorCollectorProvider,
      Provider<CompressionManager> compressionManagerProvider,
      Provider<InMemoryBuffer> inMemoryBufferProvider,
      Provider<UploadManager> uploadManagerProvider) {
    return new DynamicOptimizer_Factory(sensorCollectorProvider, compressionManagerProvider, inMemoryBufferProvider, uploadManagerProvider);
  }

  public static DynamicOptimizer newInstance(SensorCollector sensorCollector,
      CompressionManager compressionManager, InMemoryBuffer inMemoryBuffer,
      UploadManager uploadManager) {
    return new DynamicOptimizer(sensorCollector, compressionManager, inMemoryBuffer, uploadManager);
  }
}
