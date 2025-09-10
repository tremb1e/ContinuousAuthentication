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
public final class AADBuilder_Factory implements Factory<AADBuilder> {
  private final Provider<Context> contextProvider;

  private final Provider<EnvelopeCryptoBox> envelopeCryptoBoxProvider;

  public AADBuilder_Factory(Provider<Context> contextProvider,
      Provider<EnvelopeCryptoBox> envelopeCryptoBoxProvider) {
    this.contextProvider = contextProvider;
    this.envelopeCryptoBoxProvider = envelopeCryptoBoxProvider;
  }

  @Override
  public AADBuilder get() {
    return newInstance(contextProvider.get(), envelopeCryptoBoxProvider.get());
  }

  public static AADBuilder_Factory create(Provider<Context> contextProvider,
      Provider<EnvelopeCryptoBox> envelopeCryptoBoxProvider) {
    return new AADBuilder_Factory(contextProvider, envelopeCryptoBoxProvider);
  }

  public static AADBuilder newInstance(Context context, EnvelopeCryptoBox envelopeCryptoBox) {
    return new AADBuilder(context, envelopeCryptoBox);
  }
}
