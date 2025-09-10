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
public final class InMemoryBufferImpl_Factory implements Factory<InMemoryBufferImpl> {
  @Override
  public InMemoryBufferImpl get() {
    return newInstance();
  }

  public static InMemoryBufferImpl_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static InMemoryBufferImpl newInstance() {
    return new InMemoryBufferImpl();
  }

  private static final class InstanceHolder {
    private static final InMemoryBufferImpl_Factory INSTANCE = new InMemoryBufferImpl_Factory();
  }
}
