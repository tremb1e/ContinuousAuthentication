package com.continuousauth.time;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;
import kotlinx.coroutines.CoroutineScope;

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
public final class EnhancedTimeSync_Factory implements Factory<EnhancedTimeSync> {
  private final Provider<CoroutineScope> coroutineScopeProvider;

  public EnhancedTimeSync_Factory(Provider<CoroutineScope> coroutineScopeProvider) {
    this.coroutineScopeProvider = coroutineScopeProvider;
  }

  @Override
  public EnhancedTimeSync get() {
    return newInstance(coroutineScopeProvider.get());
  }

  public static EnhancedTimeSync_Factory create(Provider<CoroutineScope> coroutineScopeProvider) {
    return new EnhancedTimeSync_Factory(coroutineScopeProvider);
  }

  public static EnhancedTimeSync newInstance(CoroutineScope coroutineScope) {
    return new EnhancedTimeSync(coroutineScope);
  }
}
