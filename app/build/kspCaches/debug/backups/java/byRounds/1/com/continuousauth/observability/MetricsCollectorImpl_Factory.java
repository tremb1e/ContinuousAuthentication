package com.continuousauth.observability;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

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
public final class MetricsCollectorImpl_Factory implements Factory<MetricsCollectorImpl> {
  @Override
  public MetricsCollectorImpl get() {
    return newInstance();
  }

  public static MetricsCollectorImpl_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static MetricsCollectorImpl newInstance() {
    return new MetricsCollectorImpl();
  }

  private static final class InstanceHolder {
    private static final MetricsCollectorImpl_Factory INSTANCE = new MetricsCollectorImpl_Factory();
  }
}
