package com.continuousauth.di;

import android.content.Context;
import com.continuousauth.database.ContinuousAuthDatabase;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
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
public final class DatabaseModule_ProvideContinuousAuthDatabaseFactory implements Factory<ContinuousAuthDatabase> {
  private final Provider<Context> contextProvider;

  public DatabaseModule_ProvideContinuousAuthDatabaseFactory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public ContinuousAuthDatabase get() {
    return provideContinuousAuthDatabase(contextProvider.get());
  }

  public static DatabaseModule_ProvideContinuousAuthDatabaseFactory create(
      Provider<Context> contextProvider) {
    return new DatabaseModule_ProvideContinuousAuthDatabaseFactory(contextProvider);
  }

  public static ContinuousAuthDatabase provideContinuousAuthDatabase(Context context) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.provideContinuousAuthDatabase(context));
  }
}
