package com.continuousauth.observability;

import android.content.Context;
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
public final class PerformanceMonitorImpl_Factory implements Factory<PerformanceMonitorImpl> {
  private final Provider<Context> contextProvider;

  public PerformanceMonitorImpl_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public PerformanceMonitorImpl get() {
    return newInstance(contextProvider.get());
  }

  public static PerformanceMonitorImpl_Factory create(Provider<Context> contextProvider) {
    return new PerformanceMonitorImpl_Factory(contextProvider);
  }

  public static PerformanceMonitorImpl newInstance(Context context) {
    return new PerformanceMonitorImpl(context);
  }
}
