package com.continuousauth.network;

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
public final class ErrorHandler_Factory implements Factory<ErrorHandler> {
  @Override
  public ErrorHandler get() {
    return newInstance();
  }

  public static ErrorHandler_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static ErrorHandler newInstance() {
    return new ErrorHandler();
  }

  private static final class InstanceHolder {
    private static final ErrorHandler_Factory INSTANCE = new ErrorHandler_Factory();
  }
}
