package com.continuousauth.network;

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
public final class GrpcManager_Factory implements Factory<GrpcManager> {
  private final Provider<Context> contextProvider;

  public GrpcManager_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public GrpcManager get() {
    return newInstance(contextProvider.get());
  }

  public static GrpcManager_Factory create(Provider<Context> contextProvider) {
    return new GrpcManager_Factory(contextProvider);
  }

  public static GrpcManager newInstance(Context context) {
    return new GrpcManager(context);
  }
}
