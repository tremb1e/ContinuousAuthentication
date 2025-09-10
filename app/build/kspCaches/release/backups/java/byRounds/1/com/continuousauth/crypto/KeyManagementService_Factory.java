package com.continuousauth.crypto;

import android.content.Context;
import com.continuousauth.network.GrpcManager;
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
public final class KeyManagementService_Factory implements Factory<KeyManagementService> {
  private final Provider<Context> contextProvider;

  private final Provider<GrpcManager> grpcManagerProvider;

  public KeyManagementService_Factory(Provider<Context> contextProvider,
      Provider<GrpcManager> grpcManagerProvider) {
    this.contextProvider = contextProvider;
    this.grpcManagerProvider = grpcManagerProvider;
  }

  @Override
  public KeyManagementService get() {
    return newInstance(contextProvider.get(), grpcManagerProvider.get());
  }

  public static KeyManagementService_Factory create(Provider<Context> contextProvider,
      Provider<GrpcManager> grpcManagerProvider) {
    return new KeyManagementService_Factory(contextProvider, grpcManagerProvider);
  }

  public static KeyManagementService newInstance(Context context, GrpcManager grpcManager) {
    return new KeyManagementService(context, grpcManager);
  }
}
