package com.continuousauth.ui.fragments;

import com.continuousauth.ui.chart.ChartManager;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class SensorDataFragment_MembersInjector implements MembersInjector<SensorDataFragment> {
  private final Provider<ChartManager> chartManagerProvider;

  public SensorDataFragment_MembersInjector(Provider<ChartManager> chartManagerProvider) {
    this.chartManagerProvider = chartManagerProvider;
  }

  public static MembersInjector<SensorDataFragment> create(
      Provider<ChartManager> chartManagerProvider) {
    return new SensorDataFragment_MembersInjector(chartManagerProvider);
  }

  @Override
  public void injectMembers(SensorDataFragment instance) {
    injectChartManager(instance, chartManagerProvider.get());
  }

  @InjectedFieldSignature("com.continuousauth.ui.fragments.SensorDataFragment.chartManager")
  public static void injectChartManager(SensorDataFragment instance, ChartManager chartManager) {
    instance.chartManager = chartManager;
  }
}
