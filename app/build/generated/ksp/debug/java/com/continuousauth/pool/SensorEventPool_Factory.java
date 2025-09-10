package com.continuousauth.pool;

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
public final class SensorEventPool_Factory implements Factory<SensorEventPool> {
  @Override
  public SensorEventPool get() {
    return newInstance();
  }

  public static SensorEventPool_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static SensorEventPool newInstance() {
    return new SensorEventPool();
  }

  private static final class InstanceHolder {
    private static final SensorEventPool_Factory INSTANCE = new SensorEventPool_Factory();
  }
}
