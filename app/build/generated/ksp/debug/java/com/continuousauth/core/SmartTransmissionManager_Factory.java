package com.continuousauth.core;

import com.continuousauth.buffer.InMemoryBuffer;
import com.continuousauth.chunking.ChunkingManager;
import com.continuousauth.compression.CompressionManager;
import com.continuousauth.crypto.EnvelopeCryptoBox;
import com.continuousauth.data.DataPacketBuilder;
import com.continuousauth.monitor.SystemMonitor;
import com.continuousauth.network.UploadManager;
import com.continuousauth.observability.MetricsUploader;
import com.continuousauth.privacy.PrivacyManager;
import com.continuousauth.processing.SensorDataProcessor;
import com.continuousauth.sensor.SensorCollector;
import com.continuousauth.storage.FileQueueManager;
import com.continuousauth.time.EnhancedTimeSync;
import com.continuousauth.utils.UserIdManager;
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
public final class SmartTransmissionManager_Factory implements Factory<SmartTransmissionManager> {
  private final Provider<SensorCollector> sensorCollectorProvider;

  private final Provider<SensorDataProcessor> sensorDataProcessorProvider;

  private final Provider<DataPacketBuilder> dataPacketBuilderProvider;

  private final Provider<CompressionManager> compressionManagerProvider;

  private final Provider<EnvelopeCryptoBox> cryptoBoxProvider;

  private final Provider<InMemoryBuffer> inMemoryBufferProvider;

  private final Provider<FileQueueManager> fileQueueManagerProvider;

  private final Provider<UploadManager> uploadManagerProvider;

  private final Provider<ChunkingManager> chunkingManagerProvider;

  private final Provider<EnhancedTimeSync> timeSyncProvider;

  private final Provider<SystemMonitor> systemMonitorProvider;

  private final Provider<MetricsUploader> metricsUploaderProvider;

  private final Provider<PrivacyManager> privacyManagerProvider;

  private final Provider<UserIdManager> userIdManagerProvider;

  public SmartTransmissionManager_Factory(Provider<SensorCollector> sensorCollectorProvider,
      Provider<SensorDataProcessor> sensorDataProcessorProvider,
      Provider<DataPacketBuilder> dataPacketBuilderProvider,
      Provider<CompressionManager> compressionManagerProvider,
      Provider<EnvelopeCryptoBox> cryptoBoxProvider,
      Provider<InMemoryBuffer> inMemoryBufferProvider,
      Provider<FileQueueManager> fileQueueManagerProvider,
      Provider<UploadManager> uploadManagerProvider,
      Provider<ChunkingManager> chunkingManagerProvider,
      Provider<EnhancedTimeSync> timeSyncProvider, Provider<SystemMonitor> systemMonitorProvider,
      Provider<MetricsUploader> metricsUploaderProvider,
      Provider<PrivacyManager> privacyManagerProvider,
      Provider<UserIdManager> userIdManagerProvider) {
    this.sensorCollectorProvider = sensorCollectorProvider;
    this.sensorDataProcessorProvider = sensorDataProcessorProvider;
    this.dataPacketBuilderProvider = dataPacketBuilderProvider;
    this.compressionManagerProvider = compressionManagerProvider;
    this.cryptoBoxProvider = cryptoBoxProvider;
    this.inMemoryBufferProvider = inMemoryBufferProvider;
    this.fileQueueManagerProvider = fileQueueManagerProvider;
    this.uploadManagerProvider = uploadManagerProvider;
    this.chunkingManagerProvider = chunkingManagerProvider;
    this.timeSyncProvider = timeSyncProvider;
    this.systemMonitorProvider = systemMonitorProvider;
    this.metricsUploaderProvider = metricsUploaderProvider;
    this.privacyManagerProvider = privacyManagerProvider;
    this.userIdManagerProvider = userIdManagerProvider;
  }

  @Override
  public SmartTransmissionManager get() {
    return newInstance(sensorCollectorProvider.get(), sensorDataProcessorProvider.get(), dataPacketBuilderProvider.get(), compressionManagerProvider.get(), cryptoBoxProvider.get(), inMemoryBufferProvider.get(), fileQueueManagerProvider.get(), uploadManagerProvider.get(), chunkingManagerProvider.get(), timeSyncProvider.get(), systemMonitorProvider.get(), metricsUploaderProvider.get(), privacyManagerProvider.get(), userIdManagerProvider.get());
  }

  public static SmartTransmissionManager_Factory create(
      Provider<SensorCollector> sensorCollectorProvider,
      Provider<SensorDataProcessor> sensorDataProcessorProvider,
      Provider<DataPacketBuilder> dataPacketBuilderProvider,
      Provider<CompressionManager> compressionManagerProvider,
      Provider<EnvelopeCryptoBox> cryptoBoxProvider,
      Provider<InMemoryBuffer> inMemoryBufferProvider,
      Provider<FileQueueManager> fileQueueManagerProvider,
      Provider<UploadManager> uploadManagerProvider,
      Provider<ChunkingManager> chunkingManagerProvider,
      Provider<EnhancedTimeSync> timeSyncProvider, Provider<SystemMonitor> systemMonitorProvider,
      Provider<MetricsUploader> metricsUploaderProvider,
      Provider<PrivacyManager> privacyManagerProvider,
      Provider<UserIdManager> userIdManagerProvider) {
    return new SmartTransmissionManager_Factory(sensorCollectorProvider, sensorDataProcessorProvider, dataPacketBuilderProvider, compressionManagerProvider, cryptoBoxProvider, inMemoryBufferProvider, fileQueueManagerProvider, uploadManagerProvider, chunkingManagerProvider, timeSyncProvider, systemMonitorProvider, metricsUploaderProvider, privacyManagerProvider, userIdManagerProvider);
  }

  public static SmartTransmissionManager newInstance(SensorCollector sensorCollector,
      SensorDataProcessor sensorDataProcessor, DataPacketBuilder dataPacketBuilder,
      CompressionManager compressionManager, EnvelopeCryptoBox cryptoBox,
      InMemoryBuffer inMemoryBuffer, FileQueueManager fileQueueManager, UploadManager uploadManager,
      ChunkingManager chunkingManager, EnhancedTimeSync timeSync, SystemMonitor systemMonitor,
      MetricsUploader metricsUploader, PrivacyManager privacyManager, UserIdManager userIdManager) {
    return new SmartTransmissionManager(sensorCollector, sensorDataProcessor, dataPacketBuilder, compressionManager, cryptoBox, inMemoryBuffer, fileQueueManager, uploadManager, chunkingManager, timeSync, systemMonitor, metricsUploader, privacyManager, userIdManager);
  }
}
