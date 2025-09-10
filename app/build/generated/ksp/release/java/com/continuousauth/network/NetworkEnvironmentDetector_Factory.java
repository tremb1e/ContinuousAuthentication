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
public final class NetworkEnvironmentDetector_Factory implements Factory<NetworkEnvironmentDetector> {
  private final Provider<Context> contextProvider;

  public NetworkEnvironmentDetector_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public NetworkEnvironmentDetector get() {
    return newInstance(contextProvider.get());
  }

  public static NetworkEnvironmentDetector_Factory create(Provider<Context> contextProvider) {
    return new NetworkEnvironmentDetector_Factory(contextProvider);
  }

  public static NetworkEnvironmentDetector newInstance(Context context) {
    return new NetworkEnvironmentDetector(context);
  }
}
