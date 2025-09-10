package com.continuousauth.ui;

import android.content.Context;
import com.continuousauth.monitor.MemoryMonitor;
import com.continuousauth.network.NetworkEnvironmentDetector;
import com.continuousauth.network.ServerConnectionTester;
import com.continuousauth.network.TlsSecurityManager;
import com.continuousauth.network.UploadManager;
import com.continuousauth.observability.MetricsCollectorImpl;
import com.continuousauth.observability.PerformanceMonitorImpl;
import com.continuousauth.pool.SensorEventPool;
import com.continuousauth.privacy.PrivacyManager;
import com.continuousauth.storage.FileQueueManager;
import com.continuousauth.utils.UserIdManager;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
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
public final class MainViewModel_Factory implements Factory<MainViewModel> {
  private final Provider<Context> contextProvider;

  private final Provider<UploadManager> uploadManagerProvider;

  private final Provider<NetworkEnvironmentDetector> networkEnvironmentDetectorProvider;

  private final Provider<MetricsCollectorImpl> metricsCollectorProvider;

  private final Provider<PerformanceMonitorImpl> performanceMonitorProvider;

  private final Provider<MemoryMonitor> memoryMonitorProvider;

  private final Provider<SensorEventPool> sensorEventPoolProvider;

  private final Provider<UserIdManager> userIdManagerProvider;

  private final Provider<ServerConnectionTester> serverConnectionTesterProvider;

  private final Provider<FileQueueManager> fileQueueManagerProvider;

  private final Provider<TlsSecurityManager> tlsSecurityManagerProvider;

  private final Provider<PrivacyManager> privacyManagerProvider;

  public MainViewModel_Factory(Provider<Context> contextProvider,
      Provider<UploadManager> uploadManagerProvider,
      Provider<NetworkEnvironmentDetector> networkEnvironmentDetectorProvider,
      Provider<MetricsCollectorImpl> metricsCollectorProvider,
      Provider<PerformanceMonitorImpl> performanceMonitorProvider,
      Provider<MemoryMonitor> memoryMonitorProvider,
      Provider<SensorEventPool> sensorEventPoolProvider,
      Provider<UserIdManager> userIdManagerProvider,
      Provider<ServerConnectionTester> serverConnectionTesterProvider,
      Provider<FileQueueManager> fileQueueManagerProvider,
      Provider<TlsSecurityManager> tlsSecurityManagerProvider,
      Provider<PrivacyManager> privacyManagerProvider) {
    this.contextProvider = contextProvider;
    this.uploadManagerProvider = uploadManagerProvider;
    this.networkEnvironmentDetectorProvider = networkEnvironmentDetectorProvider;
    this.metricsCollectorProvider = metricsCollectorProvider;
    this.performanceMonitorProvider = performanceMonitorProvider;
    this.memoryMonitorProvider = memoryMonitorProvider;
    this.sensorEventPoolProvider = sensorEventPoolProvider;
    this.userIdManagerProvider = userIdManagerProvider;
    this.serverConnectionTesterProvider = serverConnectionTesterProvider;
    this.fileQueueManagerProvider = fileQueueManagerProvider;
    this.tlsSecurityManagerProvider = tlsSecurityManagerProvider;
    this.privacyManagerProvider = privacyManagerProvider;
  }

  @Override
  public MainViewModel get() {
    return newInstance(contextProvider.get(), uploadManagerProvider.get(), networkEnvironmentDetectorProvider.get(), metricsCollectorProvider.get(), performanceMonitorProvider.get(), memoryMonitorProvider.get(), sensorEventPoolProvider.get(), userIdManagerProvider.get(), serverConnectionTesterProvider.get(), fileQueueManagerProvider.get(), tlsSecurityManagerProvider.get(), privacyManagerProvider.get());
  }

  public static MainViewModel_Factory create(Provider<Context> contextProvider,
      Provider<UploadManager> uploadManagerProvider,
      Provider<NetworkEnvironmentDetector> networkEnvironmentDetectorProvider,
      Provider<MetricsCollectorImpl> metricsCollectorProvider,
      Provider<PerformanceMonitorImpl> performanceMonitorProvider,
      Provider<MemoryMonitor> memoryMonitorProvider,
      Provider<SensorEventPool> sensorEventPoolProvider,
      Provider<UserIdManager> userIdManagerProvider,
      Provider<ServerConnectionTester> serverConnectionTesterProvider,
      Provider<FileQueueManager> fileQueueManagerProvider,
      Provider<TlsSecurityManager> tlsSecurityManagerProvider,
      Provider<PrivacyManager> privacyManagerProvider) {
    return new MainViewModel_Factory(contextProvider, uploadManagerProvider, networkEnvironmentDetectorProvider, metricsCollectorProvider, performanceMonitorProvider, memoryMonitorProvider, sensorEventPoolProvider, userIdManagerProvider, serverConnectionTesterProvider, fileQueueManagerProvider, tlsSecurityManagerProvider, privacyManagerProvider);
  }

  public static MainViewModel newInstance(Context context, UploadManager uploadManager,
      NetworkEnvironmentDetector networkEnvironmentDetector, MetricsCollectorImpl metricsCollector,
      PerformanceMonitorImpl performanceMonitor, MemoryMonitor memoryMonitor,
      SensorEventPool sensorEventPool, UserIdManager userIdManager,
      ServerConnectionTester serverConnectionTester, FileQueueManager fileQueueManager,
      TlsSecurityManager tlsSecurityManager, PrivacyManager privacyManager) {
    return new MainViewModel(context, uploadManager, networkEnvironmentDetector, metricsCollector, performanceMonitor, memoryMonitor, sensorEventPool, userIdManager, serverConnectionTester, fileQueueManager, tlsSecurityManager, privacyManager);
  }
}
