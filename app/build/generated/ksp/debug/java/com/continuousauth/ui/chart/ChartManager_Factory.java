package com.continuousauth.ui.chart;

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
public final class ChartManager_Factory implements Factory<ChartManager> {
  private final Provider<SensorCollector> sensorCollectorProvider;

  public ChartManager_Factory(Provider<SensorCollector> sensorCollectorProvider) {
    this.sensorCollectorProvider = sensorCollectorProvider;
  }

  @Override
  public ChartManager get() {
    return newInstance(sensorCollectorProvider.get());
  }

  public static ChartManager_Factory create(Provider<SensorCollector> sensorCollectorProvider) {
    return new ChartManager_Factory(sensorCollectorProvider);
  }

  public static ChartManager newInstance(SensorCollector sensorCollector) {
    return new ChartManager(sensorCollector);
  }
}
