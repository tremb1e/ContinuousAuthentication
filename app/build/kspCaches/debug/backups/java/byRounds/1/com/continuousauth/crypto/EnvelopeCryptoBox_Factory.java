package com.continuousauth.crypto;

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
public final class EnvelopeCryptoBox_Factory implements Factory<EnvelopeCryptoBox> {
  private final Provider<Context> contextProvider;

  public EnvelopeCryptoBox_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public EnvelopeCryptoBox get() {
    return newInstance(contextProvider.get());
  }

  public static EnvelopeCryptoBox_Factory create(Provider<Context> contextProvider) {
    return new EnvelopeCryptoBox_Factory(contextProvider);
  }

  public static EnvelopeCryptoBox newInstance(Context context) {
    return new EnvelopeCryptoBox(context);
  }
}
