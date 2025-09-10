package com.continuousauth.network;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class ConnectionManager_Factory implements Factory<ConnectionManager> {
  private final Provider<GrpcManager> grpcManagerProvider;

  private final Provider<UploadManager> uploadManagerProvider;

  public ConnectionManager_Factory(Provider<GrpcManager> grpcManagerProvider,
      Provider<UploadManager> uploadManagerProvider) {
    this.grpcManagerProvider = grpcManagerProvider;
    this.uploadManagerProvider = uploadManagerProvider;
  }

  @Override
  public ConnectionManager get() {
    return newInstance(grpcManagerProvider.get(), uploadManagerProvider.get());
  }

  public static ConnectionManager_Factory create(Provider<GrpcManager> grpcManagerProvider,
      Provider<UploadManager> uploadManagerProvider) {
    return new ConnectionManager_Factory(grpcManagerProvider, uploadManagerProvider);
  }

  public static ConnectionManager newInstance(GrpcManager grpcManager,
      UploadManager uploadManager) {
    return new ConnectionManager(grpcManager, uploadManager);
  }
}
