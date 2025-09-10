package com.continuousauth.observability;

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
public final class MetricsUploader_Factory implements Factory<MetricsUploader> {
  private final Provider<MetricsCollectorImpl> metricsCollectorProvider;

  private final Provider<PerformanceMonitorImpl> performanceMonitorProvider;

  private final Provider<UserIdManager> userIdManagerProvider;

  public MetricsUploader_Factory(Provider<MetricsCollectorImpl> metricsCollectorProvider,
      Provider<PerformanceMonitorImpl> performanceMonitorProvider,
      Provider<UserIdManager> userIdManagerProvider) {
    this.metricsCollectorProvider = metricsCollectorProvider;
    this.performanceMonitorProvider = performanceMonitorProvider;
    this.userIdManagerProvider = userIdManagerProvider;
  }

  @Override
  public MetricsUploader get() {
    return newInstance(metricsCollectorProvider.get(), performanceMonitorProvider.get(), userIdManagerProvider.get());
  }

  public static MetricsUploader_Factory create(
      Provider<MetricsCollectorImpl> metricsCollectorProvider,
      Provider<PerformanceMonitorImpl> performanceMonitorProvider,
      Provider<UserIdManager> userIdManagerProvider) {
    return new MetricsUploader_Factory(metricsCollectorProvider, performanceMonitorProvider, userIdManagerProvider);
  }

  public static MetricsUploader newInstance(MetricsCollectorImpl metricsCollector,
      PerformanceMonitorImpl performanceMonitor, UserIdManager userIdManager) {
    return new MetricsUploader(metricsCollector, performanceMonitor, userIdManager);
  }
}
