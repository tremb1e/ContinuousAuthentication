package com.continuousauth.utils;

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
public final class UserIdManager_Factory implements Factory<UserIdManager> {
  private final Provider<Context> contextProvider;

  public UserIdManager_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public UserIdManager get() {
    return newInstance(contextProvider.get());
  }

  public static UserIdManager_Factory create(Provider<Context> contextProvider) {
    return new UserIdManager_Factory(contextProvider);
  }

  public static UserIdManager newInstance(Context context) {
    return new UserIdManager(context);
  }
}
