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
public final class ResumableUploader_Factory implements Factory<ResumableUploader> {
  private final Provider<Uploader> uploaderProvider;

  private final Provider<NetworkEnvironmentDetector> networkEnvironmentDetectorProvider;

  private final Provider<ErrorHandler> errorHandlerProvider;

  public ResumableUploader_Factory(Provider<Uploader> uploaderProvider,
      Provider<NetworkEnvironmentDetector> networkEnvironmentDetectorProvider,
      Provider<ErrorHandler> errorHandlerProvider) {
    this.uploaderProvider = uploaderProvider;
    this.networkEnvironmentDetectorProvider = networkEnvironmentDetectorProvider;
    this.errorHandlerProvider = errorHandlerProvider;
  }

  @Override
  public ResumableUploader get() {
    return newInstance(uploaderProvider.get(), networkEnvironmentDetectorProvider.get(), errorHandlerProvider.get());
  }

  public static ResumableUploader_Factory create(Provider<Uploader> uploaderProvider,
      Provider<NetworkEnvironmentDetector> networkEnvironmentDetectorProvider,
      Provider<ErrorHandler> errorHandlerProvider) {
    return new ResumableUploader_Factory(uploaderProvider, networkEnvironmentDetectorProvider, errorHandlerProvider);
  }

  public static ResumableUploader newInstance(Uploader uploader,
      NetworkEnvironmentDetector networkEnvironmentDetector, ErrorHandler errorHandler) {
    return new ResumableUploader(uploader, networkEnvironmentDetector, errorHandler);
  }
}
