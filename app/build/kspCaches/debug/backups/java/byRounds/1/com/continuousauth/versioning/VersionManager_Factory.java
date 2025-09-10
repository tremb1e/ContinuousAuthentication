package com.continuousauth.versioning;

import android.content.Context;
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
public final class VersionManager_Factory implements Factory<VersionManager> {
  private final Provider<Context> contextProvider;

  public VersionManager_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public VersionManager get() {
    return newInstance(contextProvider.get());
  }

  public static VersionManager_Factory create(Provider<Context> contextProvider) {
    return new VersionManager_Factory(contextProvider);
  }

  public static VersionManager newInstance(Context context) {
    return new VersionManager(context);
  }
}
