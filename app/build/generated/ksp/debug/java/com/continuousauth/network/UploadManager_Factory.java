package com.continuousauth.network;

import com.continuousauth.buffer.InMemoryBuffer;
import com.continuousauth.policy.PolicyManager;
import com.continuousauth.storage.FileQueueManager;
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
public final class UploadManager_Factory implements Factory<UploadManager> {
  private final Provider<Uploader> uploaderProvider;

  private final Provider<InMemoryBuffer> inMemoryBufferProvider;

  private final Provider<PolicyManager> policyManagerProvider;

  private final Provider<FileQueueManager> fileQueueManagerProvider;

  private final Provider<NetworkEnvironmentDetector> networkEnvironmentDetectorProvider;

  public UploadManager_Factory(Provider<Uploader> uploaderProvider,
      Provider<InMemoryBuffer> inMemoryBufferProvider,
      Provider<PolicyManager> policyManagerProvider,
      Provider<FileQueueManager> fileQueueManagerProvider,
      Provider<NetworkEnvironmentDetector> networkEnvironmentDetectorProvider) {
    this.uploaderProvider = uploaderProvider;
    this.inMemoryBufferProvider = inMemoryBufferProvider;
    this.policyManagerProvider = policyManagerProvider;
    this.fileQueueManagerProvider = fileQueueManagerProvider;
    this.networkEnvironmentDetectorProvider = networkEnvironmentDetectorProvider;
  }

  @Override
  public UploadManager get() {
    return newInstance(uploaderProvider.get(), inMemoryBufferProvider.get(), policyManagerProvider.get(), fileQueueManagerProvider.get(), networkEnvironmentDetectorProvider.get());
  }

  public static UploadManager_Factory create(Provider<Uploader> uploaderProvider,
      Provider<InMemoryBuffer> inMemoryBufferProvider,
      Provider<PolicyManager> policyManagerProvider,
      Provider<FileQueueManager> fileQueueManagerProvider,
      Provider<NetworkEnvironmentDetector> networkEnvironmentDetectorProvider) {
    return new UploadManager_Factory(uploaderProvider, inMemoryBufferProvider, policyManagerProvider, fileQueueManagerProvider, networkEnvironmentDetectorProvider);
  }

  public static UploadManager newInstance(Uploader uploader, InMemoryBuffer inMemoryBuffer,
      PolicyManager policyManager, FileQueueManager fileQueueManager,
      NetworkEnvironmentDetector networkEnvironmentDetector) {
    return new UploadManager(uploader, inMemoryBuffer, policyManager, fileQueueManager, networkEnvironmentDetector);
  }
}
