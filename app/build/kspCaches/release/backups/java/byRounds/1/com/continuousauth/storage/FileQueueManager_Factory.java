package com.continuousauth.storage;

import android.content.Context;
import com.continuousauth.database.BatchMetadataDao;
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
public final class FileQueueManager_Factory implements Factory<FileQueueManager> {
  private final Provider<Context> contextProvider;

  private final Provider<BatchMetadataDao> batchMetadataDaoProvider;

  public FileQueueManager_Factory(Provider<Context> contextProvider,
      Provider<BatchMetadataDao> batchMetadataDaoProvider) {
    this.contextProvider = contextProvider;
    this.batchMetadataDaoProvider = batchMetadataDaoProvider;
  }

  @Override
  public FileQueueManager get() {
    return newInstance(contextProvider.get(), batchMetadataDaoProvider.get());
  }

  public static FileQueueManager_Factory create(Provider<Context> contextProvider,
      Provider<BatchMetadataDao> batchMetadataDaoProvider) {
    return new FileQueueManager_Factory(contextProvider, batchMetadataDaoProvider);
  }

  public static FileQueueManager newInstance(Context context, BatchMetadataDao batchMetadataDao) {
    return new FileQueueManager(context, batchMetadataDao);
  }
}
