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
public final class ServerConnectionTester_Factory implements Factory<ServerConnectionTester> {
  private final Provider<GrpcManager> grpcManagerProvider;

  public ServerConnectionTester_Factory(Provider<GrpcManager> grpcManagerProvider) {
    this.grpcManagerProvider = grpcManagerProvider;
  }

  @Override
  public ServerConnectionTester get() {
    return newInstance(grpcManagerProvider.get());
  }

  public static ServerConnectionTester_Factory create(Provider<GrpcManager> grpcManagerProvider) {
    return new ServerConnectionTester_Factory(grpcManagerProvider);
  }

  public static ServerConnectionTester newInstance(GrpcManager grpcManager) {
    return new ServerConnectionTester(grpcManager);
  }
}
