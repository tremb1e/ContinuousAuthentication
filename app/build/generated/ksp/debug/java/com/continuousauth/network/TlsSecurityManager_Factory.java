package com.continuousauth.network;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

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
public final class TlsSecurityManager_Factory implements Factory<TlsSecurityManager> {
  @Override
  public TlsSecurityManager get() {
    return newInstance();
  }

  public static TlsSecurityManager_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static TlsSecurityManager newInstance() {
    return new TlsSecurityManager();
  }

  private static final class InstanceHolder {
    private static final TlsSecurityManager_Factory INSTANCE = new TlsSecurityManager_Factory();
  }
}
