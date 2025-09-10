package com.continuousauth.compression;

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
public final class CompressionManager_Factory implements Factory<CompressionManager> {
  @Override
  public CompressionManager get() {
    return newInstance();
  }

  public static CompressionManager_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static CompressionManager newInstance() {
    return new CompressionManager();
  }

  private static final class InstanceHolder {
    private static final CompressionManager_Factory INSTANCE = new CompressionManager_Factory();
  }
}
