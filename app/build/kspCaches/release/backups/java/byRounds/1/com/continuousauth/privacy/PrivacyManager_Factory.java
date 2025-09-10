package com.continuousauth.privacy;

import android.content.Context;
import com.continuousauth.database.ContinuousAuthDatabase;
import com.continuousauth.network.GrpcManager;
import com.continuousauth.storage.FileQueueManager;
import com.continuousauth.utils.UserIdManager;
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
public final class PrivacyManager_Factory implements Factory<PrivacyManager> {
  private final Provider<Context> contextProvider;

  private final Provider<ContinuousAuthDatabase> databaseProvider;

  private final Provider<FileQueueManager> fileQueueManagerProvider;

  private final Provider<UserIdManager> userIdManagerProvider;

  private final Provider<GrpcManager> grpcManagerProvider;

  public PrivacyManager_Factory(Provider<Context> contextProvider,
      Provider<ContinuousAuthDatabase> databaseProvider,
      Provider<FileQueueManager> fileQueueManagerProvider,
      Provider<UserIdManager> userIdManagerProvider, Provider<GrpcManager> grpcManagerProvider) {
    this.contextProvider = contextProvider;
    this.databaseProvider = databaseProvider;
    this.fileQueueManagerProvider = fileQueueManagerProvider;
    this.userIdManagerProvider = userIdManagerProvider;
    this.grpcManagerProvider = grpcManagerProvider;
  }

  @Override
  public PrivacyManager get() {
    return newInstance(contextProvider.get(), databaseProvider.get(), fileQueueManagerProvider.get(), userIdManagerProvider.get(), grpcManagerProvider.get());
  }

  public static PrivacyManager_Factory create(Provider<Context> contextProvider,
      Provider<ContinuousAuthDatabase> databaseProvider,
      Provider<FileQueueManager> fileQueueManagerProvider,
      Provider<UserIdManager> userIdManagerProvider, Provider<GrpcManager> grpcManagerProvider) {
    return new PrivacyManager_Factory(contextProvider, databaseProvider, fileQueueManagerProvider, userIdManagerProvider, grpcManagerProvider);
  }

  public static PrivacyManager newInstance(Context context, ContinuousAuthDatabase database,
      FileQueueManager fileQueueManager, UserIdManager userIdManager, GrpcManager grpcManager) {
    return new PrivacyManager(context, database, fileQueueManager, userIdManager, grpcManager);
  }
}
