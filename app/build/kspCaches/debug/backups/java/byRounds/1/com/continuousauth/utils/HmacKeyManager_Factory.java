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
public final class HmacKeyManager_Factory implements Factory<HmacKeyManager> {
  private final Provider<Context> contextProvider;

  public HmacKeyManager_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public HmacKeyManager get() {
    return newInstance(contextProvider.get());
  }

  public static HmacKeyManager_Factory create(Provider<Context> contextProvider) {
    return new HmacKeyManager_Factory(contextProvider);
  }

  public static HmacKeyManager newInstance(Context context) {
    return new HmacKeyManager(context);
  }
}
