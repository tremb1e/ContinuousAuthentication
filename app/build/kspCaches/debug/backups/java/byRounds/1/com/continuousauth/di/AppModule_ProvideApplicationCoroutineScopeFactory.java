package com.continuousauth.di;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
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
public final class AppModule_ProvideApplicationCoroutineScopeFactory implements Factory<CoroutineScope> {
  @Override
  public CoroutineScope get() {
    return provideApplicationCoroutineScope();
  }

  public static AppModule_ProvideApplicationCoroutineScopeFactory create() {
    return InstanceHolder.INSTANCE;
  }

  public static CoroutineScope provideApplicationCoroutineScope() {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideApplicationCoroutineScope());
  }

  private static final class InstanceHolder {
    private static final AppModule_ProvideApplicationCoroutineScopeFactory INSTANCE = new AppModule_ProvideApplicationCoroutineScopeFactory();
  }
}
