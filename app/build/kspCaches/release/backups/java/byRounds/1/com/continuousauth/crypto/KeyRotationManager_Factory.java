package com.continuousauth.crypto;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class KeyRotationManager_Factory implements Factory<KeyRotationManager> {
  private final Provider<CryptoBox> cryptoBoxProvider;

  public KeyRotationManager_Factory(Provider<CryptoBox> cryptoBoxProvider) {
    this.cryptoBoxProvider = cryptoBoxProvider;
  }

  @Override
  public KeyRotationManager get() {
    return newInstance(cryptoBoxProvider.get());
  }

  public static KeyRotationManager_Factory create(Provider<CryptoBox> cryptoBoxProvider) {
    return new KeyRotationManager_Factory(cryptoBoxProvider);
  }

  public static KeyRotationManager newInstance(CryptoBox cryptoBox) {
    return new KeyRotationManager(cryptoBox);
  }
}
