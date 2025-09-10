package com.continuousauth.buffer;

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
public final class RingBuffer_Factory implements Factory<RingBuffer> {
  @Override
  public RingBuffer get() {
    return newInstance();
  }

  public static RingBuffer_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static RingBuffer newInstance() {
    return new RingBuffer();
  }

  private static final class InstanceHolder {
    private static final RingBuffer_Factory INSTANCE = new RingBuffer_Factory();
  }
}
