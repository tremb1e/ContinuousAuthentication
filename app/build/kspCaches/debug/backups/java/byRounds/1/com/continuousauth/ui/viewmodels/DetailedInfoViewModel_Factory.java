package com.continuousauth.ui.viewmodels;

import android.content.Context;
import com.continuousauth.monitor.SystemMonitor;
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
public final class DetailedInfoViewModel_Factory implements Factory<DetailedInfoViewModel> {
  private final Provider<Context> contextProvider;

  private final Provider<SystemMonitor> systemMonitorProvider;

  public DetailedInfoViewModel_Factory(Provider<Context> contextProvider,
      Provider<SystemMonitor> systemMonitorProvider) {
    this.contextProvider = contextProvider;
    this.systemMonitorProvider = systemMonitorProvider;
  }

  @Override
  public DetailedInfoViewModel get() {
    return newInstance(contextProvider.get(), systemMonitorProvider.get());
  }

  public static DetailedInfoViewModel_Factory create(Provider<Context> contextProvider,
      Provider<SystemMonitor> systemMonitorProvider) {
    return new DetailedInfoViewModel_Factory(contextProvider, systemMonitorProvider);
  }

  public static DetailedInfoViewModel newInstance(Context context, SystemMonitor systemMonitor) {
    return new DetailedInfoViewModel(context, systemMonitor);
  }
}
