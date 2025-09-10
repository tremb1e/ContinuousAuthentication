package com.continuousauth.monitor;

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
public final class MemoryMonitor_Factory implements Factory<MemoryMonitor> {
  @Override
  public MemoryMonitor get() {
    return newInstance();
  }

  public static MemoryMonitor_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static MemoryMonitor newInstance() {
    return new MemoryMonitor();
  }

  private static final class InstanceHolder {
    private static final MemoryMonitor_Factory INSTANCE = new MemoryMonitor_Factory();
  }
}
