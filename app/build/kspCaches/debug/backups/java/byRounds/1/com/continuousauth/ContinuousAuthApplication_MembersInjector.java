package com.continuousauth;

import com.continuousauth.time.EnhancedTimeSync;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class ContinuousAuthApplication_MembersInjector implements MembersInjector<ContinuousAuthApplication> {
  private final Provider<EnhancedTimeSync> timeSyncProvider;

  public ContinuousAuthApplication_MembersInjector(Provider<EnhancedTimeSync> timeSyncProvider) {
    this.timeSyncProvider = timeSyncProvider;
  }

  public static MembersInjector<ContinuousAuthApplication> create(
      Provider<EnhancedTimeSync> timeSyncProvider) {
    return new ContinuousAuthApplication_MembersInjector(timeSyncProvider);
  }

  @Override
  public void injectMembers(ContinuousAuthApplication instance) {
    injectTimeSync(instance, timeSyncProvider.get());
  }

  @InjectedFieldSignature("com.continuousauth.ContinuousAuthApplication.timeSync")
  public static void injectTimeSync(ContinuousAuthApplication instance, EnhancedTimeSync timeSync) {
    instance.timeSync = timeSync;
  }
}
