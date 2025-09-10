package com.continuousauth.policy;

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
public final class PolicyManager_Factory implements Factory<PolicyManager> {
  private final Provider<Context> contextProvider;

  public PolicyManager_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public PolicyManager get() {
    return newInstance(contextProvider.get());
  }

  public static PolicyManager_Factory create(Provider<Context> contextProvider) {
    return new PolicyManager_Factory(contextProvider);
  }

  public static PolicyManager newInstance(Context context) {
    return new PolicyManager(context);
  }
}
