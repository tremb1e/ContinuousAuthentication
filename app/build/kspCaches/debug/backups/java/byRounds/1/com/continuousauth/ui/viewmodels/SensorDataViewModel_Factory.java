package com.continuousauth.ui.viewmodels;

import com.continuousauth.sensor.SensorCollector;
import com.continuousauth.utils.ForegroundAppDetector;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
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
public final class SensorDataViewModel_Factory implements Factory<SensorDataViewModel> {
  private final Provider<SensorCollector> sensorCollectorProvider;

  private final Provider<ForegroundAppDetector> foregroundAppDetectorProvider;

  public SensorDataViewModel_Factory(Provider<SensorCollector> sensorCollectorProvider,
      Provider<ForegroundAppDetector> foregroundAppDetectorProvider) {
    this.sensorCollectorProvider = sensorCollectorProvider;
    this.foregroundAppDetectorProvider = foregroundAppDetectorProvider;
  }

  @Override
  public SensorDataViewModel get() {
    return newInstance(sensorCollectorProvider.get(), foregroundAppDetectorProvider.get());
  }

  public static SensorDataViewModel_Factory create(
      Provider<SensorCollector> sensorCollectorProvider,
      Provider<ForegroundAppDetector> foregroundAppDetectorProvider) {
    return new SensorDataViewModel_Factory(sensorCollectorProvider, foregroundAppDetectorProvider);
  }

  public static SensorDataViewModel newInstance(SensorCollector sensorCollector,
      ForegroundAppDetector foregroundAppDetector) {
    return new SensorDataViewModel(sensorCollector, foregroundAppDetector);
  }
}
