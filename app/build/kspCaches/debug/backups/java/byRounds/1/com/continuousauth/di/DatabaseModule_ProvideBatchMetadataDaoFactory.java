package com.continuousauth.di;

import com.continuousauth.database.BatchMetadataDao;
import com.continuousauth.database.ContinuousAuthDatabase;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
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
public final class DatabaseModule_ProvideBatchMetadataDaoFactory implements Factory<BatchMetadataDao> {
  private final Provider<ContinuousAuthDatabase> databaseProvider;

  public DatabaseModule_ProvideBatchMetadataDaoFactory(
      Provider<ContinuousAuthDatabase> databaseProvider) {
    this.databaseProvider = databaseProvider;
  }

  @Override
  public BatchMetadataDao get() {
    return provideBatchMetadataDao(databaseProvider.get());
  }

  public static DatabaseModule_ProvideBatchMetadataDaoFactory create(
      Provider<ContinuousAuthDatabase> databaseProvider) {
    return new DatabaseModule_ProvideBatchMetadataDaoFactory(databaseProvider);
  }

  public static BatchMetadataDao provideBatchMetadataDao(ContinuousAuthDatabase database) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.provideBatchMetadataDao(database));
  }
}
