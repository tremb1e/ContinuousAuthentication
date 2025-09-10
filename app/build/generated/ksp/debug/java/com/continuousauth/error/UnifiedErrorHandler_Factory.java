package com.continuousauth.error;

import com.continuousauth.crypto.KeyManagementService;
import com.continuousauth.network.ConnectionManager;
import com.continuousauth.network.UploadManager;
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
public final class UnifiedErrorHandler_Factory implements Factory<UnifiedErrorHandler> {
  private final Provider<ConnectionManager> connectionManagerProvider;

  private final Provider<UploadManager> uploadManagerProvider;

  private final Provider<FileQueueManager> fileQueueManagerProvider;

  private final Provider<KeyManagementService> keyManagementServiceProvider;

  public UnifiedErrorHandler_Factory(Provider<ConnectionManager> connectionManagerProvider,
      Provider<UploadManager> uploadManagerProvider,
      Provider<FileQueueManager> fileQueueManagerProvider,
      Provider<KeyManagementService> keyManagementServiceProvider) {
    this.connectionManagerProvider = connectionManagerProvider;
    this.uploadManagerProvider = uploadManagerProvider;
    this.fileQueueManagerProvider = fileQueueManagerProvider;
    this.keyManagementServiceProvider = keyManagementServiceProvider;
  }

  @Override
  public UnifiedErrorHandler get() {
    return newInstance(connectionManagerProvider.get(), uploadManagerProvider.get(), fileQueueManagerProvider.get(), keyManagementServiceProvider.get());
  }

  public static UnifiedErrorHandler_Factory create(
      Provider<ConnectionManager> connectionManagerProvider,
      Provider<UploadManager> uploadManagerProvider,
      Provider<FileQueueManager> fileQueueManagerProvider,
      Provider<KeyManagementService> keyManagementServiceProvider) {
    return new UnifiedErrorHandler_Factory(connectionManagerProvider, uploadManagerProvider, fileQueueManagerProvider, keyManagementServiceProvider);
  }

  public static UnifiedErrorHandler newInstance(ConnectionManager connectionManager,
      UploadManager uploadManager, FileQueueManager fileQueueManager,
      KeyManagementService keyManagementService) {
    return new UnifiedErrorHandler(connectionManager, uploadManager, fileQueueManager, keyManagementService);
  }
}
